package com.kerybotu.derpibooru.mirror

import android.util.LruCache
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class TranslateBridge(
    private val webView: WebView,
    private val targetLang: String = "zh-CN"
) {
    companion object {
        private const val TAG = "TranslateBridge"
        private const val CACHE_SIZE = 1024 * 1024 // 1MB 内存缓存
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val workerProxyUrl = "https://googlefyapi.495666.xyz/"

    // 缓存：key = "原文本|目标语言"，value = 翻译结果
    private val translationCache = LruCache<String, String>(CACHE_SIZE)

    @Volatile private var isDestroyed = false

    fun destroy() {
        isDestroyed = true
        job.cancel()
        translationCache.evictAll()
    }

    @JavascriptInterface
    fun requestTranslate(requestId: String, textsJson: String) {
        if (isDestroyed) return
        scope.launch {
            val texts = try {
                parseJsonStringArray(textsJson)
            } catch (e: Exception) {
                Log.e(TAG, "parse error", e)
                emptyList()
            }

            val results = arrayOfNulls<String>(texts.size)
            val missIndices = mutableListOf<Int>()
            val missTexts = mutableListOf<String>()

            // 先查缓存
            for (i in texts.indices) {
                val cacheKey = "${texts[i]}|$targetLang"
                val cached = translationCache.get(cacheKey)
                if (cached != null) {
                    results[i] = cached
                } else {
                    missIndices.add(i)
                    missTexts.add(texts[i])
                }
            }

            // 批量请求未命中的
            if (missTexts.isNotEmpty()) {
                val translatedMiss = translateBatchViaApi(missTexts, targetLang)
                for (j in missIndices.indices) {
                    val idx = missIndices[j]
                    val translated = translatedMiss.getOrElse(j) { texts[idx] }
                    results[idx] = translated
                    if (translated.isNotEmpty()) {
                        translationCache.put("${texts[idx]}|$targetLang", translated)
                    }
                }
            }

            deliverResult(requestId, results.filterNotNull())
        }
    }

    private fun parseJsonStringArray(json: String): List<String> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { arr.optString(it, "") }
    }

    private suspend fun deliverResult(requestId: String, translated: List<String>) {
        if (isDestroyed) return
        withContext(Dispatchers.Main) {
            if (isDestroyed) return@withContext
            val resultJson = JSONArray(translated).toString()
            val script = "window.__onTranslateResult && window.__onTranslateResult(" +
                    JSONObject.quote(requestId) + ", " + JSONObject.quote(resultJson) + ");"
            webView.evaluateJavascript(script, null)
        }
    }

    private suspend fun translateBatchViaApi(texts: List<String>, to: String): List<String> =
        withContext(Dispatchers.IO) {
            if (texts.isEmpty()) return@withContext emptyList()

            val url = URL(workerProxyUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Content-Type", "application/json")

            val jsonBody = JSONObject().apply {
                val textArray = JSONArray()
                texts.forEach { textArray.put(it) }
                put("text", textArray)
                put("target_lang", to)
            }

            conn.outputStream.use { it.write(jsonBody.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return@withContext texts // 失败返回原文
            }

            val respText = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val respObj = JSONObject(respText)
            val translations = respObj.getJSONArray("translations")
            val results = mutableListOf<String>()
            for (i in 0 until translations.length()) {
                val item = translations.getJSONObject(i)
                results.add(item.optString("text", texts.getOrElse(i) { "" }))
            }
            results
        }
}