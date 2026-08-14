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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
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
import com.kerybotu.derpibooru.mirror.rules.DynamicSelectorManager
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

    // ---------- 动态获取当前站点域名和起始 URL ----------
    private fun currentTargetDomain(): String = AppSettings.getTargetDomain(applicationContext)
    private fun currentStartUrl(): String = AppSettings.getStartUrl(applicationContext)

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

        // 预加载翻译脚本到内存，减少首次注入时的文件读取延迟
        activityScope.launch(Dispatchers.IO) {
            if (translateScriptCache == null) {
                translateScriptCache = assets.open("translate.js")
                    .bufferedReader().use { it.readText() }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                translateInjected = false
                // API < 23 不支持 onPageCommitVisible，短延迟注入兜底
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    webView.postDelayed({
                        injectTranslateScriptEarlyOnce()
                    }, 80)
                }
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                super.onPageCommitVisible(view, url)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    injectTranslateScriptEarlyOnce()
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (!translateInjected) {
                    injectTranslateScriptEarlyOnce()
                }
                CookieManager.getInstance().flush()
            }
        }

        // 文件上传
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

        // 启动时同步静态规则和动态选择器
        activityScope.launch {
            StaticRuleManager.syncIfNeeded(applicationContext)
        }
        activityScope.launch {
            DynamicSelectorManager.syncIfNeeded(applicationContext)
        }

        startOptimizeAndLoad(forceRefresh = false)
    }

    // -------------------- Cookie 持久化 --------------------
    private fun setupCookiePersistence() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
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

    // -------------------- IP 优选 + 手动 IP 支持 + 加载 --------------------
    private fun startOptimizeAndLoad(forceRefresh: Boolean) {
        loadingOverlay.visibility = View.VISIBLE

        val manualIp = AppSettings.getManualIp(applicationContext)
        if (manualIp != null) {
            // 手动 IP 优先
            loadingText.text = "准备就绪"
            currentBestIp = manualIp
            activityScope.launch {
                delay(250)
                loadingOverlay.visibility = View.GONE
                if (proxyServer == null) {
                    setupProxyAndLoad(manualIp)
                } else {
                    proxyServer?.updateTargetDomain(currentTargetDomain())
                    proxyServer?.updateTargetIp(manualIp)
                    webView.reload()
                }
            }
            return
        }

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
                proxyServer?.updateTargetDomain(currentTargetDomain())
                proxyServer?.updateTargetIp(currentBestIp)
                webView.reload()
            }
        }
    }

    private fun setupProxyAndLoad(ip: String) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            proxyServer = LocalProxyServer(currentTargetDomain(), ip).apply { start() }

            val proxyConfig = ProxyConfig.Builder()
                .addProxyRule("127.0.0.1:${proxyServer!!.port}")
                .build()

            val executor = Executor { command -> command.run() }
            ProxyController.getInstance().setProxyOverride(proxyConfig, executor) {
                webView.loadUrl(currentStartUrl())
            }
        } else {
            webView.loadUrl(currentStartUrl())
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
        val checkedIndex = listOf(80, 100, 120, 150, 175)
            .indexOf(currentZoom).let { if (it == -1) 1 else it }

        AlertDialog.Builder(this)
            .setTitle("字体大小")
            .setSingleChoiceItems(arrayOf("小", "标准", "大", "较大", "特大"), checkedIndex) { dialog, which ->
                webView.settings.textZoom = listOf(80, 100, 120, 150, 175)[which]
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

    // -------------------- 翻译注入 --------------------
    private fun injectTranslateScriptEarlyOnce() {
        if (translateInjected) return
        translateInjected = true

        try {
            val script = translateScriptCache ?: assets.open("translate.js")
                .bufferedReader().use { it.readText() }
                .also { translateScriptCache = it }

            val rulesPayload = StaticRuleManager.getRulesPayloadJson(applicationContext)
            val selectorsJson = DynamicSelectorManager.getSelectorsJson(applicationContext)
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
                        DynamicSelectorManager.syncIfNeeded(applicationContext, force = true)
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
        val refreshBtn = view.findViewById<Button>(R.id.settings_refresh_btn)
        val advancedBtn = view.findViewById<Button>(R.id.settings_advanced_btn)

        ipText.text = "当前节点 IP：${currentBestIp.ifBlank { "获取中..." }}"

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("关闭", null)
            .create()

        refreshBtn.setOnClickListener {
            dialog.dismiss()
            startOptimizeAndLoad(forceRefresh = true)
        }

        advancedBtn.setOnClickListener {
            dialog.dismiss()
            showAdvancedSettingsDialog()
        }

        dialog.show()
    }

    private fun showAdvancedSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_advanced_settings, null)
        val siteRadioGroup = view.findViewById<RadioGroup>(R.id.site_radio_group)
        val radioDerpibooru = view.findViewById<RadioButton>(R.id.radio_derpibooru)
        val radioTrixiebooru = view.findViewById<RadioButton>(R.id.radio_trixiebooru)
        val manualIpInput = view.findViewById<EditText>(R.id.manual_ip_input)
        val manualIpSaveBtn = view.findViewById<Button>(R.id.manual_ip_save_btn)
        val manualIpClearBtn = view.findViewById<Button>(R.id.manual_ip_clear_btn)
        val cacheSizeText = view.findViewById<TextView>(R.id.cache_size_text)
        val clearCacheBtn = view.findViewById<Button>(R.id.clear_cache_btn)

        // 初始化站点选择
        when (AppSettings.getSelectedSite(applicationContext)) {
            AppSettings.Site.DERPIBOORU -> radioDerpibooru.isChecked = true
            AppSettings.Site.TRIXIEBOORU -> radioTrixiebooru.isChecked = true
        }

        manualIpInput.setText(AppSettings.getManualIp(applicationContext) ?: "")

        val dialog = AlertDialog.Builder(this)
            .setTitle("高级设置")
            .setView(view)
            .setPositiveButton("关闭", null)
            .create()

        siteRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val newSite = if (checkedId == R.id.radio_trixiebooru) {
                AppSettings.Site.TRIXIEBOORU
            } else {
                AppSettings.Site.DERPIBOORU
            }
            if (newSite != AppSettings.getSelectedSite(applicationContext)) {
                AppSettings.setSelectedSite(applicationContext, newSite)
                Toast.makeText(this, "已切换到 ${newSite.displayName}，正在重新加载", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                startOptimizeAndLoad(forceRefresh = false)
            }
        }

        manualIpSaveBtn.setOnClickListener {
            val ip = manualIpInput.text.toString().trim()
            if (ip.isBlank()) {
                Toast.makeText(this, "请输入 IP 地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!AppSettings.isValidIpFormat(ip)) {
                Toast.makeText(this, "IP 格式不正确", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AppSettings.setManualIp(applicationContext, ip)
            Toast.makeText(this, "已保存，正在使用该 IP 重新加载", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            startOptimizeAndLoad(forceRefresh = false)
        }

        manualIpClearBtn.setOnClickListener {
            AppSettings.setManualIp(applicationContext, null)
            manualIpInput.setText("")
            Toast.makeText(this, "已恢复自动优选", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            startOptimizeAndLoad(forceRefresh = true)
        }

        // 异步计算当前缓存大小
        activityScope.launch {
            val size = CacheManager.calculateCacheSize(applicationContext)
            cacheSizeText.text = "当前缓存：${CacheManager.formatSize(size)}"
        }

        clearCacheBtn.setOnClickListener {
            clearCacheBtn.isEnabled = false
            activityScope.launch {
                val freed = CacheManager.clearCache(applicationContext, webView)
                cacheSizeText.text = "当前缓存：0 B"
                clearCacheBtn.isEnabled = true
                Toast.makeText(
                    this@MainActivity,
                    "已清除缓存，释放 ${CacheManager.formatSize(freed)}",
                    Toast.LENGTH_SHORT
                ).show()
                // 重新同步翻译规则和动态选择器
                StaticRuleManager.syncIfNeeded(applicationContext, force = true)
                DynamicSelectorManager.syncIfNeeded(applicationContext, force = true)
            }
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