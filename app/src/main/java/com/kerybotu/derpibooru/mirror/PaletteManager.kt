package com.kerybotu.derpibooru.mirror

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Button
import android.widget.CompoundButton
import androidx.appcompat.widget.Toolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import com.google.android.material.chip.Chip

object PaletteManager {
    fun colors(context: android.content.Context): PaletteDefinitions.Scheme =
        PaletteDefinitions.forPalette(context, AppSettings.getPalette(context))

    fun apply(activity: Activity) {
        val c = colors(activity)
        activity.window.statusBarColor = c.primary
        activity.window.navigationBarColor = c.surface
        styleTree(activity.findViewById(android.R.id.content), c)
    }

    private fun styleTree(view: View, c: PaletteDefinitions.Scheme) {
        if (view.id == android.R.id.content) view.setBackgroundColor(c.surface)
        when (view) {
            is Toolbar -> { view.setBackgroundColor(c.primary); view.setTitleTextColor(c.onPrimary) }
            is BottomNavigationView -> view.setBackgroundColor(c.surface)
            is NavigationView -> {
                view.setBackgroundColor(c.surface)
                view.itemTextColor = android.content.res.ColorStateList.valueOf(c.onSurface)
                view.itemIconTintList = android.content.res.ColorStateList.valueOf(c.onSurface)
            }
            is FloatingActionButton -> view.backgroundTintList = android.content.res.ColorStateList.valueOf(c.primary)
            is Button -> {
                view.backgroundTintList = android.content.res.ColorStateList.valueOf(c.primary)
                view.setTextColor(c.onSurface)
            }
            is CompoundButton -> {
                view.buttonTintList = android.content.res.ColorStateList.valueOf(c.primary)
                view.setTextColor(c.onSurface)
            }
            is Chip -> {
                view.setTextColor(c.onSurface)
                view.chipBackgroundColor = android.content.res.ColorStateList.valueOf(c.surfaceVariant)
                view.rippleColor = android.content.res.ColorStateList.valueOf(c.primary)
            }
            is TextView -> view.setTextColor(c.onSurface)
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) styleTree(view.getChildAt(i), c)
        if (view is Toolbar) {
            for (i in 0 until view.childCount) {
                (view.getChildAt(i) as? TextView)?.setTextColor(c.onPrimary)
            }
        }
    }
}
