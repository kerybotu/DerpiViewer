package com.kerybotu.derpibooru.mirror.ui

import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.PopupMenu
import android.widget.LinearLayout
import android.widget.TextView
import android.content.res.ColorStateList
import android.widget.Toast
import android.content.ClipData
import android.content.ClipboardManager
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.chip.Chip
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.databinding.ActivityImageDetailBinding
import com.kerybotu.derpibooru.mirror.model.Image
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import com.kerybotu.derpibooru.mirror.dict.TagDictionary
import com.kerybotu.derpibooru.mirror.translate.NiuTransService
import com.kerybotu.derpibooru.mirror.download.DownloadQueueManager
import com.kerybotu.derpibooru.mirror.favorites.FavoriteItem
import com.kerybotu.derpibooru.mirror.favorites.LocalFavoritesStore
import kotlinx.coroutines.*
import org.json.JSONObject

class ImageDetailActivity : AppCompatActivity() {
    private lateinit var b: ActivityImageDetailBinding
    private lateinit var image: Image
    private lateinit var sheet: BottomSheetBehavior<View>
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var fullUrl: String? = null
    private var mediaMimeType: String? = null
    private var uploaderId: Long? = null
    private var expanded = false
    private var previewPlayer: MediaPreviewPlayer? = null
    private var adjacentLoading = false
    private var detailJob: Job? = null
    private var tagJob: Job? = null
    private var descriptionOriginal = ""
    private var descriptionTranslation: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityImageDetailBinding.inflate(layoutInflater)
        setContentView(b.root)
        PaletteManager.apply(this)
        applyPalette()
        image = intent.getSerializableExtra("image") as? Image
            ?: intent.getIntExtra("image_id", -1).takeIf { it > 0 }?.let { Image(id = it, title = "", thumbnailUrl = null, width = 0, height = 0, score = 0, faves = 0, upvotes = 0, downvotes = 0, commentCount = 0, tags = emptyList()) }
            ?: run { finish(); return }
        mediaMimeType = image.mimeType
        uploaderId = image.uploaderId
        sheet = BottomSheetBehavior.from(b.bottomSheet)
        sheet.state = BottomSheetBehavior.STATE_COLLAPSED
        b.detailToolbar.appToolbar.setNavigationIcon(com.kerybotu.derpibooru.mirror.R.drawable.ic_arrow_back)
        b.detailToolbar.appToolbar.setNavigationOnClickListener { finish() }
        b.btnShare.setOnClickListener { share() }
        b.btnMore.setOnClickListener { showMore() }
        b.btnExpandDescription.setOnClickListener { toggleDescription() }
        b.btnTranslateDescription.setOnClickListener { toggleDescriptionTranslation() }
        b.detailCommentsCount.setOnClickListener { sheet.state = BottomSheetBehavior.STATE_EXPANDED }
        b.detailFaves.setOnClickListener {
            val store = LocalFavoritesStore(this)
            store.allFolders().firstOrNull { it.isDefault }?.let { folder ->
                store.add(folder.id, FavoriteItem(image.id, image.thumbnailUrl, image.mimeType))
                Toast.makeText(this, "已收藏到本地", Toast.LENGTH_SHORT).show()
            }
        }
        b.detailVideoPlayer.setOnClickListener { openLargePreview() }
        bindInitial()
        setGestures()
        loadDetails()
    }

    private fun bindInitial() {
        b.detailFaves.text = image.faves.toString(); b.detailUpvotes.text = image.upvotes.toString()
        b.detailCommentsCount.text = image.commentCount.toString()
        b.detailUploader.text = image.uploader ?: "未知上传者"; b.detailCreatedAt.text = image.createdAt?.take(10) ?: ""
        bindDescription(image.description.orEmpty())
        b.detailUploader.visibility = if (AppSettings.isUploaderHidden(this)) View.GONE else View.VISIBLE
        bindUploaderClick()
        b.detailScore.visibility = View.GONE
        bindTags(image.tags)
        showPreview(image.mimeType, if (image.mimeType?.startsWith("video/") == true) image.fullUrl ?: image.thumbnailUrl else image.thumbnailUrl)
    }

    private fun bindTags(tags: List<String>) {
        val palette = PaletteManager.colors(this)
        tagJob?.cancel()
        tagJob = scope.launch {
            val translated = withContext(Dispatchers.IO) { TagDictionary.sortAndTranslate(this@ImageDetailActivity, tags) }
            // Initial feed data and full detail data can arrive in either order.
            // Only the newest binding owns the group, preventing duplicate chips and mixed sort orders.
            b.detailTagsGroup.removeAllViews()
            translated.forEach { entry -> b.detailTagsGroup.addView(Chip(this@ImageDetailActivity).apply {
            text = entry.chineseName; contentDescription = entry.englishName; isCheckable = false
            setTextColor(palette.onSurface)
            chipBackgroundColor = ColorStateList.valueOf(palette.surfaceVariant)
            rippleColor = ColorStateList.valueOf(palette.primary)
            setOnClickListener {
                startActivity(Intent(this@ImageDetailActivity, SearchActivity::class.java)
                    .putExtra(SearchActivity.EXTRA_INITIAL_QUERY, entry.englishName))
            }
            }) }
        }
    }

    private fun applyPalette() {
        val palette = PaletteManager.colors(this)
        b.detailRoot.setBackgroundColor(palette.surface)
        b.detailVideoPlayer.setBackgroundColor(palette.mediaSurface)
        b.bottomSheet.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(palette.surface)
            cornerRadii = floatArrayOf(dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), 0f, 0f, 0f, 0f)
        }
        b.detailDivider.setBackgroundColor(palette.divider)
        b.sheetHandle.setBackgroundColor(palette.muted)
        b.btnShare.imageTintList = ColorStateList.valueOf(palette.onSurface)
        b.btnMore.imageTintList = ColorStateList.valueOf(palette.onSurface)
        b.detailToolbar.appToolbar.navigationIcon?.setTint(palette.onPrimary)
        listOf(b.detailFaves, b.detailUpvotes, b.detailCommentsCount).forEach {
            it.compoundDrawableTintList = ColorStateList.valueOf(palette.onSurface)
            it.setTextColor(palette.onSurface)
        }
    }

    private fun setGestures() {
        val gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            // Consume the complete gesture stream so the ImageView's native long-click
            // scheduler cannot compete with horizontal swipes.
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                openLargePreview()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                showMore()
            }

            override fun onDoubleTap(e: MotionEvent): Boolean = false
            override fun onScroll(
                downEvent: MotionEvent?,
                currentEvent: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                // Treat horizontal movement as an active gesture immediately. This
                // cancels the detector's long-press timeout even for a slow swipe.
                return downEvent != null &&
                    kotlin.math.abs(currentEvent.x - downEvent.x) > kotlin.math.abs(currentEvent.y - downEvent.y)
            }

            override fun onFling(a: MotionEvent?, c: MotionEvent, vx: Float, vy: Float): Boolean {
                if (a == null) return false
                if (kotlin.math.abs(vx) > kotlin.math.abs(vy) && kotlin.math.abs(vx) > 500f) {
                    loadAdjacent(if (vx < 0f) 1 else -1)
                    return true
                }
                if (c.y - a.y > 180 && vy > 900) { finish(); return true }
                return false
            }
        })
        b.detailImage.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                view.parent?.requestDisallowInterceptTouchEvent(true)
            } else if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL) {
                view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            // Always consume the stream. Long-press handling is implemented by the
            // detector above, which is cancelled automatically once a fling starts.
            gesture.onTouchEvent(event)
            true
        }
    }

    private fun loadDetails() {
        detailJob?.cancel()
        detailJob = scope.launch {
        val json = withContext(Dispatchers.IO) {
            val filter = NetworkManager.currentFilterParam(this@ImageDetailActivity, "?")
            NetworkManager.getApi(this@ImageDetailActivity, "images/${image.id}$filter")
        } ?: return@launch
        runCatching {
            val item = JSONObject(json).getJSONObject("image")
            val mime = item.optString("mime_type", image.mimeType)
            mediaMimeType = mime
            fullUrl = item.optJSONObject("representations")?.optString("full", null)
            showPreview(mime, if (mime?.startsWith("video/") == true) fullUrl ?: image.fullUrl ?: image.thumbnailUrl else fullUrl ?: image.fullUrl ?: image.thumbnailUrl)
            b.detailUploader.text = item.optString("uploader", "未知上传者"); b.detailCreatedAt.text = item.optString("created_at", "").take(10)
            uploaderId = item.optLong("uploader_id", -1L).takeIf { it > 0L }
            bindUploaderClick()
            bindDescription(item.optString("description", ""))
            b.detailFaves.text = item.optInt("faves").toString()
            b.detailUpvotes.text = item.optInt("upvotes").toString()
            b.detailScore.visibility = View.GONE
            b.detailCommentsCount.text = item.optInt("comment_count").toString()
            bindTags(item.optJSONArray("tags")?.let { array -> List(array.length()) { array.optString(it) } }.orEmpty())
            b.detailSource.text = item.optJSONArray("source_urls")?.let { urls ->
                (0 until urls.length()).map { urls.optString(it) }.filter { it.isNotBlank() }.joinToString("\n")
            }.orEmpty()
            loadComments()
        }
        }
    }

    /** Loads the nearest image in the current gallery/search space for a horizontal swipe. */
    private fun loadAdjacent(direction: Int) {
        if (adjacentLoading) return
        adjacentLoading = true
        scope.launch {
            val comparator = if (direction > 0) "gt" else "lt"
            val sortDirection = if (direction > 0) "asc" else "desc"
            val query = java.net.URLEncoder.encode("id.$comparator:${image.id}", "UTF-8")
            val filter = NetworkManager.currentFilterParam(this@ImageDetailActivity)
            val raw = withContext(Dispatchers.IO) {
                NetworkManager.getApi(this@ImageDetailActivity, "search/images?q=$query&sf=id&sd=$sortDirection&per_page=1$filter")
            }
            val next = runCatching { JSONObject(raw.orEmpty()).optJSONArray("images")?.optJSONObject(0)?.let(::parseImage) }.getOrNull()
            if (next != null) {
                animateToAdjacent(next, direction)
            } else {
                Toast.makeText(this@ImageDetailActivity, if (direction > 0) "已经是最后一张" else "已经是第一张", Toast.LENGTH_SHORT).show()
                adjacentLoading = false
            }
        }
    }

    /** Slides the current media away, swaps data, then slides the new media in. */
    private fun animateToAdjacent(next: Image, direction: Int) {
        val outgoing = if (b.detailVideoPlayer.visibility == View.VISIBLE) {
            b.detailVideoPlayer
        } else {
            b.detailImage
        }
        val distance = (outgoing.width.takeIf { it > 0 } ?: b.root.width.coerceAtLeast(1)).toFloat()
        val exitX = if (direction > 0) -distance else distance
        val enterX = -exitX
        outgoing.animate()
            .translationX(exitX)
            .alpha(0f)
            .setDuration(180L)
            .withEndAction {
                image = next
                uploaderId = next.uploaderId
                fullUrl = null
                mediaMimeType = next.mimeType
                expanded = false
                bindInitial()
                loadDetails()

                val incoming = if (b.detailVideoPlayer.visibility == View.VISIBLE) {
                    b.detailVideoPlayer
                } else {
                    b.detailImage
                }
                incoming.translationX = enterX
                incoming.alpha = 0f
                incoming.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(220L)
                    .withEndAction { adjacentLoading = false }
                    .start()
            }
            .start()
    }

    private fun parseImage(o: JSONObject): Image {
        val reps = o.optJSONObject("representations")
        val tags = o.optJSONArray("tags")?.let { array -> List(array.length()) { array.optString(it) } }.orEmpty()
        return Image(o.optInt("id"), "", reps?.optString("small", null) ?: reps?.optString("thumb", null), o.optInt("width"), o.optInt("height"), o.optInt("score"), o.optInt("faves"), o.optInt("upvotes"), o.optInt("downvotes"), o.optInt("comment_count"), tags, reps?.optString("full", null), o.optString("uploader", null), o.optString("created_at", null), o.optString("description", null), o.optString("mime_type", null), o.optLong("uploader_id", -1L).takeIf { it > 0L })
    }

    private fun bindUploaderClick() {
        b.detailUploader.isClickable = uploaderId != null
        b.detailUploader.setOnClickListener {
            uploaderId?.let { userId -> startActivity(Intent(this, ProfileActivity::class.java).putExtra(ProfileActivity.EXTRA_USER_ID, userId)) }
        }
    }

    private fun loadComments() = scope.launch {
        val query = java.net.URLEncoder.encode("image_id:${image.id}", "UTF-8")
        val raw = withContext(Dispatchers.IO) {
            NetworkManager.getApi(this@ImageDetailActivity, "search/comments?q=$query&per_page=50")
        }
        val comments = runCatching { JSONObject(raw.orEmpty()).optJSONArray("comments") }.getOrNull()
        if (comments == null || comments.length() == 0) {
            b.detailCommentsPreview.removeAllViews()
            b.detailCommentsPreview.addView(TextView(this@ImageDetailActivity).apply {
                text = "暂无评论"
                setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
            return@launch
        }
        b.detailCommentsPreview.removeAllViews()
        for (index in 0 until comments.length()) {
            val comment = comments.optJSONObject(index) ?: continue
            val block = LinearLayout(this@ImageDetailActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setBackgroundResource(com.kerybotu.derpibooru.mirror.R.drawable.bg_comment_block)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(10)
                }
            }
            block.addView(TextView(this@ImageDetailActivity).apply {
                text = "${comment.optString("author", "匿名用户")} · ${comment.optString("created_at", "").take(10)}"
                setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurface))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            val body = comment.optString("body", "")
            val bodyView = TextView(this@ImageDetailActivity).apply {
                text = body
                setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(0, dp(6), 0, 0)
            }
            block.addView(bodyView)
            addTranslateAction(block, bodyView, body)
            b.detailCommentsPreview.addView(block)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun bindDescription(raw: String) {
        descriptionOriginal = raw
        descriptionTranslation = null
        b.detailDescription.text = raw.ifBlank { "暂无描述" }
        b.btnTranslateDescription.visibility = if (raw.isBlank()) View.GONE else View.VISIBLE
        b.btnTranslateDescription.text = "翻译"
        b.btnTranslateDescription.isEnabled = true
    }

    private fun toggleDescriptionTranslation() {
        descriptionTranslation?.let {
            descriptionTranslation = null
            b.detailDescription.text = descriptionOriginal
            b.btnTranslateDescription.text = "翻译"
            return
        }
        b.btnTranslateDescription.isEnabled = false
        b.btnTranslateDescription.text = "翻译中…"
        scope.launch {
            NiuTransService.translate(descriptionOriginal).onSuccess {
                descriptionTranslation = it
                b.detailDescription.text = it
                b.btnTranslateDescription.text = "原文"
            }.onFailure { Toast.makeText(this@ImageDetailActivity, "翻译失败", Toast.LENGTH_SHORT).show(); b.btnTranslateDescription.text = "翻译" }
            b.btnTranslateDescription.isEnabled = true
        }
    }

    private fun addTranslateAction(parent: LinearLayout, content: TextView, original: String) {
        if (!NiuTransService.shouldTranslate(original)) return
        val palette = PaletteManager.colors(this)
        parent.addView(android.widget.Button(this).apply {
            text = "翻译"; textSize = 12f
            backgroundTintList = ColorStateList.valueOf(palette.primary); setTextColor(palette.onPrimary)
            setOnClickListener {
                if (tag as? String != null) {
                    content.text = original; tag = null; text = "翻译"; return@setOnClickListener
                }
                isEnabled = false; text = "翻译中…"
                scope.launch {
                    NiuTransService.translate(original).onSuccess { translated -> content.text = translated; tag = translated; this@apply.text = "原文" }
                        .onFailure { Toast.makeText(this@ImageDetailActivity, "翻译失败", Toast.LENGTH_SHORT).show(); this@apply.text = "翻译" }
                    this@apply.isEnabled = true
                }
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
    }

    private fun resolveThemeColor(attribute: Int): Int {
        val typed = android.util.TypedValue()
        theme.resolveAttribute(attribute, typed, true)
        return if (typed.resourceId != 0) getColor(typed.resourceId) else typed.data
    }

    private fun showPreview(mime: String?, url: String?) {
        if (mime?.lowercase()?.startsWith("video/") == true && url != null) {
            b.detailImage.visibility = View.GONE
            b.detailVideoPlayer.visibility = View.VISIBLE
            previewPlayer?.release()
            previewPlayer = MediaPreviewPlayer(this, b.detailVideoPlayer).also { it.load(url) }
        } else {
            b.detailVideoPlayer.visibility = View.GONE
            b.detailImage.visibility = View.VISIBLE
            url?.let { CdnImageGate.load(b.detailImage, it, AppSettings.getCdnThreads(this)) }
        }
    }

    private fun openLargePreview() {
        startActivity(Intent(this, FullScreenImageActivity::class.java).apply {
            putExtra("thumbnail_url", image.thumbnailUrl)
            putExtra("full_url", fullUrl ?: image.fullUrl)
            putExtra("image_id", image.id)
            putExtra("mime_type", mediaMimeType)
        })
    }

    private fun toggleDescription() { expanded = !expanded; b.detailDescription.maxLines = if (expanded) Int.MAX_VALUE else 3; b.btnExpandDescription.text = if (expanded) "收起" else "展开阅读"; sheet.state = BottomSheetBehavior.STATE_HALF_EXPANDED }
    private fun share() { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "https://${AppSettings.getTargetDomain(this@ImageDetailActivity)}/images/${image.id}") }, "分享图片")) }
    private fun showMore() { PopupMenu(this, b.btnMore).apply {
        menu.add("下载"); menu.add("查看原图"); menu.add("复制链接")
        setOnMenuItemClickListener {
            when (it.title) {
                "下载" -> {
                    val url = fullUrl ?: image.fullUrl ?: image.thumbnailUrl
                    if (url.isNullOrBlank()) Toast.makeText(this@ImageDetailActivity, "暂无可下载地址", Toast.LENGTH_SHORT).show()
                    else {
                        DownloadQueueManager.get(this@ImageDetailActivity).enqueueImages(listOf(image.copy(fullUrl = url)))
                        Toast.makeText(this@ImageDetailActivity, "已加入下载队列", Toast.LENGTH_SHORT).show()
                    }
                }
                "查看原图" -> fullUrl?.let { url -> startActivity(Intent(this@ImageDetailActivity, FullScreenImageActivity::class.java).putExtra("full_url", url).putExtra("image_id", image.id)) }
                "复制链接" -> getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("图片链接", "https://${AppSettings.getTargetDomain(this@ImageDetailActivity)}/images/${image.id}"))
            }
            true
        }; show()
    } }
    override fun onDestroy() { tagJob?.cancel(); previewPlayer?.release(); scope.cancel(); super.onDestroy() }
}
