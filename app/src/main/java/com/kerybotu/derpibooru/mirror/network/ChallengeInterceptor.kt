package com.kerybotu.derpibooru.mirror.network

import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

internal class ChallengeCancelledException : IOException("Verification was cancelled")

/** Detects Derpibooru's HTML challenge and waits for a real user interaction. */
class ChallengeInterceptor(private val appContext: Context) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        runBlocking { ChallengeBackoff.awaitReady() }
        val request = chain.request()
        val requestVersion = ChallengeCoordinator.requestVersion(request.url)
        val response = chain.proceed(request)
        if (response.code == 501) {
            // The documented challenge window requires a complete 5-second quiet period.
            ChallengeBackoff.blockFor(5_000L)
            runBlocking { ChallengeBackoff.awaitReady() }
        } else if (response.code == 500 && response.peekBody(1).bytes().isEmpty()) {
            // Do not let any queued image/API request reset the remote 15-minute ban timer.
            ChallengeBackoff.blockFor(15 * 60 * 1_000L)
        }
        val challengeType = detectChallengeType(response)
        if (challengeType != null) {
            response.close()
            val challengeUrl = request.url.newBuilder()
                .removeAllQueryParameters("key")
                .build()
                .toString()
            val resolved = runBlocking {
                ChallengeCoordinator.awaitResolved(appContext, challengeUrl, challengeType, requestVersion)
            }
            if (!resolved) throw ChallengeCancelledException()
            var retriedResponse = chain.proceed(request)
            // CookieManager.flush() is asynchronous on some WebView providers.
            // Give the clearance cookie a few bounded propagation chances, without
            // opening another Activity or turning a transient race into a loop.
            var propagationAttempt = 0
            while (propagationAttempt < 3 && detectChallengeType(retriedResponse) != null) {
                retriedResponse.close()
                Thread.sleep(250L)
                retriedResponse = chain.proceed(request)
                propagationAttempt++
            }
            // BridgeInterceptor reads the shared cookie store again for this retry.
            return retriedResponse
        }
        return response
    }

    private fun detectChallengeType(response: Response): ChallengePageType? {
        val contentType = response.header("Content-Type").orEmpty()
        if (!contentType.contains("text/html", ignoreCase = true)) return null
        // Keep the body bounded: challenge markers are emitted in the document head and
        // there is no reason to retain a complete error page in memory.
        val snippet = runCatching { response.peekBody(64L * 1024L).string() }.getOrDefault("")
        return ChallengePageDetector.detect(contentType, snippet)
    }
}
