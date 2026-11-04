package com.kerybotu.derpibooru.mirror.network

import android.content.Context
import android.util.Log
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
        val source = "${request.url.host}${request.url.encodedPath}"
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
        val assessment = assessResponse(response)
        if (response.header("Content-Type").orEmpty().contains("text/html", ignoreCase = true)) {
            Log.d("AnubisCoordinator", "response_inspected source=$source status=${response.code} final=${response.request.url.host}${response.request.url.encodedPath} contentType=${response.header("Content-Type").orEmpty()} category=${assessment.category} challenge=${assessment.challengeType ?: "none"} title=${assessment.title}")
        }
        val challengeType = assessment.challengeType
        if (challengeType != null) {
            if (request.method != "GET" && request.method != "HEAD") {
                Log.w("AnubisCoordinator", "challenge_not_replayed source=$source method=${request.method} type=$challengeType reason=non_idempotent")
                return response
            }
            Log.i("AnubisCoordinator", "challenge_detected source=$source type=$challengeType status=${response.code} category=${assessment.category} cookieNames=${SharedCookieJar.cookieNames(request.url.toString()).joinToString(",").ifBlank { "none" }}")
            response.close()
            val challengeUrl = request.url.newBuilder()
                .removeAllQueryParameters("key")
                .build()
                .toString()
            val resolved = runBlocking {
                ChallengeCoordinator.awaitResolved(appContext, challengeUrl, challengeType, requestVersion)
            }
            Log.i("AnubisCoordinator", "challenge_resolved source=$source type=$challengeType success=$resolved cookieNames=${SharedCookieJar.cookieNames(request.url.toString()).joinToString(",").ifBlank { "none" }}")
            if (!resolved) throw ChallengeCancelledException()
            var retriedResponse = chain.proceed(request)
            // CookieManager.flush() is asynchronous on some WebView providers.
            // Give the clearance cookie a few bounded propagation chances, without
            // opening another Activity or turning a transient race into a loop.
            var propagationAttempt = 0
            while (propagationAttempt < 3 && assessResponse(retriedResponse).challengeType != null) {
                Log.w("AnubisCoordinator", "challenge_retry_still_blocked source=$source attempt=${propagationAttempt + 1} status=${retriedResponse.code}")
                retriedResponse.close()
                Thread.sleep(250L)
                retriedResponse = chain.proceed(request)
                propagationAttempt++
            }
            val retryAssessment = assessResponse(retriedResponse)
            Log.i("AnubisCoordinator", "request_replayed source=$source status=${retriedResponse.code} final=${retriedResponse.request.url.host}${retriedResponse.request.url.encodedPath} category=${retryAssessment.category} challenge=${retryAssessment.challengeType ?: "none"} cookieNames=${SharedCookieJar.cookieNames(retriedResponse.request.url.toString()).joinToString(",").ifBlank { "none" }}")
            // BridgeInterceptor reads the shared cookie store again for this retry.
            return retriedResponse
        }
        return response
    }

    private fun assessResponse(response: Response): ChallengeResponseAssessment {
        val contentType = response.header("Content-Type").orEmpty()
        if (!contentType.contains("text/html", ignoreCase = true)) {
            return ChallengeResponseAssessment(ChallengeResponseCategory.NON_HTML)
        }
        // Keep the body bounded: challenge markers are emitted in the document head and
        // there is no reason to retain a complete error page in memory.
        val snippet = runCatching { response.peekBody(64L * 1024L).string() }.getOrDefault("")
        return ChallengePageDetector.assess(
            statusCode = response.code,
            finalUrl = response.request.url.toString(),
            contentType = contentType,
            html = snippet
        )
    }
}
