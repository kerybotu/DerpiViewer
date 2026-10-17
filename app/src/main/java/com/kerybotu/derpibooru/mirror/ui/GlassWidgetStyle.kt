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
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import java.lang.ref.WeakReference

/** App theme and bounded rendering shared by the library's ready-made widgets. */
object GlassWidgetStyle {
    enum class MaterialRole { FLOATING, READING }
    private val materialRoles = java.util.WeakHashMap<LiquidGlassView, MaterialRole>()

    fun materialRole(view: LiquidGlassView): MaterialRole = materialRoles[view]
        ?: if (view is LiquidGlassListItem) MaterialRole.READING else MaterialRole.FLOATING

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
        // Dense text needs the existing reading-surface coverage over bright
        // artwork. It is still a tint on the optical result, never a second
        // opaque background. Floating navigation keeps its lighter coating.
        view.glassTint = if (materialRole(view) == MaterialRole.READING) colors.glassSurface else colors.glassTint
        view.enableBackdropBlur = true
        // Restore the upstream default optical path. On pre-33 devices the
        // displacement-map aberration pass IS the refraction implementation;
        // disabling both optical effects leaves only a blurred flat panel.
        // Downsample the capture instead of deleting that pass. API 33+ keeps
        // the original GPU lens (SDF refraction, dispersion and highlights).
        view.enableChromaticDispersion = false
        view.enableChromaticAberration = true
        view.globalDownsampleFactor = if (Build.VERSION.SDK_INT < 33) 0.5f else 1f
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
        view.enableEdgeHighlight = true
        // Ancestor transforms cannot be represented by the library's backdrop offset.
        view.enablePressEffect = false
        view.enableShadow = false
        view.collectFrameStats = false
        // Only use the solid fallback before a source is available. A live
        // surface must expose its backdrop, not stack two material base coats.
        view.background = BackdropFallback(view, colors.glassSurface).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = view.cornerRadius
            setStroke((density).toInt().coerceAtLeast(1), colors.glassBorder)
        }
        // Preserve the existing island/host elevation; do not erase its shadow
        // on each theme refresh. The native shadow is clipped inside the view.
        if (Build.VERSION.SDK_INT >= 28) {
            view.outlineAmbientShadowColor = colors.glassShadow
            view.outlineSpotShadowColor = colors.glassShadow
        }
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
        colors: PaletteDefinitions.Scheme = PaletteManager.colors(view.context),
        role: MaterialRole = materialRole(view)
    ) {
        materialRoles[view] = role
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

    private class BackdropFallback(view: LiquidGlassView, private val fallback: Int) : GradientDrawable() {
        private val owner = WeakReference(view)

        override fun draw(canvas: Canvas) {
            val view = owner.get()
            val source = view?.backdropSource ?: view?.parent as? View
            val fill = if (source != null && source.width > 0 && source.height > 0) Color.TRANSPARENT else fallback
            if (color?.defaultColor != fill) setColor(fill)
            super.draw(canvas)
        }
    }
}
