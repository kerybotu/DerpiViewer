package com.kerybotu.derpibooru.mirror.ui

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.GlassAccessibilityMode
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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

internal data class GlassRenderingPlan(
    val captureBackdrop: Boolean,
    val backdropBlur: Boolean,
    val refraction: Boolean,
    val material: GlassMaterial,
    val accessibilityMode: GlassAccessibilityMode
) {
    companion object {
        fun resolve(
            fallbackEnabled: Boolean,
            requestedActive: Boolean,
            sourceReady: Boolean = true,
            apiLevel: Int = 36
        ): GlassRenderingPlan {
            val preparedBackdropSupported = apiLevel >= 29
            val useSafeMaterial = fallbackEnabled || !requestedActive || !sourceReady || !preparedBackdropSupported
            return GlassRenderingPlan(
                captureBackdrop = requestedActive && sourceReady && !fallbackEnabled && preparedBackdropSupported,
                backdropBlur = requestedActive && sourceReady && !fallbackEnabled && preparedBackdropSupported,
                refraction = requestedActive && sourceReady && !fallbackEnabled && preparedBackdropSupported,
                material = if (useSafeMaterial) GlassMaterial.CLEAR else GlassMaterial.REGULAR,
                // AUTO can select the library's opaque fallback when Android enables
                // reduced transparency. Safe app materials already disable costly effects.
                accessibilityMode = if (useSafeMaterial) GlassAccessibilityMode.FORCE_FULL
                    else GlassAccessibilityMode.AUTO
            )
        }
    }
}

/** One renderer-free translucent material shared by every glass widget in safe modes. */
internal object GlassFallbackMaterial {
    data class Spec(val fillColor: Int, val borderColor: Int, val highlightColor: Int) {
        val fillAlpha: Float get() = ((fillColor ushr 24) and 0xFF) / 255f
    }

    fun resolve(isDark: Boolean): Spec = if (isDark) {
        Spec(0xC7171A20.toInt(), 0x38FFFFFF, 0x1FFFFFFF)
    } else {
        Spec(0xC7F4F7FA.toInt(), 0x8CFFFFFF.toInt(), 0x59FFFFFF)
    }

    fun drawable(spec: Spec, cornerRadius: Float, strokeWidth: Float, layeredEdges: Boolean = false): Drawable =
        TranslucentGlassDrawable(spec, cornerRadius, strokeWidth, layeredEdges)

    private class TranslucentGlassDrawable(
        private val spec: Spec,
        private val radius: Float,
        private val strokeWidth: Float,
        private val layeredEdges: Boolean
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val path = Path()
        private var drawableAlpha = 255

        override fun draw(canvas: Canvas) {
            val bounds = bounds
            if (bounds.isEmpty) return
            rect.set(bounds)
            val halfStroke = strokeWidth / 2f
            paint.style = Paint.Style.FILL
            paint.color = withDrawableAlpha(spec.fillColor)
            canvas.drawRoundRect(rect, radius, radius, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
            paint.color = withDrawableAlpha(spec.borderColor)
            rect.inset(halfStroke, halfStroke)
            canvas.drawRoundRect(rect, (radius - halfStroke).coerceAtLeast(0f),
                (radius - halfStroke).coerceAtLeast(0f), paint)

            val inset = (strokeWidth * 1.5f).coerceAtLeast(1f)
            path.reset()
            path.moveTo(rect.left + radius * 0.55f, rect.top + inset)
            path.quadTo(rect.left + inset, rect.top + inset, rect.left + inset, rect.top + radius * 0.55f)
            path.moveTo(rect.left + radius * 0.55f, rect.top + inset)
            path.lineTo(rect.right - radius * 0.55f, rect.top + inset)
            path.quadTo(rect.right - inset, rect.top + inset, rect.right - inset, rect.top + radius * 0.55f)
            paint.strokeWidth = strokeWidth.coerceAtLeast(1f)
            paint.color = withDrawableAlpha(spec.highlightColor)
            canvas.drawPath(path, paint)

            if (layeredEdges) {
                // Home's low-cost coating keeps transmitted artwork unchanged.
                // A shallow inner rim gives the existing material thickness;
                // no gradients, capture, blur, or per-frame material rebuild.
                val depth = strokeWidth * 2.5f
                rect.set(bounds)
                rect.inset(depth, depth)
                val innerRadius = (radius - depth).coerceAtLeast(0f)
                path.reset()
                path.moveTo(rect.left, rect.bottom - innerRadius)
                path.quadTo(rect.left, rect.bottom, rect.left + innerRadius, rect.bottom)
                path.lineTo(rect.right - innerRadius, rect.bottom)
                path.quadTo(rect.right, rect.bottom, rect.right, rect.bottom - innerRadius)
                paint.strokeWidth = strokeWidth * 1.5f
                paint.color = withDrawableAlpha(0x24000000)
                canvas.drawPath(path, paint)
                path.reset()
                path.moveTo(rect.left, rect.top + innerRadius)
                path.quadTo(rect.left, rect.top, rect.left + innerRadius, rect.top)
                path.lineTo(rect.right - innerRadius, rect.top)
                path.quadTo(rect.right, rect.top, rect.right, rect.top + innerRadius)
                paint.strokeWidth = strokeWidth
                paint.color = withDrawableAlpha(spec.highlightColor)
                canvas.drawPath(path, paint)
            }
        }

        override fun setAlpha(alpha: Int) { drawableAlpha = alpha.coerceIn(0, 255); invalidateSelf() }
        override fun getAlpha(): Int = drawableAlpha
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        private fun withDrawableAlpha(color: Int): Int =
            Color.argb(Color.alpha(color) * drawableAlpha / 255, Color.red(color), Color.green(color), Color.blue(color))
    }
}

/** App theme and bounded rendering shared by the library's ready-made widgets. */
object GlassWidgetStyle {
    enum class MaterialRole { FLOATING, READING }
    private val materialRoles = java.util.WeakHashMap<LiquidGlassView, MaterialRole>()
    private val renderingStates = java.util.WeakHashMap<LiquidGlassView, Boolean>()
    private val renderingSources = java.util.WeakHashMap<LiquidGlassView, WeakReference<View>>()
    private val requestedRenderingSources = java.util.WeakHashMap<LiquidGlassView, WeakReference<View>>()
    private val renderingSourceModes = java.util.WeakHashMap<LiquidGlassView, BackdropSourceMode>()
    private val renderingStaticColors = java.util.WeakHashMap<LiquidGlassView, Int>()
    private val renderingFallbackStates = java.util.WeakHashMap<LiquidGlassView, Boolean>()
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
        val palette = PaletteManager.colors(view.context)
        val backdropColor = (requestedSource?.background as? ColorDrawable)?.color ?: palette.surface
        val staticColor = backdropColor
        val previousActive = renderingStates[view]
        val previousRequestedSource = requestedRenderingSources[view]?.get()
        val previousMode = renderingSourceModes[view]
        val previousStaticColor = renderingStaticColors[view]
        val fallback = isPerformanceFallback(view.context)
        val previousFallback = renderingFallbackStates[view]
        if (previousActive == active &&
            previousRequestedSource === requestedSource && previousMode == sourceMode &&
            previousStaticColor == staticColor && previousFallback == fallback
        ) return

        renderingStates[view] = active
        requestedRenderingSources[view] = requestedSource?.let(::WeakReference)
        renderingSourceModes[view] = sourceMode
        renderingStaticColors[view] = staticColor
        renderingFallbackStates[view] = fallback
        applyRenderingPlan(view, requestedSource, sourceMode, staticColor, active, fallback)
    }

    private fun disabledSource(view: LiquidGlassView): View {
        disabledSources[view]?.get()?.let { return it }
        return View(view.context).apply {
            layout(0, 0, 1, 1)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            disabledSources[view] = WeakReference(this)
            if (Build.VERSION.SDK_INT >= 29) BackdropRenderNodeCache.prepareForTest(this)
        }
    }

    private fun applyRenderingPlan(
        view: LiquidGlassView,
        requestedSource: View?,
        sourceMode: BackdropSourceMode,
        color: Int,
        requestedActive: Boolean,
        fallbackEnabled: Boolean
    ) {
        val useFallbackMaterial = fallbackEnabled || Build.VERSION.SDK_INT < 29
        val plan = GlassRenderingPlan.resolve(
            fallbackEnabled = useFallbackMaterial,
            requestedActive = requestedActive,
            apiLevel = Build.VERSION.SDK_INT
        )
        val candidate = requestedSource.takeIf { sourceMode != BackdropSourceMode.DISABLED }
        var safeSource: View = disabledSource(view)
        var sourceBound = false
        if (Build.VERSION.SDK_INT >= 29 && plan.captureBackdrop && candidate != null) {
            sourceBound = BackdropRenderNodeCache.bind(view, candidate)
            if (sourceBound && BackdropRenderNodeCache.hasPreparedFrame(candidate, view)) safeSource = candidate
        }
        if (Build.VERSION.SDK_INT >= 29 && !sourceBound) BackdropRenderNodeCache.unbind(view)
        renderingSources[view] = WeakReference(safeSource)
        view.backdropSource = safeSource
        val palette = PaletteManager.colors(view.context)
        val fallbackSpec = GlassFallbackMaterial.resolve(palette.isDark)
        view.overLight = false
        // The AAR tint pass is drawn after the View background; leaving it
        // translucent would composite the fill twice and make it opaque.
        view.glassTint = if (useFallbackMaterial) {
            Color.TRANSPARENT
        } else if (materialRole(view) == MaterialRole.READING) {
            palette.glassSurface
        } else {
            palette.glassTint
        }
        val effectivePlan = GlassRenderingPlan.resolve(
            useFallbackMaterial,
            requestedActive && candidate != null && safeSource === candidate,
            sourceReady = safeSource === candidate,
            apiLevel = Build.VERSION.SDK_INT
        )
        configureRenderer(view, effectivePlan)
        view.background = if (useFallbackMaterial) {
            GlassFallbackMaterial.drawable(
                fallbackSpec,
                view.cornerRadius,
                view.resources.displayMetrics.density.coerceAtLeast(1f),
                // Only Home requested a deeper safe coating. Other pages and
                // every normal hardware rendering plan retain their material.
                layeredEdges = view.id == com.kerybotu.derpibooru.mirror.R.id.header_glass
            )
        } else {
            BackdropFallback(view, color).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = view.cornerRadius
                setStroke(view.resources.displayMetrics.density.toInt().coerceAtLeast(1), palette.glassBorder)
            }
        }
        CrashDiagnostics.recordEvent(
            "LiquidGlassView",
            "glass ${view.javaClass.simpleName} fb=$fallbackEnabled m=${view.material} a=${view.accessibilityMode} t=${Integer.toHexString(view.glassTint)} dark=${palette.isDark} fill=${Integer.toHexString(fallbackSpec.fillColor)}"
        )
    }

    private fun configureRenderer(view: LiquidGlassView, plan: GlassRenderingPlan) {
        view.material = plan.material
        view.accessibilityMode = plan.accessibilityMode
        view.enableBackdropBlur = plan.backdropBlur && Build.VERSION.SDK_INT >= 31
        view.enableDynamicBackground = false
        view.enableSensorHighlight = false
        view.globalDownsampleFactor = if (Build.VERSION.SDK_INT < 33) 0.5f else 1f
        view.blurAmount = if (plan.backdropBlur) 0.045f else 0f
        view.enableChromaticAberration = plan.refraction && Build.VERSION.SDK_INT >= 31
        view.enableChromaticDispersion = false
        view.enableEdgeHighlight = plan.refraction
        view.edgeHighlightOpacity = if (plan.refraction) 35f else 0f
        view.saturation = if (plan.refraction) 125f else 100f
        view.adaptiveLensScale = plan.refraction
        view.dispersionStrength = if (plan.refraction) 0.08f else 0f
        view.bevelWidth = if (plan.refraction) 16f * view.resources.displayMetrics.density else 0f
        view.refractionHeight = if (plan.refraction) 48f * view.resources.displayMetrics.density else 0f
        view.refractionFalloff = if (plan.refraction) 2f else 0f
    }

    private fun hasActivePreparedSource(view: LiquidGlassView): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        if (renderingStates[view] != true) return false
        val selected = renderingSources[view]?.get() ?: return false
        val requested = requestedRenderingSources[view]?.get() ?: return false
        return selected === requested && selected !== disabledSources[view]?.get()
            && BackdropRenderNodeCache.hasPreparedFrame(selected, view)
    }

    internal fun onBackdropFrameAvailabilityChanged(view: LiquidGlassView, source: View) {
        if (Build.VERSION.SDK_INT < 29) return
        if (requestedRenderingSources[view]?.get() !== source) return
        applyRenderingPlan(
            view = view,
            requestedSource = source,
            sourceMode = renderingSourceModes[view] ?: BackdropSourceMode.DISABLED,
            color = renderingStaticColors[view] ?: PaletteManager.colors(view.context).glassSurface,
            requestedActive = renderingStates[view] == true,
            fallbackEnabled = isPerformanceFallback(view.context)
        )
        view.invalidate()
    }

    internal fun onBackdropSourceRejected(view: LiquidGlassView, source: View?) {
        if (Build.VERSION.SDK_INT < 29) return
        if (source != null && requestedRenderingSources[view]?.get() !== source) return
        val safeSource = disabledSource(view)
        renderingSources[view] = WeakReference(safeSource)
        view.backdropSource = safeSource
        configureRenderer(view, GlassRenderingPlan.resolve(
            fallbackEnabled = isPerformanceFallback(view.context),
            requestedActive = false,
            sourceReady = false
        ))
        view.invalidate()
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
        // Preserve the existing island/host elevation; do not erase its shadow
        // on each theme refresh. The native shadow is clipped inside the view.
        if (Build.VERSION.SDK_INT >= 28) {
            view.outlineAmbientShadowColor = colors.glassShadow
            view.outlineSpotShadowColor = colors.glassShadow
        }
        // Palette/theme refreshes also call applyMaterial(). Preserve the
        // already selected safe or GPU plan instead of resetting the live
        // surface to the flat defaults above.  The prepared-source check is
        // side-effect free and keeps API 28 on the fallback path.
        if (renderingStates.containsKey(view)) {
            val fallback = isPerformanceFallback(view.context) || Build.VERSION.SDK_INT < 29
            val sourceReady = hasActivePreparedSource(view)
            configureRenderer(
                view,
                GlassRenderingPlan.resolve(
                    fallbackEnabled = fallback,
                    requestedActive = renderingStates[view] == true,
                    sourceReady = sourceReady,
                    apiLevel = Build.VERSION.SDK_INT
                )
            )
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
        val requested = requestedRenderingSources[view]?.get()
        val mode = renderingSourceModes[view] ?: BackdropSourceMode.DISABLED
        val staticColor = renderingStaticColors[view] ?: colors.glassSurface
        applyRenderingPlan(view, requested, mode, staticColor, renderingStates[view] == true,
            isPerformanceFallback(view.context))
        view.invalidate()
    }

    internal fun createSafeMaterialBackground(
        colors: PaletteDefinitions.Scheme,
        cornerRadius: Float,
        density: Float
    ): Drawable = GlassFallbackMaterial.drawable(
        GlassFallbackMaterial.resolve(colors.isDark),
        cornerRadius,
        density.coerceAtLeast(1f)
    )

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
