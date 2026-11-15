package com.kerybotu.derpibooru.mirror.ui

/** API/HTTP metadata wins over CDN filenames (including extensionless signed URLs). */
internal object MediaTypePolicy {
    fun normalize(mime: String?): String? = mime?.substringBefore(';')?.trim()?.lowercase()
        ?.takeIf { it.isNotEmpty() && it != "null" }
    fun isVideo(mime: String?): Boolean = normalize(mime)?.startsWith("video/") == true
    fun isGif(mime: String?): Boolean = normalize(mime) == "image/gif"
}
