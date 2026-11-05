package com.kerybotu.derpibooru.mirror.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.CookieManager
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted, consent-gated storage for a Derpibooru WebView cookie snapshot. */
object SiteCookieStore {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "derpiviewer_site_cookie_v1"
    private const val AAD = "https://derpibooru.org|site-cookie-v1"
    private const val MAX_COOKIE_HEADER_LENGTH = 64 * 1024

    const val PREFERENCES_NAME = "site_cookie_credentials"
    const val KEY_CIPHERTEXT = "site_cookie_ciphertext"
    private const val KEY_IV = "site_cookie_iv"
    private const val KEY_CONSENT = "site_cookie_consent_granted"

    enum class Result {
        SAVED,
        CLEARED,
        NOT_AUTHORIZED,
        INVALID_SNAPSHOT,
        STORAGE_ERROR
    }

    fun applyConsentEvent(
        context: Context,
        event: CookiePersistenceConsentEvent?,
        snapshot: OfficialSiteCookieSnapshot?
    ): Result {
        return when (event) {
            is CookiePersistenceConsentEvent.Granted -> {
                if (snapshot == null) Result.INVALID_SNAPSHOT
                else persistSnapshot(context, snapshot, grantConsent = true)
            }
            CookiePersistenceConsentEvent.Declined -> {
                clear(context)
                Result.CLEARED
            }
            null -> Result.NOT_AUTHORIZED
        }
    }

    fun syncSnapshot(context: Context, snapshot: OfficialSiteCookieSnapshot): Result {
        if (!hasConsent(context)) return Result.NOT_AUTHORIZED
        return persistSnapshot(context, snapshot, grantConsent = false)
    }

    fun syncFromWebView(context: Context, cookieManager: CookieManager): Result {
        if (!hasConsent(context)) return Result.NOT_AUTHORIZED
        val snapshot = OfficialSiteCookieSnapshot.capture(cookieManager)
            ?: return Result.INVALID_SNAPSHOT
        return syncSnapshot(context, snapshot)
    }

    fun read(context: Context): String? {
        val preferences = preferences(context)
        if (!preferences.getBoolean(KEY_CONSENT, false)) return null

        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        val iv = preferences.getString(KEY_IV, null) ?: return invalidate(context)

        return runCatching {
            require(ciphertext.length <= MAX_CIPHERTEXT_LENGTH)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateKey(),
                    GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
                )
                updateAAD(AAD.toByteArray(Charsets.UTF_8))
            }
            val plaintext = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
            require(plaintext.size <= MAX_COOKIE_HEADER_LENGTH)
            String(plaintext, Charsets.UTF_8).takeIf(::isValidCookieHeader)
                ?: invalidate(context)
        }.getOrElse { invalidate(context) }
    }

    fun hasConsent(context: Context): Boolean =
        preferences(context).getBoolean(KEY_CONSENT, false)

    fun hasStoredCookie(context: Context): Boolean = read(context) != null

    fun clear(context: Context) {
        preferences(context).edit().clear().commit()
        runCatching {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    private fun persistSnapshot(
        context: Context,
        snapshot: OfficialSiteCookieSnapshot,
        grantConsent: Boolean
    ): Result {
        val header = snapshot.cookieHeader
        val plaintext = header.toByteArray(Charsets.UTF_8)
        if (!isValidCookieHeader(header, plaintext.size)) {
            clear(context)
            return Result.INVALID_SNAPSHOT
        }

        val editor = preferences(context).edit().clear()
        if (grantConsent || hasConsent(context)) editor.putBoolean(KEY_CONSENT, true)

        if (plaintext.isNotEmpty()) {
            val encrypted = runCatching {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.ENCRYPT_MODE, getOrCreateKey())
                    updateAAD(AAD.toByteArray(Charsets.UTF_8))
                }
                cipher.doFinal(plaintext) to cipher.iv
            }.getOrElse {
                clear(context)
                return Result.STORAGE_ERROR
            }
            editor.putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted.first, Base64.NO_WRAP))
            editor.putString(KEY_IV, Base64.encodeToString(encrypted.second, Base64.NO_WRAP))
        }

        if (!editor.commit()) {
            clear(context)
            return Result.STORAGE_ERROR
        }
        return if (header.isEmpty()) Result.CLEARED else Result.SAVED
    }

    private fun invalidate(context: Context): Nothing? {
        clear(context)
        return null
    }

    private fun isValidCookieHeader(header: String, byteLength: Int = header.toByteArray(Charsets.UTF_8).size): Boolean =
        byteLength <= MAX_COOKIE_HEADER_LENGTH &&
            header.none { it == '\r' || it == '\n' || it == '\u0000' }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private const val MAX_CIPHERTEXT_LENGTH = MAX_COOKIE_HEADER_LENGTH * 2
}
