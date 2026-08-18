package com.kerybotu.derpibooru.mirror.auth

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.databinding.ActivityLoginBinding
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

class LoginActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_MANUAL_KEY = "manual_key"
        const val EXTRA_WEB_LOGIN = "web_login"
    }
    private lateinit var binding: ActivityLoginBinding
    private lateinit var accountSettingsUrl: String
    private var loginSucceeded = false
    private var settingsPageReady = false
    private var successPromptShown = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val origin = "https://${AppSettings.getTargetDomain(this)}"
        accountSettingsUrl = "$origin/registrations/edit"
        binding.loginWebview.settings.javaScriptEnabled = true
        binding.loginWebview.settings.domStorageEnabled = true
        binding.loginWebview.settings.loadsImagesAutomatically = true
        binding.loginWebview.addJavascriptInterface(ApiKeyBridge(), "AndroidApiKeyBridge")
        updatePrimaryAction()

        binding.loginWebview.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                settingsPageReady = url.contains("/registrations/edit")
                if (settingsPageReady) prepareSettingsPage(view) else detectLoginSuccess(view)
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

    private fun detectLoginSuccess(webView: WebView) {
        if (loginSucceeded || successPromptShown) return
        webView.evaluateJavascript("!!document.querySelector('.flash.flash--success')") { result ->
            if (result == "true" && !isFinishing) {
                loginSucceeded = true
                successPromptShown = true
                updatePrimaryAction()
                AlertDialog.Builder(this)
                    .setTitle("登录成功")
                    .setMessage("接下来请打开账户设置，复制网站显示的 API Key，并粘贴回 DerpiViewer。")
                    .setNegativeButton("取消登录") { _, _ -> cancelLogin() }
                    .setPositiveButton("打开账户设置") { _, _ ->
                        binding.loginWebview.loadUrl(accountSettingsUrl)
                    }
                    .show()
            }
        }
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
            loginSucceeded -> "打开账户设置"
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
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
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
        // Ensure the WebView uses the same optimized-IP local proxy as API traffic.
        lifecycleScope.launch(Dispatchers.Main) {
            if (!NetworkManager.isReady()) runCatching { NetworkManager.init(applicationContext) }
            configureWebViewProxy()
            binding.loginWebview.loadUrl("$origin/sessions/new")
        }
    }

    override fun onDestroy() {
        binding.loginWebview.removeJavascriptInterface("AndroidApiKeyBridge")
        binding.loginWebview.stopLoading()
        binding.loginWebview.destroy()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            ProxyController.getInstance().clearProxyOverride(ContextCompat.getMainExecutor(this)) { }
        }
        super.onDestroy()
    }
}
