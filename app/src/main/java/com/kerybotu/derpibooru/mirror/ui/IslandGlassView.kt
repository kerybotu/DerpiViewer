package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassView
import com.kerybotu.derpibooru.mirror.PaletteDefinitions

/** Shared glass material for the home header and bottom navigation surfaces. */
open class IslandGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LiquidGlassView(context, attrs, defStyleAttr) {
    private var renderingActive = false

    init {
        val density = resources.displayMetrics.density
        isClickable = false
        isFocusable = false
        enablePressEffect = false
        collectFrameStats = false
        material = GlassMaterial.REGULAR
        cornerRadius = Ui2DesignSystem.Shape.navigationIsland * density
        blurAmount = 0.045f
        saturation = 125f
        refractionHeight = 48f * density
        bevelWidth = 16f * density
        dispersionStrength = 0.08f
    }

    fun setPalette(colors: PaletteDefinitions.Scheme) {
        val light = Color.luminance(colors.surface) > 0.5f
        overLight = light
        glassTint = ColorUtils.setAlphaComponent(colors.surface, if (light) 56 else 88)
        onAppearanceChanged(light)
        invalidate()
    }

    open fun setRenderingActive(active: Boolean, source: View?) {
        val backdrop = if (active) source else null
        if (renderingActive == active && backdropSource === backdrop) return
        renderingActive = active
        backdropSource = backdrop
        enableDynamicBackground = active
        enableSensorHighlight = active
        enableAdaptiveTint = active
        if (active) refreshAccessibilityState()
    }
}
