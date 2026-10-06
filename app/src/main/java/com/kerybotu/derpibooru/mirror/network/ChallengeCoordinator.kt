package com.kerybotu.derpibooru.mirror.network

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean

/** Ensures concurrent API failures share one user-facing challenge window. */
object ChallengeCoordinator {
    private val inProgress = AtomicBoolean(false)
    @Volatile private var pending: CompletableDeferred<Boolean>? = null

    suspend fun awaitResolved(
        context: Context,
        requestUrl: String,
        type: ChallengePageType
    ): Boolean {
        if (inProgress.compareAndSet(false, true)) {
            val result = CompletableDeferred<Boolean>()
            pending = result
            val targetActivity = when (type) {
                ChallengePageType.DERPI_FORM -> ChallengeActivity::class.java
                ChallengePageType.CLOUDFLARE_TURNSTILE -> CloudflareChallengeActivity::class.java
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
            }
        }
        return pending?.await() ?: false
    }

    fun notifyResolved(success: Boolean) {
        pending?.complete(success)
    }
}
