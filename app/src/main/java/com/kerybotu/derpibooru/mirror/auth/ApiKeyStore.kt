package com.kerybotu.derpibooru.mirror.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the user's long-lived API credential using Android Keystore-backed encryption. */
object ApiKeyStore {
    private const val PREFS_NAME = "api_credentials"
    private const val KEY_CIPHERTEXT = "api_key_ciphertext"
    private const val KEY_IV = "api_key_iv"
    private const val KEY_USER_ID = "user_id"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "derpiviewer_api_key_v1"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(context: Context, apiKey: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }
        val encrypted = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        prefs(context).edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun get(context: Context): String? = runCatching {
        val ciphertext = prefs(context).getString(KEY_CIPHERTEXT, null) ?: return null
        val iv = prefs(context).getString(KEY_IV, null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        }
        String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    fun clear(context: Context) = prefs(context).edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).remove(KEY_USER_ID).apply()
    fun saveUserId(context: Context, id: Long) = prefs(context).edit().putLong(KEY_USER_ID, id).apply()
    fun getUserId(context: Context): Long? = prefs(context).getLong(KEY_USER_ID, -1L).takeIf { it > 0L }
    fun isLoggedIn(context: Context): Boolean = !get(context).isNullOrBlank()
    fun masked(context: Context): String = get(context)?.let { if (it.length < 8) "••••" else "${it.take(3)}••••${it.takeLast(3)}" } ?: "未登录"

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }
}
