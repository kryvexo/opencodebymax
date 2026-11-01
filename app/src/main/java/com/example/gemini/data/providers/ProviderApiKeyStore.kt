package com.example.gemini.data.providers

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts per-provider API keys at rest with an app-local, non-exportable Android Keystore key. */
object ProviderApiKeyStore {
    private const val PREFS = "maxcode_provider_credentials"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "maxcode_provider_credentials_aes_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128

    @Synchronized
    fun save(context: Context, providerId: String, rawKey: String) {
        val id = safeId(providerId)
        val key = rawKey.trim()
        require(key.isNotEmpty()) { "API key cannot be empty." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val payload = cipher.iv + cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        prefs(context).edit().putString("key_$id", Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
    }

    @Synchronized
    fun read(context: Context, providerId: String): String? {
        val id = safeId(providerId)
        val encoded = prefs(context).getString("key_$id", null) ?: return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > IV_LENGTH) { "Invalid encrypted API key." }
            val iv = payload.copyOfRange(0, IV_LENGTH)
            val encrypted = payload.copyOfRange(IV_LENGTH, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8).takeIf(String::isNotBlank)
        }.getOrNull()
    }

    fun hasKey(context: Context, providerId: String): Boolean = read(context, providerId) != null

    @Synchronized
    fun clear(context: Context, providerId: String) {
        prefs(context).edit().remove("key_${safeId(providerId)}").apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun safeId(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9._-]{1,100}"))) { "Invalid provider ID." }
        return value
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
