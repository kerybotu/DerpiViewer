package com.kerybotu.derpibooru.mirror.network

import android.content.Context
import android.content.Intent
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Coordinates one user-facing challenge window for all concurrent requests. */
object ChallengeCoordinator {
    const val EXTRA_SESSION_ID = "challenge_session_id"
    private val sessions = ChallengeSessionGate()

    fun requestVersion(url: HttpUrl): Long = sessions.version(scope(url))

    suspend fun awaitResolved(
        context: Context,
        requestUrl: String,
        type: ChallengePageType,
        requestVersion: Long
    ): Boolean {
        val parsed = requestUrl.toHttpUrlOrNull() ?: return false
        return sessions.awaitResolved(scope(parsed), requestVersion) { sessionId ->
            val target = when (type) {
                ChallengePageType.DERPI_FORM -> ChallengeActivity::class.java
                ChallengePageType.CLOUDFLARE_TURNSTILE -> CloudflareChallengeActivity::class.java
                ChallengePageType.ANUBIS -> AnubisChallengeActivity::class.java
            }
            context.startActivity(Intent(context, target).apply {
                putExtra(ChallengeActivity.EXTRA_URL, requestUrl)
                putExtra(EXTRA_SESSION_ID, sessionId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    fun notifyResolved(sessionId: Long, success: Boolean) = sessions.complete(sessionId, success)

    private fun scope(url: HttpUrl): String = "${url.scheme}://${url.host}:${url.port}"
}
