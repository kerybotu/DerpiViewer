package com.kerybotu.derpibooru.mirror.ui

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.floor

/** Central window-width policy; grids use the same target card size on phones and tablets. */
object AdaptiveLayoutPolicy {
    const val MAX_CONTENT_WIDTH_DP = 1280
    const val TOP_ISLAND_MAX_WIDTH_DP = 460
    /** Maximum width for the five-item floating bottom navigation island. */
    const val BOTTOM_ISLAND_MAX_WIDTH_DP = 520
    const val LANDSCAPE_TOP_ISLAND_MAX_WIDTH_DP = 720
    const val MIN_ARTWORK_CELL_DP = 156
    const val GRID_GAP_DP = 8
    const val CONTENT_GUTTER_DP = 16

    fun availableContentWidthDp(context: Context): Int =
        context.resources.configuration.screenWidthDp.coerceAtMost(MAX_CONTENT_WIDTH_DP)

    fun isLandscape(context: Context): Boolean =
        context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    fun contentIslandWidthPx(context: Context, availableWidthPx: Int): Int {
        val maxWidth = (MAX_CONTENT_WIDTH_DP * context.resources.displayMetrics.density).toInt()
        return availableWidthPx.coerceAtMost(maxWidth)
    }

    fun bottomIslandWidthPx(context: Context, availableWidthPx: Int): Int {
        val maxWidth = (BOTTOM_ISLAND_MAX_WIDTH_DP * context.resources.displayMetrics.density).toInt()
        return availableWidthPx.coerceAtMost(maxWidth)
    }

    /** Top navigation is a self-contained island, not a full-width content bar. */
    fun topIslandWidthPx(context: Context, availableWidthPx: Int, landscape: Boolean = isLandscape(context)): Int {
        val maxWidthDp = if (landscape) LANDSCAPE_TOP_ISLAND_MAX_WIDTH_DP else TOP_ISLAND_MAX_WIDTH_DP
        val maxWidth = (maxWidthDp * context.resources.displayMetrics.density).toInt()
        return availableWidthPx.coerceAtMost(maxWidth)
    }

    fun artworkColumnCount(activity: Activity): Int = artworkColumnCount(activity as Context)

    fun artworkColumnCount(context: Context): Int {
        return artworkColumnCountForWidth(availableContentWidthDp(context))
    }

    fun artworkColumnCountForWidth(widthDp: Int): Int {
        val width = widthDp.coerceAtMost(MAX_CONTENT_WIDTH_DP) - CONTENT_GUTTER_DP * 2
        val count = floor((width + GRID_GAP_DP).toDouble() / (MIN_ARTWORK_CELL_DP + GRID_GAP_DP)).toInt()
        return count.coerceIn(2, 6)
    }

    fun configureArtworkGrid(activity: Activity, recyclerView: RecyclerView, grid: GridLayoutManager? = null): Int {
        val spanCount = artworkColumnCount(activity)
        val manager = grid ?: (recyclerView.layoutManager as? GridLayoutManager)
            ?: GridLayoutManager(activity, spanCount)
        manager.spanCount = spanCount
        recyclerView.layoutManager = manager
        val actualWidth = activity.resources.configuration.screenWidthDp
        val contentWidth = actualWidth.coerceAtMost(MAX_CONTENT_WIDTH_DP)
        val outerGutter = ((actualWidth - contentWidth) / 2).coerceAtLeast(0)
        val horizontalInset = dp(activity, outerGutter + CONTENT_GUTTER_DP)
        recyclerView.setPadding(horizontalInset, recyclerView.paddingTop, horizontalInset, recyclerView.paddingBottom)
        recyclerView.clipToPadding = false
        return spanCount
    }

    fun constrainContentWidth(activity: Activity, view: View) {
        view.post {
            val maxWidth = dp(activity, MAX_CONTENT_WIDTH_DP)
            if (view.width > maxWidth) {
                val params = view.layoutParams
                params.width = maxWidth
                view.layoutParams = params
                if (params is ViewGroup.MarginLayoutParams) {
                    params.marginStart = (view.rootView.width - maxWidth) / 2
                    params.marginEnd = params.marginStart
                    view.layoutParams = params
                }
            }
        }
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
