package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.*
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.model.Comment
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URLEncoder

/** Cached first-level messages screen. The view owns its scroll position and loaded pages. */
class EmbeddedMessagesView(context: Context) : FrameLayout(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context)
    private val progress = ProgressBar(context)
    private var page = 1
    private var loading = false
    private var loaded = false

    init {
        val colors = PaletteManager.colors(context)
        setBackgroundColor(colors.surface)
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(SafeToolbar(context).apply {
            title = "消息"
            setNavigationIcon(com.kerybotu.derpibooru.mirror.R.drawable.ic_menu)
            setNavigationOnClickListener {
                (context as? com.kerybotu.derpibooru.mirror.MainActivity)?.showUnifiedGlassMenu()
            }
            applyUi2Appearance()
        }, LinearLayout.LayoutParams(-1, dp(56)))
        list.setPadding(dp(16), dp(8), dp(16), dp(24))
        scroll.addView(list, android.widget.FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(progress, LinearLayout.LayoutParams(-2, dp(40)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        addView(root, LayoutParams(-1, -1))
        progress.visibility = View.GONE
        scroll.viewTreeObserver.addOnScrollChangedListener {
            if (!loading && scroll.childCount > 0 && scroll.getChildAt(0).bottom - scroll.height - scroll.scrollY < dp(500)) load()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!loaded) load() }

    private fun load() {
        if (loading) return
        loading = true; progress.visibility = View.VISIBLE
        val activity = context
        scope.launch {
            val raw = withContext(Dispatchers.IO) {
                NetworkManager.getApi(activity, "search/comments?q=${URLEncoder.encode("*", "UTF-8")}&page=$page")
            }
            val arr = runCatching { JSONObject(raw.orEmpty()).optJSONArray("comments") }.getOrNull()
            repeat(arr?.length() ?: 0) { i -> arr?.optJSONObject(i)?.let { addComment(it) } }
            if ((arr?.length() ?: 0) > 0) page++
            loaded = true; loading = false; progress.visibility = View.GONE
        }
    }

    private fun addComment(raw: JSONObject) {
        val comment = Comment.fromJson(raw)
        list.addView(CommentCardRenderer.create(context, comment, scope) { imageId ->
            context.startActivity(Intent(context, ImageDetailActivity::class.java).putExtra("image_id", imageId))
        }, CommentCardRenderer.layoutParams(context))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    fun dispose() { scope.cancel() }
}
