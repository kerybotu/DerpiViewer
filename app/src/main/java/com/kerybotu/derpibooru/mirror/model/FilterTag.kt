package com.kerybotu.derpibooru.mirror.model

/** A server tag reference; the numeric ID remains the persisted filter value. */
data class FilterTag(val id: Int, val name: String)

object FilterTagLabels {
    fun display(ids: List<Int>, namesById: Map<Int, String>): List<String> = ids.map { id ->
        namesById[id]?.takeIf(String::isNotBlank) ?: "未知标签（ID: $id）"
    }
}

object FilterEditorMapper {
    fun apply(
        base: Filter?,
        newLocalId: Int,
        name: String,
        description: String,
        spoileredTagIds: List<Int>,
        spoileredComplex: String,
        hiddenTagIds: List<Int>,
        hiddenComplex: String,
        localQuery: String,
        defaultSourceDomain: String?
    ): Filter {
        val id = base?.takeIf { it.isLocal && it.id < 0 }?.id ?: newLocalId
        val sourceId = base?.sourceId ?: base?.id?.takeIf { it > 0 }
        val template = base ?: Filter(id, name, "", null, false, false, 0, 0, isLocal = true)
        val spoilerIds = spoileredTagIds.distinct()
        val hiddenIds = hiddenTagIds.distinct()
        return template.copy(
            id = id,
            name = name,
            description = description,
            spoileredTagIds = spoilerIds,
            hiddenTagIds = hiddenIds,
            spoilerCount = spoilerIds.size,
            hiddenCount = hiddenIds.size,
            spoileredComplex = spoileredComplex.ifEmpty { null },
            hiddenComplex = hiddenComplex.ifEmpty { null },
            sourceId = sourceId,
            sourceDomain = base?.sourceDomain ?: sourceId?.let { defaultSourceDomain },
            localQuery = localQuery.ifBlank { null },
            isLocal = true
        )
    }
}
