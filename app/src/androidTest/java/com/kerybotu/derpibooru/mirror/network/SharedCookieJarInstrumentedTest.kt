package com.kerybotu.derpibooru.mirror.network

import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kerybotu.derpibooru.mirror.auth.SiteCookieStore
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class SharedCookieJarInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun webViewCookiesRemainVisibleToOkHttpJarWithoutUsingPersistentStore() {
        val manager = CookieManager.getInstance()
        val host = "dv-cookie-bridge-${System.nanoTime()}.invalid"
        val origin = "https://$host/"
        val cookieName = "session"
        val cookieValue = "synthetic-bridge-${System.nanoTime()}"
        val setResult = AtomicBoolean(false)

        SiteCookieStore.clear(context)
        try {
            setCookieAndWait(manager, origin, "$cookieName=$cookieValue; Path=/; Secure", setResult)

            val cookie = SharedCookieJar().loadForRequest(origin.toHttpUrl())
                .single { it.name == cookieName }

            assertEquals(cookieValue, cookie.value)
            assertFalse(SiteCookieStore.hasConsent(context))
            assertFalse(SiteCookieStore.hasStoredCookie(context))
        } finally {
            setCookieAndWait(manager, origin, "$cookieName=; Path=/; Max-Age=0; Secure", AtomicBoolean())
            manager.flush()
            SiteCookieStore.clear(context)
        }
    }

    private fun setCookieAndWait(
        manager: CookieManager,
        origin: String,
        value: String,
        result: AtomicBoolean
    ) {
        val latch = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            manager.setCookie(origin, value) { accepted ->
                result.set(accepted == true)
                latch.countDown()
            }
        }
        assertEquals(true, latch.await(5, TimeUnit.SECONDS))
        if (value.contains("Max-Age=0")) return
        assertEquals(true, result.get())
        manager.flush()
    }
}
