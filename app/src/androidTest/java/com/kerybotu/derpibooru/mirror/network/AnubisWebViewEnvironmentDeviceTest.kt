package com.kerybotu.derpibooru.mirror.network

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.auth.LoginActivity
import com.kerybotu.derpibooru.mirror.ui.FilterSearchClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Live site smoke test. Never submits an account or modifies challenge responses/cookies. */
@RunWith(AndroidJUnit4::class)
class AnubisWebViewEnvironmentDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext

    private fun web(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) web(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun <A : android.app.Activity> waitDocument(scenario: ActivityScenario<A>): String {
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val latch = CountDownLatch(1)
            var result = ""
            scenario.onActivity { activity ->
                web(activity.window.decorView)!!.evaluateJavascript(
                    "JSON.stringify({ready:location.protocol==='https:'&&!!document.body&&document.body.innerText.length>80," +
                        "ua:navigator.userAgent,ch:typeof navigator.userAgentData," +
                        "challenge:!!document.getElementById('anubis_challenge')," +
                        "difficulty:(function(){try{return JSON.parse(document.getElementById('anubis_challenge').textContent).rules.difficulty}catch(e){return null}})()})"
                ) { result = it; latch.countDown() }
            }
            assertTrue("WebView script callback", latch.await(5, TimeUnit.SECONDS))
            if (result.contains("\\\"ready\\\":true")) return result
            SystemClock.sleep(100)
        }
        throw AssertionError("Official HTTPS document did not become visible")
    }

    private fun <A : android.app.Activity> assertEnvironment(scenario: ActivityScenario<A>): String {
        var ua = ""
        scenario.onActivity { activity ->
            val web = web(activity.window.decorView)!!
            ua = web.settings.userAgentString
            assertEquals(NetworkManager.userAgent(), ua)
            assertTrue(web.settings.javaScriptEnabled)
            assertTrue(web.settings.domStorageEnabled)
            assertTrue(CookieManager.getInstance().acceptThirdPartyCookies(web))
        }
        return ua
    }

    private fun <A : android.app.Activity> screenshot(scenario: ActivityScenario<A>, name: String) {
        val drawn = CountDownLatch(1)
        scenario.onActivity { activity ->
            web(activity.window.decorView)!!.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { drawn.countDown() }
            })
        }
        assertTrue("WebView visual state callback", drawn.await(10, TimeUnit.SECONDS))
        inst.waitForIdleSync()
        val dir = File(context.getExternalFilesDir(null), "anubis-environment").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { inst.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun loginAndFilterChallengeUseSameEnvironmentIncludingRecreation() {
        runBlocking { NetworkManager.init(context) }
        val origin = "https://${AppSettings.getTargetDomain(context)}"
        val expectedUa = NetworkManager.userAgent()
        ActivityScenario.launch<LoginActivity>(Intent(context, LoginActivity::class.java)
            .putExtra(LoginActivity.EXTRA_WEB_LOGIN, true)).use { scenario ->
            val state = waitDocument(scenario)
            assertEquals(expectedUa, assertEnvironment(scenario))
            assertTrue(state.contains(expectedUa))
            Log.i("AnubisEnvironmentTest", "page=login state=$state")
            screenshot(scenario, "login-live")
            scenario.recreate()
            assertEquals(expectedUa, assertEnvironment(scenario))
            val restored = waitDocument(scenario)
            assertTrue(restored.contains(expectedUa))
            Log.i("AnubisEnvironmentTest", "page=login_restored state=$restored")
            screenshot(scenario, "login-restored-live")
        }
        // Same production Activity used by ChallengeCoordinator for /filters.
        ActivityScenario.launch<AnubisChallengeActivity>(Intent(context, AnubisChallengeActivity::class.java)
            .putExtra(ChallengeActivity.EXTRA_URL, "$origin/filters?fq=safe")).use { scenario ->
            val state = waitDocument(scenario)
            assertEquals(expectedUa, assertEnvironment(scenario))
            assertTrue(state.contains(expectedUa))
            Log.i("AnubisEnvironmentTest", "page=filter_challenge state=$state")
            screenshot(scenario, "filter-challenge-live")
        }
    }

    @Test fun productionFilterSearchReturnsParsedFilters() {
        runBlocking {
            NetworkManager.init(context)
            val result = FilterSearchClient.search(context, "safe")
            assertTrue("Official filters should be parsed", result.filters.isNotEmpty())
            Log.i("AnubisEnvironmentTest", "filter_search_parsed count=${result.filters.size}")
        }
    }
}
