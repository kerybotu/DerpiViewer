package com.kerybotu.derpibooru.mirror.ui

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.liquidglass.LiquidGlassTabBar
import com.kerybotu.derpibooru.mirror.MainActivity
import com.kerybotu.derpibooru.mirror.R

/** Adds the shared floating navigation rail to top-level screens in wide windows. */
class AdaptiveNavigationShell private constructor(
    private val activity: Activity,
    private val content: View,
    private val selectedIndex: Int
) {
    private val root = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
    }
    private val railHost = FrameLayout(activity).apply {
        clipChildren = false
        clipToPadding = false
    }
    private val rail = HomeGlassTabBar(activity).apply {
        configureTabs(navigationItems())
        setVerticalNavigation(true)
        selectItem(selectedIndex)
        onItemSelected = { navigate(it) }
        elevation = dp(8).toFloat()
    }
    private var insetLeft = 0
    private var insetRight = 0


    init {
        val parent = content.parent as? ViewGroup
        parent?.removeView(content)
        root.addView(railHost, LinearLayout.LayoutParams(0, -1))
        root.addView(content, LinearLayout.LayoutParams(0, -1, 1f))
        railHost.addView(rail, FrameLayout.LayoutParams(dp(AdaptiveLayoutPolicy.COMPACT_NAVIGATION_RAIL_DP), -2, Gravity.CENTER))
        activity.setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            insetLeft = bars.left
            insetRight = bars.right
            root.setPadding(insetLeft, 0, insetRight, 0)
            updateLayout()
            insets
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateLayout() }
        ViewCompat.requestApplyInsets(root)
        root.post { updateLayout() }
    }

    fun onWindowSizeChanged() = updateLayout()

    fun onConfigurationChanged(configuration: Configuration) {
        root.post { updateLayout() }
    }

    private fun updateLayout() {
        if (activity.isFinishing || activity.isDestroyed || root.width <= 0 || root.height <= 0) return
        rail.selectItem(selectedIndex)
        val density = activity.resources.displayMetrics.density
        val widthDp = ((root.width - insetLeft - insetRight) / density).toInt().coerceAtLeast(1)
        val heightDp = (root.height / density).toInt()
        val spec = AdaptiveLayoutPolicy.specFor(widthDp, heightDp)
        val showRail = spec.navigationMode == AdaptiveLayoutPolicy.NavigationMode.RAIL
        val railGutter = dp(AdaptiveLayoutPolicy.tokensForWidth(widthDp).screenGutterDp)
        railHost.visibility = if (showRail) View.VISIBLE else View.GONE
        rail.setRenderingActive(showRail && rail.isAttachedToWindow, content)
        val hostParams = railHost.layoutParams as LinearLayout.LayoutParams
        val hostWidth = if (showRail) dp(spec.navigationWidthDp) + railGutter * 2 else 0
        if (hostParams.width != hostWidth) {
            hostParams.width = hostWidth
            railHost.layoutParams = hostParams
        }
        val contentParams = content.layoutParams as LinearLayout.LayoutParams
        if (contentParams.width != 0 || contentParams.weight != 1f) {
            contentParams.width = 0
            contentParams.weight = 1f
            content.layoutParams = contentParams
        }
        val barWidth = if (showRail) dp(spec.navigationWidthDp) else 0
        val barParams = rail.layoutParams as FrameLayout.LayoutParams
        if (barParams.width != barWidth) {
            barParams.width = barWidth
            rail.layoutParams = barParams
        }
    }

    private fun navigate(index: Int) {
        if (index == selectedIndex) return
        val target = when (index) {
            HOME -> MainActivity::class.java
            SEARCH -> SearchActivity::class.java
            VIDEO -> MainActivity::class.java
            FAVORITES -> FavoritesActivity::class.java
            else -> MainActivity::class.java
        }
        val intent = Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        when (index) {
            HOME -> intent.putExtra(MainActivity.EXTRA_ADAPTIVE_DESTINATION, R.id.tab_home)
            VIDEO -> intent.putExtra(MainActivity.EXTRA_ADAPTIVE_DESTINATION, R.id.tab_video_feed)
            PROFILE -> intent.putExtra(MainActivity.EXTRA_ADAPTIVE_DESTINATION, R.id.tab_profile)
        }
        activity.startActivity(intent)
    }

    private fun navigationItems() = listOf(
        LiquidGlassTabBar.TabItem("首页", activity.getDrawable(R.drawable.ic_home)),
        LiquidGlassTabBar.TabItem("搜索", activity.getDrawable(R.drawable.ic_search)),
        LiquidGlassTabBar.TabItem("视频", activity.getDrawable(R.drawable.ic_video)),
        LiquidGlassTabBar.TabItem("收藏", activity.getDrawable(R.drawable.ic_favorite)),
        LiquidGlassTabBar.TabItem("个人", activity.getDrawable(R.drawable.ic_account_box))
    )

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        const val HOME = 0
        const val SEARCH = 1
        const val VIDEO = 2
        const val FAVORITES = 3
        const val PROFILE = 4

        fun install(activity: Activity, content: View, selectedIndex: Int): AdaptiveNavigationShell =
            AdaptiveNavigationShell(activity, content, selectedIndex)
    }
}
