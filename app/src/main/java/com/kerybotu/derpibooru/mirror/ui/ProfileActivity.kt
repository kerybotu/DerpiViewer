package com.kerybotu.derpibooru.mirror.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import android.webkit.WebView
import android.webkit.WebSettings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.R
import com.kerybotu.derpibooru.mirror.auth.ApiKeyStore
import com.kerybotu.derpibooru.mirror.auth.LoginActivity
import com.kerybotu.derpibooru.mirror.model.Image
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URLEncoder
import okhttp3.Request

/** Shared user profile screen. Omit [EXTRA_USER_ID] to display the signed-in account. */
class ProfileActivity : AppCompatActivity() {
    companion object { const val EXTRA_USER_ID = "user_id" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var viewedUserId: Long? = null
    private var ownUserId: Long? = null
    private var own = false
    private lateinit var state: TextView; private lateinit var nameView: TextView; private lateinit var metaView: TextView
    private lateinit var bioView: TextView; private lateinit var avatar: ImageView; private lateinit var stats: LinearLayout
    private lateinit var awards: LinearLayout; private lateinit var links: LinearLayout; private lateinit var tabs: LinearLayout; private lateinit var commentsBox: LinearLayout
    private lateinit var adapter: ImageAdapter; private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); PaletteManager.apply(this)
        viewedUserId = intent.getLongExtra(EXTRA_USER_ID, -1).takeIf { it > 0 }; ownUserId = ApiKeyStore.getUserId(this)
        setContentView(buildView()); loadProfile()
    }

    private fun buildView(): View {
        val colors = PaletteManager.colors(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(colors.surface) }
        root.addView(SafeToolbar(this).apply { title = if (viewedUserId == null) "我的" else "用户资料"; setNavigationIcon(R.drawable.ic_arrow_back); setNavigationOnClickListener { finish() } }, LinearLayout.LayoutParams(-1, dp(56)))
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24)) }
        state = TextView(this).apply { text = "正在加载用户资料…"; gravity = Gravity.CENTER; setTextColor(colors.muted); setPadding(0, dp(28), 0, dp(18)) }; content.addView(state)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        avatar = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageResource(R.drawable.ic_image_placeholder) }; header.addView(avatar, LinearLayout.LayoutParams(dp(72), dp(72)))
        val identity = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0) }
        nameView = TextView(this).apply { textSize = 22f; setTextColor(colors.onSurface) }; metaView = TextView(this).apply { textSize = 13f; setTextColor(colors.muted); setPadding(0, dp(5), 0, 0) }
        identity.addView(nameView); identity.addView(metaView); header.addView(identity); content.addView(header)
        bioView = TextView(this).apply { visibility = View.GONE; setTextColor(colors.onSurface); setPadding(0, dp(16), 0, dp(4)) }; content.addView(bioView)
        stats = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(8)) }; content.addView(stats)
        awards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        content.addView(HorizontalScrollView(this).apply {
            visibility = View.GONE
            isHorizontalScrollBarEnabled = false
            addView(awards)
            tag = "awards_scroll"
        })
        // Links contain only tag_id and would require an extra tag lookup per item;
        // omit the entire section to avoid unnecessary API traffic.
        links = LinearLayout(this)
        tabs = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(8)) }; content.addView(tabs)
        commentsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }; content.addView(commentsBox)
        val list = RecyclerView(this).apply { layoutManager = GridLayoutManager(this@ProfileActivity, 2); isNestedScrollingEnabled = false }
        adapter = ImageAdapter(emptyList(), { startActivity(Intent(this, ImageDetailActivity::class.java).putExtra("image", it)) }); list.adapter = adapter; content.addView(list, LinearLayout.LayoutParams(-1, -2))
        root.addView(NestedScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        progress = ProgressBar(this); root.addView(progress, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        return root
    }

    private fun loadProfile() = scope.launch {
        val id = viewedUserId ?: resolveOwnId()
        if (id == null) { error("请先登录，或暂时无法识别当前账户"); return@launch }
        viewedUserId = id; own = id == ownUserId
        val user = withContext(Dispatchers.IO) { NetworkManager.getApi(this@ProfileActivity, "profiles/$id")?.let { JSONObject(it).optJSONObject("user") } }
        if (user == null) error("该用户不存在或加载失败，点击重试") else bind(user)
    }

    private suspend fun resolveOwnId(): Long? {
        ownUserId?.let { return it }; if (!ApiKeyStore.isLoggedIn(this)) return null
        val raw = withContext(Dispatchers.IO) { NetworkManager.getApi(this@ProfileActivity, "filters/user") }
        val id = runCatching { JSONObject(raw.orEmpty()).optJSONArray("filters")?.optJSONObject(0)?.optLong("user_id", -1) }.getOrNull()?.takeIf { it > 0 } ?: return null
        ApiKeyStore.saveUserId(this, id); ownUserId = id; return id
    }

    private fun bind(user: JSONObject) {
        progress.visibility = View.GONE; state.visibility = View.GONE; val c = PaletteManager.colors(this)
        nameView.text = user.optString("name", "未知用户")
        val role = user.optString("role").takeIf { it.isNotBlank() && it != "user" }?.let { " · $it" }.orEmpty()
        metaView.text = "加入于 ${user.optString("created_at").take(7)}$role"
        user.optString("avatar_url").takeIf { it.isNotBlank() }?.let { CdnImageGate.load(avatar, it, AppSettings.getCdnThreads(this)) }
        user.optString("description").takeIf { it.isNotBlank() }?.let { bioView.text = it; bioView.visibility = View.VISIBLE }
        stats.removeAllViews(); stat("上传", user.optInt("uploads_count")) { loadImages("uploader_id:$viewedUserId") }; stat("评论", user.optInt("comments_count")) { comments() }; stat("帖子", user.optInt("posts_count")) { toast("帖子浏览即将开放") }; stat("主题", user.optInt("topics_count")) { toast("主题浏览即将开放") }
        user.optJSONArray("awards")?.takeIf { it.length() > 0 }?.let { array ->
            val awardScroll = (awards.parent as View).apply { visibility = View.VISIBLE }
            awards.removeAllViews()
            repeat(array.length()) { i -> array.optJSONObject(i)?.let { award ->
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, 0, dp(12), 0)
                }
                val icon = WebView(this).apply {
                    settings.javaScriptEnabled = false
                    settings.domStorageEnabled = false
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    contentDescription = award.optString("title")
                }
                award.optString("image_url").takeIf { it.isNotBlank() }?.let { rawUrl ->
                    // Award URLs may be absolute CDN URLs or site-relative paths.
                    val url = if (rawUrl.startsWith("http")) rawUrl else "https://${AppSettings.getTargetDomain(this)}/${rawUrl.trimStart('/')}"
                    loadAwardSvg(icon, url)
                }
                item.addView(icon, LinearLayout.LayoutParams(dp(60), dp(60)))
                item.addView(TextView(this).apply {
                    text = award.optString("title", award.optString("label", "徽章"))
                    textSize = 11f
                    maxLines = 2
                    gravity = Gravity.CENTER
                    setTextColor(c.onSurface)
                }, LinearLayout.LayoutParams(dp(88), -2))
                awards.addView(item)
            } }
        }
        tabs.removeAllViews(); tab("上传") { loadImages("uploader_id:$viewedUserId") }; tab("评论") { comments() }; if (own) { tab("收藏") { loadImages("my:faves") }; tab("关注") { loadImages("my:watched") } }
        loadImages("uploader_id:$viewedUserId")
    }

    private fun stat(label: String, count: Int, action: () -> Unit) { stats.addView(TextView(this).apply { text = "$count\n$label"; gravity = Gravity.CENTER; setTextColor(PaletteManager.colors(this@ProfileActivity).onSurface); setOnClickListener { action() } }, LinearLayout.LayoutParams(0, -2, 1f)) }
    private fun tab(label: String, action: () -> Unit) { tabs.addView(Button(this).apply { text = label; setOnClickListener { action() } }, LinearLayout.LayoutParams(0, -2, 1f)) }
    private fun loadImages(query: String) = scope.launch {
        progress.visibility = View.VISIBLE; val q = URLEncoder.encode(query, "UTF-8")
        val raw = withContext(Dispatchers.IO) { NetworkManager.getApi(this@ProfileActivity, "search/images?q=$q&sf=first_seen_at&sd=desc&per_page=50${NetworkManager.currentFilterParam(this@ProfileActivity)}") }
        val array = runCatching { JSONObject(raw.orEmpty()).optJSONArray("images") }.getOrNull()
        adapter.updateData((0 until (array?.length() ?: 0)).mapNotNull { i -> array?.optJSONObject(i)?.let { o -> val r = o.optJSONObject("representations"); Image(o.optInt("id"), "", r?.optString("small", null), o.optInt("width"), o.optInt("height"), o.optInt("score"), o.optInt("faves"), o.optInt("upvotes"), o.optInt("downvotes"), o.optInt("comment_count"), emptyList(), r?.optString("full", null), o.optString("uploader", null), o.optString("created_at", null), o.optString("description", null), o.optString("mime_type", null), o.optLong("uploader_id", -1).takeIf { it > 0 }) } }); progress.visibility = View.GONE
    }
    private fun comments() = scope.launch {
        val id = viewedUserId ?: return@launch
        commentsBox.visibility = View.VISIBLE; commentsBox.removeAllViews()
        val colors = PaletteManager.colors(this@ProfileActivity)
        commentsBox.addView(TextView(this@ProfileActivity).apply { text = "最近评论"; textSize = 18f; setTextColor(colors.onSurface); setPadding(0, dp(8), 0, dp(8)) })
        val q = URLEncoder.encode("user_id:$id", "UTF-8")
        val raw = withContext(Dispatchers.IO) { NetworkManager.getApi(this@ProfileActivity, "search/comments?q=$q&page=1&per_page=50") }
        val array = runCatching { JSONObject(raw.orEmpty()).optJSONArray("comments") }.getOrNull()
        if (array == null || array.length() == 0) { commentsBox.addView(TextView(this@ProfileActivity).apply { text = "暂无评论"; setTextColor(colors.muted) }); return@launch }
        repeat(array.length()) { index -> array.optJSONObject(index)?.let { comment ->
            val card = TextView(this@ProfileActivity).apply { text = "${comment.optString("created_at").take(10)}\n${comment.optString("body")}"; textSize = 14f; setTextColor(colors.onSurface); setPadding(dp(12), dp(10), dp(12), dp(10)); setBackgroundColor(colors.surfaceVariant) }
            commentsBox.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) })
        } }
    }
    private fun loadAwardSvg(target: WebView, url: String) = scope.launch {
        val svg = withContext(Dispatchers.IO) {
            runCatching {
                val client = NetworkManager.imageHttpClient() ?: return@runCatching null
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body?.string()
                }
            }.getOrNull()
        }
        svg?.let {
            val html = "<html><head><meta name='viewport' content='width=device-width,height=device-height,initial-scale=1'/><style>html,body{width:100%;height:100%;margin:0;padding:0;overflow:hidden;background:transparent}body{display:flex;align-items:center;justify-content:center}svg{display:block;width:100% !important;height:100% !important;max-width:100%;max-height:100%;object-fit:contain}</style></head><body>$it</body></html>"
            target.loadDataWithBaseURL(url, html, "text/html", "UTF-8", null)
        }
    }
    private fun error(text: String) {
        progress.visibility = View.GONE; state.text = text
        state.setOnClickListener {
            if (!ApiKeyStore.isLoggedIn(this) && viewedUserId == null) startActivity(Intent(this, LoginActivity::class.java)) else loadProfile()
        }
    }
    override fun onResume() { super.onResume(); if (viewedUserId == null && ownUserId == null && ApiKeyStore.isLoggedIn(this)) { ownUserId = ApiKeyStore.getUserId(this); loadProfile() } }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
