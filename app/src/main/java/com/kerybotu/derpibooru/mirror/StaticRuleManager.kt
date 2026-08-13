package com.kerybotu.derpibooru.mirror.rules

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 静态 HTML 片段翻译规则管理器。
 *
 * 优先级：远程规则 > 本地内置规则（assets/translation_rules.json）。
 * 远程规则通过 Worker 代理的"清单文件"机制实现可扩展：
 * 清单里列出若干条实际规则文件地址，App 逐一拉取合并，
 * 后续新增规则来源只需编辑 GitHub 清单，无需改动 App 代码或 Worker。
 *
 * 合并规则：同一个 fragmentA 出现在多处时，后加载的覆盖先加载的，
 * 顺序为：本地内置 → 清单里 ruleUrls 按数组顺序依次覆盖。
 */
object StaticRuleManager {

    private const val TAG = "StaticRuleManager"
    private const val PREFS_NAME = "static_rules_prefs"
    private const val KEY_MANIFEST_VERSION = "manifest_version"
    private const val KEY_MERGED_RULES = "merged_rules_json"
    private const val KEY_ROOT_SELECTOR = "root_selector"
    private const val KEY_LAST_SYNC_TIME = "last_sync_time"
    private const val SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private const val MANIFEST_URL = "https://googlefyapi.495666.xyz/dict/manifest"
    private const val PROXY_BASE = "https://googlefyapi.495666.xyz/dict/proxy?url="

    data class Rule(val fragmentA: String, val fragmentB: String)

    @Volatile private var cachedRules: List<Rule>? = null
    @Volatile private var cachedRootSelector: String = "body"

    suspend fun syncIfNeeded(context: Context, force: Boolean = false) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastSync = prefs.getLong(KEY_LAST_SYNC_TIME, 0L)

        if (!force && now - lastSync < SYNC_INTERVAL_MS && prefs.contains(KEY_MERGED_RULES)) {
            loadFromCache(prefs)
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val localRules = loadLocalAssetRules(context)
                val merged = LinkedHashMap<String, String>()
                localRules.forEach { merged[it.fragmentA] = it.fragmentB }

                val manifest = fetchJsonObject(MANIFEST_URL)
                if (manifest != null) {
                    val remoteVersion = manifest.optInt("version", -1)
                    val localVersion = prefs.getInt(KEY_MANIFEST_VERSION, -1)
                    cachedRootSelector = manifest.optString("rootSelector", "body")

                    val ruleUrls = manifest.optJSONArray("ruleUrls")
                    if (ruleUrls != null) {
                        for (i in 0 until ruleUrls.length()) {
                            val ruleUrl = ruleUrls.optString(i, "")
                            if (ruleUrl.isBlank()) continue
                            val proxiedUrl = PROXY_BASE + java.net.URLEncoder.encode(ruleUrl, "UTF-8")
                            val rulesArray = fetchJsonArray(proxiedUrl)
                            rulesArray?.let { arr ->
                                for (j in 0 until arr.length()) {
                                    val obj = arr.optJSONObject(j) ?: continue
                                    val a = obj.optString("fragmentA", "")
                                    val b = obj.optString("fragmentB", "")
                                    if (a.isNotBlank()) merged[a] = b // 远程覆盖本地/更早的远程
                                }
                            }
                        }
                    }

                    prefs.edit().putInt(KEY_MANIFEST_VERSION, remoteVersion).apply()
                } else {
                    Log.w(TAG, "manifest fetch failed, using local rules only")
                }

                val mergedList = merged.map { Rule(it.key, it.value) }
                cachedRules = mergedList

                val mergedJsonArray = JSONArray()
                mergedList.forEach { rule ->
                    mergedJsonArray.put(JSONObject().apply {
                        put("fragmentA", rule.fragmentA)
                        put("fragmentB", rule.fragmentB)
                    })
                }

                prefs.edit()
                    .putString(KEY_MERGED_RULES, mergedJsonArray.toString())
                    .putString(KEY_ROOT_SELECTOR, cachedRootSelector)
                    .putLong(KEY_LAST_SYNC_TIME, now)
                    .apply()

                Log.d(TAG, "static rules synced: ${mergedList.size} rules")
            } catch (e: Exception) {
                Log.e(TAG, "sync failed, falling back to cache/local", e)
                loadFromCache(prefs, fallbackToLocal = context)
            }
        }
    }

    private fun loadFromCache(prefs: android.content.SharedPreferences, fallbackToLocal: Context? = null) {
        val cachedJson = prefs.getString(KEY_MERGED_RULES, null)
        if (cachedJson != null) {
            try {
                val arr = JSONArray(cachedJson)
                cachedRules = (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    Rule(obj.optString("fragmentA"), obj.optString("fragmentB"))
                }
                cachedRootSelector = prefs.getString(KEY_ROOT_SELECTOR, "body") ?: "body"
                return
            } catch (e: Exception) {
                Log.e(TAG, "parse cache failed", e)
            }
        }
        if (fallbackToLocal != null && cachedRules == null) {
            cachedRules = loadLocalAssetRules(fallbackToLocal)
        }
    }

    private fun loadLocalAssetRules(context: Context): List<Rule> {
        return try {
            val text = context.assets.open("translation_rules.json")
                .bufferedReader().use { it.readText() }
            val arr = JSONArray(text)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Rule(obj.optString("fragmentA"), obj.optString("fragmentB"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "load local assets rules failed", e)
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

    /** 供 JS 侧消费：规则 JSON 数组 + 应用范围的根选择器 */
    fun getRulesPayloadJson(context: Context): String {
        if (cachedRules == null) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadFromCache(prefs, fallbackToLocal = context)
        }
        val rulesArray = JSONArray()
        cachedRules?.forEach { rule ->
            rulesArray.put(JSONObject().apply {
                put("fragmentA", rule.fragmentA)
                put("fragmentB", rule.fragmentB)
            })
        }
        return JSONObject().apply {
            put("rules", rulesArray)
            put("rootSelector", cachedRootSelector)
        }.toString()
    }
}