package com.kerybotu.derpibooru.mirror.favorites

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteMembershipTest {
    @Test
    fun toggleAddsThenRemovesWithoutDuplicatingAnImage() {
        val items = mutableListOf<FavoriteItem>()
        val item = FavoriteItem(42, "thumb", "image/png")

        assertTrue(FavoriteMembership.toggle(items, item))
        assertFalse(FavoriteMembership.toggle(items, item))
        assertTrue(FavoriteMembership.toggle(items, item.copy(thumbnailUrl = "updated")))
        assertFalse(FavoriteMembership.toggle(items, item))
        assertTrue(items.isEmpty())
    }
}
