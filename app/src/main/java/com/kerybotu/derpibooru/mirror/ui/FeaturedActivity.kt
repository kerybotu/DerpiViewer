package com.kerybotu.derpibooru.mirror.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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

class FeaturedActivity : AppCompatActivity() {
    private lateinit var rootScroll: NestedScrollView
    private lateinit var heroContainer: LinearLayout
    private lateinit var heroImage: ImageView
    private lateinit var grid: RecyclerView
    private lateinit var adapter: ImageAdapter
    private lateinit var refreshLayout: PullRefreshLayout
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var page = 1
    private var loading = false
    private var sortField = "score"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PaletteManager.apply(this)
        setContentView(buildContent())
        loadFeatured(refresh = true)
    }

    private fun buildContent(): View {
        rootScroll = NestedScrollView(this).apply {
            isFillViewport = true
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                val child = getChildAt(0)
                if (!loading && child != null && scrollY + height >= child.height - 600) {
                    loadFeatured(refresh = false)
                }
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 0, 12, 24)
        }
        val toolbar = androidx.appcompat.widget.Toolbar(this).apply {
            title = "热门精选"
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationOnClickListener { finish() }
        }
        content.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))

        heroContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heroImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "精选头图"
        }
        heroContainer.addView(heroImage, LinearLayout.LayoutParams(-1, dp(280)))
        content.addView(heroContainer)

        val title = TextView(this).apply {
            text = "近期精选\n过去 3 天内的优质内容，已过滤 AI 生成内容"
            textSize = 16f
            setPadding(4, 18, 4, 12)
        }
        content.addView(title)

        grid = RecyclerView(this).apply {
            isNestedScrollingEnabled = false
            layoutManager = GridLayoutManager(this@FeaturedActivity, 2)
        }
        adapter = ImageAdapter(emptyList(), { openDetails(it) })
        grid.adapter = adapter
        content.addView(grid, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
        rootScroll.addView(content)
        refreshLayout = PullRefreshLayout(this).apply {
            setOnRefreshListener {
                loadFeatured(refresh = true)
            }
            addView(rootScroll)
        }
        return refreshLayout
    }

    private fun loadFeatured(refresh: Boolean) {
        if (loading) return
        if (refresh) page = 1
        loading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val hero = async { fetchFeaturedHero() }
                val list = async { fetchImages("first_seen_at.gt:3 days ago,-ai generated,-ai composition", sortField, 50, page) }
                awaitAll(hero, list)
            }
            val heroImages = result[0]
            val listImages = result[1]
            if (refresh) {
                featuredItems.clear()
                featuredItems.addAll(listImages)
                val hero = heroImages.firstOrNull()
                if (hero == null) {
                    heroContainer.visibility = View.GONE
                } else {
                    heroContainer.visibility = View.VISIBLE
                    CdnImageGate.load(heroImage, hero.thumbnailUrl, AppSettings.getCdnThreads(this@FeaturedActivity))
                    heroImage.setOnClickListener { openDetails(hero) }
                }
                adapter.updateData(featuredItems)
            } else if (listImages.isNotEmpty()) {
                featuredItems.addAll(listImages)
                adapter.updateData(featuredItems)
            }
            if (listImages.isNotEmpty()) {
                page++
            }
            loading = false
            refreshLayout.isRefreshing = false
        }
    }

    private val featuredItems = mutableListOf<Image>()

    private suspend fun fetchFeaturedHero(): List<Image> {
        val json = NetworkManager.getApi(this@FeaturedActivity, "images/featured") ?: return emptyList()
        val root = JSONObject(json)
        val image = root.optJSONObject("image") ?: root.optJSONArray("images")?.optJSONObject(0) ?: return emptyList()
        return parseImages(JSONObject().put("images", org.json.JSONArray().put(image)).toString())
    }

    private suspend fun fetchImages(query: String, sort: String, perPage: Int, requestedPage: Int = 1): List<Image> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val filter = NetworkManager.currentFilterParam(this@FeaturedActivity)
        val path = "search/images?q=$encoded&sf=$sort&sd=desc&per_page=$perPage&page=$requestedPage$filter"
        val json = NetworkManager.getApi(this@FeaturedActivity, path) ?: return emptyList()
        return parseImages(json)
    }

    private fun parseImages(json: String): List<Image> {
        val array = JSONObject(json).optJSONArray("images") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val reps = obj.optJSONObject("representations")
            Image(
                id = obj.optInt("id", -1), title = "", thumbnailUrl = reps?.optString("small", null) ?: reps?.optString("thumb", null),
                width = obj.optInt("width"), height = obj.optInt("height"), score = obj.optInt("score"), faves = obj.optInt("faves"),
                upvotes = obj.optInt("upvotes"), downvotes = obj.optInt("downvotes"), commentCount = obj.optInt("comment_count"),
                tags = List(obj.optJSONArray("tags")?.length() ?: 0) { obj.optJSONArray("tags")!!.optString(it) },
                fullUrl = reps?.optString("full", null), uploader = obj.optString("uploader", null),
                createdAt = obj.optString("created_at", null), description = obj.optString("description", null), mimeType = obj.optString("mime_type", null)
            )
        }
    }

    private fun openDetails(image: Image) {
        startActivity(Intent(this, ImageDetailActivity::class.java).putExtra("image", image))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
