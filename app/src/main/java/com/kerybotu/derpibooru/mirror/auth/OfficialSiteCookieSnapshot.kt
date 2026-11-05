package com.kerybotu.derpibooru.mirror.auth

import android.webkit.CookieManager

/** An in-memory snapshot captured only from the official site's WebView cookie scope. */
class OfficialSiteCookieSnapshot internal constructor(internal val cookieHeader: String) {
    companion object {
        private const val OFFICIAL_ORIGIN = "https://derpibooru.org/"

        fun capture(
            cookieManager: CookieManager,
            origin: String = OFFICIAL_ORIGIN
        ): OfficialSiteCookieSnapshot? {
            if (!CookiePersistencePolicy.isOfficialOrigin(origin)) return null
            return OfficialSiteCookieSnapshot(cookieManager.getCookie(OFFICIAL_ORIGIN).orEmpty())
        }
    }
}
