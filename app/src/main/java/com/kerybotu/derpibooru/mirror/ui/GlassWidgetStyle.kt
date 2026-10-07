package com.kerybotu.derpibooru.mirror.ui

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassButton
import com.example.liquidglass.LiquidGlassChip
import com.example.liquidglass.LiquidGlassFab
import com.example.liquidglass.LiquidGlassListItem
import com.example.liquidglass.LiquidGlassView
import com.kerybotu.derpibooru.mirror.PaletteDefinitions
import com.kerybotu.derpibooru.mirror.PaletteManager

/** App theme and bounded rendering shared by the library's ready-made widgets. */
object GlassWidgetStyle {
    fun textColor(context: android.content.Context) = PaletteManager.colors(context).glassText
    fun secondaryTextColor(context: android.content.Context) = PaletteManager.colors(context).glassSecondaryText
    fun iconColor(context: android.content.Context) = PaletteManager.colors(context).glassText

    /** The home header is the reference material for every glass surface. */
    fun applyMaterial(
        view: LiquidGlassView,
        colors: PaletteDefinitions.Scheme = PaletteManager.colors(view.context)
    ) {
        val density = view.resources.displayMetrics.density
        view.material = GlassMaterial.REGULAR
        view.enableAdaptiveTint = true
        view.overLight = !colors.isDark
        view.glassTint = colors.glassTint
        view.enableBackdropBlur = true
        view.blurAmount = 0.045f
        view.saturation = 125f
        view.adaptiveLensScale = true
        view.bevelWidth = 16f * density
        view.refractionHeight = 48f * density
        view.refractionFalloff = 2f
        view.refractionNoFold = false
        view.edgeSoftness = 0f
        view.dispersionStrength = 0.08f
        view.edgeHighlightOpacity = 35f
        // Ancestor transforms cannot be represented by the library's backdrop offset.
        view.enablePressEffect = false
        view.enableShadow = false
        view.collectFrameStats = false
        view.invalidate()
    }

    fun apply(
        view: LiquidGlassView,
        radiusDp: Float = 24f,
        colors: PaletteDefinitions.Scheme = PaletteManager.colors(view.context)
    ) {
        applyMaterial(view, colors)
        view.cornerRadius = radiusDp * view.resources.displayMetrics.density
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height,
                    view.cornerRadius.coerceAtMost(minOf(v.width, v.height) / 2f))
            }
        }
        view.clipToOutline = true
        when (view) {
            is LiquidGlassButton -> view.setTextColor(colors.glassText)
            is LiquidGlassChip -> view.setTextColor(colors.glassText)
            is LiquidGlassFab -> view.setIconTint(colors.glassText)
            is LiquidGlassListItem -> {
                view.setTextColor(colors.glassText)
                view.supportingTextView.setTextColor(colors.glassSecondaryText)
                view.trailingTextView.setTextColor(colors.glassText)
                // Override the library's reduced-opacity trailing icon color as well.
                view.leadingImageView.setColorFilter(colors.glassText)
                view.trailingImageView.setColorFilter(colors.glassSecondaryText)
            }
        }
        view.invalidate()
    }
}
