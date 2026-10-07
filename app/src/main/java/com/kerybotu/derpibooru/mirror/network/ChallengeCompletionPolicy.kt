package com.kerybotu.derpibooru.mirror.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A successful API document is sufficient even when clearance predates this WebView. */
internal object ChallengeCompletionPolicy {
    fun isResolved(
        expectedUrl: String,
        currentUrl: String,
        hasHttpError: Boolean,
        hasChallenge: Boolean,
        isJsonObject: Boolean
    ): Boolean {
        if (hasHttpError || hasChallenge || !isJsonObject) return false
        val expected = expectedUrl.toHttpUrlOrNull() ?: return false
        val current = currentUrl.toHttpUrlOrNull() ?: return false
        return current.scheme == expected.scheme && current.host == expected.host &&
            current.port == expected.port && current.encodedPath == expected.encodedPath &&
            !current.encodedPath.startsWith("/cdn-cgi/", ignoreCase = true)
    }

    // Chromium may render JSON inside a <pre> alongside a pretty-print control.
    // Reading the whole body's innerText includes that control's non-JSON label.
    val JSON_OBJECT_SCRIPT = """
        (function(){
            try {
                var node = document.querySelector('body > pre') || document.body;
                var value = JSON.parse((node && node.textContent || '').trim());
                return !!value && typeof value === 'object' && !Array.isArray(value);
            } catch(e) { return false; }
        })()
    """.trimIndent()
}
