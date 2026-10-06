package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.liquidglass.LiquidGlassListItem
import com.example.liquidglass.LiquidGlassView
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.R

/** A shared glass header, scrolling surface, loading state and pull-to-refresh for feeds. */
open class GlassFeedLayout(context: Context, title: String, onBack: (() -> Unit)? = null) : FrameLayout(context) {
    val pageBackdrop = View(context)
    val refresh = IosPullRefreshLayout(context)
    val results = RecyclerView(context)
    private val attachedGlass = mutableMapOf<LiquidGlassView, Float>()
    private val visibleRect = Rect()
    private val headerGlass = IslandGlassView(context).apply {
        GlassWidgetStyle.apply(this, Ui2DesignSystem.Shape.topIsland)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    val toolbar = Toolbar(context).apply {
        this.title = title
        minimumHeight = 0
        setBackgroundColor(Color.TRANSPARENT)
        if (onBack != null) {
            setNavigationIcon(R.drawable.ic_arrow_back)
            navigationContentDescription = "返回"
            setNavigationOnClickListener { onBack() }
        }
    }
    private val header = FrameLayout(context).apply {
        elevation = dp(Ui2DesignSystem.Elevation.islandDp.toInt()).toFloat()
        outlineProvider = headerGlass.outlineProvider
        clipToOutline = true
        addView(headerGlass, LayoutParams(-1, -1))
        addView(toolbar, LayoutParams(-1, -1))
    }
    private val progress = IosActivityIndicator(context)
    private val loadingPanel = LiquidGlassView(context).apply {
        visibility = View.GONE
        addView(progress, LayoutParams(dp(32), dp(32), Gravity.CENTER))
    }
    private val statusPanel = LiquidGlassListItem(context).apply { visibility = View.GONE }
    var isActive = false
        private set
    var onBecameActive: (() -> Unit)? = null
    var onPaletteChanged: (() -> Unit)? = null
    private var centeredLoading = true
    private var systemTop = 0
    private var systemBottom = 0
    private var systemLeft = 0
    private var systemRight = 0
    private var navigationRight = 0
    private var navigationBottom = 0

    init {
        addView(pageBackdrop, LayoutParams(-1, -1))
        results.layoutManager = LinearLayoutManager(context)
        results.clipToPadding = false
        // Glass captures screen coordinates; keep whole-card transforms out of item animations.
        results.itemAnimator = null
        refresh.addView(results, LayoutParams(-1, -1))
        refresh.scrollTarget = results
        addView(refresh, LayoutParams(-1, -1))
        addView(header, LayoutParams(-1, dp(60), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        trackGlass(loadingPanel, refresh, 24f)
        trackGlass(statusPanel, pageBackdrop, 24f)
        addView(loadingPanel, LayoutParams(dp(80), dp(80), Gravity.CENTER))
        addView(statusPanel, LayoutParams(-1, -2, Gravity.CENTER))
        results.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) { updateGlassRendering() }
        })
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            systemTop = bars.top
            systemLeft = bars.left
            systemRight = bars.right
            systemBottom = bars.bottom
            updateContentInsets()
            insets
        }
        applyPalette()
    }

    fun setActive(active: Boolean) {
        val newlyActive = active && !isActive
        isActive = active
        if (active) applyPalette() else updateGlassRendering()
        if (newlyActive) onBecameActive?.invoke()
    }

    fun setNavigationInsets(right: Int, bottom: Int) {
        navigationRight = right.coerceAtLeast(0)
        navigationBottom = bottom.coerceAtLeast(0)
        updateContentInsets()
    }

    fun showLoading(visible: Boolean, centered: Boolean = true) {
        centeredLoading = centered
        loadingPanel.visibility = if (visible) View.VISIBLE else View.GONE
        updateContentInsets()
        updateGlassRendering()
    }

    fun showStatus(title: String, detail: String, retry: (() -> Unit)? = null) {
        statusPanel.headline = title
        statusPanel.supportingText = detail
        statusPanel.setOnClickListener(if (retry == null) null else OnClickListener { retry() })
        statusPanel.visibility = View.VISIBLE
        updateGlassRendering()
    }

    fun hideStatus() {
        statusPanel.visibility = View.GONE
        updateGlassRendering()
    }

    private fun updateContentInsets() {
        val right = maxOf(systemRight, navigationRight)
        val safeWidth = ((width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - systemLeft - right).coerceAtLeast(1)
        val available = (safeWidth - dp(32)).coerceAtLeast(1)
        val top = systemTop + dp(Ui2DesignSystem.Spacing.sm)
        header.layoutParams = (header.layoutParams as LayoutParams).apply {
            width = AdaptiveLayoutPolicy.topIslandWidthPx(context, available)
            topMargin = top
            leftMargin = systemLeft + dp(16)
            rightMargin = right + dp(16)
        }
        val contentWidth = AdaptiveLayoutPolicy.contentIslandWidthPx(context, available)
        val gutter = (safeWidth - contentWidth) / 2
        val contentTop = top + dp(60 + Ui2DesignSystem.Spacing.islandGap)
        val bottom = maxOf(systemBottom, navigationBottom) + dp(16)
        results.setPadding(systemLeft + gutter, contentTop, right + gutter, bottom)
        refresh.contentTopInset = contentTop
        loadingPanel.layoutParams = (loadingPanel.layoutParams as LayoutParams).apply {
            gravity = if (centeredLoading) Gravity.CENTER else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            topMargin = if (centeredLoading) contentTop else 0
            bottomMargin = bottom
        }
        statusPanel.layoutParams = (statusPanel.layoutParams as LayoutParams).apply {
            width = contentWidth
            leftMargin = systemLeft + dp(16)
            rightMargin = right + dp(16)
            topMargin = contentTop
            bottomMargin = bottom
        }
    }

    fun <T : LiquidGlassView> trackGlass(view: T, source: View = pageBackdrop, radius: Float = 16f): T {
        GlassWidgetStyle.apply(view, radius)
        view.backdropSource = source
        view.enableDynamicBackground = false
        view.enableSensorHighlight = false
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                attachedGlass[view] = radius
                GlassWidgetStyle.apply(view, radius)
                view.post { updateGlassRendering() }
            }
            override fun onViewDetachedFromWindow(v: View) {
                attachedGlass.remove(view)
                view.enableDynamicBackground = false
                view.enableSensorHighlight = false
            }
        })
        return view
    }

    fun updateGlassRendering(forceInactive: Boolean = false) {
        val visible = !forceInactive && isActive && isAttachedToWindow && isShown
        headerGlass.setRenderingActive(visible, refresh)
        attachedGlass.keys.toList().forEach { glass ->
            val render = visible && glass.isShown && glass.getGlobalVisibleRect(visibleRect)
            glass.enableDynamicBackground = render
            glass.enableSensorHighlight = render
        }
    }

    private fun applyPalette() {
        val colors = PaletteManager.colors(context)
        setBackgroundColor(colors.surface)
        pageBackdrop.setBackgroundColor(colors.surface)
        refresh.setBackgroundColor(colors.surface)
        headerGlass.setPalette(colors)
        toolbar.setBackgroundColor(Color.TRANSPARENT)
        toolbar.setTitleTextColor(GlassWidgetStyle.TEXT_COLOR)
        val iconColor = GlassWidgetStyle.foreground(headerGlass)
        toolbar.navigationIcon?.mutate()?.setTint(iconColor)
        toolbar.overflowIcon?.mutate()?.setTint(iconColor)
        for (index in 0 until toolbar.menu.size()) toolbar.menu.getItem(index).icon?.mutate()?.setTint(iconColor)
        progress.applyPalette(colors)
        refresh.applyPalette(colors)
        attachedGlass.toMap().forEach { (view, radius) -> GlassWidgetStyle.apply(view, radius) }
        onPaletteChanged?.invoke()
        updateGlassRendering()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateContentInsets()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
        post { updateGlassRendering() }
    }

    override fun onDetachedFromWindow() {
        updateGlassRendering(forceInactive = true)
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
