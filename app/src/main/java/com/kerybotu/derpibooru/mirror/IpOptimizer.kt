package com.kerybotu.derpibooru.mirror

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.random.Random

/**
 * 负责获取 Cloudflare 优选 IP 列表并做测速优选。
 *
 * 候选来源（合并去重后统一测速）：
 * 1. Worker 提供的列表（1 小时缓存）
 * 2. Cloudflare 官方 IP 段 API 随机采样（24 小时缓存，每次候选重新随机采样）
 * 3. 本地预编码兜底列表（Worker 完全不可用时使用）
 *
 * 测速结果缓存 5 分钟，短时间内重复启动直接复用。
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

    // -------------------- Cloudflare 官方 IP 段数据源 --------------------
    private const val CF_IPS_API_URL = "https://api.cloudflare.com/client/v4/ips"
    private const val KEY_CF_CIDRS = "cf_cidrs_cache"
    private const val KEY_CF_FETCH_TIME = "cf_cidrs_fetch_time"
    private const val CF_CACHE_TTL_MS = 24 * 60 * 60 * 1000L // CIDR 列表几乎不变，缓存 24 小时
    private const val CF_SAMPLE_PER_CIDR = 4 // 每个网段随机采样的 IP 数量
    private const val CF_MAX_SAMPLED_IPS = 60 // 采样总数上限，防止候选爆炸

    // ================= Worker 挂掉时的兜底列表（预编码）=================
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
    private const val OVERALL_TIMEOUT_MS = 8000L // 候选池扩大，总超时适当放宽
    private const val MAX_CONCURRENT_TESTS = 20 // 并发测速上限，避免资源耗尽

    data class OptimizeResult(val ip: String, val didOptimize: Boolean)

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

        val candidatePool = buildCandidatePool(context, forceRefresh)
        val best = speedTest(candidatePool, 443)
            ?: candidatePool.firstOrNull()
            ?: FALLBACK_IP_LIST.first()

        prefs.edit()
            .putString(KEY_LAST_BEST_IP, best)
            .putLong(KEY_LAST_BEST_TIME, now)
            .apply()

        return OptimizeResult(best, didOptimize = true)
    }

    /**
     * 合并三类来源，去重后返回统一候选池：
     * Worker 列表 + Cloudflare 官方网段随机采样 + 本地兜底列表
     */
    private suspend fun buildCandidatePool(context: Context, forceRefresh: Boolean): List<String> {
        val workerList = getIpList(context, forceRefresh)
        val cfSampled = getCloudflareSampledIps(context, forceRefresh)

        val merged = LinkedHashSet<String>()
        merged.addAll(workerList)
        merged.addAll(cfSampled)
        merged.addAll(FALLBACK_IP_LIST)
        return merged.toList()
    }

    // -------------------- IP 列表获取（Worker，缓存 + 兜底） --------------------

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

        return emptyList() // Worker 彻底不可用时返回空列表，最终仍有 CF 采样 + FALLBACK_IP_LIST 兜底
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
                    text.lines().map { it.trim() }.filter { it.isNotBlank() && isValidIp(it) }
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

    // -------------------- Cloudflare 官方 IP 段：拉取 + 缓存 + 随机采样 --------------------

    /**
     * 获取 Cloudflare 官方 IPv4 CIDR 段列表（24 小时缓存），
     * 并从每个网段随机采样若干 IP 作为额外候选。
     * API 请求失败时静默返回空列表，不影响其它数据源正常工作。
     */
    private suspend fun getCloudflareSampledIps(context: Context, forceRefresh: Boolean): List<String> {
        val cidrs = getCloudflareCidrs(context, forceRefresh)
        if (cidrs.isEmpty()) return emptyList()

        val sampled = mutableListOf<String>()
        for (cidr in cidrs) {
            val perCidr = sampleRandomIpsInCidr(cidr, CF_SAMPLE_PER_CIDR)
            sampled.addAll(perCidr)
            if (sampled.size >= CF_MAX_SAMPLED_IPS) break
        }
        return sampled.take(CF_MAX_SAMPLED_IPS)
    }

    private suspend fun getCloudflareCidrs(context: Context, forceRefresh: Boolean): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastFetchTime = prefs.getLong(KEY_CF_FETCH_TIME, 0L)

        if (!forceRefresh && now - lastFetchTime < CF_CACHE_TTL_MS) {
            val cached = prefs.getString(KEY_CF_CIDRS, null)
            if (!cached.isNullOrBlank()) {
                val list = cached.split(",").filter { it.isNotBlank() }
                if (list.isNotEmpty()) return list
            }
        }

        val fetched = fetchCloudflareCidrsFromApi()
        if (fetched.isNotEmpty()) {
            prefs.edit()
                .putString(KEY_CF_CIDRS, fetched.joinToString(","))
                .putLong(KEY_CF_FETCH_TIME, now)
                .apply()
            return fetched
        }

        // API 请求失败：用旧缓存兜底（哪怕过期），实在没有就返回空列表，
        // 不影响 Worker 列表 + 本地兜底列表继续正常工作
        val staleCache = prefs.getString(KEY_CF_CIDRS, null)
        if (!staleCache.isNullOrBlank()) {
            return staleCache.split(",").filter { it.isNotBlank() }
        }
        return emptyList()
    }

    private suspend fun fetchCloudflareCidrsFromApi(): List<String> = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(5000L) {
                val conn = URL(CF_IPS_API_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                if (code == 200) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    parseIpv4CidrsFromResponse(text)
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

    private fun parseIpv4CidrsFromResponse(json: String): List<String> {
        return try {
            val root = JSONObject(json)
            if (!root.optBoolean("success", false)) return emptyList()
            val result = root.optJSONObject("result") ?: return emptyList()
            val cidrsArray = result.optJSONArray("ipv4_cidrs") ?: return emptyList()
            (0 until cidrsArray.length()).map { cidrsArray.getString(it) }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * 从形如 "104.16.0.0/13" 的 CIDR 网段中，随机采样 count 个可用 IP。
     * 排除网络地址和广播地址（首尾各留一个），避免采样到无效地址。
     */
    private fun sampleRandomIpsInCidr(cidr: String, count: Int): List<String> {
        return try {
            val parts = cidr.split("/")
            if (parts.size != 2) return emptyList()
            val baseIp = ipToLong(parts[0]) ?: return emptyList()
            val prefixLen = parts[1].toIntOrNull() ?: return emptyList()
            if (prefixLen !in 0..32) return emptyList()

            val hostBits = 32 - prefixLen
            val rangeSize = if (hostBits >= 31) 1L else (1L shl hostBits)

            // 网段太小（/31、/32）直接返回网络地址本身；否则排除首尾，在中间随机采样
            if (rangeSize <= 2) {
                return listOf(longToIp(baseIp))
            }

            val usableStart = baseIp + 1
            val usableEnd = baseIp + rangeSize - 2
            val usableCount = usableEnd - usableStart + 1
            if (usableCount <= 0) return emptyList()

            val actualCount = minOf(count.toLong(), usableCount).toInt()
            val results = LinkedHashSet<String>()
            var attempts = 0
            while (results.size < actualCount && attempts < actualCount * 5) {
                val offset = Random.nextLong(0, usableCount)
                val candidate = usableStart + offset
                results.add(longToIp(candidate))
                attempts++
            }
            results.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun ipToLong(ip: String): Long? {
        val parts = ip.trim().split(".")
        if (parts.size != 4) return null
        var result = 0L
        for (part in parts) {
            val n = part.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            result = (result shl 8) or n.toLong()
        }
        return result
    }

    private fun longToIp(value: Long): String {
        return "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 8) and 0xFF}.${value and 0xFF}"
    }

    // -------------------- 并发测速优选（限制并发，避免资源耗尽） --------------------

    private suspend fun speedTest(ipList: List<String>, port: Int): String? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(OVERALL_TIMEOUT_MS) {
                val semaphore = Semaphore(MAX_CONCURRENT_TESTS)
                val deferredResults = ipList.map { ip ->
                    async {
                        semaphore.withPermit {
                            val latency = measureTcpConnectLatency(ip, port)
                            ip to latency
                        }
                    }
                }
                deferredResults.awaitAll()
                    .filter { it.second >= 0 }
                    .minByOrNull { it.second }
                    ?.first
            }
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
}