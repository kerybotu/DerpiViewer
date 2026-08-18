package com.kerybotu.derpibooru.mirror.network

import kotlinx.coroutines.delay

/** Shared no-request window required after Derpibooru challenge or ban responses. */
object ChallengeBackoff {
    @Volatile private var blockedUntil = 0L

    fun blockFor(durationMs: Long) {
        blockedUntil = maxOf(blockedUntil, System.currentTimeMillis() + durationMs)
    }

    suspend fun awaitReady() {
        val wait = blockedUntil - System.currentTimeMillis()
        if (wait > 0) delay(wait)
    }

    fun isBlocked(): Boolean = System.currentTimeMillis() < blockedUntil
}
