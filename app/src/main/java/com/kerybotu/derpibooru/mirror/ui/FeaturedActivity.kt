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
import com.kerybotu.derpibooru.mirror.network.ApiFailureKind
import com.kerybotu.derpibooru.mirror.network.ApiRequestDiagnostic
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
    private lateinit var statusText: TextView
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var page = 1
    private var loading = false
    private var lastDetailsClickAt = 0L
    private var sortField = "score"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PaletteManager.apply(this)
        val pageRoot = buildContent()
        setContentView(pageRoot)
        AdaptiveNavigationShell.install(this, pageRoot, AdaptiveNavigationShell.HOME)
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
        val toolbar = SafeToolbar(this).apply {
            title = "热门精选"
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationOnClickListener { finish() }
        }
        content.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)).apply {
            val margin = dp(Ui2DesignSystem.Spacing.md)
            marginStart = margin
            marginEnd = margin
        })

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

        statusText = TextView(this).apply {
            visibility = View.GONE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { loadFeatured(refresh = true) }
        }
        content.addView(statusText, LinearLayout.LayoutParams(-1, -2))

        grid = RecyclerView(this).apply {
            isNestedScrollingEnabled = false
            layoutManager = GridLayoutManager(this@FeaturedActivity, AdaptiveLayoutPolicy.artworkColumnCount(this@FeaturedActivity))
        }
        AdaptiveLayoutPolicy.configureArtworkGrid(this, grid)
        adapter = ImageAdapter(emptyList(), { openDetails(it) }, settingsContext = this)
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
        statusText.visibility = View.GONE
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val hero = async { fetchFeaturedHero() }
                    val list = async { fetchImages("first_seen_at.gt:3 days ago,-ai generated,-ai composition", sortField, 50, page) }
                    awaitAll(hero, list)
                }
                @Suppress("UNCHECKED_CAST")
                val heroImages = result[0] as List<Image>
                @Suppress("UNCHECKED_CAST")
                val listImages = result[1] as List<Image>
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
                if (listImages.isNotEmpty()) page++
            } catch (failure: ApiFeaturedFailure) {
                statusText.text = "精选加载失败：${featuredFailureMessage(failure.diagnostic)} · 诊断 ${failure.diagnostic.requestId}，点击重试"
                statusText.visibility = View.VISIBLE
            } catch (failure: Exception) {
                val diagnostic = ApiRequestDiagnostic(
                    requestId = "parse-${System.currentTimeMillis().toString(16).takeLast(6)}",
                    operation = "images/featured", host = "derpibooru.org", phase = "parse",
                    kind = ApiFailureKind.INVALID_JSON, elapsedMs = 0, attempt = 1,
                    maxAttempts = 1, networkConfigurationVersion = NetworkManager.configurationVersion.value, retryable = false,
                    rootCause = failure.javaClass.simpleName
                )
                NetworkManager.recordClientDiagnostic(diagnostic)
                statusText.text = "精选响应格式异常 · 诊断 ${diagnostic.requestId}，点击重试"
                statusText.visibility = View.VISIBLE
            } finally {
                loading = false
                refreshLayout.isRefreshing = false
            }
        }
    }

    private val featuredItems = mutableListOf<Image>()

    private suspend fun fetchFeaturedHero(): List<Image> {
        val result = NetworkManager.getApiWithDiagnostics(this@FeaturedActivity, "images/featured")
        val json = result.body ?: throw ApiFeaturedFailure(result.failure ?: unknownDiagnostic("images/featured"))
        val root = JSONObject(json)
        val image = root.optJSONObject("image") ?: root.optJSONArray("images")?.optJSONObject(0) ?: return emptyList()
        return parseImages(JSONObject().put("images", org.json.JSONArray().put(image)).toString())
    }

    private suspend fun fetchImages(query: String, sort: String, perPage: Int, requestedPage: Int = 1): List<Image> {
        val encoded = NetworkManager.encodeSearchQuery(this@FeaturedActivity, query)
        val filter = NetworkManager.currentFilterParam(this@FeaturedActivity)
        val path = "search/images?q=$encoded&sf=$sort&sd=desc&per_page=$perPage&page=$requestedPage$filter"
        val result = NetworkManager.getApiWithDiagnostics(this@FeaturedActivity, path)
        val json = result.body ?: throw ApiFeaturedFailure(result.failure ?: unknownDiagnostic("search/images"))
        return parseImages(json)
    }

    private class ApiFeaturedFailure(val diagnostic: ApiRequestDiagnostic) : RuntimeException()

    private fun unknownDiagnostic(operation: String) = ApiRequestDiagnostic(
        requestId = "unknown", operation = operation, host = "unknown", phase = "client",
        kind = ApiFailureKind.UNKNOWN, elapsedMs = 0, attempt = 1, maxAttempts = 1,
        networkConfigurationVersion = NetworkManager.configurationVersion.value,
        retryable = true, rootCause = "empty_response"
    )

    private fun featuredFailureMessage(diagnostic: ApiRequestDiagnostic): String = when (diagnostic.kind) {
        ApiFailureKind.DNS -> "无法解析服务器地址"
        ApiFailureKind.TCP, ApiFailureKind.CONNECT_TIMEOUT -> "无法连接服务器"
        ApiFailureKind.READ_TIMEOUT -> "服务器响应超时"
        ApiFailureKind.RATE_LIMITED -> "请求过于频繁，请稍后再试"
        ApiFailureKind.HTTP_4XX -> "服务器拒绝请求（HTTP ${diagnostic.statusCode ?: "4xx"}）"
        ApiFailureKind.HTTP_5XX -> "服务器暂时异常（HTTP ${diagnostic.statusCode ?: "5xx"}）"
        ApiFailureKind.BUSINESS_ERROR -> "服务器返回了业务错误"
        ApiFailureKind.CONFIGURATION_CHANGED -> "网络配置已变化"
        else -> "精选请求失败"
    }

    private fun parseImages(json: String): List<Image> {
        val array = JSONObject(json).optJSONArray("images") ?: error("Missing images field")
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val reps = obj.optJSONObject("representations")
            Image(
                id = obj.optInt("id", -1), title = "", thumbnailUrl = reps?.optString("small").takeIf { !it.isNullOrBlank() }
                    ?: reps?.optString("thumb").takeIf { !it.isNullOrBlank() },
                width = obj.optInt("width"), height = obj.optInt("height"), score = obj.optInt("score"), faves = obj.optInt("faves"),
                upvotes = obj.optInt("upvotes"), downvotes = obj.optInt("downvotes"), commentCount = obj.optInt("comment_count"),
                tags = List(obj.optJSONArray("tags")?.length() ?: 0) { obj.optJSONArray("tags")!!.optString(it) },
                fullUrl = reps?.optString("full").takeIf { !it.isNullOrBlank() }, uploader = obj.optString("uploader").takeIf { !it.isNullOrBlank() },
                createdAt = obj.optString("created_at").takeIf { !it.isNullOrBlank() }, description = obj.optString("description").takeIf { !it.isNullOrBlank() }, mimeType = obj.optString("mime_type").takeIf { !it.isNullOrBlank() },
                spoilered = obj.optBoolean("spoilered", false)
            )
        }
    }

    private fun openDetails(image: Image) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastDetailsClickAt < 500L) return
        lastDetailsClickAt = now
        startActivity(Intent(this, ImageDetailActivity::class.java).putExtra("image", image))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) adapter.refreshDisplayMode()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
