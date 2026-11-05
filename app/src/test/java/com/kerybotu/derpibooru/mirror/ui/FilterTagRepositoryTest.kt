package com.kerybotu.derpibooru.mirror.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FilterTagRepositoryTest {
    @Test
    fun idQueryUsesUniquePositiveIdsInBatches() {
        assertEquals("id:12345 || id:67890", FilterTagRepository.buildIdQuery(listOf(12345, 67890, 12345, 0, -1)))
        assertEquals("", FilterTagRepository.buildIdQuery(listOf(0, -4)))
    }

    @Test
    fun parserMapsOnlyRequestedIdsToCanonicalServerNames() {
        val json = """{"tags":[
            {"id":12345,"name":"artist:apple bloom","aliases":["applebloom"]},
            {"id":67890,"name":"safe"},
            {"id":55555,"name":"unrequested"},
            {"id":12346,"name":""}
        ]}"""

        assertEquals(
            mapOf(12345 to "artist:apple bloom", 67890 to "safe"),
            FilterTagRepository.parseSearchResponse(json, setOf(12345, 67890, 12346))
        )
    }

    @Test
    fun malformedResponseProducesNoMappings() {
        assertEquals(emptyMap<Int, String>(), FilterTagRepository.parseSearchResponse("not-json", setOf(12345)))
    }
}
