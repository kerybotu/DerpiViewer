package com.kerybotu.derpibooru.mirror

import android.os.Build
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.liquidglass.LiquidGlassView
import com.kerybotu.derpibooru.mirror.ui.BackdropRenderNodeCache
import com.kerybotu.derpibooru.mirror.ui.BackdropSourceMode
import com.kerybotu.derpibooru.mirror.ui.GlassWidgetStyle
import com.kerybotu.derpibooru.mirror.ui.ScrollContentBackdrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackdropRenderingSafetyTest {
    @Test
    fun rejectedSourceKeepsThemedFallbackMaterialVisible() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val source = FrameLayout(context).apply { addView(LiquidGlassView(context)) }
            val glass = LiquidGlassView(context)
            GlassWidgetStyle.apply(glass)
            GlassWidgetStyle.setRenderingActive(glass, true, source, BackdropSourceMode.EXPLICIT_SOURCE)

            val material = glass.background as GradientDrawable
            val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
            try {
                material.setBounds(0, 0, bitmap.width, bitmap.height)
                material.draw(Canvas(bitmap))
                assertFalse("Rejected source must disable live blur", glass.enableBackdropBlur)
                assertTrue("Rejected source must keep a themed material fill",
                    Color.alpha(bitmap.getPixel(20, 20)) > 0)
            } finally {
                bitmap.recycle()
                BackdropRenderNodeCache.releaseForTest(source)
            }
        }
    }

    @Test
    fun explicitSafeSourceRestoresGpuBlurAndOpticalMaterial() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val source = FrameLayout(context).apply { addView(View(context)) }
            val glass = LiquidGlassView(context)
            GlassWidgetStyle.apply(glass)
            GlassWidgetStyle.setRenderingActive(glass, true, source, BackdropSourceMode.EXPLICIT_SOURCE)

            assertSame(source, glass.backdropSource)
            assertEquals(Build.VERSION.SDK_INT >= 31, glass.enableBackdropBlur)
            assertTrue(glass.enableChromaticAberration)
            assertTrue(glass.enableEdgeHighlight)
            assertTrue(glass.dispersionStrength > 0f)
            assertNull(LiquidGlassView::class.java.getDeclaredField("customBackdropCapture").apply { isAccessible = true }.get(glass))
        }
    }

    @Test
    fun sourceWithGlassDescendantIsRejected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val source = FrameLayout(context).apply { addView(LiquidGlassView(context)) }
            assertFalse(BackdropRenderNodeCache.prepareForTest(source))
            BackdropRenderNodeCache.releaseForTest(source)
        }
    }

    @Test
    fun preparedScrollSourceDrawsOrdinaryContentButOmitsGlassViews() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val scroll = ScrollView(context)
            val content = FrameLayout(context)
            val glassSurface = LiquidGlassView(context).apply {
                setBackgroundColor(Color.GREEN)
            }
            val glassContent = View(context).apply { setBackgroundColor(Color.RED) }
            glassSurface.addView(glassContent, FrameLayout.LayoutParams(50, 50))
            val ordinaryContent = View(context).apply {
                setBackgroundColor(Color.BLUE)
            }
            val panel = FrameLayout(context)
            panel.addView(ordinaryContent, FrameLayout.LayoutParams(100, 100))
            panel.addView(glassSurface, FrameLayout.LayoutParams(100, 100))
            content.addView(panel, FrameLayout.LayoutParams(100, 100))
            scroll.addView(content, FrameLayout.LayoutParams(100, 100))
            val source = ScrollContentBackdrop(context, scroll).apply {
                setBackgroundColor(Color.WHITE)
                layout(0, 0, 100, 100)
                excludeRoot(glassSurface, panel)
            }
            scroll.layout(0, 0, 100, 100)
            content.layout(0, 0, 100, 100)
            panel.layout(0, 0, 100, 100)
            glassSurface.layout(0, 0, 100, 100)
            glassContent.layout(0, 0, 50, 50)
            ordinaryContent.layout(0, 0, 100, 100)
            val otherConsumer = LiquidGlassView(context)
            val excludedResult = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            val sharedResult = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

            assertTrue(source.drawPreparedBackdrop(Canvas(excludedResult), glassSurface))
            assertTrue(source.drawPreparedBackdrop(Canvas(sharedResult), otherConsumer))

            assertEquals("A consumer's own content root must be absent from its source", Color.WHITE,
                excludedResult.getPixel(50, 50))
            assertEquals("Other consumers' ordinary artwork remains transmissive", Color.RED,
                sharedResult.getPixel(25, 25))
            assertEquals("Other ordinary page content remains transmissive", Color.BLUE,
                sharedResult.getPixel(50, 50))
            excludedResult.recycle()
            sharedResult.recycle()
        }
    }

    @Test
    fun disabledModeUsesConcreteEmptySourceAndNeverParentFallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val glass = LiquidGlassView(context)
            GlassWidgetStyle.apply(glass)
            GlassWidgetStyle.setRenderingActive(glass, false, null, BackdropSourceMode.DISABLED)

            assertNotNull(glass.backdropSource)
            assertFalse(glass.backdropSource === glass.parent)
            assertFalse(glass.enableBackdropBlur)
            assertFalse(glass.enableDynamicBackground)
        }
    }

    @Test
    fun themeReapplicationRetainsTheSelectedSourceMode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val source = View(context)
            val glass = LiquidGlassView(context)
            GlassWidgetStyle.apply(glass)
            GlassWidgetStyle.setRenderingActive(glass, true, source, BackdropSourceMode.EXPLICIT_SOURCE)
            GlassWidgetStyle.apply(glass, colors = PaletteManager.colors(context))

            assertSame(source, glass.backdropSource)
            assertEquals(Build.VERSION.SDK_INT >= 31, glass.enableBackdropBlur)
        }
    }
}
