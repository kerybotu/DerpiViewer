package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.R
import com.kerybotu.derpibooru.mirror.auth.ApiKeyStore
import com.kerybotu.derpibooru.mirror.auth.LoginActivity
import com.kerybotu.derpibooru.mirror.model.Comment
import com.kerybotu.derpibooru.mirror.model.Image
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import kotlinx.coroutines.*
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/** First-level "My profile" screen. Other users continue to use ProfileActivity. */
class EmbeddedProfileView(context: Context) : FrameLayout(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val state = TextView(context)
    private val name = TextView(context)
    private val meta = TextView(context)
    private val bio = TextView(context)
    private val avatar = ImageView(context)
    private val stats = LinearLayout(context)
    private val awards = LinearLayout(context)
    private val tabs = LinearLayout(context)
    private val commentsBox = LinearLayout(context)
    private val progress = ProgressBar(context)
    private lateinit var profileHero: LinearLayout
    private lateinit var topIsland: IslandGlassView
    private lateinit var topControls: LinearLayout
    private lateinit var profileContent: LinearLayout
    private lateinit var contentList: RecyclerView
    private lateinit var artworkAdapter: ImageAdapter
    private var viewedUserId: Long? = null
    private var ownUserId: Long? = null
    private var loaded = false

    init { buildLayout() }

    private fun buildLayout() {
        val colors = PaletteManager.colors(context)
        setBackgroundColor(colors.surface)
        profileContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // This is the single full-span header item in contentList. The
            // RecyclerView owns all vertical scrolling, including the grid.
            setPadding(0, 0, 0, dp(24))
        }
        state.apply { text = "正在加载用户资料…"; gravity = Gravity.CENTER; setTextColor(colors.muted); setPadding(0, dp(36), 0, dp(24)) }
        profileContent.addView(state, LinearLayout.LayoutParams(-1, -2))

        profileHero = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(16))
        }
        val header = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        avatar.apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageResource(R.drawable.ic_image_placeholder); contentDescription = "头像" }
        header.addView(avatar, LinearLayout.LayoutParams(dp(96), dp(96)))
        val identity = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        name.apply { textSize = 22f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD); setTextColor(colors.onSurface) }
        meta.apply { textSize = 13f; gravity = Gravity.CENTER; setTextColor(colors.muted); setPadding(0, dp(5), 0, 0) }
        identity.addView(name); identity.addView(meta)
        header.addView(identity, LinearLayout.LayoutParams(-1, -2))
        profileHero.addView(header, LinearLayout.LayoutParams(-1, -2))
        bio.apply { setTextColor(colors.onSurface); visibility = GONE; setPadding(0, dp(16), 0, dp(4)) }
        profileHero.addView(bio, LinearLayout.LayoutParams(-1, -2))
        profileContent.addView(profileHero, LinearLayout.LayoutParams(-1, -2))
        stats.apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(8)) }
        profileContent.addView(stats)

        val awardScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; visibility = GONE; addView(awards) }
        awards.apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        profileContent.addView(awardScroll, LinearLayout.LayoutParams(-1, -2))
        tabs.apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(8)) }
        profileContent.addView(tabs)
        commentsBox.apply { orientation = LinearLayout.VERTICAL; visibility = GONE }
        profileContent.addView(commentsBox)

        artworkAdapter = ImageAdapter(emptyList(), { image -> context.startActivity(Intent(context, ImageDetailActivity::class.java).putExtra("image", image)) }, settingsContext = context)
        val grid = GridLayoutManager(context, AdaptiveLayoutPolicy.artworkColumnCount(context))
        val headerAdapter = ProfileHeaderAdapter(profileContent)
        val list = RecyclerView(context).apply {
            layoutManager = grid
            clipToPadding = false
            adapter = ConcatAdapter(headerAdapter, artworkAdapter)
            // Header occupies a complete row; every artwork retains the
            // shared ImageAdapter's regular grid cell span.
            grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int = if (position == 0) grid.spanCount else 1
            }
        }
        val hostActivity = context as? android.app.Activity
        if (hostActivity != null) {
            AdaptiveLayoutPolicy.configureArtworkGrid(hostActivity, list, grid)
        } else {
            val horizontal = dp(16)
            list.setPadding(horizontal, 0, horizontal, 0)
        }
        // A single, match-parent RecyclerView makes the profile header and
        // every artwork part of one continuous scroll range. The former
        // NestedScrollView + fixed-height child grid stopped after 560dp.
        contentList = list
        addView(list, LayoutParams(-1, -1))

        topIsland = buildTopIsland(colors)
        addView(topIsland, LayoutParams(-2, dp(64), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val params = topIsland.layoutParams as LayoutParams
            params.topMargin = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top + dp(12)
            topIsland.layoutParams = params
            updateInitialContentOffset()
            insets
        }
        ViewCompat.requestApplyInsets(this)
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateProfileMotion(recyclerView.computeVerticalScrollOffset())
            }
        })
        topIsland.alpha = 0f; topIsland.scaleX = 0.96f; topIsland.scaleY = 0.96f
        topIsland.post { updateInitialContentOffset(); topIsland.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Ui2DesignSystem.Motion.emphasizedMs).setInterpolator(Ui2DesignSystem.Motion.spring).start() }
        addView(progress, LayoutParams(-2, dp(40), Gravity.CENTER))
        progress.visibility = GONE
    }

    private fun buildTopIsland(colors: com.kerybotu.derpibooru.mirror.PaletteDefinitions.Scheme): IslandGlassView {
        val island = IslandGlassView(context).apply {
            setPalette(colors)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        topControls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumWidth = dp(220)
            setPadding(dp(6), 0, dp(6), 0)
            setBackgroundColor(Color.TRANSPARENT)
        }
        val menu = ImageButton(context).apply {
            setImageResource(R.drawable.ic_menu)
            imageTintList = android.content.res.ColorStateList.valueOf(colors.onSurface)
            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            contentDescription = "打开菜单"
            setOnClickListener { (context as? com.kerybotu.derpibooru.mirror.MainActivity)?.showUnifiedGlassMenu() }
        }
        val title = TextView(context).apply { text = "我的"; gravity = Gravity.CENTER; setTextColor(colors.onSurface); textSize = 16f; setTypeface(typeface, Typeface.BOLD) }
        val more = ImageButton(context).apply {
            setImageResource(R.drawable.ic_more)
            imageTintList = android.content.res.ColorStateList.valueOf(colors.onSurface)
            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            contentDescription = "更多"
            setOnClickListener {
                PopupMenu(context, this).apply {
                    this.menu.add("刷新资料")
                    this.menu.add("设置")
                    setOnMenuItemClickListener { item ->
                        if (item.title == "刷新资料") { loaded = false; loadProfile() }
                        else context.startActivity(Intent(context, SettingsActivity::class.java))
                        true
                    }
                }.show()
            }
        }
        Ui2DesignSystem.applyPressFeedback(menu); Ui2DesignSystem.applyPressFeedback(more)
        topControls.addView(menu, LinearLayout.LayoutParams(dp(48), dp(48)))
        topControls.addView(title, LinearLayout.LayoutParams(0, -1, 1f))
        topControls.addView(more, LinearLayout.LayoutParams(dp(48), dp(48)))
        island.addView(topControls, FrameLayout.LayoutParams(-1, -1))
        return island
    }

    fun applyPalette(colors: com.kerybotu.derpibooru.mirror.PaletteDefinitions.Scheme = PaletteManager.colors(context)) {
        setBackgroundColor(colors.surface)
        state.setTextColor(colors.muted)
        name.setTextColor(colors.glassText)
        meta.setTextColor(colors.glassSecondaryText)
        bio.setTextColor(colors.glassText)
        topIsland.setPalette(colors)
        (topControls.getChildAt(0) as? ImageButton)?.imageTintList = android.content.res.ColorStateList.valueOf(colors.glassText)
        (topControls.getChildAt(1) as? TextView)?.setTextColor(colors.glassText)
        (topControls.getChildAt(2) as? ImageButton)?.imageTintList = android.content.res.ColorStateList.valueOf(colors.glassText)
        for (index in 0 until stats.childCount) {
            (stats.getChildAt(index) as? TextView)?.setTextColor(colors.glassText)
        }
        for (index in 0 until tabs.childCount) {
            (tabs.getChildAt(index) as? Button)?.let(::styleProfileAction)
        }
        fun updateTextColors(view: View) {
            if (view is TextView && view.parent !== tabs && view !== state && view !== name && view !== meta && view !== bio) {
                view.setTextColor(colors.glassText)
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) updateTextColors(view.getChildAt(index))
        }
        updateTextColors(awards)
        artworkAdapter.notifyDataSetChanged()
    }

    private fun updateInitialContentOffset() {
        if (!::contentList.isInitialized || !::topIsland.isInitialized || topIsland.height <= 0) return
        val desired = topIsland.bottom + dp(16)
        if (contentList.paddingTop != desired) {
            contentList.setPadding(contentList.paddingLeft, desired, contentList.paddingRight, contentList.paddingBottom)
        }
    }

    private fun updateProfileMotion(scrollY: Int) {
        if (!::profileHero.isInitialized) return
        val progress = (scrollY.toFloat() / dp(180).coerceAtLeast(1)).coerceIn(0f, 1f)
        profileHero.translationY = -dp(80) * progress
        avatar.scaleX = 1f - 0.42f * progress; avatar.scaleY = 1f - 0.42f * progress
        bio.alpha = 1f - progress
        stats.translationY = -dp(32) * progress; stats.alpha = 1f - 0.75f * progress
        tabs.alpha = 1f - 0.45f * progress
    }

    /** Keeps only the final content breathing room below the grid dynamic. */
    fun setBottomInset(inset: Int) {
        if (!::contentList.isInitialized) return
        val bottom = inset.coerceAtLeast(0) + dp(24)
        if (contentList.paddingBottom != bottom) {
            contentList.setPadding(contentList.paddingLeft, contentList.paddingTop, contentList.paddingRight, bottom)
        }
    }

    /** Full-span profile content before the shared artwork grid. */
    private class ProfileHeaderAdapter(private val header: View) : RecyclerView.Adapter<ProfileHeaderAdapter.Holder>() {
        class Holder(view: View) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            (header.parent as? ViewGroup)?.removeView(header)
            return Holder(header)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) = Unit

        override fun getItemCount(): Int = 1
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { if (topIsland.isShown) topIsland.setRenderingActive(true, contentList) }
        if (!loaded) loadProfile()
    }

    override fun onDetachedFromWindow() {
        if (::topIsland.isInitialized) topIsland.setRenderingActive(false, null)
        super.onDetachedFromWindow()
    }

    private fun loadProfile() = scope.launch {
        progress.visibility = VISIBLE
        val id = viewedUserId ?: resolveOwnId()
        if (id == null) { progress.visibility = GONE; showError("请先登录以查看我的资料"); return@launch }
        viewedUserId = id
        val user = withContext(Dispatchers.IO) { NetworkManager.getApi(context, "profiles/$id")?.let { JSONObject(it).optJSONObject("user") } }
        if (user == null) { progress.visibility = GONE; showError("用户资料加载失败，点击重试"); return@launch }
        bindUser(user); loaded = true; progress.visibility = GONE
    }

    private suspend fun resolveOwnId(): Long? {
        ownUserId = ApiKeyStore.getUserId(context)
        ownUserId?.let { return it }
        if (!ApiKeyStore.isLoggedIn(context)) return null
        val raw = withContext(Dispatchers.IO) { NetworkManager.getApi(context, "filters/user") }
        val id = runCatching { JSONObject(raw.orEmpty()).optJSONArray("filters")?.optJSONObject(0)?.optLong("user_id", -1) }.getOrNull()?.takeIf { it > 0 }
        id?.let { ApiKeyStore.saveUserId(context, it); ownUserId = it }
        return id
    }

    private fun bindUser(user: JSONObject) {
        val colors = PaletteManager.colors(context)
        state.visibility = GONE
        name.text = user.optString("name", "未知用户")
        val role = user.optString("role").takeIf { it.isNotBlank() && it != "user" }?.let { " · $it" }.orEmpty()
        val joined = user.optString("created_at").take(7).takeIf { it.isNotBlank() }?.let { " · 加入于 $it" }.orEmpty()
        meta.text = (role + joined).trimStart(' ', '·').trim().ifBlank { "Derpibooru 用户" }
        user.optString("avatar_url").takeIf { it.isNotBlank() }?.let { CdnImageGate.load(avatar, it, AppSettings.getCdnThreads(context)) }
        user.optString("description").takeIf { it.isNotBlank() }?.let { bio.text = it; bio.visibility = VISIBLE }
        stats.removeAllViews()
        stat("上传", user.optInt("uploads_count")) { loadImages("uploader_id:$viewedUserId") }
        stat("评论", user.optInt("comments_count")) { loadComments() }
        stat("帖子", user.optInt("posts_count")) { toast("帖子浏览即将开放") }
        stat("主题", user.optInt("topics_count")) { toast("主题浏览即将开放") }
        awards.removeAllViews()
        val awardScroll = awards.parent as? View
        val awardArray = user.optJSONArray("awards")
        awardScroll?.visibility = if (awardArray != null && awardArray.length() > 0) VISIBLE else GONE
        if (awardArray != null) repeat(awardArray.length()) { index ->
            val award = awardArray.optJSONObject(index) ?: return@repeat
            val item = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, 0, dp(12), 0) }
            val icon = WebView(context).apply { settings.javaScriptEnabled = false; settings.domStorageEnabled = false; setBackgroundColor(Color.TRANSPARENT); contentDescription = award.optString("title", "徽章") }
            award.optString("image_url").takeIf { it.isNotBlank() }?.let { rawUrl -> loadAwardSvg(icon, if (rawUrl.startsWith("http")) rawUrl else "https://${AppSettings.getTargetDomain(context)}/${rawUrl.trimStart('/')}") }
            item.addView(icon, LinearLayout.LayoutParams(dp(60), dp(60)))
            item.addView(TextView(context).apply { text = award.optString("title", award.optString("label", "徽章")); textSize = 11f; maxLines = 2; gravity = Gravity.CENTER; setTextColor(colors.onSurface) }, LinearLayout.LayoutParams(dp(88), -2))
            awards.addView(item)
        }
        tabs.removeAllViews()
        tab("上传") { loadImages("uploader_id:$viewedUserId") }
        tab("评论") { loadComments() }
        if (viewedUserId == ownUserId) { tab("收藏") { loadImages("my:faves") }; tab("关注") { loadImages("my:watched") } }
        loadImages("uploader_id:$viewedUserId")
    }

    private fun stat(label: String, count: Int, action: () -> Unit) {
        stats.addView(TextView(context).apply { text = "$count\n$label"; gravity = Gravity.CENTER; setTextColor(PaletteManager.colors(context).glassText); setPadding(dp(4), dp(4), dp(4), dp(4)); setOnClickListener { action() } }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun tab(label: String, action: () -> Unit) {
        tabs.addView(Button(context).apply { text = label; styleProfileAction(this); setOnClickListener { action() } }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
    }

    private fun styleProfileAction(button: Button) {
        val colors = Ui2DesignSystem.colors(context)
        button.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(colors.glassTint)
            setStroke(dp(1), colors.glassBorder)
        }
        button.backgroundTintList = null
        button.setTextColor(PaletteManager.colors(context).glassText)
        button.minimumHeight = dp(48)
        button.minHeight = dp(48)
        button.setPadding(dp(8), 0, dp(8), 0)
        button.textSize = 13f
        Ui2DesignSystem.applyPressFeedback(button)
    }

    private fun loadImages(query: String) = scope.launch {
        progress.visibility = VISIBLE; commentsBox.visibility = GONE
        val q = URLEncoder.encode(query, "UTF-8")
        val raw = withContext(Dispatchers.IO) { NetworkManager.getApi(context, "search/images?q=$q&sf=first_seen_at&sd=desc&per_page=50${NetworkManager.currentFilterParam(context)}") }
        artworkAdapter.updateData(parseImages(raw.orEmpty())); progress.visibility = GONE
    }

    private fun parseImages(raw: String): List<Image> {
        val array = runCatching { JSONObject(raw).optJSONArray("images") }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let { image ->
            val reps = image.optJSONObject("representations")
            Image(image.optInt("id"), "", reps?.optString("small", null), image.optInt("width"), image.optInt("height"), image.optInt("score"), image.optInt("faves"), image.optInt("upvotes"), image.optInt("downvotes"), image.optInt("comment_count"), emptyList(), reps?.optString("full", null), image.optString("uploader", null), image.optString("created_at", null), image.optString("description", null), image.optString("mime_type", null), image.optLong("uploader_id", -1).takeIf { it > 0 }, image.optBoolean("spoilered", false))
        } }
    }

    private fun loadComments() = scope.launch {
        val id = viewedUserId ?: return@launch
        progress.visibility = VISIBLE; commentsBox.visibility = VISIBLE; commentsBox.removeAllViews()
        val colors = PaletteManager.colors(context)
        commentsBox.addView(TextView(context).apply { text = "最近评论"; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(colors.onSurface); setPadding(0, dp(8), 0, dp(8)) })
        val q = URLEncoder.encode("user_id:$id", "UTF-8")
        val raw = withContext(Dispatchers.IO) { NetworkManager.getApi(context, "search/comments?q=$q&page=1&per_page=50") }
        val array = runCatching { JSONObject(raw.orEmpty()).optJSONArray("comments") }.getOrNull()
        if (array == null || array.length() == 0) commentsBox.addView(TextView(context).apply { text = "暂无评论"; setTextColor(colors.muted) })
        else repeat(array.length()) { index -> array.optJSONObject(index)?.let { rawComment ->
            commentsBox.addView(CommentCardRenderer.create(context, Comment.fromJson(rawComment), scope) { imageId -> context.startActivity(Intent(context, ImageDetailActivity::class.java).putExtra("image_id", imageId)) }, CommentCardRenderer.layoutParams(context))
        } }
        progress.visibility = GONE
    }

    private fun loadAwardSvg(target: WebView, url: String) = scope.launch {
        val svg = withContext(Dispatchers.IO) { runCatching { val client = NetworkManager.imageHttpClient() ?: return@runCatching null; client.newCall(Request.Builder().url(url).build()).execute().use { response -> if (!response.isSuccessful) return@use null; response.body?.string() } }.getOrNull() }
        svg?.let { html -> target.loadDataWithBaseURL(url, "<html><head><meta name='viewport' content='width=device-width,height=device-height,initial-scale=1'/><style>html,body{width:100%;height:100%;margin:0;padding:0;overflow:hidden;background:transparent}body{display:flex;align-items:center;justify-content:center}svg{display:block;width:100% !important;height:100% !important;max-width:100%;max-height:100%;object-fit:contain}</style></head><body>$html</body></html>", "text/html", "UTF-8", null) }
    }

    private fun showError(message: String) {
        state.visibility = VISIBLE; state.text = message
        state.setOnClickListener { if (!ApiKeyStore.isLoggedIn(context)) context.startActivity(Intent(context, LoginActivity::class.java)) else { loaded = false; loadProfile() } }
    }

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    fun dispose() { scope.cancel() }
}
