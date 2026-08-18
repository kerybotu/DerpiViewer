package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import android.view.ViewGroup

class PullRefreshLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private var downY = 0f
    private var refreshing = false
    private var listener: (() -> Unit)? = null

    fun setOnRefreshListener(block: () -> Unit) { listener = block }
    var isRefreshing: Boolean
        get() = refreshing
        set(value) { refreshing = value; if (!value) translationY = 0f }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> downY = event.y
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - downY
                val atTop = !canAnyDescendantScrollUp(this)
                if (dy > 36 && atTop) return true
            }
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (event.y - downY > 72 && !refreshing) {
                refreshing = true
                listener?.invoke()
            }
            performClick()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            translationY = ((event.y - downY) * 0.25f).coerceAtLeast(0f).coerceAtMost(36f)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /** Finds the actual scrolling child when this layout wraps another container. */
    private fun canAnyDescendantScrollUp(view: View): Boolean {
        if (view !== this && view.canScrollVertically(-1)) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (canAnyDescendantScrollUp(view.getChildAt(i))) return true
            }
        }
        return false
    }
}
