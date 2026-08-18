package com.kerybotu.derpibooru.mirror.ui

import android.os.Bundle
import android.webkit.WebView
import android.webkit.CookieManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.kerybotu.derpibooru.mirror.*
import com.kerybotu.derpibooru.mirror.databinding.ActivitySettingsBinding
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import com.kerybotu.derpibooru.mirror.network.DeepOptimizeActivity
import com.kerybotu.derpibooru.mirror.auth.ApiKeyStore
import com.kerybotu.derpibooru.mirror.auth.LoginActivity
import kotlinx.coroutines.*
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.kerybotu.derpibooru.mirror.theme.AccentColor
import com.kerybotu.derpibooru.mirror.theme.ThemeGenerator
import com.kerybotu.derpibooru.mirror.theme.ThemeMode

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        PaletteManager.apply(this)
        applyPalette()
        val toolbar = binding.settingsToolbar.appToolbar
        setSupportActionBar(toolbar)
        toolbar.title = "设置"
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        bindValues()
        buildAccentSwatches()
        bindActions()
        updateAccountStatus()
        refreshNetworkStatus()
    }

    private fun bindValues() {
        val s = this
        when (AppSettings.getPalette(s)) {
            AppSettings.Palette.DARK -> binding.paletteDark.isChecked = true
            AppSettings.Palette.LIGHT -> binding.paletteLight.isChecked = true
            AppSettings.Palette.COLORFUL -> binding.paletteColorful.isChecked = true
        }
        binding.switchHighRes.isChecked = AppSettings.isHighResolution(s)
        binding.switchVideoThumb.isChecked = AppSettings.isVideoThumbnailsEnabled(s)
        binding.switchVideoAudio.isChecked = AppSettings.isVideoAudioEnabled(s)
        binding.switchVideoWifiOnly.isChecked = AppSettings.isVideoWifiOnly(s)
        binding.switchHideUploader.isChecked = AppSettings.isUploaderHidden(s)
        binding.switchHideScore.isChecked = AppSettings.isScoreHidden(s)
        binding.switchIp.isChecked = AppSettings.isIpOptimizationEnabled(s)
        binding.manualIp.setText(AppSettings.getManualIp(s) ?: "")
        binding.customDomain.setText(AppSettings.getCustomDomain(s) ?: "")
        when (AppSettings.getSelectedSite(s)) {
            AppSettings.Site.DERPIBOORU -> binding.siteDerpi.isChecked = true
            AppSettings.Site.TRIXIEBOORU -> binding.siteTrixie.isChecked = true
            AppSettings.Site.CUSTOM -> binding.siteCustom.isChecked = true
        }
    }

    private fun buildAccentSwatches() {
        val selected = AppSettings.getAccentColor(this)
        binding.accentSwatches.removeAllViews()
        val palette = AppSettings.getPalette(this)
        val visibleAccents = if (palette == AppSettings.Palette.DARK) {
            setOf(AccentColor.BLUE, AccentColor.PURPLE, AccentColor.GREEN, AccentColor.TEAL, AccentColor.ORANGE, AccentColor.ROSE)
        } else {
            AccentColor.values().toSet()
        }
        // Dark mode intentionally exposes only the muted accents tuned for the #121212 surface.
        visibleAccents.forEach { accent ->
            val scheme = ThemeGenerator.generate(accent, if (AppSettings.getPalette(this) == AppSettings.Palette.DARK) ThemeMode.DARK else ThemeMode.LIGHT)
            val swatch = TextView(this).apply {
                text = if (accent == selected) "✓" else ""
                gravity = android.view.Gravity.CENTER
                contentDescription = accent.displayName
                setTextColor(scheme.onPrimary)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(scheme.primary); setStroke(if (accent == selected) dp(3) else dp(1), if (accent == selected) PaletteManager.colors(this@SettingsActivity).onSurface else Color.TRANSPARENT) }
                setOnClickListener { AppSettings.setAccentColor(this@SettingsActivity, accent); buildAccentSwatches(); PaletteManager.apply(this@SettingsActivity); applyPalette() }
            }
            binding.accentSwatches.addView(swatch, android.widget.LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(0, 0, dp(10), 0) })
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun bindActions() {
        binding.accountLogin.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("登录方式")
                .setItems(arrayOf("手动输入 API Key", "通过网页登录")) { _, which ->
                    if (which == 0) {
                        startActivity(android.content.Intent(this, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_MANUAL_KEY, true))
                    } else {
                        startActivity(android.content.Intent(this, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_WEB_LOGIN, true))
                    }
                }
                .show()
        }
        binding.accountLogout.setOnClickListener { confirmClearCredentials() }
        binding.paletteGroup.setOnCheckedChangeListener { _, id ->
            val palette = when (id) { binding.paletteDark.id -> AppSettings.Palette.DARK; binding.paletteLight.id -> AppSettings.Palette.LIGHT; else -> AppSettings.Palette.COLORFUL }
            AppSettings.setPalette(this, palette)
            PaletteManager.apply(this)
            applyPalette()
            buildAccentSwatches()
        }
        binding.switchHighRes.setOnCheckedChangeListener { _, v -> AppSettings.setHighResolution(this, v) }
        binding.switchVideoThumb.setOnCheckedChangeListener { _, v -> AppSettings.setVideoThumbnailsEnabled(this, v) }
        binding.switchVideoAudio.setOnCheckedChangeListener { _, v -> AppSettings.setVideoAudioEnabled(this, v) }
        binding.switchVideoWifiOnly.setOnCheckedChangeListener { _, v -> AppSettings.setVideoWifiOnly(this, v) }
        binding.switchHideUploader.setOnCheckedChangeListener { _, v -> AppSettings.setUploaderHidden(this, v) }
        binding.switchHideScore.setOnCheckedChangeListener { _, v -> AppSettings.setScoreHidden(this, v) }
        binding.switchIp.setOnCheckedChangeListener { _, v -> AppSettings.setIpOptimizationEnabled(this, v) }
        binding.siteGroup.setOnCheckedChangeListener { _, id -> when (id) { binding.siteDerpi.id -> AppSettings.setSelectedSite(this, AppSettings.Site.DERPIBOORU); binding.siteTrixie.id -> AppSettings.setSelectedSite(this, AppSettings.Site.TRIXIEBOORU); binding.siteCustom.id -> saveCustomDomain() } }
        binding.saveNetwork.setOnClickListener { saveNetwork() }
        binding.restoreAutoIp.setOnClickListener { AppSettings.setManualIp(this, null); binding.manualIp.setText(""); binding.networkStatus.text = "已恢复自动优选" }
        binding.optimizeIp.setOnClickListener { optimize() }
        binding.deepOptimizeIp.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("深度 IP 优选")
                .setMessage("将获取 Cloudflare IPv4/IPv6 网段，进行四次 TCPing、前 20 节点并发连通性验证和 CDN 下载测速。此过程可能耗时 1-2 分钟并产生较多网络请求，结果会同时用于主站和 CDN。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始") { _, _ -> startActivityForResult(android.content.Intent(this, DeepOptimizeActivity::class.java), 9042) }
                .show()
        }
        binding.clearCache.setOnClickListener { clearCache() }
    }

    private fun saveCustomDomain() {
        if (!AppSettings.setCustomSite(this, binding.customDomain.text.toString())) binding.networkStatus.text = "自定义域名格式无效"
    }

    private fun saveNetwork() {
        val ip = binding.manualIp.text.toString().trim()
        if (ip.isNotEmpty() && !AppSettings.isValidIpFormat(ip)) { binding.networkStatus.text = "IP 地址格式无效"; return }
        AppSettings.setManualIp(this, ip.ifBlank { null })
        if (binding.siteCustom.isChecked) saveCustomDomain()
        NetworkManager.shutdown()
        binding.networkStatus.text = "网络设置已保存，返回主页后生效"
    }

    private fun optimize() {
        binding.networkStatus.text = "正在测速优选节点…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                NetworkManager.reinitialize(this@SettingsActivity, forceRefresh = true)
                IpOptimizer.getBestIpSmart(this@SettingsActivity)
            }
            binding.networkStatus.text = "当前优选节点：${result.ip}"
        }
    }

    private fun clearCache() {
        binding.clearCache.isEnabled = false
        scope.launch {
            val webView = WebView(this@SettingsActivity)
            val cleared = CacheManager.clearCache(this@SettingsActivity, webView)
            webView.destroy()
            binding.clearCache.isEnabled = true
            Toast.makeText(this@SettingsActivity, "已清除 ${CacheManager.formatSize(cleared)}", Toast.LENGTH_SHORT).show()
            refreshNetworkStatus()
        }
    }

    private fun refreshNetworkStatus() {
        scope.launch {
            val size = withContext(Dispatchers.IO) { CacheManager.calculateCacheSize(this@SettingsActivity) }
            val routes = NetworkManager.currentPreferredIps()
            val routeText = if (routes.isEmpty()) "直连（未使用优选节点）" else
                routes.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            binding.networkStatus.text = "当前节点 IP:\n$routeText\n缓存大小：${CacheManager.formatSize(size)}"
        }
    }

    override fun onResume() {
        super.onResume()
        updateAccountStatus()
        refreshNetworkStatus()
    }

    private fun updateAccountStatus() {
        val loggedIn = ApiKeyStore.isLoggedIn(this)
        binding.accountStatus.text = if (loggedIn) "已登录 · API Key：${ApiKeyStore.masked(this)}" else "未登录。登录仅通过 Derpibooru 官方页面完成。"
        binding.accountLogout.isEnabled = loggedIn
    }

    private fun applyPalette() {
        val c = PaletteManager.colors(this)
        binding.root.setBackgroundColor(c.surface)
        binding.accountStatus.setTextColor(c.muted)
        binding.networkStatus.setTextColor(c.muted)
        listOf(binding.customDomain, binding.manualIp).forEach {
            it.setTextColor(c.onSurface)
            it.setHintTextColor(c.muted)
        }
    }

    private fun confirmClearCredentials() {
        AlertDialog.Builder(this)
            .setTitle("清除本地凭据")
            .setMessage("这会删除本机保存的 API Key 和网站会话，不会注销服务器账户。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清除") { _, _ ->
                ApiKeyStore.clear(this)
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                updateAccountStatus()
                Toast.makeText(this, "本地凭据已清除", Toast.LENGTH_SHORT).show()
            }.show()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
