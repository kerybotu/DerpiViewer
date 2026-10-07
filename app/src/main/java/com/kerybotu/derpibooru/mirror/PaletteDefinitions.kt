package com.kerybotu.derpibooru.mirror

import com.kerybotu.derpibooru.mirror.theme.AccentColor
import com.kerybotu.derpibooru.mirror.theme.ThemeGenerator
import com.kerybotu.derpibooru.mirror.theme.ThemeMode
import android.content.res.Configuration

/** Central semantic colors for every selectable app palette. */
object PaletteDefinitions {
    data class Scheme(
        val surface: Int, val surfaceVariant: Int, val primary: Int, val onSurface: Int,
        val onPrimary: Int, val mediaSurface: Int, val scrim: Int, val divider: Int, val muted: Int,
        val isDark: Boolean,
        val glassSurface: Int,
        val glassSurfaceElevated: Int,
        val glassTint: Int,
        val glassBorder: Int,
        val glassShadow: Int,
        val glassScrim: Int,
        val glassText: Int,
        val glassSecondaryText: Int
    )

    fun forPalette(context: android.content.Context, palette: AppSettings.Palette): Scheme {
        val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val mode = when (palette) {
            AppSettings.Palette.DARK -> ThemeMode.DARK
            AppSettings.Palette.SYSTEM -> if (systemDark) ThemeMode.DARK else ThemeMode.LIGHT
            else -> ThemeMode.LIGHT
        }
        val accent = AppSettings.getAccentColor(context)
        val generated = ThemeGenerator.generate(accent, mode)
        val surface = if (mode == ThemeMode.DARK) 0xFF121212.toInt() else generated.surface
        val surfaceVariant = if (mode == ThemeMode.DARK) 0xFF242424.toInt() else generated.surfaceVariant
        val onSurface = if (mode == ThemeMode.DARK) 0xFFFFFFFF.toInt() else generated.onSurface
        val onSurfaceVariant = if (mode == ThemeMode.DARK) 0xFFC7C7C7.toInt() else generated.onSurfaceVariant
        val outline = if (mode == ThemeMode.DARK) 0xFF666666.toInt() else generated.outline
        val glassBase = blend(surface, generated.primary, 0.12f)
        val glassTint = alpha(glassBase, if (mode == ThemeMode.DARK) 88 else 64)
        val glassSurface = alpha(surface, if (mode == ThemeMode.DARK) 184 else 194)
        val glassSurfaceElevated = alpha(surfaceVariant, if (mode == ThemeMode.DARK) 216 else 224)
        val glassBorder = alpha(if (mode == ThemeMode.DARK) 0xFFFFFFFF.toInt() else onSurface, if (mode == ThemeMode.DARK) 42 else 34)
        val glassShadow = alpha(0xFF000000.toInt(), if (mode == ThemeMode.DARK) 112 else 48)
        return Scheme(surface, surfaceVariant, generated.primary, onSurface,
            generated.onPrimary, surface, alpha(0xFF000000.toInt(), 153), outline, onSurfaceVariant,
            mode == ThemeMode.DARK, glassSurface, glassSurfaceElevated, glassTint, glassBorder,
            glassShadow, alpha(0xFF000000.toInt(), if (mode == ThemeMode.DARK) 150 else 72),
            onSurface, onSurfaceVariant)
    }

    private fun alpha(color: Int, value: Int) = (color and 0x00FFFFFF) or (value.coerceIn(0, 255) shl 24)

    private fun blend(base: Int, accent: Int, accentFraction: Float): Int {
        val f = accentFraction.coerceIn(0f, 1f)
        fun channel(shift: Int) = (((base shr shift) and 0xFF) * (1f - f) + ((accent shr shift) and 0xFF) * f).toInt()
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
