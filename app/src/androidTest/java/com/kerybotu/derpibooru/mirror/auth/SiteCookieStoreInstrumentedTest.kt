package com.kerybotu.derpibooru.mirror.auth

import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SiteCookieStoreInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    @After
    fun clearTestStore() {
        SiteCookieStore.clear(context)
    }

    @Test
    fun grantEncryptsAndReadsSyntheticSnapshot() {
        val cookieHeader = "session=fake-session-value; _philomena_key=fake-api-session"

        val result = SiteCookieStore.applyConsentEvent(
            context,
            CookiePersistenceConsentEvents.acceptedAfterExplicitUserChoice(),
            snapshot(cookieHeader)
        )

        assertEquals(SiteCookieStore.Result.SAVED, result)
        assertTrue(SiteCookieStore.hasConsent(context))
        assertTrue(SiteCookieStore.hasStoredCookie(context))
        assertEquals(cookieHeader, SiteCookieStore.read(context))
        val ciphertext = preferences().getString(SiteCookieStore.KEY_CIPHERTEXT, null)
        assertTrue(ciphertext != null)
        assertFalse(ciphertext!!.contains("fake-session-value"))
        assertFalse(ciphertext.contains("fake-api-session"))
    }

    @Test
    fun missingConsentDoesNotPersistOrExposeSnapshot() {
        val result = SiteCookieStore.syncFromWebView(context, CookieManager.getInstance())

        assertEquals(SiteCookieStore.Result.NOT_AUTHORIZED, result)
        assertEquals(
            SiteCookieStore.Result.NOT_AUTHORIZED,
            SiteCookieStore.applyConsentEvent(context, null, snapshot("session=fake-value"))
        )
        assertFalse(SiteCookieStore.hasConsent(context))
        assertFalse(SiteCookieStore.hasStoredCookie(context))
        assertNull(SiteCookieStore.read(context))
        assertNull(preferences().getString(SiteCookieStore.KEY_CIPHERTEXT, null))
    }

    @Test
    fun replacementDropsOldCookieValues() {
        SiteCookieStore.applyConsentEvent(
            context,
            CookiePersistenceConsentEvents.acceptedAfterExplicitUserChoice(),
            snapshot("session=old-synthetic-value; old_flag=1")
        )

        val result = SiteCookieStore.syncSnapshot(
            context,
            snapshot("session=new-synthetic-value; new_flag=2")
        )

        assertEquals(SiteCookieStore.Result.SAVED, result)
        assertEquals("session=new-synthetic-value; new_flag=2", SiteCookieStore.read(context))
        assertFalse(SiteCookieStore.read(context).orEmpty().contains("old-synthetic-value"))
    }

    @Test
    fun declineAndClearRemovePersistedSnapshot() {
        SiteCookieStore.applyConsentEvent(
            context,
            CookiePersistenceConsentEvents.acceptedAfterExplicitUserChoice(),
            snapshot("session=fake-session-value")
        )

        assertEquals(
            SiteCookieStore.Result.CLEARED,
            SiteCookieStore.applyConsentEvent(
                context,
                CookiePersistenceConsentEvent.Declined,
                snapshot = null
            )
        )
        assertFalse(SiteCookieStore.hasConsent(context))
        assertFalse(SiteCookieStore.hasStoredCookie(context))
        assertNull(SiteCookieStore.read(context))

        SiteCookieStore.applyConsentEvent(
            context,
            CookiePersistenceConsentEvents.acceptedAfterExplicitUserChoice(),
            snapshot("session=another-fake-value")
        )
        SiteCookieStore.clear(context)
        assertFalse(SiteCookieStore.hasConsent(context))
        assertNull(SiteCookieStore.read(context))
    }

    @Test
    fun tamperedCiphertextReturnsNoCookieAndClearsInvalidRecord() {
        SiteCookieStore.applyConsentEvent(
            context,
            CookiePersistenceConsentEvents.acceptedAfterExplicitUserChoice(),
            snapshot("session=fake-session-value")
        )
        preferences().edit().putString(SiteCookieStore.KEY_CIPHERTEXT, "tampered-ciphertext").commit()

        assertNull(SiteCookieStore.read(context))
        assertFalse(SiteCookieStore.hasConsent(context))
        assertFalse(SiteCookieStore.hasStoredCookie(context))
    }

    @Test
    fun malformedCookieSnapshotIsRejectedWithoutRetainingOldCookie() {
        SiteCookieStore.applyConsentEvent(
            context,
            CookiePersistenceConsentEvents.acceptedAfterExplicitUserChoice(),
            snapshot("session=old-synthetic-value")
        )

        val result = SiteCookieStore.syncSnapshot(
            context,
            snapshot("not-a-cookie")
        )

        assertEquals(SiteCookieStore.Result.INVALID_SNAPSHOT, result)
        assertFalse(SiteCookieStore.hasConsent(context))
        assertNull(SiteCookieStore.read(context))
    }

    @Test
    fun unsupportedOriginSnapshotIsRejectedAndCleared() {
        SiteCookieStore.applyConsentEvent(
            context,
            CookiePersistenceConsentEvents.acceptedAfterExplicitUserChoice(),
            snapshot("session=synthetic-value")
        )

        val result = SiteCookieStore.syncSnapshot(
            context,
            OfficialSiteCookieSnapshot("session=other-value", "http://derpibooru.org/")
        )

        assertEquals(SiteCookieStore.Result.INVALID_SNAPSHOT, result)
        assertFalse(SiteCookieStore.hasConsent(context))
        assertNull(SiteCookieStore.read(context))
    }

    @Test
    fun snapshotsAndConsentAreIsolatedByOrigin() {
        val first = "https://first-cookie-test.invalid/"
        val second = "https://second-cookie-test.invalid/"
        assertEquals(SiteCookieStore.Result.SAVED, SiteCookieStore.applyConsentEvent(
            context,
            CookiePersistenceConsentEvents.acceptedAfterExplicitUserChoice(),
            OfficialSiteCookieSnapshot("session=first-synthetic", first)
        ))

        assertEquals("session=first-synthetic", SiteCookieStore.read(context, first))
        assertNull(SiteCookieStore.read(context, second))
        assertFalse(SiteCookieStore.hasConsent(context, second))
        assertEquals(SiteCookieStore.Result.CLEARED, SiteCookieStore.decline(context, first))
        assertFalse(SiteCookieStore.hasConsent(context, first))
        assertNull(SiteCookieStore.read(context, first))
    }

    @Test
    fun nativeCookieJarRestoresOnlyMatchingOriginSnapshot() {
        val origin = "https://native-cookie-test.invalid/"
        assertEquals(SiteCookieStore.Result.SAVED, SiteCookieStore.applyConsentEvent(
            context,
            CookiePersistenceConsentEvents.acceptedAfterExplicitUserChoice(),
            OfficialSiteCookieSnapshot("session=native-synthetic", origin)
        ))

        val url = "https://native-cookie-test.invalid/api/v1/json".toHttpUrl()
        val requestCookies = com.kerybotu.derpibooru.mirror.network.SharedCookieJar(context).loadForRequest(url)
        assertTrue(requestCookies.any { it.name == "session" && it.value == "native-synthetic" && it.httpOnly })
        assertTrue(com.kerybotu.derpibooru.mirror.network.SharedCookieJar(context)
            .loadForRequest("https://other-cookie-test.invalid/".toHttpUrl())
            .none { it.value == "native-synthetic" })
    }

    private fun preferences() = context.getSharedPreferences(SiteCookieStore.PREFERENCES_NAME, 0)

    private fun snapshot(header: String) = OfficialSiteCookieSnapshot(header, "https://derpibooru.org/")
}
