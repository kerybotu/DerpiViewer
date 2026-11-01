package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.auth.ApiKeyStore
import com.kerybotu.derpibooru.mirror.model.Filter
import com.kerybotu.derpibooru.mirror.model.FilterJsonCodec
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import com.kerybotu.derpibooru.mirror.network.ApiRequestDiagnostic

/** Searches the site's HTML filters page using its `fq` query parameter. */
object FilterSearchClient {
    data class Result(val filters: List<Filter>, val page: Int, val hasMore: Boolean)

    suspend fun search(context: Context, query: String, page: Int = 1): Result {
        if (Regex("(?i)(^|\\s)my:filters(?:\\s|$)").containsMatchIn(query) && !ApiKeyStore.isLoggedIn(context)) {
            throw LoginRequiredException()
        }
        val params = linkedMapOf<String, String>().apply {
            query.trim().takeIf { it.isNotEmpty() }?.let { put("fq", it) }
            if (page > 1) put("page", page.toString())
        }
        val result = NetworkManager.getSitePageWithDiagnostics(context, "/filters", params)
        val html = result.body ?: throw RequestFailedException(result.failure ?: clientFailure(context))
        val parsed = FilterHtmlParser.parse(html, AppSettings.getTargetDomain(context), page)
        if (parsed.loginRequired) throw LoginRequiredException()
        if (parsed.challengePage) throw HtmlPageException("challenge_page")
        return Result(parsed.filters, page, parsed.nextPage != null && parsed.nextPage > page)
    }

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
