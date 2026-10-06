package com.kerybotu.derpibooru.mirror.network

import android.content.Context
import android.content.Intent
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Ensures concurrent API failures share one user-facing challenge window. */
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
        return sessions.awaitResolved(scope(requestUrl.toHttpUrl()), requestVersion) { sessionId ->
            val targetActivity = when (type) {
                ChallengePageType.DERPI_FORM -> ChallengeActivity::class.java
                ChallengePageType.CLOUDFLARE_TURNSTILE -> CloudflareChallengeActivity::class.java
            }
            val intent = Intent(context, targetActivity).apply {
                putExtra(ChallengeActivity.EXTRA_URL, requestUrl)
                putExtra(EXTRA_SESSION_ID, sessionId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun notifyResolved(sessionId: Long, success: Boolean) {
        sessions.complete(sessionId, success)
    }

    private fun scope(url: HttpUrl): String = "${url.scheme}://${url.host}:${url.port}"
}
