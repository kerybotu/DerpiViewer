package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import com.example.liquidglass.LiquidGlassView
import com.kerybotu.derpibooru.mirror.PaletteDefinitions

/** Home header surface using the same reference material as the library widgets. */
open class IslandGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LiquidGlassView(context, attrs, defStyleAttr) {
    private var renderingActive = false
    private var renderingSourceMode = BackdropSourceMode.AUTO_PARENT

    init {
        isClickable = false
        isFocusable = false
        cornerRadius = Ui2DesignSystem.Shape.navigationIsland * resources.displayMetrics.density
        GlassWidgetStyle.apply(this, Ui2DesignSystem.Shape.topIsland)
    }

    fun setPalette(colors: PaletteDefinitions.Scheme) {
        GlassWidgetStyle.apply(this, cornerRadius / resources.displayMetrics.density, colors)
    }

    open fun setRenderingActive(
        active: Boolean,
        source: View?,
        sourceMode: BackdropSourceMode = if (source == null) BackdropSourceMode.DISABLED else BackdropSourceMode.EXPLICIT_SOURCE
    ) {
        val backdrop = if (active || sourceMode == BackdropSourceMode.EXPLICIT_SOURCE) source else null
        if (renderingActive == active && renderingSourceMode == sourceMode && backdropSource === backdrop) return
        renderingActive = active
        renderingSourceMode = sourceMode
        GlassWidgetStyle.setRenderingActive(this, active, backdrop, sourceMode)
        if (active) refreshAccessibilityState()
    }
}
