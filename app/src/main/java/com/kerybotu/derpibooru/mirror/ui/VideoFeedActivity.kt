package com.kerybotu.derpibooru.mirror.ui

import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.ScrollView
import android.widget.ProgressBar
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.media3.ui.PlayerView
import androidx.media3.common.Player
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.databinding.ActivityVideoFeedBinding
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import com.kerybotu.derpibooru.mirror.network.ResourceCoordinator
import com.kerybotu.derpibooru.mirror.translate.NiuTransService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class VideoFeedActivity : AppCompatActivity(), VideoFeedAdapter.Actions {
    private lateinit var binding: ActivityVideoFeedBinding
    private lateinit var adapter: VideoFeedAdapter
    private lateinit var playerPool: VideoPlayerPool
    private lateinit var pagerRecycler: RecyclerView
    private val scope = CoroutineScope(Job() + Dispatchers.Main)
    private var currentPosition = 0
    private var page = 1
    private var loading = false
    // Keep one random seed for the lifetime of this feed. Paging with a new seed
    // would reshuffle the server result and produce duplicates.
    private var sort = "random:${System.currentTimeMillis() / 1000L}"
    private var sortDirection = "desc"
    private var muted = false
    private var filterQuery = ""
    private var recentFeatured = false
    private val numericFilters = mutableListOf<NumericFilter>()
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressTick = object : Runnable {
        override fun run() {
            updateVideoProgress()
            progressHandler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, bindingOrDecorView()).hide(
            androidx.core.view.WindowInsetsCompat.Type.statusBars() or androidx.core.view.WindowInsetsCompat.Type.navigationBars()
        )
        binding = ActivityVideoFeedBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ResourceCoordinator.enterVideoTab()
        CdnImageGate.pausePrefetch(this)
        PaletteManager.apply(this)
        muted = !AppSettings.isVideoAudioEnabled(this)
        playerPool = VideoPlayerPool(this)
        playerPool.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                updateVideoProgress()
            }
        })
        adapter = VideoFeedAdapter(this)
        binding.videoPager.apply {
            orientation = ViewPager2.ORIENTATION_VERTICAL
            offscreenPageLimit = 1
            this.adapter = this@VideoFeedActivity.adapter
        }
        pagerRecycler = binding.videoPager.getChildAt(0) as RecyclerView
        binding.videoBack.setOnClickListener { finish() }
        binding.videoAudio.setOnClickListener { toggleMute() }
        binding.videoSort.setOnClickListener { showSortMenu(it) }
        binding.videoPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPosition = position
                activate(position, automatic = true)
                if (position >= adapter.itemCount - 4) loadNextPage()
            }
        })
        loadNextPage()
    }

    private fun bindingOrDecorView(): View = window.decorView

    private fun loadNextPage() {
        if (loading) return
        loading = true
        binding.videoEmpty.visibility = View.VISIBLE
        scope.launch {
            if (!NetworkManager.isReady()) runCatching { NetworkManager.init(applicationContext) }
            val result = withContext(Dispatchers.IO) {
                val filter = NetworkManager.currentFilterParam(this@VideoFeedActivity)
                val query = java.net.URLEncoder.encode(buildQuery(), "UTF-8")
                val direction = if (sort.startsWith("random:")) "" else "&sd=$sortDirection"
                NetworkManager.getApi(this@VideoFeedActivity, "search/images?q=$query&per_page=50&page=$page&sf=$sort$direction$filter")
            }
            // Keep the vertical feed varied regardless of the server-side sort.
            // The API's random:{seed} ordering is stable across pages; do not shuffle
            // the response locally or page boundaries become inconsistent.
            val posts = result?.let(::parseVideoPosts).orEmpty()
            if (page == 1) adapter.replace(posts) else adapter.append(posts)
            if (posts.isNotEmpty()) page++
            binding.videoEmpty.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
            binding.videoEmpty.text = if (adapter.itemCount == 0) "没有可播放的视频" else ""
            loading = false
            if (adapter.itemCount > 0) binding.videoPager.post { activate(currentPosition, automatic = true) }
        }
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
                val tags = item.optJSONArray("tags")?.let { tagsArray ->
                    List(tagsArray.length()) { tagsArray.optString(it) }.filter { it.isNotBlank() }
                }.orEmpty()
                add(VideoPost(
                    id = item.optInt("id"), url = url,
                    uploader = item.optString("uploader", "未知上传者"), tags = tags,
                    upvotes = item.optInt("upvotes"), downvotes = item.optInt("downvotes"),
                    commentCount = item.optInt("comment_count")
                ))
            }
        }
    }

    private fun activate(position: Int, automatic: Boolean) {
        val current = adapter.item(position) ?: return
        playerPool.retainOnly(setOf(position - 1, position, position + 1))
        attachPlayer(position, current, automatic && canAutoPlay())
        adapter.item(position + 1)?.let { playerPool.prepare(position + 1, it.url) }
        playerPool.pause(position - 1)
    }

    private fun attachPlayer(position: Int, post: VideoPost, play: Boolean) {
        pagerRecycler.post {
            val holder = pagerRecycler.findViewHolderForAdapterPosition(position) as? VideoFeedAdapter.Holder ?: return@post
            val player = playerPool.prepare(position, post.url)
            holder.binding.videoPlayer.player = player
            holder.binding.videoPlayer.useController = false
            holder.binding.videoPlayer.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            holder.binding.videoPlayProgress.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val duration = player.duration
                    if (duration > 0L) player.seekTo(duration * progress / 1000L)
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) = Unit
            })
            if (play) player.playWhenReady = true
            updateVideoProgress()
        }
    }

    private fun updateVideoProgress() {
        val player = playerPool.get(currentPosition) ?: return
        val holder = pagerRecycler.findViewHolderForAdapterPosition(currentPosition) as? VideoFeedAdapter.Holder ?: return
        val duration = player.duration.takeIf { it > 0 } ?: return
        val played = ((player.currentPosition * 1000L) / duration).toInt().coerceIn(0, 1000)
        if (!holder.binding.videoPlayProgress.isPressed) holder.binding.videoPlayProgress.progress = played
        holder.binding.videoBufferProgress.progress = (player.bufferedPercentage.coerceIn(0, 100) * 10)
        val buffering = player.playbackState == Player.STATE_BUFFERING
        val prebuffering = player.playbackState == Player.STATE_IDLE && player.playWhenReady
        val bitrate = playerPool.bitrateEstimate()
        // Network speed is meaningful only while buffering/pre-buffering. Hide it
        // during normal playback so the overlay does not become permanent noise.
        holder.binding.videoBufferLabel.visibility = if (buffering || prebuffering) View.VISIBLE else View.GONE
        holder.binding.videoBufferLabel.text = buildString {
            if (bitrate > 0L) append("速度 ${formatBitrate(bitrate)}")
            if (bitrate <= 0L) {
                if (isNotEmpty()) append(" · ")
                append("已缓冲 ${player.bufferedPercentage.coerceIn(0, 100)}%")
            }
        }
    }

    private fun formatBitrate(bitsPerSecond: Long): String = when {
        bitsPerSecond / 8 >= 1_000_000L -> "%.1f MB/s".format(bitsPerSecond / 8_000_000.0)
        bitsPerSecond / 8 >= 1_000L -> "%.0f KB/s".format(bitsPerSecond / 8_000.0)
        else -> "%.0f B/s".format(bitsPerSecond / 8.0)
    }

    private fun canAutoPlay(): Boolean {
        if (!AppSettings.isVideoWifiOnly(this)) return true
        val capabilities = getSystemService(ConnectivityManager::class.java)
            .getNetworkCapabilities(getSystemService(ConnectivityManager::class.java).activeNetwork)
        return capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun toggleMute() {
        muted = !muted
        playerPool.setMuted(muted)
        binding.videoAudio.contentDescription = if (muted) "打开声音" else "静音"
        Toast.makeText(this, if (muted) "已静音" else "已打开声音", Toast.LENGTH_SHORT).show()
    }

    private fun showSortMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
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

    private data class NumericFilter(val field: String, val comparator: String, val value: String) {
        fun queryPart() = "$field$comparator:$value"
    }

    private fun buildQuery(): String {
        val parts = mutableListOf("animated")
        filterQuery.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach(parts::add)
        if (recentFeatured) {
            parts += "first_seen_at.gt:3 days ago"
            parts += "-ai generated"
            parts += "-ai composition"
        }
        numericFilters.mapTo(parts) { it.queryPart() }
        return parts.joinToString(",")
    }

    private fun reloadFeed() {
        page = 1
        currentPosition = 0
        playerPool.pauseAll()
        adapter.replace(emptyList())
        loadNextPage()
    }

    private fun showFilterSheet() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(28)) }
        val query = EditText(this).apply { hint = "输入标签或搜索条件"; setText(filterQuery); setSingleLine(true) }
        val featured = CheckBox(this).apply { text = "近期精选（近3天，排除 AI 生成内容）"; isChecked = recentFeatured }
        val filters = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun renderFilters() {
            filters.removeAllViews()
            numericFilters.forEachIndexed { index, item ->
                filters.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(android.widget.TextView(this@VideoFeedActivity).apply { text = "${numericFieldLabel(item.field)} ${comparatorLabel(item.comparator)} ${item.value}" }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(android.widget.Button(this@VideoFeedActivity).apply { text = "删除"; setOnClickListener { numericFilters.removeAt(index); renderFilters() } })
                })
            }
        }
        root.addView(android.widget.TextView(this).apply { text = "筛选条件"; textSize = 20f })
        root.addView(query)
        root.addView(featured)
        root.addView(android.widget.Button(this).apply { text = "+ 添加数值筛选"; setOnClickListener { showNumericFilterPicker { numericFilters += it; renderFilters() } } })
        root.addView(filters); renderFilters()
        val apply = android.widget.Button(this).apply { text = "应用筛选" }
        root.addView(apply)
        BottomSheetDialog(this).apply { setContentView(root); apply.setOnClickListener { filterQuery = query.text.toString().trim(); recentFeatured = featured.isChecked; dismiss(); reloadFeed() }; show() }
    }

    private fun showNumericFilterPicker(done: (NumericFilter) -> Unit) {
        val fields = listOf(
            "评分" to "score", "收藏数" to "faves", "点赞数" to "upvotes",
            "点踩数" to "downvotes", "评论数" to "comment_count", "时长" to "duration",
            "像素数" to "pixels", "文件大小" to "size"
        )
        val comparators = listOf("≥" to ".gte", "≤" to ".lte", ">" to ".gt", "<" to ".lt")
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), 0) }
        val field = Spinner(this).apply { adapter = ArrayAdapter(this@VideoFeedActivity, android.R.layout.simple_spinner_dropdown_item, fields.map { it.first }) }
        val comparator = Spinner(this).apply { adapter = ArrayAdapter(this@VideoFeedActivity, android.R.layout.simple_spinner_dropdown_item, comparators.map { it.first }) }
        val value = EditText(this).apply { hint = "数值"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
        row.addView(field); row.addView(comparator); row.addView(value)
        BottomSheetDialog(this).apply { setContentView(row); setOnShowListener { row.addView(android.widget.Button(this@VideoFeedActivity).apply { text = "添加"; setOnClickListener { val v = value.text.toString().trim(); if (v.isNotEmpty()) { done(NumericFilter(fields[field.selectedItemPosition].second, comparators[comparator.selectedItemPosition].second, v)); dismiss() } } }) }; show() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

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

    override fun onUpvote(position: Int) {
        val post = adapter.item(position) ?: return
        post.upvotes++
        pagerRecycler.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        adapter.notifyItemChanged(position)
    }

    override fun onDownvote(position: Int) {
        val post = adapter.item(position) ?: return
        post.downvotes++
        pagerRecycler.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        Toast.makeText(this, "已记录踩", Toast.LENGTH_SHORT).show()
    }

    override fun onFavorite(position: Int) {
        pagerRecycler.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        Toast.makeText(this, "已加入本地收藏队列", Toast.LENGTH_SHORT).show()
    }

    override fun onComments(position: Int) {
        val post = adapter.item(position) ?: return
        val input = EditText(this).apply { hint = "写下评论"; minLines = 3 }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(28)) }
        val commentsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val progress = ProgressBar(this).apply { visibility = View.VISIBLE }
        content.addView(TextView(this).apply { text = "评论"; textSize = 18f; setTextColor(PaletteManager.colors(this@VideoFeedActivity).onSurface) })
        content.addView(progress, LinearLayout.LayoutParams(-2, dp(40)).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL })
        val scroll = ScrollView(this).apply { addView(commentsList) }
        content.addView(scroll, LinearLayout.LayoutParams(-1, dp(260)))
        content.addView(input)
        val dialog = BottomSheetDialog(this).apply {
            setTitle("${post.commentCount} 条评论")
            setContentView(content)
            show()
        }
        scope.launch {
            val query = java.net.URLEncoder.encode("image_id:${post.id}", "UTF-8")
            val raw = withContext(Dispatchers.IO) { NetworkManager.getApi(this@VideoFeedActivity, "search/comments?q=$query&per_page=50") }
            val comments = runCatching { JSONObject(raw.orEmpty()).optJSONArray("comments") }.getOrNull()
            commentsList.removeAllViews()
            if (comments == null || comments.length() == 0) {
                commentsList.addView(TextView(this@VideoFeedActivity).apply { text = "暂无评论"; setTextColor(PaletteManager.colors(this@VideoFeedActivity).muted); setPadding(0, dp(12), 0, dp(12)) })
            } else {
                for (i in 0 until comments.length()) comments.optJSONObject(i)?.let { addVideoComment(commentsList, it) }
            }
            progress.visibility = View.GONE
        }
    }

    private fun addVideoComment(parent: LinearLayout, comment: JSONObject) {
        val c = PaletteManager.colors(this)
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10)); setBackgroundColor(c.surfaceVariant) }
        row.addView(TextView(this).apply { text = "${comment.optString("author", "匿名用户")} · ${comment.optString("created_at").take(10)}"; textSize = 12f; setTextColor(c.muted) })
        val original = comment.optString("body", "")
        val body = TextView(this).apply { text = original; setTextColor(c.onSurface); setPadding(0, dp(6), 0, 0) }
        row.addView(body)
        if (NiuTransService.shouldTranslate(original)) {
            row.addView(android.widget.Button(this).apply {
                text = "翻译"; textSize = 12f
                backgroundTintList = android.content.res.ColorStateList.valueOf(c.primary); setTextColor(c.onPrimary)
                setOnClickListener {
                    if (tag is String) { body.text = original; tag = null; text = "翻译"; return@setOnClickListener }
                    isEnabled = false; text = "翻译中…"
                    scope.launch {
                        NiuTransService.translate(original).onSuccess { body.text = it; tag = it; this@apply.text = "原文" }
                            .onFailure { Toast.makeText(this@VideoFeedActivity, "翻译失败", Toast.LENGTH_SHORT).show(); this@apply.text = "翻译" }
                        this@apply.isEnabled = true
                    }
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) })
    }

    override fun onDownload(position: Int) {
        val post = adapter.item(position) ?: return
        val request = DownloadManager.Request(Uri.parse(post.url))
            .setTitle("DerpiViewer 视频 ${post.id}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        getSystemService(DownloadManager::class.java).enqueue(request)
        Toast.makeText(this, "已加入系统下载队列", Toast.LENGTH_SHORT).show()
    }

    override fun onMore(position: Int, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("复制视频链接")
            menu.add("查看原页面")
            setOnMenuItemClickListener { Toast.makeText(this@VideoFeedActivity, "功能即将开放", Toast.LENGTH_SHORT).show(); true }
        }.show()
    }

    override fun onPause() { progressHandler.removeCallbacks(progressTick); playerPool.pauseAll(); super.onPause() }
    override fun onResume() { super.onResume(); progressHandler.post(progressTick); activate(currentPosition, automatic = true) }
    override fun onTrimMemory(level: Int) {
        ResourceCoordinator.onTrimMemory(this, level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) playerPool.releaseNonCurrent(currentPosition)
        super.onTrimMemory(level)
    }
    override fun onDestroy() { progressHandler.removeCallbacks(progressTick); scope.coroutineContext[Job]?.cancel(); playerPool.releaseAll(); ResourceCoordinator.exitVideoTab(); super.onDestroy() }
}
