package com.kerybotu.derpibooru.mirror.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.liquidglass.LiquidGlassListItem
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.model.Comment
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import com.kerybotu.derpibooru.mirror.translate.NiuTransService
import kotlinx.coroutines.*
import org.json.JSONObject

class ForumActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_FORUM = "forum"
        const val EXTRA_TOPIC = "topic"
        const val EXTRA_TITLE = "title"
    }
    private data class Entry(val title: String, val detail: String, val slug: String, val post: Comment? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val entries = mutableListOf<Entry>()
    private val translations = mutableMapOf<Comment, CommentTranslation>()
    private val forumAdapter = ForumAdapter()
    private lateinit var feed: GlassFeedLayout
    private val forum get() = intent.getStringExtra(EXTRA_FORUM)
    private val topic get() = intent.getStringExtra(EXTRA_TOPIC)
    private var page = 1
    private var loading = false
    private var hasMore = true
    private var failed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: if (topic != null) "帖子" else forum ?: "论坛"
        feed = GlassFeedLayout(this, title) { finish() }
        feed.results.adapter = forumAdapter
        feed.refresh.setCanRefresh { !loading }
        feed.refresh.setOnRefreshListener { load(reset = true, fromPull = true) }
        feed.onPaletteChanged = {
            for (index in 0 until feed.results.childCount)
                (feed.results.getChildAt(index) as? GlassCommentCard)?.applyPalette()
        }
        feed.results.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val manager = recyclerView.layoutManager as LinearLayoutManager
                if (dy > 0 && !failed && manager.findLastVisibleItemPosition() >= entries.size - 5) load(reset = false)
            }
        })
        setContentView(feed)
        load(reset = true)
    }

    private fun load(reset: Boolean, fromPull: Boolean = false) {
        if (loading || (!reset && !hasMore)) return
        loading = true
        failed = false
        feed.hideStatus()
        feed.showLoading(!fromPull, centered = entries.isEmpty())
        val requestedPage = if (reset) 1 else page
        val path = when {
            topic != null -> "forums/$forum/topics/$topic/posts?page=$requestedPage"
            forum != null -> "forums/$forum/topics?page=$requestedPage"
            else -> "forums"
        }
        val key = when { topic != null -> "posts"; forum != null -> "topics"; else -> "forums" }
        scope.launch {
            try {
                val raw = withContext(Dispatchers.IO) { NetworkManager.getApi(this@ForumActivity, path) }
                val response = JSONObject(raw ?: error("Empty forum response"))
                val array = response.optJSONArray(key) ?: error("Missing $key in response")
                val received = (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    when (key) {
                        "forums" -> Entry(item.optString("name"),
                            "${item.optString("description")}\n${item.optInt("topic_count")} 个主题", item.optString("short_name"))
                        "topics" -> Entry(item.optString("title"),
                            "${author(item)} · ${item.optString("created_at").take(10)}\n回复 ${item.optInt("reply_count", item.optInt("post_count"))}",
                            item.optString("slug"))
                        else -> Entry("", "", "", Comment.fromJson(item).copy(author = author(item), imageId = null))
                    }
                }
                if (reset) entries.clear()
                val start = entries.size
                entries.addAll(received)
                if (reset) {
                    translations.keys.retainAll(entries.mapNotNull { it.post }.toSet())
                    forumAdapter.notifyDataSetChanged()
                } else if (received.isNotEmpty()) forumAdapter.notifyItemRangeInserted(start, received.size)
                val total = response.optLong("total", -1)
                // The root endpoint is not paginated; scrolling must not append all forums again.
                hasMore = forum != null && array.length() > 0 && (total < 0 || entries.size < total)
                page = requestedPage + 1
                if (entries.isEmpty()) feed.showStatus("暂无内容", "下拉刷新查看最新内容")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                failed = true
                feed.showStatus(if (reset) "论坛加载失败" else "加载更多失败", "点击重试") { load(reset) }
            } finally {
                if (currentCoroutineContext().isActive) {
                    loading = false
                    feed.showLoading(false)
                    feed.refresh.isRefreshing = false
                }
            }
        }
    }

    private fun author(item: JSONObject): String =
        item.optString("author").takeIf { it.isNotBlank() && it != "null" }
            ?: item.optJSONObject("user")?.optString("name")?.takeIf { it.isNotBlank() }
            ?: item.optString("user").takeIf { it.isNotBlank() && it != "null" } ?: "匿名用户"

    private fun toggleTranslation(post: Comment) {
        val state = translations.getOrPut(post) { CommentTranslation() }
        if (state.loading) return
        if (state.text != null) {
            state.showTranslation = !state.showTranslation
            updatePost(post)
            return
        }
        state.loading = true
        updatePost(post)
        scope.launch {
            try {
                NiuTransService.translate(post.body).onSuccess {
                    state.text = it
                    state.showTranslation = true
                }.onFailure { GlassPageDialogs(feed).toast("翻译失败，请重试") }
            } finally {
                state.loading = false
                if (currentCoroutineContext().isActive) updatePost(post)
            }
        }
    }

    private fun updatePost(post: Comment) {
        val position = entries.indexOfFirst { it.post == post }
        if (position >= 0) forumAdapter.notifyItemChanged(position)
    }

    private inner class ForumAdapter : RecyclerView.Adapter<ForumHolder>() {
        override fun getItemCount(): Int = entries.size
        override fun getItemViewType(position: Int): Int = if (entries[position].post == null) 0 else 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ForumHolder {
            val card = if (viewType == 1) GlassCommentCard(parent.context).apply {
                glassSurfaces.forEach { feed.trackGlass(it) }
            } else feed.trackGlass(LiquidGlassListItem(parent.context)).apply {
                headlineTextView.maxLines = 3
                supportingTextView.maxLines = Int.MAX_VALUE
            }
            card.layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
            return ForumHolder(card)
        }
        override fun onBindViewHolder(holder: ForumHolder, position: Int) {
            val entry = entries[position]
            val post = entry.post
            if (holder.card is GlassCommentCard && post != null) {
                holder.card.bind(post, translations[post], { toggleTranslation(post) }, {})
            } else {
                holder.card.headline = entry.title
                holder.card.supportingText = entry.detail
                holder.card.setOnClickListener {
                    if (entry.slug.isNotBlank()) {
                        val next = Intent(this@ForumActivity, ForumActivity::class.java).putExtra(EXTRA_TITLE, entry.title)
                        if (forum == null) next.putExtra(EXTRA_FORUM, entry.slug)
                        else next.putExtra(EXTRA_FORUM, forum).putExtra(EXTRA_TOPIC, entry.slug)
                        startActivity(next)
                    }
                }
                GlassWidgetStyle.apply(holder.card, 16f)
            }
        }
    }

    private class ForumHolder(val card: LiquidGlassListItem) : RecyclerView.ViewHolder(card)

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
