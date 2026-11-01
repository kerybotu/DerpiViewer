package com.kerybotu.derpibooru.mirror.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterQueryTest {
    @Test fun serializesAllServerFieldKinds() {
        val query = FilterSearchQuery.serialize(listOf(
            FilterSearchCondition(FilterSearchField.NAME, "default"),
            FilterSearchCondition(FilterSearchField.CREATOR, "AppleDash"),
            FilterSearchCondition(FilterSearchField.DESCRIPTION, "the show's rating"),
            FilterSearchCondition(FilterSearchField.CREATED_AT, "2015"),
            FilterSearchCondition(FilterSearchField.ID, ">=1"),
            FilterSearchCondition(FilterSearchField.SPOILERED_COUNT, "1..6"),
            FilterSearchCondition(FilterSearchField.HIDDEN_COUNT, "1"),
            FilterSearchCondition(FilterSearchField.MY, "filters"),
            FilterSearchCondition(FilterSearchField.SYSTEM, "true"),
            FilterSearchCondition(FilterSearchField.PUBLIC, "false"),
            FilterSearchCondition(FilterSearchField.USER_ID, "307505")
        ))
        assertEquals(
            "name:default creator:AppleDash description:\"the show's rating\" created_at:2015 id:>=1 spoilered_count:1..6 hidden_count:1 my:filters system:true public:false user_id:307505",
            query
        )
    }

    @Test fun parsesUnquotedDescriptionAndRoundTrips() {
        val parsed = FilterSearchQuery.parse("description:the show's rating system:true")
        assertEquals("the show's rating", parsed[0].value)
        assertEquals("description:\"the show's rating\" system:true", FilterSearchQuery.normalize("description:the show's rating system:true"))
    }

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
