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
import java.net.URLEncoder

class TranslateBridge(
    private val webView: WebView,
    private val targetLang: String = "zh"
) {
    companion object {
        private const val TAG = "TranslateBridge"
        private const val CACHE_SIZE = 1024 * 1024 // 1MB 内存缓存

        // 小牛翻译 API 配置
        private const val NIUTRANS_API_URL = "https://api.niutrans.com/NiuTransServer/translation"
        private const val NIUTRANS_API_KEY = ""
        private const val NIUTRANS_APP_ID = ""
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

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

            // 清洗为纯文本（去除 HTML 标签、实体，压缩空白）
            val cleanTexts = texts.map { sanitizeText(it) }

            val results = arrayOfNulls<String>(cleanTexts.size)
            val missIndices = mutableListOf<Int>()
            val missTexts = mutableListOf<String>()

            // 先查缓存
            for (i in cleanTexts.indices) {
                val cacheKey = "${cleanTexts[i]}|$targetLang"
                val cached = translationCache.get(cacheKey)
                if (cached != null) {
                    results[i] = cached
                } else {
                    missIndices.add(i)
                    missTexts.add(cleanTexts[i])
                }
            }

            // 未命中的文本一次性拼接翻译
            if (missTexts.isNotEmpty()) {
                val translatedMiss = translateBatchViaApi(missTexts, targetLang)
                for (j in missIndices.indices) {
                    val idx = missIndices[j]
                    val translated = translatedMiss.getOrElse(j) { cleanTexts[idx] }
                    results[idx] = translated
                    if (translated.isNotEmpty() && translated != cleanTexts[idx]) {
                        translationCache.put("${cleanTexts[idx]}|$targetLang", translated)
                    }
                }
            }

            deliverResult(requestId, results.filterNotNull())
        }
    }

    /** 纯文本清洗：去除 HTML 标签、解码实体、压缩空白（不留换行） */
    private fun sanitizeText(text: String): String {
        var clean = text
        clean = clean.replace(Regex("<[^>]*>"), " ")
        clean = clean.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
        clean = clean.replace(Regex("\\s+"), " ").trim()
        return clean
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

    /** 小牛翻译单次请求（文本可能包含换行拼接） */
    private suspend fun translateSingle(text: String, to: String): String {
        try {
            val urlStr = buildString {
                append(NIUTRANS_API_URL)
                append("?from=auto")
                append("&to=").append(URLEncoder.encode(to, "UTF-8"))
                append("&apikey=").append(NIUTRANS_API_KEY)
                append("&appid=").append(NIUTRANS_APP_ID)
                append("&src_text=").append(URLEncoder.encode(text, "UTF-8"))
            }
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000

            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return text
            }

            val respText = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            return try {
                val respObj = JSONObject(respText)
                val translated = respObj.optString("tgt_text", "")
                // 只去除首尾空白，保留内部换行，以便拆分
                translated.trim()
            } catch (e: Exception) {
                Log.e(TAG, "parse niutrans response failed: $respText", e)
                text
            }
        } catch (e: Exception) {
            Log.e(TAG, "niutrans translate error for: $text", e)
            return text
        }
    }

    /** 批量翻译：将多个文本用换行拼接后一次请求，再按换行拆分 */
    private suspend fun translateBatchViaApi(texts: List<String>, to: String): List<String> =
        withContext(Dispatchers.IO) {
            if (texts.isEmpty()) return@withContext emptyList()

            // 用换行符拼接（清洗后文本内不含换行，因此分隔符唯一）
            val combined = texts.joinToString("\n")
            val translatedCombined = translateSingle(combined, to)

            // 按换行拆分
            val split = translatedCombined.split("\n")
            if (split.size == texts.size) {
                split
            } else {
                // 数量不匹配时返回原文，避免错位
                Log.w(TAG, "translated line count mismatch: expected ${texts.size}, got ${split.size}")
                texts
            }
        }
}