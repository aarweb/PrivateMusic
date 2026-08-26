package com.aar.privatemusic.data

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Cifra credenciales con una clave no exportable del Android Keystore. */
internal object SecretStore {
    private const val ALIAS = "privatemusic_credentials_v1"
    private const val PREFIX = "enc:v1:"

    fun read(prefs: SharedPreferences, key: String): String {
        val stored = prefs.getString(key, "").orEmpty()
        if (stored.isBlank() || stored.startsWith(PREFIX)) return decrypt(stored)
        // Migración transparente de instalaciones anteriores.
        write(prefs, key, stored)
        return stored
    }

    fun write(prefs: SharedPreferences, key: String, value: String) {
        val stored = if (value.isBlank()) "" else encrypt(value)
        prefs.edit().putString(key, stored).apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        return runCatching {
            val payload = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            require(payload.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, payload, 0, 12))
            cipher.doFinal(payload, 12, payload.size - 12).toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

    @Synchronized
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }
}
