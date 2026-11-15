package com.kerybotu.derpibooru.mirror.auth

import java.net.URI

sealed interface CookiePersistenceConsentEvent {
    class Granted internal constructor() : CookiePersistenceConsentEvent
    object Declined : CookiePersistenceConsentEvent
}

/** Entry point reserved for a future consent UI after the user explicitly accepts. */
internal object CookiePersistenceConsentEvents {
    fun acceptedAfterExplicitUserChoice(): CookiePersistenceConsentEvent.Granted =
        CookiePersistenceConsentEvent.Granted()
}

/** Single feature gate for persisted site sessions and native interactions. */
object CookiePersistencePolicy {
    const val CONSENT_UI_ENABLED = true
    const val NATIVE_INTERACTIONS_ENABLED = true

    private const val OFFICIAL_HOST = "derpibooru.org"

    fun isOfficialOrigin(origin: String): Boolean {
        val uri = runCatching { URI(origin.trim()) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(OFFICIAL_HOST, ignoreCase = true) &&
            (uri.port == -1 || uri.port == 443) &&
            uri.userInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            (uri.path.isNullOrEmpty() || uri.path == "/")
    }

    /** Cookie persistence is isolated by exact HTTPS origin; custom HTTPS sites are allowed. */
    fun isPersistableOrigin(origin: String): Boolean {
        val uri = runCatching { URI(origin.trim()) }.getOrNull() ?: return false
        return uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port == 443) && (uri.path.isNullOrEmpty() || uri.path == "/")
    }

    fun canUsePersistedCookie(consentGranted: Boolean, hasStoredCookie: Boolean): Boolean =
        NATIVE_INTERACTIONS_ENABLED && consentGranted && hasStoredCookie
}
