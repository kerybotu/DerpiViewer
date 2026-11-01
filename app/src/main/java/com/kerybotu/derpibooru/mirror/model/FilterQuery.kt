package com.kerybotu.derpibooru.mirror.model

data class CustomFilterCondition(val value: String, val excluded: Boolean = false)

/** Serializes locally managed include/exclude tags into Derpibooru syntax. */
object CustomFilterQueryBuilder {
    fun serialize(conditions: Iterable<CustomFilterCondition>): String = conditions
        .map { CustomFilterCondition(it.value.trim(), it.excluded) }
        .filter { it.value.isNotEmpty() }
        .distinctBy { it.excluded to it.value.lowercase() }
        .joinToString(" ") { condition ->
            val value = if (condition.value.any { it.isWhitespace() || it == '"' }) {
                "\"" + condition.value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            } else condition.value
            if (condition.excluded) "-$value" else value
        }

    /** Parses the local preset syntax back into editable conditions. */
    fun parse(query: String): List<CustomFilterCondition> = tokenize(query).mapNotNull { token ->
        val excluded = token.startsWith("-") && token.length > 1
        val raw = if (excluded) token.substring(1) else token
        raw.trim().takeIf { it.isNotEmpty() }?.let { CustomFilterCondition(unquote(it), excluded) }
    }

    private fun tokenize(query: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var escaped = false
        query.trim().forEach { char ->
            when {
                escaped -> { current.append(char); escaped = false }
                char == '\\' && quoted -> escaped = true
                char == '"' -> { quoted = !quoted; current.append(char) }
                char.isWhitespace() && !quoted -> if (current.isNotEmpty()) {
                    result += current.toString(); current.clear()
                }
                else -> current.append(char)
            }
        }
        if (escaped) current.append('\\')
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    private fun unquote(value: String): String {
        val trimmed = value.trim()
        if (trimmed.length < 2 || trimmed.first() != '"' || trimmed.last() != '"') return trimmed
        return trimmed.substring(1, trimmed.length - 1)
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }
}
