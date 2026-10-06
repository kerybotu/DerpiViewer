package com.kerybotu.derpibooru.mirror.network

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature

/** Shared WebView transport and lifecycle plumbing for the two challenge screens. */
abstract class ChallengeWebViewActivity : AppCompatActivity() {
    protected lateinit var webView: WebView
    protected var mainFrameHttpError = false
    protected var mainFrameNavigationId = 0L
    private var resolved = false

    @SuppressLint("SetJavaScriptEnabled")
    protected fun createChallengeWebView(backgroundColor: Int): WebView = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        NetworkManager.userAgent()?.let { settings.userAgentString = it }
        setBackgroundColor(backgroundColor)
        alpha = 0f
    }

    protected fun bindWebViewClient(
        loading: ProgressBar,
        onMainFrameError: () -> Unit = {},
        onPageFinished: (WebView, String) -> Unit
    ) {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                mainFrameNavigationId++
                mainFrameHttpError = false
                loading.visibility = View.VISIBLE
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                view.animate().alpha(1f).setDuration(150L).start()
                loading.visibility = View.GONE
                super.onPageCommitVisible(view, url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (view.alpha == 0f) view.animate().alpha(1f).setDuration(150L).start()
                loading.visibility = View.GONE
                onPageFinished(view, url)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    mainFrameHttpError = true
                    loading.visibility = View.GONE
                    onMainFrameError()
                }
                super.onReceivedError(view, request, error)
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                if (request.isForMainFrame) mainFrameHttpError = true
                super.onReceivedHttpError(view, request, errorResponse)
            }
        }
    }

    protected fun loadThroughOptimizedProxy(url: String, onReady: () -> Unit) {
        val port = NetworkManager.localProxyPort()
        if (port == null || !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            Log.w(TAG, "WebView 未使用本地优选代理: port=$port supported=${WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)}")
            onReady()
            return
        }
        ProxyController.getInstance().setProxyOverride(
            ProxyConfig.Builder().addProxyRule("http://127.0.0.1:$port").build(),
            ContextCompat.getMainExecutor(this)
        ) {
            if (!isFinishing && !isDestroyed) onReady()
        }
    }

    protected fun finishChallenge(success: Boolean) {
        if (resolved) return
        resolved = true
        if (success) CookieManager.getInstance().flush()
        ChallengeCoordinator.notifyResolved(intent.getLongExtra(ChallengeCoordinator.EXTRA_SESSION_ID, -1L), success)
        finish()
    }

    protected fun isCurrentPage(view: WebView, url: String, navigationId: Long): Boolean =
        !resolved && !isFinishing && !isDestroyed && !mainFrameHttpError &&
            mainFrameNavigationId == navigationId && view.url == url

    override fun onBackPressed() {
        finishChallenge(false)
    }

    override fun onDestroy() {
        if (!resolved && !isChangingConfigurations) {
            ChallengeCoordinator.notifyResolved(intent.getLongExtra(ChallengeCoordinator.EXTRA_SESSION_ID, -1L), false)
        }
        if (::webView.isInitialized) webView.destroy()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            ProxyController.getInstance().clearProxyOverride(ContextCompat.getMainExecutor(this)) { }
        }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "ChallengeWebView"
    }
}
