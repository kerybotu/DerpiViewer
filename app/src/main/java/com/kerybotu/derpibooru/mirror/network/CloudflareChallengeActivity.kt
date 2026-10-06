package com.kerybotu.derpibooru.mirror.network

import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.kerybotu.derpibooru.mirror.PaletteManager

/** A separate Cloudflare/Turnstile verification screen and state machine. */
class CloudflareChallengeActivity : ChallengeWebViewActivity() {
    private var cloudflareChallengeObserved = false

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
            text = "CF"
            gravity = Gravity.CENTER
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(palette.onPrimary)
            setBackgroundColor(palette.surfaceVariant)
        }
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 0, 0, 0)
        }
        val title = TextView(this).apply {
            text = "Cloudflare 人机验证"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(palette.onPrimary)
        }
        val hint = TextView(this).apply {
            text = "请在下方完成 Turnstile 检查，验证通过后将自动返回"
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

        bindWebViewClient(loading = loading, onPageFinished = ::checkCloudflareChallengeState)
        val target = intent.getStringExtra(ChallengeActivity.EXTRA_URL)
        if (target.isNullOrBlank()) return finishChallenge(false)
        loadThroughOptimizedProxy(target) { webView.loadUrl(target) }
    }

    private fun checkCloudflareChallengeState(view: android.webkit.WebView, url: String) {
        if (mainFrameHttpError) return
        view.evaluateJavascript(
            "(function(){var h=(document.documentElement&&document.documentElement.outerHTML||'').toLowerCase();return h.includes('/cdn-cgi/challenge-platform/')&&(h.includes('window._cf_chl_opt')||h.includes('challenges.cloudflare.com/turnstile')||h.includes('cf-turnstile-response'))})()"
        ) { result ->
            if (result == "true") {
                cloudflareChallengeObserved = true
                return@evaluateJavascript
            }
            if (!cloudflareChallengeObserved) return@evaluateJavascript
            val expectedHost = android.net.Uri.parse(
                intent.getStringExtra(ChallengeActivity.EXTRA_URL).orEmpty()
            ).host
            val currentUri = android.net.Uri.parse(url)
            val passedChallengeUrl = currentUri.host.equals(expectedHost, ignoreCase = true) &&
                !url.contains("__cf_chl", ignoreCase = true) &&
                !url.contains("/cdn-cgi/challenge-platform/", ignoreCase = true)
            if (!passedChallengeUrl) return@evaluateJavascript
            view.evaluateJavascript(
                "(function(){try{var t=(document.body&&document.body.innerText||'').trim();var v=JSON.parse(t);return !!v && typeof v==='object' && !Array.isArray(v)}catch(e){return false}})()"
            ) { jsonResult ->
                if (jsonResult == "true" && !mainFrameHttpError) finishChallenge(true)
            }
        }
    }
}
