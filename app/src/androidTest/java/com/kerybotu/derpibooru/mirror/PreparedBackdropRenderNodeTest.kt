package com.kerybotu.derpibooru.mirror

import android.graphics.RenderNode
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.liquidglass.LiquidGlassView
import com.kerybotu.derpibooru.mirror.ui.BackdropRenderNodeCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreparedBackdropRenderNodeTest {
    @Test
    fun consumerDrawUsesPreparedRenderNodeWithoutRedrawingSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        lateinit var source: CountingSourceView
        lateinit var consumer: LiquidGlassView
        lateinit var node: RenderNode

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            source = CountingSourceView(context).apply { layout(0, 0, 160, 120) }
            consumer = LiquidGlassView(context).apply { layout(0, 0, 80, 64) }
            assertTrue(BackdropRenderNodeCache.prepareForTest(source))
            assertEquals(1, source.drawCount)
            node = RenderNode("prepared-backdrop-test")
            val canvas = node.beginRecording(80, 64)
            val captureType = Class.forName("com.example.liquidglass.BackdropCapture")
            val capture = captureType.getDeclaredConstructor().newInstance()
            captureType.getMethod("draw", android.graphics.Canvas::class.java, View::class.java, View::class.java)
                .invoke(capture, canvas, source, consumer)
            node.endRecording()
            assertEquals("consumer capture must only reference the prepared node", 1, source.drawCount)
            BackdropRenderNodeCache.releaseForTest(source)
        }
    }

    @Test
    fun sourceContainingGlassConsumerIsRejectedBeforeRecording() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val source = FrameLayout(context).apply { layout(0, 0, 160, 120) }
            source.addView(LiquidGlassView(context))

            assertFalse(BackdropRenderNodeCache.prepareForTest(source))

            BackdropRenderNodeCache.releaseForTest(source)
        }
    }

    private class CountingSourceView(context: android.content.Context) : View(context) {
        var drawCount = 0
            private set

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            drawCount++
            canvas.drawColor(android.graphics.Color.CYAN)
        }
    }
}
