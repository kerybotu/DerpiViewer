package com.kerybotu.derpibooru.mirror.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.CookieManager
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Consent-gated, encrypted WebView cookie snapshots isolated by HTTPS origin. */
internal object SiteCookieStore {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS_PREFIX = "derpiviewer_site_cookie_v2_"
    private const val LEGACY_KEY_ALIAS = "derpiviewer_site_cookie_v1"
    private const val OFFICIAL_ORIGIN = "https://derpibooru.org/"
    private const val MAX_COOKIE_HEADER_LENGTH = 64 * 1024
    private val COOKIE_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    const val PREFERENCES_NAME = "site_cookie_credentials"
    const val KEY_CIPHERTEXT = "site_cookie_data_official_ciphertext"
    private const val KEY_IV = "site_cookie_data_official_iv"
    private const val LEGACY_KEY_CIPHERTEXT = "site_cookie_ciphertext"
    private const val LEGACY_KEY_IV = "site_cookie_iv"
    private const val KEY_CONSENT = "site_cookie_consent_granted"
    private const val KEY_STATE_PREFIX = "site_cookie_state_"
    private const val KEY_DATA_PREFIX = "site_cookie_data_"
    private const val KEY_UPDATED_PREFIX = "site_cookie_updated_"
    private const val KEY_AUTH_STATE_PREFIX = "site_cookie_auth_state_"
    private const val SNAPSHOT_MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

    enum class Result { SAVED, CLEARED, NOT_AUTHORIZED, INVALID_SNAPSHOT, STORAGE_ERROR }

    /** Full attributes are available only for imported records, never inferred from Cookie headers. */
    internal fun readBackupCookies(context: Context, origin: String): List<BackupCookie>? {
        val normalized = normalizeOrigin(origin) ?: return null
        val header = read(context, normalized) ?: return null
        val data = preferences(context).getString("backup_records_" + hash(normalized), null) ?: return null
        return runCatching {
            val parts = data.split(':')
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, getOrCreateKey(normalized), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
                updateAAD((normalized + "|backup-records-v1").toByteArray())
            }
            val plain = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
            try {
                val cookies = com.google.gson.Gson().fromJson(String(plain), Array<BackupCookie>::class.java).toList()
                if (cookies.joinToString("; ") { "${it.name}=${it.value}" } != header) null
                else cookies.filter { it.expiresAt == null || it.expiresAt > System.currentTimeMillis() }
            } finally { plain.fill(0) }
        }.getOrNull()
    }

    /** Both encrypted header and full attributes commit together, using the existing origin key. */
    internal fun replaceForBackup(context: Context, origin: String, cookies: List<BackupCookie>): Boolean {
        val normalized = normalizeOrigin(origin) ?: return false
        val keys = keysFor(normalized)
        fun encrypt(plain: ByteArray, aad: String): Pair<String, String> = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, getOrCreateKey(normalized)); updateAAD(aad.toByteArray())
            }.let { Base64.encodeToString(it.doFinal(plain), Base64.NO_WRAP) to Base64.encodeToString(it.iv, Base64.NO_WRAP) }
        } finally { plain.fill(0) }
        val header = cookies.joinToString("; ") { "${it.name}=${it.value}" }
        require(isValidCookieHeader(header))
        val encrypted = encrypt(header.toByteArray(), normalized)
        val records = encrypt(com.google.gson.Gson().toJson(cookies).toByteArray(), normalized + "|backup-records-v1")
        return preferences(context).edit().putString(keys.ciphertext, encrypted.first).putString(keys.iv, encrypted.second)
            .putString("backup_records_" + hash(normalized), records.second + ":" + records.first)
            .putString(keys.state, "granted").putLong(KEY_UPDATED_PREFIX + hash(normalized), System.currentTimeMillis())
            .remove(KEY_AUTH_STATE_PREFIX + hash(normalized)).commit()
    }

    internal class BackupCheckpoint internal constructor(internal val values: Map<String, Any?>)
    internal fun backupCheckpoint(context: Context, origin: String): BackupCheckpoint {
        val normalized = requireNotNull(normalizeOrigin(origin))
        val keys = keysFor(normalized)
        val names = setOf(keys.state, keys.ciphertext, keys.iv, KEY_UPDATED_PREFIX + hash(normalized),
            KEY_AUTH_STATE_PREFIX + hash(normalized), "backup_records_" + hash(normalized))
        return BackupCheckpoint(names.associateWith { preferences(context).all[it] })
    }
    internal fun restoreBackupCheckpoint(context: Context, checkpoint: BackupCheckpoint): Boolean {
        val edit = preferences(context).edit()
        checkpoint.values.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Long -> edit.putLong(key, value)
            else -> edit.remove(key)
        } }
        return edit.commit()
    }

    internal fun applyConsentEvent(context: Context, event: CookiePersistenceConsentEvent?, snapshot: OfficialSiteCookieSnapshot?): Result = when (event) {
        is CookiePersistenceConsentEvent.Granted -> if (snapshot == null) Result.INVALID_SNAPSHOT else persistSnapshot(context, snapshot, true)
        CookiePersistenceConsentEvent.Declined -> decline(context, snapshot?.sourceOrigin ?: OFFICIAL_ORIGIN)
        null -> Result.NOT_AUTHORIZED
    }

    internal fun grantFromWebView(context: Context, manager: CookieManager, origin: String): Result {
        val normalized = normalizeOrigin(origin) ?: return Result.INVALID_SNAPSHOT
        val header = runCatching { manager.getCookie(normalized).orEmpty() }.getOrNull() ?: return Result.INVALID_SNAPSHOT
        if (header.isBlank()) return Result.INVALID_SNAPSHOT
        return persistSnapshot(context, OfficialSiteCookieSnapshot(header, normalized), true)
    }

    internal fun decline(context: Context, origin: String): Result = resultAfterClearing { clear(context, origin) }.also { setState(context, origin, "declined") }

    internal fun syncSnapshot(context: Context, snapshot: OfficialSiteCookieSnapshot): Result {
        val origin = normalizeOrigin(snapshot.sourceOrigin) ?: return if (clear(context)) Result.INVALID_SNAPSHOT else Result.STORAGE_ERROR
        if (!hasConsent(context, origin)) return Result.NOT_AUTHORIZED
        return persistSnapshot(context, OfficialSiteCookieSnapshot(snapshot.cookieHeader, origin), false)
    }

    internal fun syncFromWebView(context: Context, manager: CookieManager, origin: String = OFFICIAL_ORIGIN): Result {
        val normalized = normalizeOrigin(origin) ?: return Result.INVALID_SNAPSHOT
        if (!hasConsent(context, normalized)) return Result.NOT_AUTHORIZED
        // Keep imported attributes through ordinary response-cookie refreshes. A
        // Cookie header alone cannot represent path-specific or host-only scope.
        val records = readBackupCookies(context, normalized)
        if (records != null && androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.GET_COOKIE_INFO)) {
            return runCatching {
                val base = okhttp3.HttpUrl.Builder().scheme("https").host(URI(normalized).host).build()
                val updated = (records.map { it.path } + "/").distinct().flatMap { path ->
                    val url = base.newBuilder().encodedPath(path).build()
                    androidx.webkit.CookieManagerCompat.getCookieInfo(manager, url.toString()).map { BackupCookie.fromWebView(url, it) }
                }.filter { it.domain == base.host && !BackupCookie.isChallenge(it.name) }
                    .distinctBy { Triple(it.name, it.domain, it.path) }
                if (updated.isEmpty()) {
                    persistSnapshot(context, OfficialSiteCookieSnapshot("", normalized), false)
                } else {
                    CredentialBackupCodec.validate(EncryptedExportPayload(exportedAt = System.currentTimeMillis(), origin = normalized,
                        includesCookies = true, includesApiKey = false, cookies = updated, apiKey = null))
                    if (replaceForBackup(context, normalized, updated)) Result.SAVED else Result.STORAGE_ERROR
                }
            }.getOrDefault(Result.STORAGE_ERROR)
        }
        val header = runCatching { manager.getCookie(normalized).orEmpty() }.getOrNull() ?: return Result.INVALID_SNAPSHOT
        return syncSnapshot(context, OfficialSiteCookieSnapshot(header, normalized))
    }

    internal fun read(context: Context, origin: String = OFFICIAL_ORIGIN): String? {
        val normalized = normalizeOrigin(origin) ?: return null
        if (!hasConsent(context, normalized)) return null
        val prefs = preferences(context)
        val keys = keysFor(normalized)
        val ciphertext = prefs.getString(keys.ciphertext, null) ?: run {
            if (normalized != OFFICIAL_ORIGIN || prefs.getString(LEGACY_KEY_CIPHERTEXT, null) == null) return null
            val legacy = decryptLegacy(prefs) ?: return invalidate(context, normalized)
            return if (persistSnapshot(context, OfficialSiteCookieSnapshot(legacy, normalized), true) == Result.SAVED) legacy else invalidate(context, normalized)
        }
        val updatedAt = prefs.getLong(KEY_UPDATED_PREFIX + hash(normalized), 0L)
        if (!isSnapshotFresh(updatedAt, System.currentTimeMillis())) return invalidate(context, normalized)
        val iv = prefs.getString(keys.iv, null) ?: return invalidate(context, normalized)
        return runCatching {
            require(ciphertext.length <= MAX_COOKIE_HEADER_LENGTH * 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, getOrCreateKey(normalized), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
                updateAAD(normalized.toByteArray(Charsets.UTF_8))
            }
            val plaintext = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
            require(plaintext.size <= MAX_COOKIE_HEADER_LENGTH)
            String(plaintext, Charsets.UTF_8).takeIf(::isValidCookieHeader) ?: invalidate(context, normalized)
        }.getOrElse { invalidate(context, normalized) }
    }

    internal fun hasConsent(context: Context, origin: String = OFFICIAL_ORIGIN): Boolean {
        val normalized = normalizeOrigin(origin) ?: return false
        val prefs = preferences(context)
        val keys = keysFor(normalized)
        val legacyGranted = normalized == OFFICIAL_ORIGIN && prefs.getBoolean(KEY_CONSENT, false)
        return prefs.getString(keys.state, null) == "granted" || legacyGranted
    }

    internal fun hasDecision(context: Context, origin: String = OFFICIAL_ORIGIN): Boolean {
        val normalized = normalizeOrigin(origin) ?: return false
        return preferences(context).contains(keysFor(normalized).state) ||
            (normalized == OFFICIAL_ORIGIN && preferences(context).contains(KEY_CONSENT))
    }

    internal fun hasStoredCookie(context: Context, origin: String = OFFICIAL_ORIGIN): Boolean = read(context, origin) != null

    internal fun isAuthenticationExpired(context: Context, origin: String): Boolean {
        val normalized = normalizeOrigin(origin) ?: return false
        return preferences(context).getString(KEY_AUTH_STATE_PREFIX + hash(normalized), null) == "rejected"
    }

    internal fun requestContainsPersistedCookie(context: Context, origin: String, requestCookieHeader: String?): Boolean {
        val snapshot = read(context, origin) ?: return false
        val sent = requestCookieHeader?.split(';')?.mapNotNull(::cookiePair)?.toSet().orEmpty()
        val persisted = snapshot.split(';').mapNotNull(::cookiePair).toSet()
        return persisted.any { it in sent }
    }

    internal fun noteAuthenticationRejected(context: Context, origin: String): Boolean {
        val normalized = normalizeOrigin(origin) ?: return false
        if (!hasConsent(context, normalized)) return false
        val keys = keysFor(normalized)
        return preferences(context).edit().remove(keys.ciphertext).remove(keys.iv)
            .remove("backup_records_" + hash(normalized))
            .remove(KEY_UPDATED_PREFIX + hash(normalized))
            .putString(KEY_AUTH_STATE_PREFIX + hash(normalized), "rejected").commit()
    }

    internal fun clearWebViewOrigin(manager: CookieManager, origin: String, context: Context? = null) {
        val normalized = normalizeOrigin(origin) ?: return
        if (context != null) readBackupCookies(context, normalized)?.forEach { record ->
            manager.setCookie(normalized + record.path.removePrefix("/"),
                BackupCookie(record.name, "", record.domain, record.path, record.secure,
                    record.httpOnly, record.hostOnly, 0L).cookie().toString())
        }
        val names = runCatching { manager.getCookie(normalized).orEmpty() }.getOrNull().orEmpty().split(';')
            .mapNotNull { it.trim().substringBefore('=').takeIf { name -> name.isNotBlank() } }
        names.forEach { name -> manager.setCookie(normalized, "$name=; Path=/; Max-Age=0; Secure") }
        manager.flush()
    }

    internal fun clear(context: Context, origin: String? = null): Boolean {
        val prefs = preferences(context)
        val normalized = origin?.let(::normalizeOrigin)
        if (origin != null && normalized == null) return false
        val keys = normalized?.let(::keysFor)
        val editor = prefs.edit()
        if (normalized == null) {
            editor.clear()
        } else {
            editor.remove(keys!!.state).remove(keys.ciphertext).remove(keys.iv).remove(KEY_UPDATED_PREFIX + hash(normalized)).remove(KEY_AUTH_STATE_PREFIX + hash(normalized))
            editor.remove("backup_records_" + hash(normalized))
            if (normalized == OFFICIAL_ORIGIN) editor.remove(KEY_CONSENT).remove(KEY_CIPHERTEXT).remove(KEY_IV).remove(LEGACY_KEY_CIPHERTEXT).remove(LEGACY_KEY_IV)
        }
        val deleted = runCatching {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (normalized == null) store.aliases().toList().filter { it.startsWith(KEY_ALIAS_PREFIX) || it == LEGACY_KEY_ALIAS }.forEach(store::deleteEntry)
            else {
                if (store.containsAlias(keys!!.alias)) store.deleteEntry(keys.alias)
                if (normalized == OFFICIAL_ORIGIN && store.containsAlias(LEGACY_KEY_ALIAS)) store.deleteEntry(LEGACY_KEY_ALIAS)
            }
            true
        }.getOrDefault(false)
        return editor.commit() && deleted
    }

    internal fun resultAfterClearing(clearAction: () -> Boolean): Result = if (runCatching(clearAction).getOrDefault(false)) Result.CLEARED else Result.STORAGE_ERROR

    private fun persistSnapshot(context: Context, snapshot: OfficialSiteCookieSnapshot, grantConsent: Boolean): Result {
        val origin = normalizeOrigin(snapshot.sourceOrigin) ?: return Result.INVALID_SNAPSHOT
        val header = snapshot.cookieHeader.split(';').map { it.trim() }.filter { part ->
            val name = part.substringBefore('=').trim().lowercase()
            name.isNotBlank() && name !in setOf("cf_clearance", "__cf_bm") && !name.startsWith("anubis")
        }.joinToString("; ")
        if (!isValidCookieHeader(header)) return invalidateResult(context, origin)
        val keys = keysFor(origin)
        val editor = preferences(context).edit().remove(keys.ciphertext).remove(keys.iv)
            .remove("backup_records_" + hash(origin))
            .putString(keys.state, if (grantConsent || hasConsent(context, origin)) "granted" else "declined")
            .putLong(KEY_UPDATED_PREFIX + hash(origin), System.currentTimeMillis())
            .remove(KEY_AUTH_STATE_PREFIX + hash(origin))
        if (origin == OFFICIAL_ORIGIN) editor.putBoolean(KEY_CONSENT, true)
        if (header.isNotEmpty()) {
            val encrypted = runCatching {
                Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey(origin)); updateAAD(origin.toByteArray()) }.let { it.doFinal(header.toByteArray()) to it.iv }
            }.getOrElse { clear(context, origin); return Result.STORAGE_ERROR }
            editor.putString(keys.ciphertext, Base64.encodeToString(encrypted.first, Base64.NO_WRAP)).putString(keys.iv, Base64.encodeToString(encrypted.second, Base64.NO_WRAP))
        }
        return if (editor.commit()) {
            if (origin == OFFICIAL_ORIGIN && grantConsent) runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(LEGACY_KEY_ALIAS) }
            if (header.isEmpty()) Result.CLEARED else Result.SAVED
        } else { clear(context, origin); Result.STORAGE_ERROR }
    }

    private data class Keys(val state: String, val ciphertext: String, val iv: String, val alias: String)
    private fun keysFor(origin: String): Keys {
        val hash = hash(origin)
        return if (origin == OFFICIAL_ORIGIN) Keys(KEY_STATE_PREFIX + "official", KEY_DATA_PREFIX + "official_ciphertext", KEY_DATA_PREFIX + "official_iv", KEY_ALIAS_PREFIX + hash)
        else Keys(KEY_STATE_PREFIX + hash, KEY_DATA_PREFIX + hash + "_ciphertext", KEY_DATA_PREFIX + hash + "_iv", KEY_ALIAS_PREFIX + hash)
    }

    private fun setState(context: Context, origin: String, state: String) {
        val normalized = normalizeOrigin(origin) ?: return
        preferences(context).edit().putString(keysFor(normalized).state, state).apply { if (normalized == OFFICIAL_ORIGIN) remove(KEY_CONSENT) }.apply()
    }
    private fun invalidate(context: Context, origin: String): Nothing? { clear(context, origin); return null }
    private fun invalidateResult(context: Context, origin: String): Result { clear(context, origin); return Result.INVALID_SNAPSHOT }

    private fun isValidCookieHeader(header: String): Boolean {
        if (header.toByteArray().size > MAX_COOKIE_HEADER_LENGTH || header.any { it == '\r' || it == '\n' || it == '\u0000' || it == '\u007f' || it < ' ' }) return false
        if (header.isEmpty()) return true
        return header.split(';').all { segment -> val pair = segment.trim(); val i = pair.indexOf('='); i > 0 && COOKIE_NAME.matches(pair.substring(0, i).trim()) }
    }

    private fun cookiePair(part: String): String? {
        val trimmed = part.trim()
        val separator = trimmed.indexOf('=')
        return trimmed.takeIf { separator > 0 && COOKIE_NAME.matches(trimmed.substring(0, separator).trim()) }
    }

    private fun normalizeOrigin(origin: String): String? {
        if (!CookiePersistencePolicy.isPersistableOrigin(origin)) return null
        val uri = runCatching { URI(origin.trim()) }.getOrNull() ?: return null
        return "https://${uri.host.lowercase()}/"
    }
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    internal fun isSnapshotFresh(capturedAt: Long, now: Long): Boolean = capturedAt > 0L && now >= capturedAt && now - capturedAt <= SNAPSHOT_MAX_AGE_MS
    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private fun getOrCreateKey(origin: String): SecretKey {
        val alias = keysFor(origin).alias
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build()) }.generateKey()
    }
    private fun decryptLegacy(prefs: android.content.SharedPreferences): String? = runCatching {
        val legacyCiphertext = prefs.getString(LEGACY_KEY_CIPHERTEXT, null) ?: return null
        val legacyIv = prefs.getString(LEGACY_KEY_IV, null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, getLegacyKey(), GCMParameterSpec(128, Base64.decode(legacyIv, Base64.NO_WRAP)))
            updateAAD("https://derpibooru.org|site-cookie-v1".toByteArray())
        }
        String(cipher.doFinal(Base64.decode(legacyCiphertext, Base64.NO_WRAP))).takeIf(::isValidCookieHeader)
    }.getOrNull()
    private fun getLegacyKey(): SecretKey = (KeyStore.getInstance(KEYSTORE).apply { load(null) }.getKey(LEGACY_KEY_ALIAS, null) as? SecretKey)
        ?: throw IllegalStateException("legacy cookie key unavailable")
}
