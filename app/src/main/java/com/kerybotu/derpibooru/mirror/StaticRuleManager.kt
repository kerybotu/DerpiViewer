package com.kerybotu.derpibooru.mirror.rules

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object StaticRuleManager {
    private const val URL_MANIFEST = "https://kerybotu.github.io/appuploads/sources.json"
    private const val PREFS = "static_rules_prefs"
    private const val CACHE = "merged_rules_json"
    private const val ROOT = "root_selector"
    data class Rule(val fragmentA: String, val fragmentB: String)

    suspend fun syncIfNeeded(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, 0)
        if (!force && prefs.contains(CACHE)) return@withContext
        val merged = LinkedHashMap<String, String>()
        val manifest = fetchObject(URL_MANIFEST) ?: return@withContext
        val urls = manifest.optJSONArray("ruleUrls") ?: JSONArray()
        for (i in 0 until urls.length()) {
            val array = fetchArray(urls.optString(i)) ?: continue
            for (j in 0 until array.length()) {
                val item = array.optJSONObject(j) ?: continue
                val a = item.optString("fragmentA"); if (a.isNotBlank()) merged[a] = item.optString("fragmentB")
            }
        }
        prefs.edit().putString(CACHE, JSONArray(merged.map { JSONObject().put("fragmentA", it.key).put("fragmentB", it.value) }).toString()).putString(ROOT, manifest.optString("rootSelector", "body")).apply()
    }

    fun getRulesPayloadJson(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, 0)
        return JSONObject().put("rules", JSONArray(prefs.getString(CACHE, "[]"))).put("rootSelector", prefs.getString(ROOT, "body")).toString()
    }
    private fun fetchObject(url: String) = fetch(url)?.let { runCatching { JSONObject(it) }.getOrNull() }
    private fun fetchArray(url: String) = fetch(url)?.let { runCatching { JSONArray(it) }.getOrNull() }
    private fun fetch(url: String): String? = runCatching { (URL(url).openConnection() as HttpURLConnection).let { c -> c.connectTimeout = 6000; c.readTimeout = 6000; if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() } } }.getOrNull()
}
