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
 * Draws the floating navigation island and its single shared page selector.
 * The navigation view above this one is intentionally transparent and only
 * provides the icons and touch targets.
 */
class BottomNavigationIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val islandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density)
    }
    private val islandRect = RectF()
    private val selectorRect = RectF()
    private var itemCount = 5
    private var selectedIndex = 0
    private var selectorLeft = 0f
    private var selectorWidth = 68f * density
    private var selectorHeight = 46f * density
    private var cornerRadius = 26f * density
    private var selectorAnimator: ValueAnimator? = null
    private var initialized = false
    private var islandColor = 0xCC191C22.toInt()
    private var islandBorderColor = 0x48FFFFFF
    private var selectorColor = 0xD92F3948.toInt()
    private var selectorBorderColor = 0x78FFFFFF
    private var selectorShadowColor = 0x50000000

    init {
        setWillNotDraw(false)
        // The icon/navigation view is layered above this drawing surface.
        elevation = 0f
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = false
    }

    fun setColors(
        islandColor: Int,
        islandBorderColor: Int,
        selectorColor: Int,
        selectorBorderColor: Int
    ) {
        this.islandColor = islandColor
        this.islandBorderColor = islandBorderColor
        this.selectorColor = selectorColor
        this.selectorBorderColor = selectorBorderColor
        invalidate()
    }

    fun setItemCount(count: Int) {
        itemCount = count.coerceAtLeast(1)
        if (width > 0) positionSelector(animate = false)
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

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val itemWidth = width.toFloat() / itemCount.coerceAtLeast(1)
        selectorWidth = min(72f * density, itemWidth * 0.84f)
        selectorHeight = min(48f * density, (height - 8f * density).coerceAtLeast(40f * density))
        cornerRadius = min(26f * density, selectorHeight / 2f)
        positionSelector(animate = false)
    }

    private fun targetLeft(): Float {
        val itemWidth = width.toFloat() / itemCount.coerceAtLeast(1)
        return itemWidth * selectedIndex + (itemWidth - selectorWidth) / 2f
    }

    private fun positionSelector(animate: Boolean) {
        val target = targetLeft()
        selectorAnimator?.cancel()
        if (!initialized || !animate) {
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
        val inset = 1f * density
        islandRect.set(inset, inset, width - inset, height - inset)
        islandPaint.color = islandColor
        islandPaint.style = Paint.Style.FILL
        islandPaint.clearShadowLayer()
        canvas.drawRoundRect(islandRect, cornerRadius, cornerRadius, islandPaint)
        borderPaint.color = islandBorderColor
        canvas.drawRoundRect(islandRect, cornerRadius, cornerRadius, borderPaint)

        val top = (height - selectorHeight) / 2f
        selectorRect.set(selectorLeft, top, selectorLeft + selectorWidth, top + selectorHeight)
        selectorPaint.color = selectorColor
        selectorPaint.style = Paint.Style.FILL
        selectorPaint.setShadowLayer(6f * density, 0f, 2f * density, selectorShadowColor)
        canvas.drawRoundRect(selectorRect, cornerRadius, cornerRadius, selectorPaint)
        selectorPaint.clearShadowLayer()
        borderPaint.color = selectorBorderColor
        canvas.drawRoundRect(selectorRect, cornerRadius, cornerRadius, borderPaint)
    }
}
