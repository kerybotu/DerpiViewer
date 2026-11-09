package com.kerybotu.derpibooru.mirror

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashDiagnosticsTest {
    @Test
    fun redactsCredentialValuesFromCrashText() {
        val safe = CrashDiagnostics.redactSensitiveText(
            "request https://example.org/?key=secret123 Cookie: session-secret Authorization=Bearer secret-token"
        )

        assertFalse(safe.contains("secret123"))
        assertFalse(safe.contains("session-secret"))
        assertFalse(safe.contains("secret-token"))
        assertTrue(safe.contains("[REDACTED]"))
    }
}
