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
import com.kerybotu.derpibooru.mirror.update.AppUpdateManager
import com.kerybotu.derpibooru.mirror.update.UpdateUi
import com.kerybotu.derpibooru.mirror.update.UpdateFrequency

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
        bindUpdateSettings()
        updateAccountStatus()
        refreshNetworkStatus()
    }

    private fun bindValues() {
        val s = this
        when (AppSettings.getPalette(s)) {
            AppSettings.Palette.SYSTEM -> binding.paletteSystem.isChecked = true
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
        binding.switchTagTranslation.isChecked = AppSettings.isTagTranslationEnabled(s)
        val spoilerModes = AppSettings.SpoilerDisplayMode.values().toList()
        binding.spinnerSpoilerMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, spoilerModes.map { it.label })
        binding.spinnerSpoilerMode.setSelection(spoilerModes.indexOf(AppSettings.getSpoilerDisplayMode(s)).coerceAtLeast(0))
        binding.switchAntiEmbarrassment.isChecked = AppSettings.isAntiEmbarrassmentEnabled(s)
        updateAntiEmbarrassmentFilterLabel()
        binding.switchIp.isChecked = AppSettings.isIpOptimizationEnabled(s)
        binding.switchCdnDirect.isChecked = AppSettings.isCdnDirect(s)
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
        val systemDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val darkPalette = palette == AppSettings.Palette.DARK || (palette == AppSettings.Palette.SYSTEM && systemDark)
        val visibleAccents = if (darkPalette) {
            setOf(AccentColor.BLUE, AccentColor.PURPLE, AccentColor.GREEN, AccentColor.TEAL, AccentColor.ORANGE, AccentColor.ROSE)
        } else {
            AccentColor.values().toSet()
        }
        // Dark mode intentionally exposes only the muted accents tuned for the #121212 surface.
        visibleAccents.forEach { accent ->
            val scheme = ThemeGenerator.generate(accent, if (darkPalette) ThemeMode.DARK else ThemeMode.LIGHT)
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
            val palette = when (id) {
                binding.paletteSystem.id -> AppSettings.Palette.SYSTEM
                binding.paletteDark.id -> AppSettings.Palette.DARK
                binding.paletteLight.id -> AppSettings.Palette.LIGHT
                else -> AppSettings.Palette.COLORFUL
            }
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
        binding.switchTagTranslation.setOnCheckedChangeListener { _, v ->
            AppSettings.setTagTranslationEnabled(this, v)
            Toast.makeText(this, if (v) "标签翻译已开启" else "标签翻译已关闭", Toast.LENGTH_SHORT).show()
        }
        binding.spinnerSpoilerMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                AppSettings.setSpoilerDisplayMode(this@SettingsActivity, AppSettings.SpoilerDisplayMode.values()[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.spoilerModeInfo.setOnClickListener { showSpoilerModeInfo() }
        binding.switchAntiEmbarrassment.setOnCheckedChangeListener { _, enabled ->
            AppSettings.setAntiEmbarrassmentEnabled(this, enabled)
            Toast.makeText(this, if (enabled) "防社死已开启：下次启动将自动切换过滤器" else "防社死已关闭", Toast.LENGTH_SHORT).show()
        }
        binding.antiEmbarrassmentInfo.setOnClickListener { showAntiEmbarrassmentInfo() }
        binding.antiEmbarrassmentFilter.setOnClickListener { showAntiEmbarrassmentFilterDialog() }
        binding.switchIp.setOnCheckedChangeListener { _, v -> AppSettings.setIpOptimizationEnabled(this, v) }
        binding.switchCdnDirect.setOnCheckedChangeListener { _, v ->
            AppSettings.setCdnDirect(this, v)
            NetworkManager.shutdown()
            binding.networkStatus.text = "CDN 直连设置已保存，网络客户端将在下次请求前重建"
        }
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

    private fun bindUpdateSettings() {
        val options = UpdateFrequency.values().toList()
        binding.updateFrequency.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options.map { it.label })
        binding.updateFrequency.setSelection(options.indexOf(AppUpdateManager.frequency(this)).coerceAtLeast(0))
        binding.updateFrequency.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) { AppUpdateManager.setFrequency(this@SettingsActivity, options[position]) }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        })
        binding.checkUpdate.setOnClickListener {
            binding.checkUpdate.isEnabled = false
            scope.launch {
                try {
                    when (val result = AppUpdateManager.checkDetailed(this@SettingsActivity, force = true)) {
                        is com.kerybotu.derpibooru.mirror.update.UpdateCheckResult.Available ->
                            UpdateUi.show(this@SettingsActivity, result.info, scope)
                        com.kerybotu.derpibooru.mirror.update.UpdateCheckResult.NoUpdate ->
                            Toast.makeText(this@SettingsActivity, "当前已是最新版本", Toast.LENGTH_SHORT).show()
                        is com.kerybotu.derpibooru.mirror.update.UpdateCheckResult.Failed ->
                            Toast.makeText(this@SettingsActivity, result.reason, Toast.LENGTH_LONG).show()
                    }
                } finally {
                    binding.checkUpdate.isEnabled = true
                }
            }
        }
    }

    private fun updateAntiEmbarrassmentFilterLabel() {
        binding.antiEmbarrassmentFilter.text = "指定过滤器：${AppSettings.getAntiEmbarrassmentFilterName(this)}"
    }

    private fun showAntiEmbarrassmentInfo() {
        AlertDialog.Builder(this)
            .setTitle("防社死")
            .setMessage("开启后，每次打开 APP 时都会自动设置为指定过滤器，有效防止在公共场合因展示不合适内容而社死。\n\n默认使用官方 Default 过滤器；你也可以在下方“指定过滤器”中填写自己的过滤器 ID。关闭本开关后，应用不会在启动时改动当前过滤器。")
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun showSpoilerModeInfo() {
        AlertDialog.Builder(this)
            .setTitle("剧透内容显示方式")
            .setMessage("直接隐藏：从图片列表中移除剧透图片卡片。\n\n点击显示：卡片显示遮罩，点击遮罩上的“显示”按钮后查看缩略图；点击其它区域仍可进入详情页。\n\n直接显示：不显示剧透遮罩。此设置只影响图片卡片，详情页和全屏大图仍可按页面操作查看。")
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun showAntiEmbarrassmentFilterDialog() {
        val currentId = AppSettings.getAntiEmbarrassmentFilterId(this)
        val input = EditText(this).apply {
            hint = "过滤器 ID（留空使用 Default）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(currentId?.toString().orEmpty())
            setSelectAllOnFocus(false)
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("指定防社死过滤器")
            .setMessage("留空时使用官方 Default 过滤器；填写 ID 后，每次启动会自动切换到该过滤器。")
            .setView(input)
            .setNegativeButton("取消", null)
            .setNeutralButton("使用 Default") { _, _ ->
                AppSettings.setAntiEmbarrassmentFilter(this, null, "Default")
                updateAntiEmbarrassmentFilterLabel()
            }
            .setPositiveButton("保存") { _, _ ->
                val id = input.text.toString().trim().toIntOrNull()
                if (input.text.toString().trim().isNotEmpty() && (id == null || id <= 0)) {
                    Toast.makeText(this, "请输入有效的过滤器 ID", Toast.LENGTH_SHORT).show()
                } else {
                    AppSettings.setAntiEmbarrassmentFilter(this, id)
                    updateAntiEmbarrassmentFilterLabel()
                }
            }
            .show()
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
        // Settings is an XML view hierarchy populated partly after the initial
        // palette pass (for example Spinner adapters). Re-apply the semantic
        // text color here so light and colorful modes never inherit white text
        // from a system dark Material theme.
        val palette = AppSettings.getPalette(this)
        val systemDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val dark = palette == AppSettings.Palette.DARK || (palette == AppSettings.Palette.SYSTEM && systemDark)
        applySettingsTextColor(binding.root, if (dark) c.onSurface else Color.BLACK)
        binding.root.post {
            applySettingsTextColor(binding.root, if (dark) c.onSurface else Color.BLACK)
        }
    }

    private fun applySettingsTextColor(view: android.view.View, textColor: Int) {
        // Keep the toolbar title/navigation icon and accent swatch checkmarks
        // controlled by PaletteManager and buildAccentSwatches respectively.
        if (view.id == com.kerybotu.derpibooru.mirror.R.id.app_toolbar || view === binding.accentSwatches) return
        if (view is TextView) view.setTextColor(textColor)
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) applySettingsTextColor(view.getChildAt(index), textColor)
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
