package com.kerybotu.derpibooru.mirror

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.liquidglass.LiquidGlassView
import com.kerybotu.derpibooru.mirror.theme.AccentColor
import com.kerybotu.derpibooru.mirror.ui.HomeGlassTabBar
import com.kerybotu.derpibooru.mirror.ui.SettingsActivity
import com.kerybotu.derpibooru.mirror.ui.SafeToolbar
import com.kerybotu.derpibooru.mirror.ui.IslandGlassView
import com.kerybotu.derpibooru.mirror.ui.FilterActivity
import com.kerybotu.derpibooru.mirror.ui.ImageDetailActivity
import com.kerybotu.derpibooru.mirror.ui.menu.GlassMenuCard
import com.kerybotu.derpibooru.mirror.ui.menu.GlassMenuItem
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Device regression for theme propagation and the library's darkening overlay. */
@RunWith(AndroidJUnit4::class)
class GlassThemeRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun fullWindowDisplacementMapsStayBoundedAndIndependentlyOwned() {
        val first = com.example.liquidglass.DisplacementMapGenerator.generateStandardMaps(3840, 2160)
        val second = com.example.liquidglass.DisplacementMapGenerator.generateStandardMaps(3840, 2160)
        try {
            assertEquals(com.example.liquidglass.DisplacementMode.entries.toSet(), first.keys)
            for (mode in first.keys) {
                val map = requireNotNull(first[mode])
                assertTrue("Window resolution must not determine allocator footprint", map.width <= 256 && map.height <= 256)
                assertEquals(3840f / 2160f, map.width.toFloat() / map.height, 0.02f)
                assertNotSame("Upstream recycles maps; different views must own separate buffers", map, second[mode])
                map.recycle()
                assertFalse(requireNotNull(second[mode]).isRecycled)
            }
        } finally {
            first.values.filterNot { it.isRecycled }.forEach { it.recycle() }
            second.values.forEach { it.recycle() }
        }
    }

    @Test
    fun sixThemesRefreshExistingIslandsAndOpenMenu() {
        val originalPalette = AppSettings.getPalette(context)
        val originalAccent = AppSettings.getAccentColor(context)
        val main = launch(MainActivity::class.java)
        val settings = launch(SettingsActivity::class.java)
        var menu: GlassMenuCard? = null
        try {
            instrumentation.runOnMainSync {
                menu = GlassMenuCard(settings, listOf(
                    GlassMenuItem("theme", R.drawable.ic_settings, "主题回归检查", onClick = {})
                )).also { it.show() }
            }
            for (mode in listOf(AppSettings.Palette.LIGHT, AppSettings.Palette.DARK)) {
                for (accent in listOf(AccentColor.BLUE, AccentColor.PURPLE, AccentColor.GREEN)) {
                    instrumentation.runOnMainSync {
                        AppSettings.setPalette(context, mode)
                        AppSettings.setAccentColor(context, accent)
                        PaletteManager.notifyChanged(context)
                    }
                    settle()
                    checkMaterials(main)
                    checkMaterials(settings)
                    val openMenu = requireNotNull(menu)
                    checkMaterials(requireNotNull(openMenu.window).decorView)
                    capture("${mode}_${accent}_open_menu", requireNotNull(openMenu.window).decorView)
                }
            }
        } finally {
            instrumentation.runOnMainSync { menu?.dismiss(); settings.finish(); main.finish() }
            restore(originalPalette, originalAccent)
        }
    }

    @Test
    fun sixThemePageMatrix() {
        val originalPalette = AppSettings.getPalette(context)
        val originalAccent = AppSettings.getAccentColor(context)
        // Exercise live theme changes on the existing shell, as Settings does.
        // Repeatedly creating a second shell also reruns network startup and
        // does not represent tab navigation or the theme propagation contract.
        val main = launch(MainActivity::class.java)
        try {
            awaitMainStartup(main)
            for (mode in listOf(AppSettings.Palette.LIGHT, AppSettings.Palette.DARK)) {
                for (accent in listOf(AccentColor.BLUE, AccentColor.PURPLE, AccentColor.GREEN)) {
                    instrumentation.runOnMainSync {
                        AppSettings.setPalette(context, mode)
                        AppSettings.setAccentColor(context, accent)
                        PaletteManager.notifyChanged(context)
                    }
                    run {
                        val tabs = main.findViewById<HomeGlassTabBar>(R.id.glass_bottom_navigation)
                        for ((index, page) in listOf("Home", "Video", "Hot", "Comments", "Profile").withIndex()) {
                            instrumentation.runOnMainSync {
                                tabs.selectItem(index)
                                tabs.onItemSelected?.invoke(index)
                            }
                            settle()
                            checkMaterials(main)
                            instrumentation.runOnMainSync {
                                assertEquals("BottomIsland must remain unique", 1,
                                    descendants(main.window.decorView).count { it.id == R.id.glass_bottom_navigation })
                            }
                            capturePage("${mode}_${accent}_$page", main)
                        }
                        val filter = launch(FilterActivity::class.java)
                        try {
                            checkMaterials(filter)
                            capturePage("${mode}_${accent}_Filters", filter)
                        } finally { instrumentation.runOnMainSync { filter.finish() } }
                        val detail = launch(ImageDetailActivity::class.java, "image_id" to 1)
                        try {
                            awaitDetailContent(detail)
                            instrumentation.runOnMainSync { detail.findViewById<View>(R.id.detail_comments_count).performClick() }
                            settle()
                            checkMaterials(detail)
                            capturePage("${mode}_${accent}_ImageDetail", detail)
                        } finally { instrumentation.runOnMainSync { detail.finish() } }
                        instrumentation.runOnMainSync {
                            tabs.selectItem(0)
                            tabs.onItemSelected?.invoke(0)
                        }
                        settle()
                        checkMaterials(main)
                        capturePage("${mode}_${accent}_Home_return", main)
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync { main.finish() }
            restore(originalPalette, originalAccent)
        }
    }

    private fun launch(type: Class<out Activity>, extra: Pair<String, Int>? = null): Activity {
        val intent = Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        extra?.let { intent.putExtra(it.first, it.second) }
        instrumentation.runOnMainSync { context.startActivity(intent) }
        // Glass/video continuously schedules frames; global queue idleness
        // is not a readiness condition. Await the actual resumed Activity.
        val deadline = android.os.SystemClock.uptimeMillis() + 45_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            var resumed: Activity? = null
            instrumentation.runOnMainSync {
                resumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).firstOrNull { type.isInstance(it) }
            }
            resumed?.let { settle(); return it }
            Thread.sleep(100)
        }
        error("${type.simpleName} did not reach RESUMED within 45 seconds")
    }

    private fun settle() {
        Thread.sleep(700)
        instrumentation.runOnMainSync { /* Confirm the main thread remains responsive. */ }
    }

    private fun awaitMainStartup(main: Activity) {
        // The real UI blocks tab navigation during CDN discovery. Do not
        // invoke callbacks before the app's networking client is ready.
        val deadline = android.os.SystemClock.uptimeMillis() + 180_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync {
                ready = com.kerybotu.derpibooru.mirror.network.NetworkManager.isReady() &&
                    main.findViewById<View>(R.id.startup_overlay)?.visibility != View.VISIBLE
            }
            if (ready) return
            Thread.sleep(250)
        }
        error("Startup networking did not become ready; loading screens cannot count as smoke verification")
    }

    private fun awaitDetailContent(detail: Activity) {
        val deadline = android.os.SystemClock.uptimeMillis() + 45_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync {
                val author = detail.findViewById<android.widget.TextView>(R.id.detail_uploader).text.toString()
                ready = author.isNotBlank() && author != "未知上传者" &&
                    detail.findViewById<android.widget.ImageView>(R.id.detail_image).drawable != null
            }
            if (ready) return
            Thread.sleep(250)
        }
        error("ImageDetail image/author did not load; an empty detail screen cannot count as smoke verification")
    }

    private fun checkMaterials(activity: Activity) = checkMaterials(activity.window.decorView)

    private fun capturePage(name: String, activity: Activity) {
        instrumentation.runOnMainSync {
            val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            assertTrue("$name is obscured (${resumed.map { it.javaClass.simpleName }}); it cannot count as page verification",
                resumed.contains(activity))
        }
        capture(name, activity.window.decorView)
    }

    private fun checkMaterials(root: View) {
        instrumentation.runOnMainSync {
            val scheme = PaletteManager.colors(context)
            val glasses = descendants(root).filterIsInstance<LiquidGlassView>()
            val safeToolbars = descendants(root).filterIsInstance<SafeToolbar>()
            assertTrue("Expected public glass surfaces", glasses.isNotEmpty() || safeToolbars.isNotEmpty())
            for (toolbar in safeToolbars) assertEquals(scheme.glassSurface,
                (toolbar.background as? GradientDrawable)?.color?.defaultColor)
            for (glass in glasses) {
                assertFalse("Backdrop must not replace app ThemeMode", glass.enableAdaptiveTint)
                assertFalse("Avoid library's fixed black contrast overlay", glass.overLight)
                assertEquals("Resolved tint must reach the renderer", scheme.glassTint, glass.glassTint)
                assertEquals("Fallback base must use the same scheme", scheme.glassSurface,
                    (glass.background as? GradientDrawable)?.color?.defaultColor)
            }
        }
    }

    private fun capture(name: String, root: View) {
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "theme-regression").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        // Verify actual composed pixels, not merely the token value. Use the
        // empty leading material edge, excluding labels/icons and rounded corners.
        val samples = mutableListOf<Pair<String, Rect>>()
        instrumentation.runOnMainSync {
            descendants(root).filter {
                (it is IslandGlassView || it is HomeGlassTabBar || it is SafeToolbar ||
                    it.id == R.id.detail_author_panel || it.id == R.id.detail_interaction_panel) &&
                    it.isShown && it.width > 100 && it.height > 60
            }.forEach { glass ->
                val bounds = Rect()
                if (glass.getGlobalVisibleRect(bounds) && bounds.height() >= glass.height) {
                    samples += glass.javaClass.simpleName to bounds
                }
            }
        }
        try {
            for ((component, bounds) in samples) {
                val x = bounds.left + (context.resources.displayMetrics.density * 5).toInt()
                val y = bounds.centerY()
                if (x in 0 until screenshot.width && y in 0 until screenshot.height) {
                    val luminance = Color.luminance(screenshot.getPixel(x, y))
                    android.util.Log.i("GlassThemeRegression", "$name $component luminance=$luminance")
                    if (!PaletteManager.colors(context).isDark) {
                        assertTrue("$name $component material is still dark (luminance=$luminance)", luminance > 0.5f)
                    } else {
                        assertTrue("$name $component did not recover dark material (luminance=$luminance)", luminance < 0.5f)
                    }
                }
            }
        } finally { screenshot.recycle() }
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(descendants(view.getChildAt(i)))
    }

    private fun restore(palette: AppSettings.Palette, accent: AccentColor) {
        instrumentation.runOnMainSync {
            AppSettings.setPalette(context, palette)
            AppSettings.setAccentColor(context, accent)
            PaletteManager.notifyChanged(context)
        }
    }
}
