package com.kerybotu.derpibooru.mirror.network

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.net.URI

/** Bridges the WebView cookie store to OkHttp so challenge cookies are reusable by API calls. */
class SharedCookieJar : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val manager = CookieManager.getInstance()
        cookies.forEach { manager.setCookie(url.toString(), it.toString()) }
        manager.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val manager = CookieManager.getInstance()
        // Some WebView versions do not return host-only cookies when the API URL
        // contains a long path/query. Retry against the origin before giving up.
        val header = manager.getCookie(url.toString())
            ?: manager.getCookie(url.newBuilder().encodedPath("/").build().toString())
            ?: return emptyList()
        return header.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
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
                .any { it == "techaro.lol-anubis" || it.endsWith("-anubis") }
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
