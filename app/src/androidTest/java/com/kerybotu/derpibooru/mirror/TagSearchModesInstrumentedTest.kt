package com.kerybotu.derpibooru.mirror

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.kerybotu.derpibooru.mirror.model.TagSearchMode
import com.kerybotu.derpibooru.mirror.ui.TagSearchActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TagSearchModesInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun modeSwitchChangesSourceAndOnlyFilterModeShowsParameterHelper() {
        val context = instrumentation.targetContext
        var activity: TagSearchActivity? = null
        try {
            instrumentation.runOnMainSync {
                context.startActivity(Intent(context, TagSearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            val deadline = SystemClock.uptimeMillis() + 10_000L
            while (activity == null && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<TagSearchActivity>().firstOrNull()
                }
                if (activity == null) Thread.sleep(50)
            }
            val page = activity ?: error("TagSearchActivity did not resume")
            val modeField = TagSearchActivity::class.java.getDeclaredField("mode").apply { isAccessible = true }
            val selectorField = TagSearchActivity::class.java.getDeclaredField("modeSelector").apply { isAccessible = true }
            val parameterField = TagSearchActivity::class.java.getDeclaredField("parameterButton").apply { isAccessible = true }
            val selector = selectorField.get(page) as LinearLayout
            val parameter = parameterField.get(page) as View
            instrumentation.runOnMainSync {
                assertEquals(TagSearchMode.LOCAL_TAG, modeField.get(page))
                assertEquals(View.GONE, parameter.visibility)
                selector.getChildAt(1).performClick()
                assertEquals(TagSearchMode.REMOTE_TAG, modeField.get(page))
                assertEquals(View.GONE, parameter.visibility)
                selector.getChildAt(2).performClick()
                assertEquals(TagSearchMode.REMOTE_FILTER, modeField.get(page))
                assertEquals(View.VISIBLE, parameter.visibility)
                selector.getChildAt(0).performClick()
                assertEquals(TagSearchMode.LOCAL_TAG, modeField.get(page))
                assertFalse("Parameter helper must be hidden outside filter search", parameter.visibility == View.VISIBLE)
            }
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
        }
    }
}
