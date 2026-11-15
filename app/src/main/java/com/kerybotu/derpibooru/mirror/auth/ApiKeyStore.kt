package com.kerybotu.derpibooru.mirror.auth

import android.content.Context
import com.kerybotu.derpibooru.mirror.AppSettings
import java.security.MessageDigest
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
    private const val KEY_SITE_ORIGIN = "api_key_site_origin"
    private const val KEY_SITE_CIPHERTEXT = "api_key_site_ciphertext_"
    private const val KEY_SITE_IV = "api_key_site_iv_"
    private const val KEY_SITE_USER_ID = "api_key_site_user_id_"
    private const val KEY_LEGACY_MIGRATED = "api_key_legacy_migrated"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun origin(context: Context) = "https://${AppSettings.getTargetDomain(context).lowercase()}/"
    private fun suffix(origin: String) = MessageDigest.getInstance("SHA-256").digest(origin.toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    fun save(context: Context, apiKey: String) = save(context, origin(context), apiKey)
    private fun save(context: Context, origin: String, apiKey: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey(origin)) }
        val encrypted = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        prefs(context).edit()
            .putString(KEY_SITE_CIPHERTEXT + suffix(origin), Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_SITE_IV + suffix(origin), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_SITE_ORIGIN, origin).putBoolean(KEY_LEGACY_MIGRATED, true).commit()
    }

    fun get(context: Context): String? = runCatching { get(context, origin(context)) }.getOrNull()
    private fun get(context: Context, origin: String): String? = runCatching {
        val p = prefs(context)
        val key = suffix(origin)
        val ciphertext = p.getString(KEY_SITE_CIPHERTEXT + key, null) ?: run {
            if (p.getBoolean(KEY_LEGACY_MIGRATED, false) || p.getString(KEY_SITE_ORIGIN, null)?.let { it != origin } == true) return null
            val legacyCipher = p.getString(KEY_CIPHERTEXT, null) ?: return null
            val legacyIv = p.getString(KEY_IV, null) ?: return null
            val legacy = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, Base64.decode(legacyIv, Base64.NO_WRAP)))
                String(doFinal(Base64.decode(legacyCipher, Base64.NO_WRAP)), Charsets.UTF_8)
            }
            save(context, origin, legacy)
            return legacy
        }
        val iv = p.getString(KEY_SITE_IV + key, null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(origin), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        }
        String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    fun clear(context: Context) {
        val origin = origin(context)
        prefs(context).edit().remove(KEY_SITE_CIPHERTEXT + suffix(origin)).remove(KEY_SITE_IV + suffix(origin))
            .remove(KEY_SITE_USER_ID + suffix(origin)).apply()
        if (prefs(context).getString(KEY_SITE_ORIGIN, null) == origin) prefs(context).edit()
            .remove(KEY_CIPHERTEXT).remove(KEY_IV).remove(KEY_USER_ID).remove(KEY_SITE_ORIGIN).apply()
    }
    /** Synchronous managed write used by backup transactions; never changes Cookie state. */
    internal fun replaceForBackup(context: Context, apiKey: String, origin: String = origin(context)): Boolean {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey(origin)) }
        val encrypted = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        val key = suffix(origin)
        return prefs(context).edit().putString(KEY_SITE_CIPHERTEXT + key, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_SITE_IV + key, Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).putString(KEY_SITE_ORIGIN, origin)
            .putBoolean(KEY_LEGACY_MIGRATED, true).remove(KEY_SITE_USER_ID + key).commit()
    }

    internal class BackupCheckpoint internal constructor(internal val values: Map<String, Any?>)
    internal fun backupCheckpoint(context: Context) = BackupCheckpoint(prefs(context).all.toMap())
    internal fun restoreBackupCheckpoint(context: Context, checkpoint: BackupCheckpoint): Boolean {
        val edit = prefs(context).edit().clear()
        checkpoint.values.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Long -> edit.putLong(key, value)
        } }
        return edit.commit()
    }
    fun saveUserId(context: Context, id: Long) = prefs(context).edit().putLong(KEY_SITE_USER_ID + suffix(origin(context)), id).apply()
    fun getUserId(context: Context): Long? = prefs(context).getLong(KEY_SITE_USER_ID + suffix(origin(context)), -1L).takeIf { it > 0L }
    fun isLoggedIn(context: Context): Boolean = !get(context).isNullOrBlank()
    fun masked(context: Context): String = get(context)?.let { if (it.length < 8) "••••" else "${it.take(3)}••••${it.takeLast(3)}" } ?: "未登录"

    private fun getOrCreateKey(origin: String? = null): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val alias = origin?.let { "$KEY_ALIAS-${suffix(it)}" } ?: KEY_ALIAS
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }
}
