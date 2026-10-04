package com.morningsearch.guardianwatch

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

class GuardianWatchService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH_STATUS_RESULT) return
        val json = runCatching { JSONObject(String(event.data, Charsets.UTF_8)) }.getOrNull() ?: return
        val status = WatchStatus(
            phoneOnline = true,
            batteryPercent = json.optInt("batteryPercent", -1),
            charging = json.optBoolean("charging"),
            network = json.optString("network", "unknown"),
            location = json.optString("location", ""),
            lostMode = json.optBoolean("lostMode"),
            studyMode = json.optBoolean("studyMode"),
            maintenanceMode = json.optBoolean("maintenanceMode"),
            cctvMonitor = json.optBoolean("cctvMonitor"),
            capturedAt = json.optLong("capturedAt", System.currentTimeMillis()),
            transport = "Nearby Phone"
        )
        WatchStateStore(this).saveStatus(status)
    }

    companion object {
        private const val PATH_STATUS_RESULT = "/guardian/status_result"
    }
}
