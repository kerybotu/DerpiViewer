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
import android.graphics.drawable.GradientDrawable

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
        // The library's adaptive tint samples the backdrop and replaces the
        // resolved app tint. That makes a light app theme inherit a dark
        // sample from media/scrim content. PaletteManager is the single
        // source of truth, so keep the library's adaptive sampler disabled
        // and use the resolved semantic glass tokens below.
        view.enableAdaptiveTint = false
        // In v2.0.11 overLight is a renderer contrast flag, not ThemeMode:
        // true adds #33000000 even with adaptive tint disabled (twice on
        // the bitmap fallback). Use REGULAR's neutral highlight pass; app
        // tokens below own both the light/dark base and widget foreground.
        view.overLight = false
        view.glassTint = colors.glassTint
        view.enableBackdropBlur = true
        // Pre-31 uses synchronous bitmap rendering. Native chromatic
        // dispersion on every frame stalls the UI thread on that path;
        // retain backdrop blur/refraction and use the bounded fallback.
        view.enableChromaticDispersion = android.os.Build.VERSION.SDK_INT >= 31
        view.enableChromaticAberration = android.os.Build.VERSION.SDK_INT >= 31
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
        // Keep the resolved theme visible when realtime backdrop sampling is
        // unavailable. The library's sampled layer is additive to this base.
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = view.cornerRadius
            setColor(colors.glassSurface)
            setStroke((density).toInt().coerceAtLeast(1), colors.glassBorder)
        }
        view.elevation = 0f
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

    fun apply(
        view: LiquidGlassView,
        radiusDp: Float = 24f,
        colors: PaletteDefinitions.Scheme = PaletteManager.colors(view.context)
    ) {
        applyMaterial(view, colors)
        view.cornerRadius = radiusDp * view.resources.displayMetrics.density
        (view.background as? GradientDrawable)?.cornerRadius = view.cornerRadius
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height,
                    view.cornerRadius.coerceAtMost(minOf(v.width, v.height) / 2f))
            }
        }
        view.clipToOutline = true
    }
}
