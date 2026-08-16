package com.kerybotu.derpibooru.mirror

import android.content.Context

object AppSettings {

    private const val PREFS_NAME = "app_settings_prefs"
    private const val KEY_SITE = "selected_site"
    private const val KEY_MANUAL_IP = "manual_ip"
    private const val KEY_CUSTOM_DOMAIN = "custom_domain"
    private const val KEY_USE_IP_OPTIMIZATION = "use_ip_optimization"
    private const val KEY_HIGH_RES = "high_res_thumbnails"
    private const val KEY_VIDEO_THUMBNAILS = "video_thumbnails"
    private const val KEY_VIDEO_AUDIO = "video_audio"
    private const val KEY_HIDE_UPLOADER = "hide_uploader"
    private const val KEY_HIDE_SCORE = "hide_score"
    private const val KEY_PALETTE = "palette"

    enum class Site(val domain: String, val displayName: String) {
        DERPIBOORU("derpibooru.org", "Derpibooru"),
        TRIXIEBOORU("trixiebooru.org", "Trixiebooru"),
        CUSTOM("", "自定义站点")
    }

    enum class Palette { DARK, LIGHT, COLORFUL }

    fun getSelectedSite(context: Context): Site {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_SITE, Site.DERPIBOORU.name)
        return try { Site.valueOf(name ?: Site.DERPIBOORU.name) } catch (e: Exception) { Site.DERPIBOORU }
    }

    fun setSelectedSite(context: Context, site: Site) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SITE, site.name).apply()
    }

    fun getTargetDomain(context: Context): String {
        val site = getSelectedSite(context)
        return if (site == Site.CUSTOM) getCustomDomain(context) ?: Site.DERPIBOORU.domain else site.domain
    }

    fun getCustomDomain(context: Context): String? = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_CUSTOM_DOMAIN, null)?.trim()?.ifBlank { null }

    fun setCustomSite(context: Context, domain: String): Boolean {
        val normalized = domain.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        if (normalized.length !in 3..253 || !normalized.matches(Regex("[A-Za-z0-9.-]+")) || !normalized.contains('.')) return false
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_CUSTOM_DOMAIN, normalized).putString(KEY_SITE, Site.CUSTOM.name).apply()
        return true
    }

    fun getStartUrl(context: Context): String = "https://${getTargetDomain(context)}"

    fun getManualIp(context: Context): String? {
        val ip = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MANUAL_IP, null)
        return if (ip.isNullOrBlank()) null else ip
    }

    fun setManualIp(context: Context, ip: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_MANUAL_IP, ip?.trim()?.ifBlank { null }).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isIpOptimizationEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_USE_IP_OPTIMIZATION, true)
    fun setIpOptimizationEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_USE_IP_OPTIMIZATION, enabled).apply()
    fun isHighResolution(context: Context): Boolean = prefs(context).getBoolean(KEY_HIGH_RES, true)
    fun setHighResolution(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_HIGH_RES, value).apply()
    fun isVideoThumbnailsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_VIDEO_THUMBNAILS, true)
    fun setVideoThumbnailsEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_VIDEO_THUMBNAILS, value).apply()
    fun isVideoAudioEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_VIDEO_AUDIO, true)
    fun setVideoAudioEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_VIDEO_AUDIO, value).apply()
    fun isUploaderHidden(context: Context): Boolean = prefs(context).getBoolean(KEY_HIDE_UPLOADER, false)
    fun setUploaderHidden(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_HIDE_UPLOADER, value).apply()
    fun isScoreHidden(context: Context): Boolean = prefs(context).getBoolean(KEY_HIDE_SCORE, false)
    fun setScoreHidden(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_HIDE_SCORE, value).apply()
    fun getPalette(context: Context): Palette = runCatching { Palette.valueOf(prefs(context).getString(KEY_PALETTE, Palette.COLORFUL.name)!!) }.getOrDefault(Palette.COLORFUL)
    fun setPalette(context: Context, palette: Palette) = prefs(context).edit().putString(KEY_PALETTE, palette.name).apply()
    fun getCurrentFilterId(context: Context): Int? = context.getSharedPreferences("filter_state", Context.MODE_PRIVATE).getInt("current_id", -1).takeIf { it > 0 }
    fun setCurrentFilterId(context: Context, id: Int?) = context.getSharedPreferences("filter_state", Context.MODE_PRIVATE).edit().putInt("current_id", id ?: -1).apply()

    fun isValidIpFormat(ip: String): Boolean {
        val parts = ip.trim().split(".")
        if (parts.size != 4) return false
        return parts.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
    }
}
