package com.morningsearch.guard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
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
                .put("sim", status.simSummary)
                .put("ip", status.ipAddress)
                .toString()
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

    fun pollRemoteCommandsAsync() {
        executor.execute {
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
                    val signed = SignedRecoverCommand(
                        command = json.optString("command"),
                        payload = json.optString("payload"),
                        nonce = json.optString("nonce"),
                        issuedAt = json.optLong("issuedAt"),
                        signatureBase64 = json.optString("signatureBase64")
                    )
                    val result = GuardianRecoverManager(context).applySignedCommand(signed)
                    ackRemoteCommand(base, id, result)
                }
            }.onFailure {
                timeline.record("remote_command_poll_failed", it.message ?: it.javaClass.simpleName)
            }
        }
    }

    private fun ackRemoteCommand(base: String, id: String, result: RecoverCommandResult) {
        if (id.isBlank()) return
        val status = when (result) {
            RecoverCommandResult.Applied -> JSONObject().put("ok", true)
            is RecoverCommandResult.Unsupported -> JSONObject().put("ok", false).put("error", result.reason)
            is RecoverCommandResult.Failed -> JSONObject().put("ok", false).put("error", result.reason)
        }
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
    }
}
