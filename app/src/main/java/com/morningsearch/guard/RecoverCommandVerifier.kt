package com.morningsearch.guard

import android.content.Context
import android.util.Base64
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlin.math.abs

data class SignedRecoverCommand(
    val command: String,
    val payload: String,
    val nonce: String,
    val issuedAt: Long,
    val signatureBase64: String
) {
    fun canonicalMessage(): String = "$command\n$payload\n$nonce\n$issuedAt"
}

class RecoverCommandVerifier(private val context: Context) {
    private val store = GuardStore(context)

    fun verify(command: SignedRecoverCommand): Boolean {
        if (command.command.isBlank() || command.nonce.length < 12) return false
        if (abs(System.currentTimeMillis() - command.issuedAt) > COMMAND_WINDOW_MS) return false
        val keyBase64 = store.dashboardPublicKeyBase64
        if (keyBase64.isBlank()) return false
        val verified = runCatching {
            val keyBytes = Base64.decode(keyBase64, Base64.NO_WRAP)
            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
            val signatureBytes = Base64.decode(command.signatureBase64, Base64.NO_WRAP)
            verifyWithSignatureBytes(publicKey, command.canonicalMessage(), signatureBytes) ||
                verifyWithSignatureBytes(publicKey, command.canonicalMessage(), rawP256ToDer(signatureBytes))
        }.getOrDefault(false)
        return verified && store.rememberRecoverCommandNonce(command.nonce)
    }

    private fun verifyWithSignatureBytes(publicKey: java.security.PublicKey, message: String, signatureBytes: ByteArray): Boolean {
        return runCatching {
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initVerify(publicKey)
            signature.update(message.toByteArray(Charsets.UTF_8))
            signature.verify(signatureBytes)
        }.getOrDefault(false)
    }

    private fun rawP256ToDer(raw: ByteArray): ByteArray {
        if (raw.size != 64) return raw
        val r = derInteger(raw.copyOfRange(0, 32))
        val s = derInteger(raw.copyOfRange(32, 64))
        val sequenceLength = r.size + s.size
        return byteArrayOf(0x30, sequenceLength.toByte()) + r + s
    }

    private fun derInteger(bytes: ByteArray): ByteArray {
        val strippedBytes = bytes.dropWhile { it == 0.toByte() }.toByteArray()
        val stripped = if (strippedBytes.isEmpty()) byteArrayOf(0) else strippedBytes
        val positive = if ((stripped[0].toInt() and 0x80) != 0) byteArrayOf(0) + stripped else stripped
        return byteArrayOf(0x02, positive.size.toByte()) + positive
    }

    companion object {
        private const val COMMAND_WINDOW_MS = 5L * 60L * 1000L
    }
}
