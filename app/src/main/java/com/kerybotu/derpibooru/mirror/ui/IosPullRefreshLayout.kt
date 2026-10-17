package com.kerybotu.derpibooru.mirror.ui

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.kerybotu.derpibooru.mirror.PaletteDefinitions
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.R
import kotlin.math.abs

/** Keeps the page/header fixed and opens a refresh area above the scrolling content. */
class IosPullRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val state = PullRefreshState(dp(80).toFloat())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val indicator = IosActivityIndicator(context).apply {
        visibility = View.GONE
        setPullProgress(0f)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var listener: (() -> Unit)? = null
    private var canRefresh: () -> Boolean = { true }
    private var animator: ValueAnimator? = null
    private var displayedDistance = 0f
    private var activePointer = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var horizontalGesture = false

    var onPullStarted: (() -> Unit)? = null
    val isPulling: Boolean get() = dragging || state.isRefreshing

    var scrollTarget: View? = null
        set(value) {
            field?.translationY = 0f
            field = value
            value?.overScrollMode = View.OVER_SCROLL_NEVER
            indicator.bringToFront()
            showDistance(displayedDistance)
        }

    /** Bottom of fixed controls, in this layout's coordinates (usually the list's top padding). */
    var contentTopInset = 0
        set(value) {
            field = value.coerceAtLeast(0)
            positionIndicator()
        }

    var isRefreshing: Boolean
        get() = state.isRefreshing
        set(value) {
            if (value == state.isRefreshing && (value || displayedDistance == 0f)) return
            state.setRefreshing(value)
            if (value) {
                indicator.visibility = View.VISIBLE
                indicator.startLoading()
            }
            settleTo(state.distance)
        }

    init {
        addView(indicator, LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
    }

    fun setOnRefreshListener(listener: () -> Unit) { this.listener = listener }
    fun setCanRefresh(canRefresh: () -> Boolean) { this.canRefresh = canRefresh }
    fun applyPalette(colors: PaletteDefinitions.Scheme = PaletteManager.colors(context)) {
        indicator.applyPalette(colors)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        indicator.bringToFront()
    }

    private fun canStartPull(): Boolean = isEnabled && isShown && listener != null &&
        scrollTarget?.isShown == true && !state.isRefreshing && animator?.isRunning != true && canRefresh()

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginGesture(event)
            MotionEvent.ACTION_MOVE -> {
                if (!canStartPull() || horizontalGesture) return false
                val index = event.findPointerIndex(activePointer)
                if (index < 0) return false
                if (scrollTarget?.canScrollVertically(-1) == true) {
                    downX = event.getX(index)
                    downY = event.getY(index)
                    return false
                }
                val dx = event.getX(index) - downX
                val dy = event.getY(index) - downY
                if (abs(dx) > touchSlop && abs(dx) > abs(dy)) horizontalGesture = true
                if (!horizontalGesture && dy > touchSlop && dy > abs(dx)) {
                    startDragging()
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_UP -> switchPointer(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> activePointer = MotionEvent.INVALID_POINTER_ID
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            beginGesture(event)
            return canStartPull()
        }
        if (!isEnabled || (!state.isRefreshing && !canRefresh())) {
            if (dragging) finishGesture(cancelled = true)
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointer)
                if (index < 0) { finishGesture(cancelled = true); return false }
                val dy = event.getY(index) - downY
                if (!dragging && canStartPull() && scrollTarget?.canScrollVertically(-1) != true &&
                    dy > touchSlop && dy > abs(event.getX(index) - downX)) startDragging()
                if (dragging) {
                    state.pull((dy - touchSlop) * 0.5f)
                    indicator.setPullProgress(state.progress)
                    showDistance(state.distance)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> switchPointer(event)
            MotionEvent.ACTION_UP -> {
                if (dragging) finishGesture(cancelled = false) else performClick()
                activePointer = MotionEvent.INVALID_POINTER_ID
            }
            MotionEvent.ACTION_CANCEL -> finishGesture(cancelled = true)
        }
        return true
    }

    private fun beginGesture(event: MotionEvent) {
        activePointer = event.getPointerId(0)
        downX = event.x
        downY = event.y
        dragging = false
        horizontalGesture = false
    }

    private fun startDragging() {
        dragging = true
        onPullStarted?.invoke()
        (scrollTarget as? RecyclerView)?.stopScroll()
        parent?.requestDisallowInterceptTouchEvent(true)
        indicator.bringToFront()
    }

    private fun switchPointer(event: MotionEvent) {
        val lifted = event.actionIndex
        if (event.getPointerId(lifted) != activePointer) return
        val replacement = if (lifted == 0) 1 else 0
        if (replacement >= event.pointerCount) { finishGesture(cancelled = true); return }
        // Preserve pull distance when a second finger replaces the first one.
        downY += event.getY(replacement) - event.getY(lifted)
        downX += event.getX(replacement) - event.getX(lifted)
        activePointer = event.getPointerId(replacement)
    }

    private fun finishGesture(cancelled: Boolean) {
        dragging = false
        activePointer = MotionEvent.INVALID_POINTER_ID
        parent?.requestDisallowInterceptTouchEvent(false)
        val refresh = state.release(cancelled)
        if (refresh) {
            indicator.startLoading()
            announceForAccessibility(context.getString(R.string.refreshing))
        }
        settleTo(state.distance)
        if (refresh) listener?.invoke()
    }

    private fun settleTo(distance: Float) {
        animator?.cancel()
        if (!isAttachedToWindow) { showDistance(distance); return }
        animator = ValueAnimator.ofFloat(displayedDistance, distance).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val offset = it.animatedValue as Float
                if (!state.isRefreshing) indicator.setPullProgress((offset / state.triggerDistance).coerceIn(0f, 1f))
                showDistance(offset)
            }
            start()
        }
    }

    private fun showDistance(distance: Float) {
        displayedDistance = distance
        scrollTarget?.translationY = distance
        indicator.visibility = if (distance > 0f || state.isRefreshing) View.VISIBLE else View.GONE
        positionIndicator()
    }

    private fun positionIndicator() {
        val size = dp(32).toFloat()
        indicator.translationY = contentTopInset + displayedDistance / 2f - size / 2f
        val scale = (displayedDistance / size).coerceIn(0f, 1f)
        indicator.scaleX = scale
        indicator.scaleY = scale
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        showDistance(state.distance)
        if (state.isRefreshing) indicator.startLoading()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        if (!state.isRefreshing) state.release(cancelled = true)
        dragging = false
        activePointer = MotionEvent.INVALID_POINTER_ID
        scrollTarget?.translationY = 0f
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
