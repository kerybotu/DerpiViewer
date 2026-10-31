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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Protocol
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.net.SocketException
import java.util.UUID
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
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
    @Volatile private var mediaHttpClient: OkHttpClient? = null
    private val initializationMutex = Mutex()
    private val revision = MutableStateFlow(0L)
    val configurationVersion = revision.asStateFlow()
    @Volatile private var preferredRouteIps: Map<String, String> = emptyMap()
    private val rateLimiter = ApiRateLimiter()
    private val diagnosticsLock = Any()
    private val diagnostics = ArrayDeque<ApiRequestDiagnostic>()
    @Volatile private var lastApiFailure: ApiRequestDiagnostic? = null

    data class ApiRequestResult(val body: String?, val failure: ApiRequestDiagnostic? = null)

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
    ) = initializationMutex.withLock {
        if (isReady() && !forceRefresh) return@withLock
        if (isReady()) shutdown()
        val startedAtVersion = revision.value
        var pendingProxy: LocalProxyServer? = null
        var pendingRoutes = emptyMap<String, String>()
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
                pendingRoutes = routes
                Log.d(TAG, "使用优选 IP 路由: $routes")
                pendingProxy = LocalProxyServer(routes).apply { start() }
                builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", pendingProxy.port)))
            } else {
                Log.d(TAG, "使用直连模式")
            }
            if (revision.value != startedAtVersion) {
                pendingProxy?.stop()
                return@withLock // A settings change superseded this initialization.
            }
            localProxyServer = pendingProxy
            preferredRouteIps = pendingRoutes
            okHttpClient = builder.build()
            // No total call deadline: a legitimate streamed response can last
            // longer than a minute. Read timeout bounds a stalled read instead.
            mediaHttpClient = okHttpClient!!.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()
            revision.value += 1
            Log.d(TAG, "OkHttpClient 初始化完成")
        } catch (e: Exception) {
            pendingProxy?.stop()
            Log.e(TAG, "初始化失败", e)
            throw e
        }
    }

    suspend fun get(url: String, maxRetries: Int = 3): String? =
        getWithDiagnostics(url, maxRetries).body

    suspend fun getWithDiagnostics(
        url: String,
        maxRetries: Int = 3,
        operation: String = "GET"
    ): ApiRequestResult {
        val client = okHttpClient ?: run {
            val failure = diagnostic(url, operation, "client", ApiFailureKind.CONFIGURATION_CHANGED, null, 0L, 1, maxRetries, true, "client_not_initialized")
            recordDiagnostic(failure)
            return ApiRequestResult(null, failure)
        }
        val requestId = UUID.randomUUID().toString().take(8)
        val attempts = maxRetries.coerceAtLeast(1)
        val startedConfig = revision.value
        var finalFailure: ApiRequestDiagnostic? = null
        repeat(attempts) { index ->
            val attempt = index + 1
            if (ChallengeBackoff.isBlocked()) {
                val failure = diagnostic(url, operation, "backoff", ApiFailureKind.RATE_LIMITED, requestId, 0L, attempt, attempts, false, "challenge_backoff")
                finalFailure = failure
                recordDiagnostic(failure)
                return ApiRequestResult(null, failure)
            }
            if (!rateLimiter.awaitPermit(url)) {
                val failure = diagnostic(url, operation, "rate_limit", ApiFailureKind.RATE_LIMITED, requestId, 0L, attempt, attempts, false, "local_cooldown")
                finalFailure = failure
                recordDiagnostic(failure)
                return ApiRequestResult(null, failure)
            }
            if (revision.value != startedConfig) {
                val failure = diagnostic(url, operation, "configuration", ApiFailureKind.CONFIGURATION_CHANGED, requestId, 0L, attempt, attempts, true, "network_configuration_changed")
                finalFailure = failure
                recordDiagnostic(failure)
                return ApiRequestResult(null, failure)
            }
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", activeUserAgent)
                .header("Accept", "application/json")
                .build()
            val requestStartedAt = System.nanoTime() / 1_000_000L
            try {
                val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
                response.use {
                    // A response can arrive just after the user changes the
                    // route/proxy. Do not let that old connection publish a
                    // successful body into a caller that already belongs to
                    // the new network configuration.
                    if (revision.value != startedConfig) {
                        val failure = diagnostic(url, operation, "configuration", ApiFailureKind.CONFIGURATION_CHANGED, requestId, elapsed(requestStartedAt), attempt, attempts, true, "network_configuration_changed")
                        finalFailure = failure
                        recordDiagnostic(failure)
                        return ApiRequestResult(null, failure)
                    }
                    val code = it.code
                    if (it.isSuccessful) {
                        val body = it.body?.string()
                        if (revision.value != startedConfig) {
                            val failure = diagnostic(url, operation, "configuration", ApiFailureKind.CONFIGURATION_CHANGED, requestId, elapsed(requestStartedAt), attempt, attempts, true, "network_configuration_changed")
                            finalFailure = failure
                            recordDiagnostic(failure)
                            return ApiRequestResult(null, failure)
                        }
                        if (body.isNullOrBlank()) {
                            val failure = diagnostic(url, operation, "body", ApiFailureKind.EMPTY_BODY, requestId, elapsed(requestStartedAt), attempt, attempts, false, "empty_response")
                            finalFailure = failure
                            recordDiagnostic(failure)
                            return ApiRequestResult(null, failure)
                        }
                        return ApiRequestResult(body)
                    }
                    val kind = when {
                        code == 429 || code == 501 -> ApiFailureKind.RATE_LIMITED
                        code in 400..499 -> ApiFailureKind.HTTP_4XX
                        else -> ApiFailureKind.HTTP_5XX
                    }
                    val retryable = code == 408 || code == 425 || code == 429 || code >= 500
                    val failure = diagnostic(url, operation, "http", kind, requestId, elapsed(requestStartedAt), attempt, attempts, retryable, "http_$code")
                    finalFailure = failure
                    recordDiagnostic(failure)
                    when (code) {
                        500 -> {
                            // A blank 500 response is the server's current IP-block
                            // signal. Content-Length is often -1 for a chunked
                            // response, so inspect a bounded body instead of relying
                            // on that header alone. Never retain or log its contents.
                            val errorBody = it.body?.string()?.take(512)
                            if (errorBody.isNullOrBlank()) {
                                rateLimiter.blockFor(BLOCK_DURATION_MS)
                                return ApiRequestResult(null, failure)
                            }
                            if (attempt < attempts) delay(attempt * 1_200L)
                        }
                        429, 501 -> {
                            rateLimiter.blockFor(SOFT_COOLDOWN_MS)
                            return ApiRequestResult(null, failure)
                        }
                        else -> if (attempt < attempts) delay(500L)
                    }
                }
            } catch (e: ChallengeCancelledException) {
                val failure = diagnostic(url, operation, "challenge", ApiFailureKind.CANCELLED, requestId, elapsed(requestStartedAt), attempt, attempts, false, e.javaClass.simpleName)
                finalFailure = failure
                recordDiagnostic(failure)
                return ApiRequestResult(null, failure)
            } catch (e: CancellationException) {
                val failure = diagnostic(url, operation, "cancelled", ApiFailureKind.CANCELLED, requestId, elapsed(requestStartedAt), attempt, attempts, false, e.javaClass.simpleName)
                finalFailure = failure
                recordDiagnostic(failure)
                throw e
            } catch (e: Exception) {
                val kind = classify(e)
                val failure = diagnostic(url, operation, "execute", kind, requestId, elapsed(requestStartedAt), attempt, attempts, attempt < attempts && kind != ApiFailureKind.TLS, rootCause(e))
                finalFailure = failure
                recordDiagnostic(failure)
                if (attempt < attempts) delay(2_000L) else return ApiRequestResult(null, failure)
            }
        }
        return ApiRequestResult(null, finalFailure)
    }

    suspend fun getApi(context: Context, path: String): String? = get(apiUrl(context, path))

    suspend fun getApiWithDiagnostics(context: Context, path: String): ApiRequestResult =
        getWithDiagnostics(apiUrl(context, path), operation = path.substringBefore('?'))

    fun recentDiagnostics(): List<ApiRequestDiagnostic> = synchronized(diagnosticsLock) { diagnostics.toList() }
    fun lastDiagnostic(): ApiRequestDiagnostic? = lastApiFailure

    /** Records a parsing/model failure that happened after a successful HTTP response. */
    fun recordClientDiagnostic(value: ApiRequestDiagnostic) = recordDiagnostic(value)

    private fun diagnostic(url: String, operation: String, phase: String, kind: ApiFailureKind, requestId: String?, elapsedMs: Long, attempt: Int, maxAttempts: Int, retryable: Boolean, cause: String?): ApiRequestDiagnostic = ApiRequestDiagnostic(
        requestId = requestId ?: UUID.randomUUID().toString().take(8),
        operation = operation.take(80),
        host = Uri.parse(url).host?.take(120) ?: "unknown",
        phase = phase,
        kind = kind,
        statusCode = cause?.removePrefix("http_")?.toIntOrNull(),
        elapsedMs = elapsedMs,
        attempt = attempt,
        maxAttempts = maxAttempts,
        networkConfigurationVersion = revision.value,
        retryable = retryable,
        rootCause = cause?.take(160)
    )

    private fun recordDiagnostic(value: ApiRequestDiagnostic) {
        lastApiFailure = value
        synchronized(diagnosticsLock) {
            if (diagnostics.size >= 40) diagnostics.removeFirst()
            diagnostics.addLast(value)
        }
        Log.w(TAG, "api_failure ${value.summary()}")
    }

    private fun classify(error: Throwable): ApiFailureKind = when {
        error is UnknownHostException -> ApiFailureKind.DNS
        error is SSLException -> ApiFailureKind.TLS
        error is SocketTimeoutException && error.message.orEmpty().contains("connect", true) -> ApiFailureKind.CONNECT_TIMEOUT
        error is SocketTimeoutException -> ApiFailureKind.READ_TIMEOUT
        error is ConnectException -> ApiFailureKind.TCP
        error is SocketException -> ApiFailureKind.TCP
        else -> ApiFailureKind.UNKNOWN
    }

    private fun rootCause(error: Throwable): String = generateSequence(error) { it.cause }
        .lastOrNull()?.let { "${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}" }
        ?: error.javaClass.simpleName

    private fun elapsed(startedAt: Long): Long = ((System.nanoTime() / 1_000_000L) - startedAt).coerceAtLeast(0L)

    suspend fun forceOptimize(context: Context): IpOptimizer.OptimizeResult =
        IpOptimizer.getBestIpSmart(context, forceRefresh = true)

    fun isReady(): Boolean = okHttpClient != null

    /**
     * 图片加载器与 API 复用同一个客户端，因而会复用本地代理、TLS/HTTP2 连接池。
     * 调用方只应创建 Call，不应关闭或改造该客户端。
     */
    fun imageHttpClient(): OkHttpClient? = okHttpClient

    /**
     * Media requests can legitimately pause while a CDN opens a range stream.
     * Keep the same cookies, proxy and challenge interceptors as image/API calls,
     * but allow a longer read window so a slow video is not classified as a
     * permanent playback failure at the normal API timeout.
     */
    fun videoHttpClient(): OkHttpClient? = mediaHttpClient

    private fun redactUrl(url: String): String = url.replace(Regex("([?&]key=)[^&]*"), "$1***")

    fun currentPreferredIps(): Map<String, String> = preferredRouteIps.toMap()
    fun localProxyPort(): Int? = localProxyServer?.port?.takeIf { it > 0 }
    fun userAgent(): String = activeUserAgent

    suspend fun reinitialize(context: Context, forceRefresh: Boolean = false) {
        shutdown()
        init(context, forceRefresh)
    }

    fun shutdown() {
        val oldClient = okHttpClient
        oldClient?.dispatcher?.cancelAll()
        mediaHttpClient = null
        localProxyServer?.stop()
        localProxyServer = null
        okHttpClient = null
        preferredRouteIps = emptyMap()
        revision.value += 1
        if (oldClient != null) Thread {
            runCatching { oldClient.connectionPool.evictAll() }
            runCatching { oldClient.dispatcher.executorService.shutdown() }
        }.apply { isDaemon = true; name = "DerpiNetworkClose"; start() }
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
