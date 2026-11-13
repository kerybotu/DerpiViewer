package com.kerybotu.derpibooru.mirror

import android.graphics.RenderNode
import android.graphics.Matrix
import android.graphics.Bitmap
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

    @Test
    fun preparedBackdropNodeRemainsSourceLocalForLibraryRendererOffset() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val window = FrameLayout(context).apply { layout(0, 0, 500, 500) }
            val source = View(context).apply { layout(0, 0, 500, 500) }
            val consumer = LiquidGlassView(context).apply { layout(24, 120, 224, 220) }
            window.addView(source)
            window.addView(consumer)
            assertTrue(BackdropRenderNodeCache.prepareForTest(source, consumer))
            val bitmap = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)
            val canvas = MatrixRecordingCanvas(bitmap)
            canvas.translate(-24f, -120f)
            BackdropRenderNodeCache.drawPreparedForTest(source, consumer, canvas)
            assertTranslation(canvas.lastNodeMatrix, -24f, -120f)

            consumer.translationY = 64f
            canvas.translate(0f, -64f)
            BackdropRenderNodeCache.drawPreparedForTest(source, consumer, canvas)
            assertTranslation(canvas.lastNodeMatrix, -24f, -184f)
            BackdropRenderNodeCache.releaseForTest(source)
            bitmap.recycle()
        }
    }

    private fun assertTranslation(matrix: Matrix, expectedX: Float, expectedY: Float) {
        val values = FloatArray(9)
        matrix.getValues(values)
        assertEquals(1f, values[Matrix.MSCALE_X], .001f)
        assertEquals(1f, values[Matrix.MSCALE_Y], .001f)
        assertEquals(expectedX, values[Matrix.MTRANS_X], .001f)
        assertEquals(expectedY, values[Matrix.MTRANS_Y], .001f)
    }

    private class MatrixRecordingCanvas(bitmap: android.graphics.Bitmap) : android.graphics.Canvas(bitmap) {
        val lastNodeMatrix = Matrix()

        override fun drawRenderNode(node: RenderNode) {
            getMatrix(lastNodeMatrix)
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
