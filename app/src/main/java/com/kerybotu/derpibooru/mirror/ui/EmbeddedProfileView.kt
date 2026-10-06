package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.widget.*
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.R
import com.kerybotu.derpibooru.mirror.auth.ApiKeyStore
import com.kerybotu.derpibooru.mirror.model.Image
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URLEncoder

/** First-level "My profile" screen. Other users continue to use ProfileActivity. */
class EmbeddedProfileView(context: Context) : FrameLayout(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val state = TextView(context)
    private val name = TextView(context)
    private val stats = TextView(context)
    private val progress = ProgressBar(context)
    private val adapter = ImageAdapter(emptyList(), { image ->
        context.startActivity(Intent(context, ImageDetailActivity::class.java).putExtra("image", image))
    }, settingsContext = context)
    private var loaded = false

    init {
        val colors = PaletteManager.colors(context)
        setBackgroundColor(colors.surface)
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(SafeToolbar(context).apply { title = "我的"; navigationIcon = null }, LinearLayout.LayoutParams(-1, dp(56)))
        val scroll = ScrollView(context)
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24)) }
        state.text = "正在加载用户资料…"; state.gravity = Gravity.CENTER; state.setTextColor(colors.muted)
        name.textSize = 22f; name.setTextColor(colors.onSurface)
        stats.setTextColor(colors.muted); stats.setPadding(0, dp(8), 0, dp(16))
        content.addView(state); content.addView(name); content.addView(stats)
        val list = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, AdaptiveLayoutPolicy.artworkColumnCount(context))
            isNestedScrollingEnabled = false; adapter = this@EmbeddedProfileView.adapter
        }
        AdaptiveLayoutPolicy.configureArtworkGrid(context as android.app.Activity, list)
        content.addView(list, LinearLayout.LayoutParams(-1, dp(520)))
        scroll.addView(content); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(progress, LinearLayout.LayoutParams(-2, dp(40)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        addView(root, LayoutParams(-1, -1)); progress.visibility = GONE
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!loaded) load() }

    private fun load() = scope.launch {
        val userId = ApiKeyStore.getUserId(context)
        if (userId == null) { state.text = "请先登录以查看我的资料"; return@launch }
        progress.visibility = VISIBLE
        val user = withContext(Dispatchers.IO) { NetworkManager.getApi(context, "profiles/$userId")?.let { JSONObject(it).optJSONObject("user") } }
        if (user == null) { state.text = "用户资料加载失败"; progress.visibility = GONE; return@launch }
        state.visibility = GONE; name.text = user.optString("name", "未知用户")
        stats.text = "上传 ${user.optInt("uploads_count")}   评论 ${user.optInt("comments_count")}   收藏 ${user.optInt("faves_count")}"
        val q = URLEncoder.encode("uploader_id:$userId", "UTF-8")
        val raw = withContext(Dispatchers.IO) { NetworkManager.getApi(context, "search/images?q=$q&per_page=50${NetworkManager.currentFilterParam(context)}") }
        val array = runCatching { JSONObject(raw.orEmpty()).optJSONArray("images") }.getOrNull()
        adapter.updateData((0 until (array?.length() ?: 0)).mapNotNull { i ->
            array?.optJSONObject(i)?.let { o ->
                val r = o.optJSONObject("representations")
                Image(o.optInt("id"), "", r?.optString("small", null), o.optInt("width"), o.optInt("height"), o.optInt("score"), o.optInt("faves"), o.optInt("upvotes"), o.optInt("downvotes"), o.optInt("comment_count"), emptyList(), r?.optString("full", null), o.optString("uploader", null), o.optString("created_at", null), o.optString("description", null), o.optString("mime_type", null), userId, o.optBoolean("spoilered", false))
            }
        })
        loaded = true; progress.visibility = GONE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    fun dispose() { scope.cancel() }
}
