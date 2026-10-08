package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup

/** Draws a scroll container's visible content without drawing the container itself. */
internal class ScrollContentBackdrop(
    context: Context,
    private val scroll: ViewGroup
) : View(context) {
    private val origin = IntArray(2)
    private val location = IntArray(2)

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val content = scroll.getChildAt(0) ?: return
        if (!scroll.isShown || content.visibility != VISIBLE) return

        getLocationInWindow(origin)
        scroll.getLocationInWindow(location)
        val x = (location[0] - origin[0]).toFloat()
        val y = (location[1] - origin[1]).toFloat()
        val save = canvas.save()
        canvas.clipRect(x, y, x + scroll.width, y + scroll.height)
        if (scroll.clipToPadding) {
            canvas.clipRect(
                x + scroll.paddingLeft,
                y + scroll.paddingTop,
                x + scroll.width - scroll.paddingRight,
                y + scroll.height - scroll.paddingBottom
            )
        }
        canvas.translate(x + content.left - scroll.scrollX, y + content.top - scroll.scrollY)
        canvas.concat(content.matrix)
        canvas.translate(-content.scrollX.toFloat(), -content.scrollY.toFloat())
        content.draw(canvas)
        canvas.restoreToCount(save)
    }
}
