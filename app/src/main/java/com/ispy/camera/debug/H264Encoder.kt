package com.ispy.camera.debug

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hardware-preferred H.264 (AVC) encoder using COLOR_FormatSurface.
 * Drains output and logs NAL types / sizes / keyframes to Logcat only.
 */
class H264Encoder(
    private val size: Size,
    private val bitRate: Int = DEFAULT_BITRATE,
    private val frameRate: Int = DEFAULT_FPS,
    private val iFrameIntervalSec: Int = DEFAULT_I_FRAME_INTERVAL_SEC,
) {
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var drainThread: HandlerThread? = null
    private var drainHandler: Handler? = null
    private val running = AtomicBoolean(false)

    private var outputFrameCount = 0
    private var lastLogUptimeMs = 0L
    private var bytesSinceLog = 0
    private var framesSinceLog = 0

    val surface: Surface
        get() = requireNotNull(inputSurface) { "Encoder not started" }

    fun start() {
        if (running.get()) return

        val codecName = findAvcEncoderName()
        val format = MediaFormat.createVideoFormat(MIME_TYPE, size.width, size.height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSec)
        }

        val mediaCodec = if (codecName != null) {
            MediaCodec.createByCodecName(codecName)
        } else {
            MediaCodec.createEncoderByType(MIME_TYPE)
        }

        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = mediaCodec.createInputSurface()
        mediaCodec.start()

        codec = mediaCodec
        running.set(true)

        drainThread = HandlerThread("H264EncoderDrain").also { it.start() }
        drainHandler = Handler(drainThread!!.looper)
        drainHandler!!.post { drainLoop() }

        val isHardware = codecName?.let { !it.contains("google", ignoreCase = true) } ?: false
        Log.i(
            TAG,
            "H264Encoder started codec=${codecName ?: "default"} " +
                "hwPreferred=$isHardware ${size.width}x${size.height} " +
                "@${frameRate}fps bitrate=$bitRate iFrameInterval=${iFrameIntervalSec}s",
        )
    }

    fun stop() {
        if (!running.getAndSet(false)) return

        drainThread?.quitSafely()
        try {
            drainThread?.join(1000)
        } catch (_: InterruptedException) {
        }
        drainThread = null
        drainHandler = null

        try {
            codec?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping codec", e)
        }
        try {
            codec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing codec", e)
        }
        codec = null

        inputSurface?.release()
        inputSurface = null

        Log.i(TAG, "H264Encoder stopped (outputFrames=$outputFrameCount)")
    }

    private fun drainLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (running.get()) {
            val mediaCodec = codec ?: break
            val index = try {
                mediaCodec.dequeueOutputBuffer(bufferInfo, DRAIN_TIMEOUT_US)
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "dequeueOutputBuffer failed", e)
                break
            }

            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.i(TAG, "Output format changed: ${mediaCodec.outputFormat}")
                }
                index >= 0 -> {
                    try {
                        val buffer = mediaCodec.getOutputBuffer(index)
                        if (buffer != null && bufferInfo.size > 0) {
                            onEncodedBuffer(buffer, bufferInfo)
                        }
                    } finally {
                        mediaCodec.releaseOutputBuffer(index, false)
                    }
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        Log.i(TAG, "Encoder EOS")
                        break
                    }
                }
            }
        }
    }

    private fun onEncodedBuffer(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        outputFrameCount++
        framesSinceLog++
        bytesSinceLog += info.size

        val isCodecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        val isKeyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val nalTypes = parseNalTypes(buffer, info)

        if (isCodecConfig || isKeyFrame) {
            Log.i(
                TAG,
                "NAL #$outputFrameCount size=${info.size} ptsUs=${info.presentationTimeUs} " +
                    "flags=${info.flags} keyFrame=$isKeyFrame codecConfig=$isCodecConfig " +
                    "nalTypes=$nalTypes (${nalTypes.joinToString { nalTypeName(it) }})",
            )
        }

        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (lastLogUptimeMs == 0L) {
            lastLogUptimeMs = nowMs
            return
        }
        if (nowMs - lastLogUptimeMs >= LOG_INTERVAL_MS) {
            val elapsedSec = (nowMs - lastLogUptimeMs) / 1000.0
            val outFps = framesSinceLog / elapsedSec
            val kbps = (bytesSinceLog * 8.0 / elapsedSec) / 1000.0
            Log.i(
                TAG,
                "Encode stats: outFps=${"%.1f".format(outFps)} " +
                    "bitrateKbps=${"%.0f".format(kbps)} totalFrames=$outputFrameCount " +
                    "lastNalTypes=$nalTypes",
            )
            lastLogUptimeMs = nowMs
            framesSinceLog = 0
            bytesSinceLog = 0
        }
    }

    /**
     * Walk Annex-B start codes (0x000001 / 0x00000001) and collect NAL unit type
     * values (low 5 bits of the NAL header) for H.264.
     */
    private fun parseNalTypes(buffer: ByteBuffer, info: MediaCodec.BufferInfo): List<Int> {
        val data = ByteArray(info.size)
        val pos = buffer.position()
        buffer.position(info.offset)
        buffer.get(data)
        buffer.position(pos)

        val types = mutableListOf<Int>()
        var i = 0
        while (i + 4 < data.size) {
            val sc3 = data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
            val sc4 = i + 4 < data.size &&
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            when {
                sc4 -> {
                    types.add(data[i + 4].toInt() and 0x1F)
                    i += 5
                }
                sc3 -> {
                    types.add(data[i + 3].toInt() and 0x1F)
                    i += 4
                }
                else -> i++
            }
        }
        // Some encoders omit start codes on length-prefixed buffers; treat first byte as NAL header.
        if (types.isEmpty() && data.isNotEmpty()) {
            types.add(data[0].toInt() and 0x1F)
        }
        return types
    }

    private fun nalTypeName(type: Int): String = when (type) {
        1 -> "non-IDR"
        5 -> "IDR"
        6 -> "SEI"
        7 -> "SPS"
        8 -> "PPS"
        9 -> "AUD"
        else -> "type$type"
    }

    private fun findAvcEncoderName(): String? {
        val list = MediaCodecList(MediaCodecList.ALL_CODECS)
        var softwareFallback: String? = null
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(MIME_TYPE, ignoreCase = true) }) continue
            val name = info.name
            val isSoftware = name.contains("google", ignoreCase = true) ||
                name.contains("sw", ignoreCase = true)
            if (!isSoftware) {
                Log.i(TAG, "Selected hardware AVC encoder: $name")
                return name
            }
            if (softwareFallback == null) softwareFallback = name
        }
        if (softwareFallback != null) {
            Log.w(TAG, "No hardware AVC encoder found; using $softwareFallback")
        }
        return softwareFallback
    }

    companion object {
        private const val TAG = "H264Encoder"
        private const val MIME_TYPE = "video/avc"
        private const val DEFAULT_BITRATE = 2_000_000
        private const val DEFAULT_FPS = 30
        private const val DEFAULT_I_FRAME_INTERVAL_SEC = 1
        private const val DRAIN_TIMEOUT_US = 10_000L
        private const val LOG_INTERVAL_MS = 1000L
    }
}
