package com.ispy.camera

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

import android.Manifest
import android.content.pm.PackageManager

private const val CAMERA_PERMISSION_REQUEST_CODE = 100
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
        } else {

        }

        val textView = TextView(this).apply {
            text = "iSpy Camera"
            textSize = 24f
        }

        setContentView(textView)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        
                // Granted
        
            } else {
        
                // Denied
        
            }
        }

    }
}
