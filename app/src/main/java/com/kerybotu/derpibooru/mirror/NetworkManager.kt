package com.kerybotu.derpibooru.mirror.network

import android.content.Context
import android.util.Log
import android.webkit.WebSettings
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.IpOptimizer
import com.kerybotu.derpibooru.mirror.LocalProxyServer
import com.kerybotu.derpibooru.mirror.auth.ApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    private val routingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val routingQuality = NodeRoutingPolicy()
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

    /** Merges a locally managed search preset without URL-encoding it twice. */
    fun mergeCurrentFilterQuery(context: Context, query: String): String {
        val preset = AppSettings.getCurrentFilterQuery(context)?.trim().orEmpty()
        val base = query.trim()
        if (preset.isEmpty()) return base
        if (base.isEmpty()) return preset
        return "($base) ($preset)"
    }

    fun encodeSearchQuery(context: Context, query: String): String =
        java.net.URLEncoder.encode(mergeCurrentFilterQuery(context, query), "UTF-8")

    /** Builds a normal site URL while letting Uri encode each query value once. */
    fun sitePageUrl(context: Context, path: String, parameters: Map<String, String> = emptyMap()): String {
        val base = "https://${AppSettings.getTargetDomain(context)}/${path.trimStart('/')}"
        return Uri.parse(base).buildUpon().apply {
            parameters.forEach { (key, value) -> appendQueryParameter(key, value) }
        }.build().toString()
    }

    suspend fun init(
        context: Context,
        forceRefresh: Boolean = false,
        rebuildClient: Boolean = false,
        onOptimizationProgress: ((domain: String, tested: Int, total: Int) -> Unit)? = null
    ) = initializationMutex.withLock {
        if (isReady() && !forceRefresh && !rebuildClient) return@withLock
        val startedAtVersion = revision.value
        var pendingProxy: LocalProxyServer? = null
        var pendingRoutes = emptyMap<String, String>()
        try {
            val targetDomain = AppSettings.getTargetDomain(context)
            // Keep OkHttp and the challenge WebView on one UA. Derive it from the
            // actual runtime, removing only Chrome's version token as validated by
            // the sibling browser project for Anubis difficulty on Android 9.
            val runtimeUserAgent = withContext(Dispatchers.Main) {
                WebSettings.getDefaultUserAgent(context.applicationContext)
            }.takeIf { it.isNotBlank() } ?: HTTP_USER_AGENT
            val userAgent = AnubisEnvironmentPolicy.versionlessUserAgent(runtimeUserAgent)
            activeUserAgent = userAgent
            Log.i(
                "AnubisEnvironment",
                "event=request_user_agent_policy mode=versionless chrome_major=none " +
                    "runtimeChromeMajor=${Regex("Chrome/(\\d+)", RegexOption.IGNORE_CASE).find(runtimeUserAgent)?.groupValues?.get(1) ?: "none"}"
            )
            val builder = OkHttpClient.Builder()
                .eventListenerFactory { routeListener(context.applicationContext) }
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
                val manualIp = AppSettings.getManualIp(context)
                val domainsToOptimize = buildList {
                    if (manualIp == null) add(targetDomain)
                    if (!AppSettings.isCdnDirect(context) && manualIp == null) add(CDN_DOMAIN)
                    add(CHALLENGE_DOMAIN)
                }
                val sharedResults = IpOptimizer.getBestIpsShared(
                    context,
                    domainsToOptimize,
                    forceRefresh = forceRefresh
                ) { domain, tested, total ->
                    onOptimizationProgress?.invoke(domain, tested, total)
                }
                // The candidate benchmark is shared once; each hostname is
                // selected from that ranking after its own short TCPing pass.
                val bestIp = manualIp ?: sharedResults[targetDomain]?.ip
                    ?: IpOptimizer.getBestIpSmart(context, forceRefresh = forceRefresh, domainKey = targetDomain).ip
                val routes = mutableMapOf(targetDomain to bestIp)
                if (!AppSettings.isCdnDirect(context)) {
                    val cdnIp = manualIp ?: sharedResults[CDN_DOMAIN]?.ip
                        ?: IpOptimizer.getBestIpSmart(context, forceRefresh = forceRefresh, domainKey = CDN_DOMAIN).ip
                    routes[CDN_DOMAIN] = cdnIp
                } else {
                    Log.d(TAG, "derpicdn.net 使用直连，不设置 CDN 优选 IP")
                }
                // Turnstile assets and challenge callbacks are served from a
                // different Cloudflare hostname. Keep its route independent
                // from the site's and CDN's preferred IPs: the proxy still
                // tunnels TLS with the original hostname/SNI.
                val challengeIp = sharedResults[CHALLENGE_DOMAIN]?.ip
                    ?: IpOptimizer.getBestIpSmart(context, forceRefresh = forceRefresh, domainKey = CHALLENGE_DOMAIN).ip
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
            // Keep the working route alive until its replacement is ready.
            if (isReady()) shutdown()
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
                .header("Accept", if (operation == "site:filters") "text/html,application/xhtml+xml" else "application/json")
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
                    if (operation == "site:filters") {
                        Log.d(TAG, "FilterSearch response requestId=$requestId attempt=$attempt status=$code finalUrl=${safeDiagnosticUrl(it.request.url.toString())} contentType=${it.header("Content-Type").orEmpty()} cookies=${SharedCookieJar.cookieNames(it.request.url.toString()).joinToString(",").ifBlank { "none" }}")
                    }
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
                        if (isBusinessErrorPayload(body)) {
                            val failure = diagnostic(url, operation, "body", ApiFailureKind.BUSINESS_ERROR, requestId, elapsed(requestStartedAt), attempt, attempts, false, "business_error_payload")
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

    suspend fun getSitePageWithDiagnostics(
        context: Context,
        path: String,
        parameters: Map<String, String> = emptyMap()
    ): ApiRequestResult = getWithDiagnostics(
        sitePageUrl(context, path, parameters),
        operation = "site:${path.trimStart('/')}"
    )

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

    private fun safeDiagnosticUrl(url: String): String = runCatching {
        val parsed = Uri.parse(url)
        parsed.buildUpon().clearQuery().apply {
            parsed.getQueryParameter("fq")?.let { appendQueryParameter("fq", it.take(160)) }
            parsed.getQueryParameter("page")?.let { appendQueryParameter("page", it) }
        }.build().toString()
    }.getOrDefault("https://derpibooru.org/filters")

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

    private fun routeListener(context: Context) = object : okhttp3.EventListener() {
        private val version = revision.value
        private var startedAt = System.nanoTime()
        private var headersMs = 0L
        private var bytes = 0L
        private var bodyStartedAt = 0L
        private var bodyMs = 0L
        private var eligible = false

        override fun requestHeadersEnd(call: okhttp3.Call, request: Request) {
            startedAt = System.nanoTime()
        }

        override fun responseHeadersEnd(call: okhttp3.Call, response: okhttp3.Response) {
            headersMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            // Challenges, authentication and server errors are not route failures.
            eligible = response.isSuccessful &&
                !response.header("Content-Type").orEmpty().contains("text/html", true)
        }

        override fun responseBodyStart(call: okhttp3.Call) { bodyStartedAt = System.nanoTime() }
        override fun responseBodyEnd(call: okhttp3.Call, byteCount: Long) {
            bytes = byteCount
            bodyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - bodyStartedAt)
        }

        override fun callEnd(call: okhttp3.Call) {
            if (eligible) report(call, false, headersMs > 8_000L ||
                (bytes >= 32_768 && bodyMs > 2_000 && bytes * 1_000 / bodyMs < 16_384))
        }

        override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) {
            if (!call.isCanceled() && classify(ioe) in setOf(ApiFailureKind.DNS, ApiFailureKind.TCP,
                    ApiFailureKind.TLS, ApiFailureKind.CONNECT_TIMEOUT, ApiFailureKind.READ_TIMEOUT)) {
                report(call, true, false)
            }
        }

        private fun report(call: okhttp3.Call, failed: Boolean, slow: Boolean) {
            val host = call.request().url.host
            if (version != revision.value || host !in preferredRouteIps ||
                !AppSettings.isIpOptimizationEnabled(context) || AppSettings.getManualIp(context) != null) return
            if (!routingQuality.observe(host, failed, slow, android.os.SystemClock.elapsedRealtime())) return
            Log.w("NodeRouting", "event=${if (failed) "history_connection_failed" else "quality_degraded"} host=$host")
            routingScope.launch {
                try {
                    val domains = preferredRouteIps.keys.toList()
                    val results = IpOptimizer.getBestIpsShared(context, domains, forceRefresh = true)
                    if (version == revision.value && results.values.all { it.verified } && domains.isNotEmpty()) {
                        reinitialize(context)
                        Log.i("NodeRouting", "event=route_switch_success")
                    } else Log.w("NodeRouting", "event=route_switch_failed reason=unverified_or_configuration_changed")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("NodeRouting", "event=route_switch_failed cause=${e.javaClass.simpleName}")
                } finally { routingQuality.finished() }
            }
        }
    }

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
        init(context, forceRefresh, rebuildClient = true)
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
