package com.kerybotu.derpibooru.mirror

import com.kerybotu.derpibooru.mirror.model.TagSearchMode
import com.kerybotu.derpibooru.mirror.model.TagSearchRouting
import org.junit.Assert.assertEquals
import org.junit.Test

class TagSearchRoutingTest {
    @Test
    fun eachSourceHasAnIndependentHandler() {
        assertEquals(TagSearchRouting.Handler.LOCAL_DICTIONARY, TagSearchRouting.handler(TagSearchMode.LOCAL_TAG))
        assertEquals(TagSearchRouting.Handler.IMAGE_SEARCH, TagSearchRouting.handler(TagSearchMode.REMOTE_TAG))
        assertEquals(TagSearchRouting.Handler.FILTER_HTML, TagSearchRouting.handler(TagSearchMode.REMOTE_FILTER))
    }
}
