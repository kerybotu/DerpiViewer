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
        const val CHALLENGE_TIMEOUT_MS = 90_000L
        const val TAG = "AnubisChallenge"
    }

    private val stateHandler = Handler(Looper.getMainLooper())
    private var targetUrl: String? = null
    private var challengeObserved = false
    private var redirectedAfterChallenge = false
    private var lastLoggedState: String? = null
    private val statePoll = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) checkAnubisState(webView)
        }
    }
    private val challengeTimeout = Runnable {
        if (!isFinishing && !isDestroyed) {
            Log.w(TAG, "Anubis verification timed out lastState=${lastLoggedState.orEmpty()}")
            finishChallenge(false)
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
        stateHandler.postDelayed(challengeTimeout, CHALLENGE_TIMEOUT_MS.toLong())
        loadThroughOptimizedProxy(target) { webView.loadUrl(target) }
    }

    private fun checkAnubisState(view: android.webkit.WebView) {
        if (isFinishing || isDestroyed) return
        view.evaluateJavascript(
            """
            (function(){
                var html=(document.documentElement&&document.documentElement.innerHTML||'').toLowerCase();
                var body=((document.body&&document.body.innerText)||(document.body&&document.body.textContent)||'').trim();
                var challengeObject=html.indexOf('anubis_challenge')>=0;
                var anubisRuntime=Array.prototype.some.call(document.scripts,function(s){
                    return (s.src||'').toLowerCase().indexOf('/x/cmd/anubis/')>=0;
                });
                var challenge=challengeObject || anubisRuntime ||
                    location.pathname.indexOf('/.within.website/')>=0;
                var reject=!!document.querySelector('img[src*="/x/cmd/anubis/static/img/reject.webp"]') &&
                    !!document.querySelector('.message, .centered-div');
                var difficulty='';
                try {
                    var challengeData=JSON.parse((document.getElementById('anubis_challenge')||{}).textContent||'{}');
                    if (challengeData.rules && Number.isFinite(Number(challengeData.rules.difficulty)))
                        difficulty=String(Number(challengeData.rules.difficulty));
                } catch(e) {}
                var json=false;
                try { var value=JSON.parse(body); json=value!==null && typeof value==='object'; } catch(e) {}
                var scripts=Array.prototype.map.call(document.scripts,function(s){
                    try { var u=new URL(s.src,location.href); return u.pathname; } catch(e) { return ''; }
                }).filter(function(p){ return p.toLowerCase().indexOf('anubis')>=0; }).slice(0,8).join(';');
                var progress=Array.prototype.map.call(
                    document.querySelectorAll('[role="progressbar"], progress'),function(el){
                        var width=el.getBoundingClientRect().width;
                        var max=Number(el.getAttribute('aria-valuemax')||el.max||0);
                        var value=Number(el.getAttribute('aria-valuenow')||el.value||0);
                        return [Math.round(width),max?Math.round(value*100/max):'',
                            (el.getAttribute('aria-label')||'').slice(0,40)].join(':');
                    }).slice(0,4).join(';');
                return [challenge?'1':'0',reject?'1':'0',json?'1':'0',
                    challengeObject?'1':'0',anubisRuntime?'1':'0',location.pathname,scripts,progress,difficulty].join('|');
            })()
            """.trimIndent()
        ) { result ->
            val state = result.trim().trim('"').split('|')
            val challengePresent = state.getOrNull(0) == "1"
            val rejectPage = state.getOrNull(1) == "1"
            val jsonVisible = state.getOrNull(2) == "1"
            val challengeObject = state.getOrNull(3) == "1"
            val anubisRuntime = state.getOrNull(4) == "1"
            val pagePath = state.getOrNull(5).orEmpty().take(160)
            val scriptPaths = state.getOrNull(6).orEmpty().take(500)
            val progress = state.getOrNull(7).orEmpty().take(160)
            val difficulty = state.getOrNull(8)?.toIntOrNull()
            if (rejectPage && !challengePresent) {
                Log.w(TAG, "page_state=anubis_reject path=$pagePath challengeObject=$challengeObject anubisRuntime=$anubisRuntime")
                finishChallenge(false)
                return@evaluateJavascript
            }
            if (challengePresent) {
                challengeObserved = true
                redirectedAfterChallenge = false
            } else if (challengeObserved) {
                redirectedAfterChallenge = true
            }

            val url = targetUrl
            val hasAuthCookie = !url.isNullOrBlank() && SharedCookieJar.hasAnubisCookie(url)
            val cookieNames = if (url.isNullOrBlank()) "none" else
                SharedCookieJar.cookieNames(url).joinToString(",").ifBlank { "none" }
            val stateSummary = "path=$pagePath challenge=$challengePresent difficulty=${difficulty ?: "unknown"} challengeObject=$challengeObject anubisRuntime=$anubisRuntime reject=$rejectPage json=$jsonVisible authCookie=$hasAuthCookie cookieNames=$cookieNames scripts=$scriptPaths progress=$progress"
            if (stateSummary != lastLoggedState) {
                Log.i(TAG, "page_state $stateSummary")
                lastLoggedState = stateSummary
            }
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
        stateHandler.removeCallbacks(challengeTimeout)
        super.onDestroy()
    }
}
