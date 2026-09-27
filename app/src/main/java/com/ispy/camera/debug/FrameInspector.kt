package com.ispy.camera.debug

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.util.Log
import android.util.Size
import android.view.Surface

/**
 * Captures raw Camera2 frames via ImageReader and logs format / resolution /
 * plane layout / timestamps / measured FPS (Logcat only).
 */
class FrameInspector(
    size: Size,
    private val handler: Handler,
) {
    private val imageReader: ImageReader = ImageReader.newInstance(
        size.width,
        size.height,
        ImageFormat.YUV_420_888,
        MAX_IMAGES,
    )

    private var frameCount = 0
    private var lastLogUptimeMs = 0L
    private var lastTimestampNs = 0L
    private var framesSinceLog = 0
    private var loggedFormatOnce = false

    val surface: Surface
        get() = imageReader.surface

    val size: Size
        get() = Size(imageReader.width, imageReader.height)

    fun start() {
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                onFrame(image.format, image.width, image.height, image.timestamp, image.planes)
            } finally {
                image.close()
            }
        }, handler)
        Log.i(TAG, "FrameInspector started ${size.width}x${size.height} YUV_420_888")
    }

    fun stop() {
        imageReader.setOnImageAvailableListener(null, null)
    }

    fun release() {
        stop()
        imageReader.close()
        Log.i(TAG, "FrameInspector released")
    }

    private fun onFrame(
        format: Int,
        width: Int,
        height: Int,
        timestampNs: Long,
        planes: Array<android.media.Image.Plane>,
    ) {
        frameCount++
        framesSinceLog++

        if (!loggedFormatOnce) {
            loggedFormatOnce = true
            val formatName = when (format) {
                ImageFormat.YUV_420_888 -> "YUV_420_888"
                ImageFormat.NV21 -> "NV21"
                else -> "format=$format"
            }
            val planeInfo = planes.mapIndexed { i, p ->
                "plane[$i] rowStride=${p.rowStride} pixelStride=${p.pixelStride} " +
                    "bufferSize=${p.buffer.remaining()}"
            }.joinToString("; ")
            Log.i(
                TAG,
                "First frame: $formatName ${width}x${height} planes=${planes.size} | $planeInfo",
            )
            Log.i(
                TAG,
                "Note: YUV_420_888 is Android's flexible YUV420; NV21 is a packed semi-planar layout",
            )
        }

        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (lastLogUptimeMs == 0L) {
            lastLogUptimeMs = nowMs
            lastTimestampNs = timestampNs
            return
        }

        if (nowMs - lastLogUptimeMs >= LOG_INTERVAL_MS) {
            val elapsedSec = (nowMs - lastLogUptimeMs) / 1000.0
            val measuredFps = framesSinceLog / elapsedSec
            val deltaNs = timestampNs - lastTimestampNs
            Log.i(
                TAG,
                "Frame #$frameCount ${width}x${height} " +
                    "timestampNs=$timestampNs deltaNs=$deltaNs " +
                    "measuredFps=${"%.1f".format(measuredFps)}",
            )
            lastLogUptimeMs = nowMs
            lastTimestampNs = timestampNs
            framesSinceLog = 0
        }
    }

    companion object {
        private const val TAG = "FrameInspector"
        private const val MAX_IMAGES = 3
        private const val LOG_INTERVAL_MS = 1000L
    }
}
