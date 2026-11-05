package com.kerybotu.derpibooru.mirror

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kerybotu.derpibooru.mirror.model.Filter
import com.kerybotu.derpibooru.mirror.ui.FilterStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FilterStoreEditorTest {
    @Test
    fun savingAndReloadingImportedCopyPreservesRulesWithoutChangingActiveFilter() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val activeIdBefore = AppSettings.getCurrentFilterId(context)
        val activeLocalIdBefore = AppSettings.getCurrentLocalFilterId(context)
        val sourceId = 1_987_654_321
        val filter = Filter(
            id = -1_987_654_321,
            name = "Editor round trip",
            description = "Temporary instrumentation fixture",
            userId = null,
            system = false,
            public = true,
            spoilerCount = 1,
            hiddenCount = 2,
            spoileredTagIds = listOf(12345),
            hiddenTagIds = listOf(67890, 13579),
            spoileredComplex = "(drama || mouthpiece)",
            hiddenComplex = "score.lte:-50",
            sourceId = sourceId,
            sourceDomain = "derpibooru.org",
            localQuery = "safe, -explicit",
            isLocal = true
        )

        try {
            FilterStore.saveLocalFilter(context, filter)
            val restored = FilterStore.findBySourceId(context, sourceId)
            assertNotEquals(null, restored)
            assertEquals(filter, restored)
            assertEquals(activeIdBefore, AppSettings.getCurrentFilterId(context))
            assertEquals(activeLocalIdBefore, AppSettings.getCurrentLocalFilterId(context))
        } finally {
            FilterStore.deleteLocalFilter(context, filter)
        }
    }
}
