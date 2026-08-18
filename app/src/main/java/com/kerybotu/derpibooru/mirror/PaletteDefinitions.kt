package com.kerybotu.derpibooru.mirror

import com.kerybotu.derpibooru.mirror.theme.AccentColor
import com.kerybotu.derpibooru.mirror.theme.ThemeGenerator
import com.kerybotu.derpibooru.mirror.theme.ThemeMode

/** Central semantic colors for every selectable app palette. */
object PaletteDefinitions {
    data class Scheme(
        val surface: Int, val surfaceVariant: Int, val primary: Int, val onSurface: Int,
        val onPrimary: Int, val mediaSurface: Int, val scrim: Int, val divider: Int, val muted: Int
    )

    fun forPalette(context: android.content.Context, palette: AppSettings.Palette): Scheme {
        val mode = if (palette == AppSettings.Palette.DARK) ThemeMode.DARK else ThemeMode.LIGHT
        val accent = AppSettings.getAccentColor(context)
        val generated = ThemeGenerator.generate(accent, mode)
        val surface = if (mode == ThemeMode.DARK) 0xFF121212.toInt() else generated.surface
        val surfaceVariant = if (mode == ThemeMode.DARK) 0xFF242424.toInt() else generated.surfaceVariant
        val onSurface = if (mode == ThemeMode.DARK) 0xFFFFFFFF.toInt() else generated.onSurface
        val onSurfaceVariant = if (mode == ThemeMode.DARK) 0xFFC7C7C7.toInt() else generated.onSurfaceVariant
        val outline = if (mode == ThemeMode.DARK) 0xFF666666.toInt() else generated.outline
        return Scheme(surface, surfaceVariant, generated.primary, onSurface,
            generated.onPrimary, surface, 0x99000000.toInt(), outline, onSurfaceVariant)
    }
}
