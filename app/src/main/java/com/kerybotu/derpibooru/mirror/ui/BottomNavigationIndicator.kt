package com.kerybotu.derpibooru.mirror.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * A live LiquidGlass backdrop with the existing shared page selector above it.
 * The navigation view above this one is intentionally transparent and only
 * provides the icons and touch targets.
 */
class BottomNavigationIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : IslandGlassView(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val selectorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density)
    }
    private val selectorRect = RectF()
    private var itemCount = 5
    private var selectedIndex = 0
    private var selectorLeft = 0f
    private var selectorWidth = 68f * density
    private var selectorHeight = 46f * density
    private var selectorCornerRadius = 18f * density
    private var selectorAnimator: ValueAnimator? = null
    private var initialized = false
    private var selectorColor = 0xD92F3948.toInt()
    private var selectorBorderColor = 0x78FFFFFF

    init {
        // The icon/navigation view is layered above this drawing surface.
        elevation = 0f
    }

    override fun onAppearanceChanged(isOverLight: Boolean) {
        selectorColor = if (isOverLight) 0x70FFFFFF else 0x602F3948
        selectorBorderColor = if (isOverLight) 0x80FFFFFF.toInt() else 0x50FFFFFF
        invalidate()
    }

    override fun setRenderingActive(active: Boolean, source: View?) {
        super.setRenderingActive(active, source)
        if (!active) selectorAnimator?.cancel()
    }

    fun setItemCount(count: Int) {
        itemCount = count.coerceAtLeast(1)
        selectedIndex = selectedIndex.coerceAtMost(itemCount - 1)
        if (width > 0) updateSelectorGeometry()
    }

    fun setSelectedIndex(index: Int, animate: Boolean = true) {
        val targetIndex = index.coerceIn(0, itemCount - 1)
        selectedIndex = targetIndex
        if (width <= 0) {
            initialized = false
            return
        }
        positionSelector(animate)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateSelectorGeometry()
    }

    private fun updateSelectorGeometry() {
        val inset = Ui2DesignSystem.Spacing.xs * density
        val itemWidth = width.toFloat() / itemCount
        selectorWidth = min(72f * density, (itemWidth - 2f * inset).coerceAtLeast(0f))
        selectorHeight = min(48f * density, (height - 2f * inset).coerceAtLeast(0f))
        // Nested corners follow the same curve: 26dp outer - 8dp inset = 18dp inner.
        // Clamp to the measured bounds on narrow windows and during initial layout.
        val outerRadius = min(cornerRadius, min(width, height) / 2f)
        val verticalInset = (height - selectorHeight) / 2f
        selectorCornerRadius = min(
            (outerRadius - verticalInset).coerceAtLeast(0f),
            min(selectorWidth, selectorHeight) / 2f
        )
        positionSelector(animate = false)
    }

    private fun targetLeft(): Float {
        val itemWidth = width.toFloat() / itemCount.coerceAtLeast(1)
        val visualIndex = if (layoutDirection == LAYOUT_DIRECTION_RTL) itemCount - 1 - selectedIndex else selectedIndex
        return itemWidth * visualIndex + (itemWidth - selectorWidth) / 2f
    }

    private fun positionSelector(animate: Boolean) {
        val target = targetLeft()
        selectorAnimator?.cancel()
        if (!initialized || !animate || (android.os.Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled())) {
            selectorLeft = target
            initialized = true
            invalidate()
            return
        }
        val start = selectorLeft
        selectorAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = Ui2DesignSystem.Motion.normalMs
            interpolator = Ui2DesignSystem.Motion.spring
            addUpdateListener {
                selectorLeft = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val top = (height - selectorHeight) / 2f
        selectorRect.set(selectorLeft, top, selectorLeft + selectorWidth, top + selectorHeight)
        selectorPaint.color = selectorColor
        selectorPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(selectorRect, selectorCornerRadius, selectorCornerRadius, selectorPaint)
        borderPaint.color = selectorBorderColor
        canvas.drawRoundRect(selectorRect, selectorCornerRadius, selectorCornerRadius, borderPaint)
    }

    override fun onDetachedFromWindow() {
        selectorAnimator?.cancel()
        selectorAnimator = null
        super.onDetachedFromWindow()
    }
}
