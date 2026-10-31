package com.kerybotu.derpibooru.mirror.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiDiagnosticsTest {
    @Test
    fun summaryContainsStableClassificationAndRequestId() {
        val diagnostic = ApiRequestDiagnostic(
            requestId = "a1b2c3d4",
            operation = "search/images",
            host = "derpibooru.org",
            phase = "http",
            kind = ApiFailureKind.RATE_LIMITED,
            statusCode = 429,
            elapsedMs = 1200,
            attempt = 2,
            maxAttempts = 3,
            networkConfigurationVersion = 7,
            retryable = false,
            rootCause = "https://derpibooru.org/api?key=secret Cookie=private"
        )

        val summary = diagnostic.summary()
        assertTrue(summary.contains("a1b2c3d4"))
        assertTrue(summary.contains("RATE_LIMITED"))
        assertTrue(summary.contains("http=429"))
        assertFalse(summary.contains("secret"))
        assertFalse(summary.contains("private"))
        assertFalse(summary.contains("https://"))
    }
}
