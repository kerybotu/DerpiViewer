package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.liquidglass.LiquidGlassTabBar
import com.example.liquidglass.LiquidGlassView
import kotlin.math.abs

/** Library tab bar with app navigation, reselection and theme-aware foreground. */
class HomeGlassTabBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LiquidGlassTabBar(context, attrs) {
    var onItemSelected: ((Int) -> Unit)? = null
    var onItemReselected: ((Int) -> Unit)? = null
    private var synchronizing = false
    private var hasIconTabs = false
    private var verticalNavigation = false
    private var railSelection: LiquidGlassView? = null
    private var railTargetY = Float.NaN
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        minimumHeight = (64 * resources.displayMetrics.density).toInt()
        onTabSelected = { index ->
            applyForeground()
            if (verticalNavigation) updateRailSelection(true)
            if (!synchronizing) onItemSelected?.invoke(index)
        }
    }

    fun configureTabs(items: List<TabItem>) {
        hasIconTabs = items.any { it.icon != null }
        setTabs(items)
        val inset = dp(if (hasIconTabs) NAVIGATION_INSET_DP else 4)
        setPadding(inset, inset, inset, inset)
        val row = getChildAt(0) as ViewGroup
        row.layoutParams = (row.layoutParams as LayoutParams).apply {
            gravity = Gravity.CENTER_VERTICAL
            if (!hasIconTabs) height = LayoutParams.MATCH_PARENT
        }
        items.forEachIndexed { index, item ->
            row.getChildAt(index).apply {
                if (hasIconTabs) configureNavigationItem(this as LinearLayout)
                else configureTextItem(this as LinearLayout)
                contentDescription = item.title
                isFocusable = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                setOnClickListener {
                    if (selectedIndex == index) onItemReselected?.invoke(index)
                    else selectedIndex = index
                }
                (this as? ViewGroup)?.let { tab ->
                    for (child in 0 until tab.childCount)
                        tab.getChildAt(child).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            }
        }
        applyPalette()
        applyNavigationOrientation()
        if (verticalNavigation) post { updateRailSelection(false) }
    }

    /** Reflows the same animated tab cells into a vertical island for wide windows. */
    fun setVerticalNavigation(vertical: Boolean) {
        if (verticalNavigation == vertical) return
        verticalNavigation = vertical
        if (vertical && railSelection == null) {
            railSelection = LiquidGlassView(context).also { selection ->
                selection.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(selection, LayoutParams(1, 1, Gravity.TOP or Gravity.LEFT))
            }
        }
        railSelection?.visibility = if (vertical) VISIBLE else GONE
        railTargetY = Float.NaN
        applyNavigationOrientation()
        applyPalette()
        if (verticalNavigation) post { updateRailSelection(false) }
        requestLayout()
    }

    private fun applyNavigationOrientation() {
        val row = getChildAt(0) as? LinearLayout ?: return
        if (!hasIconTabs) return
        row.orientation = if (verticalNavigation) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        row.layoutParams = (row.layoutParams as LayoutParams).apply {
            gravity = if (verticalNavigation) Gravity.CENTER else Gravity.CENTER_VERTICAL
            height = if (verticalNavigation) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT
            width = if (verticalNavigation) LayoutParams.MATCH_PARENT else LayoutParams.MATCH_PARENT
        }
        for (index in 0 until row.childCount) {
            val tab = row.getChildAt(index) as? LinearLayout ?: continue
            tab.layoutParams = (tab.layoutParams as LinearLayout.LayoutParams).apply {
                width = if (verticalNavigation) LayoutParams.MATCH_PARENT else 0
                height = if (verticalNavigation) 0 else LayoutParams.MATCH_PARENT
                weight = 1f
            }
            tab.minimumHeight = dp(48)
            tab.setPadding(dp(4), dp(2), dp(4), dp(2))
            tab.gravity = Gravity.CENTER
        }
        minimumHeight = if (verticalNavigation) dp(48 * row.childCount + NAVIGATION_INSET_DP * 2)
            else dp(64)
        setPadding(dp(NAVIGATION_INSET_DP), dp(NAVIGATION_INSET_DP), dp(NAVIGATION_INSET_DP), dp(NAVIGATION_INSET_DP))
    }

    private fun configureTextItem(tab: LinearLayout) {
        // The AAR uses a wrap-content row and a start-aligned label. Make the
        // hit target, selection droplet and text share the same measured cell.
        tab.layoutParams = (tab.layoutParams as LinearLayout.LayoutParams).apply {
            height = LayoutParams.MATCH_PARENT
        }
        tab.gravity = Gravity.CENTER
        tab.minimumHeight = dp(48)
        tab.setPadding(dp(8), dp(4), dp(8), dp(4))
        for (index in 0 until tab.childCount) {
            (tab.getChildAt(index) as? TextView)?.apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                    width = LayoutParams.MATCH_PARENT
                }
            }
        }
    }

    private fun configureNavigationItem(tab: LinearLayout) {
        // A 52dp item inside a 64dp bar leaves an even 6dp border on every side.
        tab.minimumHeight = dp(64 - NAVIGATION_INSET_DP * 2)
        tab.gravity = Gravity.CENTER
        tab.setPadding(dp(4), dp(6), dp(4), dp(6))
        for (index in 0 until tab.childCount) {
            when (val child = tab.getChildAt(index)) {
                is ImageView -> child.layoutParams = (child.layoutParams as LinearLayout.LayoutParams).apply {
                    width = dp(24)
                    height = dp(24)
                }
                is TextView -> {
                    child.includeFontPadding = false
                    child.gravity = Gravity.CENTER
                    child.layoutParams = (child.layoutParams as LinearLayout.LayoutParams).apply {
                        topMargin = dp(2)
                    }
                }
            }
        }
    }

    fun selectItem(index: Int) {
        if (selectedIndex == index) return
        synchronizing = true
        try { selectedIndex = index } finally { synchronizing = false }
        applyForeground()
    }

    fun applyPalette(colors: com.kerybotu.derpibooru.mirror.PaletteDefinitions.Scheme = com.kerybotu.derpibooru.mirror.PaletteManager.colors(context)) {
        val radius = if (hasIconTabs) navigationRadiusDp() else Ui2DesignSystem.Shape.navigationIsland
        GlassWidgetStyle.apply(this, radius, colors)
        onAppearanceChanged(overLight)
        // Keep labels above the glass passes so the header's blur/refraction
        // applies to the page backdrop, never to the navigation text or icons.
        getChildAt(0)?.elevation = resources.displayMetrics.density
        for (i in 0 until childCount) {
            (getChildAt(i) as? LiquidGlassView)?.let { droplet ->
                GlassWidgetStyle.apply(droplet, if (hasIconTabs) (radius - NAVIGATION_INSET_DP).coerceAtLeast(0f) else 999f, colors)
            }
        }
        applyForeground()
    }

    private fun navigationRadiusDp(): Float {
        val dimension = if (verticalNavigation) width else height.takeIf { it > 0 } ?: dp(64)
        return dimension / resources.displayMetrics.density / 2f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!hasIconTabs) return
        // Keep the inset contours aligned when larger system fonts grow the bar.
        cornerRadius = (if (verticalNavigation) w else h) / 2f
        invalidateOutline()
        for (index in 0 until childCount) (getChildAt(index) as? LiquidGlassView)?.let { droplet ->
            droplet.cornerRadius = if (verticalNavigation) {
                (minOf(w, dp(NAVIGATION_ITEM_HEIGHT_DP)) / 2f - paddingTop).coerceAtLeast(0f)
            } else (cornerRadius - paddingTop).coerceAtLeast(0f)
            droplet.invalidateOutline()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (verticalNavigation) updateRailSelection(false)
    }

    private fun updateRailSelection(animate: Boolean) {
        val selection = railSelection ?: return
        val row = getChildAt(0) as? ViewGroup ?: return
        val cell = row.getChildAt(selectedIndex) ?: return
        if (cell.width == 0 || cell.height == 0) return
        // The library's droplet drag/settle calculations use X only. Keep that
        // path for the bottom bar and drive this rail's selection on Y instead.
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child is LiquidGlassView && child !== selection) child.visibility = INVISIBLE
        }
        selection.visibility = VISIBLE
        selection.layout(paddingLeft, paddingTop, paddingLeft + cell.width, paddingTop + cell.height)
        selection.cornerRadius = minOf(cell.width, cell.height) / 2f
        val target = (row.top + cell.top - paddingTop).toFloat()
        if (target == railTargetY && !changedRailSize(selection, cell)) return
        selection.animate().cancel()
        val canAnimate = animate && !railTargetY.isNaN()
        railTargetY = target
        selection.translationX = (row.left + cell.left - paddingLeft).toFloat()
        if (canAnimate) selection.animate().translationY(target).setDuration(Ui2DesignSystem.Motion.normalMs)
            .setInterpolator(Ui2DesignSystem.Motion.spring).start()
        else selection.translationY = target
    }

    private fun changedRailSize(selection: View, cell: View): Boolean =
        selection.width != cell.width || selection.height != cell.height

    override fun onAppearanceChanged(isOverLight: Boolean) {
        super.onAppearanceChanged(isOverLight)
        applyForeground()
    }

    private fun applyForeground() {
        val row = getChildAt(0) as? ViewGroup ?: return
        val colors = com.kerybotu.derpibooru.mirror.PaletteManager.colors(context)
        selectedTintColor = colors.primary
        fun tint(view: View, selected: Boolean) {
            when (view) {
                is TextView -> view.setTextColor(colors.glassText)
                is ImageView -> view.imageTintList = ColorStateList.valueOf(if (selected) colors.primary else colors.glassText)
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) tint(view.getChildAt(i), selected)
        }
        for (i in 0 until row.childCount) {
            val tab = row.getChildAt(i)
            tab.isSelected = i == selectedIndex
            tint(tab, tab.isSelected)
        }
        invalidate()
    }

    fun setRenderingActive(active: Boolean, source: View) {
        GlassWidgetStyle.setRenderingActive(this, active, source)
        // Both surfaces sample the same opaque page, avoiding double tint/blur.
        for (i in 0 until childCount) (getChildAt(i) as? LiquidGlassView)?.let {
            GlassWidgetStyle.setRenderingActive(it, active && it.visibility == VISIBLE, source)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (verticalNavigation) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; moved = false }
                MotionEvent.ACTION_MOVE -> if (abs(event.y - downY) > touchSlop || abs(event.x - downX) > touchSlop) moved = true
                MotionEvent.ACTION_UP -> if (!moved && event.x in 0f..width.toFloat()) {
                    val row = getChildAt(0) as? ViewGroup ?: return true
                    val index = (0 until row.childCount).firstOrNull { i ->
                        val cell = row.getChildAt(i)
                        event.y >= row.top + cell.top && event.y < row.top + cell.bottom
                    }
                    if (index != null) row.getChildAt(index).performClick()
                }
                MotionEvent.ACTION_CANCEL -> moved = true
            }
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x; downY = event.y; moved = false
        }
        if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) moved = true
        val previous = selectedIndex
        val result = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && !moved && selectedIndex == previous &&
            event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()) {
            onItemReselected?.invoke(previous)
        }
        return result
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val NAVIGATION_INSET_DP = 6
        const val NAVIGATION_ITEM_HEIGHT_DP = 64
    }
}
