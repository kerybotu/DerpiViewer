package com.kerybotu.derpibooru.mirror.rules

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object DynamicSelectorManager {

    private const val TAG = "DynamicSelectorManager"
    private const val PREFS_NAME = "dynamic_selectors_prefs"
    private const val KEY_MANIFEST_VERSION = "manifest_version"
    private const val KEY_MERGED_SELECTORS = "merged_selectors_json"
    private const val KEY_LAST_SYNC_TIME = "last_sync_time"
    private const val SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L

    // 复用同一份 sources.json 清单（里面的 dynamicUrls 字段），
    // 不再单独维护一份 dynamic_manifest.json
    private const val MANIFEST_URL =
        "https://derpiboorumobileiupdate.495648.xyz/kerybotu/DerpibooruMobileDataBase/refs/heads/main/sources.json"

    @Volatile private var cachedSelectors: List<String>? = null

    suspend fun syncIfNeeded(context: Context, force: Boolean = false) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastSync = prefs.getLong(KEY_LAST_SYNC_TIME, 0L)

        if (!force && now - lastSync < SYNC_INTERVAL_MS && prefs.contains(KEY_MERGED_SELECTORS)) {
            loadFromCache(prefs)
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val localSelectors = loadLocalAssetSelectors(context)
                val merged = LinkedHashSet<String>()
                merged.addAll(localSelectors)

                val manifest = fetchJsonObject(MANIFEST_URL)
                if (manifest != null) {
                    val remoteVersion = manifest.optInt("version", -1)
                    val dynamicUrls = manifest.optJSONArray("dynamicUrls")

                    if (dynamicUrls != null) {
                        for (i in 0 until dynamicUrls.length()) {
                            val selectorUrl = dynamicUrls.optString(i, "")
                            if (selectorUrl.isBlank()) continue
                            val arr = fetchJsonArray(selectorUrl)
                            arr?.let {
                                for (j in 0 until it.length()) {
                                    val s = it.optString(j, "")
                                    if (s.isNotBlank()) merged.add(s)
                                }
                            }
                        }
                    }

                    prefs.edit().putInt(KEY_MANIFEST_VERSION, remoteVersion).apply()
                } else {
                    Log.w(TAG, "manifest fetch failed, using local selectors only")
                }

                val mergedList = merged.toList()
                cachedSelectors = mergedList

                prefs.edit()
                    .putString(KEY_MERGED_SELECTORS, JSONArray(mergedList).toString())
                    .putLong(KEY_LAST_SYNC_TIME, now)
                    .apply()

                Log.d(TAG, "dynamic selectors synced: ${mergedList.size} selectors")
            } catch (e: Exception) {
                Log.e(TAG, "sync failed, falling back to cache/local", e)
                loadFromCache(prefs, fallbackToLocal = context)
            }
        }
    }

    private fun loadFromCache(prefs: android.content.SharedPreferences, fallbackToLocal: Context? = null) {
        val cachedJson = prefs.getString(KEY_MERGED_SELECTORS, null)
        if (cachedJson != null) {
            try {
                val arr = JSONArray(cachedJson)
                cachedSelectors = (0 until arr.length()).map { arr.getString(it) }
                return
            } catch (e: Exception) {
                Log.e(TAG, "parse cache failed", e)
            }
        }
        if (fallbackToLocal != null && cachedSelectors == null) {
            cachedSelectors = loadLocalAssetSelectors(fallbackToLocal)
        }
    }

    private fun loadLocalAssetSelectors(context: Context): List<String> {
        return try {
            val text = context.assets.open("dynamic.json")
                .bufferedReader().use { it.readText() }
            val arr = JSONArray(text)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            Log.e(TAG, "load local dynamic selectors failed", e)
            emptyList()
        }
    }

    private fun fetchJsonObject(urlStr: String): JSONObject? {
        val text = fetchText(urlStr) ?: return null
        return try { JSONObject(text) } catch (e: Exception) { null }
    }

    private fun fetchJsonArray(urlStr: String): JSONArray? {
        val text = fetchText(urlStr) ?: return null
        return try { JSONArray(text) } catch (e: Exception) { null }
    }

    private fun fetchText(urlStr: String): String? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.requestMethod = "GET"
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            text
        } catch (e: Exception) {
            Log.e(TAG, "fetchText failed: $urlStr", e)
            null
        }
    }

    fun getSelectorsJson(context: Context): String {
        if (cachedSelectors == null) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadFromCache(prefs, fallbackToLocal = context)
        }
        return JSONArray(cachedSelectors ?: emptyList<String>()).toString()
    }
}