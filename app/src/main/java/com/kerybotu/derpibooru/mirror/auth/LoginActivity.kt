package com.kerybotu.derpibooru.mirror.auth

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.os.Bundle
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.text.InputType
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.CookieManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewCompat
import androidx.webkit.ScriptHandler
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.databinding.ActivityLoginBinding
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID

class LoginActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_MANUAL_KEY = "manual_key"
        const val EXTRA_WEB_LOGIN = "web_login"
    }
    private lateinit var binding: ActivityLoginBinding
    private lateinit var accountSettingsUrl: String
    private var loginSucceeded = false
    private var settingsPageReady = false
    private var cookiePromptShown = false
    private lateinit var loginOrigin: String
    private lateinit var observerScript: String
    private var observerHandle: ScriptHandler? = null
    private var webLoginActive = false
    private var pageGeneration = 0
    private lateinit var observerFlow: String

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(binding.root)

        val origin = "https://${AppSettings.getTargetDomain(this)}"
        loginOrigin = origin
        observerFlow = savedInstanceState?.getString("observerFlow") ?: UUID.randomUUID().toString()
        observerScript = LoginDomObserver.script(origin, observerFlow)
        loginSucceeded = savedInstanceState?.getBoolean("loginSucceeded") ?: false
        cookiePromptShown = savedInstanceState?.getBoolean("cookiePromptShown") ?: false
        if (loginSucceeded && !SiteCookieStore.hasDecision(applicationContext, "$origin/")) {
            // A configuration change dismisses an unanswered dialog; keep that decision reachable.
            cookiePromptShown = false
        }
        accountSettingsUrl = "$origin/registrations/edit"
        binding.loginWebview.settings.javaScriptEnabled = true
        binding.loginWebview.settings.domStorageEnabled = true
        binding.loginWebview.settings.loadsImagesAutomatically = true
        binding.loginWebview.addJavascriptInterface(ApiKeyBridge(), "AndroidApiKeyBridge")
        binding.loginWebview.addJavascriptInterface(LoginObserverBridge(), "AndroidLoginObserver")
        if (!loginSucceeded && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            observerHandle = WebViewCompat.addDocumentStartJavaScript(binding.loginWebview, observerScript, setOf(origin))
        }
        binding.loginWebview.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                installLoginObserver(view)
            }
        }
        updatePrimaryAction()

        binding.loginWebview.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                pageGeneration++
                settingsPageReady = false
                updatePrimaryAction()
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                installLoginObserver(view)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                val origin = "https://${AppSettings.getTargetDomain(this@LoginActivity)}/"
                if (SiteCookieStore.hasConsent(applicationContext, origin)) {
                    SiteCookieStore.syncFromWebView(applicationContext, CookieManager.getInstance(), origin)
                }
                settingsPageReady = isLoginOrigin(url) && Uri.parse(url).path == "/registrations/edit"
                if (settingsPageReady) prepareSettingsPage(view)
                updatePrimaryAction()
            }
        }
        binding.loginAccountSettings.setOnClickListener {
            when {
                settingsPageReady -> showApiKeyPasteDialog()
                loginSucceeded -> binding.loginWebview.loadUrl(accountSettingsUrl)
            }
        }
        binding.loginClose.setOnClickListener { cancelLogin() }
        val webState = savedInstanceState?.getBundle("webState")
        if (webState != null) {
            webLoginActive = true
            if (binding.loginWebview.restoreState(webState) != null) {
                updatePrimaryAction()
                if (loginSucceeded && !cookiePromptShown) showCookiePersistencePrompt()
                return
            }
        }
        val manualRequested = intent.getBooleanExtra(EXTRA_MANUAL_KEY, false)
        val webRequested = intent.getBooleanExtra(EXTRA_WEB_LOGIN, false)
        if (manualRequested) {
            showApiKeyPasteDialog()
        } else if (webRequested) {
            startWebLogin(origin)
        } else {
            AlertDialog.Builder(this)
                .setTitle("选择登录方式")
                .setItems(arrayOf("手动输入 API Key", "通过网页登录")) { _, which ->
                    if (which == 0) showApiKeyPasteDialog() else startWebLogin(origin)
                }
                .setOnCancelListener { cancelLogin() }
                .show()
        }
    }

    private fun isLoginOrigin(url: String?): Boolean = runCatching {
        val uri = Uri.parse(url ?: return false)
        val expected = Uri.parse(loginOrigin)
        uri.scheme == "https" && uri.host == expected.host &&
            (if (uri.port == -1) 443 else uri.port) == (if (expected.port == -1) 443 else expected.port)
    }.getOrDefault(false)

    private fun installLoginObserver(view: WebView) {
        if (webLoginActive && !loginSucceeded && isLoginOrigin(view.url)) {
            view.evaluateJavascript(observerScript, null)
        }
    }

    private inner class LoginObserverBridge {
        @JavascriptInterface
        fun changed(documentToken: String?) {
            if (documentToken == null || documentToken.length > 160) return
            runOnUiThread { verifyLoginEvidence(documentToken) }
        }
    }

    private fun verifyLoginEvidence(token: String) {
        if (!webLoginActive || loginSucceeded || isFinishing || isDestroyed || !isLoginOrigin(binding.loginWebview.url)) return
        val generation = pageGeneration
        binding.loginWebview.evaluateJavascript(
            "window.__dvLoginWatch && window.__dvLoginWatch.verify(${JSONObject.quote(token)})"
        ) { result ->
            if (!webLoginActive || loginSucceeded || generation != pageGeneration || !isLoginOrigin(binding.loginWebview.url) || isFinishing || isDestroyed) return@evaluateJavascript
            val evidence = runCatching { JSONObject(JSONTokener(result).nextValue() as String) }.getOrNull()
            if (evidence?.optBoolean("valid") == true) {
                loginSucceeded = true
                observerHandle?.remove()
                observerHandle = null
                updatePrimaryAction()
                Log.d("LoginDetection", "event=verified_dom_success dispatch_ms=${evidence.optLong("elapsedMs")}")
                binding.loginWebview.evaluateJavascript("window.__dvLoginWatch && window.__dvLoginWatch.stop()", null)
                showCookiePersistencePrompt()
            } else {
                binding.loginWebview.evaluateJavascript(
                    "window.__dvLoginWatch && window.__dvLoginWatch.resume(${JSONObject.quote(token)})", null)
            }
        }
    }

    private fun showCookiePersistencePrompt() {
        if (cookiePromptShown || !CookiePersistencePolicy.CONSENT_UI_ENABLED) return
        cookiePromptShown = true
        val origin = "https://${AppSettings.getTargetDomain(this)}/"
        if (SiteCookieStore.hasDecision(applicationContext, origin)) {
            if (SiteCookieStore.hasConsent(applicationContext, origin)) {
                val result = SiteCookieStore.syncFromWebView(applicationContext, CookieManager.getInstance(), origin)
                if (result != SiteCookieStore.Result.SAVED) Toast.makeText(this, "登录状态已更新，但当前 Cookie 无法持久化", Toast.LENGTH_SHORT).show()
            }
            openAccountSettingsPrompt()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("保存登录状态？")
            .setMessage("保存当前站点的登录 Cookie 后，后续点赞、踩、收藏等账号功能可以复用登录状态。Cookie 会使用本机安全存储加密保存；拒绝不会退出当前网页登录会话。")
            .setNegativeButton("暂不保存") { _, _ ->
                SiteCookieStore.decline(applicationContext, origin)
                openAccountSettingsPrompt()
            }
            .setPositiveButton("安全保存") { _, _ ->
                val result = SiteCookieStore.grantFromWebView(applicationContext, CookieManager.getInstance(), origin)
                Toast.makeText(this, if (result == SiteCookieStore.Result.SAVED) "登录状态已加密保存" else "当前站点 Cookie 无法保存", Toast.LENGTH_SHORT).show()
                openAccountSettingsPrompt()
            }
            .show()
    }

    private fun openAccountSettingsPrompt() {
        AlertDialog.Builder(this)
            .setTitle("登录成功")
            .setMessage("是否打开账户设置查看 API Key？API Key 与登录 Cookie 独立管理。")
            .setNegativeButton("稍后") { _, _ -> }
            .setPositiveButton("打开账户设置") { _, _ -> binding.loginWebview.loadUrl(accountSettingsUrl) }
            .show()
    }

    private fun prepareSettingsPage(webView: WebView) {
        webView.evaluateJavascript(
            """
            (function() {
              var key = document.querySelector('#api-key code');
              if (!key) return false;
              key.parentElement.style.outline = '3px solid #4A90D9';
              key.parentElement.style.outlineOffset = '6px';
              key.scrollIntoView({behavior: 'smooth', block: 'center'});
              if (!document.querySelector('#derpi-viewer-import-key')) {
                var button = document.createElement('button');
                button.id = 'derpi-viewer-import-key';
                button.textContent = '导入 API Key 到 DerpiViewer';
              button.style.cssText = 'position:fixed;bottom:92px;right:24px;z-index:99999;padding:12px 18px;border:0;border-radius:8px;background:#4A90D9;color:white;font-size:14px;';
                button.onclick = function() {
                  window.AndroidApiKeyBridge.submitApiKey(key.textContent.trim());
                };
                document.body.appendChild(button);
              }
              return true;
            })();
            """.trimIndent()
        ) { result ->
            settingsPageReady = result == "true"
            updatePrimaryAction()
            if (settingsPageReady) {
                Toast.makeText(this, "请点击网页上的“导入 API Key 到 DerpiViewer”按钮", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updatePrimaryAction() {
        binding.loginAccountSettings.isEnabled = loginSucceeded || settingsPageReady
        binding.loginAccountSettings.text = when {
            settingsPageReady -> "手动粘贴 API Key"
            loginSucceeded -> "已登录成功"
            else -> "等待登录成功"
        }
    }

    private fun showApiKeyPasteDialog() {
        val input = EditText(this).apply {
            hint = "粘贴 API Key"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val container = android.widget.FrameLayout(this).apply {
            setPadding(48, 0, 48, 0)
            addView(input, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("粘贴 API Key")
            .setMessage("密钥只会加密保存在本机。")
            .setView(container)
            .setNeutralButton("粘贴", null)
            .setNegativeButton("取消", null)
            .setPositiveButton("完成", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val clipboard = getSystemService(ClipboardManager::class.java)
                val pasted = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
                if (pasted.isBlank()) {
                    Toast.makeText(this, "剪贴板中没有可粘贴的内容", Toast.LENGTH_SHORT).show()
                } else {
                    input.setText(pasted.trim())
                    input.setSelection(input.length())
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = input.text.toString().trim()
                if (!isValidApiKey(key)) {
                    input.error = "API Key 格式无效"
                    return@setOnClickListener
                }
                ApiKeyStore.save(applicationContext, key)
                setResult(RESULT_OK)
                dialog.dismiss()
                finish()
            }
        }
        dialog.show()
    }

    private fun isValidApiKey(key: String): Boolean =
        key.length in 10..128 && key.matches(Regex("^[A-Za-z0-9_-]+$"))

    private inner class ApiKeyBridge {
        @JavascriptInterface
        fun submitApiKey(value: String?) {
            val key = value?.trim().orEmpty()
            runOnUiThread {
                if (!isValidApiKey(key)) {
                    Toast.makeText(this@LoginActivity, "API Key 格式无效", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                ApiKeyStore.save(applicationContext, key)
                Toast.makeText(this@LoginActivity, "API Key 已加密保存", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    private fun cancelLogin() {
        webLoginActive = false
        binding.loginWebview.evaluateJavascript("window.__dvLoginWatch && window.__dvLoginWatch.stop()", null)
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun configureWebViewProxy() {
        val port = NetworkManager.localProxyPort() ?: return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return
        ProxyController.getInstance().setProxyOverride(
            ProxyConfig.Builder().addProxyRule("http://127.0.0.1:$port").build(),
            ContextCompat.getMainExecutor(this)
        ) { }
    }

    private fun startWebLogin(origin: String) {
        webLoginActive = true
        // Ensure the WebView uses the same optimized-IP local proxy as API traffic.
        lifecycleScope.launch(Dispatchers.Main) {
            if (!NetworkManager.isReady()) runCatching { NetworkManager.init(applicationContext) }
            configureWebViewProxy()
            binding.loginWebview.loadUrl("$origin/sessions/new")
        }
    }

    override fun onDestroy() {
        webLoginActive = false
        pageGeneration++
        observerHandle?.remove()
        binding.loginWebview.evaluateJavascript("window.__dvLoginWatch && window.__dvLoginWatch.stop()", null)
        binding.loginWebview.removeJavascriptInterface("AndroidLoginObserver")
        binding.loginWebview.removeJavascriptInterface("AndroidApiKeyBridge")
        binding.loginWebview.stopLoading()
        binding.loginWebview.destroy()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            ProxyController.getInstance().clearProxyOverride(ContextCompat.getMainExecutor(this)) { }
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("observerFlow", observerFlow)
        outState.putBoolean("loginSucceeded", loginSucceeded)
        outState.putBoolean("cookiePromptShown", cookiePromptShown)
        if (webLoginActive) {
            val state = Bundle()
            if (binding.loginWebview.saveState(state) != null) outState.putBundle("webState", state)
        }
        super.onSaveInstanceState(outState)
    }
}
