package com.kerybotu.derpibooru.mirror.model

/** Navigation rules for an ordered image result set. */
object ImageGalleryNavigation {
    const val PREVIOUS = -1
    const val NEXT = 1

    fun normalize(ids: IntArray): IntArray = ids.asSequence()
        .filter { it > 0 }
        .distinct()
        .toList()
        .toIntArray()

    /** A finger moving right reveals the previous item, matching Android gallery conventions. */
    fun directionForSwipe(deltaX: Float): Int = if (deltaX >= 0f) PREVIOUS else NEXT

    /** Home and other ID-sorted feeds are displayed newest-first. */
    fun idOrderQuery(direction: Int): Pair<String, String>? = when (direction) {
        NEXT -> "lt" to "desc"
        PREVIOUS -> "gt" to "asc"
        else -> null
    }

    /** NEXT leaves to the left; PREVIOUS leaves to the right. */
    fun exitTranslationSign(direction: Int): Int = when (direction) {
        NEXT -> -1
        PREVIOUS -> 1
        else -> 0
    }

    fun releaseDirection(
        deltaX: Float,
        velocityX: Float,
        width: Float,
        distanceFraction: Float = 0.22f,
        velocityThreshold: Float = 900f,
        cancelled: Boolean = false
    ): Int? {
        if (cancelled) return null
        if (kotlin.math.abs(deltaX) >= width * distanceFraction) {
            return if (deltaX < 0f) NEXT else PREVIOUS
        }
        if (kotlin.math.abs(velocityX) >= velocityThreshold) {
            return if (velocityX < 0f) NEXT else PREVIOUS
        }
        return null
    }

    fun adjacentId(ids: IntArray, currentId: Int, direction: Int): Int? {
        if (direction != PREVIOUS && direction != NEXT) return null
        val normalized = normalize(ids)
        val index = normalized.indexOf(currentId)
        if (index < 0) return null
        return normalized.getOrNull(index + direction)
    }
}
