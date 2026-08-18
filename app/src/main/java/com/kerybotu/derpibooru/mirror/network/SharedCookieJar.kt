package com.kerybotu.derpibooru.mirror.network

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** Bridges the WebView cookie store to OkHttp so challenge cookies are reusable by API calls. */
class SharedCookieJar : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val manager = CookieManager.getInstance()
        cookies.forEach { manager.setCookie(url.toString(), it.toString()) }
        manager.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = CookieManager.getInstance().getCookie(url.toString()) ?: return emptyList()
        return header.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
    }
}
