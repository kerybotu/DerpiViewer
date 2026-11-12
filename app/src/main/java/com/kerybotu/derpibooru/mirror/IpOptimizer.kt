package com.kerybotu.derpibooru.mirror

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * 测速结果按独立设置持久化，默认六小时内直接复用。
 */
object IpOptimizer {

    private const val PREFS_NAME = "ip_optimizer_prefs"

    private const val KEY_IP_LIST = "cached_ip_list"
    private const val KEY_FETCH_TIME = "cached_fetch_time"
    private const val CACHE_TTL_MS = 60 * 60 * 1000L // IP 列表缓存 1 小时

    private const val KEY_LAST_BEST_IP = "last_best_ip"
    private const val KEY_LAST_BEST_TIME = "last_best_time"
    private val benchmarkMutex = Mutex()
    private var completedAt = 0L
    private var completedConfig = ""
    private var completedResults: Map<String, OptimizeResult> = emptyMap()

    private const val WORKER_URL = "https://cloudflarecdnip.495648.xyz/"

    // -------------------- Cloudflare 官方 IP 段数据源 --------------------
    private const val CF_IPS_API_URL = "https://api.cloudflare.com/client/v4/ips"
    private const val CF_IPS_V6_URL = "https://www.cloudflare.com/ips-v6/"
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
    private const val DOMAIN_TCP_CANDIDATES = 8

    data class OptimizeResult(val ip: String, val didOptimize: Boolean, val verified: Boolean = true, val latencyMs: Long? = null)

    /**
     * Runs the expensive candidate speed ranking once and reuses it for all
     * Cloudflare-backed hostnames. Each hostname still performs its own small
     * TCPing pass over the ranked candidates before a route is selected.
     */
    suspend fun getBestIpsShared(
        context: Context,
        domainKeys: List<String>,
        forceRefresh: Boolean = false,
        onProgress: ((domain: String, tested: Int, total: Int) -> Unit)? = null
    ): Map<String, OptimizeResult> {
        val requestedAt = System.currentTimeMillis()
        return benchmarkMutex.withLock {
            withContext(Dispatchers.IO) {
                val domains = domainKeys.map { it.trim().lowercase() }.distinct()
                if (completedAt >= requestedAt && completedConfig == configurationKey(context) &&
                    domains.all { it in completedResults }) {
                    Log.i("NodeRouting", "event=benchmark_joined")
                    completedResults.filterKeys { it in domains }
                } else {
                    val config = configurationKey(context)
                    val results = getBestIpsLocked(context, domains, forceRefresh, requestedAt, onProgress)
                    completedConfig = config
                    completedResults = results
                    completedAt = System.currentTimeMillis()
                    results
                }
            }
        }
    }

    private suspend fun getBestIpsLocked(
        context: Context,
        domainKeys: List<String>,
        forceRefresh: Boolean,
        requestedAt: Long,
        onProgress: ((String, Int, Int) -> Unit)?
    ): Map<String, OptimizeResult> {
        val domains = domainKeys.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        if (domains.isEmpty()) return emptyMap()
        val results = LinkedHashMap<String, OptimizeResult>()
        val missing = domains.filter { domain ->
            // Concurrent forced callers share the result completed while they waited.
            val cached = readCachedResult(context, domain, if (forceRefresh) requestedAt else null)
            if (cached != null) {
                results[domain] = cached
                false
            } else true
        }
        if (missing.isEmpty()) return results

        val config = configurationKey(context)
        Log.i("NodeRouting", "event=benchmark_start domains=${missing.joinToString(",")} forced=$forceRefresh")
        val candidates = buildCandidatePool(context, forceRefresh)
        val ranked = speedTestRanked(candidates, 443) { tested, total ->
            // The shared speed stage is deliberately reported once. Callers
            // can show this as a single global progress bar.
            onProgress?.invoke("shared", tested, total)
        }
        val usable = ranked.ifEmpty { candidates }
        missing.forEach { domain ->
            val domainRanked = tcpRankForDomain(domain, usable.take(DOMAIN_TCP_CANDIDATES), onProgress)
            val best = domainRanked.firstOrNull()?.first ?: usable.firstOrNull() ?: FALLBACK_IP_LIST.first()
            val verified = domainRanked.isNotEmpty()
            val latency = domainRanked.firstOrNull()?.second
            val result = OptimizeResult(best, didOptimize = true, verified = verified, latencyMs = latency)
            if (verified && config == configurationKey(context)) persistResult(context, domain, best, latency!!)
            Log.i("NodeRouting", "event=node_selected domain=$domain ip=$best verified=$verified latency_ms=$latency")
            results[domain] = result
        }
        Log.i("NodeRouting", "event=benchmark_end verified=${results.values.all { it.verified }}")
        return results
    }

    suspend fun getBestIpSmart(
        context: Context,
        forceRefresh: Boolean = false,
        domainKey: String = AppSettings.getTargetDomain(context),
        onProgress: ((tested: Int, total: Int) -> Unit)? = null
    ): OptimizeResult {
        return getBestIpsShared(context, listOf(domainKey), forceRefresh) { _, tested, total ->
            onProgress?.invoke(tested, total)
        }.getValue(domainKey.trim().lowercase())
    }

    private fun configurationKey(context: Context): String =
        "v2|$WORKER_URL|$CF_IPS_API_URL|${FALLBACK_IP_LIST.hashCode()}|" +
            "${AppSettings.getTargetDomain(context)}|${AppSettings.getManualIp(context)}|${AppSettings.isCdnDirect(context)}"

    private fun readCachedResult(context: Context, domainKey: String, newerThan: Long? = null): OptimizeResult? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val suffix = domainKey.lowercase().replace(Regex("[^a-z0-9]"), "_")
        val ip = prefs.getString("${KEY_LAST_BEST_IP}_$suffix", null)
        val time = prefs.getLong("${KEY_LAST_BEST_TIME}_$suffix", 0L)
        val interval = AppSettings.getNodeBenchmarkIntervalHours(context) * 3_600_000L
        val valid = !ip.isNullOrBlank() && isValidIp(ip) &&
            prefs.getString("best_config_$suffix", null) == configurationKey(context) &&
            com.kerybotu.derpibooru.mirror.network.NodeRoutingPolicy.isFresh(time, System.currentTimeMillis(), interval) &&
            (newerThan == null || time >= newerThan)
        Log.i("NodeRouting", "event=${if (valid) "history_reused" else "history_missing_or_expired"} domain=$domainKey interval_ms=$interval next_at=${time + interval}")
        return if (valid) {
            requireNotNull(ip)
            OptimizeResult(ip, didOptimize = false, latencyMs = prefs.getLong("best_latency_$suffix", -1L).takeIf { it >= 0 })
        } else null
    }

    private fun persistResult(context: Context, domainKey: String, ip: String, latencyMs: Long) {
        val suffix = domainKey.lowercase().replace(Regex("[^a-z0-9]"), "_")
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("${KEY_LAST_BEST_IP}_$suffix", ip)
            .putLong("best_latency_$suffix", latencyMs)
            .putLong("${KEY_LAST_BEST_TIME}_$suffix", System.currentTimeMillis())
            .putString("best_config_$suffix", configurationKey(context))
            .commit()
        if (!saved) Log.e("NodeRouting", "event=node_persistence_failed domain=$domainKey")
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
        val value = s.trim().removePrefix("[").removeSuffix("]")
        if (value.isEmpty() || value.any { it.isWhitespace() }) return false
        if (!value.contains(':') && !value.matches(Regex("[0-9.]+"))) return false
        return runCatching {
            java.net.InetAddress.getByName(value)
            value.contains('.') || value.contains(':')
        }.getOrDefault(false)
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
                    val ranges = parseIpv4CidrsFromResponse(text).toMutableList()
                    ranges += fetchCloudflareIpv6Ranges()
                    ranges
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

    private fun fetchCloudflareIpv6Ranges(): List<String> = runCatching {
        val conn = URL(CF_IPS_V6_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 4000; conn.readTimeout = 4000
        if (conn.responseCode != 200) return@runCatching emptyList()
        conn.inputStream.bufferedReader().use { reader ->
            reader.readLines().map { it.trim() }.filter { it.contains(':') && it.contains('/') }
        }.also { conn.disconnect() }
    }.getOrDefault(emptyList())

    private fun parseIpv4CidrsFromResponse(json: String): List<String> {
        return try {
            val root = JSONObject(json)
            if (!root.optBoolean("success", false)) return emptyList()
            val result = root.optJSONObject("result") ?: return emptyList()
            val cidrs = mutableListOf<String>()
            result.optJSONArray("ipv4_cidrs")?.let { array -> for (i in 0 until array.length()) cidrs += array.getString(i) }
            result.optJSONArray("ipv6_cidrs")?.let { array -> for (i in 0 until array.length()) cidrs += array.getString(i) }
            cidrs
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
            // IPv6 ranges are accepted and probed through the official ips-v6 source.
            // Keep the network address as a conservative candidate; IPv6 anycast ranges
            // are not safely enumerable with the IPv4 Long sampler below.
            if (parts[0].contains(':')) return listOf(parts[0])
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

    private suspend fun speedTest(
        ipList: List<String>,
        port: Int,
        onProgress: ((tested: Int, total: Int) -> Unit)? = null
    ): String? = speedTestRanked(ipList, port, onProgress).firstOrNull()

    /** One shared raw TCP speed pass, sorted for reuse by all hostnames. */
    private suspend fun speedTestRanked(
        ipList: List<String>,
        port: Int,
        onProgress: ((tested: Int, total: Int) -> Unit)? = null
    ): List<String> = withContext(Dispatchers.IO) {
        withTimeoutOrNull(OVERALL_TIMEOUT_MS) {
            val semaphore = Semaphore(MAX_CONCURRENT_TESTS)
            val completed = java.util.concurrent.atomic.AtomicInteger(0)
            val deferredResults = ipList.map { ip ->
                async {
                    semaphore.withPermit {
                        val latency = measureTcpConnectLatency(ip, port)
                        onProgress?.invoke(completed.incrementAndGet(), ipList.size)
                        ip to latency
                    }
                }
            }
            deferredResults.awaitAll()
                .filter { it.second >= 0 }
                .sortedBy { it.second }
                .map { it.first }
        } ?: emptyList()
    }

    /**
     * A short per-host TCPing pass. It intentionally runs only on the best
     * shared-speed candidates, so host-specific routing remains independent
     * without repeating the full candidate benchmark three times.
     */
    private suspend fun tcpRankForDomain(
        domain: String,
        candidates: List<String>,
        onProgress: ((domain: String, tested: Int, total: Int) -> Unit)?
    ): List<Pair<String, Long>> = withContext(Dispatchers.IO) {
        val semaphore = Semaphore(minOf(MAX_CONCURRENT_TESTS, DOMAIN_TCP_CANDIDATES))
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        candidates.map { ip ->
            async {
                semaphore.withPermit {
                    val latency = measureTcpConnectLatency(ip, 443)
                    onProgress?.invoke(domain, completed.incrementAndGet(), candidates.size)
                    ip to latency
                }
            }
        }.awaitAll().filter { it.second >= 0 }.sortedBy { it.second }
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
