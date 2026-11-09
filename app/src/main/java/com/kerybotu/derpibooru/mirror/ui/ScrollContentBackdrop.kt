package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import com.example.liquidglass.LiquidGlassView
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** A prepared source view that records ordinary scroll content without glass consumers. */
internal interface PreparedBackdropContentSource {
    fun drawPreparedBackdrop(canvas: Canvas, consumer: LiquidGlassView?): Boolean
}

internal class ScrollContentBackdrop(
    context: Context,
    private val contentRoot: ViewGroup
) : View(context), PreparedBackdropContentSource {
    private val excludedRoots = WeakHashMap<LiquidGlassView, WeakReference<View>>()
    private val sourceOrigin = IntArray(2)
    private val scrollOrigin = IntArray(2)
    private var overlayHost: ViewGroup? = null
    private var overlayLayoutListener: View.OnLayoutChangeListener? = null

    init {
        setWillNotDraw(true)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun excludeRoot(consumer: LiquidGlassView, root: View) {
        excludedRoots[consumer] = WeakReference(root)
    }

    fun attachToOverlay(host: ViewGroup) {
        if (overlayHost === host) return
        detachFromOverlay()
        overlayHost = host
        overlayLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            layout(0, 0, host.width, host.height)
        }.also(host::addOnLayoutChangeListener)
        host.overlay.add(this)
        layout(0, 0, host.width, host.height)
    }

    fun detachFromOverlay() {
        val host = overlayHost ?: return
        overlayLayoutListener?.let(host::removeOnLayoutChangeListener)
        overlayLayoutListener = null
        host.overlay.remove(this)
        overlayHost = null
    }

    override fun drawPreparedBackdrop(canvas: Canvas, consumer: LiquidGlassView?): Boolean {
        if (contentRoot.visibility != VISIBLE) return false

        val color = (background as? ColorDrawable)?.color
        if (color != null) canvas.drawColor(color)
        getLocationInWindow(sourceOrigin)
        contentRoot.getLocationInWindow(scrollOrigin)
        val x = (scrollOrigin[0] - sourceOrigin[0]).toFloat()
        val y = (scrollOrigin[1] - sourceOrigin[1]).toFloat()
        val save = canvas.save()
        canvas.clipRect(x, y, x + contentRoot.width, y + contentRoot.height)
        canvas.translate(x, y)
        val excluded = consumer?.let { excludedRoots[it]?.get() } ?: consumer
        drawContents(contentRoot, canvas, excluded)
        canvas.restoreToCount(save)
        return true
    }

    private fun drawContents(group: ViewGroup, canvas: Canvas, excluded: View?, drawGroupBackground: Boolean = true) {
        if (drawGroupBackground) drawBackground(group, canvas)
        val clip = canvas.save()
        if (group.clipChildren) canvas.clipRect(0, 0, group.width, group.height)
        if (group.clipToPadding) canvas.clipRect(group.paddingLeft, group.paddingTop,
            group.width - group.paddingRight, group.height - group.paddingBottom)
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (child.visibility != VISIBLE || child === excluded) continue
            val childSave = canvas.save()
            canvas.translate(child.left - group.scrollX.toFloat(), child.top - group.scrollY.toFloat())
            canvas.concat(child.matrix)
            if (child is LiquidGlassView) {
                if (child is ViewGroup) drawContents(child, canvas, excluded, drawGroupBackground = false)
            } else if (child is ViewGroup && containsGlass(child)) drawContents(child, canvas, excluded)
            else child.draw(canvas)
            canvas.restoreToCount(childSave)
        }
        canvas.restoreToCount(clip)
    }

    private fun containsGlass(view: View): Boolean {
        if (view is LiquidGlassView) return true
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) if (containsGlass(view.getChildAt(index))) return true
        }
        return false
    }

    private fun drawBackground(view: View, canvas: Canvas) {
        val drawable = view.background ?: return
        if (drawable is ColorDrawable) {
            canvas.drawColor(drawable.color)
            return
        }
        val oldBounds = Rect(drawable.bounds)
        drawable.setBounds(0, 0, view.width, view.height)
        drawable.draw(canvas)
        drawable.bounds = oldBounds
    }
}
