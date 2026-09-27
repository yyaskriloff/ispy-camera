package com.ispy.camera.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    fun getLanIpv4(context: Context): String? {
        wifiIpFromManager(context)?.let { return it }
        return firstNonLoopbackIpv4()
    }

    fun httpUrl(ip: String, port: Int): String = "http://$ip:$port/"

    fun wsUrl(ip: String, port: Int): String = "ws://$ip:$port/ws"

    private fun wifiIpFromManager(context: Context): String? {
        return try {
            @Suppress("DEPRECATION")
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = wm.connectionInfo?.ipAddress ?: return null
            if (ip == 0) return null
            String.format(
                "%d.%d.%d.%d",
                ip and 0xff,
                ip shr 8 and 0xff,
                ip shr 16 and 0xff,
                ip shr 24 and 0xff,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun firstNonLoopbackIpv4(): String? {
        return try {
            for (intf in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun registerNetworkCallback(
        context: Context,
        onChanged: () -> Unit,
    ): ConnectivityManager.NetworkCallback {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChanged()
            override fun onLost(network: Network) = onChanged()
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) = onChanged()

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                onChanged()
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            cm.registerDefaultNetworkCallback(callback)
        } else {
            cm.registerNetworkCallback(request, callback)
        }
        return callback
    }

    fun unregisterNetworkCallback(
        context: Context,
        callback: ConnectivityManager.NetworkCallback,
    ) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }
}
