package com.kerybotu.derpibooru.mirror.network

enum class ChallengePageType {
    DERPI_FORM,
    CLOUDFLARE_TURNSTILE
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
