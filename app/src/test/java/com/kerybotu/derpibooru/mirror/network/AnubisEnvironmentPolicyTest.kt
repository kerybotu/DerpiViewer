package com.kerybotu.derpibooru.mirror.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnubisEnvironmentPolicyTest {
    private val systemWebViewUa =
        "Mozilla/5.0 (Linux; Android 9; RMX1931 Build/PQ3B.190801.03191204; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/91.0.4472.114 Mobile Safari/537.36"

    @Test
    fun versionlessPolicyRemovesOnlyTheChromeVersionToken() {
        val actual = AnubisEnvironmentPolicy.versionlessUserAgent(systemWebViewUa)

        assertEquals(
            "Mozilla/5.0 (Linux; Android 9; RMX1931 Build/PQ3B.190801.03191204; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Mobile Safari/537.36",
            actual
        )
        assertTrue(actual.contains("Android 9"))
        assertTrue(actual.contains("RMX1931"))
        assertTrue(actual.contains("; wv)"))
        assertFalse(actual.contains("Chrome/"))
    }

    @Test
    fun versionlessPolicyPreservesUserAgentsWithoutChromeVersion() {
        val ua = "Mozilla/5.0 (Linux; Android 9; wv) AppleWebKit/537.36 Mobile Safari/537.36"

        assertEquals(ua, AnubisEnvironmentPolicy.versionlessUserAgent(ua))
    }

    @Test
    fun recognizesAnubisAuthCookieWithoutTreatingVerificationCookieAsClearance() {
        assertTrue(AnubisEnvironmentPolicy.isAuthCookieName("techaro.lol-anubis-auth-6b097436"))
        assertTrue(AnubisEnvironmentPolicy.isAuthCookieName("techaro.lol-anubis"))
        assertFalse(AnubisEnvironmentPolicy.isAuthCookieName("techaro.lol-anubis-cookie-verification-6b097436"))
    }
}
