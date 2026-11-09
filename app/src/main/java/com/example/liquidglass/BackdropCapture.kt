package com.example.liquidglass

import android.graphics.Canvas
import android.view.View
import com.example.liquidglass.LiquidGlassView
import com.kerybotu.derpibooru.mirror.ui.BackdropRenderNodeCache

/** AAR ABI replacement: consumers reference a prepared source node and never redraw a View tree. */
class BackdropCapture {
    fun draw(canvas: Canvas, source: View, glass: View) {
        BackdropRenderNodeCache.drawPrepared(source, glass as? LiquidGlassView, canvas)
    }
}
