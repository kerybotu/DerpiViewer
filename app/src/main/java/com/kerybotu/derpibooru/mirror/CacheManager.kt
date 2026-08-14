package com.kerybotu.derpibooru.mirror

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object CacheManager {

    suspend fun calculateCacheSize(context: Context): Long = withContext(Dispatchers.IO) {
        var total = 0L
        total += dirSize(context.cacheDir)
        total += dirSize(context.codeCacheDir)
        val webviewCacheDir = File(context.cacheDir.parentFile, "app_webview/Default/Cache")
        if (webviewCacheDir.exists()) total += dirSize(webviewCacheDir)
        val webviewGpuCache = File(context.cacheDir.parentFile, "app_webview/Default/GPUCache")
        if (webviewGpuCache.exists()) total += dirSize(webviewGpuCache)
        total
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var size = 0L
        val files = dir.listFiles() ?: return 0L
        for (f in files) {
            size += if (f.isDirectory) dirSize(f) else f.length()
        }
        return size
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex++
        }
        return String.format(Locale.getDefault(), "%.1f %s", value, units[unitIndex])
    }

    suspend fun clearCache(context: Context, webView: WebView): Long = withContext(Dispatchers.IO) {
        val before = calculateCacheSize(context)

        withContext(Dispatchers.Main) {
            webView.clearCache(true)
        }

        deleteDirContents(context.cacheDir)
        deleteDirContents(context.codeCacheDir)

        clearOwnPrefsCache(context, "static_rules_prefs")
        clearOwnPrefsCache(context, "dynamic_selectors_prefs")
        clearOwnPrefsCache(context, "ip_optimizer_prefs")

        val after = calculateCacheSize(context)
        (before - after).coerceAtLeast(0L)
    }

    private fun deleteDirContents(dir: File) {
        if (!dir.exists()) return
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory) {
                deleteDirContents(f)
                f.delete()
            } else {
                f.delete()
            }
        }
    }

    private fun clearOwnPrefsCache(context: Context, prefsName: String) {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }
}