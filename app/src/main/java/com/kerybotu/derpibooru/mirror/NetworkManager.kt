package com.kerybotu.derpibooru.mirror.network

import android.content.Context
import android.util.Log
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.IpOptimizer
import com.kerybotu.derpibooru.mirror.LocalProxyServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

object NetworkManager {

    private const val TAG = "NetworkManager"
    private var localProxyServer: LocalProxyServer? = null
    private var okHttpClient: OkHttpClient? = null

    fun apiUrl(context: Context, path: String): String {
        return "https://${AppSettings.getTargetDomain(context)}/api/v1/json/${path.trimStart('/')}"
    }

    suspend fun init(context: Context, forceRefresh: Boolean = false) {
        try {
            val targetDomain = AppSettings.getTargetDomain(context)
            val builder = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
            if (AppSettings.isIpOptimizationEnabled(context)) {
                val bestIp = AppSettings.getManualIp(context)
                    ?: IpOptimizer.getBestIpSmart(context, forceRefresh = forceRefresh).ip
                Log.d(TAG, "使用优选 IP: $bestIp")
                localProxyServer = LocalProxyServer(targetDomain, bestIp).apply { start() }
                builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", localProxyServer!!.port)))
            } else {
                Log.d(TAG, "使用直连模式")
            }
            okHttpClient = builder.build()
            Log.d(TAG, "OkHttpClient 初始化完成")
        } catch (e: Exception) {
            Log.e(TAG, "初始化失败", e)
            throw e
        }
    }

    suspend fun get(url: String, maxRetries: Int = 3): String? {
        val client = okHttpClient ?: run {
            Log.e(TAG, "OkHttpClient 未初始化")
            return null
        }

        repeat(maxRetries) { attempt ->
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "application/json")
                .build()

            try {
                val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
                response.use {
                    val code = it.code
                    Log.d(TAG, "HTTP 状态码: $code")
                    if (it.isSuccessful) {
                        val body = it.body?.string()
                        if (body.isNullOrBlank()) {
                            Log.e(TAG, "响应成功但 body 为空")
                            return null
                        }
                        return body
                    } else {
                        val errorBody = it.body?.string()?.take(500)
                        Log.w(TAG, "非成功响应: $code, body: $errorBody")
                        when (code) {
                            429, 500, 501 -> delay((attempt + 1) * 1200L)
                            else -> delay(500L)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "请求异常", e)
                delay(2000)
            }
        }
        return null
    }

    suspend fun getApi(context: Context, path: String): String? = get(apiUrl(context, path))

    suspend fun forceOptimize(context: Context): IpOptimizer.OptimizeResult =
        IpOptimizer.getBestIpSmart(context, forceRefresh = true)

    fun isReady(): Boolean = okHttpClient != null

    suspend fun reinitialize(context: Context, forceRefresh: Boolean = false) {
        shutdown()
        init(context, forceRefresh)
    }

    fun shutdown() {
        localProxyServer?.stop()
        localProxyServer = null
        okHttpClient = null
    }
}
