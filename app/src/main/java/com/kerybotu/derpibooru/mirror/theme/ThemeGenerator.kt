package com.kerybotu.derpibooru.mirror.theme

import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.SchemeTonalSpot

enum class AccentColor(
    val displayName: String,
    val lightHue: Float,
    val lightChroma: Double,
    val darkHue: Float,
    val darkChroma: Double,
    val darkPrimary: Int? = null
) {
    BLUE   ("蓝色", 220f, 0.92, 220f, 0.782, 0xFF48617C.toInt()),
    INDIGO ("靛蓝", 248f, 0.96, 248f, 0.816),
    PURPLE ("紫色", 278f, 0.95, 278f, 0.808, 0xFF553F5D.toInt()),
    TEAL   ("青色", 185f, 0.90, 185f, 0.765, 0xFF40636C.toInt()),
    GREEN  ("绿色", 145f, 0.92, 145f, 0.782, 0xFF496A56.toInt()),
    AMBER  ("琥珀", 68f, 0.75, 68f, 0.638),
    ORANGE ("橙色", 30f, 0.92, 30f, 0.782, 0xFF7A5A45.toInt()),
    ROSE   ("玫瑰", 345f, 0.84, 345f, 0.714, 0xFF6A4B57.toInt()),
    GRAY   ("灰色", 0f, 0.08, 0f, 0.068)
}

enum class ThemeMode { DARK, LIGHT }

data class AppColorScheme(
    val primary: Int, val onPrimary: Int, val primaryContainer: Int, val onPrimaryContainer: Int,
    val surface: Int, val onSurface: Int, val surfaceVariant: Int, val onSurfaceVariant: Int,
    val outline: Int, val error: Int, val onError: Int
)

object ThemeGenerator {
    fun generate(accent: AccentColor, mode: ThemeMode): AppColorScheme {
        val hue = if (mode == ThemeMode.LIGHT) accent.lightHue else accent.darkHue
        // The product spec expresses chroma as a normalized 0..1 value; HCT uses 0..100.
        val chroma = (if (mode == ThemeMode.LIGHT) accent.lightChroma else accent.darkChroma) * 100.0
        val tone = if (mode == ThemeMode.DARK) 55.0 else 50.0
        val scheme = SchemeTonalSpot(Hct.from(hue.toDouble(), chroma, tone), mode == ThemeMode.DARK, 0.0)
        val primary = if (mode == ThemeMode.DARK) accent.darkPrimary ?: scheme.primary else scheme.primary
        val onPrimary = if (mode == ThemeMode.DARK && accent.darkPrimary != null) 0xFFFFFFFF.toInt() else scheme.onPrimary
        return AppColorScheme(primary, onPrimary, scheme.primaryContainer, scheme.onPrimaryContainer,
            scheme.surface, scheme.onSurface, scheme.surfaceVariant, scheme.onSurfaceVariant,
            scheme.outline, scheme.error, scheme.onError)
    }
}
