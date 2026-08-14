package com.kerybotu.derpibooru.mirror

import android.content.Context

object AppSettings {

    private const val PREFS_NAME = "app_settings_prefs"
    private const val KEY_SITE = "selected_site"
    private const val KEY_MANUAL_IP = "manual_ip"

    enum class Site(val domain: String, val displayName: String) {
        DERPIBOORU("derpibooru.org", "Derpibooru"),
        TRIXIEBOORU("trixiebooru.org", "Trixiebooru")
    }

    fun getSelectedSite(context: Context): Site {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_SITE, Site.DERPIBOORU.name)
        return try { Site.valueOf(name ?: Site.DERPIBOORU.name) } catch (e: Exception) { Site.DERPIBOORU }
    }

    fun setSelectedSite(context: Context, site: Site) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SITE, site.name).apply()
    }

    fun getTargetDomain(context: Context): String = getSelectedSite(context).domain

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

    fun isValidIpFormat(ip: String): Boolean {
        val parts = ip.trim().split(".")
        if (parts.size != 4) return false
        return parts.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
    }
}