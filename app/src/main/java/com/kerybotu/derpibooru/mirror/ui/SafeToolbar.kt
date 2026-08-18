package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Toolbar with a status-bar-safe content area shared by all non-immersive screens. */
class SafeToolbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Toolbar(context, attrs) {
    private val actionBarHeight = TypedValue().let { value ->
        context.theme.resolveAttribute(androidx.appcompat.R.attr.actionBarSize, value, true)
        TypedValue.complexToDimensionPixelSize(value.data, resources.displayMetrics)
    }
    private val baseLeft = paddingLeft
    private val baseRight = paddingRight
    private val baseBottom = paddingBottom

    init {
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            layoutParams?.let { params ->
                params.height = actionBarHeight + top
                layoutParams = params
            }
            setPadding(baseLeft, top, baseRight, baseBottom)
            insets
        }
        ViewCompat.requestApplyInsets(this)
    }
}
