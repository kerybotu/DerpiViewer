package com.kerybotu.derpibooru.mirror

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Button
import android.widget.CompoundButton
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton

object PaletteManager {
    data class Colors(val surface: Int, val primary: Int, val onSurface: Int)

    fun colors(context: android.content.Context): Colors = when (AppSettings.getPalette(context)) {
        AppSettings.Palette.DARK -> Colors(Color.rgb(26, 29, 41), Color.rgb(103, 80, 164), Color.WHITE)
        AppSettings.Palette.LIGHT -> Colors(Color.WHITE, Color.rgb(63, 81, 181), Color.rgb(30, 30, 35))
        AppSettings.Palette.COLORFUL -> Colors(Color.rgb(255, 252, 242), Color.rgb(0, 137, 123), Color.rgb(28, 35, 34))
    }

    fun apply(activity: Activity) {
        val palette = AppSettings.getPalette(activity)
        AppCompatDelegate.setDefaultNightMode(
            when (palette) {
                AppSettings.Palette.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                AppSettings.Palette.LIGHT, AppSettings.Palette.COLORFUL -> AppCompatDelegate.MODE_NIGHT_NO
            }
        )
        val c = colors(activity)
        activity.window.statusBarColor = c.primary
        activity.window.navigationBarColor = c.surface
        styleTree(activity.findViewById(android.R.id.content), c)
    }

    private fun styleTree(view: View, c: Colors) {
        if (view.id == android.R.id.content) view.setBackgroundColor(c.surface)
        when (view) {
            is Toolbar -> view.setBackgroundColor(c.primary)
            is BottomNavigationView -> view.setBackgroundColor(c.surface)
            is FloatingActionButton -> view.backgroundTintList = android.content.res.ColorStateList.valueOf(c.primary)
            is Button -> view.backgroundTintList = android.content.res.ColorStateList.valueOf(c.primary)
            is CompoundButton -> view.buttonTintList = android.content.res.ColorStateList.valueOf(c.primary)
            is TextView -> if (view.id == android.R.id.content) view.setTextColor(c.onSurface)
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) styleTree(view.getChildAt(i), c)
    }
}
