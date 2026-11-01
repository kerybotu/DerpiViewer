package com.kerybotu.derpibooru.mirror.network


/** Stable, user-independent categories for failures in the shared API client. */
enum class ApiFailureKind {
    DNS,
    TCP,
    TLS,
    CONNECT_TIMEOUT,
    READ_TIMEOUT,
    CANCELLED,
    CONFIGURATION_CHANGED,
    HTTP_4XX,
    HTTP_5XX,
    RATE_LIMITED,
    EMPTY_BODY,
    INVALID_JSON,
    BUSINESS_ERROR,
    UNKNOWN
}

data class ApiRequestDiagnostic(
    val requestId: String,
    val operation: String,
    val host: String,
    val method: String = "GET",
    val phase: String,
    val kind: ApiFailureKind,
    val statusCode: Int? = null,
    val elapsedMs: Long,
    val attempt: Int,
    val maxAttempts: Int,
    val networkConfigurationVersion: Long,
    val retryable: Boolean,
    val rootCause: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
) {
    fun summary(): String = buildString {
        append(requestId).append(' ')
        append(method).append(' ').append(operation).append(' ')
        append(kind.name).append(" phase=").append(phase)
        statusCode?.let { append(" http=").append(it) }
        append(" elapsedMs=").append(elapsedMs)
        append(" attempt=").append(attempt).append('/').append(maxAttempts)
        append(" config=").append(networkConfigurationVersion)
        rootCause?.takeIf { it.isNotBlank() }?.let { append(" cause=").append(redact(it)) }
    }

    private fun redact(value: String): String = value
        .replace(Regex("(?i)(authorization|cookie|token|key|password)=([^&\\s]+)"), "$1=***")
        .replace(Regex("(?i)https?://[^\\s]+"), "<url>")
}

/** Detects a structured API rejection returned with an otherwise successful HTTP status. */
internal fun isBusinessErrorPayload(body: String): Boolean = runCatching {
    val normalized = body.trim()
    Regex("(?i)\\\"(?:error|errors)\\\"\\s*:").containsMatchIn(normalized) ||
        Regex("(?i)\\\"success\\\"\\s*:\\s*false\\b").containsMatchIn(normalized)
}.getOrDefault(false)
