package com.kerybotu.derpibooru.mirror.ui

import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/** Scroll-linked motion for the Home top island only. */
class HomeTopIslandController(
    private val island: View,
    private val content: View?
) {
    private val density = island.resources.displayMetrics.density
    private var offset = 0f
    private var maxTravel = 0f
    private var laidOut = false
    private var entered = false

    fun onLayoutChanged() {
        val margin = (island.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        maxTravel = (island.height + margin + (12f * density)).coerceAtLeast(1f)
        if (!laidOut) {
            laidOut = true
            updateVisuals()
            if (!entered && island.visibility == View.VISIBLE) enter()
        } else {
            offset = offset.coerceIn(-maxTravel, 0f)
            updateVisuals()
        }
    }

    fun onScrolled(dy: Int) {
        if (island.visibility != View.VISIBLE || maxTravel <= 0f) return
        offset = (offset - dy.toFloat()).coerceIn(-maxTravel, 0f)
        updateVisuals()
    }

    fun onScrollIdle(recyclerView: RecyclerView) {
        if (island.visibility != View.VISIBLE || maxTravel <= 0f) return
        val target = if (!recyclerView.canScrollVertically(-1)) 0f
        else if (offset > -maxTravel * 0.5f) 0f else -maxTravel
        animateTo(target)
    }

    fun restoreForHome() {
        if (!laidOut) return
        animateTo(if (offset > -maxTravel * 0.5f) 0f else -maxTravel)
    }

    fun reset() {
        offset = 0f
        updateVisuals()
    }

    private fun enter() {
        entered = true
        island.animate().cancel()
        content?.animate()?.cancel()
        island.alpha = 0f
        island.translationY = -20f * density
        island.scaleX = 0.94f
        island.scaleY = 0.94f
        island.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(420L).setInterpolator(Ui2DesignSystem.Motion.spring).start()
    }

    private fun animateTo(target: Float) {
        val start = offset
        if (kotlin.math.abs(start - target) < 0.5f) {
            offset = target
            updateVisuals()
            return
        }
        android.animation.ValueAnimator.ofFloat(start, target).apply {
            duration = 220L
            interpolator = Ui2DesignSystem.Motion.spring
            addUpdateListener { offset = it.animatedValue as Float; updateVisuals() }
            start()
        }
    }

    private fun updateVisuals() {
        val progress = (-offset / maxTravel.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val visual = progress * progress * (3f - 2f * progress)
        island.translationY = offset
        island.alpha = 1f - 0.28f * visual
        island.scaleX = 1f - 0.025f * visual
        island.scaleY = 1f - 0.04f * visual
        content?.translationY = offset * 0.08f
        island.elevation = island.resources.displayMetrics.density * (12f - 3f * visual)
        val radius = (28f + 4f * visual) * density
        (island.background as? GradientDrawable)?.cornerRadius = radius
    }
}
