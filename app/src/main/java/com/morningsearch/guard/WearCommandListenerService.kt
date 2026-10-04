package com.morningsearch.guard

import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

class WearCommandListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            PATH_COMMAND -> handleCommand(event)
            PATH_STATUS -> sendStatus(event.sourceNodeId)
            PATH_PAIR_REQUEST -> handlePairRequest(event)
        }
    }

    private fun handlePairRequest(event: MessageEvent) {
        val key = runCatching { JSONObject(String(event.data, Charsets.UTF_8)).optString("publicKey") }.getOrNull().orEmpty()
        val store = GuardStore(this)
        val accepted = key.length in 40..2_000 && store.watchPairingArmed
        if (accepted) store.setPendingWatchPublicKey(key)
        val response = JSONObject().put("ok", accepted).put("message", if (accepted) "Approve pairing on phone" else "Pairing window is closed")
        Wearable.getMessageClient(this).sendMessage(event.sourceNodeId, PATH_PAIR_RESULT, response.toString().toByteArray())
    }

    private fun handleCommand(event: MessageEvent) {
        val json = runCatching { JSONObject(String(event.data, Charsets.UTF_8)) }.getOrNull() ?: return
        val signed = SignedRecoverCommand(
            command = json.optString("command"),
            payload = json.optString("payload"),
            nonce = json.optString("nonce"),
            issuedAt = json.optLong("issuedAt"),
            signatureBase64 = json.optString("signatureBase64")
        )
        val result = GuardianRecoverManager(this).applySignedCommand(signed)
        val response = when (result) {
            RecoverCommandResult.Applied -> JSONObject().put("ok", true)
            is RecoverCommandResult.Unsupported -> JSONObject().put("ok", false).put("error", result.reason)
            is RecoverCommandResult.Failed -> JSONObject().put("ok", false).put("error", result.reason)
        }
        Wearable.getMessageClient(this).sendMessage(event.sourceNodeId, PATH_COMMAND_RESULT, response.toString().toByteArray())
    }

    private fun sendStatus(nodeId: String) {
        val store = GuardStore(this)
        val status = RecoverDeviceStatus(this).capture()
        val body = JSONObject()
            .put("ok", true)
            .put("batteryPercent", status.batteryPercent)
            .put("charging", status.charging)
            .put("network", status.networkSummary)
            .put("location", status.locationSummary)
            .put("lostMode", status.lostModeActive)
            .put("studyMode", store.studyModeActive)
            .put("maintenanceMode", store.maintenanceModeActive)
            .put("cctvMonitor", store.cctvMonitorActive)
            .put("capturedAt", System.currentTimeMillis())
        Wearable.getMessageClient(this).sendMessage(nodeId, PATH_STATUS_RESULT, body.toString().toByteArray())
    }

    companion object {
        const val PATH_COMMAND = "/guardian/command"
        const val PATH_COMMAND_RESULT = "/guardian/command_result"
        const val PATH_STATUS = "/guardian/status"
        const val PATH_STATUS_RESULT = "/guardian/status_result"
        const val PATH_PAIR_REQUEST = "/guardian/pair_request"
        const val PATH_PAIR_RESULT = "/guardian/pair_result"
    }
}
