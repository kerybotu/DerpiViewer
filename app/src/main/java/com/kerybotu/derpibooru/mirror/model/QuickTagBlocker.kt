package com.kerybotu.derpibooru.mirror.model

/** Pure query manipulation used by the detail-page quick tag action. */
object QuickTagBlocker {
    fun normalize(tag: String): String = tag.trim().replace(Regex("\\s+"), " ")

    fun isBlocked(filter: Filter, tag: String): Boolean {
        val normalized = normalize(tag)
        if (normalized.isEmpty()) return false
        return CustomFilterQueryBuilder.parse(filter.localQuery.orEmpty())
            .any { it.excluded && normalize(it.value).equals(normalized, ignoreCase = true) }
    }

    fun withBlockedTag(filter: Filter, tag: String, blocked: Boolean): Filter? {
        val normalized = normalize(tag)
        if (normalized.isEmpty()) return null
        val existing = CustomFilterQueryBuilder.parse(filter.localQuery.orEmpty())
        val retained = existing.filterNot {
            it.excluded && normalize(it.value).equals(normalized, ignoreCase = true)
        }
        val updated = if (blocked) retained + CustomFilterCondition(normalized, excluded = true) else retained
        return filter.copy(localQuery = CustomFilterQueryBuilder.serialize(updated).ifBlank { null })
    }
}
