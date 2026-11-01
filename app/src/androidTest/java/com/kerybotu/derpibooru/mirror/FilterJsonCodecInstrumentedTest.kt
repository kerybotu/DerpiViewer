package com.kerybotu.derpibooru.mirror

import com.kerybotu.derpibooru.mirror.model.FilterJsonCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FilterJsonCodecInstrumentedTest {
    @Test fun preservesCompleteRemoteConfiguration() {
        val filter = FilterJsonCodec.parseResponse("""{"filter":{"id":215340,"name":"public","description":"desc","public":true,"system":false,"user_id":7,"spoilered_tag_ids":[1,2],"hidden_tag_ids":[3],"spoilered_complex":"a","hidden_complex":"b"}}""")
        assertNotNull(filter)
        requireNotNull(filter)
        assertEquals(listOf(1, 2), filter.spoileredTagIds)
        assertEquals(listOf(3), filter.hiddenTagIds)
        assertEquals("a", filter.spoileredComplex)
        assertEquals("b", filter.hiddenComplex)
        assertEquals(2, filter.spoilerCount)
        assertEquals(1, filter.hiddenCount)
    }
}
