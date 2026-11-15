package com.kerybotu.derpibooru.mirror.auth

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class CredentialBackupCodecTest {
    private val password get() = "Rivers9!Moonlight#2026".toCharArray()
    private fun payload(cookies: Boolean = true, key: Boolean = true) = EncryptedExportPayload(
        exportedAt = System.currentTimeMillis(), origin = "https://derpibooru.org/",
        includesCookies = cookies, includesApiKey = key,
        cookies = if (cookies) listOf(BackupCookie("session", "synthetic-test-only", "derpibooru.org", "/",
            true, true, true, System.currentTimeMillis() + 3600_000)) else null,
        apiKey = if (key) "synthetic-key-only" else null)
    private fun rejects(block: () -> Unit) { try { block(); fail("Must reject invalid backup") } catch (_: Exception) { } }

    @Test fun randomizedSaltNonceAndPortableRoundTripForAllSelections() {
        for ((cookies, key) in listOf(true to false, false to true, true to true)) {
            val payload = payload(cookies, key)
            val first = CredentialBackupCodec.encrypt(payload, password)
            val second = CredentialBackupCodec.encrypt(payload, password)
            val a = JsonParser().parse(String(first)).asJsonObject
            val b = JsonParser().parse(String(second)).asJsonObject
            assertNotEquals(a["kdf"].asJsonObject["salt"], b["kdf"].asJsonObject["salt"])
            assertNotEquals(a["cipher"].asJsonObject["nonce"], b["cipher"].asJsonObject["nonce"])
            val restored = CredentialBackupCodec.decrypt(first, password)
            assertEquals(cookies, restored.includesCookies); assertEquals(key, restored.includesApiKey)
            assertEquals(payload.apiKey, restored.apiKey)
            assertEquals(payload.cookies?.first()?.value, restored.cookies?.first()?.value)
            assertFalse(String(first).contains("synthetic"))
        }
    }

    @Test fun wrongPasswordTamperProtectedMetadataTruncationAndBoundsFail() {
        val bytes = CredentialBackupCodec.encrypt(payload(), password)
        rejects { CredentialBackupCodec.decrypt(bytes, "WrongPassword9".toCharArray()) }
        fun modified(change: (com.google.gson.JsonObject) -> Unit): ByteArray =
            JsonParser().parse(String(bytes)).asJsonObject.also(change).toString().toByteArray()
        rejects { CredentialBackupCodec.decrypt(modified { it.addProperty("ciphertext", "A" + it["ciphertext"].asString.drop(1)) }, password) }
        rejects { CredentialBackupCodec.decrypt(modified { it["kdf"].asJsonObject.addProperty("iterations", 600001) }, password) }
        rejects { CredentialBackupCodec.decrypt(modified { it["kdf"].asJsonObject.addProperty("iterations", 599999) }, password) }
        rejects { CredentialBackupCodec.decrypt(modified { it["kdf"].asJsonObject.addProperty("iterations", Int.MAX_VALUE) }, password) }
        rejects { CredentialBackupCodec.decrypt(modified { it["cipher"].asJsonObject.addProperty("nonce", "AAAAAAAAAAAAAAAA") }, password) }
        rejects { CredentialBackupCodec.decrypt(bytes.copyOf(bytes.size / 2), password) }
        rejects { CredentialBackupCodec.decrypt(ByteArray(CredentialBackupCodec.MAX_FILE_BYTES + 1), password) }
        rejects { CredentialBackupCodec.decrypt(("[".repeat(10000) + "]".repeat(10000)).toByteArray(), password) }
    }

    @Test fun cookieScopeChallengeExpiryAndPasswordPolicy() {
        fun cookie(name: String = "session", domain: String = "derpibooru.org", expires: Long? = null) =
            BackupCookie(name, "synthetic", domain, "/", true, true, true, expires)
        fun bad(cookie: BackupCookie) = EncryptedExportPayload(exportedAt = System.currentTimeMillis(),
            origin = "https://derpibooru.org/", includesCookies = true, includesApiKey = false, cookies = listOf(cookie), apiKey = null)
        rejects { CredentialBackupCodec.validate(bad(cookie(domain = "other.example"))) }
        rejects { CredentialBackupCodec.validate(bad(cookie(name = "cf_clearance"))) }
        rejects { CredentialBackupCodec.validate(bad(cookie(name = "techaro.lol-anubis-auth"))) }
        rejects { CredentialBackupCodec.validate(bad(cookie(expires = 1))) }
        assertFalse(BackupPasswordPolicy.valid("        "))
        assertFalse(BackupPasswordPolicy.valid("abcdefgh"))
        assertFalse(BackupPasswordPolicy.valid("12345678"))
        assertTrue(BackupPasswordPolicy.valid("abcd1234"))
        assertEquals(0, BackupPasswordPolicy.strength("Password123456789!"))
        assertEquals(0, BackupPasswordPolicy.strength("a1a1a1a1a1a1a1a1"))
        assertEquals(3, BackupPasswordPolicy.strength("River9!Mountains#Cloud2026"))
    }

    @Test fun passwordAssessmentTracksInputAndClear() {
        assertEquals("输入备份密码", BackupPasswordPolicy.assess("").message)
        assertFalse(BackupPasswordPolicy.assess("short").meetsMinimum)
        assertFalse(BackupPasswordPolicy.assess("abcdefgh").meetsMinimum)
        assertFalse(BackupPasswordPolicy.assess("12345678").meetsMinimum)
        assertEquals(0, BackupPasswordPolicy.assess("Password123456789!").score)
        assertTrue(BackupPasswordPolicy.assess("abcd1234").meetsMinimum)
        assertEquals(3, BackupPasswordPolicy.assess("River9!Mountains#Cloud2026").score)
        assertEquals(0, BackupPasswordPolicy.assess("a1a1a1a1a1a1a1a1").score)
        assertTrue(BackupPasswordPolicy.assess("Correct-Horse7-Battery!Cloud").score >= 2)
        val edited = StringBuilder("abcd1234")
        assertTrue(BackupPasswordPolicy.assess(edited).meetsMinimum)
        edited.clear()
        assertEquals("输入备份密码", BackupPasswordPolicy.assess(edited).message)
    }
}
