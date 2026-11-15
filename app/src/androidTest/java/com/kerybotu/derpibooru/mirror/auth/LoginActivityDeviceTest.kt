package com.kerybotu.derpibooru.mirror.auth

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Synthetic DOM evidence, not a real account login. Uses the production Activity and WebView observer. */
@RunWith(AndroidJUnit4::class)
class LoginActivityDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val origin get() = "https://${AppSettings.getTargetDomain(context)}"

    private fun waitFor(label: String, timeoutMs: Long = 12_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(40)
        }
        fail("Timed out: $label")
    }

    private fun js(scenario: ActivityScenario<LoginActivity>, source: String): String {
        val done = CountDownLatch(1)
        var result = ""
        scenario.onActivity { activity ->
            activity.findViewById<WebView>(R.id.login_webview).evaluateJavascript(source) {
                result = it
                done.countDown()
            }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        return result
    }

    private fun succeeded(scenario: ActivityScenario<LoginActivity>): Boolean {
        var value = false
        scenario.onActivity { value = it.findViewById<Button>(R.id.login_account_settings).text == "已登录成功" }
        return value
    }

    private fun launch(): ActivityScenario<LoginActivity> {
        val scenario = ActivityScenario.launch<LoginActivity>(Intent(context, LoginActivity::class.java)
            .putExtra(LoginActivity.EXTRA_MANUAL_KEY, true))
        onView(withText("取消")).perform(click())
        scenario.onActivity {
            LoginActivity::class.java.getDeclaredField("webLoginActive").apply { isAccessible = true }.setBoolean(it, true)
        }
        return scenario
    }

    private fun load(scenario: ActivityScenario<LoginActivity>, html: String, release: CountDownLatch? = null): AtomicBoolean {
        val finished = AtomicBoolean(false)
        scenario.onActivity { activity ->
            val web = activity.findViewById<WebView>(R.id.login_webview)
            val production = web.webViewClient
            web.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = production.onPageStarted(view, url, favicon)
                override fun onPageCommitVisible(view: WebView, url: String) = production.onPageCommitVisible(view, url)
                override fun onPageFinished(view: WebView, url: String) {
                    finished.set(true)
                    production.onPageFinished(view, url)
                }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    if (request.url.path == "/slow-image" && release != null) {
                        return WebResourceResponse("image/png", null, object : InputStream() {
                            override fun read(): Int { release.await(30, TimeUnit.SECONDS); return -1 }
                        })
                    }
                    return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray()))
                }
            }
            web.loadUrl("$origin/")
        }
        waitFor("DOM parsed") { js(scenario, "!!document.getElementById('fixture')") == "true" }
        return finished
    }

    @Test fun dynamicVisibleEvidenceUpdatesBeforeNoncriticalResourceFinishes() {
        val release = CountDownLatch(1)
        launch().use { scenario ->
            try {
                val finished = load(scenario, """<html><meta name="viewport" content="width=device-width"><body id="fixture">
                    <h1>登录检测测试页</h1><p>非关键图片仍在加载</p><img src="/slow-image">
                    <header><a id="account" href="/sessions" data-method="delete" style="display:none">退出登录（合成测试）</a></header>
                    </body></html>""", release)
                assertFalse(succeeded(scenario))
                val appearedAt = SystemClock.elapsedRealtime()
                js(scenario, "document.getElementById('account').style.display='block'")
                waitFor("visible account evidence") { succeeded(scenario) }
                Log.i("LoginUiValidation", "synthetic_element_to_button_ms=${SystemClock.elapsedRealtime() - appearedAt}")
                assertFalse("Must succeed before onPageFinished", finished.get())
                pressBack()
                SystemClock.sleep(300)
                screenshot("delayed-success")
                // Repeated DOM changes must not reset the state or queue another success callback.
                js(scenario, "document.body.appendChild(document.createElement('div'))")
                assertTrue(succeeded(scenario))
                js(scenario, "history.pushState({},'', '/?login-test=home');document.body.insertAdjacentHTML('beforeend','<p>Home</p>')")
                assertTrue(succeeded(scenario))
                release.countDown()
                scenario.recreate()
                assertTrue("Recreation must preserve authenticated UI state", succeeded(scenario))
            } finally { release.countDown() }
        }
    }

    @Test fun anonymousHomeAndHiddenOrForeignAccountControlsDoNotAuthenticate() {
        launch().use { scenario ->
            load(scenario, """<html><body id="fixture"><h1>Anonymous home</h1>
                <div class="flash flash--success">Unrelated success</div>
                <header><a href="/sessions" data-method="delete" hidden>Hidden logout</a>
                <a href="https://other.invalid/sessions" data-method="delete">Foreign logout</a></header>
                <a href="/sessions" data-method="delete">Untrusted body content</a>
                </body></html>""")
            assertEquals("false", js(scenario, "window.__dvLoginWatch.verify(window.__dvLoginWatch.token) !== null"))
            assertFalse(succeeded(scenario))
            js(scenario, "window.AndroidLoginObserver.changed('stale-document-token')")
            assertFalse(succeeded(scenario))
        }
    }

    @Test fun submittedLoginFlashAndAlreadyPresentAccountControlAreDetected() {
        launch().use { scenario ->
            load(scenario, """<html><body id="fixture"><form action="/sessions" method="post"></form></body></html>""")
            js(scenario, "document.querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));" +
                "document.body.insertAdjacentHTML('beforeend','<div class=\"flash flash--success\">Signed in</div>')")
            waitFor("submitted login flash") { succeeded(scenario) }
        }
        launch().use { scenario ->
            load(scenario, """<html><body id="fixture"><header><a href="/sessions" data-method="delete">Logout</a></header></body></html>""")
            waitFor("initial DOM account evidence") { succeeded(scenario) }
        }
    }

    @Test fun layoutUsesSystemSafeAreaInPortraitAndLandscape() {
        for ((orientation, name) in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to "portrait",
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to "landscape")) {
            launch().use { scenario ->
                scenario.onActivity { it.requestedOrientation = orientation }
                inst.waitForIdleSync()
                runCatching { onView(withText("取消")).perform(click()) }
                // Rotation may recreate the Activity and its method chooser.
                scenario.onActivity { activity ->
                    activity.findViewById<WebView>(R.id.login_webview).loadData("""<meta name="viewport" content="width=device-width">
                        <h1>网页顶部 / Top</h1><p>此行应完整显示在系统状态栏下方。</p>
                        <div style="height:2000px">可滚动网页内容</div>""", "text/html", "UTF-8")
                }
                waitFor("viewport layout") {
                    var valid = false
                    scenario.onActivity { activity ->
                        val web = activity.findViewById<WebView>(R.id.login_webview)
                        val button = activity.findViewById<Button>(R.id.login_account_settings)
                        val a = IntArray(2); val b = IntArray(2)
                        web.getLocationOnScreen(a); button.getLocationOnScreen(b)
                        val bars = web.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                        valid = bars != null && a[1] >= bars.top && a[0] >= bars.left && a[1] + web.height <= b[1]
                    }
                    valid
                }
                SystemClock.sleep(500)
                screenshot(name)
            }
        }
    }

    private fun screenshot(name: String) {
        val dir = File(context.getExternalFilesDir(null), "login-validation").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            inst.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun officialLoginPageOpensWithoutReportingAnonymousSuccess() {
        for ((orientation, name) in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to "official-portrait",
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to "official-landscape")) {
            ActivityScenario.launch<LoginActivity>(Intent(context, LoginActivity::class.java)
                .putExtra(LoginActivity.EXTRA_WEB_LOGIN, true)).use { scenario ->
                scenario.onActivity { it.requestedOrientation = orientation }
                waitFor("official document", 60_000) {
                    js(scenario, "location.origin === '$origin' && !!document.body && document.body.innerText.length > 80") == "true"
                }
                Log.i("LoginUiValidation", "official_title=${js(scenario, "document.title")}")
                Log.i("LoginUiValidation", "official_login_form=${js(scenario,
                    "Array.from(document.forms).some(f => new URL(f.action).pathname === '/sessions' && f.method === 'post')")}")
                assertFalse("No real account was used in this test", succeeded(scenario))
                screenshot(name)
            }
        }
    }
}
