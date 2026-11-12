package com.kerybotu.derpibooru.mirror.ui

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** A bounded recursive inline parser. Output is data, never executable HTML. */
object RichTextParser {
    data class Mark(val start: Int, val end: Int, val kind: String, val target: String? = null)
    data class Document(val text: String, val marks: List<Mark>)
    private val delimiters = linkedMapOf("**" to "bold", "__" to "underline", "~~" to "strike", "||" to "spoiler", "*" to "italic", "^" to "sup", "~" to "sub")
    fun safeUrl(value: String): String? = value.takeIf {
        (it.startsWith("https://", true) || it.startsWith("http://", true) || it.startsWith("/") && !it.startsWith("//")) && it.none(Char::isISOControl)
    }
    fun parse(raw: String): Document {
        val text = raw.take(200_000)
        if (Regex("</?(?:p|div|a|strong|em|br|span|blockquote|script|style)(?:\\s|>|/)", RegexOption.IGNORE_CASE).containsMatchIn(text)) return parseHtml(text)
        val out = StringBuilder(); val marks = mutableListOf<Mark>()
        fun scan(start: Int, end: Int, depth: Int) {
            var i = start
            while (i < end) {
                if (text[i] == '\\' && i + 1 < end) { out.append(text[i + 1]); i += 2; continue }
                if (text[i] == '`') {
                    val close = text.indexOf('`', i + 1).takeIf { it in (i + 1) until end }
                    if (close != null) { val s = out.length; out.append(text.substring(i + 1, close)); marks += Mark(s, out.length, "code"); i = close + 1; continue }
                }
                if (text.startsWith("[[", i)) {
                    val close = text.indexOf("]]", i + 2).takeIf { it in (i + 2) until end }
                    if (close != null) { val tag = text.substring(i + 2, close); val s = out.length; out.append(tag); marks += Mark(s, out.length, "tag", tag); i = close + 2; continue }
                }
                if (text[i] == '[' && depth < 24) {
                    val middle = text.indexOf("](", i + 1).takeIf { it in (i + 1) until end }
                    val close = middle?.let { text.indexOf(')', it + 2).takeIf { c -> c > it && c < end } }
                    if (middle != null && close != null) {
                        val url = safeUrl(text.substring(middle + 2, close))
                        if (url != null) { val s = out.length; scan(i + 1, middle, depth + 1); marks += Mark(s, out.length, "link", url); i = close + 1; continue }
                    }
                }
                if (text.startsWith(">>", i) && i + 2 < end && text[i + 2].isDigit()) {
                    var close = i + 2; while (close < end && text[close].isDigit()) close++
                    val id = text.substring(i + 2, close); val s = out.length; out.append(text.substring(i, close)); marks += Mark(s, out.length, "link", "/images/$id"); i = close; continue
                }
                if (text.startsWith("https://", i, true) || text.startsWith("http://", i, true)) {
                    var close = i; while (close < end && !text[close].isWhitespace() && text[close] !in "<>\"'") close++
                    while (close > i && text[close - 1] in ".,;!?)") close--
                    val s = out.length; val url = text.substring(i, close); out.append(url); marks += Mark(s, out.length, "link", url); i = close; continue
                }
                val delimiter = if (depth < 24) delimiters.keys.firstOrNull { text.startsWith(it, i) } else null
                if (delimiter != null) {
                    val close = text.indexOf(delimiter, i + delimiter.length).takeIf { it >= i + delimiter.length && it < end }
                    if (close != null && close > i + delimiter.length && text.substring(i + delimiter.length, close).isNotBlank()) {
                        val s = out.length; scan(i + delimiter.length, close, depth + 1); marks += Mark(s, out.length, delimiters.getValue(delimiter)); i = close + delimiter.length; continue
                    }
                }
                out.append(text[i++])
            }
        }
        scan(0, text.length, 0)
        return Document(out.toString(), marks)
    }
    private fun parseHtml(raw: String): Document {
        val out = StringBuilder(); val marks = mutableListOf<Mark>()
        fun walk(node: Node, depth: Int) {
            if (depth > 32) return
            if (node is TextNode) { out.append(node.wholeText); return }
            if (node !is Element || node.normalName() in setOf("script", "style", "iframe", "object")) return
            val name = node.normalName()
            if (name == "br") { out.append('\n'); return }
            val start = out.length
            node.childNodes().forEach { walk(it, depth + 1) }
            val kind = when (name) { "b", "strong" -> "bold"; "i", "em" -> "italic"; "u" -> "underline"; "s", "del" -> "strike"; "sup" -> "sup"; "sub" -> "sub"; "code", "pre" -> "code"; "a" -> "link"; "span" -> if (node.hasClass("spoiler")) "spoiler" else null; else -> null }
            val target = if (kind == "link") safeUrl(node.attr("href")) else null
            if (kind != null && (kind != "link" || target != null)) marks += Mark(start, out.length, kind, target)
            if (name in setOf("p", "div", "blockquote", "pre", "li") && out.isNotEmpty() && out.last() != '\n') out.append('\n')
        }
        walk(Jsoup.parseBodyFragment(raw).body(), 0)
        return Document(out.toString(), marks)
    }
}
