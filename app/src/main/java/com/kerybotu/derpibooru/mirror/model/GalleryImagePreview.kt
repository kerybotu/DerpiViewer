package com.kerybotu.derpibooru.mirror.model

import java.io.Serializable

/** Small, transfer-safe data needed to paint adjacent gallery pages immediately. */
data class GalleryImagePreview(
    val id: Int,
    val thumbnailUrl: String?,
    val mimeType: String?,
    val spoilered: Boolean
) : Serializable {
    fun toImage(): Image = Image(
        id = id,
        title = "",
        thumbnailUrl = thumbnailUrl,
        width = 0,
        height = 0,
        score = 0,
        faves = 0,
        upvotes = 0,
        downvotes = 0,
        commentCount = 0,
        tags = emptyList(),
        mimeType = mimeType,
        spoilered = spoilered
    )

    companion object {
        fun from(image: Image) = GalleryImagePreview(image.id, image.thumbnailUrl, image.mimeType, image.spoilered)

        fun nearby(images: List<Image>, currentId: Int, radius: Int = 3): ArrayList<GalleryImagePreview> {
            val index = images.indexOfFirst { it.id == currentId }
            if (index < 0) return arrayListOf()
            return ArrayList(images.subList((index - radius).coerceAtLeast(0), (index + radius + 1).coerceAtMost(images.size)).map(::from))
        }
    }
}
