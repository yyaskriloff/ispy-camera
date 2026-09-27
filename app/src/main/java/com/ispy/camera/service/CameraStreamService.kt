package com.ispy.camera.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ispy.camera.MainActivity
import com.ispy.camera.R
import com.ispy.camera.StreamConfig
import com.ispy.camera.camera.CameraController
import com.ispy.camera.signaling.SignalingServer
import com.ispy.camera.util.NetworkUtils
import com.ispy.camera.util.PinStore
import com.ispy.camera.webrtc.WebRtcClient
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer

class CameraStreamService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): CameraStreamService = this@CameraStreamService
    }

    private val binder = LocalBinder()

    private var wakeLock: PowerManager.WakeLock? = null
    private var signalingServer: SignalingServer? = null
    private var webRtcClient: WebRtcClient? = null
    private var cameraController: CameraController? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var pin: String = ""
    private var lanIp: String? = null
    private var statusText: String = "Starting…"
    private var viewerConnected = false

    var statusListener: ((StreamStatus) -> Unit)? = null

    data class StreamStatus(
        val status: String,
        val pin: String,
        val httpUrl: String?,
        val viewerConnected: Boolean,
    )

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        pin = PinStore(this).getOrCreatePin()
        createNotificationChannel()
        acquireWakeLock()
        startAsForeground()
        startPipeline()
        watchNetwork()
        Log.i(TAG, "Service created PIN=$pin")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startAsForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        networkCallback?.let { NetworkUtils.unregisterNetworkCallback(this, it) }
        networkCallback = null
        try {
            signalingServer?.stop()
        } catch (_: Exception) {
        }
        signalingServer = null
        webRtcClient?.stop()
        webRtcClient = null
        cameraController = null
        releaseWakeLock()
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    fun attachPreview(renderer: SurfaceViewRenderer) {
        webRtcClient?.attachPreview(renderer)
    }

    fun detachPreview() {
        webRtcClient?.detachPreview()
    }

    fun eglContext(): EglBase.Context? = webRtcClient?.eglContext

    fun currentStatus(): StreamStatus = buildStatus()

    private fun startPipeline() {
        refreshLanIp()
        val cam = CameraController(applicationContext)
        cameraController = cam

        val rtc = WebRtcClient(applicationContext, cam) { msg ->
            statusText = msg
            if (msg.contains("Viewer connected", ignoreCase = true)) {
                viewerConnected = true
            } else if (msg.contains("Waiting for viewer", ignoreCase = true) ||
                msg.contains("Camera ready", ignoreCase = true)
            ) {
                viewerConnected = false
            }
            publishStatus()
        }
        webRtcClient = rtc
        rtc.start()

        val server = SignalingServer(
            context = applicationContext,
            expectedPin = pin,
            listener = object : SignalingServer.Listener {
                override fun onViewerAuthenticated(session: SignalingServer.ViewerSession) {
                    statusText = "Viewer authenticated — creating offer"
                    viewerConnected = false
                    publishStatus()
                    webRtcClient?.connectViewer(session)
                }

                override fun onViewerAnswer(session: SignalingServer.ViewerSession, sdp: String) {
                    webRtcClient?.onRemoteAnswer(session, sdp)
                }

                override fun onViewerIce(
                    session: SignalingServer.ViewerSession,
                    candidate: String,
                    sdpMid: String?,
                    sdpMLineIndex: Int?,
                ) {
                    webRtcClient?.onRemoteIce(session, candidate, sdpMid, sdpMLineIndex)
                }

                override fun onViewerDisconnected(session: SignalingServer.ViewerSession) {
                    webRtcClient?.onViewerDisconnected(session)
                    viewerConnected = false
                    statusText = "Waiting for viewer"
                    publishStatus()
                }
            },
        )
        signalingServer = server
        try {
            server.start(SOCKET_READ_TIMEOUT_MS, false)
            statusText = "Streaming — waiting for viewer"
            publishStatus()
            Log.i(TAG, "Signaling listening on :${StreamConfig.SIGNALING_PORT}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start signaling", e)
            statusText = "Signaling failed: ${e.message}"
            publishStatus()
        }
    }

    private fun watchNetwork() {
        networkCallback = NetworkUtils.registerNetworkCallback(this) {
            val previous = lanIp
            refreshLanIp()
            if (lanIp != previous) {
                Log.i(TAG, "LAN IP changed: $previous -> $lanIp")
                publishStatus()
            }
        }
    }

    private fun refreshLanIp() {
        lanIp = NetworkUtils.getLanIpv4(this)
    }

    private fun buildStatus(): StreamStatus {
        val url = lanIp?.let { NetworkUtils.httpUrl(it, StreamConfig.SIGNALING_PORT) }
        return StreamStatus(
            status = statusText,
            pin = pin,
            httpUrl = url,
            viewerConnected = viewerConnected,
        )
    }

    private fun publishStatus() {
        val status = buildStatus()
        updateNotification(status)
        statusListener?.invoke(status)
    }

    private fun startAsForeground() {
        val notification = buildNotification(buildStatus())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(status: StreamStatus) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: StreamStatus): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, CameraStreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val urlLine = status.httpUrl ?: getString(R.string.waiting_wifi)
        val text = "${status.status}\n$urlLine  PIN ${status.pin}"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(urlLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .addAction(0, getString(R.string.btn_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ispy:stream").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    companion object {
        private const val TAG = "CameraStreamService"
        private const val CHANNEL_ID = "ispy_stream"
        private const val NOTIFICATION_ID = 42
        private const val SOCKET_READ_TIMEOUT_MS = 30_000
        const val ACTION_STOP = "com.ispy.camera.action.STOP"

        fun startIntent(context: android.content.Context): Intent =
            Intent(context, CameraStreamService::class.java)

        fun stopIntent(context: android.content.Context): Intent =
            Intent(context, CameraStreamService::class.java).setAction(ACTION_STOP)
    }
}
