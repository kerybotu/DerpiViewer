package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.example.liquidglass.LiquidGlassTabBar
import com.example.liquidglass.LiquidGlassView
import kotlin.math.abs

/** Library tab bar with app navigation, reselection and fixed theme colors. */
class HomeGlassTabBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LiquidGlassTabBar(context, attrs) {
    var onItemSelected: ((Int) -> Unit)? = null
    var onItemReselected: ((Int) -> Unit)? = null
    private var synchronizing = false
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
        setTabs(items)
        val row = getChildAt(0) as ViewGroup
        items.forEachIndexed { index, item ->
            row.getChildAt(index).apply {
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

    fun selectItem(index: Int) {
        if (selectedIndex == index) return
        synchronizing = true
        try { selectedIndex = index } finally { synchronizing = false }
        applyForeground()
    }

    fun applyPalette() {
        GlassWidgetStyle.apply(this, Ui2DesignSystem.Shape.navigationIsland)
        onAppearanceChanged(overLight)
        // Keep labels above the glass passes so the header's blur/refraction
        // applies to the page backdrop, never to the navigation text or icons.
        getChildAt(0)?.elevation = resources.displayMetrics.density
        for (i in 0 until childCount) {
            (getChildAt(i) as? LiquidGlassView)?.let { droplet ->
                GlassWidgetStyle.apply(droplet, 999f)
            }
        }
        applyForeground()
    }

    override fun onAppearanceChanged(isOverLight: Boolean) {
        super.onAppearanceChanged(isOverLight)
        applyForeground()
    }

    private fun applyForeground() {
        val row = getChildAt(0) as? ViewGroup ?: return
        val color = GlassWidgetStyle.foreground(this)
        selectedTintColor = color
        fun tint(view: View) {
            when (view) {
                is TextView -> view.setTextColor(GlassWidgetStyle.TEXT_COLOR)
                is ImageView -> view.imageTintList = ColorStateList.valueOf(color)
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) tint(view.getChildAt(i))
        }
        for (i in 0 until row.childCount) {
            val tab = row.getChildAt(i)
            tab.isSelected = i == selectedIndex
            tint(tab)
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
}
