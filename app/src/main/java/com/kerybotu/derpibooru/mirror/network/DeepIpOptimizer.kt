package com.kerybotu.derpibooru.mirror.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

data class DeepOptimizeCandidate(val ip: String, var avgLatencyMs: Double = -1.0,
                                 var connectivityPassed: Boolean = false,
                                 var downloadSpeedKBps: Double = -1.0)
sealed class DeepOptimizeLog {
    data class Info(val message: String) : DeepOptimizeLog()
    data class Success(val message: String) : DeepOptimizeLog()
    data class Warn(val message: String) : DeepOptimizeLog()
    data class Progress(val current: Int, val total: Int, val stage: String) : DeepOptimizeLog()
}
data class DeepOptimizeResult(val bestIp: String, val avgLatencyMs: Double, val downloadSpeedKBps: Double)
private data class SpeedTarget(val domain: String, val path: String)

object DeepIpOptimizer {
    private const val V4 = "https://www.cloudflare.com/ips-v4/"
    private const val V6 = "https://www.cloudflare.com/ips-v6/"
    private const val MAIN = "derpibooru.org"
    private const val CDN = "derpicdn.net"
    private const val SPEED_TEST_PATH = "/img/view/2012/1/2/4.png"
    private const val ROUNDS = 4
    private const val SAMPLE_TOTAL = 100
    private const val TCP_CONCURRENCY = 10
    private const val TOP = 20
    private const val CONCURRENCY = 5
    private const val SPEED_CONCURRENCY = 3
    private const val SPEED_ROUNDS = 3
    private val fallback = listOf("172.67.102.229", "104.24.169.89", "104.24.64.97", "104.19.229.139")

    suspend fun run(onLog: (DeepOptimizeLog) -> Unit): DeepOptimizeResult? = withContext(Dispatchers.IO) {
        onLog(DeepOptimizeLog.Info("========== 深度 IP 优选开始 =========="))
        val speedTarget = SpeedTarget(CDN, SPEED_TEST_PATH)
        onLog(DeepOptimizeLog.Info("使用固定 803 KB CDN 图片样本，测速正文范围最多 512 KB"))
        val ranges = fetch(V4) + fetch(V6)
        val ips = if (ranges.isEmpty()) {
            onLog(DeepOptimizeLog.Warn("Cloudflare 网段 API 不可达，使用内置 IPv4 节点")); fallback
        } else {
            onLog(DeepOptimizeLog.Success("获取 Cloudflare 网段 ${ranges.size} 个（IPv4/IPv6）"))
            // 按网段均匀分配抽样额度，避免 100 个候选全部集中在单一大网段。
            val perRange = (SAMPLE_TOTAL + ranges.size - 1) / ranges.size
            ranges.flatMap { sample(it, perRange) }.distinct().shuffled().take(SAMPLE_TOTAL)
        }
        onLog(DeepOptimizeLog.Info("候选节点共 ${ips.size} 个，开始四次并发 TCPing（并发 $TCP_CONCURRENCY）..."))
        val tcpSemaphore = Semaphore(TCP_CONCURRENCY)
        val tcpResults = coroutineScope {
            ips.mapIndexed { index, ip ->
                async {
                    tcpSemaphore.withPermit {
                        val latency = tcping(ip)
                        onLog(DeepOptimizeLog.Progress(index + 1, ips.size, "TCPing"))
                        if (latency > 0) {
                            onLog(DeepOptimizeLog.Info("$ip 平均 ${"%.1f".format(latency)} ms"))
                            DeepOptimizeCandidate(ip, latency)
                        } else {
                            onLog(DeepOptimizeLog.Warn("$ip TCPing 失败")); null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val ranked = tcpResults.sortedBy { it.avgLatencyMs }.take(TOP)
        if (ranked.isEmpty()) return@withContext null

        onLog(DeepOptimizeLog.Info("对前 ${ranked.size} 个节点验证 Derpibooru 主站连通性（并发 $CONCURRENCY）"))
        val sem = Semaphore(CONCURRENCY)
        coroutineScope {
            ranked.map { c -> async {
                sem.withPermit {
                    delay(Random.nextLong(40, 160))
                    c.connectivityPassed = validate(c.ip, MAIN)
                    onLog(if (c.connectivityPassed) DeepOptimizeLog.Success("${c.ip} 主站连通性通过") else DeepOptimizeLog.Warn("${c.ip} 主站连通性失败"))
                }
            }}.awaitAll()
        }
        val passed = ranked.filter { it.connectivityPassed }
        if (passed.isEmpty()) return@withContext null
        onLog(DeepOptimizeLog.Info("主站验证通过 ${passed.size} 个，全部进入 CDN 下载测速（并发 $SPEED_CONCURRENCY）"))
        val speedSemaphore = Semaphore(SPEED_CONCURRENCY)
        val speedProgress = AtomicInteger()
        coroutineScope {
            passed.map { c -> async {
                speedSemaphore.withPermit {
                    c.downloadSpeedKBps = speed(c.ip, speedTarget)
                    val current = speedProgress.incrementAndGet()
                    onLog(DeepOptimizeLog.Progress(current, passed.size, "CDN 下载测速"))
                    onLog(if (c.downloadSpeedKBps > 0) DeepOptimizeLog.Info("${c.ip} 下载速度 ${formatSpeed(c.downloadSpeedKBps)}") else DeepOptimizeLog.Warn("${c.ip} CDN 图片样本测速无效"))
                }
            }}.awaitAll()
        }
        val best = passed.filter { it.downloadSpeedKBps > 0 }.maxByOrNull { it.downloadSpeedKBps }
            ?: passed.minByOrNull { it.avgLatencyMs } ?: return@withContext null
        onLog(DeepOptimizeLog.Success("========== 完成：${best.ip}，延迟 ${"%.1f".format(best.avgLatencyMs)} ms =========="))
        DeepOptimizeResult(best.ip, best.avgLatencyMs, best.downloadSpeedKBps)
    }

    private fun fetch(endpoint: String): List<String> = runCatching {
        val c = (URL(endpoint).openConnection() as HttpURLConnection).apply { connectTimeout = 6000; readTimeout = 6000 }
        try { if (c.responseCode != 200) emptyList() else c.inputStream.bufferedReader().readLines().map { it.trim() }.filter { '/' in it } }
        finally { c.disconnect() }
    }.getOrDefault(emptyList())

    private fun sample(cidr: String, count: Int): List<String> = runCatching {
        val p = cidr.split('/'); val base = InetAddress.getByName(p[0]).address; val bits = base.size * 8
        val prefix = p[1].toInt(); val value = BigInteger(1, base); val hostBits = bits - prefix
        val mask = BigInteger.ONE.shiftLeft(hostBits).subtract(BigInteger.ONE)
        (0 until count).map {
            val randomHost = if (hostBits <= 1) BigInteger.ZERO else BigInteger(hostBits, java.util.Random())
            val n = value.and(mask.not()).or(randomHost)
            val bytes = n.toByteArray().let { b -> if (b.size == base.size) b else if (b.size > base.size) b.copyOfRange(b.size - base.size, b.size) else ByteArray(base.size - b.size) + b }
            InetAddress.getByAddress(bytes).hostAddress
        }
    }.getOrDefault(emptyList())

    private suspend fun tcping(ip: String): Double = withContext(Dispatchers.IO) {
        val values = mutableListOf<Long>()
        repeat(ROUNDS) { runCatching { Socket().use { s -> val t = System.nanoTime(); s.connect(InetSocketAddress(ip, 443), 1800); values += (System.nanoTime() - t) / 1_000_000 } } }
        if (values.size >= 2) values.average() else -1.0
    }

    private fun validate(ip: String, domain: String): Boolean {
        val status = requestStatus(ip, domain, "/")
        return status in 200L..499L && status != 530L
    }

    private fun speed(ip: String, target: SpeedTarget): Double {
        // Excludes connection setup and samples a real image body, not a tiny favicon.
        return (0 until SPEED_ROUNDS).mapNotNull { downloadBodySpeed(ip, target) }.maxOrNull() ?: -1.0
    }

    private fun formatSpeed(kbps: Double): String =
        if (kbps >= 1024.0) "${"%.2f".format(kbps / 1024.0)} MB/s" else "${"%.1f".format(kbps)} KB/s"

    private fun requestStatus(ip: String, domain: String, path: String): Long {
        var raw: Socket? = null; var ssl: SSLSocket? = null
        return try {
            raw = Socket(); raw.connect(InetSocketAddress(ip, 443), 5000); raw.soTimeout = 8000
            ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, domain, 443, true) as SSLSocket
            ssl.startHandshake(); ssl.outputStream.write("GET $path HTTP/1.1\r\nHost: $domain\r\nConnection: close\r\nUser-Agent: DerpiViewer\r\n\r\n".toByteArray()); ssl.outputStream.flush()
            readStatusAndHeaders(ssl.inputStream).first.toLong()
        } catch (_: Exception) { -1L } finally { runCatching { ssl?.close() }; runCatching { raw?.close() } }
    }

    private fun downloadBodySpeed(ip: String, target: SpeedTarget): Double? {
        var raw: Socket? = null; var ssl: SSLSocket? = null
        return try {
            raw = Socket(); raw.connect(InetSocketAddress(ip, 443), 5000); raw.soTimeout = 8000
            ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, target.domain, 443, true) as SSLSocket
            ssl.startHandshake()
            ssl.outputStream.write("GET ${target.path} HTTP/1.1\r\nHost: ${target.domain}\r\nRange: bytes=0-524287\r\nAccept-Encoding: identity\r\nConnection: close\r\nUser-Agent: DerpiViewer\r\n\r\n".toByteArray()); ssl.outputStream.flush()
            val input = ssl.inputStream
            val (status, _) = readStatusAndHeaders(input)
            if (status !in 200..299) return null
            val started = System.nanoTime(); var bytes = 0L; val buffer = ByteArray(8192)
            while (bytes < 524288) { val read = input.read(buffer); if (read < 0) break; bytes += read }
            val seconds = (System.nanoTime() - started) / 1_000_000_000.0
            if (bytes >= 32 * 1024 && seconds >= 0.005) bytes / 1024.0 / seconds else null
        } catch (_: Exception) { null } finally { runCatching { ssl?.close() }; runCatching { raw?.close() } }
    }

    /** Reads exactly through CRLFCRLF from the raw stream without prefetching body bytes. */
    private fun readStatusAndHeaders(input: java.io.InputStream): Pair<Int, String> {
        val bytes = ArrayList<Byte>(1024); var match = 0
        while (bytes.size < 32 * 1024) {
            val b = input.read(); if (b < 0) break; bytes += b.toByte()
            match = when { match == 0 && b == '\r'.code -> 1; match == 1 && b == '\n'.code -> 2; match == 2 && b == '\r'.code -> 3; match == 3 && b == '\n'.code -> 4; b == '\r'.code -> 1; else -> 0 }
            if (match == 4) break
        }
        val header = bytes.toByteArray().toString(Charsets.ISO_8859_1)
        return (header.substringBefore("\r\n").split(" ").getOrNull(1)?.toIntOrNull() ?: 0) to header
    }
}
