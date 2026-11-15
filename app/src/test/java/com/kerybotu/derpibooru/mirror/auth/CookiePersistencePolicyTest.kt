package com.kerybotu.derpibooru.mirror.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CookiePersistencePolicyTest {
    @Test
    fun failedCleanupIsNeverReportedAsSuccessfulDecline() {
        assertEquals(
            SiteCookieStore.Result.CLEARED,
            SiteCookieStore.resultAfterClearing { true }
        )
        assertEquals(
            SiteCookieStore.Result.STORAGE_ERROR,
            SiteCookieStore.resultAfterClearing { false }
        )
        assertEquals(
            SiteCookieStore.Result.STORAGE_ERROR,
            SiteCookieStore.resultAfterClearing { error("synthetic cleanup failure") }
        )
    }

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
    fun persistedCookieCanBeUsedOnlyAfterExplicitConsentAndSnapshot() {
        assertTrue(CookiePersistencePolicy.CONSENT_UI_ENABLED)
        assertTrue(CookiePersistencePolicy.NATIVE_INTERACTIONS_ENABLED)
        assertTrue(CookiePersistencePolicy.canUsePersistedCookie(consentGranted = true, hasStoredCookie = true))
        assertFalse(CookiePersistencePolicy.canUsePersistedCookie(consentGranted = false, hasStoredCookie = true))
        assertFalse(CookiePersistencePolicy.canUsePersistedCookie(consentGranted = true, hasStoredCookie = false))
    }

    @Test
    fun arbitraryHttpsOriginsAreValidatedWithoutAllowingUnsafeComponents() {
        assertTrue(CookiePersistencePolicy.isPersistableOrigin("https://trixiebooru.org/"))
        assertFalse(CookiePersistencePolicy.isPersistableOrigin("http://trixiebooru.org/"))
        assertFalse(CookiePersistencePolicy.isPersistableOrigin("https://trixiebooru.org/?next=x"))
    }

    @Test
    fun persistedSnapshotHasBoundedLifetimeAndRejectsClockRollback() {
        val now = 2_000_000_000_000L
        assertTrue(SiteCookieStore.isSnapshotFresh(now - 1_000L, now))
        assertFalse(SiteCookieStore.isSnapshotFresh(now - 15L * 24 * 60 * 60 * 1000, now))
        assertFalse(SiteCookieStore.isSnapshotFresh(now + 1_000L, now))
    }
}
