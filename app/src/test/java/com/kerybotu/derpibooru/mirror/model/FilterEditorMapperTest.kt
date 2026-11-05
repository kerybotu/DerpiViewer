package com.kerybotu.derpibooru.mirror.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FilterEditorMapperTest {
    @Test
    fun appliesAllEditableFieldsAndKeepsImportedFilterIdentity() {
        val remote = Filter(
            id = 215340,
            name = "Original",
            description = "Original description",
            userId = 307505,
            system = false,
            public = true,
            spoilerCount = 1,
            hiddenCount = 2,
            spoileredTagIds = listOf(12),
            hiddenTagIds = listOf(23, 34),
            spoileredComplex = "old spoiler",
            hiddenComplex = "old hide",
            sourceId = 215340,
            sourceDomain = "derpibooru.org"
        )

        val saved = FilterEditorMapper.apply(
            remote,
            newLocalId = -99,
            name = "Custom",
            description = "Updated",
            spoileredTagIds = listOf(101, 101),
            spoileredComplex = "(drama || mouthpiece)",
            hiddenTagIds = listOf(202),
            hiddenComplex = "score.lte:-50\nmy:upvotes",
            localQuery = "safe, -explicit",
            defaultSourceDomain = "other.example"
        )

        assertEquals(-99, saved.id)
        assertEquals(215340, saved.sourceId)
        assertEquals("derpibooru.org", saved.sourceDomain)
        assertEquals("Custom", saved.name)
        assertEquals("Updated", saved.description)
        assertEquals(listOf(101), saved.spoileredTagIds)
        assertEquals("(drama || mouthpiece)", saved.spoileredComplex)
        assertEquals(listOf(202), saved.hiddenTagIds)
        assertEquals("score.lte:-50\nmy:upvotes", saved.hiddenComplex)
        assertEquals("safe, -explicit", saved.localQuery)
        assertEquals(1, saved.spoilerCount)
        assertEquals(1, saved.hiddenCount)
        assertEquals(true, saved.isLocal)
    }

    @Test
    fun editingLocalFilterKeepsItsLocalIdAndAllowsEmptyComplexRules() {
        val local = Filter(-7, "Old", "", null, false, false, 0, 0, isLocal = true, sourceId = 4)
        val saved = FilterEditorMapper.apply(
            local,
            newLocalId = -99,
            name = "New",
            description = "",
            spoileredTagIds = emptyList(),
            spoileredComplex = "",
            hiddenTagIds = emptyList(),
            hiddenComplex = "",
            localQuery = "",
            defaultSourceDomain = null
        )
        assertEquals(-7, saved.id)
        assertEquals(4, saved.sourceId)
        assertEquals(null, saved.spoileredComplex)
        assertEquals(null, saved.hiddenComplex)
        assertEquals(null, saved.localQuery)
    }
}
