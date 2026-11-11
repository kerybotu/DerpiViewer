package com.kerybotu.derpibooru.mirror

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageButton
import androidx.appcompat.widget.Toolbar
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kerybotu.derpibooru.mirror.model.Image
import com.kerybotu.derpibooru.mirror.ui.ImageAdapter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainMenuSelectionRegressionTest {
    @Test
    fun cancellingDownloadSelectionRestoresMenuIconAndAction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val main = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("loadMockImages").apply {
                    isAccessible = true
                    invoke(main)
                }
                val adapter = MainActivity::class.java.getDeclaredField("adapter").apply {
                    isAccessible = true
                }.get(main) as ImageAdapter
                val toolbar = main.findViewById<Toolbar>(R.id.toolbar)
                val navigation = (0 until toolbar.childCount).map(toolbar::getChildAt)
                    .filterIsInstance<ImageButton>()
                    .first { it.contentDescription == toolbar.navigationContentDescription }
                val toggle = ImageAdapter::class.java.getDeclaredMethod("toggleSelection", Image::class.java, View::class.java)
                    .apply { isAccessible = true }
                repeat(3) {
                    toggle.invoke(adapter, adapter.displayedItems().first(), toolbar)
                    assertEquals(1, adapter.selectedItems().size)
                    navigation.performClick()
                    assertTrue(adapter.selectedItems().isEmpty())
                    assertEquals("DerpiViewer", toolbar.title.toString())
                    val expected = iconPixels(requireNotNull(main.getDrawable(R.drawable.ic_menu)))
                    val actual = iconPixels(requireNotNull(toolbar.navigationIcon))
                    try {
                        assertTrue("Cancelling download selection must restore the menu glyph, not leave a close icon", expected.sameAs(actual))
                    } finally { expected.recycle(); actual.recycle() }
                    assertEquals(main.getString(R.string.navigation_drawer_open), toolbar.navigationContentDescription)
                }
            }
            onView(withContentDescription(main.getString(R.string.navigation_drawer_open))).perform(click())
            onView(withText("标签")).inRoot(isDialog()).check(matches(isDisplayed()))
        } finally {
            instrumentation.runOnMainSync { main.finish() }
        }
    }

    private fun iconPixels(drawable: Drawable): Bitmap {
        val copy = requireNotNull(drawable.constantState).newDrawable().mutate()
        copy.setTint(Color.BLACK)
        return Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).also {
            copy.setBounds(0, 0, it.width, it.height)
            copy.draw(Canvas(it))
        }
    }
}
