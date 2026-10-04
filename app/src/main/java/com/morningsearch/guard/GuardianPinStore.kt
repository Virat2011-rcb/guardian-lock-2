package com.morningsearch.guard

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class GuardianPinStore(context: Context) {
    private val preferences = context.getSharedPreferences("guardian_auth", Context.MODE_PRIVATE)

    val hasPin: Boolean
        get() = preferences.contains("pin_hash") && preferences.contains("pin_salt")

    fun setOnce(pin: CharArray): Boolean {
        if (hasPin || pin.size !in 6..10 || pin.any { !it.isDigit() }) {
            pin.fill('\u0000')
            return false
        }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val hash = derive(pin, salt)
        pin.fill('\u0000')
        return preferences.edit()
            .putString("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pin_hash", Base64.encodeToString(hash, Base64.NO_WRAP))
            .commit()
    }

    fun verify(pin: CharArray): Boolean {
        val saltText = preferences.getString("pin_salt", null)
        val hashText = preferences.getString("pin_hash", null)
        if (saltText == null || hashText == null) {
            pin.fill('\u0000')
            return false
        }
        val expected = Base64.decode(hashText, Base64.NO_WRAP)
        val actual = derive(pin, Base64.decode(saltText, Base64.NO_WRAP))
        pin.fill('\u0000')
        return MessageDigest.isEqual(expected, actual)
    }

    private fun derive(pin: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin, salt, 150_000, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
