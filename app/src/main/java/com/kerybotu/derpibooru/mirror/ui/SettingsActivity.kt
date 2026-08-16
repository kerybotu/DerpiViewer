package com.kerybotu.derpibooru.mirror.ui

import android.os.Bundle
import android.webkit.WebView
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.kerybotu.derpibooru.mirror.*
import com.kerybotu.derpibooru.mirror.databinding.ActivitySettingsBinding
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import kotlinx.coroutines.*

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        PaletteManager.apply(this)
        setSupportActionBar(binding.settingsToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.settingsToolbar.setNavigationOnClickListener { finish() }
        bindValues()
        bindActions()
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

    private fun bindActions() {
        binding.paletteGroup.setOnCheckedChangeListener { _, id ->
            val palette = when (id) { binding.paletteDark.id -> AppSettings.Palette.DARK; binding.paletteLight.id -> AppSettings.Palette.LIGHT; else -> AppSettings.Palette.COLORFUL }
            AppSettings.setPalette(this, palette); recreate()
        }
        binding.switchHighRes.setOnCheckedChangeListener { _, v -> AppSettings.setHighResolution(this, v) }
        binding.switchVideoThumb.setOnCheckedChangeListener { _, v -> AppSettings.setVideoThumbnailsEnabled(this, v) }
        binding.switchVideoAudio.setOnCheckedChangeListener { _, v -> AppSettings.setVideoAudioEnabled(this, v) }
        binding.switchHideUploader.setOnCheckedChangeListener { _, v -> AppSettings.setUploaderHidden(this, v) }
        binding.switchHideScore.setOnCheckedChangeListener { _, v -> AppSettings.setScoreHidden(this, v) }
        binding.switchIp.setOnCheckedChangeListener { _, v -> AppSettings.setIpOptimizationEnabled(this, v) }
        binding.siteGroup.setOnCheckedChangeListener { _, id -> when (id) { binding.siteDerpi.id -> AppSettings.setSelectedSite(this, AppSettings.Site.DERPIBOORU); binding.siteTrixie.id -> AppSettings.setSelectedSite(this, AppSettings.Site.TRIXIEBOORU); binding.siteCustom.id -> saveCustomDomain() } }
        binding.saveNetwork.setOnClickListener { saveNetwork() }
        binding.restoreAutoIp.setOnClickListener { AppSettings.setManualIp(this, null); binding.manualIp.setText(""); binding.networkStatus.text = "已恢复自动优选" }
        binding.optimizeIp.setOnClickListener { optimize() }
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
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
