package com.morningsearch.guardianwatch

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

class WatchCommandManager(private val context: Context) {
    private val store = WatchStateStore(context)

    fun publicKeyBase64(): String {
        val key = keyPair().getCertificate(ALIAS).publicKey.encoded
        return Base64.encodeToString(key, Base64.NO_WRAP)
    }

    fun sign(command: String, payload: String = ""): SignedWatchCommand {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val issuedAt = System.currentTimeMillis()
        val message = "$command\n$payload\n$nonce\n$issuedAt"
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair().getKey(ALIAS, null) as java.security.PrivateKey)
            update(message.toByteArray(Charsets.UTF_8))
        }.sign()
        return SignedWatchCommand(command, payload, nonce, issuedAt, Base64.encodeToString(signature, Base64.NO_WRAP))
    }

    fun toJson(command: SignedWatchCommand): JSONObject = JSONObject()
        .put("command", command.command)
        .put("payload", command.payload)
        .put("nonce", command.nonce)
        .put("issuedAt", command.issuedAt)
        .put("signatureBase64", command.signatureBase64)

    fun send(command: String, payload: String = ""): String {
        val signed = sign(command, payload)
        val json = toJson(signed)
        val nearby = WatchDataLayerClient(context).sendSignedCommand(json)
        if (nearby) return "Sent through Nearby Phone"
        WatchCloudClient(context, store).queueSignedCommand(json)
        return "Queued through Cloud"
    }

    private fun keyPair(): KeyStore {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(ALIAS)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()
            generator.initialize(spec)
            generator.generateKeyPair()
        }
        return keyStore
    }

    companion object {
        private const val ALIAS = "guardian_watch_command_key_v1"
    }
}
