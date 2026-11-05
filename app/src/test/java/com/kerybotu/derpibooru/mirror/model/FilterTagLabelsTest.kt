package com.kerybotu.derpibooru.mirror.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FilterTagLabelsTest {
    @Test
    fun preservesOrderAndShowsUnknownIdsWhenNamesAreMissing() {
        assertEquals(
            listOf("artist:apple bloom", "未知标签（ID: 67890）", "未知标签（ID: 13579）"),
            FilterTagLabels.display(listOf(12345, 67890, 13579), mapOf(12345 to "artist:apple bloom"))
        )
    }

    @Test
    fun emptyIdsProduceNoLabels() {
        assertEquals(emptyList<String>(), FilterTagLabels.display(emptyList(), emptyMap()))
    }
}
