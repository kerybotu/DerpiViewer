package com.kerybotu.derpibooru.mirror.network

import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView

/**
 * Shared, pre-navigation WebView environment used by login and challenge pages.
 * It keeps the runtime UA and cookie policy aligned without spoofing a newer
 * browser or changing TLS/security behavior.
 */
internal object AnubisWebViewEnvironment {
    fun configure(webView: WebView) {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        // NetworkManager's UA is derived from this WebView provider during init.
        // Before that point, retain WebView's real provider UA instead of applying
        // the HTTP fallback UA, which describes a different browser environment.
        val networkReady = NetworkManager.isReady()
        val userAgent = AnubisEnvironmentPolicy.sharedWebViewUserAgent(
            providerUserAgent = webView.settings.userAgentString,
            networkReady = networkReady,
            networkUserAgent = NetworkManager.userAgent()
        )
        webView.settings.userAgentString = userAgent
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        val provider = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WebView.getCurrentWebViewPackage()?.let { "${it.packageName}/${it.versionName}" } ?: "unknown"
        } else "platform"
        Log.i(
            "AnubisEnvironment",
            "event=webview_configured ua_mode=${if (networkReady) "shared_versionless" else "provider_default"} " +
                "network_ready=$networkReady ua=${webView.settings.userAgentString} " +
                "javascript=${webView.settings.javaScriptEnabled} dom_storage=${webView.settings.domStorageEnabled} " +
                "third_party_cookie=" +
                CookieManager.getInstance().acceptThirdPartyCookies(webView) +
                " provider=$provider"
        )
    }
}
