package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.kerybotu.derpibooru.mirror.PaletteManager

/** Toolbar with a status-bar-safe content area shared by all non-immersive screens. */
class SafeToolbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Toolbar(context, attrs) {
    private val actionBarHeight = TypedValue().let { value ->
        context.theme.resolveAttribute(androidx.appcompat.R.attr.actionBarSize, value, true)
        TypedValue.complexToDimensionPixelSize(value.data, resources.displayMetrics)
    }

    init {
        applyUi2Appearance()
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            layoutParams?.let { params ->
                params.height = actionBarHeight
                layoutParams = params
            }
            setPadding(paddingLeft, 0, paddingRight, paddingBottom)
            insets
        }
        Ui2DesignSystem.applyPressFeedback(this)
        ViewCompat.requestApplyInsets(this)
    }

    fun applyUi2Appearance() {
        val palette = PaletteManager.colors(context)
        Ui2DesignSystem.styleIsland(this, palette, Ui2DesignSystem.Shape.topIsland)
        setTitleTextColor(palette.onSurface)
        navigationIcon?.setTint(palette.onSurface)
        elevation = Ui2DesignSystem.Elevation.topIslandDp * resources.displayMetrics.density
    }
}
