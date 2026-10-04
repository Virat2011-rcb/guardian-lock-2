package com.morningsearch.guard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import android.location.Location
import java.util.concurrent.Executors

class RecoverUploadManager(private val context: Context) {
    private val store = GuardStore(context)
    private val timeline = RecoverTimeline(context)

    fun uploadStatusAsync(status: RecoverStatus = RecoverDeviceStatus(context).capture()) {
        executor.execute {
            val base = normalizedBaseUrl() ?: return@execute
            val body = JSONObject()
                .put("capturedAt", System.currentTimeMillis())
                .put("batteryPercent", status.batteryPercent)
                .put("charging", status.charging)
                .put("network", status.networkSummary)
                .put("location", status.locationSummary)
                .put("lostMode", status.lostModeActive)
                .put("cctvMonitor", store.cctvMonitorActive)
                .put("sim", status.simSummary)
                .put("ip", status.ipAddress)
                .put("cloudTransport", store.cloudTransportState)
                .toString()
            CloudWebSocketManager.get(context).sendStatus(JSONObject(body))
            runCatching {
                val connection = open("$base/recover/status", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            }.onSuccess {
                timeline.record("status_uploaded", base)
            }.onFailure {
                timeline.record("status_upload_failed", it.message ?: it.javaClass.simpleName)
            }
        }
    }

    fun uploadFileAsync(file: File, type: String) {
        executor.execute {
            if (!file.exists() || file.length() <= 0L) return@execute
            val base = normalizedBaseUrl() ?: return@execute
            val encodedName = URLEncoder.encode(file.name, "UTF-8")
            val encodedType = URLEncoder.encode(type, "UTF-8")
            runCatching {
                val connection = open("$base/recover/upload?type=$encodedType&name=$encodedName", "application/octet-stream")
                file.inputStream().use { input -> connection.outputStream.use { output -> input.copyTo(output) } }
                require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            }.onSuccess {
                timeline.record("${type}_uploaded", file.name)
            }.onFailure {
                timeline.record("${type}_upload_failed", it.message ?: it.javaClass.simpleName)
            }
        }
    }

    fun uploadEventAsync(type: String, detail: String) {
        executor.execute {
            val base = normalizedBaseUrl() ?: return@execute
            val body = JSONObject()
                .put("type", type)
                .put("detail", detail.take(600))
                .put("timestamp", System.currentTimeMillis())
                .put("source", "phone")
                .toString()
            CloudWebSocketManager.get(context).sendEvent(type, detail)
            runCatching {
                val connection = open("$base/recover/event", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            }.onSuccess {
                timeline.record("${type}_uploaded", detail)
            }.onFailure {
                timeline.record("${type}_upload_failed", it.message ?: it.javaClass.simpleName)
            }
        }
    }

    fun uploadLiveLocation(location: Location) {
        executor.execute {
            if (!GuardStore(context).liveTrackingActive) return@execute
            val body = JSONObject()
                .put("timestamp", System.currentTimeMillis())
                .put("latitude", location.latitude)
                .put("longitude", location.longitude)
                .put("accuracyMeters", if (location.hasAccuracy()) location.accuracy else JSONObject.NULL)
                .put("speedMps", if (location.hasSpeed()) location.speed else JSONObject.NULL)
                .put("bearing", if (location.hasBearing()) location.bearing else JSONObject.NULL)
            val pushed = CloudWebSocketManager.get(context).sendLocationUpdate(body)
            val base = normalizedBaseUrl() ?: return@execute
            runCatching {
                val connection = open("$base/recover/live-location", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            }.onFailure {
                if (!pushed) timeline.record("live_location_upload_failed", it.message ?: it.javaClass.simpleName)
            }
        }
    }

    fun fetchWatchPublicKeyByCode(code: String): String? {
        val cleaned = code.filter { it.isDigit() }
        if (!Regex("^\\d{7}$").matches(cleaned)) return null
        val base = normalizedBaseUrl() ?: return null
        return runCatching {
            val connection = openGet("$base/recover/watch/pairing/$cleaned")
            require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).optString("publicKey").takeIf { it.length >= 60 }
        }.onFailure {
            timeline.record("watch_pair_code_failed", it.message ?: it.javaClass.simpleName)
        }.getOrNull()
    }

    fun pollRemoteCommandsAsync(force: Boolean = false) {
        executor.execute {
            if (!force && CloudWebSocketManager.get(context).isConnected()) {
                val now = System.currentTimeMillis()
                if (now - store.lastRemoteReconcileAt < 60_000L) return@execute
            }
            store.lastRemoteReconcileAt = System.currentTimeMillis()
            val base = normalizedBaseUrl() ?: return@execute
            runCatching {
                val connection = openGet("$base/recover/commands")
                require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val commands = JSONObject(body).optJSONArray("commands") ?: JSONArray()
                for (index in 0 until commands.length()) {
                    val envelope = commands.optJSONObject(index) ?: continue
                    val id = envelope.optString("id")
                    val json = envelope.optJSONObject("command") ?: continue
                    val result = applyPushedCommand(json)
                    ackRemoteCommand(base, id, result)
                }
            }.onFailure {
                timeline.record("remote_command_poll_failed", it.message ?: it.javaClass.simpleName)
            }
        }
    }

    /** Applies one signed envelope from either WebSocket or HTTP transport. */
    fun applyPushedCommand(json: JSONObject): RecoverCommandResult {
        val signed = SignedRecoverCommand(
            command = json.optString("command"),
            payload = json.optString("payload"),
            nonce = json.optString("nonce"),
            issuedAt = json.optLong("issuedAt"),
            signatureBase64 = json.optString("signatureBase64")
        )
        return GuardianRecoverManager(context).applySignedCommand(signed)
    }

    private fun ackRemoteCommand(base: String, id: String, result: RecoverCommandResult) {
        if (id.isBlank()) return
        val status = commandResultJson(result)
        runCatching {
            val encoded = URLEncoder.encode(id, "UTF-8")
            val connection = open("$base/recover/commands/$encoded/ack", "application/json")
            connection.outputStream.use { it.write(status.toString().toByteArray(Charsets.UTF_8)) }
            require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
        }
    }

    private fun open(url: String, contentType: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", contentType)
            val token = store.recoveryUploadToken
            if (token.isNotBlank()) setRequestProperty("X-Guardian-Token", token)
        }
    }

    private fun openGet(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            val token = store.recoveryUploadToken
            if (token.isNotBlank()) setRequestProperty("X-Guardian-Token", token)
        }
    }

    private fun normalizedBaseUrl(): String? {
        val value = store.recoveryUploadUrl.trim().trimEnd('/')
        if (!value.startsWith("http://") && !value.startsWith("https://")) return null
        return value
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor()

        fun commandResultJson(result: RecoverCommandResult): JSONObject = when (result) {
            RecoverCommandResult.Applied -> JSONObject().put("ok", true)
            is RecoverCommandResult.Unsupported -> JSONObject().put("ok", false).put("error", result.reason)
            is RecoverCommandResult.Failed -> JSONObject().put("ok", false).put("error", result.reason)
        }
    }
}
