package com.kerybotu.derpibooru.mirror.network

import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/** Detects Derpibooru's HTML challenge and waits for a real user interaction. */
class ChallengeInterceptor(private val appContext: Context) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        runBlocking { ChallengeBackoff.awaitReady() }
        val request = chain.request()
        var response = chain.proceed(request)
        if (response.code == 501) {
            // The documented challenge window requires a complete 5-second quiet period.
            ChallengeBackoff.blockFor(5_000L)
            runBlocking { ChallengeBackoff.awaitReady() }
        } else if (response.code == 500 && response.peekBody(1).bytes().isEmpty()) {
            // Do not let any queued image/API request reset the remote 15-minute ban timer.
            ChallengeBackoff.blockFor(15 * 60 * 1_000L)
        }
        var retried = false
        while (isChallengePage(response) && !retried) {
            response.close()
            val challengeUrl = request.url.newBuilder()
                .removeAllQueryParameters("key")
                .build()
                .toString()
            val resolved = runBlocking {
                ChallengeCoordinator.awaitResolved(appContext, challengeUrl)
            }
            if (!resolved) {
                retried = true
                return chain.proceed(request)
            }
            response = chain.proceed(request)
            retried = true
        }
        return response
    }

    private fun isChallengePage(response: Response): Boolean {
        val contentType = response.header("Content-Type").orEmpty()
        if (!contentType.contains("text/html", ignoreCase = true)) return false
        // Only the actual Derpibooru challenge form may open the user-facing verifier.
        // Do not treat generic HTML errors, challenge-like text, or status codes as a click challenge.
        val snippet = runCatching { response.peekBody(64L * 1024L).string() }.getOrDefault("")
        return snippet.contains(CHALLENGE_FORM_SIGNATURE)
    }

    private companion object {
        const val CHALLENGE_FORM_SIGNATURE = "<form class=\"derpi-challenge\" action=\"/challenge\" method=\"post\">"
    }
}
