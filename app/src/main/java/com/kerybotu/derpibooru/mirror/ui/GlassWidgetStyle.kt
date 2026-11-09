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
import com.kerybotu.derpibooru.mirror.CrashDiagnostics
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Build
import java.lang.ref.WeakReference
import androidx.core.graphics.ColorUtils
import android.graphics.Canvas

/** App theme and bounded rendering shared by the library's ready-made widgets. */
object GlassWidgetStyle {
    enum class MaterialRole { FLOATING, READING }
    private val materialRoles = java.util.WeakHashMap<LiquidGlassView, MaterialRole>()
    private val renderingStates = java.util.WeakHashMap<LiquidGlassView, Boolean>()
    private val renderingSources = java.util.WeakHashMap<LiquidGlassView, WeakReference<View>>()
    private val requestedRenderingSources = java.util.WeakHashMap<LiquidGlassView, WeakReference<View>>()
    private val renderingSourceModes = java.util.WeakHashMap<LiquidGlassView, BackdropSourceMode>()
    private val renderingStaticColors = java.util.WeakHashMap<LiquidGlassView, Int>()
    private val disabledSources = java.util.WeakHashMap<LiquidGlassView, WeakReference<View>>()
    private val trackedViews = java.util.Collections.newSetFromMap(java.util.WeakHashMap<LiquidGlassView, Boolean>())
    fun materialRole(view: LiquidGlassView): MaterialRole = materialRoles[view]
        ?: if (view is LiquidGlassListItem) MaterialRole.READING else MaterialRole.FLOATING

    fun textColor(context: android.content.Context) = PaletteManager.colors(context).glassText
    fun secondaryTextColor(context: android.content.Context) = PaletteManager.colors(context).glassSecondaryText
    fun iconColor(context: android.content.Context) = PaletteManager.colors(context).glassText

    fun isPerformanceFallback(context: android.content.Context): Boolean =
        com.kerybotu.derpibooru.mirror.AppSettings.isGlassPerformanceFallbackEnabled(context)

    /** Binds only a prepared, consumer-free source display list to the GPU glass renderer. */
    fun setRenderingActive(
        view: LiquidGlassView,
        active: Boolean,
        source: View?,
        sourceMode: BackdropSourceMode = if (source == null) BackdropSourceMode.DISABLED else BackdropSourceMode.EXPLICIT_SOURCE
    ) {
        trackDiagnostics(view)
        val requestedSource = when (sourceMode) {
            BackdropSourceMode.EXPLICIT_SOURCE -> source
            BackdropSourceMode.AUTO_PARENT -> view.parent as? View
            BackdropSourceMode.DISABLED -> null
        }
        val staticColor = (requestedSource?.background as? ColorDrawable)?.color
            ?: PaletteManager.colors(view.context).glassSurface
        val safeSource = if (active && requestedSource != null &&
            BackdropRenderNodeCache.bind(view, requestedSource)
        ) requestedSource else disabledSource(view)
        val previousActive = renderingStates[view]
        val previousSource = renderingSources[view]?.get()
        val previousRequestedSource = requestedRenderingSources[view]?.get()
        val previousMode = renderingSourceModes[view]
        val previousStaticColor = renderingStaticColors[view]
        if (previousActive == active && previousSource === safeSource &&
            previousRequestedSource === requestedSource && previousMode == sourceMode &&
            previousStaticColor == staticColor
        ) return

        renderingStates[view] = active
        renderingSources[view] = WeakReference(safeSource)
        requestedRenderingSources[view] = requestedSource?.let(::WeakReference)
        renderingSourceModes[view] = sourceMode
        renderingStaticColors[view] = staticColor
        CrashDiagnostics.recordEvent(
            "LiquidGlassView",
                "edge owner=${view.javaClass.simpleName}@${System.identityHashCode(view)} active=$active mode=$sourceMode requested=${requestedSource?.javaClass?.simpleName ?: "none"}@${requestedSource?.let { System.identityHashCode(it) } ?: 0} selected=${safeSource.javaClass.simpleName}@${System.identityHashCode(safeSource)} backend=prepared-render-node"
        )
        BackdropRenderNodeCache.unbind(view)
        if (active && safeSource === requestedSource) BackdropRenderNodeCache.bind(view, safeSource)
        view.backdropSource = safeSource
        configureRenderer(view, staticColor, active && safeSource === requestedSource)
    }

    private fun disabledSource(view: LiquidGlassView): View {
        disabledSources[view]?.get()?.let { return it }
        return View(view.context).apply {
            layout(0, 0, 1, 1)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            disabledSources[view] = WeakReference(this)
            BackdropRenderNodeCache.prepareForTest(this)
        }
    }

    private fun configureRenderer(view: LiquidGlassView, color: Int, active: Boolean) {
        view.enableBackdropBlur = active && Build.VERSION.SDK_INT >= 31
        view.enableDynamicBackground = false
        view.enableSensorHighlight = false
        view.globalDownsampleFactor = if (Build.VERSION.SDK_INT < 33) 0.5f else 1f
        view.blurAmount = if (active) 0.045f else 0f
        view.enableChromaticAberration = Build.VERSION.SDK_INT >= 33
        view.enableChromaticDispersion = false
        view.enableEdgeHighlight = true
        view.edgeHighlightOpacity = 35f
        view.saturation = 125f
        view.adaptiveLensScale = true
        view.dispersionStrength = 0.08f
        view.background = BackdropFallback(view, color).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = view.cornerRadius
            setStroke(view.resources.displayMetrics.density.toInt().coerceAtLeast(1), PaletteManager.colors(view.context).glassBorder)
        }
    }

    private fun hasActivePreparedSource(view: LiquidGlassView): Boolean {
        if (renderingStates[view] != true) return false
        val selected = renderingSources[view]?.get() ?: return false
        val requested = requestedRenderingSources[view]?.get() ?: return false
        return selected === requested && selected !== disabledSources[view]?.get()
    }

    private fun trackDiagnostics(view: LiquidGlassView) {
        if (!trackedViews.add(view)) return
        CrashDiagnostics.recordEvent("LiquidGlassView", "created view=${view.javaClass.simpleName}@${System.identityHashCode(view)}")
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                CrashDiagnostics.recordEvent("LiquidGlassView", "attach view=${v.javaClass.simpleName}@${System.identityHashCode(v)}")
            }

            override fun onViewDetachedFromWindow(v: View) {
                CrashDiagnostics.recordEvent("LiquidGlassView", "detach view=${v.javaClass.simpleName}@${System.identityHashCode(v)}")
            }
        })
    }

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
        // Restore the upstream default optical path. On pre-33 devices the
        // displacement-map aberration pass IS the refraction implementation;
        // disabling both optical effects leaves only a blurred flat panel.
        // Downsample the capture instead of deleting that pass. API 33+ keeps
        // the original GPU lens (SDF refraction, dispersion and highlights).
        view.enableChromaticDispersion = false
        view.enableChromaticAberration = Build.VERSION.SDK_INT >= 31
        view.globalDownsampleFactor = if (Build.VERSION.SDK_INT < 33) 0.5f else 1f
        view.enableBackdropBlur = false
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
        if (renderingStates[view] == null) view.backdropSource = disabledSource(view)
        view.background = BackdropFallback(view, colors.glassSurface).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = view.cornerRadius
            setStroke(density.toInt().coerceAtLeast(1), colors.glassBorder)
        }
        view.enableDynamicBackground = false
        view.enableSensorHighlight = false
        configureRenderer(view, colors.glassSurface, active = false)
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
        renderingStates[view]?.let { active ->
            val requested = requestedRenderingSources[view]?.get()
            val selected = renderingSources[view]?.get() ?: disabledSource(view)
            view.backdropSource = selected
            configureRenderer(view, colors.glassSurface, active && selected === requested)
        }
    }

    internal fun createSafeMaterialBackground(
        colors: PaletteDefinitions.Scheme,
        cornerRadius: Float,
        density: Float
    ): GradientDrawable {
        val highlight = ColorUtils.blendARGB(colors.glassSurface, android.graphics.Color.WHITE, 0.24f)
        val accentTint = ColorUtils.blendARGB(colors.glassSurface, colors.primary, 0.16f)
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(highlight, colors.glassSurface, accentTint)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            this.cornerRadius = cornerRadius
            setStroke(density.toInt().coerceAtLeast(1), colors.glassBorder)
        }
    }

    private class BackdropFallback(view: LiquidGlassView, private val fallback: Int) : GradientDrawable() {
        private val owner = WeakReference(view)

        override fun draw(canvas: Canvas) {
            val view = owner.get()
            val fill = if (view != null && hasActivePreparedSource(view)) {
                android.graphics.Color.TRANSPARENT
            } else fallback
            if (color?.defaultColor != fill) setColor(fill)
            super.draw(canvas)
        }
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
        if (!renderingStates.containsKey(view)) {
            setRenderingActive(view, false, null, BackdropSourceMode.DISABLED)
        }
    }

}
