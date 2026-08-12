package com.kerybotu.derpibooru.mirror

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * 负责获取 Cloudflare 优选 IP 列表并做测速优选。
 *
 * 两层缓存：
 * - IP 列表缓存 1 小时（省 Worker 请求配额）
 * - 测速结果缓存 5 分钟（短时间内重复启动 App，直接复用上次优选结果，跳过测速动画）
 * - Worker 请求失败时回退到预编码的兜底列表，保证任何情况下都能启动
 */
object IpOptimizer {

    private const val PREFS_NAME = "ip_optimizer_prefs"

    private const val KEY_IP_LIST = "cached_ip_list"
    private const val KEY_FETCH_TIME = "cached_fetch_time"
    private const val CACHE_TTL_MS = 60 * 60 * 1000L // IP 列表缓存 1 小时

    private const val KEY_LAST_BEST_IP = "last_best_ip"
    private const val KEY_LAST_BEST_TIME = "last_best_time"
    private const val SHORT_CACHE_TTL_MS = 5 * 60 * 1000L // 测速结果缓存 5 分钟

    private const val WORKER_URL = "https://cloudflarecdnip.495648.xyz/"

    // ================= Worker 挂掉时的兜底列表（预编码）=================
    // 建议定期手动从 Worker 拉一次最新结果，同步更新这份列表
    private val FALLBACK_IP_LIST = listOf(
        "172.67.102.229",
        "104.24.169.89",
        "104.24.64.97",
        "104.19.229.139",
        "172.67.99.229",
        "104.18.115.232",
        "104.16.242.11",
        "104.16.36.198",
        "104.16.56.150",
        "104.16.204.69",
        "104.16.51.59",
        "104.18.91.208",
        "104.16.97.232",
        "172.64.32.208",
        "104.18.59.245",
        "162.159.133.53",
        "104.25.49.254",
        "172.67.209.210",
        "104.17.36.108",
        "104.24.188.188",
        "104.25.250.168",
        "172.64.148.105",
        "172.65.169.125",
        "104.25.215.175",
        "198.41.215.57",
        "104.21.29.208",
        "104.17.139.58",
        "104.17.100.37",
        "104.19.179.132",
        "104.25.146.43",
        "162.159.7.215",
        "104.17.199.76",
        "104.21.123.255",
        "104.25.143.160",
        "198.41.204.173",
        "172.66.135.11",
        "104.18.77.189",
        "172.65.209.6",
        "104.27.40.161",
        "104.21.208.192",
        "104.21.74.240",
        "104.20.12.15",
        "104.16.184.203",
        "104.17.174.88",
        "104.24.230.53",
        "162.159.207.62",
        "104.25.206.62",
        "104.17.216.47",
        "104.18.52.206",
        "172.67.118.177",
        "172.64.94.194",
        "104.17.188.202",
        "104.27.31.132",
        "104.18.207.121",
        "104.18.196.18",
        "172.67.200.140",
        "104.27.99.93",
        "104.18.200.94",
        "104.18.92.166",
        "104.21.73.214",
        "104.24.185.159",
        "104.21.4.172",
        "104.18.184.72",
        "172.64.147.112",
        "104.16.161.157",
        "104.17.170.145",
        "104.17.73.246",
        "104.17.196.138",
        "172.64.83.150",
        "104.19.223.152",
        "172.67.221.198",
        "104.17.213.45",
        "104.19.102.244",
        "172.67.67.99",
        "104.25.19.24",
        "104.16.126.183",
        "172.67.175.146",
        "172.66.159.152",
        "104.16.56.181",
        "104.18.51.173",
        "104.25.181.19",
        "104.25.108.152",
        "104.18.52.16",
        "172.64.77.104",
        "104.25.204.223",
        "104.17.126.29",
        "104.24.89.62",
        "104.16.73.30",
        "104.27.18.197",
        "172.67.128.44",
        "104.17.13.169",
        "104.27.109.45",
        "172.66.173.55",
        "172.64.230.153",
        "104.17.67.81",
        "104.19.59.212",
        "104.16.65.42",
        "172.64.69.162",
        "104.27.200.250",
        "104.16.90.76",
        "104.16.0.224",
        "172.67.67.28",
        "172.64.147.183",
        "104.16.104.13",
        "104.18.83.228",
        "104.16.244.231",
        "104.17.201.10",
        "104.18.18.108",
        "172.67.210.42",
        "104.21.198.99",
        "162.159.22.192",
        "104.21.99.62",
        "172.67.166.27",
        "172.67.215.80",
        "104.16.150.100",
        "104.17.88.197",
        "104.27.49.170",
        "104.17.50.124",
        "104.17.43.238",
        "104.25.192.246",
        "104.18.77.13",
        "104.18.217.72",
        "104.18.146.19",
        "104.25.165.45",
        "104.19.33.238",
        "104.18.205.118",
        "172.64.187.178",
        "104.24.33.118",
        "104.16.108.62",
        "104.27.59.153",
        "104.25.6.226",
        "104.17.117.95",
        "104.16.242.39",
        "104.20.5.51",
        "162.159.133.190",
        "104.24.229.129",
        "172.66.157.204",
        "104.21.77.164",
        "104.21.222.19",
        "104.27.122.27",
        "104.16.99.126",
        "173.245.59.33",
        "104.21.75.135",
        "104.21.15.147",
        "104.25.75.197",
        "104.20.5.242",
        "104.21.216.20",
        "104.24.8.87",
        "104.21.87.17",
        "104.27.46.161",
        "104.19.180.162",
        "104.19.254.114",
        "104.18.231.147",
        "172.67.152.109",
        "104.24.81.177",
        "172.67.198.60",
        "104.24.82.90",
        "104.27.66.174",
        "104.19.157.115",
        "172.65.36.156",
        "104.18.82.108",
        "104.25.86.206",
        "104.17.198.216",
        "104.18.239.201",
        "104.16.16.230",
        "104.25.190.63",
        "104.27.203.224",
        "104.17.211.34",
        "172.67.95.255",
        "172.66.209.169",
        "104.24.240.74",
        "104.19.23.24",
        "104.25.47.13",
        "104.19.12.251",
        "162.159.232.192",
        "104.24.150.80",
        "104.21.55.104",
        "104.16.173.17",
        "104.24.198.117",
        "104.27.195.136",
        "104.20.38.100",
        "172.67.178.164",
        "172.66.165.44",
        "198.41.223.134",
        "104.19.81.131",
        "104.17.7.153",
        "104.24.67.77",
        "104.25.4.241",
        "104.27.62.113",
        "104.16.169.180",
        "104.16.207.105",
        "162.159.61.43",
        "104.27.7.161",
        "104.24.211.96",
        "104.18.194.53",
        "104.16.154.139",
        "162.159.231.94",
        "162.159.19.50",
        "104.19.171.251",
        "104.16.249.166",
        "104.17.141.143"
    )
    // ====================================================================

    private const val CONNECT_TIMEOUT_MS = 1200
    private const val OVERALL_TIMEOUT_MS = 3000L

    /** didOptimize=false 表示命中了 5 分钟短缓存，未真正重新测速 */
    data class OptimizeResult(val ip: String, val didOptimize: Boolean)

    /**
     * 唯一对外入口。
     * @param forceRefresh 手动"重新优选"时传 true，跳过所有缓存强制刷新
     */
    suspend fun getBestIpSmart(context: Context, forceRefresh: Boolean = false): OptimizeResult {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        if (!forceRefresh) {
            val lastIp = prefs.getString(KEY_LAST_BEST_IP, null)
            val lastTime = prefs.getLong(KEY_LAST_BEST_TIME, 0L)
            if (!lastIp.isNullOrBlank() && now - lastTime < SHORT_CACHE_TTL_MS) {
                return OptimizeResult(lastIp, didOptimize = false)
            }
        }

        val ipList = getIpList(context, forceRefresh)

        // 阶段一：原有的纯 TCP 测速排序，逻辑完全不变
        val rankedByLatency = speedTestRanked(ipList, 443)
        val fastest = rankedByLatency.firstOrNull() ?: ipList.firstOrNull() ?: FALLBACK_IP_LIST.first()

        // 阶段二（新增，最小化改动）：只对"测速最快的这一个"做一次真实验证，
        // 不并发、不批量、只此一次，行为上等同于用户正常打开一次网页，
        // 避免像之前那样对多个陌生 IP 发起密集连接触发风控。
        // 如果这一个验证失败（1034），退而求其次换下一个候选，最多再试 1 次，到此为止不再继续。
        val best = withContext(Dispatchers.IO) {
            if (validateSingleIp(fastest)) {
                fastest
            } else {
                val second = rankedByLatency.getOrNull(1)
                if (second != null && validateSingleIp(second)) second else fastest
            }
        }

        prefs.edit()
            .putString(KEY_LAST_BEST_IP, best)
            .putLong(KEY_LAST_BEST_TIME, now)
            .apply()

        return OptimizeResult(best, didOptimize = true)
    }

    // -------------------- IP 列表获取（缓存 + Worker + 兜底） --------------------

    private suspend fun getIpList(context: Context, forceRefresh: Boolean): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastFetchTime = prefs.getLong(KEY_FETCH_TIME, 0L)
        val now = System.currentTimeMillis()

        if (!forceRefresh && now - lastFetchTime < CACHE_TTL_MS) {
            val cached = prefs.getString(KEY_IP_LIST, null)
            if (!cached.isNullOrBlank()) {
                val list = cached.split(",").filter { it.isNotBlank() }
                if (list.isNotEmpty()) return list
            }
        }

        val fetched = fetchFromWorker()
        if (fetched.isNotEmpty()) {
            prefs.edit()
                .putString(KEY_IP_LIST, fetched.joinToString(","))
                .putLong(KEY_FETCH_TIME, now)
                .apply()
            return fetched
        }

        val staleCache = prefs.getString(KEY_IP_LIST, null)
        if (!staleCache.isNullOrBlank()) {
            val list = staleCache.split(",").filter { it.isNotBlank() }
            if (list.isNotEmpty()) return list
        }

        return FALLBACK_IP_LIST
    }

    private suspend fun fetchFromWorker(): List<String> = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(4000L) {
                val conn = URL(WORKER_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "GET"

                val code = conn.responseCode
                if (code == 200) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    text.lines()
                        .map { it.trim() }
                        .filter { it.isNotBlank() && isValidIp(it) }
                } else {
                    conn.disconnect()
                    emptyList()
                }
            } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun isValidIp(s: String): Boolean {
        val parts = s.split(".")
        if (parts.size != 4) return false
        return parts.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
    }

    // -------------------- 并发测速优选 --------------------

    private suspend fun speedTestRanked(ipList: List<String>, port: Int): List<String> =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(OVERALL_TIMEOUT_MS) {
                val deferredResults = ipList.map { ip ->
                    async {
                        val latency = measureTcpConnectLatency(ip, port)
                        ip to latency
                    }
                }
                deferredResults.awaitAll()
                    .filter { it.second >= 0 }
                    .sortedBy { it.second }
                    .map { it.first }
            } ?: emptyList()
        }

    private fun measureTcpConnectLatency(ip: String, port: Int): Long {
        return try {
            val start = System.currentTimeMillis()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
            }
            System.currentTimeMillis() - start
        } catch (e: Exception) {
            -1L
        }
    }

    // -------------------- 单 IP 真实验证（新增，仅验证最终候选，不做批量） --------------------

    private const val TARGET_DOMAIN_FOR_VALIDATION = "derpibooru.org"

    private fun validateSingleIp(ip: String): Boolean {
        var rawSocket: java.net.Socket? = null
        var sslSocket: javax.net.ssl.SSLSocket? = null
        return try {
            rawSocket = java.net.Socket()
            rawSocket.connect(InetSocketAddress(ip, 443), 4000)
            rawSocket.soTimeout = 4000

            val factory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
            sslSocket = factory.createSocket(rawSocket, TARGET_DOMAIN_FOR_VALIDATION, 443, true) as javax.net.ssl.SSLSocket
            sslSocket.soTimeout = 4000
            sslSocket.startHandshake()

            val out = sslSocket.outputStream
            val request = "GET / HTTP/1.1\r\n" +
                    "Host: $TARGET_DOMAIN_FOR_VALIDATION\r\n" +
                    "User-Agent: Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36\r\n" +
                    "Connection: close\r\n\r\n"
            out.write(request.toByteArray(Charsets.UTF_8))
            out.flush()

            val reader = java.io.BufferedReader(java.io.InputStreamReader(sslSocket.inputStream))
            val statusLine = reader.readLine() ?: return false
            val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: return false

            // 读完响应体再关闭，避免半截断开的异常流量特征
            try {
                val buffer = CharArray(2048)
                var totalRead = 0
                while (totalRead < 65536) {
                    val n = reader.read(buffer)
                    if (n == -1) break
                    totalRead += n
                }
            } catch (_: Exception) {}

            statusCode != 530
        } catch (e: Exception) {
            false
        } finally {
            try { sslSocket?.close() } catch (_: Exception) {}
            try { rawSocket?.close() } catch (_: Exception) {}
        }
    }
}