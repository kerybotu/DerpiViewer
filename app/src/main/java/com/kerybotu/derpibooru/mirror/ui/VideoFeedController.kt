package com.kerybotu.derpibooru.mirror.ui

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.favorites.FavoriteItem
import com.kerybotu.derpibooru.mirror.favorites.LocalFavoritesStore
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import kotlinx.coroutines.*
import org.json.JSONObject

class VideoFeedController(
    private val context: Context,
    private val adapter: VideoFeedAdapter,
    private val pager: ViewPager2,
    private val pagerRecycler: RecyclerView,
    private val onEmptyView: (Boolean, String) -> Unit,
    private val onLoadingView: (Boolean) -> Unit
) : VideoFeedAdapter.Actions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val playerPool = VideoPlayerPool(context)
    
    var currentPosition = 0
    var page = 1
    var loading = false
    var sort = "random:${System.currentTimeMillis() / 1000L}"
    var sortDirection = "desc"
    var muted = !AppSettings.isVideoAudioEnabled(context)
    var filterQuery = ""
    var recentFeatured = false
    val numericFilters = mutableListOf<NumericFilter>()

    init {
        playerPool.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(context, "视频播放失败：${error.errorCodeName}", Toast.LENGTH_LONG).show()
            }
        })
        playerPool.setMuted(muted)
    }

    fun loadNextPage() {
        if (loading) return
        loading = true
        onLoadingView(true)
        scope.launch {
            if (!ensureNetworkReady()) {
                loading = false
                onLoadingView(false)
                if (adapter.itemCount == 0) onEmptyView(true, "网络初始化失败，无法加载视频")
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                val filter = NetworkManager.currentFilterParam(context)
                val query = java.net.URLEncoder.encode(buildQuery(), "UTF-8")
                val direction = if (sort.startsWith("random:")) "" else "&sd=$sortDirection"
                NetworkManager.getApi(context, "search/images?q=$query&per_page=50&page=$page&sf=$sort$direction$filter")
            }
            val posts = result?.let(::parseVideoPosts).orEmpty()
            if (page == 1) adapter.replace(posts) else adapter.append(posts)
            if (posts.isNotEmpty()) page++
            onEmptyView(adapter.itemCount == 0, if (adapter.itemCount == 0) "没有可播放的视频" else "")
            loading = false
            onLoadingView(false)
            if (adapter.itemCount > 0) {
                pager.post { activate(currentPosition, automatic = true) }
            }
        }
    }

    private suspend fun ensureNetworkReady(): Boolean {
        if (NetworkManager.isReady()) return true
        return runCatching {
            NetworkManager.init(context.applicationContext)
            NetworkManager.isReady()
        }.getOrDefault(false)
    }

    private fun parseVideoPosts(json: String): List<VideoPost> {
        val images = JSONObject(json).optJSONArray("images") ?: return emptyList()
        return buildList {
            for (index in 0 until images.length()) {
                val item = images.optJSONObject(index) ?: continue
                val mime = item.optString("mime_type").lowercase()
                if (mime != "video/webm" && mime != "video/mp4") continue
                val reps = item.optJSONObject("representations")
                val url = reps?.optString("full").takeUnless { it.isNullOrBlank() }
                    ?: reps?.optString("large").takeUnless { it.isNullOrBlank() }
                    ?: continue
                val thumbnail = reps?.optString("small")?.takeIf { it.isNotBlank() }
                    ?: reps?.optString("thumb")?.takeIf { it.isNotBlank() }
                val tags = item.optJSONArray("tags")?.let { tagsArray ->
                    List(tagsArray.length()) { tagsArray.optString(it) }.filter { it.isNotBlank() }
                }.orEmpty()
                add(VideoPost(
                    id = item.optInt("id"), url = url,
                    thumbnailUrl = thumbnail,
                    mimeType = mime,
                    width = item.optInt("width"), height = item.optInt("height"),
                    uploader = item.optString("uploader", "未知上传者"), tags = tags,
                    upvotes = item.optInt("upvotes"), downvotes = item.optInt("downvotes"),
                    commentCount = item.optInt("comment_count")
                ))
            }
        }
    }

    fun activate(position: Int, automatic: Boolean) {
        val current = adapter.item(position) ?: return
        playerPool.retainOnly(setOf(position))
        scope.launch {
            if (!ensureNetworkReady()) return@launch
            if (position != currentPosition) return@launch
            attachPlayer(position, current, automatic && canAutoPlay())
            val forwardUrls = (1..4).mapNotNull { offset -> adapter.item(position + offset)?.url }
            playerPool.prefetch(forwardUrls, prefetchScope)
        }
    }

    private fun attachPlayer(position: Int, post: VideoPost, play: Boolean) {
        val player = playerPool.prepare(position, post.url)
        bindPlayerView(position, player, play, attempt = 0)
    }

    private fun bindPlayerView(position: Int, player: Player, play: Boolean, attempt: Int) {
        if (position != currentPosition) return
        val holder = pagerRecycler.findViewHolderForAdapterPosition(position) as? VideoFeedAdapter.Holder
        if (holder == null) {
            if (attempt >= 12) return
            pagerRecycler.post { bindPlayerView(position, player, play, attempt + 1) }
            return
        }
        val attached = holder.binding.videoPlayer
        if (attached.player !== player) {
            attached.player = player
            attached.useController = false
            holder.binding.videoPlayProgress.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val duration = player.duration
                    if (duration > 0L) player.seekTo(duration * progress / 1000L)
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) = Unit
            })
        }
        if (play) player.playWhenReady = true
    }

    private fun canAutoPlay(): Boolean {
        if (!AppSettings.isVideoWifiOnly(context)) return true
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = cm?.getNetworkCapabilities(cm.activeNetwork)
        return capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    fun toggleMute() {
        muted = !muted
        playerPool.setMuted(muted)
        Toast.makeText(context, if (muted) "已静音" else "已打开声音", Toast.LENGTH_SHORT).show()
    }

    fun showSortMenu(anchor: View) {
        PopupMenu(context, anchor).apply {
            menu.add("随机")
            menu.add("首次收录时间")
            menu.add("图片ID")
            menu.add("最后修改时间")
            menu.add("收藏数")
            menu.add("点赞数")
            menu.add("点踩数")
            menu.add("评分")
            menu.add("Wilson评分")
            menu.add("相关度")
            menu.add("评论数")
            menu.add("标签数量")
            menu.add("像素数")
            menu.add("文件大小")
            menu.add("时长")
            menu.add("筛选条件")
            if (!sort.startsWith("random:")) {
                menu.addSubMenu("排序方向").apply {
                    add("升序")
                    add("降序")
                }
            }
            setOnMenuItemClickListener {
                if (it.title == "筛选条件") {
                    showFilterSheet()
                    return@setOnMenuItemClickListener true
                }
                if (it.title == "升序" || it.title == "降序") {
                    sortDirection = if (it.title == "升序") "asc" else "desc"
                    reloadFeed()
                    return@setOnMenuItemClickListener true
                }
                sort = when (it.title.toString()) {
                    "随机" -> "random:${System.currentTimeMillis() / 1000L}"
                    "首次收录时间" -> "first_seen_at"
                    "图片ID" -> "id"
                    "最后修改时间" -> "updated_at"
                    "收藏数" -> "faves"
                    "点赞数" -> "upvotes"
                    "点踩数" -> "downvotes"
                    "评分" -> "score"
                    "Wilson评分" -> "wilson_score"
                    "相关度" -> "_score"
                    "评论数" -> "comment_count"
                    "标签数量" -> "tag_count"
                    "像素数" -> "pixels"
                    "文件大小" -> "size"
                    "时长" -> "duration"
                    else -> sort
                }
                sortDirection = "desc"
                reloadFeed(); true
            }
        }.show()
    }

    data class NumericFilter(val field: String, val comparator: String, val value: String) {
        fun queryPart() = "$field$comparator:$value"
    }

    private fun buildQuery(): String {
        val parts = mutableListOf("animated")
        normalizeFilterQuery(filterQuery).forEach(parts::add)
        if (recentFeatured) {
            parts += "first_seen_at.gt:3 days ago"
            parts += "-ai generated"
            parts += "-ai composition"
        }
        numericFilters.mapTo(parts) { it.queryPart() }
        return parts.joinToString(",")
    }

    private fun normalizeFilterQuery(raw: String): List<String> = raw
        .replace('＞', '>').replace('＜', '<').replace("≥", ">=").replace("≤", "<=")
        .split(',')
        .mapNotNull { token ->
            val value = token.trim().replace(Regex("\\s+"), " ")
            if (value.isBlank()) return@mapNotNull null
            val match = Regex("^([A-Za-z_][A-Za-z0-9_]*)\\s*(?::\\s*)?(>=|<=|>|<)\\s*(.+)$").matchEntire(value)
            if (match != null) {
                val op = when (match.groupValues[2]) { ">=" -> ".gte"; "<=" -> ".lte"; ">" -> ".gt"; else -> ".lt" }
                "${match.groupValues[1]}$op:${match.groupValues[3].trim()}"
            } else value
        }

    fun reloadFeed() {
        page = 1
        currentPosition = 0
        playerPool.releaseAll()
        adapter.replace(emptyList())
        pager.setCurrentItem(0, false)
        loadNextPage()
    }

    private fun showFilterSheet() {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(28)) }
        val query = EditText(context).apply { hint = "输入标签或搜索条件"; setText(filterQuery); setSingleLine(true) }
        val featured = CheckBox(context).apply { text = "近期精选（近3天，排除 AI 生成内容）"; isChecked = recentFeatured }
        val filters = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun renderFilters() {
            filters.removeAllViews()
            numericFilters.forEachIndexed { index, item ->
                filters.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(android.widget.TextView(context).apply { text = "${numericFieldLabel(item.field)} ${comparatorLabel(item.comparator)} ${item.value}" }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(android.widget.Button(context).apply { text = "删除"; setOnClickListener { numericFilters.removeAt(index); renderFilters() } })
                })
            }
        }
        root.addView(android.widget.TextView(context).apply { text = "筛选条件"; textSize = 20f })
        root.addView(query)
        root.addView(featured)
        root.addView(android.widget.Button(context).apply { text = "+ 添加数值筛选"; setOnClickListener { showNumericFilterPicker { numericFilters += it; renderFilters() } } })
        root.addView(filters); renderFilters()
        val apply = android.widget.Button(context).apply { text = "应用筛选" }
        root.addView(apply)
        BottomSheetDialog(context).apply { setContentView(root); apply.setOnClickListener { filterQuery = query.text.toString().trim(); recentFeatured = featured.isChecked; dismiss(); reloadFeed() }; show() }
    }

    private fun showNumericFilterPicker(done: (NumericFilter) -> Unit) {
        val fields = listOf("评分" to "score", "收藏数" to "faves", "点赞数" to "upvotes", "点踩数" to "downvotes", "评论数" to "comment_count", "时长" to "duration", "像素数" to "pixels", "文件大小" to "size")
        val comparators = listOf("≥" to ".gte", "≤" to ".lte", ">" to ".gt", "<" to ".lt")
        val row = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), 0) }
        val field = Spinner(context).apply { adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, fields.map { it.first }) }
        val comparator = Spinner(context).apply { adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, comparators.map { it.first }) }
        val value = EditText(context).apply { hint = "数值"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
        row.addView(field); row.addView(comparator); row.addView(value)
        BottomSheetDialog(context).apply { setContentView(row); setOnShowListener { row.addView(android.widget.Button(context).apply { text = "添加"; setOnClickListener { val v = value.text.toString().trim(); if (v.isNotEmpty()) { done(NumericFilter(fields[field.selectedItemPosition].second, comparators[comparator.selectedItemPosition].second, v)); dismiss() } } }) }; show() }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun numericFieldLabel(field: String) = mapOf("score" to "评分", "faves" to "收藏数", "upvotes" to "点赞数", "downvotes" to "点踩数", "comment_count" to "评论数", "duration" to "时长", "pixels" to "像素数", "size" to "文件大小")[field] ?: field
    private fun comparatorLabel(value: String) = mapOf(".gte" to "≥", ".lte" to "≤", ".gt" to ">", ".lt" to "<")[value] ?: value

    override fun onToggle(position: Int) {
        val post = adapter.item(position) ?: return
        val player = playerPool.prepare(position, post.url)
        player.playWhenReady = !player.isPlaying
    }
    override fun onDoubleTap(position: Int) = onUpvote(position)
    override fun onLongPress(position: Int, active: Boolean) {
        playerPool.setSpeed(position, if (active) 2f else 1f)
        (pagerRecycler.findViewHolderForAdapterPosition(position) as? VideoFeedAdapter.Holder)?.binding?.videoSpeed?.visibility = if (active) View.VISIBLE else View.GONE
    }
    override fun onUpvote(position: Int) { adapter.item(position)?.let { it.upvotes++; adapter.notifyItemChanged(position); pagerRecycler.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) } }
    override fun onDownvote(position: Int) { adapter.item(position)?.let { it.downvotes++; adapter.notifyItemChanged(position); Toast.makeText(context, "已记录踩", Toast.LENGTH_SHORT).show() } }
    override fun onFavorite(position: Int) {
        val post = adapter.item(position) ?: return
        LocalFavoritesStore(context).allFolders().firstOrNull { it.isDefault }?.let { folder -> LocalFavoritesStore(context).add(folder.id, FavoriteItem(post.id, post.thumbnailUrl ?: post.url, post.mimeType)) }
        Toast.makeText(context, "已收藏到本地", Toast.LENGTH_SHORT).show()
    }
    override fun onComments(position: Int) { Toast.makeText(context, "评论面板请从详情页打开", Toast.LENGTH_SHORT).show() }
    override fun onDownload(position: Int) {
        val post = adapter.item(position) ?: return
        context.getSystemService(DownloadManager::class.java).enqueue(DownloadManager.Request(Uri.parse(post.url)).setTitle("DerpiViewer 视频 ${post.id}").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED))
        Toast.makeText(context, "已加入系统下载队列", Toast.LENGTH_SHORT).show()
    }
    override fun onMore(position: Int, anchor: View) {
        val post = adapter.item(position) ?: return
        PopupMenu(context, anchor).apply {
            menu.add("复制视频链接"); menu.add("查看原页面")
            setOnMenuItemClickListener { item ->
                if (item.title == "复制视频链接") {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("视频链接", post.url)); Toast.makeText(context, "视频链接已复制", Toast.LENGTH_SHORT).show()
                } else {
                    context.startActivity(Intent(context, ImageDetailActivity::class.java).putExtra("image_id", post.id))
                }; true
            }
        }.show()
    }

    fun dispose() {
        scope.cancel()
        prefetchScope.cancel()
        playerPool.releaseAll()
    }
}
