package com.kerybotu.derpibooru.mirror.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageGalleryNavigationTest {
    @Test
    fun swipeDirectionMatchesGalleryNavigationForSuppliedSearchOrder() {
        val ids = intArrayOf(91, 17, 203, 44, 8)

        assertEquals(17, ImageGalleryNavigation.adjacentId(ids, currentId = 91, direction = ImageGalleryNavigation.NEXT))
        assertEquals(203, ImageGalleryNavigation.adjacentId(ids, currentId = 17, direction = ImageGalleryNavigation.NEXT))
        assertEquals(17, ImageGalleryNavigation.adjacentId(ids, currentId = 203, direction = ImageGalleryNavigation.PREVIOUS))
        assertEquals(ImageGalleryNavigation.PREVIOUS, ImageGalleryNavigation.directionForSwipe(deltaX = 120f))
        assertEquals(ImageGalleryNavigation.NEXT, ImageGalleryNavigation.directionForSwipe(deltaX = -120f))
    }

    @Test
    fun unknownCurrentImageAndListBoundariesNeverJumpToAnotherSource() {
        val ids = intArrayOf(91, 17, 203)

        assertNull(ImageGalleryNavigation.adjacentId(ids, currentId = 999, direction = ImageGalleryNavigation.NEXT))
        assertNull(ImageGalleryNavigation.adjacentId(ids, currentId = 91, direction = ImageGalleryNavigation.PREVIOUS))
        assertNull(ImageGalleryNavigation.adjacentId(ids, currentId = 203, direction = ImageGalleryNavigation.NEXT))
    }

    @Test
    fun duplicateIdsAreCollapsedWithoutChangingFirstOccurrenceOrder() {
        assertEquals(intArrayOf(91, 17, 203).toList(), ImageGalleryNavigation.normalize(intArrayOf(91, 17, 91, 203)).toList())
    }

    @Test
    fun idOrderedFeedUsesDescendingOrderForNextAndOppositeAnimationDirections() {
        assertEquals("lt" to "desc", ImageGalleryNavigation.idOrderQuery(ImageGalleryNavigation.NEXT))
        assertEquals("gt" to "asc", ImageGalleryNavigation.idOrderQuery(ImageGalleryNavigation.PREVIOUS))
        assertEquals(-1, ImageGalleryNavigation.exitTranslationSign(ImageGalleryNavigation.NEXT))
        assertEquals(1, ImageGalleryNavigation.exitTranslationSign(ImageGalleryNavigation.PREVIOUS))
    }

    @Test
    fun releaseUsesOneDistanceOrVelocityDecisionAndCancelNeverCommits() {
        assertEquals(null, ImageGalleryNavigation.releaseDirection(-30f, -700f, 400f))
        assertEquals(ImageGalleryNavigation.NEXT, ImageGalleryNavigation.releaseDirection(-90f, 0f, 400f))
        assertEquals(ImageGalleryNavigation.NEXT, ImageGalleryNavigation.releaseDirection(-30f, -950f, 400f))
        assertEquals(ImageGalleryNavigation.PREVIOUS, ImageGalleryNavigation.releaseDirection(30f, 950f, 400f))
        assertEquals(null, ImageGalleryNavigation.releaseDirection(-200f, -1500f, 400f, cancelled = true))
    }

    @Test
    fun galleryPreviewKeepsAdjacentThumbnailReadyForTheTransition() {
        val images = listOf(
            Image(9, "", "thumb-9", 0, 0, 0, 0, 0, 0, 0, emptyList()),
            Image(10, "", "thumb-10", 0, 0, 0, 0, 0, 0, 0, emptyList(), mimeType = "image/png"),
            Image(11, "", "thumb-11", 0, 0, 0, 0, 0, 0, 0, emptyList(), mimeType = "image/jpeg", spoilered = true),
            Image(12, "", "thumb-12", 0, 0, 0, 0, 0, 0, 0, emptyList())
        )

        val previews = GalleryImagePreview.nearby(images, currentId = 10, radius = 1)
        val adjacentId = ImageGalleryNavigation.adjacentId(intArrayOf(10, 11, 12), 10, ImageGalleryNavigation.NEXT)
        val next = previews.first { it.id == adjacentId }.toImage()

        assertEquals(11, next.id)
        assertEquals("thumb-11", next.thumbnailUrl)
        assertEquals("image/jpeg", next.mimeType)
        assertEquals(true, next.spoilered)
        assertEquals(listOf(9, 10, 11), previews.map { it.id })
    }
}
