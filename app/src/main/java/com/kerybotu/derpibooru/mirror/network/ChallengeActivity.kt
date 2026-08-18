package com.kerybotu.derpibooru.mirror.network

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.kerybotu.derpibooru.mirror.PaletteManager
import android.util.Log

/** Displays the official challenge page and accepts only a real user click. */
class ChallengeActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_URL = "challenge_url"
        private const val TAG = "ChallengeActivity"
    }

    private lateinit var webView: WebView
    private var resolved = false
    private var mainFrameHttpError = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val palette = PaletteManager.colors(this)
        val hint = TextView(this).apply {
            text = "请完成网页中的机器人验证，完成后会自动继续请求"
            setPadding(24, 20, 24, 12)
            setTextColor(palette.onSurface)
            setBackgroundColor(palette.surface)
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(palette.surface)
            alpha = 0f
        }
        val loading = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(palette.primary)
        }
        val root = FrameLayout(this).apply { setBackgroundColor(palette.surface) }
        root.addView(hint, FrameLayout.LayoutParams(-1, -2))
        root.addView(webView, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 76 })
        root.addView(loading, FrameLayout.LayoutParams(56, 56, android.view.Gravity.CENTER))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, top, 0, 0)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        val target = intent.getStringExtra(EXTRA_URL)
        if (target.isNullOrBlank()) return finishChallenge(false)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                mainFrameHttpError = false
                loading.visibility = android.view.View.VISIBLE
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                view.animate().alpha(1f).setDuration(150L).start()
                loading.visibility = android.view.View.GONE
                super.onPageCommitVisible(view, url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (view.alpha == 0f) view.animate().alpha(1f).setDuration(150L).start()
                loading.visibility = android.view.View.GONE
                checkChallengeState(view, url)
            }

            override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) {
                    mainFrameHttpError = true
                    loading.visibility = android.view.View.GONE
                    hint.text = "验证页面加载失败，请检查网络后返回重试"
                }
                super.onReceivedError(view, request, error)
            }

            override fun onReceivedHttpError(view: WebView, request: android.webkit.WebResourceRequest, errorResponse: android.webkit.WebResourceResponse) {
                if (request.isForMainFrame) mainFrameHttpError = true
                super.onReceivedHttpError(view, request, errorResponse)
            }
        }
        // ProxyController applies its override asynchronously.  Loading before the
        // callback can send the first challenge request through system DNS/direct
        // routing, which is especially slow or blocked when IP optimization is on.
        configureWebViewProxy {
            if (!isFinishing && !isDestroyed) {
                Log.d(TAG, "开始加载验证页: proxy=${NetworkManager.localProxyPort()} ips=${NetworkManager.currentPreferredIps()}")
                webView.loadUrl(target)
            }
        }
    }

    private fun checkChallengeState(view: WebView, url: String) {
        if (resolved || mainFrameHttpError) return
        view.evaluateJavascript(
            "!!document.querySelector('form.derpi-challenge[action=\"/challenge\"][method=\"post\"]')"
        ) { result ->
            if (result != "false" || url.contains("/challenge")) return@evaluateJavascript
            // The post-challenge page must be a JSON object.  A rendered error page,
            // plain text status, or HTML document is never considered resolved.
            view.evaluateJavascript(
                "(function(){try{var t=(document.body&&document.body.innerText||'').trim();var v=JSON.parse(t);return !!v && typeof v==='object' && !Array.isArray(v)}catch(e){return false}})()"
            ) { jsonResult ->
                if (jsonResult == "true" && !mainFrameHttpError) finishChallenge(true)
            }
        }
    }

    private fun finishChallenge(success: Boolean) {
        if (resolved) return
        resolved = true
        ChallengeCoordinator.notifyResolved(success)
        finish()
    }

    private fun configureWebViewProxy(onReady: () -> Unit) {
        val port = NetworkManager.localProxyPort()
        if (port == null || !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            Log.w(TAG, "WebView 未使用本地优选代理: port=$port supported=${WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)}")
            onReady()
            return
        }
        Log.d(TAG, "等待 WebView 应用本地优选代理: 127.0.0.1:$port")
        ProxyController.getInstance().setProxyOverride(
            ProxyConfig.Builder().addProxyRule("http://127.0.0.1:$port").build(),
            ContextCompat.getMainExecutor(this)
        ) {
            Log.d(TAG, "WebView 本地优选代理已生效: 127.0.0.1:$port")
            onReady()
        }
    }

    override fun onBackPressed() {
        finishChallenge(false)
    }

    override fun onDestroy() {
        webView.destroy()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            ProxyController.getInstance().clearProxyOverride(ContextCompat.getMainExecutor(this)) { }
        }
        super.onDestroy()
    }
}
