package com.kerybotu.derpibooru.mirror.ui

import com.kerybotu.derpibooru.mirror.model.Filter
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

/** Parses the server's filters page without exposing its HTML to the UI. */
object FilterHtmlParser {
    data class Result(
        val filters: List<Filter>,
        val nextPage: Int? = null,
        val loginRequired: Boolean = false,
        val challengePage: Boolean = false
    )

    fun parse(html: String, allowedDomain: String, currentPage: Int = 1): Result {
        val document = Jsoup.parse(html)
        val bodyText = document.body()?.text().orEmpty()
        val normalized = bodyText.lowercase()
        val challenge = document.select("form[action*=/anubis], #anubis_challenge, script[src*=anubis]").isNotEmpty() ||
            normalized.contains("anubis challenge")
        val login = document.select("form[action*=/login], input[name=password]").isNotEmpty() ||
            normalized.contains("log in") && normalized.contains("password")
        if (challenge || login) return Result(emptyList(), loginRequired = login, challengePage = challenge)

        val filters = document.select(".filter").mapNotNull { parseCard(it, allowedDomain) }
        val next = document.select("a[rel=next], .pagination a, a[href]")
            .mapNotNull { safePath(it.attr("href"), allowedDomain) }
            .mapNotNull { path -> Regex("(?:^|[?&])page=(\\d+)").find(path)?.groupValues?.get(1)?.toIntOrNull() }
            .filter { it > currentPage }
            .minOrNull()
        return Result(filters, nextPage = next)
    }

    private fun parseCard(card: Element, allowedDomain: String): Filter? {
        val viewPath = card.select("a[href]").mapNotNull { safePath(it.attr("href"), allowedDomain) }
            .firstOrNull { Regex("^/filters/(\\d+)$").matches(it) }
        val id = viewPath?.let { Regex("^/filters/(\\d+)$").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() } ?: return null
        val name = clean(card.selectFirst("h3")?.text()).ifBlank { "未命名过滤器" }
        val owner = card.select("p strong a[href]").firstOrNull { it.attr("href").contains("/profiles/") }
        val ownerPath = owner?.let { safePath(it.attr("href"), allowedDomain) }
        val creator = ownerPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: clean(owner?.text()).removeSuffix("维护").removeSuffix("maintained by").trim().ifBlank { null }
        val options = card.selectFirst(".filter-options")
        val optionsText = clean(options?.text())
        val spoilerCount = findCount(optionsText, SPOILERS) ?: -1
        val hiddenCount = findCount(optionsText, HIDES) ?: -1
        val copyPath = options?.select("a[href]")?.mapNotNull { safePath(it.attr("href"), allowedDomain) }
            ?.firstOrNull { it.startsWith("/filters/new?") }
        val editPath = options?.select("a[href]")?.mapNotNull { safePath(it.attr("href"), allowedDomain) }
            ?.firstOrNull { Regex("^/filters/\\d+/edit$").matches(it) }
        val useAction = options?.selectFirst("form[action]")?.let { safePath(it.attr("action"), allowedDomain) }
        val description = clean(options?.selectFirst("p em")?.text())
        return Filter(
            id = id,
            name = name,
            description = description,
            userId = null,
            system = false,
            public = true,
            spoilerCount = spoilerCount,
            hiddenCount = hiddenCount,
            creator = creator,
            sourceId = id,
            sourceDomain = allowedDomain,
            ownerProfilePath = ownerPath,
            remoteViewPath = viewPath,
            remoteCopyPath = copyPath,
            remoteEditPath = editPath,
            remoteUseAction = useAction
        )
    }

    private fun clean(value: String?): String = value.orEmpty().replace(WHITESPACE, " ").trim()

    private fun findCount(text: String, patterns: List<Regex>): Int? = patterns.firstNotNullOfOrNull { pattern ->
        pattern.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun safePath(raw: String?, allowedDomain: String): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank() || value.startsWith("#") || value.startsWith("javascript:", true)) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.isAbsolute && !uri.host.equals(allowedDomain, true)) return null
        val path = uri.path?.takeIf { it.startsWith("/") } ?: return null
        return buildString {
            append(path)
            uri.rawQuery?.let { append('?').append(it) }
        }
    }

    private val WHITESPACE = Regex("\\s+")
    private val SPOILERS = listOf(
        Regex("(?i)(?:spoilers?|剧透)\\s*[:：]?\\s*(\\d+)")
    )
    private val HIDES = listOf(
        Regex("(?i)(?:hides?|hidden|隐藏)\\s*[:：]?\\s*(\\d+)")
    )
}
