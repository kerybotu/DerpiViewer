package com.kerybotu.derpibooru.mirror.network

import android.content.Context
import android.util.Log
import android.webkit.WebSettings
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.IpOptimizer
import com.kerybotu.derpibooru.mirror.LocalProxyServer
import com.kerybotu.derpibooru.mirror.auth.ApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Protocol
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import android.net.Uri
import java.util.concurrent.TimeUnit

object NetworkManager {

    private const val TAG = "NetworkManager"
    /** Kept identical between OkHttp and the challenge WebView because
     * Cloudflare clearance cookies can be bound to the requesting UA. */
    const val HTTP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    @Volatile private var activeUserAgent: String = HTTP_USER_AGENT
    private var localProxyServer: LocalProxyServer? = null
    private var okHttpClient: OkHttpClient? = null
    @Volatile private var preferredRouteIps: Map<String, String> = emptyMap()
    private val rateLimiter = ApiRateLimiter()

    fun apiUrl(context: Context, path: String): String {
        val base = "https://${AppSettings.getTargetDomain(context)}/api/v1/json/${path.trimStart('/')}"
        val key = ApiKeyStore.get(context) ?: return base
        return Uri.parse(base).buildUpon().appendQueryParameter("key", key).build().toString()
    }

    fun currentFilterParam(context: Context, separator: String = "&"): String =
        AppSettings.getCurrentFilterId(context)?.let { "$separator" + "filter_id=$it" } ?: ""

    suspend fun init(
        context: Context,
        forceRefresh: Boolean = false,
        onOptimizationProgress: ((domain: String, tested: Int, total: Int) -> Unit)? = null
    ) {
        try {
            // Keep OkHttp and the challenge WebView on the exact same device UA.
            // A desktop UA paired with Android WebView navigator values is a common
            // source of low Turnstile scores and repeated challenge pages.
            activeUserAgent = runCatching {
                WebSettings.getDefaultUserAgent(context.applicationContext)
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: HTTP_USER_AGENT
            val targetDomain = AppSettings.getTargetDomain(context)
            // Clearance cookies belong to the browser identity used for verification.
            val userAgent = withContext(Dispatchers.Main) { WebSettings.getDefaultUserAgent(context) }
            activeUserAgent = userAgent
            val builder = OkHttpClient.Builder()
                .cookieJar(SharedCookieJar())
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
                }
                .addInterceptor(ChallengeInterceptor(context.applicationContext))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
                .dispatcher(Dispatcher().apply {
                    maxRequests = 24
                    maxRequestsPerHost = 12
                })
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            if (AppSettings.isIpOptimizationEnabled(context)) {
                val bestIp = AppSettings.getManualIp(context)
                    ?: IpOptimizer.getBestIpSmart(context, forceRefresh = forceRefresh, domainKey = targetDomain) { tested, total ->
                        onOptimizationProgress?.invoke(targetDomain, tested, total)
                    }.ip
                val routes = mutableMapOf(targetDomain to bestIp)
                if (!AppSettings.isCdnDirect(context)) {
                    val cdnIp = AppSettings.getManualIp(context) ?: IpOptimizer.getBestIpSmart(context, forceRefresh = forceRefresh, domainKey = CDN_DOMAIN) { tested, total ->
                        onOptimizationProgress?.invoke(CDN_DOMAIN, tested, total)
                    }.ip
                    routes[CDN_DOMAIN] = cdnIp
                } else {
                    Log.d(TAG, "derpicdn.net 使用直连，不设置 CDN 优选 IP")
                }
                // Turnstile assets and challenge callbacks are served from a
                // different Cloudflare hostname. Keep its route independent
                // from the site's and CDN's preferred IPs: the proxy still
                // tunnels TLS with the original hostname/SNI.
                val challengeIp = IpOptimizer.getBestIpSmart(
                    context,
                    forceRefresh = forceRefresh,
                    domainKey = CHALLENGE_DOMAIN
                ) { tested, total ->
                    onOptimizationProgress?.invoke(CHALLENGE_DOMAIN, tested, total)
                }.ip
                routes[CHALLENGE_DOMAIN] = challengeIp
                preferredRouteIps = routes
                Log.d(TAG, "使用优选 IP 路由: $routes")
                localProxyServer = LocalProxyServer(preferredRouteIps).apply { start() }
                builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", localProxyServer!!.port)))
            } else {
                Log.d(TAG, "使用直连模式")
                preferredRouteIps = emptyMap()
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
            if (ChallengeBackoff.isBlocked()) {
                Log.w(TAG, "请求已被挑战退避窗口拦截")
                return null
            }
            if (!rateLimiter.awaitPermit(url)) {
                Log.w(TAG, "请求已被本地封锁保护拦截：${redactUrl(url)}")
                return null
            }
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", activeUserAgent)
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
                        Log.w(TAG, "非成功响应: $code, url=${redactUrl(url)}, body: $errorBody")
                        when (code) {
                            // Derpibooru's 500 empty-body response indicates an IP block.
                            // Any request during this period resets the remote 15-minute timer.
                            500 -> if (errorBody.isNullOrBlank()) {
                                rateLimiter.blockFor(BLOCK_DURATION_MS)
                                return null
                            } else {
                                delay((attempt + 1) * 1_200L)
                            }
                            // Stop probing after a rate-limit or anti-bot signal instead of
                            // retrying aggressively and escalating into a 15-minute block.
                            429, 501 -> {
                                rateLimiter.blockFor(SOFT_COOLDOWN_MS)
                                return null
                            }
                            else -> delay(500L)
                        }
                    }
                }
            } catch (e: ChallengeCancelledException) {
                return null
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

    /**
     * 图片加载器与 API 复用同一个客户端，因而会复用本地代理、TLS/HTTP2 连接池。
     * 调用方只应创建 Call，不应关闭或改造该客户端。
     */
    fun imageHttpClient(): OkHttpClient? = okHttpClient

    private fun redactUrl(url: String): String = url.replace(Regex("([?&]key=)[^&]*"), "$1***")

    fun currentPreferredIps(): Map<String, String> = preferredRouteIps.toMap()
    fun localProxyPort(): Int? = localProxyServer?.port?.takeIf { it > 0 }
    fun userAgent(): String = activeUserAgent

    suspend fun reinitialize(context: Context, forceRefresh: Boolean = false) {
        shutdown()
        init(context, forceRefresh)
    }

    fun shutdown() {
        localProxyServer?.stop()
        localProxyServer = null
        okHttpClient = null
        preferredRouteIps = emptyMap()
    }

    private class ApiRateLimiter {
        private val mutex = Mutex()
        private val normalRequests = ArrayDeque<Long>()
        private val searchRequests = ArrayDeque<Long>()
        private var blockedUntil = 0L

        suspend fun awaitPermit(url: String): Boolean {
            val isSearch = url.contains("/api/v1/json/search", ignoreCase = true)
            val windowMs = if (isSearch) SEARCH_WINDOW_MS else NORMAL_WINDOW_MS
            val maxRequests = if (isSearch) SEARCH_MAX_REQUESTS else NORMAL_MAX_REQUESTS

            while (true) {
                val waitMs = mutex.withLock {
                    val now = System.currentTimeMillis()
                    if (now < blockedUntil) return false

                    val requests = if (isSearch) searchRequests else normalRequests
                    while (requests.isNotEmpty() && now - requests.first() >= windowMs) requests.removeFirst()
                    if (requests.size < maxRequests) {
                        requests.addLast(now)
                        return true
                    }
                    (windowMs - (now - requests.first())).coerceAtLeast(1L)
                }
                delay(waitMs)
            }
        }

        suspend fun blockFor(durationMs: Long) = mutex.withLock {
            blockedUntil = maxOf(blockedUntil, System.currentTimeMillis() + durationMs)
            normalRequests.clear()
            searchRequests.clear()
        }
    }

    private const val NORMAL_MAX_REQUESTS = 30
    private const val NORMAL_WINDOW_MS = 5_000L
    private const val SEARCH_MAX_REQUESTS = 20
    private const val SEARCH_WINDOW_MS = 10_000L
    private const val BLOCK_DURATION_MS = 15 * 60 * 1_000L
    private const val SOFT_COOLDOWN_MS = 5 * 1_000L
    private const val CDN_DOMAIN = "derpicdn.net"
    private const val CHALLENGE_DOMAIN = "challenges.cloudflare.com"
}
