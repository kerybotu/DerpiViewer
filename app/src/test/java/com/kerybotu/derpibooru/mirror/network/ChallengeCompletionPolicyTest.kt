package com.kerybotu.derpibooru.mirror.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChallengeCompletionPolicyTest {
    private val target = "https://derpibooru.org/api/v1/json/search/images?q=safe"

    @Test
    fun acceptsJsonOnFirstLoadWithExistingClearance() {
        assertTrue(ChallengeCompletionPolicy.isResolved(target, target, false, false, true))
    }

    @Test
    fun acceptsSuccessfulApiResponseWithLeftoverChallengeQuery() {
        assertTrue(ChallengeCompletionPolicy.isResolved(
            target, "$target&__cf_chl_tk=completed", false, false, true
        ))
    }

    @Test
    fun rejectsErrorPagesAndIncompleteVerification() {
        assertFalse(ChallengeCompletionPolicy.isResolved(target, target, true, false, true))
        assertFalse(ChallengeCompletionPolicy.isResolved(target, target, false, true, true))
        assertFalse(ChallengeCompletionPolicy.isResolved(target, target, false, false, false))
    }

    @Test
    fun rejectsJsonFromAnotherOriginOrEndpoint() {
        listOf(
            target.replace("derpibooru.org", "example.com"),
            target.replace("https:", "http:"),
            target.replace(".org/", ".org:8443/"),
            "https://derpibooru.org/api/v1/json/filters/system",
            "https://derpibooru.org/cdn-cgi/challenge-platform/result",
            "about:blank"
        ).forEach { url ->
            assertFalse(url, ChallengeCompletionPolicy.isResolved(target, url, false, false, true))
        }
    }
}
