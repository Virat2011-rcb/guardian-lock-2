package com.morningsearch.guard

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Authenticated, push-first command transport. The server still keeps every
 * command in its durable HTTP queue; this socket only reduces delivery delay.
 */
class CloudWebSocketManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val store = GuardStore(appContext)
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private var socket: WebSocket? = null
    private var reconnectAttempt = 0
    private var reconnectScheduled = false
    private var stopped = false

    @Synchronized
    fun start() {
        stopped = false
        connectIfConfigured()
    }

    @Synchronized
    fun stop() {
        stopped = true
        reconnectScheduled = false
        handler.removeCallbacksAndMessages(null)
        socket?.close(1000, "service_stopped")
        socket = null
        setState("offline")
    }

    @Synchronized
    private fun connectIfConfigured() {
        if (stopped || socket != null) return
        val base = normalizedBaseUrl() ?: run {
            setState("offline")
            return
        }
        val token = store.recoveryUploadToken.trim()
        if (token.isBlank()) {
            setState("fallback_http")
            return
        }
        setState("connecting")
        // Obtain a short-lived, role-bound ticket over authenticated HTTPS
        // before opening WSS. The long-lived recovery token stays in the
        // request header and is never placed in the WebSocket URL.
        val ticketRequest = Request.Builder()
            .url("$base/api/ws-ticket?role=phone")
            .header("X-Guardian-Token", token)
            .get()
            .build()
        client.newCall(ticketRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                synchronized(this@CloudWebSocketManager) {
                    if (!stopped) {
                        setState("fallback_http")
                        scheduleReconnectLocked()
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val ticket = runCatching {
                        if (!it.isSuccessful) null else JSONObject(it.body?.string().orEmpty()).optString("ticket").takeIf(String::isNotBlank)
                    }.getOrNull()
                    synchronized(this@CloudWebSocketManager) {
                        if (stopped || ticket.isNullOrBlank()) {
                            if (!stopped) {
                                setState("fallback_http")
                                scheduleReconnectLocked()
                            }
                            return
                        }
                        val encodedTicket = URLEncoder.encode(ticket, "UTF-8")
                        val request = Request.Builder()
                            .url("${base.replaceFirst(Regex("^http"), "ws")}/ws?role=phone&ticket=$encodedTicket")
                            .build()
                        socket = client.newWebSocket(request, listener)
                    }
                }
            }
        })
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(this@CloudWebSocketManager) {
                socket = webSocket
                reconnectAttempt = 0
                reconnectScheduled = false
                setState("online")
            }
            webSocket.send(JSONObject().put("type", "hello").put("role", "phone").toString())
            // Reconcile anything queued while the phone was offline.
            RecoverUploadManager(appContext).pollRemoteCommandsAsync(force = true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (message.optString("type")) {
                "ping" -> webSocket.send(JSONObject().put("type", "pong").put("at", System.currentTimeMillis()).toString())
                "command" -> {
                    val id = message.optString("id")
                    val command = message.optJSONObject("command") ?: return
                    val receivedAt = System.currentTimeMillis()
                    val executionStartedAt = System.currentTimeMillis()
                    val result = RecoverUploadManager(appContext).applyPushedCommand(command)
                    val executionFinishedAt = System.currentTimeMillis()
                    sendAck(id, result, receivedAt, executionStartedAt, executionFinishedAt)
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(this@CloudWebSocketManager) {
                if (socket === webSocket) socket = null
                scheduleReconnectLocked()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(this@CloudWebSocketManager) {
                if (socket === webSocket) socket = null
                setState("fallback_http")
                scheduleReconnectLocked()
            }
        }
    }

    @Synchronized
    private fun scheduleReconnectLocked() {
        if (stopped || reconnectScheduled) return
        reconnectScheduled = true
        val delay = min(30_000L, 1_000L shl reconnectAttempt.coerceAtMost(5))
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(6)
        handler.postDelayed({
            synchronized(this) {
                reconnectScheduled = false
                connectIfConfigured()
            }
        }, delay)
    }

    @Synchronized
    fun isConnected(): Boolean = socket != null && store.cloudTransportState == "online"

    @Synchronized
    fun sendAck(
        id: String,
        result: RecoverCommandResult,
        phoneReceivedAt: Long = System.currentTimeMillis(),
        executionStartedAt: Long = phoneReceivedAt,
        executionFinishedAt: Long = System.currentTimeMillis(),
    ): Boolean {
        if (id.isBlank()) return false
        val payload = RecoverUploadManager.commandResultJson(result)
            .put("phoneReceivedAt", phoneReceivedAt)
            .put("executionStartedAt", executionStartedAt)
            .put("executionFinishedAt", executionFinishedAt)
            .put("ackSentAt", System.currentTimeMillis())
        return socket?.send(JSONObject().put("type", "ack").put("id", id).put("result", payload).toString()) == true
    }

    @Synchronized
    fun sendEvent(type: String, detail: String): Boolean {
        return socket?.send(
            JSONObject().put("type", "event")
                .put("event", JSONObject().put("type", type).put("detail", detail.take(600)).put("timestamp", System.currentTimeMillis()).put("source", "phone"))
                .toString()
        ) == true
    }

    @Synchronized
    fun sendStatus(status: JSONObject): Boolean {
        return socket?.send(JSONObject().put("type", "status").put("status", status).toString()) == true
    }

    @Synchronized
    fun sendLocationUpdate(location: JSONObject): Boolean {
        return socket?.send(JSONObject().put("type", "location_update").put("location", location).toString()) == true
    }

    private fun setState(value: String) {
        store.cloudTransportState = value
        store.cloudTransportLastChange = System.currentTimeMillis()
    }

    private fun normalizedBaseUrl(): String? {
        val value = store.recoveryUploadUrl.trim().trimEnd('/')
        if (!value.startsWith("http://") && !value.startsWith("https://")) return null
        return value
    }

    companion object {
        @Volatile private var instance: CloudWebSocketManager? = null

        fun get(context: Context): CloudWebSocketManager =
            instance ?: synchronized(this) {
                instance ?: CloudWebSocketManager(context).also { instance = it }
            }
    }
}
