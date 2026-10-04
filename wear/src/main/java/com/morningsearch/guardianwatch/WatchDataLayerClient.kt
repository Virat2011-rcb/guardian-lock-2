package com.morningsearch.guardianwatch

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WatchDataLayerClient(private val context: Context) {
    fun sendSignedCommand(command: JSONObject): Boolean {
        return runCatching {
            val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes, 2, TimeUnit.SECONDS)
            var sent = false
            for (node in nodes) {
                Tasks.await(
                    Wearable.getMessageClient(context).sendMessage(
                        node.id,
                        PATH_COMMAND,
                        command.toString().toByteArray(Charsets.UTF_8)
                    ),
                    2,
                    TimeUnit.SECONDS
                )
                sent = true
            }
            if (sent) WatchStateStore(context).lastTransport = "Nearby Phone"
            sent
        }.getOrDefault(false)
    }

    fun requestStatus(): Boolean {
        return runCatching {
            val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes, 2, TimeUnit.SECONDS)
            for (node in nodes) {
                Tasks.await(Wearable.getMessageClient(context).sendMessage(node.id, PATH_STATUS, ByteArray(0)), 2, TimeUnit.SECONDS)
            }
            nodes.isNotEmpty()
        }.getOrDefault(false)
    }

    fun requestNearbyPairing(publicKey: String): Boolean {
        return runCatching {
            val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes, 2, TimeUnit.SECONDS)
            var sent = false
            val body = JSONObject().put("publicKey", publicKey).toString().toByteArray(Charsets.UTF_8)
            for (node in nodes) {
                Tasks.await(Wearable.getMessageClient(context).sendMessage(node.id, PATH_PAIR_REQUEST, body), 2, TimeUnit.SECONDS)
                sent = true
            }
            sent
        }.getOrDefault(false)
    }

    companion object {
        const val PATH_COMMAND = "/guardian/command"
        const val PATH_STATUS = "/guardian/status"
        const val PATH_PAIR_REQUEST = "/guardian/pair_request"
    }
}
