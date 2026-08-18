package com.kerybotu.derpibooru.mirror.dict

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TagEntry(val englishName: String, val chineseName: String, val priority: Int, val imageCount: Int, val aliases: List<String>)

/** In-memory read-only snapshot of the bundled 2024 tag export. */
object TagDictionary {
    @Volatile private var entries: List<TagEntry>? = null
    private val lock = Any()

    private suspend fun all(context: Context): List<TagEntry> = withContext(Dispatchers.IO) {
        entries ?: synchronized(lock) {
            entries ?: load(context.applicationContext).also { entries = it }
        }
    }

    private fun load(context: Context): List<TagEntry> {
        val result = ArrayList<TagEntry>()
        runCatching {
            context.assets.open("derpibooru_tag.csv").bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    val fields = parseDelimited(line, '\t')
                    if (fields.size < 4) return@forEach
                    val english = fields[0].trim()
                    if (english.isBlank()) return@forEach
                    val aliases = parseDelimited(fields.getOrNull(4).orEmpty(), ',').map { it.trim() }.filter { it.isNotBlank() }
                    result += TagEntry(english, fields[1].trim(), fields[2].trim().toIntOrNull() ?: 0, fields[3].trim().toIntOrNull() ?: 0, aliases)
                }
            }
        }
        return result
    }

    /** Handles quoted fields and doubled quotes in the tab-separated export. */
    private fun parseDelimited(line: String, delimiter: Char): List<String> {
        val result = ArrayList<String>(); val field = StringBuilder(); var quoted = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"') {
                if (quoted && i + 1 < line.length && line[i + 1] == '"') { field.append('"'); i++ }
                else quoted = !quoted
            } else if (c == delimiter && !quoted) { result += field.toString(); field.setLength(0) }
            else field.append(c)
            i++
        }
        result += field.toString()
        return result
    }

    suspend fun search(context: Context, query: String): List<TagEntry> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val all = all(context)
        val chinese = q.any { it in '\u4e00'..'\u9fff' }
        val normalized = q.lowercase().replace(' ', '_')
        return all.asSequence().filter { entry ->
            if (chinese) entry.chineseName.contains(q) || entry.aliases.any { it.contains(q, ignoreCase = true) }
            else normalize(entry.englishName).startsWith(normalized) || entry.aliases.any { normalize(it).startsWith(normalized) }
        }.sortedWith(tagComparator()).take(20).toList()
    }

    suspend fun sortAndTranslate(context: Context, tags: List<String>): List<TagEntry> {
        val all = all(context).associateBy { normalize(it.englishName) }
        return tags.map { name -> all[normalize(name)] ?: TagEntry(name, name, -1, 0, emptyList()) }
            .sortedWith(tagComparator())
    }

    private fun normalize(value: String): String = value.trim().lowercase().replace(Regex("\\s+"), "_")

    private fun tagComparator(): Comparator<TagEntry> = compareByDescending<TagEntry> { it.priority == 5 }
        .thenByDescending { it.priority }
        .thenByDescending { it.imageCount }
}
