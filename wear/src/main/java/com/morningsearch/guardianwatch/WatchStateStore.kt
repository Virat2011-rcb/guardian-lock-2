package com.morningsearch.guardianwatch

import android.content.Context
import org.json.JSONObject

class WatchStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("guardian_watch_state", Context.MODE_PRIVATE)

    var cloudUrl: String
        get() = prefs.getString("cloud_url", "") ?: ""
        set(value) = prefs.edit().putString("cloud_url", value.trim().trimEnd('/')).apply()

    var cloudToken: String
        get() = prefs.getString("cloud_token", "") ?: ""
        set(value) = prefs.edit().putString("cloud_token", value.trim()).apply()

    var lastTransport: String
        get() = prefs.getString("transport", "Unknown") ?: "Unknown"
        set(value) = prefs.edit().putString("transport", value).apply()

    var lastEventId: String
        get() = prefs.getString("last_event_id", "") ?: ""
        set(value) = prefs.edit().putString("last_event_id", value).apply()

    fun saveStatus(status: WatchStatus) {
        prefs.edit().putString("last_status", JSONObject()
            .put("phoneOnline", status.phoneOnline)
            .put("batteryPercent", status.batteryPercent)
            .put("charging", status.charging)
            .put("network", status.network)
            .put("location", status.location)
            .put("lostMode", status.lostMode)
            .put("studyMode", status.studyMode)
            .put("maintenanceMode", status.maintenanceMode)
            .put("cctvMonitor", status.cctvMonitor)
            .put("capturedAt", status.capturedAt)
            .put("transport", status.transport)
            .toString()).apply()
    }

    fun lastStatus(): WatchStatus {
        val json = runCatching { JSONObject(prefs.getString("last_status", "") ?: "") }.getOrNull()
            ?: return WatchStatus(transport = lastTransport)
        return WatchStatus(
            phoneOnline = json.optBoolean("phoneOnline"),
            batteryPercent = json.optInt("batteryPercent", -1),
            charging = json.optBoolean("charging"),
            network = json.optString("network", "unknown"),
            location = json.optString("location", ""),
            lostMode = json.optBoolean("lostMode"),
            studyMode = json.optBoolean("studyMode"),
            maintenanceMode = json.optBoolean("maintenanceMode"),
            cctvMonitor = json.optBoolean("cctvMonitor"),
            capturedAt = json.optLong("capturedAt"),
            transport = json.optString("transport", lastTransport)
        )
    }
}
