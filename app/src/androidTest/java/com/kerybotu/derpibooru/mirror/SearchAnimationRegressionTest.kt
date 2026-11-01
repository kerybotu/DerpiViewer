package com.kerybotu.derpibooru.mirror

import android.content.Intent
import android.graphics.drawable.Animatable
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.kerybotu.derpibooru.mirror.ui.IosActivityIndicator
import com.kerybotu.derpibooru.mirror.ui.SearchActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the search loader starts after attach and can be restarted. */
@RunWith(AndroidJUnit4::class)
class SearchAnimationRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun searchIndicatorStartsAfterAttachAndRestart() {
        val context = instrumentation.targetContext
        var page: SearchActivity? = null
        try {
            instrumentation.runOnMainSync {
                context.startActivity(Intent(context, SearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            val deadline = SystemClock.uptimeMillis() + 10_000L
            while (page == null && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    page = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<SearchActivity>()
                        .firstOrNull()
                }
                if (page == null) Thread.sleep(50)
            }
            val activity = page ?: error("SearchActivity did not resume")
            val indicatorField = SearchActivity::class.java.getDeclaredField("loadingIndicator").apply { isAccessible = true }
            val setLoading = SearchActivity::class.java.getDeclaredMethod("setSearchLoading", Boolean::class.javaPrimitiveType!!).apply { isAccessible = true }
            instrumentation.runOnMainSync { setLoading.invoke(activity, true) }
            awaitRunning(indicatorField.get(activity) as IosActivityIndicator)
            instrumentation.runOnMainSync { setLoading.invoke(activity, false); setLoading.invoke(activity, true) }
            awaitRunning(indicatorField.get(activity) as IosActivityIndicator)
        } finally {
            instrumentation.runOnMainSync { page?.finish() }
        }
    }

    private fun awaitRunning(indicator: IosActivityIndicator) {
        val deadline = SystemClock.uptimeMillis() + 3_000L
        while (SystemClock.uptimeMillis() < deadline) {
            var running = false
            instrumentation.runOnMainSync {
                running = (indicator.indeterminateDrawable as? Animatable)?.isRunning == true
            }
            if (running) return
            Thread.sleep(50)
        }
        assertTrue("Search loading indicator did not start after attach/restart", false)
    }
}
