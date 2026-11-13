package com.kerybotu.derpibooru.mirror

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.MotionEvent
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.liquidglass.LiquidGlassView
import com.kerybotu.derpibooru.mirror.ui.ImageDetailActivity
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses real Activity windows and labelled local artwork to make transmission measurable offline. */
@RunWith(AndroidJUnit4::class)
class HomeFallbackZoomRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun settle() = Thread.sleep(650)
    private fun current(type: Class<out Activity>): Activity {
        var result: Activity? = null
        main { result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull { type.isInstance(it) } }
        return requireNotNull(result)
    }
    private fun homeFixture(page: Activity) {
        page.findViewById<View>(R.id.home_refresh).apply { background = Pattern(); visibility = View.VISIBLE }
        page.findViewById<View>(R.id.recycler_view).visibility = View.INVISIBLE
        page.findViewById<View>(R.id.startup_overlay).visibility = View.GONE
        page.findViewById<View>(R.id.progress_bar).visibility = View.GONE
        page.findViewById<View>(R.id.main_content_host).visibility = View.GONE
    }
    private fun launch(type: Class<out Activity>, detail: Boolean = false): Activity {
        val intent = Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (detail) intent.putExtra("image_id", 1)
        main { context.startActivity(intent) }
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            var result: Activity? = null
            main { result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull { type.isInstance(it) } }
            result?.let { settle(); return it }
            Thread.sleep(100)
        }
        error("Activity did not resume: " + type)
    }
    private fun shot(name: String) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val dir = File(context.getExternalFilesDir(null), "home-fallback-zoom").apply { mkdirs() }
        File(dir, name + ".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private class Pattern : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            for (y in bounds.top until bounds.bottom step 50) for (x in bounds.left until bounds.right step 50) {
                paint.color = if ((x / 50 + y / 50) % 2 == 0) Color.rgb(35, 130, 225) else Color.rgb(240, 175, 45)
                canvas.drawRect(x.toFloat(), y.toFloat(), (x + 50).toFloat(), (y + 50).toFloat(), paint)
            }
            paint.color = Color.BLACK; paint.textSize = 32f
            canvas.drawText("LOCAL ARTWORK FIXTURE", 25f, 230f, paint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(filter: ColorFilter?) {}
        @Deprecated("Deprecated") override fun getOpacity() = PixelFormat.OPAQUE
    }
    @Test fun homeFallbackKeepsTransmissiveMaterialAcrossMotionAndTheme() {
        val saved = AppSettings.isGlassPerformanceFallbackEnabled(context)
        val palette = AppSettings.getPalette(context)
        AppSettings.setGlassPerformanceFallbackEnabled(context, true)
        val page = launch(MainActivity::class.java)
        try {
            main {
                homeFixture(page)
            }
            for (mode in listOf(AppSettings.Palette.LIGHT, AppSettings.Palette.DARK)) {
                main {
                    AppSettings.setPalette(context, mode)
                    PaletteManager.notifyChanged(context)
                    page.findViewById<View>(R.id.home_refresh).background = Pattern()
                }
                settle()
                main { homeFixture(page) }
                val bar = page.findViewById<View>(R.id.app_bar_layout)
                val glass = page.findViewById<LiquidGlassView>(R.id.header_glass)
                main { (bar as com.google.android.material.appbar.AppBarLayout).setLifted(true) }
                settle()
                shot("home_fixture_" + mode + "_lifted")
                val originalMaterial = glass.background
                for (dy in listOf(0, 18, 36, 0)) {
                    main {
                        val field = MainActivity::class.java.getDeclaredField("homeTopIslandController").apply { isAccessible = true }
                        val controller = field.get(page)!!
                        controller.javaClass.getMethod("reset").invoke(controller)
                        controller.javaClass.getMethod("onScrolled", Int::class.javaPrimitiveType).invoke(controller, dy)
                        val background = glass.background
                        assertSame("Motion must not rebuild or replace the glass coating", originalMaterial, background)
                        val pixels = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
                        val originalBounds = Rect(background.bounds)
                        background.setBounds(0, 0, 200, 100); background.draw(Canvas(pixels))
                        background.bounds = originalBounds
                        val alpha = Color.alpha(pixels.getPixel(100, 50))
                        android.util.Log.i("HomeFallbackZoom", mode.toString() + " dy=" + dy + " bg=" + background.javaClass.simpleName + " alpha=" + alpha + " tint=" + Integer.toHexString(glass.glassTint) + " barBg=" + bar.background.javaClass.simpleName)
                        assertTrue("Fallback coating must stay transmissive", alpha < 240)
                        val parentMaterial = bar.background as? com.google.android.material.shape.MaterialShapeDrawable
                        android.util.Log.i("HomeFallbackZoom", "parentFill=" + parentMaterial?.fillColor?.defaultColor + " tint=" + parentMaterial?.tintList?.defaultColor)
                        assertEquals("AppBar host must not add a second opaque coat", 0, Color.alpha(parentMaterial?.fillColor?.defaultColor ?: (bar.background as? ColorDrawable)?.color ?: 0))
                        pixels.recycle()
                    }
                    settle(); shot("home_fixture_" + mode + "_dy" + dy)
                    assertTransmittedArtwork(glass)
                }
                main {
                    val controller = MainActivity::class.java.getDeclaredField("homeTopIslandController").apply { isAccessible = true }.get(page)!!
                    controller.javaClass.getMethod("onScrolled", Int::class.javaPrimitiveType).invoke(controller, 40)
                    controller.javaClass.getMethod("onScrollIdle", androidx.recyclerview.widget.RecyclerView::class.java).invoke(controller, page.findViewById<View>(R.id.recycler_view))
                }
                for (frame in 1..3) {
                    Thread.sleep(60)
                    shot("home_fixture_" + mode + "_animation" + frame)
                    main { assertSame(originalMaterial, glass.background) }
                }
            }
        } finally { main { AppSettings.setPalette(context, palette); AppSettings.setGlassPerformanceFallbackEnabled(context, saved); page.finish() } }
    }
    @Test fun zoomViewportReachesBehindFloatingIslandsInBothOrientations() {
        var page = launch(ImageDetailActivity::class.java, true)
        try {
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                main { page.requestedOrientation = orientation }
                settle(); settle()
                page = current(ImageDetailActivity::class.java)
                val image = page.findViewById<ImageView>(R.id.detail_image)
                main {
                    val job = ImageDetailActivity::class.java.getDeclaredField("detailJob").apply { isAccessible = true }.get(page) as? kotlinx.coroutines.Job
                    job?.cancel()
                    val art = Bitmap.createBitmap(1000, 1400, Bitmap.Config.ARGB_8888)
                    Pattern().apply { setBounds(0, 0, 1000, 1400); draw(Canvas(art)) }
                    image.setImageDrawable(BitmapDrawable(page.resources, art))
                    image.visibility = View.VISIBLE
                    page.findViewById<View>(R.id.detail_spoiler_cover).visibility = View.GONE
                }
                settle(); shot("detail_fixture_" + orientation + "_fit")
                val fitMatrix = FloatArray(9)
                main { image.imageMatrix.getValues(fitMatrix) }
                // Actual double-tap event stream exercises GestureDetector and matrix clamping.
                doubleTap(image)
                settle(); shot("detail_fixture_" + orientation + "_zoom")
                main {
                    val zoomMatrix = FloatArray(9).also { image.imageMatrix.getValues(it) }
                    assertTrue("Double tap must actually magnify the artwork", zoomMatrix[Matrix.MSCALE_X] > fitMatrix[Matrix.MSCALE_X] * 2f)
                    val root = page.findViewById<View>(R.id.detail_root)
                    android.util.Log.i("HomeFallbackZoom", "orientation=" + orientation + " root=" + root.width + "x" + root.height + " image=" + image.left + "," + image.top + "," + image.right + "," + image.bottom)
                    assertEquals("Application top island must not cut the zoom viewport", 0, image.top)
                    assertEquals("Application info card must not cut the zoom viewport", root.height, image.bottom)
                }
                drag(image)
                settle(); shot("detail_fixture_" + orientation + "_drag")
                pinch(image)
                settle(); shot("detail_fixture_" + orientation + "_pinch")
                doubleTap(image)
                settle(); shot("detail_fixture_" + orientation + "_reset")
                main {
                    val reset = FloatArray(9).also { image.imageMatrix.getValues(it) }
                    assertArrayEquals("Reset restores the initial fit without changing the full zoom canvas", fitMatrix, reset, .1f)
                }
            }
        } finally { main { page.finish() } }
    }
    private fun doubleTap(view: View) {
        repeat(2) {
            main {
                val time = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, view.width / 2f, view.height / 2f, 0)
                val up = MotionEvent.obtain(time, time + 30, MotionEvent.ACTION_UP, view.width / 2f, view.height / 2f, 0)
                view.dispatchTouchEvent(down); view.dispatchTouchEvent(up); down.recycle(); up.recycle()
            }
            Thread.sleep(70)
        }
    }
    private fun assertTransmittedArtwork(glass: View) {
        val rect = Rect()
        main { glass.getGlobalVisibleRect(rect) }
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val y = (rect.bottom - 15).coerceIn(0, screenshot.height - 1)
            val blue = (rect.centerX() - 100..rect.centerX() + 100 step 5).map { Color.blue(screenshot.getPixel(it, y)) }
            val range = blue.max() - blue.min()
            android.util.Log.i("HomeFallbackZoom", "transmittedArtworkBlueRange=" + range + " bounds=" + rect)
            assertTrue("Actual pixels must transmit both backdrop colors, not an opaque coat", range > 15)
        } finally { screenshot.recycle() }
    }
    private fun drag(view: View) {
        val start = SystemClock.uptimeMillis()
        for (step in 0..6) {
            main {
                val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 6 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                MotionEvent.obtain(start, start + step * 30, action, view.width / 2f + step * 18, view.height / 2f + step * 14, 0).let { view.dispatchTouchEvent(it); it.recycle() }
            }
            Thread.sleep(30)
        }
    }
    private fun pinch(view: View) {
        val start = SystemClock.uptimeMillis()
        val properties = Array(2) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        for (step in 0..9) {
            main {
                val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 1 -> MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT); 8 -> MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT); 9 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                val coords = Array(2) { index -> MotionEvent.PointerCoords().apply { x = view.width / 2f + (if (index == 0) -1 else 1) * (60 + step * 10); y = view.height / 2f; pressure = 1f; size = 1f } }
                MotionEvent.obtain(start, start + step * 35, action, if (step == 0 || step == 9) 1 else 2, properties, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0).let { view.dispatchTouchEvent(it); it.recycle() }
            }
            Thread.sleep(35)
        }
    }
}
