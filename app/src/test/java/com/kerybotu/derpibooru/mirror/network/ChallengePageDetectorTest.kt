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
    fun detectsAnubisProofOfWorkPage() {
        val html = """
            <html><head><title>Making sure you're not a bot!</title>
            <script id="anubis-main" type="module" src="/.within.website/x/cmd/anubis/static/js/main.mjs"></script>
            <script>window.anubis_challenge = {}</script>
            <meta name="anubis_version" content="1.0.0"></head></html>
        """.trimIndent()
        assertEquals(ChallengePageType.ANUBIS, ChallengePageDetector.detect("text/html", html))
    }

    @Test
    fun detectsCurrentDerpibooruAnubisPageWithPrecursorScript() {
        val html = """
            <title>Derpibooru - Making sure you're not a bot!</title>
            <script id="anubis_version" type="application/json">"v1.27.0"</script>
            <script id="anubis_challenge" type="application/json">{"rules":{"difficulty":2}}</script>
            <script id="anubis-main" type="module" src="/.within.website/x/cmd/anubis/static/js/main.mjs"></script>
            <script src="/cdn-cgi/challenge-platform/scripts/precursor/main.js"></script>
        """.trimIndent()
        assertEquals(ChallengePageType.ANUBIS, ChallengePageDetector.detect("text/html; charset=UTF-8", html))
    }

    @Test
    fun ignoresLooseAnubisMarkers() {
        assertNull(
            ChallengePageDetector.detect(
                "text/html",
                "<p>Anubis challenge accepted</p><script src=\"/static/main.js\"></script>"
            )
        )
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
