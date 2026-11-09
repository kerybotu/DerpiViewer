package com.kerybotu.derpibooru.mirror.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun detectsAnubisChallengeObjectInsideScriptWithRuntime() {
        val html = """
            <title>Protected by Anubis</title>
            <script>window.anubis_challenge = {rules: {difficulty: 2}}</script>
            <script src="/.within.website/x/cmd/anubis/static/js/main.mjs"></script>
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
    fun detectsHttp200AnubisWithSingleQuotedAndReformattedAttributes() {
        val html = """
            <html><head><title>Making sure you're not a bot!</title>
            <script
                type='application/json'
                id = 'anubis_challenge'>{"rules":{"difficulty":2}}</script>
            <script type='module' src='/.within.website/x/cmd/anubis/static/js/main.mjs'></script>
            </head><body>Protected by Anubis</body></html>
        """.trimIndent()
        assertEquals(ChallengePageType.ANUBIS, ChallengePageDetector.detect("text/html", html))
    }

    @Test
    fun doesNotClassifyOhNoesAccessDeniedAsAnubis() {
        val html = """
            <html><head><title>Derpibooru - Oh noes!</title></head>
            <body><h1>Oh noes!</h1><p>You have been blocked from accessing this site.</p></body></html>
        """.trimIndent()
        assertNull(ChallengePageDetector.detect("text/html", html))
        assertEquals(
            ChallengeResponseCategory.ACCESS_DENIED,
            ChallengePageDetector.assess(200, "https://derpibooru.org/filters", "text/html", html).category
        )
    }

    @Test
    fun reportsOnlySafeOhNoesPageStructureForRuntimeDiagnosis() {
        val html = """
            <html><head><title>Derpibooru - Oh noes!</title>
            <script id="anubis_challenge" type="application/json">{"token":"secret"}</script>
            <script src="/.within.website/x/cmd/anubis/main.mjs?token=secret"></script>
            <meta name="anubis_version" content="secret">
            </head><body><main id="anubis-main" class="challenge blocked">Denied</main></body></html>
        """.trimIndent()
        val evidence = ChallengePageDetector.inspect(html, "https://derpibooru.org/filters")
        assertEquals("Derpibooru - Oh noes!", evidence.title)
        assertTrue(evidence.scriptPaths.any { it.contains("/x/cmd/anubis/main.mjs") })
        assertTrue(evidence.elementIds.containsAll(listOf("anubis_challenge", "anubis-main")))
        assertTrue(evidence.metaNames.contains("anubis_version"))
        assertTrue(evidence.challengeScriptMarkers.contains("anubis_challenge"))
        assertFalse(evidence.toString().contains("secret"))
    }

    @Test
    fun detectsDerpibooruCustomizedAnubisRejectPageWithoutTreatingItAsSuccess() {
        val html = """
            <html><head><title>Derpibooru - Oh noes!</title>
            <link rel="stylesheet" href="/.within.website/x/cmd/anubis/static/css/derpibooru.css"></head>
            <body><div class="message centered-div"><img src="/.within.website/x/cmd/anubis/static/img/reject.webp">You have been blocked</div></body></html>
        """.trimIndent()
        val assessment = ChallengePageDetector.assess(
            200,
            "https://derpibooru.org/filters?fq=creator%3A495pygly",
            "text/html; charset=utf-8",
            html
        )
        assertEquals(ChallengePageType.ANUBIS, assessment.challengeType)
        assertEquals(ChallengeResponseCategory.ANUBIS_REJECT_PAGE, assessment.category)
    }

    @Test
    fun classifiesHttp200ChallengeByHtmlFeaturesAndNormalPageSeparately() {
        val challenge = """
            <title>Making sure you're not a bot!</title>
            <script id="anubis_challenge" type="application/json">{}</script>
            <script src="/.within.website/x/cmd/anubis/static/js/main.mjs"></script>
        """.trimIndent()
        val normal = "<html><head><title>Filters</title></head><body>Filters list</body></html>"
        assertEquals(
            ChallengeResponseCategory.ANUBIS_CHALLENGE,
            ChallengePageDetector.assess(200, "https://derpibooru.org/filters", "text/html", challenge).category
        )
        assertEquals(
            ChallengeResponseCategory.NORMAL_HTML,
            ChallengePageDetector.assess(200, "https://derpibooru.org/filters", "text/html", normal).category
        )
    }

    @Test
    fun classifiesLoginAndServerErrorPagesWithoutOpeningChallengeFlow() {
        val login = "<form action='/login'><input name='password'></form>"
        val serverError = "<html><head><title>Internal Server Error</title></head><body>Try again later</body></html>"
        assertEquals(
            ChallengeResponseCategory.LOGIN_PAGE,
            ChallengePageDetector.assess(200, "https://derpibooru.org/login", "text/html", login).category
        )
        assertEquals(
            ChallengeResponseCategory.SERVER_ERROR,
            ChallengePageDetector.assess(503, "https://derpibooru.org/filters", "text/html", serverError).category
        )
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
