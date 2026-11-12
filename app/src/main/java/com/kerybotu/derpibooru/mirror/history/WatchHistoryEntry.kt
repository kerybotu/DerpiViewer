package com.kerybotu.derpibooru.mirror.history

import com.kerybotu.derpibooru.mirror.model.Image

data class WatchHistoryEntry(
    val imageId: Int,
    val detailUrl: String,
    val thumbnailUrl: String?,
    val title: String,
    val tags: String,
    val mediaType: String,
    val firstWatchedAt: Long,
    val lastWatchedAt: Long,
    val watchCount: Int,
    val fullUrl: String?,
    val width: Int,
    val height: Int,
    val mimeType: String?
) {
    fun toImage(): Image = Image(
        id = imageId, title = title, thumbnailUrl = thumbnailUrl, width = width, height = height,
        score = 0, faves = 0, upvotes = 0, downvotes = 0, commentCount = 0,
        tags = tags.split(" ").filter { it.isNotBlank() }, fullUrl = fullUrl,
        mimeType = mimeType
    )
}
