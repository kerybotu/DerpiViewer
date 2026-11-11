package com.kerybotu.derpibooru.mirror.ui

import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.GlassAccessibilityMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassRenderingPlanTest {
    @Test
    fun fallbackDisablesCaptureBlurAndRefractionWhileFullModeKeepsThem() {
        val full = GlassRenderingPlan.resolve(fallbackEnabled = false, requestedActive = true)
        assertTrue(full.captureBackdrop)
        assertTrue(full.backdropBlur)
        assertTrue(full.refraction)
        assertEquals(GlassMaterial.REGULAR, full.material)
        assertEquals(GlassAccessibilityMode.AUTO, full.accessibilityMode)

        val fallback = GlassRenderingPlan.resolve(fallbackEnabled = true, requestedActive = true)
        assertFalse(fallback.captureBackdrop)
        assertFalse(fallback.backdropBlur)
        assertFalse(fallback.refraction)
        assertEquals(GlassMaterial.CLEAR, fallback.material)
        assertEquals(GlassAccessibilityMode.FORCE_FULL, fallback.accessibilityMode)
    }

    @Test
    fun inactiveGlassNeverCapturesEvenWhenFallbackIsOff() {
        val inactive = GlassRenderingPlan.resolve(fallbackEnabled = false, requestedActive = false)
        assertFalse(inactive.captureBackdrop)
        assertFalse(inactive.backdropBlur)
        assertFalse(inactive.refraction)
    }

    @Test
    fun glassWithUnpreparedBackdropUsesSafeMaterialUntilFrameIsReady() {
        val waiting = GlassRenderingPlan.resolve(
            fallbackEnabled = false,
            requestedActive = true,
            sourceReady = false
        )

        assertFalse(waiting.captureBackdrop)
        assertFalse(waiting.backdropBlur)
        assertFalse(waiting.refraction)
        assertEquals(GlassMaterial.CLEAR, waiting.material)
        assertEquals(GlassAccessibilityMode.FORCE_FULL, waiting.accessibilityMode)

        val ready = GlassRenderingPlan.resolve(
            fallbackEnabled = false,
            requestedActive = true,
            sourceReady = true
        )
        assertTrue(ready.captureBackdrop)
        assertTrue(ready.backdropBlur)
        assertTrue(ready.refraction)
        assertEquals(GlassMaterial.REGULAR, ready.material)
        assertEquals(GlassAccessibilityMode.AUTO, ready.accessibilityMode)
    }

    @Test
    fun inactiveGlassUsesSafeMaterialDuringActivityTransition() {
        val inactive = GlassRenderingPlan.resolve(
            fallbackEnabled = false,
            requestedActive = false,
            sourceReady = false
        )

        assertFalse(inactive.captureBackdrop)
        assertFalse(inactive.backdropBlur)
        assertFalse(inactive.refraction)
        assertEquals(GlassMaterial.CLEAR, inactive.material)
        assertEquals(GlassAccessibilityMode.FORCE_FULL, inactive.accessibilityMode)
    }

    @Test
    fun api28UsesSafeMaterialWithoutRenderNodeBackdrop() {
        val legacy = GlassRenderingPlan.resolve(
            fallbackEnabled = false,
            requestedActive = true,
            apiLevel = 28
        )

        assertFalse(legacy.captureBackdrop)
        assertFalse(legacy.backdropBlur)
        assertFalse(legacy.refraction)
        assertEquals(GlassMaterial.CLEAR, legacy.material)
        assertEquals(GlassAccessibilityMode.FORCE_FULL, legacy.accessibilityMode)
    }

    @Test
    fun fallbackMaterialUsesSpecifiedFlatThemeAwareAlphaColors() {
        val light = GlassFallbackMaterial.resolve(isDark = false)
        val dark = GlassFallbackMaterial.resolve(isDark = true)

        assertEquals(0xC7F4F7FA.toInt(), light.fillColor)
        assertEquals(0xC7171A20.toInt(), dark.fillColor)
        assertEquals(0x8CFFFFFF.toInt(), light.borderColor)
        assertEquals(0x38FFFFFF.toInt(), dark.borderColor)
        assertEquals(0x59FFFFFF.toInt(), light.highlightColor)
        assertEquals(0x1FFFFFFF.toInt(), dark.highlightColor)
        assertEquals(0.78f, light.fillAlpha, 0.001f)
        assertEquals(0.78f, dark.fillAlpha, 0.001f)
        assertTrue((light.fillColor ushr 24) < 255)
        assertTrue((dark.fillColor ushr 24) < 255)
    }
}
