package com.kerybotu.derpibooru.mirror

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.liquidglass.LiquidGlassView
import com.kerybotu.derpibooru.mirror.theme.AccentColor
import com.kerybotu.derpibooru.mirror.ui.HomeGlassTabBar
import com.kerybotu.derpibooru.mirror.ui.SettingsActivity
import com.kerybotu.derpibooru.mirror.ui.SafeToolbar
import com.kerybotu.derpibooru.mirror.ui.IslandGlassView
import com.kerybotu.derpibooru.mirror.ui.GlassWidgetStyle
import com.kerybotu.derpibooru.mirror.ui.FilterActivity
import com.kerybotu.derpibooru.mirror.ui.EmbeddedMessagesView
import com.kerybotu.derpibooru.mirror.ui.ImageDetailActivity
import com.kerybotu.derpibooru.mirror.ui.PreparedBackdropContentSource
import com.kerybotu.derpibooru.mirror.ui.ScrollContentBackdrop
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
    fun mainMenuUsesUi2WhileHomeIsStillLoading() {
        val main = launch(MainActivity::class.java)
        try {
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredField("homeHasLoaded").apply {
                    isAccessible = true
                    setBoolean(main, false)
                }
                MainActivity::class.java.getDeclaredMethod("updateNavigationMenuMode").apply {
                    isAccessible = true
                    invoke(main)
                }
            }
            onView(withContentDescription(main.getString(R.string.navigation_drawer_open))).perform(click())
            onView(withText("标签")).inRoot(isDialog()).check(matches(isDisplayed()))
            instrumentation.runOnMainSync {
                assertFalse("Opening UI 2.0 must not open the legacy drawer", main.findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.drawer_layout).isDrawerOpen(androidx.core.view.GravityCompat.START))
            }
            capturePage("startup_ui2_menu_before_home_loaded", main)
        } finally {
            instrumentation.runOnMainSync { main.finish() }
        }
    }

    @Test
    fun menuUsesIndependentLiveBackdropSourceWhenActivityContainsGlass() {
        val main = launch(MainActivity::class.java)
        var menu: GlassMenuCard? = null
        try {
            instrumentation.runOnMainSync {
                menu = GlassMenuCard(main, listOf(
                    GlassMenuItem("source", R.drawable.ic_settings, "Source check", onClick = {})
                )).also { it.show() }
            }
            settle()
            var menuGlass: LiquidGlassView? = null
            instrumentation.runOnMainSync {
                val decor = requireNotNull(menu?.window).decorView
                menuGlass = descendants(decor).filterIsInstance<LiquidGlassView>().first()
            }
            val glass = requireNotNull(menuGlass)
            assertTrue("Menu glass must retain dynamic backdrop blur", glass.enableBackdropBlur)
            assertTrue("Menu must use a dedicated prepared content source",
                glass.backdropSource is PreparedBackdropContentSource)
            assertNotSame("Menu may not capture the Activity content root",
                main.findViewById<View>(android.R.id.content), glass.backdropSource)
        } finally {
            instrumentation.runOnMainSync { menu?.dismiss(); main.finish() }
        }
    }

    /** Render the compatibility material to pixels; theme and accent changes must remain visible. */
    @Test
    fun safeMaterialRetainsTranslucencyTintAndThemeContrast() {
        val originalPalette = AppSettings.getPalette(context)
        val originalAccent = AppSettings.getAccentColor(context)
        try {
            val renderedSamples = mutableMapOf<Pair<AppSettings.Palette, AccentColor>, Int>()
            for (mode in listOf(AppSettings.Palette.LIGHT, AppSettings.Palette.DARK)) {
                for (accent in listOf(AccentColor.BLUE, AccentColor.PURPLE, AccentColor.GREEN)) {
                    instrumentation.runOnMainSync {
                        AppSettings.setPalette(context, mode)
                        AppSettings.setAccentColor(context, accent)
                        PaletteManager.notifyChanged(context)
                    }
                    val scheme = PaletteManager.colors(context)
                    val drawable = GlassWidgetStyle.createSafeMaterialBackground(
                        scheme,
                        cornerRadius = 30f * context.resources.displayMetrics.density,
                        density = context.resources.displayMetrics.density
                    )
                    val bitmap = Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    drawable.setBounds(0, 0, bitmap.width, bitmap.height)
                    drawable.draw(canvas)
                    try {
                        val top = bitmap.getPixel(bitmap.width / 2, 2)
                        val middle = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                        val bottom = bitmap.getPixel(bitmap.width / 2, bitmap.height - 3)
                        assertTrue("$mode $accent material must retain translucent pixels", Color.alpha(middle) in 1..254)
                        assertTrue("$mode $accent material must contain visible highlight variation",
                            colorDifference(top, bottom) > 1)
                        renderedSamples[mode to accent] = middle
                        assertTrue("$mode $accent material must retain a visible border color", Color.alpha(scheme.glassBorder) > 0)
                    } finally { bitmap.recycle() }
                }
            }
            for (accent in listOf(AccentColor.BLUE, AccentColor.PURPLE, AccentColor.GREEN)) {
                val light = requireNotNull(renderedSamples[AppSettings.Palette.LIGHT to accent])
                val dark = requireNotNull(renderedSamples[AppSettings.Palette.DARK to accent])
                assertTrue("$accent material must reflect light and dark themes", colorDifference(light, dark) > 20)
            }
            val blue = requireNotNull(renderedSamples[AppSettings.Palette.LIGHT to AccentColor.BLUE])
            val purple = requireNotNull(renderedSamples[AppSettings.Palette.LIGHT to AccentColor.PURPLE])
            assertTrue("Accent choice must alter the rendered material pixels", colorDifference(blue, purple) > 1)
        } finally {
            restore(originalPalette, originalAccent)
        }
    }

    private fun colorDifference(first: Int, second: Int): Int =
        kotlin.math.abs(Color.red(first) - Color.red(second)) +
            kotlin.math.abs(Color.green(first) - Color.green(second)) +
            kotlin.math.abs(Color.blue(first) - Color.blue(second))

    /** Exercise the actual AAR View on hardware, not just its background drawable. */
    @Test
    fun fallbackViewTransmitsChangingBackgroundInBothThemes() {
        val prefs = context.getSharedPreferences("app_settings_prefs", 0)
        val hadFallback = prefs.contains("glass_performance_fallback")
        val savedFallback = AppSettings.isGlassPerformanceFallbackEnabled(context)
        val originalPalette = AppSettings.getPalette(context)
        val originalAccent = AppSettings.getAccentColor(context)
        val page = launch(SettingsActivity::class.java)
        val root = page.findViewById<ViewGroup>(android.R.id.content)
        var host: FrameLayout? = null
        try {
            for (mode in listOf(AppSettings.Palette.LIGHT, AppSettings.Palette.DARK)) {
                lateinit var glass: IslandGlassView
                instrumentation.runOnMainSync {
                    AppSettings.setPalette(context, mode)
                    AppSettings.setGlassPerformanceFallbackEnabled(context, true)
                    AppSettings.setAccentColor(context, AccentColor.PURPLE)
                    PaletteManager.notifyChanged(context)
                    assertSame("Repeated glass bindings must reuse the immutable palette",
                        PaletteManager.colors(context), PaletteManager.colors(context))
                    host = FrameLayout(page).apply {
                        setBackgroundColor(Color.RED)
                        elevation = 100f
                    }
                    glass = IslandGlassView(page)
                    host!!.addView(glass, FrameLayout.LayoutParams(400, 180).apply {
                        gravity = android.view.Gravity.CENTER
                    })
                    root.addView(host, FrameLayout.LayoutParams(480, 240).apply {
                        gravity = android.view.Gravity.CENTER
                    })
                    glass.setPalette(PaletteManager.colors(context))
                    glass.setRenderingActive(true, host)
                    assertFalse(glass.enableBackdropBlur)
                    assertFalse(glass.enableChromaticAberration)
                    assertNotSame("Fallback must not capture its host", host, glass.backdropSource)
                }
                settle()
                val red = opticalSnapshot(glass)
                instrumentation.runOnMainSync { host!!.setBackgroundColor(Color.BLUE) }
                settle()
                val blue = opticalSnapshot(glass)
                try {
                    val centerRed = red.getPixel(red.width / 2, red.height / 2)
                    val centerBlue = blue.getPixel(blue.width / 2, blue.height / 2)
                    assertTrue("$mode must transmit the changing background through the real AAR View",
                        Color.red(centerRed) - Color.red(centerBlue) > 20 &&
                            Color.blue(centerBlue) - Color.blue(centerRed) > 20)
                    assertTrue("$mode must still apply material, rather than become fully transparent",
                        colorDifference(centerRed, Color.RED) > 30 &&
                            colorDifference(centerBlue, Color.BLUE) > 30)
                    capturePage("fallback_actual_view_${mode.name.lowercase()}", page)
                } finally { red.recycle(); blue.recycle() }
                instrumentation.runOnMainSync { root.removeView(host); host = null }
            }
        } finally {
            instrumentation.runOnMainSync { host?.let(root::removeView); page.finish() }
            if (hadFallback) AppSettings.setGlassPerformanceFallbackEnabled(context, savedFallback)
            else prefs.edit().remove("glass_performance_fallback").commit()
            restore(originalPalette, originalAccent)
        }
    }

    private fun awaitOpticalFrame(glass: LiquidGlassView, effect: String, blurred: Boolean = false, refracted: Boolean = false) {
        val deadline = android.os.SystemClock.uptimeMillis() + 30_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync {
                val frame = glass.lastFrameStats
                ready = frame != null && frame.effectName == effect && frame.processedWidth > 0 &&
                    (!blurred || frame.blurRecomputed) && (!refracted || frame.effectRecomputed)
            }
            if (ready) { settle(); return }
            Thread.sleep(100)
        }
        error("Optical renderer did not produce $effect (blur=$blurred refraction=$refracted) within 30 seconds")
    }

    private fun opticalSnapshot(glass: View): Bitmap {
        val bounds = Rect()
        instrumentation.runOnMainSync { assertTrue(glass.getGlobalVisibleRect(bounds)) }
        val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        // Exclude the rounded corners/highlight so optical changes cannot be
        // mistaken for merely toggling a border or shadow.
        val inset = (24 * context.resources.displayMetrics.density).toInt()
        return try { Bitmap.createBitmap(shot, bounds.left + inset, bounds.top + inset,
            bounds.width() - inset * 2, bounds.height() - inset * 2) } finally { shot.recycle() }
    }

    private fun pixelDifference(first: Bitmap, second: Bitmap): Float {
        var difference = 0L
        var count = 0
        for (y in 0 until first.height step 3) for (x in 0 until first.width step 3) {
            val a = first.getPixel(x, y)
            val b = second.getPixel(x, y)
            difference += kotlin.math.abs(Color.red(a) - Color.red(b)) +
                kotlin.math.abs(Color.green(a) - Color.green(b)) + kotlin.math.abs(Color.blue(a) - Color.blue(b))
            count++
        }
        return difference.toFloat() / (count * 3)
    }

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
    fun settingsGlassBackdropDoesNotOccludeSectionText() {
        val settings = launch(SettingsActivity::class.java)
        try {
            settle()
            var labelBounds: Rect? = null
            instrumentation.runOnMainSync {
                val label = descendants(settings.window.decorView)
                    .filterIsInstance<android.widget.TextView>()
                    .first { it.text.toString() == "账户" }
                labelBounds = Rect().also { assertTrue(label.getGlobalVisibleRect(it)) }
            }
            val bounds = requireNotNull(labelBounds)
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                var darkPixels = 0
                for (y in bounds.top until bounds.bottom) {
                    for (x in bounds.left until bounds.right) {
                        if (Color.luminance(screenshot.getPixel(x, y)) < 0.25f) darkPixels++
                    }
                }
                assertTrue("Settings section label must be visible above its glass backdrop; dark pixels=$darkPixels", darkPixels >= 20)
            } finally {
                screenshot.recycle()
            }
        } finally {
            instrumentation.runOnMainSync { settings.finish() }
        }
    }

    @Test
    fun profileIslandBindsLiveBackdropWhenHiddenAttachedPageBecomesVisible() {
        val main = launch(MainActivity::class.java)
        try {
            awaitMainStartup(main)
            instrumentation.runOnMainSync {
                val tabs = main.findViewById<HomeGlassTabBar>(R.id.glass_bottom_navigation)
                tabs.selectItem(4)
                tabs.onItemSelected?.invoke(4)
            }
            settle()
            instrumentation.runOnMainSync {
                val profileSourceIsland = descendants(main.window.decorView)
                    .filterIsInstance<IslandGlassView>()
                    .firstOrNull { it.isShown && it.backdropSource is androidx.recyclerview.widget.RecyclerView }
                assertNotNull("Profile's visible top island must bind its ordinary scrolling content source", profileSourceIsland)
                assertTrue("Profile's visible top island must retain live GPU blur", profileSourceIsland!!.enableBackdropBlur)
            }
        } finally {
            instrumentation.runOnMainSync { main.finish() }
        }
    }

    @Test
    fun embeddedCommentsLoadingGlassReactivatesWhenPageBecomesVisible() {
        val main = launch(MainActivity::class.java)
        try {
            instrumentation.runOnMainSync {
                val tabs = main.findViewById<HomeGlassTabBar>(R.id.glass_bottom_navigation)
                tabs.selectItem(3)
                tabs.onItemSelected?.invoke(3)
            }
            settle()
            instrumentation.runOnMainSync {
                val comments = descendants(main.window.decorView)
                    .filterIsInstance<EmbeddedMessagesView>()
                    .first { it.isShown }
                comments.showLoading(true, centered = true)
            }
            settle()
            instrumentation.runOnMainSync {
                val comments = descendants(main.window.decorView)
                    .filterIsInstance<EmbeddedMessagesView>()
                    .first { it.isShown }
                val loadingGlass = descendants(comments).filterIsInstance<LiquidGlassView>()
                    .firstOrNull { it.isShown && it.width == 160 && it.height == 160 }
                assertNotNull("Visible comments loading material must exist", loadingGlass)
                assertTrue("Visible comments loading material must use live GPU blur",
                    requireNotNull(loadingGlass).enableBackdropBlur)
                assertTrue("Visible comments loading material must retain its prepared source",
                    loadingGlass!!.backdropSource is ScrollContentBackdrop)
            }
        } finally {
            instrumentation.runOnMainSync { main.finish() }
        }
    }

    @Test
    fun detailSectionsOwnContentAndReflowAcrossSixThemes() {
        val originalPalette = AppSettings.getPalette(context)
        val originalAccent = AppSettings.getAccentColor(context)
        val landscape = InstrumentationRegistry.getArguments().getString("detail_landscape") == "true"
        var main = launch(MainActivity::class.java)
        var detail: Activity? = null
        try {
            if (landscape) {
                instrumentation.runOnMainSync { main.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                val rotationDeadline = android.os.SystemClock.uptimeMillis() + 45_000
                var rotated: Activity? = null
                while (rotated == null && android.os.SystemClock.uptimeMillis() < rotationDeadline) {
                    instrumentation.runOnMainSync {
                        rotated = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .firstOrNull { it is MainActivity && it.findViewById<View>(android.R.id.content).let { root -> root.width > root.height } }
                    }
                    if (rotated == null) Thread.sleep(100)
                }
                main = requireNotNull(rotated) { "System did not recreate the shell in a real landscape viewport" }
            }
            awaitMainStartup(main)
            var page = launch(ImageDetailActivity::class.java, "image_id" to 1)
            if (landscape) {
                instrumentation.runOnMainSync { page.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                val deadline = android.os.SystemClock.uptimeMillis() + 45_000
                var rotated: Activity? = null
                while (rotated == null && android.os.SystemClock.uptimeMillis() < deadline) {
                    instrumentation.runOnMainSync {
                        rotated = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .firstOrNull { it is ImageDetailActivity && it.findViewById<View>(R.id.detail_root).let { root -> root.width > root.height } }
                    }
                    if (rotated == null) Thread.sleep(100)
                }
                page = requireNotNull(rotated) { "Detail did not recreate in landscape" }
            }
            detail = page
            awaitDetailContent(page)
            awaitDetailComments(page)
            verifyDetailZoom(page)
            fun view(id: Int) = page.findViewById<View>(id)
            val scroll = view(R.id.detail_scroll) as androidx.core.widget.NestedScrollView
            instrumentation.runOnMainSync {
                if (landscape) assertTrue("Actual detail viewport must be landscape, not just a rotation setting",
                    view(R.id.detail_root).width > view(R.id.detail_root).height)
                assertEquals("Image #1 has no description; do not leave an empty island", View.GONE,
                    view(R.id.detail_description_panel).visibility)
                assertTrue("Real sources must remain clickable",
                    (view(R.id.detail_source) as android.widget.TextView).urls.isNotEmpty())
                view(R.id.detail_comments_count).performClick()
            }
            settle()
            capturePage("detail_real_empty_description", page)
            verifyDetailCachedBackdrop(page)
            instrumentation.runOnMainSync {
                ImageDetailActivity::class.java.getDeclaredMethod("showDetailLoadFailure").apply { isAccessible = true }.invoke(page)
                val status = view(R.id.detail_load_status)
                assertTrue("Retry belongs to the actual author glass container", descendants(view(R.id.detail_author_panel)).contains(status))
                assertTrue("Failed loading needs an enabled recovery action", status.isEnabled)
            }
            settle()
            capturePage("detail_fixture_loading_failure", page)
            instrumentation.runOnMainSync { view(R.id.detail_load_status).performClick() }
            awaitDetailContent(page)
            awaitDetailComments(page)

            // Boundary fixtures use the real binders and widgets after real API
            // loading. Screenshots are labeled fixture, never API-data evidence.
            val longDescription = ("长文本布局检查：描述应与玻璃背景一起滚动，且展开后不被裁切。\n").repeat(18)
            val longSource = "https://example.org/artwork/" + "long-source-segment/".repeat(20)
            val bindDescription = ImageDetailActivity::class.java.getDeclaredMethod("bindDescription", String::class.java).apply { isAccessible = true }
            val bindSources = ImageDetailActivity::class.java.getDeclaredMethod("bindSources", List::class.java).apply { isAccessible = true }
            val bindTags = ImageDetailActivity::class.java.getDeclaredMethod("bindTags", List::class.java).apply { isAccessible = true }
            instrumentation.runOnMainSync {
                bindDescription.invoke(page, longDescription)
                bindSources.invoke(page, listOf(longSource))
                bindTags.invoke(page, listOf("a-very-long-tag-name-".repeat(15)) + (1..30).map { "layout-fixture-$it" })
                (view(R.id.detail_uploader) as android.widget.TextView).text = "长上传者昵称".repeat(20)
                (view(R.id.detail_faves) as android.widget.TextView).text = "99999999"
            }
            val tagDeadline = android.os.SystemClock.uptimeMillis() + 45_000
            var fixtureTagsReady = false
            while (!fixtureTagsReady && android.os.SystemClock.uptimeMillis() < tagDeadline) {
                instrumentation.runOnMainSync {
                    val group = view(R.id.detail_tags_group) as ViewGroup
                    fixtureTagsReady = group.childCount == 31 && !group.isLayoutRequested &&
                        (0 until group.childCount).all { group.getChildAt(it).width > 0 }
                }
                if (!fixtureTagsReady) Thread.sleep(100)
            }
            assertTrue("Asynchronous fixture-tag translation must finish before layout assertions", fixtureTagsReady)
            settle()
            val ownership = mapOf(
                R.id.detail_description to R.id.detail_description_panel,
                R.id.btn_expand_description to R.id.detail_description_panel,
                R.id.btn_translate_description to R.id.detail_description_panel,
                R.id.detail_tags_group to R.id.detail_tags_panel,
                R.id.detail_source to R.id.detail_source_panel,
                R.id.detail_uploader to R.id.detail_author_panel,
                R.id.detail_faves to R.id.detail_interaction_panel
            )
            instrumentation.runOnMainSync {
                ownership.forEach { (content, panel) ->
                    assertTrue("Content must belong to its material container", descendants(view(panel)).contains(view(content)))
                }
                val group = view(R.id.detail_tags_group) as ViewGroup
                assertEquals(31, group.childCount)
                for (i in 0 until group.childCount) assertTrue("Long tag must fit its measured container", group.getChildAt(i).width <= group.width)
                assertEquals(longSource, (view(R.id.detail_source) as android.widget.TextView).text.toString())
                assertTrue("Long source must wrap", (view(R.id.detail_source) as android.widget.TextView).lineCount > 1)
                assertEquals("Interaction island is unique", 1, descendants(page.window.decorView).count { it.id == R.id.detail_interaction_panel })
            }
            for (mode in listOf(AppSettings.Palette.LIGHT, AppSettings.Palette.DARK)) {
                for (accent in listOf(AccentColor.BLUE, AccentColor.PURPLE, AccentColor.GREEN)) {
                    instrumentation.runOnMainSync {
                        AppSettings.setPalette(context, mode)
                        AppSettings.setAccentColor(context, accent)
                        PaletteManager.notifyChanged(context)
                        view(R.id.detail_comments_count).performClick()
                        scroll.scrollTo(0, 0)
                        val scheme = PaletteManager.colors(context)
                        assertEquals(scheme.glassText, (view(R.id.detail_uploader_avatar) as android.widget.ImageView).imageTintList?.defaultColor)
                        for (backdrop in listOf(Color.WHITE, Color.BLACK)) {
                            val reading = androidx.core.graphics.ColorUtils.compositeColors(scheme.glassSurface, backdrop)
                            assertTrue("Inline action/link text needs reading contrast",
                                androidx.core.graphics.ColorUtils.calculateContrast(scheme.glassActionText, reading) >= 4.5)
                        }
                    }
                    settle()
                    checkMaterials(page)
                    capturePage("detail_fixture_${mode}_${accent}_description", page)
                    for ((name, id) in listOf("tags" to R.id.detail_tags_panel, "source" to R.id.detail_source_panel)) {
                        instrumentation.runOnMainSync { scroll.scrollTo(0, view(id).top) }
                        settle()
                        instrumentation.runOnMainSync {
                            val glass = view(id) as LiquidGlassView
                            assertNotNull("Glass must use a prepared source", glass.backdropSource)
                            assertNotSame("Glass must not fall back to its parent", glass.parent, glass.backdropSource)
                            assertTrue("Visible detail glass retains GPU blur", glass.enableBackdropBlur)
                            assertEquals(android.os.Build.VERSION.SDK_INT >= 33, glass.enableChromaticAberration)
                            assertTrue("A section is measured around its children", glass.height > 0)
                        }
                        capturePage("detail_fixture_${mode}_${accent}_$name", page)
                    }
                }
            }
            instrumentation.runOnMainSync {
                scroll.scrollTo(0, 0)
                view(R.id.btn_expand_description).performClick()
                view(R.id.detail_comments_count).performClick()
            }
            settle()
            instrumentation.runOnMainSync {
                assertEquals(Int.MAX_VALUE, (view(R.id.detail_description) as android.widget.TextView).maxLines)
                val body = view(R.id.detail_description)
                val panel = view(R.id.detail_description_panel)
                assertTrue("Expanded body must remain inside the glass container", panel.height > body.height)
                scroll.fullScroll(View.FOCUS_DOWN)
            }
            settle()
            instrumentation.runOnMainSync {
                val comments = view(R.id.detail_comments_preview) as ViewGroup
                val last = comments.getChildAt(comments.childCount - 1)
                val position = IntArray(2)
                last.getLocationOnScreen(position)
                val viewport = Rect()
                scroll.getGlobalVisibleRect(viewport)
                assertTrue("Last comment bottom must be reachable", position[1] + last.height <= viewport.bottom)
                bindDescription.invoke(page, "")
                bindSources.invoke(page, emptyList<String>())
                bindTags.invoke(page, emptyList<String>())
            }
            settle()
            instrumentation.runOnMainSync {
                for (id in listOf(R.id.detail_description_panel, R.id.detail_source_panel, R.id.detail_tags_panel))
                    assertEquals("Absent content must not leave an empty container", View.GONE, view(id).visibility)
            }
            capturePage("detail_fixture_empty_sections_comments", page)
        } finally {
            instrumentation.runOnMainSync { detail?.finish(); main.finish() }
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
                            awaitPageArtwork(main, page)
                            if (page == "Video") verifyLiveVideoThemeChange(main, mode, accent)
                            checkMaterials(main)
                            instrumentation.runOnMainSync {
                                assertEquals("BottomIsland must remain unique", 1,
                                    descendants(main.window.decorView).count { it.id == R.id.glass_bottom_navigation })
                            }
                            capturePage("${mode}_${accent}_$page", main)
                        }
                        val filter = launch(FilterActivity::class.java)
                        try {
                            awaitFilterContent(filter)
                            checkMaterials(filter)
                            capturePage("${mode}_${accent}_Filters", filter)
                        } finally { instrumentation.runOnMainSync { filter.finish() } }
                        val detail = launch(ImageDetailActivity::class.java, "image_id" to 1)
                        try {
                            awaitDetailContent(detail)
                            awaitDetailComments(detail)
                            checkMaterials(detail)
                            capturePage("${mode}_${accent}_ImageDetail_image", detail)
                            instrumentation.runOnMainSync { detail.findViewById<View>(R.id.btn_more).performClick() }
                            settle()
                            capturePage("${mode}_${accent}_ImageDetail_menu", detail)
                            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                            settle()
                            instrumentation.runOnMainSync { detail.findViewById<View>(R.id.detail_comments_count).performClick() }
                            settle()
                            instrumentation.runOnMainSync {
                                val top = Rect()
                                val author = Rect()
                                detail.findViewById<View>(R.id.detail_top_island).getGlobalVisibleRect(top)
                                detail.findViewById<View>(R.id.detail_author_panel).getGlobalVisibleRect(author)
                                assertTrue("Expanded author island must remain below navigation", author.top >= top.bottom)
                                val viewport = Rect()
                                detail.findViewById<View>(R.id.detail_scroll).getGlobalVisibleRect(viewport)
                                val root = Rect()
                                detail.findViewById<View>(R.id.detail_root).getGlobalVisibleRect(root)
                                assertEquals("Comment viewport must not extend below the root", root.bottom, viewport.bottom)
                            }
                            checkMaterials(detail)
                            capturePage("${mode}_${accent}_ImageDetail", detail)
                            instrumentation.runOnMainSync {
                                detail.findViewById<androidx.core.widget.NestedScrollView>(R.id.detail_scroll).fullScroll(View.FOCUS_DOWN)
                            }
                            settle()
                            instrumentation.runOnMainSync {
                                val comments = detail.findViewById<ViewGroup>(R.id.detail_comments_preview)
                                val last = comments.getChildAt(comments.childCount - 1)
                                assertTrue("Last comment must have been measured", last.height > 0)
                                val location = IntArray(2)
                                last.getLocationOnScreen(location)
                                val viewport = Rect()
                                detail.findViewById<View>(R.id.detail_scroll).getGlobalVisibleRect(viewport)
                                assertTrue("Last comment bottom must be reachable inside the viewport",
                                    location[1] + last.height <= viewport.bottom)
                            }
                            capturePage("${mode}_${accent}_ImageDetail_scroll", detail)
                            instrumentation.runOnMainSync {
                                detail.findViewById<androidx.core.widget.NestedScrollView>(R.id.detail_scroll).fullScroll(View.FOCUS_UP)
                            }
                            settle()
                            checkMaterials(detail)
                            instrumentation.runOnMainSync {
                                assertEquals("Interaction island must remain one instance", 1,
                                    descendants(detail.window.decorView).count { it.id == R.id.detail_interaction_panel })
                            }
                        } finally { instrumentation.runOnMainSync { detail.finish() } }
                        instrumentation.runOnMainSync {
                            tabs.selectItem(0)
                            tabs.onItemSelected?.invoke(0)
                        }
                        settle()
                        checkMaterials(main)
                        capturePage("${mode}_${accent}_Home_return", main)
                        instrumentation.runOnMainSync { (main as MainActivity).showUnifiedGlassMenu() }
                        settle()
                        capturePage("${mode}_${accent}_side_menu", main)
                        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                        settle()
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync { main.finish() }
            restore(originalPalette, originalAccent)
        }
    }

    @Test
    fun detailMenuActionsAndReturnHome() {
        val favorites = context.getSharedPreferences("local_favorites", android.content.Context.MODE_PRIVATE)
        val originalFavorites = favorites.getString("folders", null)
        val main = launch(MainActivity::class.java)
        var detail: Activity? = null
        try {
            awaitMainStartup(main)
            val page = launch(ImageDetailActivity::class.java, "image_id" to 1)
            detail = page
            awaitDetailContent(page)
            instrumentation.runOnMainSync {
                assertEquals("No opaque legacy sheet behind the actual islands", Color.TRANSPARENT,
                    (page.findViewById<View>(R.id.bottom_sheet).background as? android.graphics.drawable.ColorDrawable)?.color)
                page.findViewById<View>(R.id.detail_faves).performClick()
                assertTrue("Original local-favorite handler must persist the image",
                    com.kerybotu.derpibooru.mirror.favorites.LocalFavoritesStore(context).allFolders()
                        .first { it.isDefault }.items.any { it.imageId == 1 })
            }
            awaitDetailComments(page)
            var clickedTag = ""
            instrumentation.runOnMainSync {
                val tag = page.findViewById<ViewGroup>(R.id.detail_tags_group).getChildAt(0)
                clickedTag = tag.contentDescription.toString()
                tag.performClick()
            }
            val search = awaitResumed(com.kerybotu.derpibooru.mirror.ui.SearchActivity::class.java)
            try {
                assertEquals("Original full tag must reach search", clickedTag,
                    search.intent.getStringExtra(com.kerybotu.derpibooru.mirror.ui.SearchActivity.EXTRA_INITIAL_QUERY))
            } finally { instrumentation.runOnMainSync { search.finish() } }
            awaitResumed(ImageDetailActivity::class.java)
            fun openMenu() {
                instrumentation.runOnMainSync { page.findViewById<View>(R.id.btn_more).performClick() }
                settle()
            }
            openMenu()
            tapMenuItem("复制链接")
            settle()
            instrumentation.runOnMainSync {
                val clipboard = page.getSystemService(android.content.ClipboardManager::class.java)
                assertEquals("https://${AppSettings.getTargetDomain(page)}/images/1",
                    clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            }
            openMenu()
            tapMenuItem("查看原图")
            val original = awaitResumed(com.kerybotu.derpibooru.mirror.ui.FullScreenImageActivity::class.java)
            try {
                val deadline = android.os.SystemClock.uptimeMillis() + 90_000
                var loaded = false
                while (!loaded && android.os.SystemClock.uptimeMillis() < deadline) {
                    instrumentation.runOnMainSync {
                        loaded = original.findViewById<android.widget.ImageView>(R.id.fullscreen_image)
                            .drawable?.intrinsicWidth?.let { it > 500 } == true
                    }
                    if (!loaded) Thread.sleep(200)
                }
                assertTrue("Original action must load real artwork", loaded)
                capturePage("detail_action_original", original)
            } finally { instrumentation.runOnMainSync { original.finish() } }
            awaitResumed(ImageDetailActivity::class.java)
            val queue = com.kerybotu.derpibooru.mirror.download.DownloadQueueManager.get(context)
            val before = queue.state.value.map { it.taskId }.toSet()
            openMenu()
            tapMenuItem("下载")
            val deadline = android.os.SystemClock.uptimeMillis() + 90_000
            var completed = false
            while (!completed && android.os.SystemClock.uptimeMillis() < deadline) {
                val task = queue.state.value.firstOrNull { it.taskId !in before && it.imageId == 1L }
                if (task?.status == com.kerybotu.derpibooru.mirror.download.DownloadStatus.FAILED)
                    error("Actual download failed: ${task.errorMessage}")
                completed = task?.status == com.kerybotu.derpibooru.mirror.download.DownloadStatus.COMPLETED &&
                    task.downloadedBytes > 0 && task.outputUri != null
                if (!completed) Thread.sleep(200)
            }
            assertTrue("Download action must complete through the original queue", completed)
            val downloaded = queue.state.value.first { it.taskId !in before && it.imageId == 1L }
            val dimensions = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(android.net.Uri.parse(downloaded.outputUri)).use {
                android.graphics.BitmapFactory.decodeStream(it, null, dimensions)
            }
            assertTrue("Downloaded file must be real artwork, not a saved verification response",
                dimensions.outWidth > 500 && dimensions.outHeight > 500)
            instrumentation.runOnMainSync { page.findViewById<View>(R.id.detail_back).performClick() }
            awaitResumed(MainActivity::class.java)
            checkMaterials(main)
            instrumentation.runOnMainSync {
                assertEquals(1, descendants(main.window.decorView).count { it.id == R.id.glass_bottom_navigation })
            }
            capturePage("detail_action_return_home", main)
        } finally {
            instrumentation.runOnMainSync { detail?.finish(); main.finish() }
            favorites.edit().apply {
                if (originalFavorites == null) remove("folders") else putString("folders", originalFavorites)
            }.commit()
        }
    }

    private fun tapMenuItem(label: String) {
        val deadline = android.os.SystemClock.uptimeMillis() + 15_000
        var bounds: Rect? = null
        while (bounds == null && android.os.SystemClock.uptimeMillis() < deadline) {
            instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
                val nodes = root.findAccessibilityNodeInfosByText(label)
                nodes.firstOrNull { it.text?.toString() == label }?.let { node ->
                    bounds = Rect().also(node::getBoundsInScreen)
                }
                nodes.forEach { it.recycle() }
                root.recycle()
            }
            if (bounds == null) Thread.sleep(100)
        }
        val target = requireNotNull(bounds) { "Actual anchored menu does not contain $label" }
        val down = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action,
                target.exactCenterX(), target.exactCenterY(), 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
    }

    private fun awaitFilterContent(page: Activity) {
        val deadline = android.os.SystemClock.uptimeMillis() + 90_000
        var loading = true
        while (loading && android.os.SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync { loading = page.findViewById<View>(R.id.filter_loading).visibility == View.VISIBLE }
            if (loading) Thread.sleep(200)
        }
        assertFalse("Do not leave Filters while its real request/verification is still in progress", loading)
    }

    private fun launch(type: Class<out Activity>, extra: Pair<String, Int>? = null): Activity {
        val intent = Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        extra?.let { intent.putExtra(it.first, it.second) }
        instrumentation.runOnMainSync { context.startActivity(intent) }
        return awaitResumed(type)
    }

    private fun awaitResumed(type: Class<out Activity>): Activity {
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

    private fun verifyDetailZoom(page: Activity) {
        val image = page.findViewById<android.widget.ImageView>(R.id.detail_image)
        var centerX = 0f
        var centerY = 0f
        var halfSpan = 0f
        fun scale(): Float {
            var result = 0f
            instrumentation.runOnMainSync {
                val values = FloatArray(9)
                image.imageMatrix.getValues(values)
                result = values[android.graphics.Matrix.MSCALE_X]
            }
            return result
        }
        instrumentation.runOnMainSync {
            val top = Rect()
            val sheet = IntArray(2)
            page.findViewById<View>(R.id.detail_top_island).getGlobalVisibleRect(top)
            page.findViewById<View>(R.id.bottom_sheet).getLocationOnScreen(sheet)
            val bounds = Rect()
            image.getGlobalVisibleRect(bounds)
            centerX = bounds.exactCenterX()
            centerY = (top.bottom + sheet[1]) / 2f
            halfSpan = bounds.width() * 0.12f
            assertTrue("Gesture test needs a real image viewport outside controls", sheet[1] > top.bottom)
        }
        val originalScale = scale()
        capturePage("detail_real_before_zoom", page)
        var downTime = android.os.SystemClock.uptimeMillis()
        fun send(action: Int, count: Int, span: Float, waitForDispatch: Boolean = true) {
            val properties = Array(count) { index -> android.view.MotionEvent.PointerProperties().apply {
                id = index
                toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
            } }
            val coordinates = Array(count) { index -> android.view.MotionEvent.PointerCoords().apply {
                x = centerX + if (index == 0) -span else span
                y = centerY
                pressure = 1f
                size = 1f
            } }
            val event = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), action,
                count, properties, coordinates, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, waitForDispatch)) } finally { event.recycle() }
        }
        send(android.view.MotionEvent.ACTION_DOWN, 1, halfSpan)
        Thread.sleep(50)
        send(android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, halfSpan)
        // Real gestures span multiple frames; Android's scale detector also
        // stabilizes touch size over time before accepting a scale stream.
        for (factor in listOf(1.1f, 1.3f, 1.5f, 1.8f, 2.1f, 2.4f, 2.7f, 3f, 3.2f, 3.4f)) {
            Thread.sleep(40)
            send(android.view.MotionEvent.ACTION_MOVE, 2, halfSpan * factor)
        }
        send(android.view.MotionEvent.ACTION_POINTER_UP or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, halfSpan * 3.4f)
        send(android.view.MotionEvent.ACTION_UP, 1, halfSpan * 3.4f)
        settle()
        assertTrue("Pinch must cross the existing double-tap reset threshold: $originalScale -> ${scale()}", scale() > originalScale * 1.2f)
        capturePage("detail_real_after_zoom", page)
        instrumentation.runOnMainSync {
            assertEquals("Two-finger movement must not start an adjacent-image swipe", 0f, image.translationX, 0.01f)
            assertTrue("Pinch must not open the long-press menu", page.hasWindowFocus())
        }
        repeat(2) { tap ->
            downTime = android.os.SystemClock.uptimeMillis()
            // Queue taps at human timing rather than waiting for a full CPU
            // glass frame between taps; scale moves above must be dispatched.
            send(android.view.MotionEvent.ACTION_DOWN, 1, 0f, false)
            Thread.sleep(30)
            send(android.view.MotionEvent.ACTION_UP, 1, 0f, false)
            if (tap == 0) Thread.sleep(80)
        }
        settle()
        assertEquals("Existing double-tap must restore fit scale", originalScale, scale(), originalScale * 0.03f)
    }

    private fun verifyDetailCachedBackdrop(page: Activity) {
        val artwork = page.findViewById<android.widget.ImageView>(R.id.detail_image)
        val glass = page.findViewById<LiquidGlassView>(R.id.detail_author_panel)
        var original: android.graphics.drawable.Drawable? = null
        instrumentation.runOnMainSync { original = artwork.drawable }
        fun cachedFrame(color: Int): Bitmap {
            instrumentation.runOnMainSync { artwork.setImageDrawable(android.graphics.drawable.ColorDrawable(color)) }
            val deadline = android.os.SystemClock.uptimeMillis() + 30_000
            var cached = false
            while (!cached && android.os.SystemClock.uptimeMillis() < deadline) {
                settle()
                instrumentation.runOnMainSync { cached = !glass.enableDynamicBackground }
            }
            assertTrue("Static artwork must settle without a continuous CPU blur loop", cached)
            return opticalSnapshot(glass)
        }
        try {
            val warm = cachedFrame(Color.rgb(240, 40, 20))
            val cool = cachedFrame(Color.rgb(20, 80, 240))
            try {
                val artworkDifference = pixelDifference(warm, cool)
                assertTrue("Prepared glass texture must refresh after artwork changes ($artworkDifference)", artworkDifference > 5f)
                instrumentation.runOnMainSync { page.findViewById<View>(R.id.detail_root).invalidate() }
                settle()
                val retained = opticalSnapshot(glass)
                try {
                    assertTrue("An unrelated redraw must retain the same real optical texture", pixelDifference(cool, retained) < 2f)
                } finally { retained.recycle() }
            } finally { warm.recycle(); cool.recycle() }
        } finally {
            instrumentation.runOnMainSync { artwork.setImageDrawable(original) }
            settle()
        }
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
        var nextRetry = 0L
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync {
                val author = detail.findViewById<android.widget.TextView>(R.id.detail_uploader).text.toString()
                ready = author.isNotBlank() && author != "未知上传者" &&
                    (detail.findViewById<android.widget.ImageView>(R.id.detail_image).drawable?.intrinsicWidth ?: 0) > 500 &&
                    detail.findViewById<View>(R.id.detail_load_status).visibility == View.GONE
                val status = detail.findViewById<View>(R.id.detail_load_status)
                val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).contains(detail)
                val now = android.os.SystemClock.uptimeMillis()
                if (!ready && resumed && status.visibility == View.VISIBLE && status.isEnabled && now >= nextRetry) {
                    // Use the real recovery action after a service failure or
                    // completed website verification; never bypass verification.
                    status.performClick()
                    nextRetry = now + 5_000
                }
            }
            if (ready) return
            Thread.sleep(250)
        }
        error("ImageDetail image/author did not load; an empty detail screen cannot count as smoke verification")
    }

    private fun awaitDetailComments(detail: Activity) {
        val deadline = android.os.SystemClock.uptimeMillis() + 45_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync {
                val comments = detail.findViewById<ViewGroup>(R.id.detail_comments_preview)
                val tags = detail.findViewById<ViewGroup>(R.id.detail_tags_group)
                ready = descendants(comments).any {
                    it is com.kerybotu.derpibooru.mirror.ui.GlassCommentCard && it.height > 0
                } && tags.childCount > 0 && !tags.isLayoutRequested && !comments.isLayoutRequested
            }
            if (ready) return
            Thread.sleep(250)
        }
        error("Image #1 tags/comments did not finish layout; pending content cannot verify scrolling to the last comment")
    }

    @android.annotation.SuppressLint("UnsafeOptInUsageError")
    private fun verifyLiveVideoThemeChange(main: Activity, mode: AppSettings.Palette, accent: AccentColor) {
        var player: androidx.media3.common.Player? = null
        var artwork: android.graphics.drawable.Drawable? = null
        fun visiblePlayerView() = descendants(main.window.decorView)
            .filterIsInstance<androidx.media3.ui.PlayerView>().first { it.isShown }
        instrumentation.runOnMainSync {
            player = visiblePlayerView().player
            artwork = main.findViewById<android.widget.ImageView>(R.id.video_ambient_background).drawable
            AppSettings.setPalette(context, if (mode == AppSettings.Palette.LIGHT) AppSettings.Palette.DARK else AppSettings.Palette.LIGHT)
            AppSettings.setAccentColor(context, if (accent == AccentColor.BLUE) AccentColor.PURPLE else AccentColor.BLUE)
            PaletteManager.notifyChanged(context)
        }
        settle()
        instrumentation.runOnMainSync {
            assertSame("A live theme update must retain the attached player", player, visiblePlayerView().player)
            assertSame("A live theme update must retain its captured artwork", artwork,
                main.findViewById<android.widget.ImageView>(R.id.video_ambient_background).drawable)
        }
        checkMaterials(main)
        capturePage("${mode}_${accent}_Video_live_switch", main)
        instrumentation.runOnMainSync {
            AppSettings.setPalette(context, mode)
            AppSettings.setAccentColor(context, accent)
            PaletteManager.notifyChanged(context)
        }
        settle()
        instrumentation.runOnMainSync {
            assertSame("Restoring the theme must retain the attached player", player, visiblePlayerView().player)
        }
        awaitPageArtwork(main, "Video")
    }

    @android.annotation.SuppressLint("UnsafeOptInUsageError")
    private fun awaitPageArtwork(main: Activity, page: String) {
        if (page !in listOf("Home", "Video", "Hot")) return
        val deadline = android.os.SystemClock.uptimeMillis() + 90_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync {
                val views = descendants(main.window.decorView)
                ready = when (page) {
                    "Video" -> views.filterIsInstance<androidx.media3.ui.PlayerView>().any {
                        it.isShown && it.player?.playbackState == androidx.media3.common.Player.STATE_READY
                    }
                    "Hot" -> views.filterIsInstance<android.widget.ImageView>().any {
                        it.isShown && it.contentDescription == "精选头图" && it.drawable != null
                    }
                    else -> views.filterIsInstance<android.widget.ImageView>().any {
                        it.isShown && it.id == R.id.image_thumbnail && it.drawable != null
                    }
                }
            }
            if (ready) { settle(); return }
            Thread.sleep(200)
        }
        var state = ""
        instrumentation.runOnMainSync {
            state = descendants(main.window.decorView).filterIsInstance<androidx.media3.ui.PlayerView>()
                .filter { it.isShown }.joinToString { "player=${it.player != null} state=${it.player?.playbackState}" }
        }
        error("$page did not load real artwork within 90 seconds ($state); a loading panel cannot verify backdrop optics")
    }

    private fun checkMaterials(activity: Activity) = checkMaterials(
        activity.window.decorView,
        isResumed(activity)
    )

    private fun isResumed(activity: Activity): Boolean {
        var resumed = false
        instrumentation.runOnMainSync {
            resumed = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).contains(activity)
        }
        return resumed
    }

    private fun capturePage(name: String, activity: Activity) {
        instrumentation.runOnMainSync {
            val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            assertTrue("$name is obscured (${resumed.map { it.javaClass.simpleName }}); it cannot count as page verification",
                resumed.contains(activity))
        }
        capture(name, activity.window.decorView)
    }

    private fun checkMaterials(root: View, activeWindow: Boolean = true) {
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
                val tint = if (GlassWidgetStyle.materialRole(glass) == GlassWidgetStyle.MaterialRole.READING)
                    scheme.glassSurface else scheme.glassTint
                assertEquals("Resolved role tint must reach the renderer", tint, glass.glassTint)
                assertTrue("Liquid Glass keeps its renderer highlight", glass.enableEdgeHighlight)
                assertEquals("API 33+ uses the GPU lens path", android.os.Build.VERSION.SDK_INT >= 33,
                    glass.enableChromaticAberration)
                assertNotNull("Glass uses a prepared or explicitly disabled source", glass.backdropSource)
                assertNotSame("No glass component may fall back to its parent", glass.parent, glass.backdropSource)
                val visible = isActuallyVisible(glass)
                if (activeWindow && visible && android.os.Build.VERSION.SDK_INT >= 31) {
                    assertTrue("Foreground glass keeps dynamic GPU backdrop blur enabled: ${describeGlass(glass)}",
                        glass.enableBackdropBlur)
                    val source = glass.backdropSource
                    assertNotNull("Foreground blur must have a recorded source", source)
                    assertFalse("Foreground blur source must not contain any glass consumer: ${source?.javaClass?.simpleName}",
                        source?.let(::containsGlassConsumer) == true)
                }
                val material = glass.background as? GradientDrawable
                assertNotNull("Safe glass must retain a themed backing drawable", material)
                val hasThemedFill = material?.color?.defaultColor?.let { Color.alpha(it) > 0 } == true ||
                    material?.colors?.any { Color.alpha(it) > 0 } == true
                val hasPreparedLiveSource = glass.enableBackdropBlur &&
                    glass.backdropSource?.let { !containsGlassConsumer(it) } == true
                if (activeWindow && visible) {
                    assertTrue("Visible glass needs either a themed fallback fill or a prepared live backdrop: ${glass.javaClass.simpleName}@${System.identityHashCode(glass)}",
                        hasThemedFill || hasPreparedLiveSource)
                }
                assertTrue("Safe glass must retain its rounded corners", glass.cornerRadius > 0f)
            }
        }
    }

    private fun describeGlass(view: LiquidGlassView): String {
        val bounds = Rect()
        view.getGlobalVisibleRect(bounds)
        val parents = generateSequence(view as View?) { it.parent as? View }
            .take(8)
            .joinToString(" <- ") { node ->
                val id = if (node.id == View.NO_ID) "none" else
                    runCatching { node.resources.getResourceEntryName(node.id) }.getOrDefault(node.id.toString())
                "${node.javaClass.simpleName}(id=$id,visibility=${node.visibility},alpha=${node.alpha},size=${node.width}x${node.height})"
            }
        return "${view.javaClass.simpleName}@${System.identityHashCode(view)} blur=${view.enableBackdropBlur} " +
            "source=${view.backdropSource?.javaClass?.simpleName}@${view.backdropSource?.let(System::identityHashCode)} " +
            "bounds=$bounds parents=[$parents]"
    }

    private fun isActuallyVisible(view: View): Boolean {
        val bounds = Rect()
        if (!view.isShown || !view.getGlobalVisibleRect(bounds)) return false
        var effectiveAlpha = 1f
        var current: View? = view
        while (current != null) {
            effectiveAlpha *= current.alpha
            if (effectiveAlpha <= 0f) return false
            current = current.parent as? View
        }
        return true
    }

    private fun containsGlassConsumer(view: View): Boolean {
        if (view is LiquidGlassView) return true
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (containsGlassConsumer(view.getChildAt(index))) return true
            }
        }
        return false
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
                    // Media brightness is not a ThemeMode oracle: authentic
                    // glass transmits that media. Theme contrast and actual
                    // optics are measured above over an identical known scene.
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
