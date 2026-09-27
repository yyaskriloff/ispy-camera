package com.ispy.camera

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val textView = TextView(this).apply {
            text = "iSpy Camera"
            textSize = 24f
        }

        setContentView(textView)
    }

  

}
