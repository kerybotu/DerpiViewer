package com.kerybotu.derpibooru.mirror.ui

import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.kerybotu.derpibooru.mirror.PaletteManager

class RecentCommentsActivity : AppCompatActivity() {
    private lateinit var feed: CommentsFeedView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        feed = CommentsFeedView(this, "全站评论") { finish() }
        setContentView(feed)
    }

    override fun onResume() {
        super.onResume()
        val colors = PaletteManager.colors(this)
        window.statusBarColor = colors.surface
        window.navigationBarColor = colors.surface
        WindowCompat.getInsetsController(window, feed).apply {
            val light = Color.luminance(colors.surface) > 0.5f
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        feed.setActive(true)
    }

    override fun onPause() {
        feed.setActive(false)
        super.onPause()
    }

    override fun onDestroy() {
        feed.dispose()
        super.onDestroy()
    }
}
