package com.ispy.camera

/** Overnight-friendly capture / encode defaults. */
object StreamConfig {
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val FPS = 15
    const val MAX_BITRATE_BPS = 1_200_000
    const val SIGNALING_PORT = 8765
    const val CAMERA_RETRY_DELAY_MS = 2_000L
    const val CAMERA_RETRY_MAX = 5
    const val ICE_RESTART_DELAY_MS = 1_500L
}
