package com.kerybotu.derpibooru.mirror.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.liquidglass.LiquidGlassButton
import com.example.liquidglass.LiquidGlassListItem
import com.example.liquidglass.LiquidGlassView
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.R
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URLEncoder
import java.text.NumberFormat
import java.util.Locale

class TagSearchActivity : AppCompatActivity() {
    private data class Tag(val name: String, val images: Long, val description: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val tags = mutableListOf<Tag>()
    private val numberFormat = NumberFormat.getIntegerInstance(Locale.US)
    private val attachedGlass = mutableMapOf<LiquidGlassView, Float>()
    private val visibleRect = Rect()
    private val tagAdapter = TagAdapter()
    private lateinit var root: FrameLayout
    private lateinit var pageBackdrop: View
    private lateinit var resultsSurface: IosPullRefreshLayout
    private lateinit var results: RecyclerView
    private lateinit var controls: LinearLayout
    private lateinit var header: FrameLayout
    private lateinit var headerGlass: IslandGlassView
    private lateinit var toolbar: Toolbar
    private lateinit var searchRow: LinearLayout
    private lateinit var input: EditText
    private lateinit var loadingPanel: LiquidGlassView
    private lateinit var loadingIndicator: IosActivityIndicator
    private lateinit var statusPanel: LiquidGlassListItem
    private var loadJob: Job? = null
    private var page = 1
    private var query = "*"
    private var loading = false
    private var hasMore = true
    private var failed = false
    private var resumed = false
    private var summaryText = "正在加载标签…"
    private var leftInset = 0
    private var rightInset = 0
    private var bottomInset = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = buildView()
        setContentView(root)
        applyPalette()
        ViewCompat.requestApplyInsets(root)
        load(reset = true)
    }

    private fun buildView(): FrameLayout {
        val root = FrameLayout(this).apply { isFocusableInTouchMode = true }
        pageBackdrop = View(this)
        root.addView(pageBackdrop, FrameLayout.LayoutParams(-1, -1))
        resultsSurface = IosPullRefreshLayout(this)
        results = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@TagSearchActivity)
            adapter = tagAdapter
            clipToPadding = false
            // Whole-card translations cannot be represented by the glass backdrop capture.
            itemAnimator = null
        }
        resultsSurface.addView(results, FrameLayout.LayoutParams(-1, -1))
        resultsSurface.scrollTarget = results
        resultsSurface.setCanRefresh { !loading }
        resultsSurface.setOnRefreshListener { load(reset = true, fromPull = true) }
        root.addView(resultsSurface, FrameLayout.LayoutParams(-1, -1))

        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headerGlass = IslandGlassView(this).apply {
            GlassWidgetStyle.apply(this, Ui2DesignSystem.Shape.topIsland)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        toolbar = Toolbar(this).apply {
            title = "标签"
            minimumHeight = 0
            setBackgroundColor(Color.TRANSPARENT)
            setNavigationIcon(R.drawable.ic_arrow_back)
            navigationContentDescription = "返回"
            setNavigationOnClickListener { finish() }
        }
        header = FrameLayout(this).apply {
            elevation = dp(Ui2DesignSystem.Elevation.islandDp.toInt()).toFloat()
            // The toolbar and GPU surface share the home header's rounded boundary.
            outlineProvider = headerGlass.outlineProvider
            clipToOutline = true
            addView(headerGlass, FrameLayout.LayoutParams(-1, -1))
            addView(toolbar, FrameLayout.LayoutParams(-1, -1))
        }
        controls.addView(header, LinearLayout.LayoutParams(-1, dp(60)).apply { gravity = Gravity.CENTER_HORIZONTAL })

        input = EditText(this).apply {
            hint = "搜索标签（* 表示全部）"
            setSingleLine()
            setText("*")
            setSelectAllOnFocus(false)
            setPadding(dp(16), 0, dp(16), 0)
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) { load(reset = true); true } else false
            }
        }
        val inputSurface = trackGlass(LiquidGlassView(this), resultsSurface).apply {
            addView(input, FrameLayout.LayoutParams(-1, -1))
        }
        val searchButton = trackGlass(LiquidGlassButton(this), resultsSurface).apply {
            text = "搜索"
            setOnClickListener { load(reset = true) }
        }
        searchRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(inputSurface, LinearLayout.LayoutParams(0, dp(56), 1f))
            addView(searchButton, LinearLayout.LayoutParams(-2, dp(56)).apply { marginStart = dp(8) })
        }
        controls.addView(searchRow, LinearLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(Ui2DesignSystem.Spacing.sm)
        })
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        controls.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateContentInsets() }

        loadingIndicator = IosActivityIndicator(this)
        loadingPanel = trackGlass(LiquidGlassView(this), resultsSurface).apply {
            visibility = View.GONE
            addView(loadingIndicator, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        }
        root.addView(loadingPanel, FrameLayout.LayoutParams(dp(80), dp(80), Gravity.CENTER))
        statusPanel = trackGlass(LiquidGlassListItem(this), pageBackdrop).apply { visibility = View.GONE }
        root.addView(statusPanel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
            setMargins(dp(24), 0, dp(24), 0)
        })

        results.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateGlassRendering()
                val manager = recyclerView.layoutManager as LinearLayoutManager
                if (dy > 0 && !failed && manager.findLastVisibleItemPosition() >= tagAdapter.itemCount - 5) load(reset = false)
            }
        })
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            leftInset = bars.left
            rightInset = bars.right
            bottomInset = maxOf(bars.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            controls.setPadding(bars.left + dp(16), bars.top + dp(Ui2DesignSystem.Spacing.sm), bars.right + dp(16), 0)
            updateContentInsets()
            insets
        }
        root.requestFocus()
        return root
    }

    private fun updateContentInsets() {
        if (!::results.isInitialized) return
        val width = (results.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - leftInset - rightInset
        val available = (width - dp(32)).coerceAtLeast(1)
        val headerWidth = AdaptiveLayoutPolicy.topIslandWidthPx(this, available)
        val contentWidth = AdaptiveLayoutPolicy.contentIslandWidthPx(this, available)
        if (header.layoutParams.width != headerWidth) header.layoutParams = header.layoutParams.apply { this.width = headerWidth }
        if (searchRow.layoutParams.width != contentWidth) searchRow.layoutParams = searchRow.layoutParams.apply { this.width = contentWidth }
        val gutter = (width - contentWidth) / 2
        results.setPadding(leftInset + gutter, controls.height + dp(8), rightInset + gutter, bottomInset + dp(16))
        resultsSurface.contentTopInset = results.paddingTop
        if (::loadingPanel.isInitialized) {
            loadingPanel.layoutParams = (loadingPanel.layoutParams as FrameLayout.LayoutParams).apply {
                gravity = if (tags.isEmpty()) Gravity.CENTER else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = if (tags.isEmpty()) 0 else bottomInset + dp(16)
            }
        }
    }

    private fun load(reset: Boolean, fromPull: Boolean = false) {
        if (!reset && (loading || !hasMore)) return
        if (reset) {
            loadJob?.cancel()
            query = input.text.toString().trim().ifBlank { "*" }
            page = 1
            hasMore = true
            if (!fromPull) {
                resultsSurface.isRefreshing = false
                tags.clear()
                summaryText = "正在加载标签…"
                tagAdapter.notifyDataSetChanged()
                results.scrollToPosition(0)
            }
        }
        loading = true
        failed = false
        statusPanel.visibility = View.GONE
        loadingPanel.visibility = if (fromPull) View.GONE else View.VISIBLE
        updateContentInsets()
        updateGlassRendering()
        val requestedPage = page
        val requestedQuery = query
        loadJob = scope.launch {
            try {
                val encoded = URLEncoder.encode(requestedQuery, "UTF-8")
                val raw = withContext(Dispatchers.IO) {
                    NetworkManager.getApi(this@TagSearchActivity, "search/tags?q=$encoded&page=$requestedPage")
                }
                val response = JSONObject(raw ?: error("Empty tag response"))
                val entries = response.optJSONArray("tags") ?: error("Missing tags in response")
                val newTags = buildList {
                    repeat(entries.length()) { index ->
                        entries.optJSONObject(index)?.let { tag ->
                            val name = tag.optString("name")
                            if (name.isNotBlank()) add(Tag(name, tag.optLong("images"), tag.optString("short_description")))
                        }
                    }
                }
                if (reset) tags.clear()
                val start = tags.size + 1
                tags.addAll(newTags)
                if (reset) tagAdapter.notifyDataSetChanged()
                else if (newTags.isNotEmpty()) tagAdapter.notifyItemRangeInserted(start, newTags.size)
                val total = response.optLong("total", -1)
                summaryText = if (total >= 0) "共 ${numberFormat.format(total)} 个标签" else "已加载 ${numberFormat.format(tags.size)} 个标签"
                tagAdapter.notifyItemChanged(0)
                hasMore = entries.length() > 0 && (total < 0 || tags.size < total)
                if (hasMore) page = requestedPage + 1
                if (tags.isEmpty()) {
                    statusPanel.headline = "没有找到标签"
                    statusPanel.supportingText = "试试其他关键词，或输入 * 查看全部"
                    statusPanel.setOnClickListener(null)
                    statusPanel.visibility = View.VISIBLE
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                failed = true
                if (tags.isEmpty()) {
                    summaryText = "标签加载失败"
                    tagAdapter.notifyItemChanged(0)
                }
                statusPanel.headline = if (reset) "标签加载失败" else "加载更多失败"
                statusPanel.supportingText = "点击重试"
                statusPanel.setOnClickListener { load(reset = reset) }
                statusPanel.visibility = View.VISIBLE
            } finally {
                // A cancelled search must not hide the next search's indicator.
                if (currentCoroutineContext().isActive) {
                    loading = false
                    loadingPanel.visibility = View.GONE
                    resultsSurface.isRefreshing = false
                    updateGlassRendering()
                }
            }
        }
    }

    private inner class TagAdapter : RecyclerView.Adapter<TagHolder>() {
        override fun getItemCount(): Int = tags.size + 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TagHolder {
            val row = trackGlass(LiquidGlassListItem(parent.context), pageBackdrop, 16f).apply {
                headlineTextView.maxLines = 2
                supportingTextView.maxLines = Int.MAX_VALUE
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            }
            return TagHolder(row)
        }

        override fun onBindViewHolder(holder: TagHolder, position: Int) {
            GlassWidgetStyle.apply(holder.row, 16f)
            if (position == 0) {
                holder.row.headline = summaryText
                holder.row.supportingText = null
                holder.row.trailingText = null
                holder.row.setOnClickListener(null)
            } else {
                val tag = tags[position - 1]
                holder.row.headline = tag.name
                holder.row.supportingText = tag.description.takeIf { it.isNotBlank() }
                holder.row.trailingText = "${numberFormat.format(tag.images)} 张"
                holder.row.setOnClickListener {
                    startActivity(Intent(this@TagSearchActivity, SearchActivity::class.java)
                        .putExtra(SearchActivity.EXTRA_INITIAL_QUERY, tag.name))
                }
            }
        }
    }

    private class TagHolder(val row: LiquidGlassListItem) : RecyclerView.ViewHolder(row)

    private fun <T : LiquidGlassView> trackGlass(view: T, source: View, radius: Float = 24f): T {
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

    private fun updateGlassRendering() {
        headerGlass.setRenderingActive(resumed && header.isShown, resultsSurface)
        attachedGlass.keys.toList().forEach { glass ->
            val active = resumed && glass.isShown && glass.getGlobalVisibleRect(visibleRect)
            glass.enableDynamicBackground = active
            glass.enableSensorHighlight = active
        }
    }

    private fun applyPalette() {
        val colors = PaletteManager.colors(this)
        pageBackdrop.setBackgroundColor(colors.surface)
        resultsSurface.setBackgroundColor(colors.surface)
        window.statusBarColor = colors.surface
        window.navigationBarColor = colors.surface
        WindowCompat.getInsetsController(window, root).apply {
            val light = Color.luminance(colors.surface) > 0.5f
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        headerGlass.setPalette(colors)
        val foreground = GlassWidgetStyle.foreground(headerGlass)
        toolbar.setTitleTextColor(GlassWidgetStyle.TEXT_COLOR)
        toolbar.navigationIcon?.mutate()?.setTint(foreground)
        input.setTextColor(GlassWidgetStyle.TEXT_COLOR)
        input.setHintTextColor(GlassWidgetStyle.TEXT_COLOR)
        loadingIndicator.applyPalette(colors)
        resultsSurface.applyPalette(colors)
        attachedGlass.toMap().forEach { (glass, radius) -> GlassWidgetStyle.apply(glass, radius) }
        tagAdapter.notifyDataSetChanged()
        updateGlassRendering()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        applyPalette()
    }

    override fun onPause() {
        resumed = false
        updateGlassRendering()
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        headerGlass.setRenderingActive(false, null)
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
