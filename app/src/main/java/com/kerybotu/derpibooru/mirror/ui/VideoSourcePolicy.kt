package com.kerybotu.derpibooru.mirror.ui

import java.net.URI

/** Only the verified Derpicdn Philomena image paths may imply an MP4 representation. */
internal object VideoSourcePolicy {
    fun candidates(representations: Map<String, String>, width: Int, height: Int): List<String> {
        val bounded = maxOf(width, height) > 1920
        val originals = (if (bounded) listOf("medium", "large", "full") else listOf("large", "full", "medium"))
            .mapNotNull { representations[it]?.takeIf(String::isNotBlank) }.distinct()
        return buildList {
            representations["mp4"]?.takeIf(String::isNotBlank)?.let(::add)
            originals.mapNotNullTo(this, ::verifiedMp4Candidate)
            addAll(originals)
        }.distinct()
    }

    fun verifiedMp4Candidate(url: String): String? = runCatching {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.host != "derpicdn.net" || uri.userInfo != null || uri.port != -1) return null
        if (!uri.path.matches(Regex("/img/(?:view/)?\\d{4}/\\d{1,2}/\\d{1,2}/\\d+(?:/(?:full|large|medium|small|thumb))?\\.webm"))) return null
        // Preserve the exact escaped query (including any access parameters).
        val extension = url.indexOf(".webm", url.indexOf(uri.rawPath))
        url.replaceRange(extension, extension + 5, ".mp4")
    }.getOrNull()

    /**
     * Returns a URL that can be handed to the image loader as a real preview.
     * The API sometimes exposes a `small` representation for a video. That
     * representation is still a video stream (usually `.webm`), not a poster
     * image. Inventing a `.gif` URL for it produces a 404, so callers keep
     * their normal placeholder when no image representation exists.
     */
    fun previewCandidate(url: String?): String? = url?.takeIf { value ->
        runCatching {
            val path = URI(value).path.orEmpty().lowercase()
            // Keep extensionless CDN poster URLs usable, but never send a
            // known video stream to Glide as if it were a bitmap.
            listOf(".webm", ".mp4", ".mkv", ".mov", ".m4v", ".avi").none(path::endsWith)
        }.getOrDefault(false)
    }

    fun acceptsContentType(type: String): Boolean = type.substringBefore(';').lowercase().trim().let {
        it.startsWith("video/") || it == "application/octet-stream" || it == "binary/octet-stream"
    }
}
