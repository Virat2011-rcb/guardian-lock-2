package com.morningsearch.guard

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

class DashboardCommandServer(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    private var socket: ServerSocket? = null

    fun start() {
        if (running) return
        running = true
        executor.execute {
            runCatching {
                ServerSocket(PORT).use { server ->
                    socket = server
                    server.soTimeout = 1000
                    RecoverTimeline(context).record("dashboard_server_started", "Listening on ${localIpAddress()}:$PORT")
                    while (running) {
                        try {
                            server.accept().use { client -> handle(client) }
                        } catch (_: SocketTimeoutException) {
                        }
                    }
                }
            }.onFailure {
                RecoverTimeline(context).record("dashboard_server_failed", it.javaClass.simpleName)
            }
            running = false
            socket = null
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
    }

    private fun handle(client: Socket) {
        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val requestLine = reader.readLine().orEmpty()
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val index = line.indexOf(':')
            if (index > 0) headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
        }
        if (requestLine.startsWith("OPTIONS")) {
            write(client, 204, "")
            return
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = CharArray(contentLength).also { if (contentLength > 0) reader.read(it, 0, contentLength) }.concatToString()
        val response = when {
            requestLine.startsWith("GET /status ") -> dashboardStatus()
            requestLine.startsWith("POST /command ") -> command(body)
            else -> JSONObject().put("ok", false).put("error", "not_found")
        }
        write(client, if (response.optBoolean("ok")) 200 else 400, response.toString())
    }

    private fun command(body: String): JSONObject {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return JSONObject().put("ok", false).put("error", "bad_json")
        val signed = SignedRecoverCommand(
            command = json.optString("command"),
            payload = json.optString("payload"),
            nonce = json.optString("nonce"),
            issuedAt = json.optLong("issuedAt"),
            signatureBase64 = json.optString("signatureBase64")
        )
        val result = GuardianRecoverManager(context).applySignedCommand(signed)
        return when (result) {
            RecoverCommandResult.Applied -> JSONObject().put("ok", true)
            is RecoverCommandResult.Unsupported -> JSONObject().put("ok", false).put("error", result.reason)
            is RecoverCommandResult.Failed -> JSONObject().put("ok", false).put("error", result.reason)
        }
    }

    private fun dashboardStatus(): JSONObject {
        val store = GuardStore(context)
        val status = RecoverDeviceStatus(context).capture()
        return JSONObject()
            .put("ok", true)
            .put("paired", store.dashboardPublicKeyBase64.isNotBlank())
            .put("deviceOwner", LockManager(context).isDeviceOwner)
            .put("lostMode", store.lostModeActive)
            .put("siren", store.recoverAlarmActive)
            .put("flashlight", store.recoverFlashlightActive)
            .put("maintenanceMode", store.maintenanceModeActive)
            .put("maintenanceRemainingMs", store.maintenanceRemainingMs())
            .put("battery", status.batteryPercent)
            .put("charging", status.charging)
            .put("network", status.networkSummary)
            .put("location", status.locationSummary)
            .put("phoneIp", localIpAddress())
            .put("port", PORT)
    }

    private fun write(client: Socket, code: Int, body: String) {
        val text = if (body.isBlank()) "" else body
        val reason = when (code) {
            200 -> "OK"
            204 -> "No Content"
            else -> "Bad Request"
        }
        client.getOutputStream().write(
            buildString {
                append("HTTP/1.1 $code $reason\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
                append("Access-Control-Allow-Headers: Content-Type\r\n")
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Content-Length: ${text.toByteArray().size}\r\n")
                append("Connection: close\r\n\r\n")
                append(text)
            }.toByteArray()
        )
    }

    companion object {
        const val PORT = 8765

        fun localIpAddress(): String {
            return runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress }
                    ?.hostAddress ?: "0.0.0.0"
            }.getOrDefault("0.0.0.0")
        }
    }
}
