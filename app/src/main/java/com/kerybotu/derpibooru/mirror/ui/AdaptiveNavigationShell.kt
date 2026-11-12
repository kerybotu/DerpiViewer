package com.kerybotu.derpibooru.mirror.ui

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.liquidglass.LiquidGlassTabBar
import com.kerybotu.derpibooru.mirror.MainActivity
import com.kerybotu.derpibooru.mirror.R

/** Adds the shared floating navigation rail to top-level screens in wide windows. */
class AdaptiveNavigationShell private constructor(
    private val activity: Activity,
    private val content: View,
    private val selectedIndex: Int,
    private val contentMode: ContentMode
) {
    enum class ContentMode { SAFE_AREA, MEDIA_CANVAS }
    private val root = FrameLayout(activity).apply {
        clipChildren = false
        clipToPadding = false
    }
    private val railHost = FrameLayout(activity).apply {
        clipChildren = false
        clipToPadding = false
    }
    private val railScroll = ScrollView(activity).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = true
    }
    private val rail = HomeGlassTabBar(activity).apply {
        configureTabs(primaryNavigationItems(activity))
        setVerticalNavigation(true)
        selectItem(selectedIndex)
        onItemSelected = { navigate(it) }
        onItemReselected = { if (selectedIndex == NO_SELECTION) navigate(it) }
        elevation = dp(8).toFloat()
    }
    private var insetLeft = 0
    private var insetRight = 0
    private var insetTop = 0
    private var insetBottom = 0


    init {
        val parent = content.parent as? ViewGroup
        parent?.removeView(content)
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        root.addView(railHost, FrameLayout.LayoutParams(-1, -1))
        railScroll.addView(rail, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        railHost.addView(railScroll)
        activity.setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            insetLeft = bars.left
            insetRight = bars.right
            insetTop = bars.top
            insetBottom = bars.bottom
            updateLayout()
            insets
        }
        railScroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> publishBounds() }
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
        val hostParams = railHost.layoutParams as FrameLayout.LayoutParams
        if (hostParams.width != ViewGroup.LayoutParams.MATCH_PARENT || hostParams.height != ViewGroup.LayoutParams.MATCH_PARENT) {
            hostParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            hostParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            railHost.layoutParams = hostParams
        }
        val railParams = railScroll.layoutParams as FrameLayout.LayoutParams
        val railWidth = if (showRail) dp(spec.navigationWidthDp) else 0
        val railHeight = (root.height - insetTop - insetBottom - railGutter * 2).coerceAtLeast(0)
        val railStart = if (ViewCompat.getLayoutDirection(root) == ViewCompat.LAYOUT_DIRECTION_RTL) insetRight + railGutter
            else insetLeft + railGutter
        if (railParams.width != railWidth || railParams.height != railHeight ||
            railParams.marginStart != railStart || railParams.topMargin != insetTop + railGutter ||
            railParams.gravity != (Gravity.TOP or Gravity.START)) {
            railParams.width = railWidth
            railParams.height = railHeight
            railParams.marginStart = railStart
            railParams.topMargin = insetTop + railGutter
            railParams.bottomMargin = insetBottom + railGutter
            railParams.gravity = Gravity.TOP or Gravity.START
            railScroll.layoutParams = railParams
        }
        publishBounds()
    }

    private fun publishBounds() {
        AdaptiveNavigationContent.publish(root, railScroll.takeIf { railHost.isShown }, dp(Ui2DesignSystem.Spacing.islandGap))
        if (contentMode == ContentMode.SAFE_AREA) {
            AdaptiveNavigationContent.applyFallbackInsets(
                content,
                AdaptiveNavigationContent.horizontalInsets(content, insetLeft, insetRight)
            )
        }
    }

    private fun navigate(index: Int) {
        if (index == selectedIndex) return
        val destination = when (index) {
            HOME -> R.id.tab_home
            VIDEO -> R.id.tab_video_feed
            FEATURED -> R.id.tab_featured
            COMMENTS -> R.id.tab_messages
            PROFILE -> R.id.tab_profile
            else -> return
        }
        val intent = Intent(activity, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(MainActivity.EXTRA_ADAPTIVE_DESTINATION, destination)
        activity.startActivity(intent)
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        const val HOME = 0
        const val VIDEO = 1
        const val FEATURED = 2
        const val COMMENTS = 3
        const val PROFILE = 4
        // These host markers are not destinations in the original five tabs.
        const val NO_SELECTION = -1
        const val SEARCH = NO_SELECTION
        const val FAVORITES = NO_SELECTION

        fun primaryNavigationItems(activity: Activity) = listOf(
            LiquidGlassTabBar.TabItem("首页", activity.getDrawable(R.drawable.ic_home)),
            LiquidGlassTabBar.TabItem("视频", activity.getDrawable(R.drawable.ic_video)),
            LiquidGlassTabBar.TabItem("热门", activity.getDrawable(R.drawable.ic_whatshot)),
            LiquidGlassTabBar.TabItem("评论", activity.getDrawable(R.drawable.ic_comment)),
            LiquidGlassTabBar.TabItem("我的", activity.getDrawable(R.drawable.ic_account_box))
        )

        fun primaryDestination(index: Int): Int = when (index) {
            HOME -> R.id.tab_home
            VIDEO -> R.id.tab_video_feed
            FEATURED -> R.id.tab_featured
            COMMENTS -> R.id.tab_messages
            PROFILE -> R.id.tab_profile
            else -> View.NO_ID
        }

        fun install(
            activity: Activity,
            content: View,
            selectedIndex: Int,
            contentMode: ContentMode = ContentMode.SAFE_AREA
        ): AdaptiveNavigationShell = AdaptiveNavigationShell(activity, content, selectedIndex, contentMode)
    }
}
