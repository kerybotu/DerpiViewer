package com.kerybotu.derpibooru.mirror.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.widget.ProgressBar
import com.kerybotu.derpibooru.mirror.PaletteDefinitions
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.R
import kotlin.math.roundToInt

/**
 * Shared iOS-style loader for XML layouts and programmatically created views.
 * Keeps native progress accessibility and visibility-driven animation lifecycle.
 */
class IosActivityIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.progressBarStyle
) : ProgressBar(context, attrs, defStyleAttr) {
    init {
        isIndeterminate = true
        indeterminateDrawable = SpokeDrawable((32 * resources.displayMetrics.density).roundToInt())
        if (contentDescription.isNullOrBlank()) contentDescription = context.getString(R.string.loading)
        applyPalette()
    }

    fun applyPalette(colors: PaletteDefinitions.Scheme = PaletteManager.colors(context)) {
        indeterminateTintList = ColorStateList.valueOf(colors.muted)
    }
}

private class SpokeDrawable(private val intrinsicSize: Int) : Drawable(), Animatable, Runnable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private var tint: ColorStateList? = null
    private var color = Color.GRAY
    private var drawableAlpha = 255
    private var head = 0
    private var running = false

    override fun draw(canvas: Canvas) {
        val size = minOf(bounds.width(), bounds.height()).toFloat()
        if (size <= 0f) return
        val checkpoint = canvas.save()
        canvas.translate(bounds.exactCenterX(), bounds.exactCenterY())
        paint.color = color
        paint.strokeWidth = size * 0.075f
        repeat(SPOKE_COUNT) { index ->
            val age = (head - index + SPOKE_COUNT) % SPOKE_COUNT
            val opacity = 1f - 0.8f * age / (SPOKE_COUNT - 1)
            paint.alpha = (Color.alpha(color) * drawableAlpha / 255f * opacity).roundToInt()
            canvas.drawLine(0f, -size * 0.23f, 0f, -size * 0.41f, paint)
            canvas.rotate(360f / SPOKE_COUNT)
        }
        canvas.restoreToCount(checkpoint)
    }

    override fun start() {
        if (running || !isVisible) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()) return
        running = true
        head = 0
        invalidateSelf()
        scheduleSelf(this, SystemClock.uptimeMillis() + FRAME_DURATION_MS)
    }

    override fun stop() {
        running = false
        unscheduleSelf(this)
    }

    override fun isRunning(): Boolean = running

    override fun run() {
        if (!running || !isVisible) return
        head = (head + 1) % SPOKE_COUNT
        invalidateSelf()
        scheduleSelf(this, SystemClock.uptimeMillis() + FRAME_DURATION_MS)
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (!visible) stop()
        return changed
    }

    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint
        onStateChange(state)
    }

    override fun isStateful(): Boolean = tint?.isStateful == true

    override fun onStateChange(state: IntArray): Boolean {
        val nextColor = tint?.let { it.getColorForState(state, it.defaultColor) } ?: Color.GRAY
        if (color == nextColor) return false
        color = nextColor
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawableAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = intrinsicSize
    override fun getIntrinsicHeight(): Int = intrinsicSize

    private companion object {
        const val SPOKE_COUNT = 12
        const val FRAME_DURATION_MS = 80L
    }
}
