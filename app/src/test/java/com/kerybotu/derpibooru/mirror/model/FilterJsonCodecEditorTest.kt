package com.kerybotu.derpibooru.mirror.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONObject

class FilterJsonCodecEditorTest {
    @Test
    fun parseResponseRestoresLocalIdentityFromEditorIntentJson() {
        val original = Filter(
            id = -12,
            name = "Local",
            description = "",
            userId = null,
            system = false,
            public = false,
            spoilerCount = 0,
            hiddenCount = 0,
            sourceId = 215340,
            sourceDomain = "derpibooru.org",
            localQuery = "safe",
            isLocal = true
        )

        val restored = FilterJsonCodec.parseResponse(FilterJsonCodec.toJson(original).toString())

        assertEquals(original, restored)
    }

    @Test
    fun roundTripsAllEditorFieldsAndImportedSourceIdentity() {
        val original = Filter(
            id = -12,
            name = "Only explicit",
            description = "Personal copy",
            userId = 307505,
            system = false,
            public = false,
            spoilerCount = 2,
            hiddenCount = 1,
            spoileredTagIds = listOf(12345, 67890),
            hiddenTagIds = listOf(13579),
            spoileredComplex = "(drama || mouthpiece)",
            hiddenComplex = "score.lte:-50\nmy:upvotes",
            sourceId = 215340,
            sourceDomain = "derpibooru.org",
            localQuery = "safe, -explicit",
            isLocal = true
        )

        val restored = FilterJsonCodec.parse(JSONObject(FilterJsonCodec.toJson(original).toString()), local = true)

        assertEquals(original, restored)
    }

    @Test
    fun readsOlderLocalJsonWithoutNewFields() {
        val restored = FilterJsonCodec.parseResponse(
            """{"id":-3,"name":"Legacy","description":"","local_query":"safe","is_local":true}"""
        )
        assertEquals(-3, restored?.id)
        assertEquals("safe", restored?.localQuery)
        assertEquals(emptyList<Int>(), restored?.spoileredTagIds)
        assertEquals(emptyList<Int>(), restored?.hiddenTagIds)
        assertNull(restored?.spoileredComplex)
        assertNull(restored?.hiddenComplex)
    }
}
