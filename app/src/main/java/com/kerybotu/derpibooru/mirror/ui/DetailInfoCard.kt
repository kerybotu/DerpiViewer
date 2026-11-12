package com.kerybotu.derpibooru.mirror.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import kotlin.math.abs

/** One geometry progress drives the entire card and the media viewport. No child translations. */
internal class DetailInfoCard(context: Context) : IslandGlassView(context) {
    var progress = 0f; private set
    var onProgress: (() -> Unit)? = null
    var dragRangePx = 1f
    private var animator: ValueAnimator? = null
    private var startX = 0f
    private var startY = 0f
    private var startProgress = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel(); startX = event.rawX; startY = event.rawY; startProgress = progress; dragging = false
                tracker?.recycle(); tracker = VelocityTracker.obtain().also { it.addMovement(event) }
            }
            MotionEvent.ACTION_MOVE -> if (abs(event.rawX - startX) > slop && abs(event.rawX - startX) > abs(event.rawY - startY) * 1.2f) {
                dragging = true; parent?.requestDisallowInterceptTouchEvent(true); return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.recycle(); tracker = null
                if (progress > 0f && progress < 1f) snap(DetailInfoSnap.target(progress, 0f))
            }
        }
        return false
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        tracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> { progress = (startProgress + (startX - event.rawX) / dragRangePx.coerceAtLeast(1f)).coerceIn(0f, 1f); onProgress?.invoke() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.computeCurrentVelocity(1000)
                val velocity = if (event.actionMasked == MotionEvent.ACTION_CANCEL) 0f else tracker?.xVelocity ?: 0f
                tracker?.recycle(); tracker = null; dragging = false
                snap(DetailInfoSnap.target(progress, velocity))
            }
        }
        return true
    }
    fun snap(state: DetailInfoState, animate: Boolean = true) {
        animator?.cancel()
        val target = if (state == DetailInfoState.EXPANDED) 1f else 0f
        if (!animate) { progress = target; onProgress?.invoke(); return }
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 220
            addUpdateListener { progress = it.animatedValue as Float; onProgress?.invoke() }
            var cancelled = false
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled) { progress = target; onProgress?.invoke() } }
            })
            start()
        }
    }
    override fun onDetachedFromWindow() { animator?.cancel(); tracker?.recycle(); tracker = null; super.onDetachedFromWindow() }
}
