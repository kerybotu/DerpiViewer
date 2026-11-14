package com.kerybotu.derpibooru.mirror

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.View
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.kerybotu.derpibooru.mirror.model.Image
import com.kerybotu.derpibooru.mirror.ui.DetailInfoState
import com.kerybotu.derpibooru.mirror.ui.DetailStatistics
import com.kerybotu.derpibooru.mirror.ui.ImageDetailActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetailCompactStatisticsAndLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun statisticsBindAndActionPanelMovesBetweenResponsiveParentsAtOriginalSize() {
        var page = launch()
        try {
            onMain {
                val stats = field(page, "compactStatistics") as View
                val bind = stats.javaClass.getDeclaredMethod("bind", DetailStatistics::class.java, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                bind.invoke(stats, DetailStatistics(12_300, 45_600, 789), false)
                val labels = (0 until (stats as LinearLayout).childCount).map { stats.getChildAt(it) as android.widget.TextView }
                assertEquals(listOf("12.3K", "45.6K", "789"), labels.map { it.text.toString() })
                assertEquals(listOf("点赞 12300", "收藏 45600", "评论 789"), labels.map { it.contentDescription.toString() })

                bind.invoke(stats, null, true)
                assertEquals(listOf("\u2026", "\u2026", "\u2026"), labels.map { it.text.toString() })
                bind.invoke(stats, null, false)
                assertEquals(listOf("\u2014", "\u2014", "\u2014"), labels.map { it.text.toString() })
                bind.invoke(stats, DetailStatistics(12_300, 45_600, 789), false)
                ImageDetailActivity::class.java.getDeclaredMethod("bindInitial").apply { isAccessible = true }.invoke(page)
                assertEquals("Switching images clears the prior sample and returns to loading",
                    listOf("\u2026", "\u2026", "\u2026"), labels.map { it.text.toString() })
                bind.invoke(stats, DetailStatistics(12_300, 45_600, 789), false)
            }
            instrumentation.waitForIdleSync()
            onMain {
                val stats = field(page, "compactStatistics")
                stats.javaClass.getDeclaredMethod("bind", DetailStatistics::class.java, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(stats, DetailStatistics(12_300, 45_600, 789), false)
            }
            capture("detail-portrait-compact")

            page.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            page = awaitResumed(Configuration.ORIENTATION_LANDSCAPE)
            onMain {
                val apply = ImageDetailActivity::class.java.getDeclaredMethod("applyResponsiveDetailLayout").apply { isAccessible = true }
                apply.invoke(page)
                val binding = field(page, "b")
                val panel = binding.javaClass.getField("detailInteractionPanel").get(binding) as View
                val author = binding.javaClass.getField("detailAuthorPanel").get(binding) as View
                val content = binding.javaClass.getField("detailContent").get(binding) as View
                val row = (panel as android.view.ViewGroup).getChildAt(0) as LinearLayout
                assertSame("Landscape interaction island belongs to the scrolling detail content", content, panel.parent)
                assertSame("Landscape author information scrolls in the same content area", content, author.parent)
                assertEquals("The compact state hides actions without reserving a sheet slot", View.GONE, panel.visibility)
                assertEquals(LinearLayout.LayoutParams.MATCH_PARENT, (panel.layoutParams as LinearLayout.LayoutParams).width)
                assertEquals(LinearLayout.LayoutParams.MATCH_PARENT, (row.layoutParams as android.widget.FrameLayout.LayoutParams).width)
                assertTrue("Existing local-favourite action remains attached", page.findViewById<View>(R.id.detail_faves).hasOnClickListeners())
                assertTrue("Existing comments action remains attached", page.findViewById<View>(R.id.detail_comments_count).hasOnClickListeners())
                assertEquals(3, (0 until row.childCount).map { row.getChildAt(it) }.count { it.visibility == View.VISIBLE })
                assertFalse("No interaction placeholder remains in the fixed sheet header",
                    (binding.javaClass.getField("bottomSheet").get(binding) as LinearLayout).let { sheet ->
                        (0 until sheet.childCount).any { sheet.getChildAt(it) === panel || sheet.getChildAt(it) === author }
                    })

                val card = field(page, "infoCard")
                card.javaClass.getMethod("snap", DetailInfoState::class.java, Boolean::class.javaPrimitiveType)
                    .invoke(card, DetailInfoState.EXPANDED, false)
                apply.invoke(page)
                assertEquals(View.VISIBLE, panel.visibility)
            }
            instrumentation.waitForIdleSync()
            onMain {
                val binding = field(page, "b")
                val panel = binding.javaClass.getField("detailInteractionPanel").get(binding) as View
                val minActionHeight = (56 * page.resources.displayMetrics.density).toInt()
                assertTrue("Restored full-size action row must retain its normal height", panel.height >= minActionHeight)
            }
            capture("detail-landscape-expanded")

            page.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            page = awaitResumed(Configuration.ORIENTATION_PORTRAIT)
            onMain {
                val apply = ImageDetailActivity::class.java.getDeclaredMethod("applyResponsiveDetailLayout").apply { isAccessible = true }
                apply.invoke(page)
                val binding = field(page, "b")
                val panel = binding.javaClass.getField("detailInteractionPanel").get(binding) as View
                val sheet = binding.javaClass.getField("bottomSheet").get(binding) as LinearLayout
                val author = binding.javaClass.getField("detailAuthorPanel").get(binding) as View
                assertSame("Portrait interaction island retains its original sheet parent", sheet, panel.parent)
                assertSame("Portrait author panel retains its original sheet parent", sheet, author.parent)
                assertEquals(2, sheet.indexOfChild(panel))
                assertEquals(LinearLayout.LayoutParams.MATCH_PARENT, (panel.layoutParams as LinearLayout.LayoutParams).width)
            }
        } finally {
            onMain { page.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; page.finish() }
        }
    }

    private fun launch(): ImageDetailActivity {
        val image = Image(4321, "layout test", null, 0, 0, 0, 0, 0, 0, 0, emptyList())
        val intent = Intent(context, ImageDetailActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("image", image)
        onMain { context.startActivity(intent) }
        return awaitResumed()
    }

    private fun awaitResumed(orientation: Int? = null): ImageDetailActivity {
        val deadline = android.os.SystemClock.uptimeMillis() + 20_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            var activity: Activity? = null
            onMain {
                activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .firstOrNull { it is ImageDetailActivity && (orientation == null || it.resources.configuration.orientation == orientation) }
            }
            (activity as? ImageDetailActivity)?.let { return it }
            Thread.sleep(100)
        }
        error("ImageDetailActivity did not resume")
    }

    private fun field(target: Any, name: String): Any =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val directory = context.cacheDir
        directory.mkdirs()
        java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
