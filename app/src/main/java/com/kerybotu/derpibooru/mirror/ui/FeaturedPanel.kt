package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.widget.NestedScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.R
import com.kerybotu.derpibooru.mirror.model.Image
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

class FeaturedPanel(context: Context) : FrameLayout(context) {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val items = mutableListOf<Image>()
    private lateinit var adapter: ImageAdapter
    private lateinit var heroBox: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var refreshLayout: PullRefreshLayout
    private lateinit var topIsland: FrameLayout
    private lateinit var topGlass: IslandGlassView
    private lateinit var topToolbar: Toolbar
    private var systemTopInset = 0
    private var systemLeftInset = 0
    private var systemRightInset = 0
    private var page = 1
    private var loading = false
    private var hasLoadedOnce = false
    private var lastDetailsClickAt = 0L
    var onRefreshFinished: (() -> Unit)? = null
    var onSelectionChanged: ((Int) -> Unit)? = null

    init {
        refreshLayout = PullRefreshLayout(context)
        val scroll = NestedScrollView(context)
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(20)) }
        heroBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(heroBox, LinearLayout.LayoutParams(-1, dp(280)))
        content.addView(View(context).apply {
            setBackgroundColor(PaletteManager.colors(context).divider)
        }, LinearLayout.LayoutParams(-1, dp(1)))
        content.addView(TextView(context).apply {
            text = "近期精选"
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), dp(16), dp(4), dp(16))
        })
        content.addView(View(context).apply {
            setBackgroundColor(PaletteManager.colors(context).divider)
        }, LinearLayout.LayoutParams(-1, dp(1)))
        val grid = RecyclerView(context).apply {
            isNestedScrollingEnabled = false
            layoutManager = GridLayoutManager(context, AdaptiveLayoutPolicy.artworkColumnCount(context))
        }
        adapter = ImageAdapter(emptyList(), { openDetails(it) }, { count -> onSelectionChanged?.invoke(count) }, context)
        grid.adapter = adapter
        content.addView(grid, LinearLayout.LayoutParams(-1, -2))
        scroll.addView(content)
        refreshLayout.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        refreshLayout.setOnRefreshListener { refresh() }
        addView(refreshLayout, LayoutParams(-1, -1))
        addSharedMenuIsland()
        progress = ProgressBar(context).apply { isIndeterminate = true }
        addView(progress, LayoutParams(dp(48), dp(48), android.view.Gravity.CENTER))
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            if (!loading && y + scroll.height >= content.height - dp(500)) loadPage()
        }
        // The panel is constructed while the app's startup networking task is
        // still running. Loading here could fail before the client exists and
        // incorrectly mark the first visit as complete. The visible tab calls
        // ensureLoaded() after it becomes the active primary destination.
    }

    /** Uses the same GlassMenuCard entry point as Home, Video, Messages, and Profile. */
    private fun addSharedMenuIsland() {
        topGlass = IslandGlassView(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            GlassWidgetStyle.apply(this, Ui2DesignSystem.Shape.topIsland)
        }
        topToolbar = Toolbar(context).apply {
            title = "热门"
            minimumHeight = 0
            setBackgroundColor(Color.TRANSPARENT)
            setNavigationIcon(R.drawable.ic_menu)
            navigationContentDescription = "打开菜单"
            setNavigationOnClickListener {
                (context as? com.kerybotu.derpibooru.mirror.MainActivity)?.showUnifiedGlassMenu()
            }
            setTitleTextColor(PaletteManager.colors(context).glassText)
            navigationIcon?.mutate()?.setTint(PaletteManager.colors(context).glassText)
        }
        topIsland = FrameLayout(context).apply {
            elevation = dp(Ui2DesignSystem.Elevation.topIslandDp.toInt()).toFloat()
            addView(topGlass, LayoutParams(-1, -1))
            addView(topToolbar, LayoutParams(-1, -1))
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val radius = topGlass.cornerRadius.coerceAtMost(minOf(view.width, view.height) / 2f)
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            clipToOutline = true
        }
        addView(topIsland, LayoutParams(-1, dp(60), Gravity.TOP or Gravity.LEFT))
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            systemTopInset = bars.top
            systemLeftInset = bars.left
            systemRightInset = bars.right
            updateTopIslandLayout()
            insets
        }
        ViewCompat.requestApplyInsets(this)
        post { updateTopIslandLayout(); updateTopIslandRendering() }
    }

    /** Positions the floating island from the actual window and inset sizes. */
    private fun updateTopIslandLayout() {
        if (!::topIsland.isInitialized) return
        val safeWidth = (width - systemLeftInset - systemRightInset).coerceAtLeast(1)
        val available = (safeWidth - dp(32)).coerceAtLeast(1)
        val params = topIsland.layoutParams as? FrameLayout.LayoutParams ?: return
        params.width = AdaptiveLayoutPolicy.topIslandWidthPx(context, available)
        params.height = dp(60)
        params.topMargin = systemTopInset + dp(Ui2DesignSystem.Spacing.sm)
        params.leftMargin = systemLeftInset + (safeWidth - params.width) / 2
        params.rightMargin = 0
        topIsland.layoutParams = params

        // The first item starts below the island, then naturally scrolls behind it.
        val scroll = refreshLayout.getChildAt(0) as? NestedScrollView ?: return
        val content = scroll.getChildAt(0) as? LinearLayout ?: return
        val contentTop = params.topMargin + params.height + dp(Ui2DesignSystem.Spacing.islandGap)
        content.setPadding(content.paddingLeft, contentTop, content.paddingRight, content.paddingBottom)
        scroll.clipToPadding = false
    }

    private fun updateTopIslandRendering() {
        if (!::topGlass.isInitialized) return
        // FeaturedPanel can be created before the activity applies its current
        // palette. Refresh the material when it becomes visible so the island
        // uses the same tint/blur configuration as the Home shell.
        topGlass.setPalette(PaletteManager.colors(context))
        topToolbar.setBackgroundColor(Color.TRANSPARENT)
        val colors = PaletteManager.colors(context)
        topToolbar.setTitleTextColor(colors.glassText)
        topToolbar.navigationIcon?.mutate()?.setTint(colors.glassText)
        val active = isShown && isAttachedToWindow
        topGlass.setRenderingActive(active, refreshLayout)
    }

    fun refreshPalette() {
        updateTopIslandRendering()
        refreshLayout.setBackgroundColor(PaletteManager.colors(context).surface)
        refreshDisplayMode()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateTopIslandLayout()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
        post { updateTopIslandRendering() }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView === this) post { updateTopIslandRendering() }
    }

    override fun onDetachedFromWindow() {
        if (::topGlass.isInitialized) topGlass.setRenderingActive(false, null)
        super.onDetachedFromWindow()
    }

    fun refresh() {
        // Avoid clearing visible content when a refresh gesture arrives while
        // the initial request is still running.
        if (loading) return
        page = 1
        hasLoadedOnce = false
        items.clear()
        adapter.updateData(emptyList())
        heroBox.visibility = View.GONE
        loadPage()
    }

    /** Loads the panel only when it has not produced its first result yet. */
    fun ensureLoaded() {
        if (!hasLoadedOnce && !loading) loadPage()
    }

    fun refreshDisplayMode() {
        adapter.refreshDisplayMode()
    }

    private fun loadPage() {
        if (loading) return
        loading = true
        progress.visibility = View.VISIBLE
        val firstPage = page == 1
        scope.launch {
            try {
                val responses = withContext(Dispatchers.IO) {
                    val hero = async { requestFeaturedHero() }
                    val list = async { request("first_seen_at.gt:3 days ago,-ai generated,-ai composition", "score", 50, page) }
                    awaitAll(hero, list)
                }
                if (firstPage) showHero(responses[0].firstOrNull())
                if (responses[1].isNotEmpty()) {
                    items.addAll(responses[1])
                    adapter.updateData(items.toList())
                    page++
                }
            } finally {
                loading = false
                // A failed precondition or an empty response must remain
                // retryable when the user first enters the Featured tab.
                hasLoadedOnce = items.isNotEmpty() || heroBox.childCount > 0
                progress.visibility = View.GONE
                refreshLayout.isRefreshing = false
                onRefreshFinished?.invoke()
            }
        }
    }

    private fun showHero(image: Image?) {
        if (image == null) return
        heroBox.visibility = View.VISIBLE
        heroBox.removeAllViews()
        val frame = FrameLayout(context)
        val imageView = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = "精选头图" }
        CdnImageGate.load(imageView, image.thumbnailUrl, AppSettings.getCdnThreads(context))
        frame.addView(imageView, FrameLayout.LayoutParams(-1, -1))
        val palette = PaletteManager.colors(context)
        frame.addView(TextView(context).apply {
            text = "精选 · 评分 ${image.score} · 点赞 ${image.upvotes}"
            textSize = 14f
            setTextColor(palette.onPrimary)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(palette.scrim)
            layoutParams = FrameLayout.LayoutParams(-1, -2).apply { gravity = android.view.Gravity.BOTTOM }
        })
        frame.setOnClickListener { openDetails(image) }
        heroBox.addView(frame, LinearLayout.LayoutParams(-1, -1))
    }

    /** The documented featured endpoint provides the current hero independently of search tags. */
    private suspend fun requestFeaturedHero(): List<Image> {
        val json = NetworkManager.getApi(context, "images/featured") ?: return emptyList()
        val root = JSONObject(json)
        val image = root.optJSONObject("image") ?: root.optJSONArray("images")?.optJSONObject(0) ?: return emptyList()
        return listOf(parseImage(image, highResolution = true))
    }

    private suspend fun request(query: String, sort: String, perPage: Int, requestedPage: Int): List<Image> {
        val q = URLEncoder.encode(query, "UTF-8")
        val filter = NetworkManager.currentFilterParam(context)
        val json = NetworkManager.getApi(context, "search/images?q=$q&sf=$sort&sd=desc&per_page=$perPage&page=$requestedPage$filter") ?: return emptyList()
        val array = JSONObject(json).optJSONArray("images") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { parseImage(it, requestedPage == 1 && perPage == 1) } }
    }

    private fun parseImage(o: JSONObject, highResolution: Boolean): Image {
        val reps = o.optJSONObject("representations")
        val thumb = if (highResolution) reps?.optString("large", null) ?: reps?.optString("medium", null) else reps?.optString("small", null) ?: reps?.optString("thumb", null)
        return Image(o.optInt("id"), "", thumb, o.optInt("width"), o.optInt("height"), o.optInt("score"), o.optInt("faves"), o.optInt("upvotes"), o.optInt("downvotes"), o.optInt("comment_count"), tags(o), reps?.optString("full", null), o.optString("uploader", null), o.optString("created_at", null), o.optString("description", null), o.optString("mime_type", null), o.optLong("uploader_id", -1L).takeIf { it > 0L }, o.optBoolean("spoilered", false))
    }

    private fun tags(o: JSONObject): List<String> {
        val a = o.optJSONArray("tags") ?: return emptyList()
        return List(a.length()) { a.optString(it) }
    }

    private fun openDetails(image: Image) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastDetailsClickAt < 500L) return
        lastDetailsClickAt = now
        context.startActivity(Intent(context, ImageDetailActivity::class.java).putExtra("image", image))
    }
    fun selectedImages(): List<Image> = adapter.selectedItems()
    fun clearSelection() = adapter.clearSelection()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    fun dispose() { scope.cancel() }
}
