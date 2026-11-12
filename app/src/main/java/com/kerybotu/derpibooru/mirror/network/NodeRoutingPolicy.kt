package com.kerybotu.derpibooru.mirror.network

/** Counts completed calls, not individual retries. Uses a monotonic clock for cooldowns. */
internal class NodeRoutingPolicy {
    private data class Quality(var failures: Int = 0, var slow: Int = 0)
    private val hosts = mutableMapOf<String, Quality>()
    private var lastAttempt: Long? = null
    private var running = false

    @Synchronized
    fun observe(host: String, failed: Boolean, slow: Boolean, now: Long): Boolean {
        val quality = hosts.getOrPut(host) { Quality() }
        quality.failures = if (failed) quality.failures + 1 else 0
        quality.slow = if (!failed && slow) quality.slow + 1 else 0
        if (quality.failures < 3 && quality.slow < 4) return false
        if (running || lastAttempt?.let { now - it < 300_000L } == true) return false
        running = true
        lastAttempt = now
        hosts.clear()
        return true
    }

    @Synchronized
    fun finished() { running = false }

    companion object {
        fun isFresh(measuredAt: Long, now: Long, interval: Long): Boolean =
            measuredAt > 0 && now >= measuredAt && now - measuredAt < interval
    }
}
