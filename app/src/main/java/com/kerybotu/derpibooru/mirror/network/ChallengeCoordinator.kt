package com.kerybotu.derpibooru.mirror.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean

/** Ensures concurrent API failures share one user-facing challenge window. */
object ChallengeCoordinator {
    private const val SUCCESS_GRACE_MS = 20_000L
    private const val FAILURE_COOLDOWN_MS = 8_000L
    private val inProgress = AtomicBoolean(false)
    @Volatile private var pending: CompletableDeferred<Boolean>? = null
    @Volatile private var lastSuccessAt = 0L
    @Volatile private var lastSuccessHost: String? = null
    @Volatile private var lastSuccessType: ChallengePageType? = null
    @Volatile private var lastFailureAt = 0L
    @Volatile private var lastFailureHost: String? = null
    @Volatile private var lastFailureType: ChallengePageType? = null

    suspend fun awaitResolved(
        context: Context,
        requestUrl: String,
        type: ChallengePageType
    ): Boolean {
        val host = Uri.parse(requestUrl).host
        val now = System.currentTimeMillis()
        // Several API calls can be retried immediately after the same WebView
        // challenge. Reopening the activity before CookieManager has propagated
        // its clearance cookie creates a visible verification loop.
        if (lastSuccessAt > 0L && now - lastSuccessAt < SUCCESS_GRACE_MS &&
            host.equals(lastSuccessHost, ignoreCase = true) && lastSuccessType == type
        ) {
            return true
        }
        // If a challenge page returned JSON but never produced a replayable cookie,
        // do not reopen a new Activity for every queued request. Allow the user a
        // short cooldown before trying the challenge again.
        if (lastFailureAt > 0L && now - lastFailureAt < FAILURE_COOLDOWN_MS &&
            host.equals(lastFailureHost, ignoreCase = true) && lastFailureType == type
        ) {
            return false
        }
        if (inProgress.compareAndSet(false, true)) {
            val result = CompletableDeferred<Boolean>()
            pending = result
            activeHost = host
            activeType = type
            val targetActivity = when (type) {
                ChallengePageType.DERPI_FORM -> ChallengeActivity::class.java
                ChallengePageType.CLOUDFLARE_TURNSTILE -> CloudflareChallengeActivity::class.java
                ChallengePageType.ANUBIS -> AnubisChallengeActivity::class.java
            }
            val intent = Intent(context, targetActivity).apply {
                putExtra(ChallengeActivity.EXTRA_URL, requestUrl)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return try {
                result.await()
            } finally {
                pending = null
                inProgress.set(false)
                activeHost = null
                activeType = null
            }
        }
        return pending?.await() ?: false
    }

    fun notifyResolved(success: Boolean) {
        if (success) {
            lastSuccessAt = System.currentTimeMillis()
            // The request URL/type are only needed for the short retry grace
            // period; callers waiting on the deferred still receive the result.
            // They are populated by the activity launch below through the fields.
            lastSuccessHost = activeHost
            lastSuccessType = activeType
            lastFailureAt = 0L
            lastFailureHost = null
            lastFailureType = null
        } else {
            lastFailureAt = System.currentTimeMillis()
            lastFailureHost = activeHost
            lastFailureType = activeType
        }
        pending?.complete(success)
    }

    @Volatile private var activeHost: String? = null
    @Volatile private var activeType: ChallengePageType? = null
}
