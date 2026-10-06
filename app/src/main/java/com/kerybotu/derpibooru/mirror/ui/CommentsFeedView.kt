package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.liquidglass.LiquidGlassToast
import com.kerybotu.derpibooru.mirror.model.Comment
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import com.kerybotu.derpibooru.mirror.translate.NiuTransService
import kotlinx.coroutines.*
import org.json.JSONObject

/** Shared by the cached primary Comments tab and the standalone recent-comments screen. */
open class CommentsFeedView(context: Context, title: String, onBack: (() -> Unit)? = null) : GlassFeedLayout(context, title, onBack) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val comments = mutableListOf<Comment>()
    private val translations = mutableMapOf<Comment, CommentTranslation>()
    private val feedAdapter = CommentsAdapter()
    private var loaded = false
    private var loading = false
    private var failed = false
    private var hasMore = true
    private var page = 1

    init {
        results.adapter = feedAdapter
        refresh.setCanRefresh { !loading }
        refresh.setOnRefreshListener { load(reset = true, fromPull = true) }
        onBecameActive = { if (!loaded && !loading && !failed) load(reset = true) }
        onPaletteChanged = {
            for (index in 0 until results.childCount) (results.getChildAt(index) as? GlassCommentCard)?.applyPalette()
        }
        results.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val manager = recyclerView.layoutManager as LinearLayoutManager
                if (isActive && dy > 0 && !failed && manager.findLastVisibleItemPosition() >= comments.size - 5) load(reset = false)
            }
        })
    }

    private fun load(reset: Boolean, fromPull: Boolean = false) {
        if (loading || (!reset && !hasMore)) return
        loading = true
        failed = false
        hideStatus()
        showLoading(!fromPull, centered = comments.isEmpty())
        val requestedPage = if (reset) 1 else page
        scope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    NetworkManager.getApi(context, "search/comments?q=*&page=$requestedPage")
                }
                val response = JSONObject(raw ?: error("Empty comments response"))
                val entries = response.optJSONArray("comments") ?: error("Missing comments in response")
                val received = (0 until entries.length()).mapNotNull { index ->
                    entries.optJSONObject(index)?.let { Comment.fromJson(it) }
                }
                if (reset) comments.clear()
                val start = comments.size
                comments.addAll(received)
                if (reset) {
                    translations.keys.retainAll(comments.toSet())
                    feedAdapter.notifyDataSetChanged()
                } else if (received.isNotEmpty()) feedAdapter.notifyItemRangeInserted(start, received.size)
                val total = response.optLong("total", -1)
                hasMore = entries.length() > 0 && (total < 0 || comments.size < total)
                page = requestedPage + 1
                loaded = true
                if (comments.isEmpty()) showStatus("暂无评论", "下拉刷新，查看最新评论")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                failed = true
                showStatus(if (reset) "评论加载失败" else "加载更多失败", "点击重试") { load(reset) }
            } finally {
                if (currentCoroutineContext().isActive) {
                    loading = false
                    showLoading(false)
                    refresh.isRefreshing = false
                }
            }
        }
    }

    private fun toggleTranslation(comment: Comment) {
        val state = translations.getOrPut(comment) { CommentTranslation() }
        if (state.loading) return
        if (state.text != null) {
            state.showTranslation = !state.showTranslation
            updateComment(comment)
            return
        }
        state.loading = true
        updateComment(comment)
        scope.launch {
            try {
                NiuTransService.translate(comment.body).onSuccess {
                    state.text = it
                    state.showTranslation = true
                }.onFailure {
                    if (this@CommentsFeedView.isActive && isShown) LiquidGlassToast.makeText(context, "翻译失败，请重试", LiquidGlassToast.LENGTH_SHORT)
                        .setTextColor(GlassWidgetStyle.TEXT_COLOR).show()
                }
            } finally {
                state.loading = false
                if (currentCoroutineContext().isActive) updateComment(comment)
            }
        }
    }

    private fun updateComment(comment: Comment) {
        val index = comments.indexOf(comment)
        if (index >= 0) feedAdapter.notifyItemChanged(index)
    }

    private inner class CommentsAdapter : RecyclerView.Adapter<CommentHolder>() {
        override fun getItemCount(): Int = comments.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentHolder {
            val card = GlassCommentCard(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
                    bottomMargin = (10 * resources.displayMetrics.density).toInt()
                }
                glassSurfaces.forEach { trackGlass(it) }
            }
            return CommentHolder(card)
        }
        override fun onBindViewHolder(holder: CommentHolder, position: Int) {
            val comment = comments[position]
            holder.card.bind(comment, translations[comment], { toggleTranslation(comment) }) { imageId ->
                context.startActivity(Intent(context, ImageDetailActivity::class.java).putExtra("image_id", imageId))
            }
        }
    }

    private class CommentHolder(val card: GlassCommentCard) : RecyclerView.ViewHolder(card)

    fun dispose() {
        setActive(false)
        scope.cancel()
    }
}
