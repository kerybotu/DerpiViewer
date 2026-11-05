package com.kerybotu.derpibooru.mirror.model

import org.json.JSONArray
import org.json.JSONObject

object FilterJsonCodec {
    fun parseList(json: String): List<Filter> {
        val array = runCatching {
            val trimmed = json.trim()
            if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).optJSONArray("filters")
        }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::parse) }
    }

    fun parseResponse(json: String): Filter? = runCatching {
        val objectValue = JSONObject(json)
        val filterObject = objectValue.optJSONObject("filter") ?: objectValue
        parse(filterObject, local = filterObject.optBoolean("is_local", objectValue.optBoolean("is_local", false)))
    }.getOrNull()

    fun parse(o: JSONObject, local: Boolean = false, localQuery: String? = null): Filter {
        val spoilered = o.optJSONArray("spoilered_tag_ids").toIntList()
        val hidden = o.optJSONArray("hidden_tag_ids").toIntList()
        return Filter(
            id = o.optInt("id", 0),
            name = o.optString("name", "未命名过滤器"),
            description = o.optString("description", ""),
            userId = if (o.isNull("user_id")) null else o.optInt("user_id"),
            system = o.optBoolean("system", false),
            public = o.optBoolean("public", false),
            spoilerCount = if (spoilered.isNotEmpty() || o.has("spoilered_tag_ids")) spoilered.size else o.optInt("spoilered_count", 0),
            hiddenCount = if (hidden.isNotEmpty() || o.has("hidden_tag_ids")) hidden.size else o.optInt("hidden_count", 0),
            creator = o.optNullableString("creator") ?: o.optNullableString("user_name"),
            createdAt = o.optNullableString("created_at"),
            spoileredTagIds = spoilered,
            hiddenTagIds = hidden,
            spoileredComplex = o.optNullableString("spoilered_complex"),
            hiddenComplex = o.optNullableString("hidden_complex"),
            sourceId = o.optInt("source_id", 0).takeIf { it > 0 } ?: o.optInt("id", 0).takeIf { it > 0 },
            sourceDomain = o.optNullableString("source_domain"),
            localQuery = localQuery ?: o.optNullableString("local_query"),
            isLocal = local
        )
    }

    fun toJson(filter: Filter): JSONObject = JSONObject().apply {
        put("id", filter.id)
        put("name", filter.name)
        put("description", filter.description)
        put("user_id", filter.userId ?: JSONObject.NULL)
        put("system", filter.system)
        put("public", filter.public)
        put("created_at", filter.createdAt ?: JSONObject.NULL)
        put("spoilered_tag_ids", JSONArray(filter.spoileredTagIds))
        put("hidden_tag_ids", JSONArray(filter.hiddenTagIds))
        put("spoilered_complex", filter.spoileredComplex ?: JSONObject.NULL)
        put("hidden_complex", filter.hiddenComplex ?: JSONObject.NULL)
        put("source_id", filter.sourceId ?: JSONObject.NULL)
        put("source_domain", filter.sourceDomain ?: JSONObject.NULL)
        put("local_query", filter.localQuery ?: JSONObject.NULL)
        put("is_local", filter.isLocal)
    }

    private fun JSONArray?.toIntList(): List<Int> = this?.let { array ->
        (0 until array.length()).mapNotNull { index -> array.optInt(index, -1).takeIf { it > 0 } }
    }.orEmpty()

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
