package com.kerybotu.derpibooru.mirror.network

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.kerybotu.derpibooru.mirror.PaletteManager

/** Runs Anubis's browser-side proof of work in its own verification screen. */
class AnubisChallengeActivity : ChallengeWebViewActivity() {
    private companion object {
        const val STATE_POLL_INTERVAL_MS = 400L
        const val TAG = "AnubisChallenge"
    }

    private val stateHandler = Handler(Looper.getMainLooper())
    private var targetUrl: String? = null
    private var challengeObserved = false
    private var redirectedAfterChallenge = false
    private val statePoll = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) checkAnubisState(webView)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val palette = PaletteManager.colors(this)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 16, 20, 16)
            setBackgroundColor(palette.primary)
        }
        val badge = TextView(this).apply {
            text = "A"
            gravity = Gravity.CENTER
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(palette.onPrimary)
            setBackgroundColor(palette.surfaceVariant)
        }
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 0, 0, 0)
        }
        val title = TextView(this).apply {
            text = "Anubis 人机验证"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(palette.onPrimary)
        }
        val hint = TextView(this).apply {
            text = "正在由浏览器完成验证，通过后将自动返回"
            textSize = 13f
            setTextColor(palette.onPrimary)
            alpha = 0.86f
        }
        copy.addView(title)
        copy.addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 3 })
        header.addView(badge, LinearLayout.LayoutParams(48, 48))
        header.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))

        webView = createChallengeWebView(palette.surface)
        val loading = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(palette.primary)
        }
        val root = FrameLayout(this).apply { setBackgroundColor(palette.surface) }
        root.addView(header, FrameLayout.LayoutParams(-1, 112))
        root.addView(webView, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 112 })
        root.addView(loading, FrameLayout.LayoutParams(56, 56, Gravity.CENTER))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            view.setPadding(0, insets.getInsets(WindowInsetsCompat.Type.statusBars()).top, 0, 0)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        bindWebViewClient(loading = loading, onPageFinished = { view, _ ->
            mainFrameHttpError = false
            stateHandler.removeCallbacks(statePoll)
            statePoll.run()
        })
        val target = intent.getStringExtra(ChallengeActivity.EXTRA_URL)
        targetUrl = target
        if (target.isNullOrBlank()) return finishChallenge(false)
        loadThroughOptimizedProxy(target) { webView.loadUrl(target) }
    }

    private fun checkAnubisState(view: android.webkit.WebView) {
        if (isFinishing || isDestroyed) return
        view.evaluateJavascript(
            """
            (function(){
                var html=(document.documentElement&&document.documentElement.innerHTML||'').toLowerCase();
                var body=((document.body&&document.body.innerText)||(document.body&&document.body.textContent)||'').trim();
                var challenge=html.indexOf('anubis_challenge')>=0 || html.indexOf('/x/cmd/anubis/')>=0 ||
                    location.pathname.indexOf('/.within.website/')>=0;
                var json=false;
                try { var value=JSON.parse(body); json=value!==null && typeof value==='object'; } catch(e) {}
                return (challenge?'1':'0')+','+(json?'1':'0');
            })()
            """.trimIndent()
        ) { result ->
            val state = result.trim().trim('"').split(',')
            val challengePresent = state.getOrNull(0) == "1"
            val jsonVisible = state.getOrNull(1) == "1"
            if (challengePresent) {
                challengeObserved = true
                redirectedAfterChallenge = false
            } else if (challengeObserved) {
                redirectedAfterChallenge = true
            }

            val url = targetUrl
            val hasAuthCookie = !url.isNullOrBlank() && SharedCookieJar.hasAnubisCookie(url)
            if (challengeObserved && redirectedAfterChallenge && !challengePresent &&
                (jsonVisible || hasAuthCookie)
            ) {
                Log.d(TAG, "Anubis challenge completed; json=$jsonVisible authCookie=$hasAuthCookie")
                android.webkit.CookieManager.getInstance().flush()
                finishChallenge(true)
                return@evaluateJavascript
            }
            if (!isFinishing && !isDestroyed) {
                stateHandler.removeCallbacks(statePoll)
                stateHandler.postDelayed(statePoll, STATE_POLL_INTERVAL_MS)
            }
        }
    }

    override fun onDestroy() {
        stateHandler.removeCallbacks(statePoll)
        super.onDestroy()
    }
}
