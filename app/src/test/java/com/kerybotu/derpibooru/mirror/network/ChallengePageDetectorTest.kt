package com.kerybotu.derpibooru.mirror.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChallengePageDetectorTest {
    @Test
    fun detectsLegacyDerpibooruForm() {
        val html = "<form class=\"derpi-challenge\" action=\"/challenge\" method=\"post\"></form>"
        assertEquals(ChallengePageType.DERPI_FORM, ChallengePageDetector.detect("text/html", html))
    }

    @Test
    fun detectsCloudflareTurnstilePage() {
        val html = """
            <script src="/cdn-cgi/challenge-platform/h/b/orchestrate/chl_page/v1"></script>
            <script src="https://challenges.cloudflare.com/turnstile/v0/api.js"></script>
            <input type="hidden" name="cf-turnstile-response">
            <p>Please complete the CAPTCHA to verify you are not a robot.</p>
        """.trimIndent()
        assertEquals(ChallengePageType.CLOUDFLARE_TURNSTILE, ChallengePageDetector.detect("text/html", html))
    }

    @Test
    fun ignoresPartialCloudflareMarkers() {
        val html = "<script src=\"/cdn-cgi/challenge-platform/runtime.js\"></script>"
        assertNull(ChallengePageDetector.detect("text/html", html))
    }

    @Test
    fun ignoresNonHtmlResponses() {
        assertNull(
            ChallengePageDetector.detect(
                "application/json",
                "window._cf_chl_opt; cf-turnstile-response; captcha robot"
            )
        )
    }
}
