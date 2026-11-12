package com.kerybotu.derpibooru.mirror.ui

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import com.kerybotu.derpibooru.mirror.R

/** Measured navigation occlusion, independent of the full-window page canvas. */
object AdaptiveNavigationContent {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val centerX: Int get() = (left + right) / 2
    }
    data class HorizontalInsets(val left: Int, val right: Int)

    fun contentBounds(window: Bounds, navigation: Bounds?, gap: Int): Bounds {
        if (navigation != null && navigation.width > 0) {
            if (navigation.centerX < window.centerX) {
                return window.copy(left = (navigation.right + gap).coerceIn(window.left, window.right))
            } else {
                return window.copy(right = (navigation.left - gap).coerceIn(window.left, window.right))
            }
        }
        return window
    }

    fun publish(host: View, navigation: View?, gap: Int) {
        if (host.width <= 0) return
        val origin = IntArray(2)
        host.getLocationOnScreen(origin)
        val window = Bounds(origin[0], origin[1], origin[0] + host.width, origin[1] + host.height)
        val navBounds = if (navigation != null && navigation.isShown && navigation.width > 0) {
            val location = IntArray(2)
            navigation.getLocationOnScreen(location)
            Bounds(location[0], location[1], location[0] + navigation.width, location[1] + navigation.height)
        } else null
        val safe = contentBounds(window, navBounds, gap)
        val safeRect = Rect(safe.left, safe.top, safe.right, safe.bottom)
        if (host.getTag(R.id.adaptive_navigation_bounds) == safeRect) return
        host.setTag(R.id.adaptive_navigation_bounds, safeRect)
        notifyObservers(host)
    }

    /** Convert once into each consumer's coordinates; system and rail insets overlap, never add. */
    fun horizontalInsets(view: View, systemLeft: Int = 0, systemRight: Int = 0): HorizontalInsets {
        var ancestor: View? = view
        while (ancestor != null) {
            val safe = ancestor.getTag(R.id.adaptive_navigation_bounds) as? Rect
            if (safe != null && view.width > 0) {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                return HorizontalInsets(
                    maxOf(systemLeft, safe.left - location[0]).coerceIn(0, view.width),
                    maxOf(systemRight, location[0] + view.width - safe.right).coerceIn(0, view.width)
                )
            }
            ancestor = ancestor.parent as? View
        }
        return HorizontalInsets(systemLeft, systemRight)
    }

    fun observe(view: View, changed: () -> Unit) {
        view.setTag(R.id.adaptive_navigation_observer, changed)
        view.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (l != ol || t != ot || r != or || b != ob) view.post { changed() }
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { v.post { changed() } }
            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }

    fun hasObserver(view: View): Boolean = view.getTag(R.id.adaptive_navigation_observer) != null

    fun applyFallbackInsets(view: View, insets: HorizontalInsets) {
        if (hasObserver(view)) return
        val base = (view.getTag(R.id.adaptive_navigation_base_padding) as? IntArray)
            ?: intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom).also {
                view.setTag(R.id.adaptive_navigation_base_padding, it)
            }
        val left = maxOf(base[0], insets.left)
        val right = maxOf(base[2], insets.right)
        if (view.paddingLeft != left || view.paddingRight != right) {
            view.setPadding(left, view.paddingTop, right, view.paddingBottom)
        }
    }

    private fun notifyObservers(view: View) {
        @Suppress("UNCHECKED_CAST")
        (view.getTag(R.id.adaptive_navigation_observer) as? (() -> Unit))?.invoke()
        if (view is ViewGroup) for (i in 0 until view.childCount) notifyObservers(view.getChildAt(i))
    }
}
