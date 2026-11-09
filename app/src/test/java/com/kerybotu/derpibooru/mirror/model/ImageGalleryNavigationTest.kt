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
}
