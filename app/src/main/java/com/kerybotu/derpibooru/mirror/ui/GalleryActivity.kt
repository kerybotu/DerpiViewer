package com.kerybotu.derpibooru.mirror.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.liquidglass.LiquidGlassButton
import com.example.liquidglass.LiquidGlassDialogBuilder
import com.example.liquidglass.LiquidGlassListItem
import com.example.liquidglass.LiquidGlassView
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.R
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URLEncoder

class GalleryActivity : AppCompatActivity() {
    /* Legacy ScrollView gallery implementation superseded by the shared GlassFeedLayout.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); private lateinit var list: LinearLayout; private lateinit var scroll: ScrollView; private lateinit var progress: ProgressBar; private var page = 1; private var query = "*"; private var loading = false
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContentView(buildView()); load(true) }
    private fun buildView(): LinearLayout { val c = PaletteManager.colors(this); return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(c.surface); val toolbar = SafeToolbar(this@GalleryActivity).apply { title = "图集"; setNavigationIcon(com.kerybotu.derpibooru.mirror.R.drawable.ic_arrow_back); setNavigationOnClickListener { finish() }; inflateMenu(com.kerybotu.derpibooru.mirror.R.menu.filter_menu); setOnMenuItemClickListener { showFilters(); true } }; addView(toolbar, LinearLayout.LayoutParams(-1, dp(56))); scroll = ScrollView(this@GalleryActivity); list = LinearLayout(this@GalleryActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }; scroll.addView(list); addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); progress = ProgressBar(this@GalleryActivity).apply { visibility = View.GONE }; addView(progress, LinearLayout.LayoutParams(-2, dp(40)).apply { gravity = Gravity.CENTER_HORIZONTAL }); scroll.viewTreeObserver.addOnScrollChangedListener { if (!loading && scroll.getChildAt(0).bottom - scroll.height - scroll.scrollY < dp(500)) load(false) } } }
    private fun showFilters() { val input = EditText(this).apply { hint = "标题、描述或创建者"; setText(query.takeUnless { it == "*" }) }; BottomSheetDialog(this).apply { setContentView(LinearLayout(this@GalleryActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(32)); addView(input); addView(Button(this@GalleryActivity).apply { text = "应用筛选"; PaletteManager.styleButton(this); setOnClickListener { query = input.text.toString().trim().ifBlank { "*" }; load(true); dismiss() } }) }); show() } }
    private fun load(reset: Boolean) { if (loading) return; if (reset) { page = 1; list.removeAllViews() }; loading = true; progress.visibility = View.VISIBLE; scope.launch { val q = URLEncoder.encode(query, "UTF-8"); val raw = withContext(Dispatchers.IO) { NetworkManager.getApi(this@GalleryActivity, "search/galleries?q=$q&page=$page") }; val arr = runCatching { JSONObject(raw.orEmpty()).optJSONArray("galleries") }.getOrNull(); repeat(arr?.length() ?: 0) { i -> arr?.optJSONObject(i)?.let { add(it) } }; if ((arr?.length() ?: 0) > 0) page++; loading = false; progress.visibility = View.GONE } }
    private fun add(gallery: JSONObject) {
        val c = PaletteManager.colors(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(c.surfaceVariant)
            addView(TextView(this@GalleryActivity).apply { text = gallery.optString("title"); textSize = 17f; setTextColor(c.primary) })
            gallery.optString("description").takeIf { it.isNotBlank() }?.let { description -> addView(TextView(this@GalleryActivity).apply { text = description; setTextColor(c.onSurface); setPadding(0, dp(5), 0, 0) }) }
            addView(TextView(this@GalleryActivity).apply { text = gallery.optString("user", "未知创建者"); setTextColor(c.muted); setPadding(0, dp(6), 0, 0) })
        }
        card.setOnClickListener { startActivity(Intent(this, SearchActivity::class.java).putExtra(SearchActivity.EXTRA_INITIAL_QUERY, "gallery_id:${gallery.optInt("id")}")) }
        list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) })
    */
    private data class Gallery(val id: Int, val title: String, val description: String, val creator: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val galleries = mutableListOf<Gallery>()
    private val galleryAdapter = GalleryAdapter()
    private lateinit var feed: GlassFeedLayout
    private var loadJob: Job? = null
    private var page = 1
    private var query = "*"
    private var loading = false
    private var hasMore = true
    private var failed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        feed = GlassFeedLayout(this, "图集") { finish() }
        feed.results.adapter = galleryAdapter
        feed.toolbar.inflateMenu(R.menu.filter_menu)
        feed.toolbar.setOnMenuItemClickListener { showFilters(); true }
        feed.refresh.setCanRefresh { !loading }
        feed.refresh.setOnRefreshListener { load(reset = true, fromPull = true) }
        feed.results.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val manager = recyclerView.layoutManager as LinearLayoutManager
                if (dy > 0 && !failed && manager.findLastVisibleItemPosition() >= galleries.size - 5) load(reset = false)
            }
        })
        setContentView(feed)
        load(reset = true)
    }

    private fun showFilters() {
        val input = EditText(this).apply {
            hint = "标题、描述或创建者"
            setText(query.takeUnless { it == "*" })
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = null
            setPadding(dp(16), 0, dp(16), 0)
            setTextColor(GlassWidgetStyle.TEXT_COLOR)
            setHintTextColor(GlassWidgetStyle.TEXT_COLOR)
        }
        val inputSurface = feed.trackGlass(LiquidGlassView(this), feed.refresh, 24f).apply {
            addView(input, FrameLayout.LayoutParams(-1, dp(56)))
        }
        lateinit var dialog: AlertDialog
        fun applyFilter() {
            query = input.text.toString().trim().ifBlank { "*" }
            load(reset = true)
            dialog.dismiss()
        }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { applyFilter(); true } else false
        }
        val actions = LinearLayout(this)
        actions.addView(button("取消") { dialog.dismiss() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        actions.addView(button("应用筛选") { applyFilter() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
            addView(inputSurface, LinearLayout.LayoutParams(-1, -2))
            addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        val builder = LiquidGlassDialogBuilder(this, animateShow = false, glassSetup = {
            feed.trackGlass(this, feed.refresh, 28f)
        }).apply {
            overLightTextColor = GlassWidgetStyle.TEXT_COLOR
            overDarkTextColor = GlassWidgetStyle.TEXT_COLOR
        }
        dialog = builder.setTitle("筛选图集").setView(content).create()
        dialog.show()
    }

    private fun button(label: String, action: () -> Unit) = feed.trackGlass(LiquidGlassButton(this), feed.refresh).apply {
        text = label
        setTextSize(14f)
        textView.setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener { action() }
    }

    private fun load(reset: Boolean, fromPull: Boolean = false) {
        if (!reset && (loading || !hasMore)) return
        if (reset) {
            loadJob?.cancel()
            hasMore = true
            if (!fromPull) {
                feed.refresh.isRefreshing = false
                galleries.clear()
                galleryAdapter.notifyDataSetChanged()
                feed.results.scrollToPosition(0)
            }
        }
        loading = true
        failed = false
        feed.hideStatus()
        feed.showLoading(!fromPull, centered = galleries.isEmpty())
        val requestedPage = if (reset) 1 else page
        val requestedQuery = query
        loadJob = scope.launch {
            try {
                val encoded = URLEncoder.encode(requestedQuery, "UTF-8")
                val raw = withContext(Dispatchers.IO) {
                    NetworkManager.getApi(this@GalleryActivity, "search/galleries?q=$encoded&page=$requestedPage")
                }
                val response = JSONObject(raw ?: error("Empty gallery response"))
                val entries = response.optJSONArray("galleries") ?: error("Missing galleries in response")
                val received = (0 until entries.length()).mapNotNull { index ->
                    val entry = entries.optJSONObject(index) ?: return@mapNotNull null
                    val id = entry.optInt("id")
                    if (id <= 0) return@mapNotNull null
                    val creator = entry.optJSONObject("user")?.optString("name")?.takeIf { it.isNotBlank() }
                        ?: entry.optString("user").takeIf { it.isNotBlank() && it != "null" } ?: "未知创建者"
                    Gallery(id, entry.optString("title").ifBlank { "未命名图集" }, entry.optString("description"), creator)
                }
                if (reset) galleries.clear()
                val start = galleries.size
                galleries.addAll(received)
                if (reset) galleryAdapter.notifyDataSetChanged()
                else if (received.isNotEmpty()) galleryAdapter.notifyItemRangeInserted(start, received.size)
                val total = response.optLong("total", -1)
                hasMore = entries.length() > 0 && (total < 0 || galleries.size < total)
                page = requestedPage + 1
                if (galleries.isEmpty()) feed.showStatus("没有找到图集", "试试其他筛选条件，或清空条件查看全部")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                failed = true
                feed.showStatus(if (reset) "图集加载失败" else "加载更多失败", "点击重试") { load(reset) }
            } finally {
                if (currentCoroutineContext().isActive) {
                    loading = false
                    feed.showLoading(false)
                    feed.refresh.isRefreshing = false
                }
            }
        }
    }

    private inner class GalleryAdapter : RecyclerView.Adapter<GalleryHolder>() {
        override fun getItemCount(): Int = galleries.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GalleryHolder {
            val card = feed.trackGlass(LiquidGlassListItem(parent.context)).apply {
                headlineTextView.maxLines = 2
                supportingTextView.maxLines = Int.MAX_VALUE
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
            }
            return GalleryHolder(card)
        }
        override fun onBindViewHolder(holder: GalleryHolder, position: Int) {
            val gallery = galleries[position]
            holder.card.headline = gallery.title
            holder.card.supportingText = listOfNotNull(gallery.description.takeIf { it.isNotBlank() }, "创建者：${gallery.creator}")
                .joinToString("\n")
            holder.card.setOnClickListener {
                startActivity(Intent(this@GalleryActivity, SearchActivity::class.java)
                    .putExtra(SearchActivity.EXTRA_INITIAL_QUERY, "gallery_id:${gallery.id}"))
            }
            GlassWidgetStyle.apply(holder.card, 16f)
        }
    }

    private class GalleryHolder(val card: LiquidGlassListItem) : RecyclerView.ViewHolder(card)

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

    override fun onPause() { feed.setActive(false); super.onPause() }
    override fun onDestroy() { feed.setActive(false); scope.cancel(); super.onDestroy() }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
