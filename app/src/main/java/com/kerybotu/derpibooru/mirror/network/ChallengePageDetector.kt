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

/** Identifies supported verification pages without matching generic HTML errors. */
object ChallengePageDetector {
    private const val DERPI_FORM_SIGNATURE =
        "<form class=\"derpi-challenge\" action=\"/challenge\" method=\"post\">"

    fun detect(contentType: String, html: String): ChallengePageType? {
        return assess(statusCode = 200, finalUrl = "", contentType = contentType, html = html).challengeType
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
                ChallengePageType.ANUBIS -> ChallengeResponseCategory.ANUBIS_CHALLENGE
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
        if (hasAnubisChallenge && (hasAnubisRuntime || hasAnubisBranding)) {
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
}
