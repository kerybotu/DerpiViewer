package com.kerybotu.derpibooru.mirror

import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import android.widget.Toast

/**
 * 页面 JS 通过 window.AndroidTranslator 调用这里；
 * 真正的网络请求在 Kotlin 侧完成（不受页面 CORS 限制），完成后回调给页面 JS。
 *
 * 优化点：
 * - 内存缓存已翻译文本，避免重复消耗 Worker 配额
 * - 同批次内文本去重，减小请求体
 * - Semaphore 限制并发请求数，避免瞬间打爆 Worker
 * - 失败自动重试一次，且读取错误响应体便于排查
 * - 绑定生命周期，Activity/WebView 销毁后自动停止回调，避免崩溃
 */
class TranslateBridge(
    private val webView: WebView,
    private val targetLang: String = "zh-CN"
) {
    companion object {
        private const val TAG = "TranslateBridge"
        private const val MAX_CONCURRENT_REQUESTS = 3
        private const val MAX_RETRY = 1
    }

    // 填入你部署好的 Cloudflare Worker 地址
    private val workerProxyUrl = "https://这里填你的Work地址.work.net/"

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    // 简单的进程内翻译缓存：key = "目标语言|原文" -> 译文
    // 用 synchronizedMap 保证多协程并发写入安全
    private val translationCache: MutableMap<String, String> =
        Collections.synchronizedMap(LinkedHashMap())

    private val requestSemaphore = Semaphore(MAX_CONCURRENT_REQUESTS)

    @Volatile
    private var isDestroyed = false

    /** 在 Activity onDestroy 里调用，停止一切挂起的回调，避免访问已销毁的 WebView */
    fun destroy() {
        isDestroyed = true
        job.cancel()
    }

    @JavascriptInterface
    fun requestTranslate(requestId: String, textsJson: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            //Toast.makeText(webView.context, "正在翻译", Toast.LENGTH_SHORT).show()
        }
        scope.launch {
            val translated = try {
                val texts = parseJsonStringArray(textsJson)
                translateWithCache(texts, targetLang)
            } catch (e: Exception) {
                Log.e(TAG, "translate failed", e)
                emptyList()
            }
            deliverResult(requestId, translated)
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

    /**
     * 先查缓存，把命中的直接填好；未命中的去重后打包发给 Worker；
     * 结果回填缓存，再按原始顺序拼回完整结果数组。
     */
    private suspend fun translateWithCache(texts: List<String>, to: String): List<String> {
        if (texts.isEmpty()) return emptyList()

        val results = arrayOfNulls<String>(texts.size)
        val missingIndicesByText = LinkedHashMap<String, MutableList<Int>>() // 去重 + 记录原始下标

        for (i in texts.indices) {
            val key = "$to|${texts[i]}"
            val cached = translationCache[key]
            if (cached != null) {
                results[i] = cached
            } else {
                missingIndicesByText.getOrPut(texts[i]) { mutableListOf() }.add(i)
            }
        }

        if (missingIndicesByText.isNotEmpty()) {
            val uniqueTexts = missingIndicesByText.keys.toList()
            val translated = requestSemaphore.withPermit {
                translateBatchWithRetry(uniqueTexts, to)
            }

            uniqueTexts.forEachIndexed { idx, originalText ->
                val translatedText = translated.getOrElse(idx) { originalText }
                translationCache["$to|$originalText"] = translatedText
                missingIndicesByText[originalText]?.forEach { pos ->
                    results[pos] = translatedText
                }
            }
        }

        return results.mapIndexed { i, v -> v ?: texts[i] }
    }

    private suspend fun translateBatchWithRetry(texts: List<String>, to: String): List<String> {
        var lastError: Exception? = null
        repeat(MAX_RETRY + 1) { attempt ->
            try {
                return translateBatch(texts, to)
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "translate attempt ${attempt + 1} failed: ${e.message}")
            }
        }
        Log.e(TAG, "translate failed after retries", lastError)
        return texts // 兜底：返回原文，不让页面卡住
    }

    private suspend fun translateBatch(texts: List<String>, to: String): List<String> =
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
                // 读出错误响应体，方便定位是限流(429)/参数错误(4xx)/服务端异常(5xx)
                val errorBody = try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                } catch (e: Exception) { null }
                conn.disconnect()
                throw IllegalStateException("Worker HTTP $code: ${errorBody ?: "no body"}")
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