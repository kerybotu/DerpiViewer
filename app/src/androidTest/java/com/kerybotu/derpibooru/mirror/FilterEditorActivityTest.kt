package com.kerybotu.derpibooru.mirror

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kerybotu.derpibooru.mirror.model.Filter
import com.kerybotu.derpibooru.mirror.model.FilterJsonCodec
import com.kerybotu.derpibooru.mirror.ui.FilterEditorActivity
import com.kerybotu.derpibooru.mirror.ui.FilterStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FilterEditorActivityTest {
    @Test
    fun explicitSavePersistsEditedFieldsAndKeepsServerSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceId = 1_987_654_320
        val imported = Filter(
            id = sourceId,
            name = "Before",
            description = "Before description",
            userId = null,
            system = false,
            public = true,
            spoilerCount = 0,
            hiddenCount = 0,
            sourceId = sourceId,
            sourceDomain = "derpibooru.org"
        )
        val intent = android.content.Intent(context, FilterEditorActivity::class.java)
            .putExtra(FilterEditorActivity.EXTRA_FILTER_JSON, FilterJsonCodec.toJson(imported).toString())

        try {
            ActivityScenario.launch<FilterEditorActivity>(intent).use {
                onView(withHint("过滤器名称")).perform(replaceText("Edited copy"))
                onView(withHint("描述")).perform(replaceText("Updated locally"))
                onView(withHint("复杂剧透过滤器")).perform(scrollTo(), replaceText("(drama || mouthpiece)"))
                onView(withHint("复杂隐藏过滤器")).perform(scrollTo(), replaceText("score.lte:-50\nmy:upvotes"))
                onView(withHint("包含标签（逗号或换行分隔）")).perform(scrollTo(), replaceText("safe"))
                onView(withHint("排除标签（逗号或换行分隔）")).perform(scrollTo(), replaceText("explicit"))
                onView(withText("保存本地过滤器")).perform(scrollTo(), click())
            }

            val saved = FilterStore.findBySourceId(context, sourceId)
            assertEquals("Edited copy", saved?.name)
            assertEquals("Updated locally", saved?.description)
            assertEquals("(drama || mouthpiece)", saved?.spoileredComplex)
            assertEquals("score.lte:-50\nmy:upvotes", saved?.hiddenComplex)
            assertEquals("safe -explicit", saved?.localQuery)
            assertEquals(sourceId, saved?.sourceId)
            assertEquals("derpibooru.org", saved?.sourceDomain)
        } finally {
            FilterStore.findBySourceId(context, sourceId)?.let { FilterStore.deleteLocalFilter(context, it) }
        }
    }
}
