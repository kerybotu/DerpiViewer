package com.kerybotu.derpibooru.mirror.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RenderCrashExperimentTest {
    @Test
    fun parsesEveryExplicitExperimentAndDefaultsUnknownValuesToBaseline() {
        assertEquals(RenderCrashExperiment.BASELINE, RenderCrashExperiment.parse("baseline"))
        assertEquals(RenderCrashExperiment.A_NO_SCROLL_MIRROR, RenderCrashExperiment.parse("a_no_scroll_mirror"))
        assertEquals(RenderCrashExperiment.B_STATIC_SETTINGS_BACKDROP, RenderCrashExperiment.parse("b_static_settings_backdrop"))
        assertEquals(RenderCrashExperiment.C_STATIC_DETAIL_BACKDROP, RenderCrashExperiment.parse("c_static_detail_backdrop"))
        assertEquals(RenderCrashExperiment.D_DEFER_RESUME, RenderCrashExperiment.parse("d_defer_resume"))
        assertEquals(RenderCrashExperiment.F_NO_BACKDROP, RenderCrashExperiment.parse("f_no_backdrop"))
        assertEquals(RenderCrashExperiment.G_PLAIN_VIEW, RenderCrashExperiment.parse("g_plain_view"))
        assertEquals(RenderCrashExperiment.H_SINGLE_GLASS, RenderCrashExperiment.parse("h_single_glass"))
        assertEquals(RenderCrashExperiment.I_MULTIPLE_GLASS, RenderCrashExperiment.parse("i_multiple_glass"))
        assertEquals(RenderCrashExperiment.J_STATIC_BACKDROP, RenderCrashExperiment.parse("j_static_backdrop"))
        assertEquals(RenderCrashExperiment.K_BYPASS_GLASS_RENDERING, RenderCrashExperiment.parse("k_bypass_glass_rendering"))
        assertEquals(RenderCrashExperiment.BASELINE, RenderCrashExperiment.parse("unknown"))
        assertEquals(RenderCrashExperiment.BASELINE, RenderCrashExperiment.parse(null))
    }

    @Test
    fun eachExperimentChangesOnlyItsTargetRenderPath() {
        for (experiment in RenderCrashExperiment.entries) {
            assertEquals(experiment != RenderCrashExperiment.A_NO_SCROLL_MIRROR && experiment != RenderCrashExperiment.AB_STATIC_SETTINGS, experiment.shouldMirrorScrollContent())
            assertEquals(experiment != RenderCrashExperiment.B_STATIC_SETTINGS_BACKDROP && experiment != RenderCrashExperiment.AB_STATIC_SETTINGS, experiment.shouldUpdateSettingsBackdrop())
            assertEquals(experiment != RenderCrashExperiment.C_STATIC_DETAIL_BACKDROP, experiment.shouldUpdateDetailBackdrop())
            assertEquals(experiment == RenderCrashExperiment.D_DEFER_RESUME, experiment.shouldDeferBackdropAfterResume())
        }
    }

    @Test
    fun combinedSettingsExperimentDisablesOnlyScrollMirrorAndSettingsBackdrop() {
        val experiment = RenderCrashExperiment.AB_STATIC_SETTINGS
        assertEquals(false, experiment.shouldMirrorScrollContent())
        assertEquals(false, experiment.shouldUpdateSettingsBackdrop())
        assertEquals(true, experiment.shouldUpdateDetailBackdrop())
        assertEquals(false, experiment.shouldDeferBackdropAfterResume())
    }

    @Test
    fun layeredExperimentsIsolateBackdropAndGlassRendererIndependently() {
        assertEquals(false, RenderCrashExperiment.F_NO_BACKDROP.usesBackdrop())
        assertEquals(true, RenderCrashExperiment.F_NO_BACKDROP.usesGlassRenderer())
        assertEquals(false, RenderCrashExperiment.G_PLAIN_VIEW.usesGlassRenderer())
        assertEquals(true, RenderCrashExperiment.G_PLAIN_VIEW.preservesGlassViewHierarchy())
        assertEquals(false, RenderCrashExperiment.H_SINGLE_GLASS.usesBackdrop())
        assertEquals(true, RenderCrashExperiment.H_SINGLE_GLASS.usesGlassRenderer())
        assertEquals(true, RenderCrashExperiment.I_MULTIPLE_GLASS.usesGlassRenderer())
        assertEquals(true, RenderCrashExperiment.J_STATIC_BACKDROP.usesStaticBackdrop())
        assertEquals(false, RenderCrashExperiment.J_STATIC_BACKDROP.usesDynamicBackdropCapture())
        assertEquals(false, RenderCrashExperiment.K_BYPASS_GLASS_RENDERING.usesCustomGlassDrawing())
    }
}
