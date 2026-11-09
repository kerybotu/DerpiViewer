package com.kerybotu.derpibooru.mirror.network

import org.jsoup.Jsoup

enum class ChallengePageType {
    DERPI_FORM,
    CLOUDFLARE_TURNSTILE,
    ANUBIS
}

enum class ChallengeResponseCategory {
    NON_HTML,
    ANUBIS_CHALLENGE,
    ANUBIS_REJECT_PAGE,
    DERPI_CHALLENGE,
    CLOUDFLARE_CHALLENGE,
    ACCESS_DENIED,
    LOGIN_PAGE,
    RATE_LIMITED,
    SERVER_ERROR,
    NORMAL_HTML,
    UNKNOWN_HTML
}

data class ChallengeResponseAssessment(
    val category: ChallengeResponseCategory,
    val challengeType: ChallengePageType? = null,
    val title: String = ""
)

data class ChallengePageEvidence(
    val title: String,
    val htmlLength: Int,
    val scriptPaths: List<String>,
    val inlineScriptCount: Int,
    val scriptIds: List<String>,
    val elementIds: List<String>,
    val classNames: List<String>,
    val metaNames: List<String>,
    val formPaths: List<String>,
    val resourcePaths: List<String>,
    val frameCount: Int,
    val challengeScriptMarkers: List<String>,
    val markers: List<String>
)

/** Identifies supported verification pages without matching generic HTML errors. */
object ChallengePageDetector {
    private const val DERPI_FORM_SIGNATURE =
        "<form class=\"derpi-challenge\" action=\"/challenge\" method=\"post\">"

    fun detect(contentType: String, html: String): ChallengePageType? {
        return assess(statusCode = 200, finalUrl = "", contentType = contentType, html = html).challengeType
    }

    fun inspect(html: String, finalUrl: String): ChallengePageEvidence {
        val document = Jsoup.parse(html, finalUrl)
        val bodyText = document.body()?.text().orEmpty().lowercase()
        val scriptPaths = document.select("script[src]")
            .map { safeResourcePath(it.attr("src"), finalUrl) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(12)
        val inlineScripts = document.select("script:not([src])")
        val challengeScriptMarkers = listOf("anubis_challenge", "anubis_version", "/x/cmd/anubis/", "within.website")
            .filter { marker -> inlineScripts.any { it.outerHtml().contains(marker, ignoreCase = true) } }
        val resourcePaths = document.select("img[src], link[href], iframe[src]")
            .map { element ->
                val attribute = if (element.normalName() == "link") "href" else "src"
                safeResourcePath(element.attr(attribute), finalUrl)
            }
            .filter { it.isNotBlank() }
            .distinct()
            .take(16)
        val scriptIds = document.select("script[id]").eachAttr("id").map(::safeName).filter { it.isNotBlank() }.distinct().take(12)
        val elementIds = document.select("[id]").eachAttr("id").map(::safeName).filter { it.isNotBlank() }.distinct().take(20)
        val classNames = document.select("[class]").flatMap { it.classNames() }.map(::safeName).filter { it.isNotBlank() }.distinct().take(20)
        val metaNames = document.select("meta[name]").eachAttr("name").map(::safeName).filter { it.isNotBlank() }.distinct().take(12)
        val formPaths = document.select("form[action]").map { safeResourcePath(it.attr("action"), finalUrl) }
            .filter { it.isNotBlank() }.distinct().take(8)
        val markers = listOf("anubis", "challenge", "verification", "captcha", "robot", "blocked", "denied", "oh noes")
            .filter { bodyText.contains(it) || document.title().contains(it, ignoreCase = true) }
        return ChallengePageEvidence(
            title = document.title().replace(Regex("\\s+"), " ").trim().take(120),
            htmlLength = html.length,
            scriptPaths = scriptPaths,
            inlineScriptCount = inlineScripts.size,
            scriptIds = scriptIds,
            elementIds = elementIds,
            classNames = classNames,
            metaNames = metaNames,
            formPaths = formPaths,
            resourcePaths = resourcePaths,
            frameCount = document.select("iframe, frame").size,
            challengeScriptMarkers = challengeScriptMarkers,
            markers = markers
        )
    }

    fun assess(statusCode: Int, finalUrl: String, contentType: String, html: String): ChallengeResponseAssessment {
        if (!contentType.contains("text/html", ignoreCase = true)) {
            return ChallengeResponseAssessment(ChallengeResponseCategory.NON_HTML)
        }
        val document = Jsoup.parse(html, finalUrl)
        val title = document.title().replace(Regex("\\s+"), " ").trim().take(120)
        val bodyText = document.body()?.text().orEmpty().lowercase()
        val challengeType = detectHtml(document, html, finalUrl)
        if (challengeType != null) {
            val category = when (challengeType) {
                ChallengePageType.ANUBIS -> if (isAnubisRejectPage(document, finalUrl)) {
                    ChallengeResponseCategory.ANUBIS_REJECT_PAGE
                } else {
                    ChallengeResponseCategory.ANUBIS_CHALLENGE
                }
                ChallengePageType.DERPI_FORM -> ChallengeResponseCategory.DERPI_CHALLENGE
                ChallengePageType.CLOUDFLARE_TURNSTILE -> ChallengeResponseCategory.CLOUDFLARE_CHALLENGE
            }
            return ChallengeResponseAssessment(category, challengeType, title)
        }
        if (statusCode == 429 || bodyText.contains("too many requests") || bodyText.contains("rate limit")) {
            return ChallengeResponseAssessment(ChallengeResponseCategory.RATE_LIMITED, title = title)
        }
        if (document.select("form[action*=/login], input[name=password]").isNotEmpty() ||
            statusCode == 401 && title.contains("login", ignoreCase = true)
        ) {
            return ChallengeResponseAssessment(ChallengeResponseCategory.LOGIN_PAGE, title = title)
        }
        if (statusCode == 403 || bodyText.contains("access denied") ||
            bodyText.contains("you have been blocked") ||
            (title.contains("oh noes", ignoreCase = true) && (bodyText.contains("blocked") || bodyText.contains("denied")))
        ) {
            return ChallengeResponseAssessment(ChallengeResponseCategory.ACCESS_DENIED, title = title)
        }
        if (statusCode >= 500 || bodyText.contains("internal server error") || bodyText.contains("something went wrong")) {
            return ChallengeResponseAssessment(ChallengeResponseCategory.SERVER_ERROR, title = title)
        }
        val category = if (statusCode in 200..299) {
            ChallengeResponseCategory.NORMAL_HTML
        } else {
            ChallengeResponseCategory.UNKNOWN_HTML
        }
        return ChallengeResponseAssessment(category, title = title)
    }

    private fun detectHtml(document: org.jsoup.nodes.Document, html: String, finalUrl: String): ChallengePageType? {
        if (document.selectFirst("form.derpi-challenge[action=/challenge][method=post]") != null ||
            html.contains(DERPI_FORM_SIGNATURE, ignoreCase = true)
        ) {
            return ChallengePageType.DERPI_FORM
        }

        // Anubis pages expose a structured challenge object and load their
        // proof-of-work runtime from the private `within.website` path. Keep
        // both markers required so ordinary pages mentioning "challenge" or
        // a user tag named "anubis" are never opened as a verification page.
        val hasAnubisChallenge = document.selectFirst("#anubis_challenge, #anubis-main") != null ||
            document.select("script").any { it.data().contains("anubis_challenge", ignoreCase = true) }
        val hasAnubisRuntime =
            document.select("script[src]").any { it.attr("src").contains("/x/cmd/anubis/", true) } ||
                finalUrl.contains("/.within.website/", ignoreCase = true) ||
                document.selectFirst("script#anubis_version, meta[name=anubis_version]") != null
        val bodyText = document.body()?.text().orEmpty()
        val hasAnubisBranding =
            document.title().contains("making sure you're not a bot", ignoreCase = true) ||
                bodyText.contains("protected by", ignoreCase = true) &&
                bodyText.contains("anubis", ignoreCase = true)
        val hasAnubisRejectPage = isAnubisRejectPage(document, finalUrl)
        if ((hasAnubisChallenge && (hasAnubisRuntime || hasAnubisBranding)) || hasAnubisRejectPage) {
            return ChallengePageType.ANUBIS
        }

        val hasCloudflareRuntime =
            document.select("script[src]").any { it.attr("src").contains("/cdn-cgi/challenge-platform/", true) } &&
                (document.select("script[src], iframe[src]").any { it.attr("src").contains("challenges.cloudflare.com/turnstile", true) } ||
                    document.select("script").any { it.data().contains("window._cf_chl_opt", ignoreCase = true) })
        val hasVerificationUi =
            document.select("input[name=cf-turnstile-response], iframe[src*=challenges.cloudflare.com]").isNotEmpty() ||
                (document.select("body").text().contains("captcha", ignoreCase = true) &&
                    document.select("body").text().contains("robot", ignoreCase = true))

        return if (hasCloudflareRuntime && hasVerificationUi) {
            ChallengePageType.CLOUDFLARE_TURNSTILE
        } else {
            null
        }
    }

    private fun safeName(value: String): String = value.filter { it.isLetterOrDigit() || it in "_-:." }.take(80)

    private fun isAnubisRejectPage(document: org.jsoup.nodes.Document, finalUrl: String): Boolean {
        val resources = document.select("link[href], img[src]")
            .map { element -> if (element.normalName() == "link") element.attr("href") else element.attr("src") }
            .map { safeResourcePath(it, finalUrl).lowercase() }
        return resources.any { it.contains("/.within.website/x/cmd/anubis/static/css/") } &&
            resources.any { it.endsWith("/reject.webp") } &&
            document.select(".message, .centered-div").isNotEmpty()
    }

    private fun safeResourcePath(value: String, baseUrl: String): String = runCatching {
        val uri = java.net.URI(value)
        val path = if (uri.isAbsolute) uri.path else java.net.URI(baseUrl).resolve(uri).path
        path.orEmpty().take(200)
    }.getOrDefault("")
}
