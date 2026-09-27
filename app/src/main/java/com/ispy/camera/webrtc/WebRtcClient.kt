package com.ispy.camera.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ispy.camera.StreamConfig
import com.ispy.camera.camera.CameraController
import com.ispy.camera.signaling.SignalingServer
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PeerConnectionFactory, local A/V tracks, and a single viewer PeerConnection.
 * Prefers hardware H.264 via [DefaultVideoEncoderFactory].
 */
class WebRtcClient(
    private val context: Context,
    private val cameraController: CameraController,
    private val statusListener: (String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var peerConnection: PeerConnection? = null
    private var boundSession: SignalingServer.ViewerSession? = null
    private var previewRenderer: SurfaceViewRenderer? = null
    private var previewAttached = false
    private var cameraRetryCount = 0

    val eglContext: EglBase.Context?
        get() = eglBase?.eglBaseContext

    fun start() {
        if (!started.compareAndSet(false, true)) return

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )

        val egl = EglBase.create()
        eglBase = egl

        val encoderFactory = DefaultVideoEncoderFactory(
            egl.eglBaseContext,
            /* enableIntelVp8Encoder */ true,
            /* enableH264HighProfile */ true,
        )
        val decoderFactory = DefaultVideoDecoderFactory(egl.eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        startCaptureWithRetry()
        Log.i(TAG, "WebRtcClient started (prefer HW H.264, ${StreamConfig.WIDTH}x${StreamConfig.HEIGHT}@${StreamConfig.FPS})")
    }

    private fun startCaptureWithRetry() {
        val f = factory ?: return
        val egl = eglBase ?: return
        try {
            cameraController.errorListener = { msg ->
                statusListener("Camera: $msg")
                scheduleCameraRestart()
            }

            videoSource?.dispose()
            audioSource?.dispose()
            localVideoTrack?.dispose()
            localAudioTrack?.dispose()
            surfaceTextureHelper?.dispose()

            val helper = SurfaceTextureHelper.create("iSpyCapture", egl.eglBaseContext)
            surfaceTextureHelper = helper

            val vSource = f.createVideoSource(false)
            videoSource = vSource
            vSource.adaptOutputFormat(StreamConfig.WIDTH, StreamConfig.HEIGHT, StreamConfig.FPS)

            cameraController.start(
                helper,
                vSource.capturerObserver,
                StreamConfig.WIDTH,
                StreamConfig.HEIGHT,
                StreamConfig.FPS,
            )

            val vTrack = f.createVideoTrack(VIDEO_TRACK_ID, vSource)
            vTrack.setEnabled(true)
            localVideoTrack = vTrack

            val aSource = f.createAudioSource(MediaConstraints())
            audioSource = aSource
            val aTrack = f.createAudioTrack(AUDIO_TRACK_ID, aSource)
            aTrack.setEnabled(true)
            localAudioTrack = aTrack

            cameraRetryCount = 0
            reattachPreviewIfNeeded()
            statusListener("Camera ready")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture", e)
            statusListener("Camera start failed: ${e.message}")
            scheduleCameraRestart()
        }
    }

    private fun scheduleCameraRestart() {
        if (!started.get()) return
        if (cameraRetryCount >= StreamConfig.CAMERA_RETRY_MAX) {
            statusListener("Camera retries exhausted")
            return
        }
        cameraRetryCount++
        val delay = StreamConfig.CAMERA_RETRY_DELAY_MS * cameraRetryCount
        Log.w(TAG, "Retrying camera in ${delay}ms (attempt $cameraRetryCount)")
        mainHandler.postDelayed({
            if (!started.get()) return@postDelayed
            try {
                cameraController.stop()
            } catch (_: Exception) {
            }
            startCaptureWithRetry()
        }, delay)
    }

    fun attachPreview(renderer: SurfaceViewRenderer) {
        previewRenderer = renderer
        val track = localVideoTrack
        if (track != null && !previewAttached) {
            track.addSink(renderer)
            previewAttached = true
            Log.i(TAG, "Preview attached")
        }
    }

    fun detachPreview() {
        val renderer = previewRenderer
        val track = localVideoTrack
        if (previewAttached && renderer != null && track != null) {
            track.removeSink(renderer)
            previewAttached = false
            Log.i(TAG, "Preview detached (stream continues)")
        }
        previewRenderer = null
    }

    private fun reattachPreviewIfNeeded() {
        val renderer = previewRenderer ?: return
        val track = localVideoTrack ?: return
        if (!previewAttached) {
            track.addSink(renderer)
            previewAttached = true
        }
    }

    fun connectViewer(session: SignalingServer.ViewerSession) {
        mainHandler.post {
            if (boundSession != null && boundSession !== session) {
                session.sendError("busy", "viewer already connected")
                session.closeQuietly()
                return@post
            }
            disposePeerConnection("new viewer")
            boundSession = session
            createPeerConnectionAndOffer(session)
        }
    }

    fun onRemoteAnswer(session: SignalingServer.ViewerSession, sdp: String) {
        mainHandler.post {
            if (session !== boundSession) return@post
            val pc = peerConnection ?: return@post
            pc.setRemoteDescription(
                LoggingSdpObserver("setRemoteAnswer"),
                SessionDescription(SessionDescription.Type.ANSWER, sdp),
            )
            statusListener("Viewer answer applied")
        }
    }

    fun onRemoteIce(
        session: SignalingServer.ViewerSession,
        candidate: String,
        sdpMid: String?,
        sdpMLineIndex: Int?,
    ) {
        mainHandler.post {
            if (session !== boundSession) return@post
            val pc = peerConnection ?: return@post
            pc.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex ?: 0, candidate))
        }
    }

    fun onViewerDisconnected(session: SignalingServer.ViewerSession) {
        mainHandler.post {
            if (session !== boundSession) return@post
            disposePeerConnection("viewer disconnected")
            boundSession = null
            statusListener("Waiting for viewer")
        }
    }

    private fun createPeerConnectionAndOffer(session: SignalingServer.ViewerSession) {
        val f = factory ?: return
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pc = f.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.i(TAG, "signaling=$state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.i(TAG, "iceConnection=$state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED,
                    -> statusListener("Viewer connected")
                    PeerConnection.IceConnectionState.FAILED -> {
                        statusListener("ICE failed — reconnecting")
                        mainHandler.postDelayed({
                            if (boundSession === session && started.get()) {
                                disposePeerConnection("ice failed")
                                createPeerConnectionAndOffer(session)
                            }
                        }, StreamConfig.ICE_RESTART_DELAY_MS)
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED ->
                        statusListener("ICE disconnected")
                    else -> Unit
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.i(TAG, "iceGathering=$state")
            }

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null) return
                session.sendIce(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onAddStream(stream: MediaStream?) {}

            override fun onRemoveStream(stream: MediaStream?) {}

            override fun onDataChannel(dc: DataChannel?) {}

            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }) ?: run {
            statusListener("Failed to create PeerConnection")
            return
        }

        peerConnection = pc

        localVideoTrack?.let { pc.addTrack(it, listOf(STREAM_ID)) }
        localAudioTrack?.let { pc.addTrack(it, listOf(STREAM_ID)) }

        // Send-only: no recv audio/video from viewer.
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                val tuned = SessionDescription(desc.type, preferHwH264(desc.description))
                pc.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        session.sendOffer(tuned.description)
                        statusListener("Offer sent")
                        Log.i(TAG, "Offer sent to viewer ${session.id}")
                    }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "setLocalDescription failed: $error")
                    }
                }, tuned)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "createOffer failed: $error")
                statusListener("createOffer failed")
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    /**
     * Bump H.264 payload types ahead of VP8/VP9 in the offer when present.
     */
    private fun preferHwH264(sdp: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        val videoMLineIndex = lines.indexOfFirst { it.startsWith("m=video") }
        if (videoMLineIndex < 0) return sdp

        var h264Pt: String? = null
        for (line in lines) {
            if (line.startsWith("a=rtpmap:") && line.contains("H264/90000", ignoreCase = true)) {
                h264Pt = line.removePrefix("a=rtpmap:").substringBefore(' ')
                break
            }
        }
        if (h264Pt == null) {
            Log.w(TAG, "No H264 rtpmap in offer; leaving SDP unchanged")
            return sdp
        }

        val parts = lines[videoMLineIndex].split(' ').toMutableList()
        // m=video port proto pt pt pt...
        if (parts.size > 3) {
            val pts = parts.subList(3, parts.size).toMutableList()
            pts.remove(h264Pt)
            pts.add(0, h264Pt)
            lines[videoMLineIndex] = (parts.subList(0, 3) + pts).joinToString(" ")
            Log.i(TAG, "Preferring H264 payload type $h264Pt")
        }
        return lines.joinToString("\r\n")
    }

    private fun disposePeerConnection(reason: String) {
        Log.i(TAG, "Disposing PeerConnection ($reason)")
        try {
            peerConnection?.close()
        } catch (_: Exception) {
        }
        try {
            peerConnection?.dispose()
        } catch (_: Exception) {
        }
        peerConnection = null
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        mainHandler.removeCallbacksAndMessages(null)
        detachPreview()
        boundSession?.sendBye()
        boundSession = null
        disposePeerConnection("stop")
        try {
            cameraController.stop()
        } catch (_: Exception) {
        }
        localVideoTrack?.dispose()
        localVideoTrack = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        videoSource?.dispose()
        videoSource = null
        audioSource?.dispose()
        audioSource = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        factory?.dispose()
        factory = null
        eglBase?.release()
        eglBase = null
        Log.i(TAG, "WebRtcClient stopped")
    }

    private class LoggingSdpObserver(private val label: String) : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {
            Log.i(TAG, "$label ok")
        }
        override fun onCreateFailure(error: String?) {
            Log.e(TAG, "$label create fail: $error")
        }
        override fun onSetFailure(error: String?) {
            Log.e(TAG, "$label set fail: $error")
        }
    }

    companion object {
        private const val TAG = "WebRtcClient"
        private const val VIDEO_TRACK_ID = "ispy_video"
        private const val AUDIO_TRACK_ID = "ispy_audio"
        private const val STREAM_ID = "ispy_stream"
    }
}
