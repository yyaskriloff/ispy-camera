package com.ispy.camera

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ispy.camera.service.CameraStreamService
import com.ispy.camera.util.PinStore
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

class MainActivity : Activity() {

    private lateinit var preview: SurfaceViewRenderer
    private lateinit var statusText: TextView
    private lateinit var urlText: TextView
    private lateinit var pinText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var settingsButton: Button

    private var service: CameraStreamService? = null
    private var bound = false
    private var previewInitialized = false
    private var previewAttached = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as CameraStreamService.LocalBinder
            service = local.getService()
            bound = true
            service?.statusListener = { status -> runOnUiThread { applyStatus(status) } }
            service?.currentStatus()?.let { applyStatus(it) }
            maybeAttachPreview()
            updateButtons(streaming = true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
            previewAttached = false
            updateButtons(streaming = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.localPreview)
        statusText = findViewById(R.id.statusText)
        urlText = findViewById(R.id.urlText)
        pinText = findViewById(R.id.pinText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        settingsButton = findViewById(R.id.settingsButton)

        pinText.text = PinStore(this).getOrCreatePin()

        startButton.setOnClickListener {
            if (hasAllPermissions()) {
                startStreaming()
            } else {
                requestNeededPermissions()
            }
        }
        stopButton.setOnClickListener { stopStreaming() }
        settingsButton.setOnClickListener { openAppSettings() }

        if (isServiceRunning()) {
            bindStreamService()
        }
    }

    override fun onStart() {
        super.onStart()
        if (isServiceRunning() && !bound) {
            bindStreamService()
        }
    }

    override fun onResume() {
        super.onResume()
        maybeAttachPreview()
    }

    override fun onPause() {
        // Screen off / locked / background: drop preview only; service keeps encode/stream.
        detachPreview()
        super.onPause()
    }

    override fun onStop() {
        if (bound) {
            service?.statusListener = null
            unbindService(connection)
            bound = false
            service = null
            previewAttached = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (previewInitialized) {
            preview.release()
            previewInitialized = false
        }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return

        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            settingsButton.visibility = View.GONE
            startStreaming()
            return
        }

        val permanentlyDenied = permissions.indices.any { i ->
            grantResults[i] != PackageManager.PERMISSION_GRANTED &&
                !ActivityCompat.shouldShowRequestPermissionRationale(this, permissions[i])
        }

        if (permanentlyDenied) {
            statusText.text = getString(R.string.permission_permanently_denied)
            settingsButton.visibility = View.VISIBLE
            Toast.makeText(this, R.string.permission_permanently_denied, Toast.LENGTH_LONG).show()
        } else {
            statusText.text = getString(R.string.permission_denied)
            settingsButton.visibility = View.GONE
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private fun startStreaming() {
        statusText.text = getString(R.string.status_starting)
        val intent = CameraStreamService.startIntent(this)
        ContextCompat.startForegroundService(this, intent)
        bindStreamService()
        updateButtons(streaming = true)
    }

    private fun stopStreaming() {
        detachPreview()
        if (bound) {
            service?.statusListener = null
            unbindService(connection)
            bound = false
            service = null
            previewAttached = false
        }
        startService(CameraStreamService.stopIntent(this))
        stopService(CameraStreamService.startIntent(this))
        statusText.text = getString(R.string.status_idle)
        updateButtons(streaming = false)
    }

    private fun bindStreamService() {
        bindService(
            CameraStreamService.startIntent(this),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    private fun maybeAttachPreview() {
        val svc = service ?: return
        if (previewAttached) return
        val egl = svc.eglContext() ?: return
        if (!previewInitialized) {
            preview.init(egl, null)
            preview.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            preview.setMirror(false)
            preview.setEnableHardwareScaler(true)
            previewInitialized = true
        }
        svc.attachPreview(preview)
        previewAttached = true
        Log.i(TAG, "Local preview attached")
    }

    private fun detachPreview() {
        if (!previewAttached) return
        service?.detachPreview()
        previewAttached = false
        Log.i(TAG, "Local preview detached")
    }

    private fun applyStatus(status: CameraStreamService.StreamStatus) {
        statusText.text = status.status
        pinText.text = status.pin
        urlText.text = status.httpUrl ?: getString(R.string.waiting_wifi)
        updateButtons(streaming = true)
    }

    private fun updateButtons(streaming: Boolean) {
        startButton.isEnabled = !streaming
        stopButton.isEnabled = streaming
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.toTypedArray()
    }

    private fun requestNeededPermissions() {
        statusText.text = getString(R.string.status_waiting_permissions)
        ActivityCompat.requestPermissions(this, requiredPermissions(), REQ_PERMISSIONS)
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
        startActivity(intent)
    }

    private fun isServiceRunning(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        return am.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == CameraStreamService::class.java.name
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_PERMISSIONS = 1001
    }
}
