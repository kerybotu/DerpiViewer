package com.kerybotu.derpibooru.mirror.model

import java.net.URI

object FilterImportParser {
    fun parseId(input: String, allowedDomains: Set<String>): Int? {
        val value = input.trim()
        if (value.matches(Regex("\\d+"))) return value.toIntOrNull()?.takeIf { it > 0 }
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host !in allowedDomains.map { it.lowercase() }.toSet()) return null
        val parts = uri.path.orEmpty().trim('/').split('/')
        if (parts.size !in 2..3 || parts[0] != "filters" || (parts.size == 3 && parts[2] != "edit")) return null
        return parts[1].toIntOrNull()?.takeIf { it > 0 }
    }
}
