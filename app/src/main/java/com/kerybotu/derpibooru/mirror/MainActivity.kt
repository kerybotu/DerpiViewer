package com.kerybotu.derpibooru.mirror

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.kerybotu.derpibooru.mirror.rules.StaticRuleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TARGET_DOMAIN = "derpibooru.org"
        private const val START_URL = "https://derpibooru.org"

        private val ZOOM_LEVELS = listOf(80, 100, 120, 150, 175)
        private val ZOOM_LABELS = arrayOf("小", "标准", "大", "较大", "特大")

        // 动态内容（评论、帖子描述等）扫描范围，实时调用 API 翻译。
        // TODO: 请对照 derpibooru.org 真实页面 DOM 结构核实这些选择器是否准确。
        private val DYNAMIC_SELECTORS = listOf(
            ".comment",
            ".comment_body",
            ".image-description",
            "[itemprop=\"description\"]"
        )

        private const val TRANSLATE_PREFS = "translate_prefs"
        private const val KEY_AUTO_STATIC_TRANSLATE = "auto_static_translate"
    }

    private lateinit var toolbar: Toolbar
    private lateinit var webView: WebView
    private lateinit var loadingOverlay: LinearLayout
    private lateinit var loadingText: TextView

    private var proxyServer: LocalProxyServer? = null
    private var translateBridge: TranslateBridge? = null
    private var currentBestIp: String = ""
    private var translateScriptCache: String? = null
    private var translateInjected = false

    private val activityJob = Job()
    private val activityScope = CoroutineScope(Dispatchers.Main + activityJob)

    private val notificationPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // -------------------- 文件上传相关 --------------------
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileChooserCallback
            fileChooserCallback = null
            if (callback == null) return@registerForActivityResult

            val data = result.data
            val uris: Array<Uri>? = when {
                result.resultCode != RESULT_OK || data == null -> null
                data.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                }
                data.data != null -> arrayOf(data.data!!)
                else -> null
            }
            callback.onReceiveValue(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        webView = findViewById(R.id.webview)
        loadingOverlay = findViewById(R.id.loading_overlay)
        loadingText = findViewById(R.id.loading_text)

        setSupportActionBar(toolbar)
        supportActionBar?.title = "Derpibooru"

        applyInsets()
        requestNotificationPermissionIfNeeded()
        setupCookiePersistence()

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT

        translateBridge = TranslateBridge(webView)
        webView.addJavascriptInterface(translateBridge!!, "AndroidTranslator")

        activityScope.launch(Dispatchers.IO) {
            if (translateScriptCache == null) {
                translateScriptCache = assets.open("translate.js").bufferedReader().use { it.readText() }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                translateInjected = false

                // 如果设备不支持 onPageCommitVisible（API < 23），则短延迟后注入。
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    webView.postDelayed({
                        injectTranslateScriptEarlyOnce()
                    }, 80)
                }
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                super.onPageCommitVisible(view, url)
                // 页面内容首次可见时注入，比 DOMContentLoaded 更早，确保脚本在目标文档中执行。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    injectTranslateScriptEarlyOnce()
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                // 兜底：如果由于某些原因还未注入，则在这里补一次。
                if (!translateInjected) {
                    injectTranslateScriptEarlyOnce()
                }
                CookieManager.getInstance().flush()
            }
        }

        // 文件上传：网页 <input type="file"> 触发时会走这里
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback

                val intent = fileChooserParams.createIntent().apply {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    fileChooserCallback = null
                    Toast.makeText(this@MainActivity, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val port = proxyServer?.port
            if (port != null) {
                DownloadHelper.download(this, port, url, userAgent, contentDisposition, mimeType)
            } else {
                Toast.makeText(this, "代理未就绪，无法下载", Toast.LENGTH_SHORT).show()
            }
        }

        // 启动时异步同步静态翻译规则（不阻塞页面加载）
        activityScope.launch {
            StaticRuleManager.syncIfNeeded(applicationContext)
        }

        startOptimizeAndLoad(forceRefresh = false)
    }

    // -------------------- Cookie 持久化 --------------------
    private fun setupCookiePersistence() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        // WebView 默认已开启持久化存储（写入设备磁盘），这里只需确保开关打开 + 定期 flush 即可。
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onStop() {
        super.onStop()
        CookieManager.getInstance().flush()
    }

    // -------------------- 状态栏适配 --------------------
    private fun applyInsets() {
        val baseToolbarHeight = run {
            val typedValue = android.util.TypedValue()
            theme.resolveAttribute(androidx.appcompat.R.attr.actionBarSize, typedValue, true)
            typedValue.getDimension(resources.displayMetrics).toInt()
        }

        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val statusBarInset = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val params = view.layoutParams as ViewGroup.MarginLayoutParams
            params.height = baseToolbarHeight + statusBarInset.top
            view.layoutParams = params
            view.setPadding(view.paddingLeft, statusBarInset.top, view.paddingRight, view.paddingBottom)
            insets
        }
        ViewCompat.requestApplyInsets(toolbar)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // -------------------- IP 优选 + 开屏加载态 --------------------
    private fun startOptimizeAndLoad(forceRefresh: Boolean) {
        loadingOverlay.visibility = View.VISIBLE
        loadingText.text = "正在优选IP"

        activityScope.launch {
            val result = IpOptimizer.getBestIpSmart(applicationContext, forceRefresh)
            currentBestIp = result.ip

            loadingText.text = "准备就绪"
            delay(250)
            loadingOverlay.visibility = View.GONE

            if (proxyServer == null) {
                setupProxyAndLoad(currentBestIp)
            } else {
                proxyServer?.updateTargetIp(currentBestIp)
                webView.reload()
            }
        }
    }

    private fun setupProxyAndLoad(ip: String) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            proxyServer = LocalProxyServer(TARGET_DOMAIN, ip).apply { start() }

            val proxyConfig = ProxyConfig.Builder()
                .addProxyRule("127.0.0.1:${proxyServer!!.port}")
                .build()

            val executor = Executor { command -> command.run() }
            ProxyController.getInstance().setProxyOverride(proxyConfig, executor) {
                webView.loadUrl(START_URL)
            }
        } else {
            webView.loadUrl(START_URL)
        }
    }

    // -------------------- 三点菜单 --------------------
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_font_size -> { showFontSizeDialog(); true }
            R.id.action_refresh -> { webView.reload(); true }
            R.id.action_translate -> { showTranslateSubmenu(); true }
            R.id.action_copy_link -> { copyCurrentLink(); true }
            R.id.action_settings -> { showSettingsDialog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showFontSizeDialog() {
        val currentZoom = webView.settings.textZoom
        val checkedIndex = ZOOM_LEVELS.indexOf(currentZoom).let { if (it == -1) 1 else it }

        AlertDialog.Builder(this)
            .setTitle("字体大小")
            .setSingleChoiceItems(ZOOM_LABELS, checkedIndex) { dialog, which ->
                webView.settings.textZoom = ZOOM_LEVELS[which]
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // -------------------- 复制链接 --------------------
    private fun copyCurrentLink() {
        val url = webView.url
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "暂无可复制的链接", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("link", url))
        Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show()
    }

    // -------------------- 翻译：静态规则注入 + 动态内容扫描配置 --------------------
    private fun injectTranslateScriptEarlyOnce() {
        if (translateInjected) return
        translateInjected = true

        try {
            val script = translateScriptCache ?: assets.open("translate.js")
                .bufferedReader().use { it.readText() }
                .also { translateScriptCache = it }

            val rulesPayload = StaticRuleManager.getRulesPayloadJson(applicationContext)
            val selectorsJson = JSONArray(DYNAMIC_SELECTORS).toString()
            val autoEnabled = isAutoStaticTranslateEnabled()

            val initScript = """
                (function() {
                    window.__setStaticRules && window.__setStaticRules(${JSONObject.quote(rulesPayload)});
                    window.__setDynamicSelectors && window.__setDynamicSelectors(${JSONObject.quote(selectorsJson)});
                    window.__bootstrapAutoStatic && window.__bootstrapAutoStatic($autoEnabled);
                })();
            """.trimIndent()

            webView.evaluateJavascript(script + "\n" + initScript, null)
        } catch (e: Exception) {
            Toast.makeText(this, "翻译脚本加载失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun isAutoStaticTranslateEnabled(): Boolean {
        return getSharedPreferences(TRANSLATE_PREFS, MODE_PRIVATE)
            .getBoolean(KEY_AUTO_STATIC_TRANSLATE, false)
    }

    private fun showTranslateSubmenu() {
        val prefs = getSharedPreferences(TRANSLATE_PREFS, MODE_PRIVATE)
        val autoEnabled = prefs.getBoolean(KEY_AUTO_STATIC_TRANSLATE, false)

        val options = arrayOf(
            "翻译评论/动态内容",
            "恢复动态内容原文",
            if (autoEnabled) "关闭全站本地静态翻译" else "开启全站本地静态翻译",
            "立即更新翻译规则"
        )

        AlertDialog.Builder(this)
            .setTitle("翻译")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> webView.evaluateJavascript(
                        "window.__pageTranslator && window.__pageTranslator.runDynamic();", null
                    )
                    1 -> webView.evaluateJavascript(
                        "window.__pageTranslator && window.__pageTranslator.revertDynamic();", null
                    )
                    2 -> {
                        val newVal = !autoEnabled
                        prefs.edit().putBoolean(KEY_AUTO_STATIC_TRANSLATE, newVal).apply()
                        if (newVal) {
                            webView.evaluateJavascript(
                                "window.__pageTranslator && window.__pageTranslator.runStatic();", null
                            )
                            Toast.makeText(this, "已开启：进入页面自动本地翻译静态内容", Toast.LENGTH_SHORT).show()
                        } else {
                            webView.evaluateJavascript(
                                "window.__pageTranslator && window.__pageTranslator.stopStaticWatch();", null
                            )
                            Toast.makeText(this, "静态翻译已关闭，刷新页面后完全生效", Toast.LENGTH_SHORT).show()
                        }
                    }
                    3 -> activityScope.launch {
                        StaticRuleManager.syncIfNeeded(applicationContext, force = true)
                        Toast.makeText(this@MainActivity, "翻译规则已更新", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    // -------------------- 设置面板 --------------------
    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val ipText = view.findViewById<TextView>(R.id.settings_current_ip)
        val refreshBtn = view.findViewById<android.widget.Button>(R.id.settings_refresh_btn)

        ipText.text = "当前节点 IP：${currentBestIp.ifBlank { "获取中..." }}"

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("关闭", null)
            .create()

        refreshBtn.setOnClickListener {
            dialog.dismiss()
            startOptimizeAndLoad(forceRefresh = true)
        }

        dialog.show()
    }

    override fun onDestroy() {
        activityJob.cancel()
        translateBridge?.destroy()
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        CookieManager.getInstance().flush()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            ProxyController.getInstance().clearProxyOverride({ it.run() }) {}
        }
        proxyServer?.stop()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}