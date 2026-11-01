package com.kerybotu.derpibooru.mirror.model

/** Fields supported by the Derpibooru /filters search syntax. */
enum class FilterSearchField(val key: String, val kind: Kind) {
    NAME("name", Kind.LITERAL),
    CREATOR("creator", Kind.LITERAL),
    DESCRIPTION("description", Kind.FULL_TEXT),
    CREATED_AT("created_at", Kind.DATE_RANGE),
    ID("id", Kind.NUMERIC_RANGE),
    SPOILERED_COUNT("spoilered_count", Kind.NUMERIC_RANGE),
    HIDDEN_COUNT("hidden_count", Kind.NUMERIC_RANGE),
    MY("my", Kind.META),
    SYSTEM("system", Kind.BOOLEAN),
    PUBLIC("public", Kind.BOOLEAN),
    USER_ID("user_id", Kind.LITERAL);

    enum class Kind { LITERAL, FULL_TEXT, DATE_RANGE, NUMERIC_RANGE, META, BOOLEAN }

    companion object {
        fun fromKey(value: String): FilterSearchField? = values().firstOrNull { it.key == value.lowercase() }
    }
}

data class FilterSearchCondition(
    val field: FilterSearchField = FilterSearchField.NAME,
    val value: String
)

/**
 * Serializer/parser for filter search queries. It deliberately leaves range
 * operators intact and only quotes values that need quoting; URL encoding is
 * performed by the HTTP layer exactly once.
 */
object FilterSearchQuery {
    fun serialize(conditions: Iterable<FilterSearchCondition>): String = conditions
        .map { it.copy(value = it.value.trim()) }
        .filter { it.value.isNotEmpty() }
        .distinctBy { it.field.key to it.value }
        .joinToString(" ") { condition ->
            "${condition.field.key}:${formatValue(condition.value, condition.field.kind)}"
        }

    fun parse(query: String): List<FilterSearchCondition> {
        val tokens = tokenize(query)
        val result = mutableListOf<FilterSearchCondition>()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            val separator = token.indexOf(':')
            if (separator <= 0) {
                result += FilterSearchCondition(FilterSearchField.NAME, token)
                index++
                continue
            }
            val field = FilterSearchField.fromKey(token.substring(0, separator))
            if (field == null) {
                result += FilterSearchCondition(FilterSearchField.NAME, token)
                index++
                continue
            }
            var value = token.substring(separator + 1)
            // Full-text descriptions may contain spaces without quotes in the
            // documented examples. Consume following bare words until the next
            // field expression instead of silently turning them into name terms.
            if (field == FilterSearchField.DESCRIPTION && !value.startsWith("\"")) {
                val parts = mutableListOf(value)
                var next = index + 1
                while (next < tokens.size && !tokens[next].contains(':')) {
                    parts += tokens[next]; next++
                }
                value = parts.joinToString(" ")
                index = next
            } else index++
            result += FilterSearchCondition(field, unquote(value))
        }
        return result
    }

    fun normalize(query: String): String = serialize(parse(query))

    private fun formatValue(raw: String, kind: FilterSearchField.Kind): String {
        val value = raw.trim()
        if (kind == FilterSearchField.Kind.BOOLEAN ||
            kind == FilterSearchField.Kind.NUMERIC_RANGE ||
            kind == FilterSearchField.Kind.DATE_RANGE ||
            kind == FilterSearchField.Kind.META
        ) return value
        return if (value.any { it.isWhitespace() || it == '"' || it == '\\' }) {
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        } else value
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

data class CustomFilterCondition(val value: String, val excluded: Boolean = false)

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
    fun parse(query: String): List<CustomFilterCondition> {
        val tokens = tokenize(query)
        return tokens.mapNotNull { token ->
            val excluded = token.startsWith("-") && token.length > 1
            val raw = if (excluded) token.substring(1) else token
            raw.trim().takeIf { it.isNotEmpty() }?.let {
                CustomFilterCondition(unquote(it), excluded)
            }
        }
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
