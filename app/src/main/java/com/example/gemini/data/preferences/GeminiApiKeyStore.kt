package com.example.gemini.data.preferences

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores a user-provided Gemini developer API key encrypted with a non-exportable Android Keystore key. */
object GeminiApiKeyStore {
    private const val PREFS = "gemini_api_credentials"
    private const val VALUE_KEY = "encrypted_gemini_api_key"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "opencode_max_gemini_api_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128

    @Synchronized
    fun save(context: Context, rawKey: String) {
        val key = rawKey.trim()
        require(key.isNotEmpty()) { "Gemini API key cannot be empty." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + encrypted
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(VALUE_KEY, Base64.encodeToString(payload, Base64.NO_WRAP))
            .apply()
    }

    @Synchronized
    fun read(context: Context): String? {
        val encoded = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(VALUE_KEY, null) ?: return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > IV_LENGTH) { "Invalid encrypted Gemini API key." }
            val iv = payload.copyOfRange(0, IV_LENGTH)
            val ciphertext = payload.copyOfRange(IV_LENGTH, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8).takeIf(String::isNotBlank)
        }.getOrNull()
    }

    fun hasKey(context: Context): Boolean = read(context) != null

    @Synchronized
    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(VALUE_KEY)
            .apply()
        runCatching {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        }
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
