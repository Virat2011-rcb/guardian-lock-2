package com.morningsearch.guard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.net.NetworkInterface

class RecoverDeviceStatus(private val context: Context) {
    fun capture(): RecoverStatus {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return RecoverStatus(
            batteryPercent = percent,
            charging = plugged != 0,
            networkSummary = networkSummary(),
            locationSummary = locationSummary(),
            lostModeActive = GuardStore(context).lostModeActive,
            locked = false,
            simSummary = simSummary(),
            ipAddress = ipAddress()
        )
    }

    private fun networkSummary(): String {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return "offline"
        val caps = manager.getNetworkCapabilities(network) ?: return "unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "connected"
        }
    }

    private fun locationSummary(): String {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return "permission_missing"
        val manager = context.getSystemService(LocationManager::class.java)
        val providers = manager.getProviders(true)
        val location = providers.asSequence()
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
        return location?.let { "%.5f,%.5f @ %d".format(it.latitude, it.longitude, it.time) } ?: "unavailable"
    }

    private fun simSummary(): String {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        if (!granted) return "permission_missing"
        return runCatching {
            val telephony = context.getSystemService(TelephonyManager::class.java)
            "${telephony.simOperatorName.ifBlank { "unknown" }} / ${telephony.simCountryIso.ifBlank { "unknown" }}"
        }.getOrDefault("unavailable")
    }

    private fun ipAddress(): String {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
                ?.hostAddress ?: "unavailable"
        }.getOrDefault("unavailable")
    }
}
