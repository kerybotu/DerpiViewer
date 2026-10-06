package com.kerybotu.derpibooru.mirror.model

import org.json.JSONObject

/** Normalized comment payload shared by every native comment surface. */
data class Comment(
    val id: Int,
    val author: String,
    val body: String,
    val createdAt: String,
    val userId: Long? = null,
    val avatarUrl: String? = null,
    val imageId: Int? = null
) {
    companion object {
        fun fromJson(json: JSONObject): Comment = Comment(
            id = json.optInt("id", -1),
            author = json.optString("author", "匿名用户").ifBlank { "匿名用户" },
            body = json.optString("body", ""),
            createdAt = json.optString("created_at", ""),
            userId = json.optLong("user_id", -1L).takeIf { it > 0L },
            avatarUrl = json.optString("avatar_url", "").takeIf { it.isNotBlank() },
            imageId = json.optInt("image_id", -1).takeIf { it > 0 }
        )
    }
}
