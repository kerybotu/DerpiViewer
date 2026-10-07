package com.kerybotu.derpibooru.mirror.network

enum class ChallengePageType {
    DERPI_FORM,
    CLOUDFLARE_TURNSTILE,
    ANUBIS
}

/** Identifies supported verification pages without matching generic HTML errors. */
object ChallengePageDetector {
    private const val DERPI_FORM_SIGNATURE =
        "<form class=\"derpi-challenge\" action=\"/challenge\" method=\"post\">"

    fun detect(contentType: String, html: String): ChallengePageType? {
        if (!contentType.contains("text/html", ignoreCase = true)) return null
        if (html.contains(DERPI_FORM_SIGNATURE, ignoreCase = true)) {
            return ChallengePageType.DERPI_FORM
        }

        // Anubis pages expose a structured challenge object and load their
        // proof-of-work runtime from the private `within.website` path. Keep
        // both markers required so ordinary pages mentioning "challenge" or
        // a user tag named "anubis" are never opened as a verification page.
        val hasAnubisChallenge =
            html.contains("anubis_challenge", ignoreCase = true) ||
                html.contains("id=\"anubis-main\"", ignoreCase = true)
        val hasAnubisRuntime =
            html.contains("/x/cmd/anubis/", ignoreCase = true) ||
                html.contains("anubis_version", ignoreCase = true) ||
                html.contains("id=\"anubis-main\"", ignoreCase = true)
        val hasAnubisBranding =
            html.contains("making sure you're not a bot", ignoreCase = true) ||
                html.contains("protected by", ignoreCase = true) &&
                html.contains("anubis", ignoreCase = true)
        if (hasAnubisChallenge && (hasAnubisRuntime || hasAnubisBranding)) {
            return ChallengePageType.ANUBIS
        }

        val hasCloudflareRuntime =
            html.contains("/cdn-cgi/challenge-platform/", ignoreCase = true) &&
                (html.contains("window._cf_chl_opt", ignoreCase = true) ||
                    html.contains("challenges.cloudflare.com/turnstile", ignoreCase = true))
        val hasVerificationUi =
            html.contains("cf-turnstile-response", ignoreCase = true) ||
                (html.contains("captcha", ignoreCase = true) &&
                    html.contains("robot", ignoreCase = true))

        return if (hasCloudflareRuntime && hasVerificationUi) {
            ChallengePageType.CLOUDFLARE_TURNSTILE
        } else {
            null
        }
    }
}
