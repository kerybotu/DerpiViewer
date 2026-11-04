package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import android.util.Log
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.auth.ApiKeyStore
import com.kerybotu.derpibooru.mirror.model.Filter
import com.kerybotu.derpibooru.mirror.model.FilterJsonCodec
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import com.kerybotu.derpibooru.mirror.network.ApiRequestDiagnostic
import com.kerybotu.derpibooru.mirror.network.SharedCookieJar

/** Searches the site's HTML filters page using its `fq` query parameter. */
object FilterSearchClient {
    private const val TAG = "FilterSearch"
    data class Result(val filters: List<Filter>, val page: Int, val hasMore: Boolean)

    suspend fun search(context: Context, query: String, page: Int = 1): Result {
        val normalizedQuery = query.trim()
        Log.d(TAG, "search_start mode=REMOTE_FILTER query=${redactQuery(normalizedQuery)} page=$page cookies=${cookieNames(context).joinToString(",").ifBlank { "none" }}")
        if (Regex("(?i)(^|\\s)my:filters(?:\\s|$)").containsMatchIn(normalizedQuery) && !ApiKeyStore.isLoggedIn(context)) {
            throw LoginRequiredException()
        }
        val params = linkedMapOf<String, String>().apply {
            normalizedQuery.takeIf { it.isNotEmpty() }?.let { put("fq", it) }
            if (page > 1) put("page", page.toString())
        }
        val requestUrl = NetworkManager.sitePageUrl(context, "/filters", params)
        Log.d(TAG, "request url=${safeUrl(requestUrl)} fq=${redactQuery(normalizedQuery)}")
        val result = NetworkManager.getSitePageWithDiagnostics(context, "/filters", params)
        val html = result.body ?: run {
            Log.w(TAG, "request_failed diagnostic=${result.failure?.summary() ?: "unknown"}")
            throw RequestFailedException(result.failure ?: clientFailure(context))
        }
        val bodySummary = FilterHtmlParser.pageSummary(html)
        Log.d(TAG, "response_ok bytes=${html.length} type=${bodySummary.type} filters=${bodySummary.filterCount} challenge=${bodySummary.challenge} login=${bodySummary.login} title=${bodySummary.title} bodyCategory=${bodySummary.bodyCategory} markers=${bodySummary.markers} cookies=${cookieNames(context).joinToString(",").ifBlank { "none" }}")
        val parsed = FilterHtmlParser.parse(html, AppSettings.getTargetDomain(context), page)
        if (parsed.loginRequired) throw LoginRequiredException()
        if (parsed.challengePage) throw HtmlPageException("challenge_page")
        if (parsed.accessDenied) {
            Log.w(TAG, "response_rejected category=access_denied filters=0")
            throw HtmlPageException("access_denied")
        }
        Log.d(TAG, "parse_complete filters=${parsed.filters.size} nextPage=${parsed.nextPage ?: "none"}")
        return Result(parsed.filters, page, parsed.nextPage != null && parsed.nextPage > page)
    }

    private fun redactQuery(query: String): String = query.replace(Regex("[\\r\\n]"), " ").take(160)
    private fun safeUrl(url: String): String = runCatching {
        val parsed = android.net.Uri.parse(url)
        parsed.buildUpon().clearQuery().apply {
            parsed.getQueryParameter("fq")?.let { appendQueryParameter("fq", redactQuery(it)) }
            parsed.getQueryParameter("page")?.let { appendQueryParameter("page", it) }
        }.build().toString()
    }.getOrDefault("https://derpibooru.org/filters")
    private fun cookieNames(context: Context): Set<String> = runCatching {
        SharedCookieJar.cookieNames("https://${AppSettings.getTargetDomain(context)}/")
    }.getOrDefault(emptySet())

    suspend fun getById(context: Context, id: Int): Filter? {
        if (id <= 0) return null
        return request(context, "filters/$id")?.let(FilterJsonCodec::parseResponse)
    }

    class LoginRequiredException : IllegalStateException("login_required")

    class HtmlPageException(message: String) : IllegalStateException(message)

    class RequestFailedException(val diagnostic: ApiRequestDiagnostic) :
        IllegalStateException(diagnostic.summary())

    private suspend fun request(context: Context, path: String): String? {
        val result = NetworkManager.getApiWithDiagnostics(context, path)
        if (result.body == null && result.failure != null) throw RequestFailedException(result.failure)
        return result.body
    }

    private fun clientFailure(context: Context): ApiRequestDiagnostic = ApiRequestDiagnostic(
        requestId = "filters",
        operation = "site:filters",
        host = AppSettings.getTargetDomain(context),
        phase = "client",
        kind = com.kerybotu.derpibooru.mirror.network.ApiFailureKind.UNKNOWN,
        elapsedMs = 0,
        attempt = 1,
        maxAttempts = 1,
        networkConfigurationVersion = NetworkManager.configurationVersion.value,
        retryable = true,
        rootCause = "empty_html_response"
    )
}
