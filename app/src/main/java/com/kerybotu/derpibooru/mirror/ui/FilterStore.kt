package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import com.kerybotu.derpibooru.mirror.model.Filter
import com.kerybotu.derpibooru.mirror.model.FilterJsonCodec
import org.json.JSONArray

/** Persistent store for local query presets and complete remote imports. */
object FilterStore {
    private const val PREFS = "filter_cache"
    private const val KEY_LOCAL = "local_filters_v2"

    fun getLocalFilters(context: Context): List<Filter> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LOCAL, null) ?: return emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { FilterJsonCodec.parse(it, local = true) }
        }
    }.getOrDefault(emptyList())

    fun saveLocalFilter(context: Context, filter: Filter): List<Filter> {
        val current = getLocalFilters(context).toMutableList()
        val key = filter.sourceId ?: filter.id
        val index = current.indexOfFirst { (it.sourceId ?: it.id) == key }
        if (index >= 0) current[index] = filter.copy(isLocal = true) else current += filter.copy(isLocal = true)
        persist(context, current)
        return current
    }

    fun deleteLocalFilter(context: Context, filter: Filter): List<Filter> {
        val key = filter.sourceId ?: filter.id
        val current = getLocalFilters(context).filterNot { (it.sourceId ?: it.id) == key }
        persist(context, current)
        return current
    }

    fun findBySourceId(context: Context, sourceId: Int): Filter? = getLocalFilters(context)
        .firstOrNull { it.sourceId == sourceId }

    private fun persist(context: Context, filters: List<Filter>) {
        val array = JSONArray()
        filters.forEach { array.put(FilterJsonCodec.toJson(it)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LOCAL, array.toString()).apply()
    }
}
