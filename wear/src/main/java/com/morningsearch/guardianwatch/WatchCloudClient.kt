package com.morningsearch.guardianwatch

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class WatchCloudClient(private val context: android.content.Context, private val store: WatchStateStore) {
    fun queueSignedCommand(command: JSONObject) {
        val base = store.cloudUrl.ifBlank { error("Cloud URL missing") }
        val connection = open("$base/api/commands", "POST")
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(command.toString().toByteArray(Charsets.UTF_8)) }
        require(connection.responseCode in 200..299) { "Cloud HTTP ${connection.responseCode}" }
        store.lastTransport = "Cloud"
    }

    fun latestStatus(): WatchStatus {
        val base = store.cloudUrl.ifBlank { return store.lastStatus() }
        val connection = open("$base/api/status/latest", "GET")
        if (connection.responseCode !in 200..299) return store.lastStatus().copy(phoneOnline = false, transport = "Cloud Offline")
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val root = JSONObject(body)
        val status = root.optJSONObject("status")
        val data = status?.optJSONObject("data")
        val parsed = WatchStatus(
            phoneOnline = data != null,
            batteryPercent = data?.optInt("batteryPercent", -1) ?: -1,
            charging = data?.optBoolean("charging") ?: false,
            network = data?.optString("network", "unknown") ?: "unknown",
            location = data?.optString("location", "") ?: "",
            lostMode = data?.optBoolean("lostMode") ?: false,
            cctvMonitor = data?.optBoolean("cctvMonitor") ?: false,
            capturedAt = data?.optLong("capturedAt", status?.optLong("timestamp") ?: 0L) ?: 0L,
            transport = "Cloud"
        )
        store.saveStatus(parsed)
        store.lastTransport = "Cloud"
        return parsed
    }

    fun registerPairingCode(code: String, publicKey: String): Long {
        val base = store.cloudUrl.ifBlank { error("Cloud URL missing") }
        val expiresAt = System.currentTimeMillis() + 15 * 60 * 1000
        val body = JSONObject()
            .put("code", code)
            .put("publicKey", publicKey)
            .put("expiresAt", expiresAt)
        val connection = open("$base/api/watch/pairing", "POST")
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        require(connection.responseCode in 200..299) { "Cloud HTTP ${connection.responseCode}" }
        return expiresAt
    }

    fun latestEvent(): WatchRecoveryEvent? {
        val base = store.cloudUrl.ifBlank { return null }
        val connection = open("$base/api/events/latest", "GET")
        if (connection.responseCode !in 200..299) return null
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val event = JSONObject(body).optJSONObject("event") ?: return null
        return WatchRecoveryEvent(
            id = event.optString("id"),
            type = event.optString("type"),
            detail = event.optString("detail"),
            timestamp = event.optLong("timestamp")
        )
    }

    private fun open(url: String, method: String): HttpURLConnection {
        val target = if (url.contains("?") || store.cloudToken.isBlank()) url else "$url?token=${java.net.URLEncoder.encode(store.cloudToken, "UTF-8")}"
        return (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            if (store.cloudToken.isNotBlank()) setRequestProperty("X-Guardian-Token", store.cloudToken)
        }
    }
}
