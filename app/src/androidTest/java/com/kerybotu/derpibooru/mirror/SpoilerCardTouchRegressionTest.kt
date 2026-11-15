package com.kerybotu.derpibooru.mirror

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.kerybotu.derpibooru.mirror.model.Image
import com.kerybotu.derpibooru.mirror.ui.ImageAdapter
import com.kerybotu.derpibooru.mirror.ui.ImageDetailActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpoilerCardTouchRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun realSpoilerButtonTouchRevealsAndCardBodyStillNavigatesAfterReuse() {
        val context = instrumentation.targetContext
        AppSettings.setSpoilerDisplayMode(context, AppSettings.SpoilerDisplayMode.CLICK_TO_SHOW)
        instrumentation.runOnMainSync {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val activity = awaitActivity<MainActivity>()
        val adapter = MainActivity::class.java.getDeclaredField("adapter").apply { isAccessible = true }
            .get(activity) as ImageAdapter
        val fixtures = (1..24).map { index ->
            Image(index, "touch fixture $index", null, 400, 400, 0, 0, 0, 0, 0, emptyList(), spoilered = true)
        }
        instrumentation.runOnMainSync { adapter.updateData(fixtures) }
        val list = activity.findViewById<RecyclerView>(R.id.recycler_view)
        await("first spoiler card attached") { list.childCount > 0 && find(list.getChildAt(0), R.id.thumbnail_spoiler_cover)?.visibility == View.VISIBLE }

        var card = list.getChildAt(0)
        var cover = requireNotNull(find(card, R.id.thumbnail_spoiler_cover))
        var button = findByText(cover, "显示")
        assertNotNull("reveal button must exist in CLICK_TO_SHOW", button)
        assertTrue("bound image item must remain clickable", card.isClickable)
        val initialBounds = bounds(cover)
        val inset = (32 * context.resources.displayMetrics.density).toInt()
        tap(initialBounds.left + inset.toFloat(), initialBounds.top + inset.toFloat())
        await("initial card body touch opens detail") { resumedActivityOnMain() is ImageDetailActivity }
        instrumentation.runOnMainSync { (resumedActivityOnMain() as? ImageDetailActivity)?.finish() }
        await("return to list") { resumedActivityOnMain() is MainActivity }
        await("spoiler cover rebound after returning") { cover.visibility == View.VISIBLE }
        card = list.getChildAt(0)
        cover = requireNotNull(find(card, R.id.thumbnail_spoiler_cover))
        button = findByText(cover, "显示")
        tap(button!!)
        await("button touch hides only the cover") { cover.visibility == View.GONE }
        assertEquals("button must not navigate to detail", MainActivity::class.java, resumedActivity()!!::class.java)

        instrumentation.runOnMainSync { list.scrollToPosition(12) }
        await("recycled spoiler card attached") { list.childCount > 0 && find(list.getChildAt(0), R.id.thumbnail_spoiler_cover)?.visibility == View.VISIBLE }
        instrumentation.runOnMainSync { list.scrollToPosition(0) }
        await("first card rebound after holder reuse") {
            list.childCount > 0 && find(list.getChildAt(0), R.id.thumbnail_spoiler_cover)?.visibility == View.VISIBLE
        }
        card = list.getChildAt(0)
        cover = requireNotNull(find(card, R.id.thumbnail_spoiler_cover))
        val bodyBounds = bounds(cover)
        tap(bodyBounds.left + inset.toFloat(), bodyBounds.top + inset.toFloat())
        await("card body touch opens matching detail") { resumedActivityOnMain() is ImageDetailActivity }
        assertTrue("body tap should use the card navigation path", resumedActivity() is ImageDetailActivity)
    }

    private fun tap(view: View) {
        val rect = bounds(view)
        tap(rect.exactCenterX(), rect.exactCenterY())
    }

    private fun tap(x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0).apply {
            source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        }
        val up = MotionEvent.obtain(downTime, downTime + 90, MotionEvent.ACTION_UP, x, y, 0).apply {
            source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        }
        try {
            assertTrue(instrumentation.uiAutomation.injectInputEvent(down, true))
            assertTrue(instrumentation.uiAutomation.injectInputEvent(up, true))
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun bounds(view: View): Rect {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    private fun find(root: View, id: Int): View? {
        if (root.id == id) return root
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) find(root.getChildAt(index), id)?.let { return it }
        return null
    }

    private fun findByText(root: View, text: String): View? {
        if (root is android.widget.TextView && root.text.toString() == text) return root
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) findByText(root.getChildAt(index), text)?.let { return it }
        return null
    }

    private inline fun <reified T : Activity> awaitActivity(): T {
        await("${T::class.java.simpleName} resumed") { resumedActivityOnMain() is T }
        return resumedActivity() as T
    }

    private fun resumedActivity(): Activity? {
        var activity: Activity? = null
        instrumentation.runOnMainSync { activity = resumedActivityOnMain() }
        return activity
    }

    private fun resumedActivityOnMain(): Activity? = ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(Stage.RESUMED).firstOrNull()

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            Thread.sleep(100)
        }
        throw AssertionError("Timed out: $label")
    }
}
