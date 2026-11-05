package com.kerybotu.derpibooru.mirror.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CookiePersistencePolicyTest {
    @Test
    fun acceptedEventIsDistinctFromDecline() {
        val granted = CookiePersistenceConsentEvents.acceptedAfterExplicitUserChoice()
        val declined = CookiePersistenceConsentEvent.Declined

        assertNotSame(granted, declined)
    }

    @Test
    fun onlyCanonicalHttpsDerpibooruOriginIsAllowed() {
        listOf(
            "https://derpibooru.org",
            "https://derpibooru.org/",
            "https://derpibooru.org:443/"
        ).forEach { assertTrue("Expected allowed origin: $it", CookiePersistencePolicy.isOfficialOrigin(it)) }

        listOf(
            "http://derpibooru.org/",
            "https://www.derpibooru.org/",
            "https://derpibooru.org.example/",
            "https://user@derpibooru.org/",
            "https://derpibooru.org:8443/",
            "https://derpibooru.org/?next=other",
            "https://derpibooru.org/#other"
        ).forEach { assertFalse("Expected rejected origin: $it", CookiePersistencePolicy.isOfficialOrigin(it)) }
    }

    @Test
    fun persistedCookieCannotEnableInteractionsInThisBuild() {
        assertFalse(CookiePersistencePolicy.CONSENT_UI_ENABLED)
        assertFalse(CookiePersistencePolicy.NATIVE_INTERACTIONS_ENABLED)
        assertFalse(CookiePersistencePolicy.canUsePersistedCookie(consentGranted = true, hasStoredCookie = true))
        assertFalse(CookiePersistencePolicy.canUsePersistedCookie(consentGranted = false, hasStoredCookie = true))
        assertFalse(CookiePersistencePolicy.canUsePersistedCookie(consentGranted = true, hasStoredCookie = false))
    }
}
