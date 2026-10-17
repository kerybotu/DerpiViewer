package com.kerybotu.derpibooru.mirror.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.RadioButton
import androidx.core.graphics.ColorUtils
import com.google.android.material.switchmaterial.SwitchMaterial
import com.kerybotu.derpibooru.mirror.PaletteDefinitions
import com.kerybotu.derpibooru.mirror.PaletteManager

/** A theme-aware switch with a normal 48dp touch target and glass-compatible colors. */
class GlassSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SwitchMaterial(context, attrs) {
    init {
        minWidth = dp(52)
        minimumWidth = dp(52)
        minHeight = dp(48)
        minimumHeight = dp(48)
        includeFontPadding = false
        setPadding(0, 0, 0, 0)
        applyPalette(PaletteManager.colors(context))
    }

    fun applyPalette(colors: PaletteDefinitions.Scheme = PaletteManager.colors(context)) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(colors.onPrimary, colors.muted))
        trackTintList = ColorStateList(
            states,
            intArrayOf(
                ColorUtils.setAlphaComponent(colors.primary, 190),
                ColorUtils.setAlphaComponent(colors.glassBorder, 170)
            )
        )
        setTextColor(colors.glassText)
        invalidate()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

/** A single-selector segmented control shared by theme and site choices. */
class GlassSegmentedControl @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : android.widget.RadioGroup(context, attrs) {
    private val selectorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var selectorLeft = 0f
    private var selectorWidth = 0f
    private var selectorColor = PaletteManager.colors(context).primary
    private var externalListener: ((android.widget.RadioGroup, Int) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        setWillNotDraw(false)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        super.setOnCheckedChangeListener { group, id ->
            animateSelector()
            externalListener?.invoke(group, id)
        }
    }

    fun addOption(id: Int, label: String): RadioButton = RadioButton(context).also { option ->
        option.id = id
        option.text = label
        option.buttonDrawable = null
        option.background = null
        option.gravity = android.view.Gravity.CENTER
        option.minHeight = dp(44)
        option.minimumHeight = dp(44)
        option.setPadding(dp(8), 0, dp(8), 0)
        option.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        addView(option, LayoutParams(0, dp(44), 1f))
    }

    fun setSelectionListener(listener: ((android.widget.RadioGroup, Int) -> Unit)?) {
        externalListener = listener
    }

    fun applyPalette(colors: PaletteDefinitions.Scheme = PaletteManager.colors(context)) {
        selectorColor = ColorUtils.setAlphaComponent(colors.primary, if (colors.isDark) 210 else 185)
        selectorPaint.color = selectorColor
        for (index in 0 until childCount) {
            (getChildAt(index) as? CompoundButton)?.setTextColor(colors.glassText)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (selectorWidth > 0f) {
            selectorPaint.color = selectorColor
            canvas.drawRoundRect(
                RectF(selectorLeft, paddingTop.toFloat(), selectorLeft + selectorWidth,
                    height - paddingBottom.toFloat()),
                (height / 2f), (height / 2f), selectorPaint
            )
        }
        super.onDraw(canvas)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (selectorWidth > 0f) {
            selectorPaint.color = selectorColor
            canvas.drawRoundRect(
                RectF(selectorLeft, paddingTop.toFloat(), selectorLeft + selectorWidth,
                    height - paddingBottom.toFloat()),
                height / 2f, height / 2f, selectorPaint
            )
        }
        super.dispatchDraw(canvas)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { positionSelectorImmediately() }
    }

    private fun positionSelectorImmediately() {
        val selected = selectedChild() ?: return
        selectorLeft = selected.left.toFloat()
        selectorWidth = selected.width.toFloat()
        invalidate()
    }

    private fun animateSelector() {
        val selected = selectedChild() ?: return
        val targetLeft = selected.left.toFloat()
        val targetWidth = selected.width.toFloat()
        if (selectorWidth <= 0f) {
            selectorLeft = targetLeft
            selectorWidth = targetWidth
            invalidate()
            return
        }
        val startLeft = selectorLeft
        val startWidth = selectorWidth
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Ui2DesignSystem.Motion.fastMs
            interpolator = Ui2DesignSystem.Motion.standard
            addUpdateListener {
                val fraction = it.animatedFraction
                selectorLeft = startLeft + (targetLeft - startLeft) * fraction
                selectorWidth = startWidth + (targetWidth - startWidth) * fraction
                invalidate()
            }
            start()
        }
    }

    private fun selectedChild(): View? = (0 until childCount)
        .map { getChildAt(it) }
        .firstOrNull { it.isSelected || (it is CompoundButton && it.isChecked) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

/** A small wrapping container for accent swatches and other compact choices. */
class ResponsiveWrapLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {
    var horizontalGapDp: Int = 12
    var verticalGapDp: Int = 12

    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams =
        ViewGroup.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet?): ViewGroup.LayoutParams =
        ViewGroup.LayoutParams(context, attrs)

    override fun generateLayoutParams(params: ViewGroup.LayoutParams): ViewGroup.LayoutParams =
        ViewGroup.LayoutParams(params)

    override fun checkLayoutParams(params: ViewGroup.LayoutParams): Boolean = true

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        var lineWidth = 0
        var lineHeight = 0
        var totalHeight = paddingTop + paddingBottom
        var rows = 1
        val horizontalGap = dp(horizontalGapDp)
        val verticalGap = dp(verticalGapDp)
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight
            val nextWidth = if (lineWidth == 0) childWidth else lineWidth + horizontalGap + childWidth
            if (lineWidth > 0 && nextWidth > available) {
                totalHeight += lineHeight + verticalGap
                rows++
                lineWidth = childWidth
                lineHeight = childHeight
            } else {
                lineWidth = nextWidth
                lineHeight = maxOf(lineHeight, childHeight)
            }
        }
        if (lineWidth > 0) totalHeight += lineHeight
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(totalHeight, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val available = (right - left - paddingLeft - paddingRight).coerceAtLeast(0)
        val horizontalGap = dp(horizontalGapDp)
        val verticalGap = dp(verticalGapDp)
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            val nextX = if (x == paddingLeft) x + child.measuredWidth else x + horizontalGap + child.measuredWidth
            if (x != paddingLeft && nextX > right - left - paddingRight) {
                x = paddingLeft
                y += lineHeight + verticalGap
                lineHeight = 0
            }
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            x = child.right + horizontalGap
            lineHeight = maxOf(lineHeight, child.measuredHeight)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
