package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.widget.NestedScrollView
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
    private var page = 1
    private var loading = false
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
            layoutManager = GridLayoutManager(context, 2)
        }
        adapter = ImageAdapter(emptyList(), { openDetails(it) }) { count -> onSelectionChanged?.invoke(count) }
        grid.adapter = adapter
        content.addView(grid, LinearLayout.LayoutParams(-1, -2))
        scroll.addView(content)
        refreshLayout.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        refreshLayout.setOnRefreshListener { refresh() }
        addView(refreshLayout, LayoutParams(-1, -1))
        progress = ProgressBar(context).apply { isIndeterminate = true }
        addView(progress, LayoutParams(dp(48), dp(48), android.view.Gravity.CENTER))
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            if (!loading && y + scroll.height >= content.height - dp(500)) loadPage()
        }
        refresh()
    }

    fun refresh() {
        page = 1
        items.clear()
        adapter.updateData(emptyList())
        heroBox.visibility = View.GONE
        loadPage()
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
        return Image(o.optInt("id"), "", thumb, o.optInt("width"), o.optInt("height"), o.optInt("score"), o.optInt("faves"), o.optInt("upvotes"), o.optInt("downvotes"), o.optInt("comment_count"), tags(o), reps?.optString("full", null), o.optString("uploader", null), o.optString("created_at", null), o.optString("description", null), o.optString("mime_type", null), o.optLong("uploader_id", -1L).takeIf { it > 0L })
    }

    private fun tags(o: JSONObject): List<String> {
        val a = o.optJSONArray("tags") ?: return emptyList()
        return List(a.length()) { a.optString(it) }
    }

    private fun openDetails(image: Image) { context.startActivity(Intent(context, ImageDetailActivity::class.java).putExtra("image", image)) }
    fun selectedImages(): List<Image> = adapter.selectedItems()
    fun clearSelection() = adapter.clearSelection()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    fun dispose() { scope.cancel() }
}
