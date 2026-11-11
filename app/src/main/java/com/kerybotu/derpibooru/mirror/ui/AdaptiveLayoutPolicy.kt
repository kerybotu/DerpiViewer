package com.kerybotu.derpibooru.mirror.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.floor

/** Central window-width policy; grids use the same target card size on phones and tablets. */
object AdaptiveLayoutPolicy {
    const val MAX_CONTENT_WIDTH_DP = 1280
    const val TOP_ISLAND_MAX_WIDTH_DP = 460
    const val MEDIUM_TOP_ISLAND_MAX_WIDTH_DP = 600
    const val EXPANDED_TOP_ISLAND_MAX_WIDTH_DP = 720
    /** Maximum width for the five-item floating bottom navigation island. */
    const val BOTTOM_ISLAND_MAX_WIDTH_DP = 520
    const val MIN_ARTWORK_CELL_DP = 156
    const val GRID_GAP_DP = 8
    const val CONTENT_GUTTER_DP = 16
    const val HORIZONTAL_LAYOUT_ASPECT_RATIO = 1.15f
    const val COMPACT_NAVIGATION_RAIL_DP = 72
    const val WIDE_NAVIGATION_RAIL_DP = 88
    const val COMPACT_HEIGHT_MAX_DP = 480
    const val MEDIUM_HEIGHT_MAX_DP = 900
    const val DETAIL_SIDE_PANE_MIN_WIDTH_DP = 840
    const val DETAIL_SIDE_PANE_MIN_HEIGHT_DP = 480

    enum class WindowWidthClass { COMPACT, MEDIUM, EXPANDED, LARGE, EXTRA_LARGE }
    enum class WindowHeightClass { COMPACT, MEDIUM, EXPANDED }
    enum class NavigationMode { BOTTOM_BAR, RAIL }

    data class AdaptiveLayoutSpec(
        val availableWidthDp: Int,
        val availableHeightDp: Int,
        val widthClass: WindowWidthClass,
        val heightClass: WindowHeightClass,
        val navigationMode: NavigationMode,
        val navigationWidthDp: Int,
        val contentMaxWidthDp: Int,
        val horizontalPaddingDp: Int,
        val componentSpacingDp: Int,
        val panelWidthDp: Int,
        val useDetailSidePane: Boolean
    )

    data class ResponsiveTokens(
        val screenGutterDp: Int,
        val contentMaxWidthDp: Int,
        val topIslandMaxWidthDp: Int,
        val bottomIslandMaxWidthDp: Int,
        val minArtworkCellDp: Int,
        val maxArtworkColumns: Int
    )

    fun widthClass(widthDp: Int): WindowWidthClass = when {
        widthDp < 600 -> WindowWidthClass.COMPACT
        widthDp < 840 -> WindowWidthClass.MEDIUM
        widthDp < 1200 -> WindowWidthClass.EXPANDED
        widthDp < 1600 -> WindowWidthClass.LARGE
        else -> WindowWidthClass.EXTRA_LARGE
    }

    fun heightClass(heightDp: Int): WindowHeightClass = when {
        heightDp < COMPACT_HEIGHT_MAX_DP -> WindowHeightClass.COMPACT
        heightDp < MEDIUM_HEIGHT_MAX_DP -> WindowHeightClass.MEDIUM
        else -> WindowHeightClass.EXPANDED
    }

    fun specFor(availableWidthDp: Int, availableHeightDp: Int): AdaptiveLayoutSpec {
        val width = availableWidthDp.coerceAtLeast(1)
        val height = availableHeightDp.coerceAtLeast(1)
        val widthClass = widthClass(width)
        val heightClass = heightClass(height)
        val useRail = widthClass.ordinal >= WindowWidthClass.EXPANDED.ordinal ||
            (width >= 600 && isHorizontalLayout(width, height))
        val navigationMode = if (useRail) NavigationMode.RAIL else NavigationMode.BOTTOM_BAR
        val navigationWidth = if (useRail) navigationRailWidthDp(width) else 0
        val tokens = tokensForWidth(width)
        val usableContentWidth = (width - navigationWidth - tokens.screenGutterDp * 2).coerceAtLeast(1)
        val sidePane = width >= DETAIL_SIDE_PANE_MIN_WIDTH_DP &&
            height >= DETAIL_SIDE_PANE_MIN_HEIGHT_DP &&
            usableContentWidth >= 760
        val panelWidth = if (sidePane) {
            (usableContentWidth * 0.32f).toInt().coerceIn(320, 440)
        } else {
            when (widthClass) {
                WindowWidthClass.COMPACT -> 0
                WindowWidthClass.MEDIUM -> 280
                else -> 320
            }
        }
        return AdaptiveLayoutSpec(
            availableWidthDp = width,
            availableHeightDp = height,
            widthClass = widthClass,
            heightClass = heightClass,
            navigationMode = navigationMode,
            navigationWidthDp = navigationWidth,
            contentMaxWidthDp = tokens.contentMaxWidthDp,
            horizontalPaddingDp = tokens.screenGutterDp,
            componentSpacingDp = when (widthClass) {
                WindowWidthClass.COMPACT -> 8
                WindowWidthClass.MEDIUM -> 12
                WindowWidthClass.EXPANDED -> 16
                WindowWidthClass.LARGE -> 20
                WindowWidthClass.EXTRA_LARGE -> 24
            },
            panelWidthDp = panelWidth,
            useDetailSidePane = sidePane
        )
    }

    fun specFor(context: Context, view: View? = null): AdaptiveLayoutSpec = specFor(
        availableContentWidthDp(context, view),
        availableWindowHeightDp(context, view)
    )

    fun isHorizontalLayout(availableWidthDp: Int, availableHeightDp: Int): Boolean =
        availableWidthDp > availableHeightDp * HORIZONTAL_LAYOUT_ASPECT_RATIO

    fun isHorizontalLayout(configuration: Configuration): Boolean =
        isHorizontalLayout(configuration.screenWidthDp, configuration.screenHeightDp)

    fun navigationRailWidthDp(availableWidthDp: Int): Int =
        if (availableWidthDp < 600) COMPACT_NAVIGATION_RAIL_DP else WIDE_NAVIGATION_RAIL_DP

    fun contentWidthAfterNavigationDp(availableWidthDp: Int, railWidthDp: Int, gutterDp: Int): Int =
        (availableWidthDp - railWidthDp - gutterDp * 2).coerceAtLeast(1)

    fun tokensForWidth(widthDp: Int): ResponsiveTokens = when (widthClass(widthDp)) {
        WindowWidthClass.COMPACT -> ResponsiveTokens(16, 600, TOP_ISLAND_MAX_WIDTH_DP, BOTTOM_ISLAND_MAX_WIDTH_DP, MIN_ARTWORK_CELL_DP, 6)
        WindowWidthClass.MEDIUM -> ResponsiveTokens(20, 900, MEDIUM_TOP_ISLAND_MAX_WIDTH_DP, BOTTOM_ISLAND_MAX_WIDTH_DP, MIN_ARTWORK_CELL_DP, 6)
        WindowWidthClass.EXPANDED -> ResponsiveTokens(24, 1120, EXPANDED_TOP_ISLAND_MAX_WIDTH_DP, BOTTOM_ISLAND_MAX_WIDTH_DP, MIN_ARTWORK_CELL_DP, 6)
        WindowWidthClass.LARGE -> ResponsiveTokens(32, MAX_CONTENT_WIDTH_DP, EXPANDED_TOP_ISLAND_MAX_WIDTH_DP, BOTTOM_ISLAND_MAX_WIDTH_DP, MIN_ARTWORK_CELL_DP, 6)
        WindowWidthClass.EXTRA_LARGE -> ResponsiveTokens(40, MAX_CONTENT_WIDTH_DP, EXPANDED_TOP_ISLAND_MAX_WIDTH_DP, BOTTOM_ISLAND_MAX_WIDTH_DP, MIN_ARTWORK_CELL_DP, 6)
    }

    fun availableContentWidthDp(context: Context, view: View? = null): Int {
        val density = context.resources.displayMetrics.density
        val measuredWidth = view?.width?.takeIf { it > 0 }?.let { (it / density).toInt() }
        val windowWidth = context.resources.configuration.screenWidthDp
        return (measuredWidth ?: windowWidth).coerceAtLeast(1)
    }

    fun availableContentHeightDp(context: Context): Int =
        context.resources.configuration.screenHeightDp.coerceAtLeast(1)

    fun availableWindowHeightDp(context: Context, view: View? = null): Int {
        val density = context.resources.displayMetrics.density
        val measuredHeight = view?.height?.takeIf { it > 0 }?.let { (it / density).toInt() }
        return (measuredHeight ?: context.resources.configuration.screenHeightDp).coerceAtLeast(1)
    }

    fun isHorizontalLayout(context: Context, view: View? = null): Boolean {
        val width = availableContentWidthDp(context, view)
        val height = availableWindowHeightDp(context, view)
        return isHorizontalLayout(width, height)
    }

    @Deprecated("Use available window aspect ratio via isHorizontalLayout")
    fun isLandscape(context: Context): Boolean = isHorizontalLayout(context)

    fun contentIslandWidthPx(context: Context, availableWidthPx: Int): Int {
        val widthDp = (availableWidthPx / context.resources.displayMetrics.density).toInt()
        val maxWidth = (tokensForWidth(widthDp).contentMaxWidthDp * context.resources.displayMetrics.density).toInt()
        return availableWidthPx.coerceAtMost(maxWidth)
    }

    fun bottomIslandWidthPx(context: Context, availableWidthPx: Int): Int {
        val widthDp = (availableWidthPx / context.resources.displayMetrics.density).toInt()
        val maxWidth = (tokensForWidth(widthDp).bottomIslandMaxWidthDp * context.resources.displayMetrics.density).toInt()
        return availableWidthPx.coerceAtMost(maxWidth)
    }

    /** Top navigation is a self-contained island, not a full-width content bar. */
    fun topIslandWidthPx(context: Context, availableWidthPx: Int): Int {
        val widthDp = (availableWidthPx / context.resources.displayMetrics.density).toInt()
        val maxWidthDp = tokensForWidth(widthDp).topIslandMaxWidthDp
        val maxWidth = (maxWidthDp * context.resources.displayMetrics.density).toInt()
        return availableWidthPx.coerceAtMost(maxWidth)
    }

    fun artworkColumnCount(activity: Activity): Int = artworkColumnCount(activity as Context)

    fun artworkColumnCount(context: Context): Int {
        return artworkColumnCountForWidth(availableContentWidthDp(context))
    }

    fun artworkColumnCountForWidth(widthDp: Int): Int {
        val tokens = tokensForWidth(widthDp)
        val width = widthDp.coerceAtMost(tokens.contentMaxWidthDp) - tokens.screenGutterDp * 2
        val count = floor((width + GRID_GAP_DP).toDouble() / (tokens.minArtworkCellDp + GRID_GAP_DP)).toInt()
        return count.coerceIn(2, tokens.maxArtworkColumns)
    }

    fun artworkHorizontalInsetPx(context: Context, availableWidthDp: Int): Int {
        val tokens = tokensForWidth(availableWidthDp)
        val contentWidth = availableWidthDp.coerceAtMost(tokens.contentMaxWidthDp)
        val outerGutter = ((availableWidthDp - contentWidth) / 2).coerceAtLeast(0)
        return dp(context, outerGutter + tokens.screenGutterDp)
    }

    fun configureArtworkGrid(activity: Activity, recyclerView: RecyclerView, grid: GridLayoutManager? = null): Int {
        val spanCount = artworkColumnCount(activity)
        val manager = grid ?: (recyclerView.layoutManager as? GridLayoutManager)
            ?: GridLayoutManager(activity, spanCount)
        recyclerView.layoutManager = manager
        fun updateForMeasuredWidth() {
            val availableWidthDp = availableContentWidthDp(activity, recyclerView)
            val horizontalInset = artworkHorizontalInsetPx(activity, availableWidthDp)
            val columns = artworkColumnCountForWidth(availableWidthDp)
            if (manager.spanCount != columns) manager.spanCount = columns
            if (recyclerView.paddingLeft != horizontalInset || recyclerView.paddingRight != horizontalInset) {
                recyclerView.setPadding(horizontalInset, recyclerView.paddingTop, horizontalInset, recyclerView.paddingBottom)
            }
        }
        updateForMeasuredWidth()
        recyclerView.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) updateForMeasuredWidth()
        }
        recyclerView.clipToPadding = false
        return spanCount
    }

    fun constrainContentWidth(activity: Activity, view: View) {
        view.post {
            val maxWidth = dp(activity, tokensForWidth(availableContentWidthDp(activity, view)).contentMaxWidthDp)
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

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
