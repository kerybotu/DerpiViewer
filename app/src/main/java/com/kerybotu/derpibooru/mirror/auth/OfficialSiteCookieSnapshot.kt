package com.kerybotu.derpibooru.mirror.auth

/** An in-memory snapshot captured only from the official site's WebView cookie scope. */
internal class OfficialSiteCookieSnapshot internal constructor(
    internal val cookieHeader: String,
    internal val sourceOrigin: String
)
