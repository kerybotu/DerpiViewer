package com.kerybotu.derpibooru.mirror.model

data class FilterSearchInsertion(val text: String, val cursor: Int)

/** Pure insertion rules used by the compact parameter helper. */
object FilterSearchInput {
    fun insert(current: String, selectionStart: Int, selectionEnd: Int, value: String, cursorOffset: Int): FilterSearchInsertion {
        val start = selectionStart.takeIf { it in 0..current.length } ?: current.length
        val end = selectionEnd.takeIf { it in start..current.length } ?: start
        val before = current.take(start)
        val after = current.drop(end)
        val prefix = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
        val suffix = if (after.isNotEmpty() && !after.first().isWhitespace()) " " else ""
        val inserted = prefix + value + suffix
        val result = before + inserted + after
        val cursor = (start + prefix.length + cursorOffset).coerceIn(0, result.length)
        return FilterSearchInsertion(result, cursor)
    }
}
