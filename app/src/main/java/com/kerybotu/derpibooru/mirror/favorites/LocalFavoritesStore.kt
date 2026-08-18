package com.kerybotu.derpibooru.mirror.favorites

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class FavoriteFolder(val id: Long, var name: String, val isDefault: Boolean, val items: MutableList<FavoriteItem> = mutableListOf())
data class FavoriteItem(val imageId: Int, val thumbnailUrl: String?, val format: String?)

/** Small JSON-backed store for offline local favorites. */
class LocalFavoritesStore(context: Context) {
    private val prefs = context.getSharedPreferences("local_favorites", Context.MODE_PRIVATE)
    private val folders = mutableListOf<FavoriteFolder>()
    init { load(); if (folders.none { it.isDefault }) { folders.add(FavoriteFolder(1L, "全部收藏", true)); save() } }
    fun allFolders(): List<FavoriteFolder> = folders.toList()
    fun createFolder(name: String): FavoriteFolder { val folder = FavoriteFolder((folders.maxOfOrNull { it.id } ?: 0) + 1, name.trim().ifBlank { "新收藏夹" }, false); folders.add(folder); save(); return folder }
    fun renameFolder(folderId: Long, name: String) { folders.firstOrNull { it.id == folderId && !it.isDefault }?.let { it.name = name.trim().ifBlank { it.name }; save() } }
    fun deleteFolder(folderId: Long) { folders.removeAll { it.id == folderId && !it.isDefault }; save() }
    fun add(folderId: Long, item: FavoriteItem) { folders.firstOrNull { it.id == folderId }?.items?.removeAll { it.imageId == item.imageId }; folders.firstOrNull { it.id == folderId }?.items?.add(0, item); save() }
    fun remove(folderId: Long, imageId: Int) { folders.firstOrNull { it.id == folderId }?.items?.removeAll { it.imageId == imageId }; save() }
    private fun load() { runCatching { JSONArray(prefs.getString("folders", "[]")) }.getOrNull()?.let { array -> for (i in 0 until array.length()) { val o = array.optJSONObject(i) ?: continue; val f = FavoriteFolder(o.optLong("id"), o.optString("name"), o.optBoolean("default")); val items = o.optJSONArray("items") ?: JSONArray(); for (j in 0 until items.length()) { val item = items.optJSONObject(j) ?: continue; f.items.add(FavoriteItem(item.optInt("id"), item.optString("url").ifBlank { null }, item.optString("format").ifBlank { null })) }; folders.add(f) } } }
    private fun save() { val array = JSONArray(); folders.forEach { f -> val o = JSONObject().put("id", f.id).put("name", f.name).put("default", f.isDefault); val items = JSONArray(); f.items.forEach { items.put(JSONObject().put("id", it.imageId).put("url", it.thumbnailUrl ?: "").put("format", it.format ?: "")) }; o.put("items", items); array.put(o) }; prefs.edit().putString("folders", array.toString()).apply() }
}
