package com.kerybotu.derpibooru.mirror.favorites

internal object FavoriteMembership {
    fun toggle(items: MutableList<FavoriteItem>, item: FavoriteItem): Boolean {
        val removed = items.removeAll { it.imageId == item.imageId }
        if (removed) return false
        items.add(0, item)
        return true
    }
}
