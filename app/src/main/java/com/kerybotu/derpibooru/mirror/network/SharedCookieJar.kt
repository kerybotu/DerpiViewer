package com.kerybotu.derpibooru.mirror.network

import android.content.Context
import android.webkit.CookieManager
import com.kerybotu.derpibooru.mirror.auth.SiteCookieStore
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/** Bridges the WebView cookie store to OkHttp so challenge cookies are reusable by API calls. */
class SharedCookieJar(private val context: Context? = null) : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val manager = CookieManager.getInstance()
        if (cookies.isEmpty()) return
        val origin = "${url.scheme}://${url.host}/"
        val appContext = context?.applicationContext
        val remaining = AtomicInteger(cookies.size)
        cookies.forEach { cookie ->
            manager.setCookie(url.toString(), cookie.toString()) {
                if (remaining.decrementAndGet() == 0) {
                    manager.flush()
                    if (appContext != null && SiteCookieStore.hasConsent(appContext, origin)) SiteCookieStore.syncFromWebView(appContext, manager, origin)
                }
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val manager = CookieManager.getInstance()
        // Some WebView versions do not return host-only cookies when the API URL
        // contains a long path/query. Retry against the origin before giving up.
        val header = manager.getCookie(url.toString())
            ?: manager.getCookie(url.newBuilder().encodedPath("/").build().toString())
        val webViewCookies = header?.split(';')?.mapNotNull { Cookie.parse(url, it.trim()) }.orEmpty()
        val appContext = context ?: return webViewCookies
        if (url.isHttps.not()) return webViewCookies
        val persistedHeader = SiteCookieStore.read(appContext, "https://${url.host}/") ?: return webViewCookies
        val currentNames = webViewCookies.mapTo(mutableSetOf()) { it.name }
        val persisted = persistedHeader.split(';').mapNotNull { part ->
            val pair = part.trim()
            val index = pair.indexOf('=')
            if (index <= 0) return@mapNotNull null
            val name = pair.substring(0, index).trim()
            val value = pair.substring(index + 1)
            if (name in currentNames) null else runCatching {
                Cookie.Builder().name(name).value(value).hostOnlyDomain(url.host).path("/").secure().httpOnly().build()
            }.getOrNull()
        }
        return webViewCookies + persisted
    }

    companion object {
        private fun parseUrl(url: String): HttpUrl? = runCatching {
            val uri = URI(url)
            HttpUrl.Builder()
                .scheme(uri.scheme)
                .host(uri.host)
                .apply { if (uri.port > 0) port(uri.port) }
                .build()
        }.getOrNull()

        /** Returns whether WebView has a Cloudflare clearance token for this origin. */
        fun hasClearanceCookie(url: String): Boolean {
            val parsed = parseUrl(url) ?: return false
            val manager = CookieManager.getInstance()
            val header = manager.getCookie(parsed.toString())
                ?: manager.getCookie(parsed.newBuilder().encodedPath("/").build().toString())
                ?: return false
            return header.split(';')
                .asSequence()
                .map { it.trim().substringBefore('=').lowercase() }
                // __cf_bm is issued during an ordinary Cloudflare page load and is
                // not proof that the Turnstile challenge was passed. Only the
                // clearance token can be replayed to satisfy the blocked API call.
                .any { it == "cf_clearance" }
        }

        /** Returns whether Anubis issued its access cookie for this origin. */
        fun hasAnubisCookie(url: String): Boolean {
            val parsed = parseUrl(url) ?: return false
            val manager = CookieManager.getInstance()
            val header = manager.getCookie(parsed.toString())
                ?: manager.getCookie(parsed.newBuilder().encodedPath("/").build().toString())
                ?: return false
            return header.split(';')
                .asSequence()
                .map { it.trim().substringBefore('=').lowercase() }
                .any(AnubisEnvironmentPolicy::isAuthCookieName)
        }

        fun cookieNames(url: String): Set<String> {
            val parsed = parseUrl(url) ?: return emptySet()
            val header = CookieManager.getInstance().getCookie(parsed.toString()) ?: return emptySet()
            return header.split(';').mapNotNull { part ->
                part.trim().substringBefore('=').trim().takeIf { it.isNotEmpty() }
            }.toSet()
        }
    }
}
