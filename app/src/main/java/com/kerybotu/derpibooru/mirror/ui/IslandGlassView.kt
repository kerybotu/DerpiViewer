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

    init {
        isClickable = false
        isFocusable = false
        cornerRadius = Ui2DesignSystem.Shape.navigationIsland * resources.displayMetrics.density
        GlassWidgetStyle.applyMaterial(this)
    }

    fun setPalette(colors: PaletteDefinitions.Scheme) {
        GlassWidgetStyle.applyMaterial(this, colors)
        onAppearanceChanged(overLight)
    }

    open fun setRenderingActive(active: Boolean, source: View?) {
        val backdrop = if (active) source else null
        if (renderingActive == active && backdropSource === backdrop) return
        renderingActive = active
        backdropSource = backdrop
        enableDynamicBackground = active
        enableSensorHighlight = active
        if (active) refreshAccessibilityState()
    }
}
