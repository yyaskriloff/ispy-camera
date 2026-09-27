package com.ispy.camera

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var cameraManager: CameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraId: String? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var surfaceReady = false

    private var frameInspector: FrameInspector? = null
    private var h264Encoder: H264Encoder? = null
    private var streamSize: Size? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        surfaceView = SurfaceView(this)
        setContentView(surfaceView)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraId = findBackCameraId()

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                ensurePreviewSizeThenOpen()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                surfaceReady = true
                Log.i(TAG, "Preview surfaceChanged ${width}x${height}")
                openCameraIfReady()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                closeCamera()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        ensurePreviewSizeThenOpen()
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun findBackCameraId(): String? {
        for (id in cameraManager.cameraIdList) {
            val facing = cameraManager
                .getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
        }
        return cameraManager.cameraIdList.firstOrNull()
    }

    /** Pick stream size and setFixedSize on the UI thread before opening the camera. */
    private fun ensurePreviewSizeThenOpen() {
        val id = cameraId ?: return
        if (streamSize == null) {
            val map = cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map == null) {
                Log.e(TAG, "No stream configuration map")
                return
            }
            val size = chooseStreamSize(map)
            streamSize = size
            Log.i(TAG, "Chosen stream size: ${size.width}x${size.height}")
            surfaceView.holder.setFixedSize(size.width, size.height)
            // Surface will recreate; openCameraIfReady runs from surfaceChanged.
            return
        }
        openCameraIfReady()
    }

    @SuppressLint("MissingPermission")
    private fun openCameraIfReady() {
        val id = cameraId ?: return
        val size = streamSize ?: return
        if (!surfaceReady || cameraDevice != null) return

        val frame = surfaceView.holder.surfaceFrame
        if (frame.width() != size.width || frame.height() != size.height) {
            Log.i(
                TAG,
                "Waiting for preview surface ${size.width}x${size.height} " +
                    "(now ${frame.width()}x${frame.height()})",
            )
            return
        }

        try {
            cameraManager.openCamera(id, stateCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
            Toast.makeText(this, "Failed to open camera", Toast.LENGTH_LONG).show()
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            startPipeline(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            closePipeline()
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "Camera error: $error")
            closePipeline()
            camera.close()
            cameraDevice = null
        }
    }

    private fun startPipeline(camera: CameraDevice) {
        val previewSurface: Surface = surfaceView.holder.surface
        if (!previewSurface.isValid) return

        val id = cameraId ?: return
        val characteristics = cameraManager.getCameraCharacteristics(id)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: run {
                Log.e(TAG, "No stream configuration map")
                return
            }

        val size = streamSize ?: chooseStreamSize(map).also { streamSize = it }
        Log.i(TAG, "Starting pipeline at ${size.width}x${size.height}")

        val handler = backgroundHandler ?: return

        try {
            val inspector = FrameInspector(size, handler).also { it.start() }
            frameInspector = inspector

            val encoder = H264Encoder(size).also { it.start() }
            h264Encoder = encoder

            val targets = listOf(previewSurface, inspector.surface, encoder.surface)
            val requestBuilder =
                camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(previewSurface)
                    addTarget(inspector.surface)
                    addTarget(encoder.surface)
                }

            camera.createCaptureSession(
                targets,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        try {
                            session.setRepeatingRequest(
                                requestBuilder.build(),
                                null,
                                backgroundHandler,
                            )
                            Log.i(
                                TAG,
                                "Capture session started: preview + ImageReader + H264 encoder",
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start repeating request", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed")
                        Toast.makeText(
                            this@MainActivity,
                            "Failed to start camera pipeline",
                            Toast.LENGTH_LONG,
                        ).show()
                        closePipeline()
                    }
                },
                backgroundHandler,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture session / pipeline", e)
            closePipeline()
        }
    }

    /**
     * Prefer ~1280x720 that is supported for SurfaceView preview, YUV_420_888
     * ImageReader, and MediaCodec AVC Surface input.
     */
    private fun chooseStreamSize(map: StreamConfigurationMap): Size {
        val previewSizes = map.getOutputSizes(SurfaceHolder::class.java)?.toSet().orEmpty()
        val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888)?.toSet().orEmpty()
        val encoderSizes = map.getOutputSizes(MediaCodec::class.java)?.toSet()
            ?: encoderCapableSizesFallback()

        val common = previewSizes.intersect(yuvSizes).intersect(encoderSizes.toSet())
        if (common.isEmpty()) {
            Log.w(TAG, "No common size across preview/YUV/encoder; falling back to preview sizes")
            return pickClosest(previewSizes.ifEmpty { yuvSizes }, TARGET_WIDTH, TARGET_HEIGHT)
        }
        return pickClosest(common, TARGET_WIDTH, TARGET_HEIGHT)
    }

    private fun encoderCapableSizesFallback(): Set<Size> {
        // When getOutputSizes(MediaCodec) is null, still try common 16:9 sizes the encoder accepts.
        return setOf(
            Size(1920, 1080),
            Size(1280, 720),
            Size(640, 480),
        ).filter { size ->
            try {
                val format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    size.width,
                    size.height,
                ).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                try {
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    true
                } finally {
                    codec.release()
                }
            } catch (_: Exception) {
                false
            }
        }.toSet()
    }

    private fun pickClosest(sizes: Collection<Size>, targetW: Int, targetH: Int): Size {
        return sizes.minByOrNull { size ->
            abs(size.width * size.height - targetW * targetH)
        } ?: Size(targetW, targetH)
    }

    private fun closeCamera() {
        try {
            captureSession?.stopRepeating()
        } catch (_: Exception) {
        }
        captureSession?.close()
        captureSession = null

        closePipeline()

        cameraDevice?.close()
        cameraDevice = null
    }

    private fun closePipeline() {
        h264Encoder?.stop()
        h264Encoder = null
        frameInspector?.release()
        frameInspector = null
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while stopping background thread", e)
        }
        backgroundThread = null
        backgroundHandler = null
    }

    companion object {
        private const val TAG = "iSpyCamera"
        private const val TARGET_WIDTH = 1280
        private const val TARGET_HEIGHT = 720
    }
}
