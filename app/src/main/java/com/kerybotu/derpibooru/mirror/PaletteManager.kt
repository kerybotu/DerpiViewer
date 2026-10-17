package com.kerybotu.derpibooru.mirror

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Button
import android.widget.CompoundButton
import android.graphics.drawable.GradientDrawable
import com.kerybotu.derpibooru.mirror.ui.Ui2DesignSystem
import androidx.appcompat.widget.Toolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import com.google.android.material.chip.Chip
import com.example.liquidglass.LiquidGlassView
import com.kerybotu.derpibooru.mirror.ui.EmbeddedProfileView
import com.kerybotu.derpibooru.mirror.ui.EmbeddedVideoView
import com.kerybotu.derpibooru.mirror.ui.FeaturedPanel
import com.kerybotu.derpibooru.mirror.ui.GlassCommentCard
import com.kerybotu.derpibooru.mirror.ui.GlassFeedLayout
import com.kerybotu.derpibooru.mirror.ui.GlassWidgetStyle
import com.kerybotu.derpibooru.mirror.ui.HomeGlassTabBar
import com.kerybotu.derpibooru.mirror.ui.IslandGlassView
import com.kerybotu.derpibooru.mirror.ui.SafeToolbar
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

object PaletteManager {
    private val activities = java.util.WeakHashMap<Activity, PaletteDefinitions.Scheme>()
    private val observers = CopyOnWriteArrayList<WeakReference<(PaletteDefinitions.Scheme) -> Unit>>()

    fun observe(owner: android.content.Context, callback: (PaletteDefinitions.Scheme) -> Unit): () -> Unit {
        val ref = WeakReference(callback)
        observers += ref
        callback(colors(owner))
        return { observers.remove(ref) }
    }

    fun notifyChanged(context: android.content.Context) {
        val current = colors(context)
        activities.keys.toList().filterNot { it.isDestroyed || it.isFinishing }.forEach(::apply)
        observers.removeAll { ref ->
            val callback = ref.get()
            if (callback == null) true else { callback(current); false }
        }
    }

    fun colors(context: android.content.Context): PaletteDefinitions.Scheme =
        PaletteDefinitions.forPalette(context, AppSettings.getPalette(context))

    /** Dialog windows are outside the Activity content tree. Refresh them
     * only while attached, with the callback retained by their own decor. */
    fun bindDialogTheme(dialog: android.app.Dialog) {
        val decor = dialog.window?.decorView ?: return
        var previous = colors(dialog.context)
        var removeObserver: (() -> Unit)? = null
        val callback: (PaletteDefinitions.Scheme) -> Unit = { current ->
            if (previous != current) refreshSurfaces(decor, previous, current)
            styleTree(decor, current)
            applyGlassTree(decor, current)
            previous = current
        }
        decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                removeObserver?.invoke()
                removeObserver = observe(dialog.context, callback)
            }
            override fun onViewDetachedFromWindow(v: View) {
                removeObserver?.invoke()
                removeObserver = null
            }
        })
        if (decor.isAttachedToWindow) removeObserver = observe(dialog.context, callback)
    }

    fun apply(activity: Activity) {
        val c = colors(activity)
        activity.window.statusBarColor = c.primary
        activity.window.navigationBarColor = c.surface
        val root = activity.findViewById<View>(android.R.id.content)
        activities.put(activity, c)?.takeIf { it != c }?.let { refreshSurfaces(root, it, c) }
        styleTree(root, c)
        applyGlassTree(root, c)
    }

    /** Refresh semantic page backgrounds without replacing artwork or transparent hosts. */
    private fun refreshSurfaces(view: View, previous: PaletteDefinitions.Scheme, current: PaletteDefinitions.Scheme) {
        // Refresh existing semantic icon tints, leaving untinted artwork and
        // stateful image content untouched (avatars/media must not be tinted).
        if (view is android.widget.ImageView) {
            view.imageTintList?.takeUnless { it.isStateful }?.let { tint ->
                val replacement = when (tint.defaultColor) {
                    previous.glassText -> current.glassText
                    previous.glassSecondaryText -> current.glassSecondaryText
                    previous.primary -> current.primary
                    previous.onPrimary -> current.onPrimary
                    else -> null
                }
                replacement?.let { view.imageTintList = android.content.res.ColorStateList.valueOf(it) }
            }
        }
        (view.background as? GradientDrawable)?.let { drawable ->
            when (drawable.color?.defaultColor) {
                previous.glassSurface -> drawable.setColor(current.glassSurface)
                previous.glassSurfaceElevated -> drawable.setColor(current.glassSurfaceElevated)
            }
        }
        val background = view.background as? android.graphics.drawable.ColorDrawable
        background?.color?.let { color ->
            when (color) {
                previous.surface -> view.setBackgroundColor(current.surface)
                previous.surfaceVariant -> view.setBackgroundColor(current.surfaceVariant)
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) refreshSurfaces(view.getChildAt(i), previous, current)
    }

    private fun applyGlassTree(view: View, c: PaletteDefinitions.Scheme) {
        when (view) {
            is GlassFeedLayout -> view.applyPalette(c)
            is EmbeddedProfileView -> view.applyPalette(c)
            is EmbeddedVideoView -> view.refreshPalette()
            is FeaturedPanel -> view.refreshPalette(c)
            is GlassCommentCard -> view.applyPalette(c)
            is HomeGlassTabBar -> view.applyPalette(c)
            is IslandGlassView -> view.setPalette(c)
            is LiquidGlassView -> GlassWidgetStyle.applyMaterial(view, c)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) applyGlassTree(view.getChildAt(index), c)
        }
    }

    private fun styleTree(view: View, c: PaletteDefinitions.Scheme) {
        if (view.id == android.R.id.content) view.setBackgroundColor(c.surface)
        when (view) {
            is SafeToolbar -> view.applyUi2Appearance(c)
            is Toolbar -> {
                val transparent = (view.background as? android.graphics.drawable.ColorDrawable)?.color == android.graphics.Color.TRANSPARENT
                if (!transparent) view.setBackgroundColor(c.primary)
                view.setTitleTextColor(if (transparent) c.glassText else c.onPrimary)
            }
            is BottomNavigationView -> view.setBackgroundColor(c.surface)
            is NavigationView -> {
                view.setBackgroundColor(c.surface)
                view.itemTextColor = android.content.res.ColorStateList.valueOf(c.onSurface)
                view.itemIconTintList = android.content.res.ColorStateList.valueOf(c.onSurface)
            }
            is FloatingActionButton -> {
                view.backgroundTintList = android.content.res.ColorStateList.valueOf(withAlpha(c.primary, 0.86f))
                Ui2DesignSystem.applyPressFeedback(view)
            }
            is CompoundButton -> {
                // Radio buttons and switches are selection controls. Do not
                // replace their native backgrounds with the command-button
                // surface used only by android.widget.Button above.
                view.buttonTintList = android.content.res.ColorStateList.valueOf(c.primary)
                view.setTextColor(c.onSurface)
            }
            is Button -> {
                styleButton(view, c)
            }
            is Chip -> {
                view.setTextColor(c.onSurface)
                view.chipBackgroundColor = android.content.res.ColorStateList.valueOf(c.surfaceVariant)
                view.rippleColor = android.content.res.ColorStateList.valueOf(c.primary)
            }
            is TextView -> {
                view.setTextColor(c.onSurface)
                // XML compound icons (including the detail interaction row)
                // otherwise retain their drawable's fixed initialization tint.
                if (view.compoundDrawablesRelative.any { it != null }) {
                    androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(
                        view, android.content.res.ColorStateList.valueOf(c.onSurface))
                }
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) styleTree(view.getChildAt(i), c)
        if (view is Toolbar && view !is SafeToolbar && (view.background as? android.graphics.drawable.ColorDrawable)?.color != android.graphics.Color.TRANSPARENT) {
            for (i in 0 until view.childCount) {
                (view.getChildAt(i) as? TextView)?.setTextColor(c.onPrimary)
            }
        }
    }

    /** Applies the shared 2.0 button surface to both XML and programmatic buttons. */
    fun styleButton(view: View, c: PaletteDefinitions.Scheme = colors(view.context)) {
        val density = view.resources.displayMetrics.density
        val fill = withAlpha(c.primary, 0.86f)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
            setColor(fill)
            setStroke((density).toInt().coerceAtLeast(1), withAlpha(c.onPrimary, 0.28f))
        }
        view.backgroundTintList = null
        if (view is TextView) {
            // Command buttons are deliberately compact; navigation keeps its
            // independent 48dp touch targets and is not styled here.
            val compactHeight = (40f * density).toInt()
            val horizontalPadding = (12f * density).toInt()
            view.minimumHeight = compactHeight
            view.minHeight = compactHeight
            view.minWidth = 0
            view.setPaddingRelative(horizontalPadding, 0, horizontalPadding, 0)
            view.textSize = 14f
            view.setTextColor(c.onPrimary)
        }
        Ui2DesignSystem.applyPressFeedback(view)
    }

    private fun withAlpha(color: Int, fraction: Float): Int =
        android.graphics.Color.argb(
            (fraction.coerceIn(0f, 1f) * 255f).toInt(),
            android.graphics.Color.red(color),
            android.graphics.Color.green(color),
            android.graphics.Color.blue(color)
        )
}
