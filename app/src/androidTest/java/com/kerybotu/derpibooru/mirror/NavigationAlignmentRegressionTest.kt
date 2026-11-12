package com.kerybotu.derpibooru.mirror

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.kerybotu.derpibooru.mirror.ui.AdaptiveLayoutPolicy
import com.kerybotu.derpibooru.mirror.ui.HomeGlassTabBar
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Measures real laid-out controls, using the real feed rather than mocked media. */
@RunWith(AndroidJUnit4::class)
class NavigationAlignmentRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val evidence = File(context.getExternalFilesDir(null), "navigation-alignment").apply { mkdirs() }
    private val config = context.resources.configuration
    private val name = "${config.screenWidthDp}x${config.screenHeightDp}-d${config.densityDpi}"

    @Test
    fun originalTabsAndContentRelativeAlignment() {
        instrumentation.runOnMainSync {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        lateinit var main: MainActivity
        await("MainActivity resumed") {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().firstOrNull()
            if (activity != null) main = activity
            activity != null && activity.findViewById<View>(R.id.toolbar).height > 0
        }
        select(main, 0)
        settle()
        val report = StringBuilder("window=${config.screenWidthDp}x${config.screenHeightDp}dp density=${config.densityDpi}\n")
        instrumentation.runOnMainSync {
            val toolbar = main.findViewById<Toolbar>(R.id.toolbar)
            val menu = children(toolbar).filterIsInstance<ImageButton>().first()
            val island = main.findViewById<View>(R.id.app_bar_layout)
            assertCenter("home menu / toolbar", bounds(menu), bounds(toolbar))
            assertCenter("toolbar / island", bounds(toolbar), bounds(island))
            for (id in listOf(R.id.btn_search, R.id.btn_density)) {
                assertCenter("toolbar action", bounds(main.findViewById(id)), bounds(toolbar))
            }
            report.append("home island=${bounds(island)} toolbar=${bounds(toolbar)} menu=${bounds(menu)}\n")
            val bar = main.findViewById<HomeGlassTabBar>(R.id.glass_bottom_navigation)
            val row = bar.getChildAt(0) as ViewGroup
            assertEquals(5, row.childCount)
            assertEquals(listOf("首页", "视频", "热门", "评论", "我的"),
                (0 until row.childCount).map { i -> children(row.getChildAt(i)).filterIsInstance<TextView>().single().text.toString() })
        }
        screenshot("home")

        select(main, 1)
        await("real video controls loaded", 90_000) {
            main.findViewById<View?>(R.id.video_action_rail)?.let { it.isShown && it.height > 0 } == true
        }
        settle()
        instrumentation.runOnMainSync {
            val top = main.findViewById<View>(R.id.video_top_island)
            val content = top.parent as View
            val safe = ViewCompat.getRootWindowInsets(content)!!.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            assertEquals("top island consumes safe top exactly once", bounds(content).top + safe.top, bounds(top).top)
            val rail = main.findViewById<ViewGroup>(R.id.video_action_rail)
            val page = rail.parent as View
            val pageBounds = bounds(page)
            val nav = main.findViewById<HomeGlassTabBar>(R.id.glass_bottom_navigation)
            val spec = AdaptiveLayoutPolicy.specFor(context, main.findViewById(android.R.id.content))
            val effectiveBottom = if (spec.navigationMode == AdaptiveLayoutPolicy.NavigationMode.BOTTOM_BAR) {
                bounds(nav).top
            } else pageBounds.bottom - safe.bottom
            val effective = Rect(pageBounds.left, pageBounds.top + safe.top, pageBounds.right, effectiveBottom)
            assertCenter("video rail / usable content", bounds(rail), effective)
            assertTrue("rail above bottom occupied area", bounds(rail).bottom <= effectiveBottom)
            assertTrue("rail below safe top", bounds(rail).top >= effective.top)
            val scroll = rail.getChildAt(0) as ViewGroup
            val buttons = scroll.getChildAt(0)
            if (!scroll.canScrollVertically(1)) {
                assertCenter("button group / rail", bounds(buttons), bounds(rail))
            } else {
                assertTrue("overflow is scrollable", buttons.height > scroll.height)
                scroll.scrollTo(0, buttons.height)
                assertTrue("last button reachable", bounds(main.findViewById(R.id.video_more)).bottom <= bounds(rail).bottom)
                scroll.scrollTo(0, 0)
            }
            report.append("video content=${bounds(content)} top=${bounds(top)} safe=$safe\n")
            report.append("video page=$pageBounds usable=$effective rail=${bounds(rail)} buttons=${bounds(buttons)} nav=${bounds(nav)}\n")
        }
        screenshot("video")

        // Exercise each original cell and the real destination/selected state.
        val ids = listOf(R.id.tab_home, R.id.tab_video_feed, R.id.tab_featured, R.id.tab_messages, R.id.tab_profile)
        for (index in listOf(2, 3, 4, 0, 1)) {
            select(main, index)
            instrumentation.runOnMainSync {
                val selected = MainActivity::class.java.getDeclaredField("selectedPrimaryId").apply { isAccessible = true }.getInt(main)
                assertEquals("destination for tab $index", ids[index], selected)
                val bar = main.findViewById<HomeGlassTabBar>(R.id.glass_bottom_navigation)
                assertEquals("selected index", index, bar.selectedIndex)
                val row = bar.getChildAt(0) as ViewGroup
                for (i in 0 until 5) assertEquals("cell selection $i", i == index, row.getChildAt(i).isSelected)
            }
        }
        report.append("original five labels, destinations and selected states: PASS\n")
        File(evidence, "$name-bounds.txt").writeText(report.toString())
    }

    private fun select(activity: Activity, index: Int) {
        instrumentation.runOnMainSync {
            val bar = activity.findViewById<HomeGlassTabBar>(R.id.glass_bottom_navigation)
            (bar.getChildAt(0) as ViewGroup).getChildAt(index).performClick()
        }
        settle()
    }

    private fun assertCenter(label: String, actual: Rect, area: Rect) {
        assertTrue("$label: actual=$actual area=$area", abs(actual.exactCenterY() - area.exactCenterY()) <= 1.5f)
    }

    private fun bounds(view: View): Rect {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    private fun children(view: View): List<View> = buildList {
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            add(child)
            addAll(children(child))
        }
    }

    private fun await(label: String, timeout: Long = 20_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            Thread.sleep(150)
        }
        fail("Timed out: $label")
    }

    private fun settle() = Thread.sleep(700)

    private fun screenshot(page: String) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(evidence, "$name-$page.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
