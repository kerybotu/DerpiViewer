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
internal object SiteCookieStore {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "derpiviewer_site_cookie_v1"
    private const val OFFICIAL_ORIGIN = "https://derpibooru.org/"
    private const val AAD = "https://derpibooru.org|site-cookie-v1"
    private const val MAX_COOKIE_HEADER_LENGTH = 64 * 1024
    private val COOKIE_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

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

    internal fun applyConsentEvent(
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
                resultAfterClearing { clear(context) }
            }
            null -> Result.NOT_AUTHORIZED
        }
    }

    internal fun syncSnapshot(context: Context, snapshot: OfficialSiteCookieSnapshot): Result {
        if (!hasConsent(context)) return Result.NOT_AUTHORIZED
        if (!CookiePersistencePolicy.isOfficialOrigin(snapshot.sourceOrigin)) {
            return if (clear(context)) Result.INVALID_SNAPSHOT else Result.STORAGE_ERROR
        }
        return persistSnapshot(context, snapshot, grantConsent = false)
    }

    internal fun syncFromWebView(context: Context, cookieManager: CookieManager): Result {
        if (!hasConsent(context)) return Result.NOT_AUTHORIZED
        val snapshot = captureOfficialSnapshot(cookieManager)
            ?: return Result.INVALID_SNAPSHOT
        return syncSnapshot(context, snapshot)
    }

    internal fun read(context: Context): String? {
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

    internal fun hasConsent(context: Context): Boolean =
        preferences(context).getBoolean(KEY_CONSENT, false)

    internal fun hasStoredCookie(context: Context): Boolean = read(context) != null

    internal fun clear(context: Context): Boolean {
        val preferencesCleared = runCatching { preferences(context).edit().clear().commit() }
            .getOrDefault(false)
        val keyDeleted = runCatching {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
            !KeyStore.getInstance(KEYSTORE).apply { load(null) }.containsAlias(KEY_ALIAS)
        }.getOrDefault(false)
        return preferencesCleared && keyDeleted
    }

    internal fun resultAfterClearing(clearAction: () -> Boolean): Result =
        if (runCatching(clearAction).getOrDefault(false)) Result.CLEARED else Result.STORAGE_ERROR

    private fun persistSnapshot(
        context: Context,
        snapshot: OfficialSiteCookieSnapshot,
        grantConsent: Boolean
    ): Result {
        val header = snapshot.cookieHeader
        val plaintext = header.toByteArray(Charsets.UTF_8)
        if (!CookiePersistencePolicy.isOfficialOrigin(snapshot.sourceOrigin) ||
            !isValidCookieHeader(header, plaintext.size)
        ) {
            return if (clear(context)) Result.INVALID_SNAPSHOT else Result.STORAGE_ERROR
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

    private fun isValidCookieHeader(
        header: String,
        byteLength: Int = header.toByteArray(Charsets.UTF_8).size
    ): Boolean {
        if (byteLength > MAX_COOKIE_HEADER_LENGTH ||
            header.any { it == '\r' || it == '\n' || it == '\u0000' || it == '\u007f' || it < ' ' }
        ) return false
        if (header.isEmpty()) return true
        return header.split(';').all { segment ->
            val pair = segment.trim()
            val separator = pair.indexOf('=')
            separator > 0 && COOKIE_NAME.matches(pair.substring(0, separator).trim())
        }
    }

    private fun captureOfficialSnapshot(cookieManager: CookieManager): OfficialSiteCookieSnapshot? {
        if (!CookiePersistencePolicy.isOfficialOrigin(OFFICIAL_ORIGIN)) return null
        val header = runCatching { cookieManager.getCookie(OFFICIAL_ORIGIN).orEmpty() }.getOrNull() ?: return null
        return OfficialSiteCookieSnapshot(header, OFFICIAL_ORIGIN)
    }

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
