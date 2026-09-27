package com.ispy.camera.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import org.webrtc.Camera2Capturer
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns Camera2 capturer lifecycle. Preview is attached via [org.webrtc.VideoTrack] sinks
 * in [com.ispy.camera.webrtc.WebRtcClient], not here.
 */
class CameraController(
    private val context: Context,
) {
    private var capturer: CameraVideoCapturer? = null
    private val running = AtomicBoolean(false)

    private val capturerEvents = object : CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(errorDescription: String?) {
            Log.e(TAG, "Camera error: $errorDescription")
            errorListener?.invoke(errorDescription ?: "camera error")
        }

        override fun onCameraDisconnected() {
            Log.w(TAG, "Camera disconnected")
            errorListener?.invoke("camera disconnected")
        }

        override fun onCameraFreezed(errorDescription: String?) {
            Log.w(TAG, "Camera frozen: $errorDescription")
        }

        override fun onCameraOpening(cameraName: String?) {
            Log.i(TAG, "Camera opening: $cameraName")
        }

        override fun onFirstFrameAvailable() {
            Log.i(TAG, "First camera frame available")
        }

        override fun onCameraClosed() {
            Log.i(TAG, "Camera closed")
        }
    }

    var errorListener: ((String) -> Unit)? = null

    fun start(
        helper: SurfaceTextureHelper,
        observer: CapturerObserver,
        width: Int,
        height: Int,
        fps: Int,
    ) {
        if (running.getAndSet(true)) return

        val enumerator = Camera2Enumerator(context)
        val cameraName = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: throw IllegalStateException("No camera available")

        val cam = Camera2Capturer(context, cameraName, capturerEvents)
        capturer = cam
        cam.initialize(helper, context, observer)
        cam.startCapture(width, height, fps)
        Log.i(
            TAG,
            "Capturer started ${width}x${height}@$fps " +
                "camera=$cameraName sensorOrientation=${sensorOrientationDegrees()} " +
                "displayRotation=${displayRotationDegrees()}",
        )
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try {
            capturer?.stopCapture()
        } catch (e: Exception) {
            Log.w(TAG, "stopCapture failed", e)
        }
        try {
            capturer?.dispose()
        } catch (e: Exception) {
            Log.w(TAG, "dispose capturer failed", e)
        }
        capturer = null
        Log.i(TAG, "Capturer stopped")
    }

    fun sensorOrientationDegrees(): Int {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in cm.cameraIdList) {
                val facing = cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return cm.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                }
            }
            90
        } catch (_: Exception) {
            90
        }
    }

    fun displayRotationDegrees(): Int {
        @Suppress("DEPRECATION")
        val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.rotation
        return when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    companion object {
        private const val TAG = "CameraController"
    }
}
