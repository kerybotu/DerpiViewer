package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import com.kerybotu.derpibooru.mirror.model.FilterTag
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Resolves official server tag IDs without replacing the IDs stored in filters. */
object FilterTagRepository {
    private const val PREFERENCES = "filter_tag_names"
    private const val KEY_NAMES = "id_to_name_v1"
    private const val MAX_CACHE_ENTRIES = 500

    fun buildIdQuery(ids: List<Int>): String = ids.asSequence()
        .filter { it > 0 }
        .distinct()
        .joinToString(" || ") { "id:$it" }

    fun parseSearchResponse(json: String, expectedIds: Set<Int>): Map<Int, String> = runCatching {
        val tags = JSONObject(json).optJSONArray("tags") ?: JSONArray()
        buildMap {
            for (index in 0 until tags.length()) {
                val tag = tags.optJSONObject(index) ?: continue
                val id = tag.optInt("id", -1)
                val name = tag.optString("name").trim()
                if (id in expectedIds && name.isNotBlank()) put(id, name)
            }
        }
    }.getOrDefault(emptyMap())

    suspend fun resolve(context: Context, ids: List<Int>): Map<Int, String> {
        val requested = ids.filter { it > 0 }.distinct()
        if (requested.isEmpty()) return emptyMap()

        val cached = readCache(context).filterKeys { it in requested }
        val missing = requested.filterNot(cached::containsKey)
        if (missing.isEmpty()) return cached

        val query = buildIdQuery(missing)
        if (query.isBlank()) return cached
        val json = runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            NetworkManager.getApi(context, "search/tags?q=$encoded")
        }.getOrNull() ?: return cached
        val resolved = parseSearchResponse(json, missing.toSet())
        if (resolved.isNotEmpty()) writeCache(context, readCache(context) + resolved)
        return cached + resolved
    }

    suspend fun search(context: Context, query: String): List<FilterTag> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val encoded = URLEncoder.encode("$trimmed*", "UTF-8")
        val json = NetworkManager.getApi(context, "search/tags?q=$encoded") ?: return emptyList()
        val tags = runCatching { JSONObject(json).optJSONArray("tags") }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until tags.length()) {
                val tag = tags.optJSONObject(index) ?: continue
                val id = tag.optInt("id", -1)
                val name = tag.optString("name").trim()
                if (id > 0 && name.isNotBlank()) add(FilterTag(id, name))
            }
        }
    }

    private fun readCache(context: Context): Map<Int, String> = runCatching {
        val stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_NAMES, null) ?: return emptyMap()
        val json = JSONObject(stored)
        buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val id = key.toIntOrNull()?.takeIf { it > 0 } ?: continue
                val name = json.optString(key).trim()
                if (name.isNotBlank()) put(id, name)
            }
        }
    }.getOrDefault(emptyMap())

    private fun writeCache(context: Context, values: Map<Int, String>) {
        val bounded = values.entries.asSequence()
            .filter { it.key > 0 && it.value.isNotBlank() }
            .toList()
            .takeLast(MAX_CACHE_ENTRIES)
        val json = JSONObject().apply { bounded.forEach { put(it.key.toString(), it.value) } }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(KEY_NAMES, json.toString())
            .apply()
    }
}
