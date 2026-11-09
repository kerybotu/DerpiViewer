package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import com.kerybotu.derpibooru.mirror.R

enum class RenderCrashExperiment(val id: String) {
    BASELINE("baseline"),
    A_NO_SCROLL_MIRROR("a_no_scroll_mirror"),
    B_STATIC_SETTINGS_BACKDROP("b_static_settings_backdrop"),
    AB_STATIC_SETTINGS("ab_static_settings"),
    C_STATIC_DETAIL_BACKDROP("c_static_detail_backdrop"),
    D_DEFER_RESUME("d_defer_resume"),
    F_NO_BACKDROP("f_no_backdrop"),
    G_PLAIN_VIEW("g_plain_view"),
    H_SINGLE_GLASS("h_single_glass"),
    I_MULTIPLE_GLASS("i_multiple_glass"),
    J_STATIC_BACKDROP("j_static_backdrop"),
    K_BYPASS_GLASS_RENDERING("k_bypass_glass_rendering"),
    L_SOURCE_TREE_GUARD("l_source_tree_guard");

    fun shouldMirrorScrollContent(): Boolean = this != A_NO_SCROLL_MIRROR && this != AB_STATIC_SETTINGS
    fun shouldUpdateSettingsBackdrop(): Boolean = this != B_STATIC_SETTINGS_BACKDROP && this != AB_STATIC_SETTINGS
    fun shouldUpdateDetailBackdrop(): Boolean = this != C_STATIC_DETAIL_BACKDROP
    fun shouldDeferBackdropAfterResume(): Boolean = this == D_DEFER_RESUME
    fun usesBackdrop(): Boolean = this != F_NO_BACKDROP && this != H_SINGLE_GLASS
    fun usesGlassRenderer(): Boolean = this != G_PLAIN_VIEW
    fun preservesGlassViewHierarchy(): Boolean = this == G_PLAIN_VIEW
    fun usesStaticBackdrop(): Boolean = this == J_STATIC_BACKDROP
    fun usesDynamicBackdropCapture(): Boolean = this != F_NO_BACKDROP && this != J_STATIC_BACKDROP
    fun usesCustomGlassDrawing(): Boolean = this != K_BYPASS_GLASS_RENDERING

    companion object {
        fun parse(value: String?): RenderCrashExperiment = entries.firstOrNull {
            it.id == value?.trim()?.lowercase()
        } ?: BASELINE

        fun from(context: Context): RenderCrashExperiment =
            parse(context.getString(R.string.render_crash_experiment))
    }
}
