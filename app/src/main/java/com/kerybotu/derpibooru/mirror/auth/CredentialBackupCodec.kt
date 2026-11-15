package com.kerybotu.derpibooru.mirror.auth

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonElement
import com.google.gson.JsonArray
import com.google.gson.JsonPrimitive
import com.google.gson.JsonNull
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable format: contains no Android or device-key dependency. Never log these objects. */
internal class EncryptedExportPayload(
    val version: Int = 1,
    val exportedAt: Long,
    val origin: String,
    val includesCookies: Boolean,
    val includesApiKey: Boolean,
    val cookies: List<BackupCookie>?,
    val apiKey: String?
)

internal class BackupCookie(
    val name: String, val value: String, val domain: String, val path: String,
    val secure: Boolean, val httpOnly: Boolean, val hostOnly: Boolean, val expiresAt: Long?
) {
    fun cookie(): Cookie = Cookie.Builder().name(name).value(value).path(path).apply {
        if (hostOnly) hostOnlyDomain(domain) else domain(domain)
        if (secure) secure()
        if (httpOnly) httpOnly()
        expiresAt?.let(::expiresAt)
    }.build()

    companion object {
        fun from(cookie: Cookie) = BackupCookie(cookie.name, cookie.value, cookie.domain, cookie.path,
            cookie.secure, cookie.httpOnly, cookie.hostOnly, cookie.expiresAt.takeIf { cookie.persistent })
        fun isChallenge(name: String): Boolean = name.lowercase().let {
            it.startsWith("cf_") || it.startsWith("__cf") || it.contains("anubis")
        }
        fun fromWebView(url: okhttp3.HttpUrl, raw: String): BackupCookie {
            val parsed = requireNotNull(Cookie.parse(url, raw))
            val domainAttribute = raw.split(';').drop(1).firstOrNull {
                it.substringBefore('=').trim().equals("domain", true)
            }?.substringAfter('=')?.trim()
            return BackupCookie(parsed.name, parsed.value, parsed.domain, parsed.path, parsed.secure,
                parsed.httpOnly, domainAttribute?.startsWith('.') != true,
                parsed.expiresAt.takeIf { parsed.persistent })
        }
    }
}

internal object BackupPasswordPolicy {
    data class Assessment(val score: Int, val message: String, val meetsMinimum: Boolean)

    fun valid(password: CharSequence): Boolean = password.length in 8..1024 &&
        password.any { it in 'a'..'z' || it in 'A'..'Z' } && password.any { it in '0'..'9' }

    fun strength(password: CharSequence): Int {
        val value = password.toString()
        val lower = value.lowercase()
        if (value.isEmpty()) return 0
        if (!valid(value) || listOf("password", "qwerty", "123456", "letmein", "abc123", "welcome")
                .any(lower::contains) || value.toSet().size < 5 || Regex("(.{1,4})\\1{2,}").containsMatchIn(value)) return 0
        val kinds = listOf(value.any(Char::isLowerCase), value.any(Char::isUpperCase),
            value.any(Char::isDigit), value.any { !it.isLetterOrDigit() && !it.isWhitespace() }).count { it }
        return when {
            value.length >= 20 && kinds >= 3 -> 3
            value.length >= 14 && kinds >= 3 -> 2
            value.length >= 12 && kinds >= 2 -> 1
            else -> 0
        }
    }

    fun assess(password: CharSequence): Assessment {
        val score = strength(password)
        val value = password.toString()
        val missing = buildList {
            if (value.length < 8) add("至少 8 个字符")
            if (value.none { it in 'a'..'z' || it in 'A'..'Z' }) add("英文字母")
            if (value.none { it.isDigit() }) add("数字")
        }
        return Assessment(score, when {
            value.isEmpty() -> "输入备份密码"
            missing.isNotEmpty() -> "还需要：${missing.joinToString("、")}"
            score == 0 -> "弱 · 避免常见词和重复模式"
            score == 1 -> "中 · 可以加入大小写或符号"
            score == 2 -> "强 · 较好的密码"
            else -> "很强 · 推荐"
        }, valid(value))
    }
}

internal object CredentialBackupCodec {
    const val MAX_FILE_BYTES = 512 * 1024
    const val ITERATIONS = 600_000
    private const val FORMAT = "DerpiViewerEncryptedBackup"
    private val gson = com.google.gson.GsonBuilder().disableHtmlEscaping().create()
    private val random = SecureRandom()
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    /** Bound nesting and reject duplicate keys before Gson can discard ambiguity. */
    private fun parse(text: String): JsonObject {
        val reader = JsonReader(StringReader(text))
        fun value(depth: Int): JsonElement {
            require(depth <= 8)
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val name = reader.nextName(); require(!has(name)); add(name, value(depth + 1))
                    }
                    reader.endObject()
                }
                JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                    reader.beginArray()
                    while (reader.hasNext()) { require(size() < 256); add(value(depth + 1)) }
                    reader.endArray()
                }
                JsonToken.STRING -> JsonPrimitive(reader.nextString())
                JsonToken.NUMBER -> JsonPrimitive(reader.nextString().also {
                    require(it.length <= 20 && Regex("-?(0|[1-9][0-9]*)").matches(it))
                }.toLong())
                JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
                else -> error("invalid_backup_json")
            }
        }
        val result = value(0).asJsonObject
        require(reader.peek() == JsonToken.END_DOCUMENT)
        return result
    }

    fun origin(value: String): String {
        require(CookiePersistencePolicy.isPersistableOrigin(value))
        return "https://${value.toHttpUrl().host}/"
    }

    fun validate(payload: EncryptedExportPayload, now: Long = System.currentTimeMillis()) {
        require(payload.version == 1 && payload.exportedAt > 0 && payload.exportedAt <= now + 300_000)
        require(origin(payload.origin) == payload.origin)
        require(payload.includesCookies || payload.includesApiKey)
        require(payload.includesCookies == (payload.cookies != null))
        require(payload.includesApiKey == (payload.apiKey != null))
        payload.apiKey?.let { require(it.isNotBlank() && it.length <= 8192 && it.none { c -> c <= ' ' || c == '\u007f' }) }
        payload.cookies?.let { cookies ->
            require(cookies.isNotEmpty() && cookies.size <= 128)
            require(cookies.joinToString("; ") { "${it.name}=${it.value}" }.toByteArray().size <= 64 * 1024)
            require(cookies.map { "${it.name}|${it.domain}|${it.path}" }.toSet().size == cookies.size)
            cookies.forEach {
                require(!BackupCookie.isChallenge(it.name) && it.name.length <= 256 && it.value.length <= 16_384)
                require(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+").matches(it.name))
                require(it.value.none { c -> c <= ' ' || c == ';' || c == '\u007f' })
                require(it.path.none { c -> c <= ' ' || c == ';' || c == '\u007f' })
                // Exact target host only: never allow a backup to populate sibling sites.
                require(it.domain == payload.origin.toHttpUrl().host && it.path.startsWith('/') && it.path.length <= 2048)
                require(it.expiresAt == null || it.expiresAt > now)
                val cookie = it.cookie()
                require(cookie.domain == it.domain && cookie.path == it.path)
                require(!it.name.startsWith("__Secure-") || it.secure)
                require(!it.name.startsWith("__Host-") || (it.secure && it.hostOnly && it.path == "/"))
            }
        }
        require(gson.toJson(payload).toByteArray().size <= 128 * 1024)
    }

    private fun metadata(iterations: Int, salt: String, nonce: String): JsonObject = JsonObject().apply {
        addProperty("format", FORMAT); addProperty("version", 1)
        add("kdf", JsonObject().apply {
            addProperty("algorithm", "PBKDF2WithHmacSHA256"); addProperty("iterations", iterations); addProperty("salt", salt)
        })
        add("cipher", JsonObject().apply {
            addProperty("algorithm", "AES-256-GCM"); addProperty("nonce", nonce); addProperty("tagLength", 128)
        })
    }

    private fun crypt(mode: Int, password: CharArray, salt: ByteArray, nonce: ByteArray,
                      iterations: Int, aad: ByteArray, input: ByteArray): ByteArray {
        require(password.size in 1..1024)
        val spec = PBEKeySpec(password, salt, iterations, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad)
            }.doFinal(input)
        } finally { key.fill(0) }
    }

    fun encrypt(payload: EncryptedExportPayload, password: CharArray): ByteArray {
        require(BackupPasswordPolicy.valid(String(password)))
        validate(payload)
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val header = metadata(ITERATIONS, encoder.encodeToString(salt), encoder.encodeToString(nonce))
        val plain = gson.toJson(payload).toByteArray(Charsets.UTF_8)
        try {
            val ciphertext = crypt(Cipher.ENCRYPT_MODE, password, salt, nonce, ITERATIONS,
                gson.toJson(header).toByteArray(Charsets.UTF_8), plain)
            header.addProperty("ciphertext", encoder.encodeToString(ciphertext))
            return gson.toJson(header).toByteArray(Charsets.UTF_8)
        } finally { plain.fill(0) }
    }

    fun decrypt(bytes: ByteArray, password: CharArray): EncryptedExportPayload {
        require(bytes.size in 1..MAX_FILE_BYTES)
        val header = parse(String(bytes, Charsets.UTF_8))
        require(header.keySet() == setOf("format", "version", "kdf", "cipher", "ciphertext"))
        require(header["format"].asString == FORMAT && header["version"].toString() == "1")
        val kdf = header.getAsJsonObject("kdf")
        val cipher = header.getAsJsonObject("cipher")
        require(kdf.keySet() == setOf("algorithm", "iterations", "salt"))
        require(cipher.keySet() == setOf("algorithm", "nonce", "tagLength"))
        require(kdf["algorithm"].asString == "PBKDF2WithHmacSHA256")
        require(cipher["algorithm"].asString == "AES-256-GCM" && cipher["tagLength"].toString() == "128")
        val iterations = kdf["iterations"].toString().toInt()
        require(iterations in ITERATIONS..1_200_000)
        val saltText = kdf["salt"].asString
        val nonceText = cipher["nonce"].asString
        require(saltText.length == 24 && nonceText.length == 16)
        val salt = decoder.decode(saltText); val nonce = decoder.decode(nonceText)
        require(salt.size == 16 && nonce.size == 12)
        require(encoder.encodeToString(salt) == saltText && encoder.encodeToString(nonce) == nonceText)
        val ciphertext = decoder.decode(header["ciphertext"].asString)
        require(ciphertext.size in 17..(128 * 1024 + 16))
        val aad = gson.toJson(metadata(iterations, saltText, nonceText)).toByteArray(Charsets.UTF_8)
        val plain = crypt(Cipher.DECRYPT_MODE, password, salt, nonce, iterations, aad, ciphertext)
        return try {
            gson.fromJson(parse(String(plain, Charsets.UTF_8)), EncryptedExportPayload::class.java).also { validate(it) }
        } finally { plain.fill(0) }
    }
}
