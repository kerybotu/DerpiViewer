package com.kerybotu.derpibooru.mirror.network

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Coordinates one user-facing challenge window for all concurrent requests. */
object ChallengeCoordinator {
    private const val TAG = "AnubisCoordinator"
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
        val source = "${parsed.host}${parsed.encodedPath}"
        Log.i(TAG, "verification_wait source=$source type=$type requestVersion=$requestVersion")
        val resolved = sessions.awaitResolvedWithObserver(
            scope = scope(parsed),
            requestVersion = requestVersion,
            onSession = { sessionId, owner ->
                Log.i(TAG, "verification_session source=$source session=$sessionId owner=$owner")
            }
        ) { sessionId ->
            val target = when (type) {
                ChallengePageType.DERPI_FORM -> ChallengeActivity::class.java
                ChallengePageType.CLOUDFLARE_TURNSTILE -> CloudflareChallengeActivity::class.java
                ChallengePageType.ANUBIS -> AnubisChallengeActivity::class.java
            }
            withContext(Dispatchers.Main.immediate) {
                Log.i(TAG, "webview_launch source=$source type=$type session=$sessionId")
                context.startActivity(Intent(context, target).apply {
                    putExtra(ChallengeActivity.EXTRA_URL, requestUrl)
                    putExtra(EXTRA_SESSION_ID, sessionId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }
        Log.i(
            TAG,
            "verification_complete source=$source type=$type success=$resolved cookieNames=${SharedCookieJar.cookieNames(requestUrl).joinToString(",").ifBlank { "none" }} anubisCookie=${SharedCookieJar.hasAnubisCookie(requestUrl)} clearanceCookie=${SharedCookieJar.hasClearanceCookie(requestUrl)}"
        )
        return resolved
    }

    fun notifyResolved(sessionId: Long, success: Boolean) {
        Log.i(TAG, "verification_result session=$sessionId success=$success")
        sessions.complete(sessionId, success)
    }

    private fun scope(url: HttpUrl): String = "${url.scheme}://${url.host}:${url.port}"
}
