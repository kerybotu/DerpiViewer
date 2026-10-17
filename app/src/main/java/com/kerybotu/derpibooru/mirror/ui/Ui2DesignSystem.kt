package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.kerybotu.derpibooru.mirror.PaletteDefinitions

/** Shared visual tokens for the 2.0 View-based UI. */
object Ui2DesignSystem {
    object Spacing {
        const val xxs = 4
        const val xs = 8
        const val sm = 12
        const val md = 16
        const val lg = 20
        const val xl = 24
        const val xxl = 32
        const val xxxl = 40
        const val huge = 48

        const val islandMargin = md
        const val islandGap = xs
        const val content = md
    }

    object Shape {
        const val small = 8f
        const val medium = 12f
        const val large = 16f
        const val island = 26f
        const val pill = 50f

        const val topIsland = 30f
        const val navigationIsland = island
        const val fabIsland = island
    }

    object Elevation {
        const val cardDp = 1f
        const val islandDp = 8f
        const val topIslandDp = 12f
        const val floatingActionDp = 12f
        const val dialogDp = 24f
    }

    object Motion {
        const val fastMs = 150L
        const val normalMs = 240L
        const val slowMs = 360L
        const val emphasizedMs = 420L
        const val stateChangeMs = normalMs
        val standard = DecelerateInterpolator(1.7f)
        val decelerate = DecelerateInterpolator(2f)
        val accelerate = AccelerateInterpolator(1.7f)
        val spring = OvershootInterpolator(0.7f)
    }

    enum class GlassPerformanceMode { FULL, REDUCED, OFF }

    data class Colors(
        val primary: Int,
        val primaryContainer: Int,
        val onPrimary: Int,
        val secondary: Int,
        val secondaryContainer: Int,
        val background: Int,
        val surface: Int,
        val surfaceVariant: Int,
        val onSurface: Int,
        val glassTint: Int,
        val glassBorder: Int,
        val glassHighlight: Int,
        val success: Int,
        val warning: Int,
        val error: Int
    )

    fun colors(context: Context): Colors {
        val palette = com.kerybotu.derpibooru.mirror.PaletteManager.colors(context)
        val light = Color.luminance(palette.surface) > 0.5f
        return Colors(
            primary = palette.primary,
            primaryContainer = blend(palette.primary, palette.surface, if (light) 0.86f else 0.58f),
            onPrimary = palette.onPrimary,
            secondary = palette.primary,
            secondaryContainer = palette.surfaceVariant,
            background = palette.surface,
            surface = palette.surface,
            surfaceVariant = palette.surfaceVariant,
            onSurface = palette.onSurface,
            glassTint = palette.glassSurface,
            glassBorder = palette.glassBorder,
            glassHighlight = palette.glassTint,
            success = Color.rgb(54, 125, 89),
            warning = Color.rgb(174, 112, 38),
            error = Color.rgb(179, 58, 61)
        )
    }

    /** Maintains the legacy shell hook while callers migrate to shared components. */
    fun styleIsland(view: View, colors: PaletteDefinitions.Scheme, radiusDp: Float) {
        val density = view.resources.displayMetrics.density
        val light = Color.luminance(colors.surface) > 0.5f
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(colors.glassSurface)
            setStroke((density).toInt().coerceAtLeast(1), colors.glassBorder)
        }
        view.elevation = Elevation.islandDp * density
        view.clipToOutline = true
    }

    fun applyPressFeedback(view: View, animatedView: View = view) {
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    animatedView.animate().cancel()
                    animatedView.animate().scaleX(0.97f).scaleY(0.97f)
                        .setDuration(Motion.fastMs).setInterpolator(Motion.standard).start()
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    animatedView.animate().cancel()
                    animatedView.animate().scaleX(1f).scaleY(1f)
                        .setDuration(Motion.normalMs).setInterpolator(Motion.spring).start()
                }
            }
            false
        }
    }

    private fun blend(foreground: Int, background: Int, backgroundFraction: Float): Int {
        val f = backgroundFraction.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(foreground) * (1 - f) + Color.red(background) * f).toInt(),
            (Color.green(foreground) * (1 - f) + Color.green(background) * f).toInt(),
            (Color.blue(foreground) * (1 - f) + Color.blue(background) * f).toInt()
        )
    }
}
