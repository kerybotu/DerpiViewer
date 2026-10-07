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

/** Library tab bar with app navigation, reselection and a fixed white foreground. */
class HomeGlassTabBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LiquidGlassTabBar(context, attrs) {
    var onItemSelected: ((Int) -> Unit)? = null
    var onItemReselected: ((Int) -> Unit)? = null
    private var synchronizing = false
    private var hasIconTabs = false
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        minimumHeight = (64 * resources.displayMetrics.density).toInt()
        onTabSelected = { index ->
            applyForeground()
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
            gravity = if (hasIconTabs) Gravity.CENTER_VERTICAL else Gravity.TOP
        }
        items.forEachIndexed { index, item ->
            row.getChildAt(index).apply {
                if (hasIconTabs) configureNavigationItem(this as LinearLayout)
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

    private fun navigationRadiusDp(): Float =
        (height.takeIf { it > 0 } ?: dp(64)) / resources.displayMetrics.density / 2f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!hasIconTabs) return
        // Keep the inset contours aligned when larger system fonts grow the bar.
        cornerRadius = h / 2f
        invalidateOutline()
        for (index in 0 until childCount) (getChildAt(index) as? LiquidGlassView)?.let { droplet ->
            droplet.cornerRadius = (cornerRadius - paddingTop).coerceAtLeast(0f)
            droplet.invalidateOutline()
        }
    }

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
        backdropSource = source
        enableDynamicBackground = active
        enableSensorHighlight = active
        // Both surfaces sample the same opaque page, avoiding double tint/blur.
        for (i in 0 until childCount) (getChildAt(i) as? LiquidGlassView)?.let {
            it.backdropSource = source
            it.enableDynamicBackground = active
            it.enableSensorHighlight = active
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
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
    }
}
