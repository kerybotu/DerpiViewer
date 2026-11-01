package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import com.kerybotu.derpibooru.mirror.auth.ApiKeyStore
import com.kerybotu.derpibooru.mirror.model.Filter
import com.kerybotu.derpibooru.mirror.model.FilterJsonCodec
import com.kerybotu.derpibooru.mirror.model.FilterSearchCondition
import com.kerybotu.derpibooru.mirror.model.FilterSearchField
import com.kerybotu.derpibooru.mirror.model.FilterSearchQuery
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import com.kerybotu.derpibooru.mirror.network.ApiRequestDiagnostic

/** Uses the documented JSON endpoints, then applies the same field semantics locally. */
object FilterSearchClient {
    data class Result(val filters: List<Filter>, val page: Int, val hasMore: Boolean)

    suspend fun search(context: Context, query: String, page: Int = 1): Result {
        val conditions = FilterSearchQuery.parse(query)
        val id = conditions.singleOrNull { it.field == FilterSearchField.ID }
            ?.value?.toIntOrNull()
        if (id != null && id > 0) {
            val json = request(context, "filters/$id") ?: return Result(emptyList(), page, false)
            return Result(listOfNotNull(FilterJsonCodec.parseResponse(json)), page, false)
        }

        val wantsMine = conditions.any { it.field == FilterSearchField.MY && it.value.equals("filters", true) }
        if (wantsMine && !ApiKeyStore.isLoggedIn(context)) throw LoginRequiredException()

        val endpoint = if (wantsMine) "filters/user?page=$page" else "filters/system?page=$page"
        val first = request(context, endpoint)
        val candidates = FilterJsonCodec.parseList(first.orEmpty()).toMutableList()
        // The API has no general /filters search endpoint. When authenticated,
        // include the user's own filters so public:false and creator/user_id
        // queries are not silently limited to system filters.
        if (!wantsMine && ApiKeyStore.isLoggedIn(context)) {
            val user = request(context, "filters/user?page=$page")
            FilterJsonCodec.parseList(user.orEmpty()).forEach { filter ->
                if (candidates.none { it.id == filter.id }) candidates += filter
            }
        }
        val filtered = candidates.filter { matches(it, conditions) }
        return Result(filtered, page, candidates.size >= PAGE_SIZE)
    }

    suspend fun getById(context: Context, id: Int): Filter? {
        if (id <= 0) return null
        return request(context, "filters/$id")?.let(FilterJsonCodec::parseResponse)
    }

    fun matches(filter: Filter, conditions: List<FilterSearchCondition>): Boolean = conditions.all { condition ->
        val value = condition.value.trim()
        when (condition.field) {
            FilterSearchField.NAME -> filter.name.contains(value, ignoreCase = true)
            FilterSearchField.CREATOR -> filter.creator?.contains(value, true) == true
            FilterSearchField.DESCRIPTION -> filter.description.contains(value, true)
            FilterSearchField.CREATED_AT -> filter.createdAt?.contains(value, true) == true
            FilterSearchField.ID -> compareNumber(filter.id.toDouble(), value)
            FilterSearchField.SPOILERED_COUNT -> compareNumber(filter.spoilerCount.toDouble(), value)
            FilterSearchField.HIDDEN_COUNT -> compareNumber(filter.hiddenCount.toDouble(), value)
            FilterSearchField.MY -> value.equals("filters", ignoreCase = true)
            FilterSearchField.SYSTEM -> filter.system == value.toBooleanStrictOrNull()
            FilterSearchField.PUBLIC -> filter.public == value.toBooleanStrictOrNull()
            FilterSearchField.USER_ID -> filter.userId?.toString() == value
        }
    }

    private fun compareNumber(actual: Double, expression: String): Boolean {
        val value = expression.trim()
        val range = Regex("^(-?\\d+(?:\\.\\d+)?)\\.\\.(-?\\d+(?:\\.\\d+)?)$").matchEntire(value)
        if (range != null) return actual in range.groupValues[1].toDouble()..range.groupValues[2].toDouble()
        val comparator = Regex("^(<=|>=|<|>|=)?\\s*(-?\\d+(?:\\.\\d+)?)$").matchEntire(value) ?: return false
        val number = comparator.groupValues[2].toDouble()
        return when (comparator.groupValues[1]) {
            "<" -> actual < number
            "<=" -> actual <= number
            ">" -> actual > number
            ">=" -> actual >= number
            else -> actual == number
        }
    }

    class LoginRequiredException : IllegalStateException("login_required")

    class RequestFailedException(val diagnostic: ApiRequestDiagnostic) :
        IllegalStateException(diagnostic.summary())

    private suspend fun request(context: Context, path: String): String? {
        val result = NetworkManager.getApiWithDiagnostics(context, path)
        if (result.body == null && result.failure != null) throw RequestFailedException(result.failure)
        return result.body
    }

    private const val PAGE_SIZE = 50
}
