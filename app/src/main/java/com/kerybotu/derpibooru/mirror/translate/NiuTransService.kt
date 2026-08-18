package com.kerybotu.derpibooru.mirror.translate

import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object NiuTransService {
    private const val ENDPOINT = "https://api.niutrans.com/NiuTransServer/translation"
    private const val API_KEY = ""
    private const val APP_ID = "YaM1786642324207"
    private val cache = LruCache<String, String>(1024 * 1024)

    suspend fun translate(raw: String): Result<String> = withContext(Dispatchers.IO) {
        val text = clean(raw)
        if (text.isBlank()) return@withContext Result.success(text)
        val key = "$text|zh"
        cache.get(key)?.let { return@withContext Result.success(it) }
        runCatching {
            val url = URL("$ENDPOINT?from=auto&to=zh&apikey=$API_KEY&appid=$APP_ID&src_text=${URLEncoder.encode(text, "UTF-8")}")
            val connection = (url.openConnection() as HttpURLConnection).apply { requestMethod = "GET"; connectTimeout = 8000; readTimeout = 8000 }
            try {
                check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
                JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optString("tgt_text").trim().ifBlank { text }
            } finally { connection.disconnect() }
        }.onSuccess { if (it != text) cache.put(key, it) }
    }

    fun shouldTranslate(raw: String): Boolean {
        val letters = raw.count { it.isLetter() }
        if (letters == 0) return false
        val chinese = raw.count { it in '\u4e00'..'\u9fff' }
        return chinese.toDouble() / letters < 0.3
    }

    fun clean(raw: String): String = raw.replace(Regex("<[^>]*>"), " ")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ").trim()
}
