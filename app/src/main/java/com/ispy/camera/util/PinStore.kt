package com.ispy.camera.util

import android.content.Context
import android.content.SharedPreferences
import kotlin.random.Random

class PinStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getOrCreatePin(): String {
        val existing = prefs.getString(KEY_PIN, null)
        if (!existing.isNullOrBlank()) return existing
        val pin = Random.nextInt(0, 10_000).toString().padStart(4, '0')
        prefs.edit().putString(KEY_PIN, pin).apply()
        return pin
    }

    fun setPin(pin: String) {
        require(pin.length in 4..8 && pin.all { it.isDigit() }) { "PIN must be 4–8 digits" }
        prefs.edit().putString(KEY_PIN, pin).apply()
    }

    companion object {
        private const val PREFS = "ispy_prefs"
        private const val KEY_PIN = "viewer_pin"
    }
}
