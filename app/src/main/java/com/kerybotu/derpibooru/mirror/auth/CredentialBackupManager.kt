package com.kerybotu.derpibooru.mirror.auth

import android.content.Context
import android.webkit.CookieManager
import androidx.webkit.CookieManagerCompat
import androidx.webkit.WebViewFeature
import com.kerybotu.derpibooru.mirror.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.coroutines.resume

/** All secrets remain in memory; only the codec output may be given to a document provider. */
internal class CredentialBackupManager(
    private val context: Context,
    private val saveKey: (Context, String, String) -> Boolean = { ctx, key, origin -> ApiKeyStore.replaceForBackup(ctx, key, origin) }
) {
    companion object { private val imports = Mutex() }
    fun currentOrigin() = CredentialBackupCodec.origin("https://${AppSettings.getTargetDomain(context)}/")

    private suspend fun webCookies(origin: String): List<BackupCookie> = withContext(Dispatchers.Main) {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO))
        val url = origin.toHttpUrl()
        CookieManagerCompat.getCookieInfo(CookieManager.getInstance(), origin).map { raw ->
            // WebView's cookie-info serialization includes Domain even for host-only
            // cookies. Chromium encodes domain cookies with a leading dot; treating
            // every Domain attribute as a domain cookie prevents host-only deletion.
            BackupCookie.fromWebView(url, raw)
        }.filter { !BackupCookie.isChallenge(it.name) }
    }

    private suspend fun knownCookies(origin: String): List<BackupCookie> {
        val live = webCookies(origin)
        val stored = withContext(Dispatchers.IO) { SiteCookieStore.readBackupCookies(context, origin).orEmpty() }
        return (live + stored.filter { old -> live.none { it.name == old.name && it.domain == old.domain && it.path == old.path } })
            .filter { it.expiresAt == null || it.expiresAt > System.currentTimeMillis() }
    }

    suspend fun capture(cookies: Boolean, apiKey: Boolean): EncryptedExportPayload {
        require(cookies || apiKey)
        val origin = currentOrigin()
        // Imported path-specific records may not appear in a root URL query.
        val records = if (cookies) knownCookies(origin) else null
        val key = if (apiKey) withContext(Dispatchers.IO) { requireNotNull(ApiKeyStore.get(context)) } else null
        return EncryptedExportPayload(exportedAt = System.currentTimeMillis(), origin = origin,
            includesCookies = cookies, includesApiKey = apiKey, cookies = records, apiKey = key)
            .also { CredentialBackupCodec.validate(it); check(currentOrigin() == origin) }
    }

    private suspend fun setCookie(origin: String, cookie: BackupCookie, delete: Boolean = false) = withContext(Dispatchers.Main) {
        val header = if (delete) {
            // Replace expiry rather than append a second expiry to a live cookie.
            BackupCookie(cookie.name, "", cookie.domain, cookie.path, cookie.secure,
                cookie.httpOnly, cookie.hostOnly, 0L).cookie().toString()
        } else cookie.cookie().toString()
        val url = origin.toHttpUrl().newBuilder().encodedPath(cookie.path).build().toString()
        withTimeout(5_000) { suspendCancellableCoroutine<Unit> { continuation ->
            CookieManager.getInstance().setCookie(url, header) { accepted ->
                if (continuation.isActive) {
                    if (accepted) continuation.resume(Unit)
                    else continuation.resumeWith(Result.failure(IllegalStateException("cookie_store_failed")))
                }
            }
        } }
    }

    suspend fun restore(payload: EncryptedExportPayload): Boolean = imports.withLock {
        CredentialBackupCodec.validate(payload)
        check(currentOrigin() == payload.origin)
        val origin = payload.origin
        val previousCookies = if (payload.includesCookies) knownCookies(origin) else emptyList()
        withContext(Dispatchers.IO + NonCancellable) {
            check(currentOrigin() == origin)
            val cookieCheckpoint = if (payload.includesCookies) SiteCookieStore.backupCheckpoint(context, origin) else null
            val keyCheckpoint = if (payload.includesApiKey) ApiKeyStore.backupCheckpoint(context) else null
            try {
                if (payload.includesCookies) {
                    previousCookies.forEach { setCookie(origin, it, delete = true) }
                    payload.cookies!!.forEach { setCookie(origin, it) }
                    withContext(Dispatchers.Main) { CookieManager.getInstance().flush() }
                    check(SiteCookieStore.replaceForBackup(context, origin, payload.cookies))
                }
                if (payload.includesApiKey) check(saveKey(context, payload.apiKey!!, origin))
                check(currentOrigin() == origin)
                true
            } catch (_: Exception) {
                var recovered = true
                if (payload.includesCookies) {
                    recovered = runCatching {
                        payload.cookies!!.forEach { setCookie(origin, it, delete = true) }
                        previousCookies.forEach { setCookie(origin, it) }
                        withContext(Dispatchers.Main) { CookieManager.getInstance().flush() }
                    }.isSuccess
                    recovered = SiteCookieStore.restoreBackupCheckpoint(context, cookieCheckpoint!!) && recovered
                }
                if (keyCheckpoint != null) recovered = ApiKeyStore.restoreBackupCheckpoint(context, keyCheckpoint) && recovered
                // Tell UI if storage failure also prevented compensation. Never report partial success.
                if (!recovered) throw IllegalStateException("backup_rollback_failed")
                false
            }
        }
    }
}
