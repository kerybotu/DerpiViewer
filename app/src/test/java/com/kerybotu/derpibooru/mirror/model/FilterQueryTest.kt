package com.kerybotu.derpibooru.mirror.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterQueryTest {
    @Test fun customQueryDeduplicatesAndEscapesTags() {
        assertEquals("safe \"big breasts\" -nsfw", CustomFilterQueryBuilder.serialize(listOf(
            CustomFilterCondition("safe"), CustomFilterCondition("safe"),
            CustomFilterCondition("big breasts"), CustomFilterCondition("nsfw", true)
        )))
    }

    @Test fun emptyConditionsProduceEmptyQuery() {
        assertTrue(CustomFilterQueryBuilder.serialize(emptyList()).isEmpty())
    }

    @Test fun customQueryCanBeEditedAfterReload() {
        assertEquals(
            listOf(
                CustomFilterCondition("safe"),
                CustomFilterCondition("spoiler warning", excluded = true),
                CustomFilterCondition("artist:foo", excluded = true)
            ),
            CustomFilterQueryBuilder.parse("safe -\"spoiler warning\" -artist:foo")
        )
    }
}
