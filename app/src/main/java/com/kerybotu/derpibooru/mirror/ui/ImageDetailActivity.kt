package com.kerybotu.derpibooru.mirror.ui

import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import android.widget.LinearLayout
import android.content.res.ColorStateList
import android.widget.Toast
import android.content.ClipData
import android.content.ClipboardManager
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.CrashDiagnostics
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.R
import com.kerybotu.derpibooru.mirror.databinding.ActivityImageDetailBinding
import com.kerybotu.derpibooru.mirror.model.ImageGalleryNavigation
import com.kerybotu.derpibooru.mirror.model.Image
import com.kerybotu.derpibooru.mirror.model.GalleryImagePreview
import com.kerybotu.derpibooru.mirror.model.Comment
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import com.kerybotu.derpibooru.mirror.dict.TagDictionary
import com.kerybotu.derpibooru.mirror.dict.TagEntry
import com.kerybotu.derpibooru.mirror.translate.NiuTransService
import com.kerybotu.derpibooru.mirror.download.DownloadQueueManager
import com.kerybotu.derpibooru.mirror.favorites.FavoriteItem
import com.kerybotu.derpibooru.mirror.favorites.LocalFavoritesStore
import com.kerybotu.derpibooru.mirror.history.WatchHistoryStore
import com.kerybotu.derpibooru.mirror.ui.menu.GlassMenuCard
import com.kerybotu.derpibooru.mirror.ui.menu.GlassMenuItem
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.example.liquidglass.LiquidGlassView
import kotlinx.coroutines.*
import org.json.JSONObject

class ImageDetailActivity : AppCompatActivity() {
    private lateinit var b: ActivityImageDetailBinding
    private lateinit var image: Image
    private lateinit var infoCard: DetailInfoCard
    private lateinit var compactSummary: android.widget.TextView
    private lateinit var compactStatistics: DetailStatisticsView
    private lateinit var portraitInteractionParams: LinearLayout.LayoutParams
    private lateinit var portraitAuthorParams: LinearLayout.LayoutParams
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var fullUrl: String? = null
    private var mediaMimeType: String? = null
    private var uploaderId: Long? = null
    private var expanded = false
    private var previewPlayer: MediaPreviewPlayer? = null
    private var adjacentLoading = false
    private var imageLoadFailedId: Int? = null
    private var galleryImageIds: IntArray? = null
    private var galleryImagePreviews: ArrayList<GalleryImagePreview>? = null
    private var galleryQuery: String? = null
    private var gallerySortField: String? = null
    private var gallerySortDirection: String? = null
    private var galleryPage: Int = 1
    private var detailJob: Job? = null
    private var detailRequestGeneration = 0
    private var imageBindingGeneration = 0
    private var descriptionGeneration = 0
    private var tagJob: Job? = null
    private var tagRequestGeneration = 0
    private var commentsJob: Job? = null
    private var commentsRequestGeneration = 0
    private var descriptionOriginal = ""
    private var descriptionTranslation: String? = null
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var draggingMedia = false
    private var ignoreTransitionGesture = false
    private var velocityTracker: VelocityTracker? = null
    private var spoilerRevealed = false
    private val imageMatrix = Matrix()
    private var baseScale = 1f
    private var maxScale = 5f
    private var matrixDrawable: Drawable? = null
    // Fit artwork beside the information card, but never clip the zoom canvas to it.
    // System/navigation insets still position controls and the initial fit rectangle.
    private val imageFitBounds = Rect()
    private var renderingActive = false
    private var resumeBackdropEnabled = true
    private val renderExperiment by lazy { RenderCrashExperiment.from(this) }
    private val resumeBackdropRunnable = Runnable {
        if (!isFinishing && !isDestroyed && renderExperiment.shouldDeferBackdropAfterResume()) {
            resumeBackdropEnabled = true
            CrashDiagnostics.recordEvent("ImageDetailActivity", "deferred_backdrop_enabled image_id=${image.id} experiment=${renderExperiment.id}")
            updateGlassRendering()
        }
    }
    private val resumePreDrawListener = object : android.view.ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            if (b.root.viewTreeObserver.isAlive) b.root.viewTreeObserver.removeOnPreDrawListener(this)
            CrashDiagnostics.recordEvent("ImageDetailActivity", "resume_first_predraw image_id=${image.id} experiment=${renderExperiment.id}")
            b.root.postOnAnimation(resumeBackdropRunnable)
            return true
        }
    }
    private var bottomInset = 0
    private var removePaletteObserver: (() -> Unit)? = null
    private val paletteObserver: (com.kerybotu.derpibooru.mirror.PaletteDefinitions.Scheme) -> Unit = { applyPalette() }
    private val visibleBounds = Rect()
    private val lastGlassBounds = java.util.IdentityHashMap<LiquidGlassView, Rect>()
    private val artworkMatrixValues = FloatArray(9)
    private var lastArtworkSignature: Int? = null
    private val glassVisibilityListener = android.view.ViewTreeObserver.OnPreDrawListener {
        updateGlassRendering()
        true
    }
    private val readingIslands: List<LiquidGlassView> by lazy {
        listOf(b.detailAuthorPanel, b.detailInteractionPanel, b.detailDescriptionPanel,
            b.detailTagsPanel, b.detailSourcePanel, b.detailCommentsHeader)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityImageDetailBinding.inflate(layoutInflater)
        setContentView(b.root)
        AdaptiveNavigationShell.install(this, b.root, AdaptiveNavigationShell.HOME, AdaptiveNavigationShell.ContentMode.MEDIA_CANVAS)
        AdaptiveNavigationContent.observe(b.root) {
            updateDetailNavigationInsets()
            applyResponsiveDetailLayout()
            applyIslandWidths()
            updateSheetBounds()
        }
        PaletteManager.apply(this)
        applyPalette()
        image = intent.getSerializableExtra("image") as? Image
            ?: intent.getIntExtra("image_id", -1).takeIf { it > 0 }?.let { Image(id = it, title = "", thumbnailUrl = null, width = 0, height = 0, score = 0, faves = 0, upvotes = 0, downvotes = 0, commentCount = 0, tags = emptyList()) }
            ?: run { finish(); return }
        CrashDiagnostics.recordEvent("ImageDetailActivity", "opened image_id=${image.id} experiment=${renderExperiment.id}")
        recordWatchHistory()
        galleryImageIds = intent.getIntArrayExtra(SearchActivity.EXTRA_GALLERY_IMAGE_IDS)
            ?.let(ImageGalleryNavigation::normalize)
        @Suppress("DEPRECATION")
        galleryImagePreviews = intent.getSerializableExtra(SearchActivity.EXTRA_GALLERY_IMAGE_PREVIEWS)
            as? ArrayList<GalleryImagePreview>
        galleryQuery = intent.getStringExtra(SearchActivity.EXTRA_GALLERY_QUERY)
        gallerySortField = intent.getStringExtra(SearchActivity.EXTRA_GALLERY_SORT_FIELD)
        gallerySortDirection = intent.getStringExtra(SearchActivity.EXTRA_GALLERY_SORT_DIRECTION)
        galleryPage = intent.getIntExtra(SearchActivity.EXTRA_GALLERY_PAGE, 1).coerceAtLeast(1)
        mediaMimeType = image.mimeType
        uploaderId = image.uploaderId
        val oldParent = b.bottomSheet.parent as android.view.ViewGroup
        oldParent.removeView(b.bottomSheet)
        infoCard = DetailInfoCard(this).apply {
            tag = "detail-info-card"
            elevation = dp(8).toFloat()
            onProgress = { applyResponsiveDetailLayout() }
        }
        b.root.addView(infoCard, androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(-1, -1))
        infoCard.addView(b.bottomSheet, android.widget.FrameLayout.LayoutParams(-1, -1))
        portraitInteractionParams = LinearLayout.LayoutParams(b.detailInteractionPanel.layoutParams as LinearLayout.LayoutParams)
        portraitAuthorParams = LinearLayout.LayoutParams(b.detailAuthorPanel.layoutParams as LinearLayout.LayoutParams)
        compactStatistics = DetailStatisticsView(this)
        b.bottomSheet.addView(compactStatistics, 3, LinearLayout.LayoutParams(-1, -2))
        compactSummary = android.widget.TextView(this).apply {
            textSize = 14f; setPadding(dp(16), dp(8), dp(16), dp(12))
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxLines = 5
            setOnClickListener { infoCard.snap(DetailInfoState.EXPANDED) }
        }
        b.bottomSheet.addView(compactSummary, 4, LinearLayout.LayoutParams(-1, 0, 1f))
        b.sheetHandle.layoutParams = b.sheetHandle.layoutParams.apply { width = -1; height = dp(48) }
        b.sheetHandle.contentDescription = "向左拖动展开信息，向右拖动收起"
        b.sheetHandle.setOnClickListener { infoCard.snap(if (infoCard.progress >= .5f) DetailInfoState.COMPACT else DetailInfoState.EXPANDED) }
        infoCard.snap(if (savedInstanceState?.getBoolean("infoExpanded") == true) DetailInfoState.EXPANDED else DetailInfoState.COMPACT, false)
        applyPalette()
        b.detailBack.setOnClickListener { finish() }
        Ui2DesignSystem.applyPressFeedback(b.detailBack)
        b.btnShare.setOnClickListener { share() }
        b.btnMore.setOnClickListener { showMore() }
        b.detailLoadStatus.setOnClickListener { loadDetails() }
        b.btnExpandDescription.setOnClickListener { toggleDescription() }
        b.btnTranslateDescription.setOnClickListener { toggleDescriptionTranslation() }
        b.detailCommentsCount.setOnClickListener { infoCard.snap(DetailInfoState.EXPANDED) }
        b.detailFaves.setOnClickListener {
            val store = LocalFavoritesStore(this)
            runCatching {
                val isFavorite = store.toggleDefaultFavorite(FavoriteItem(image.id, image.thumbnailUrl, image.mimeType))
                renderFavoriteState(store.isFavoriteInDefaultFolder(image.id))
                Toast.makeText(this, if (isFavorite) "已收藏到本地" else "已取消收藏", Toast.LENGTH_SHORT).show()
            }.onFailure {
                renderFavoriteState(store.isFavoriteInDefaultFolder(image.id))
                Toast.makeText(this, "收藏状态更新失败", Toast.LENGTH_SHORT).show()
            }
        }
        b.detailVideoPlayer.setOnClickListener { openLargePreview() }
        bindInitial()
        setGestures()
        loadDetails()
        b.root.addOnLayoutChangeListener { _, left, _, right, bottom, oldLeft, _, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom != oldBottom) {
                applyResponsiveDetailLayout()
                applyIslandWidths()
                updateSheetBounds()
            }
        }
        b.detailTopIsland.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSheetBounds() }
        b.root.post { applyResponsiveDetailLayout(); applyIslandWidths(); updateSheetBounds() }
        b.root.viewTreeObserver.addOnPreDrawListener(glassVisibilityListener)
        b.detailInteractionPanel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSheetBounds() }
        b.detailTagsGroup.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) updateTagWidths()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::b.isInitialized) {
            PaletteManager.apply(this)
            applyPalette()
            renderingActive = true
            resumeBackdropEnabled = !renderExperiment.shouldDeferBackdropAfterResume()
            b.root.removeCallbacks(resumeBackdropRunnable)
            CrashDiagnostics.recordEvent("ImageDetailActivity", "resumed image_id=${image.id} experiment=${renderExperiment.id}")
            if (!resumeBackdropEnabled) b.root.viewTreeObserver.addOnPreDrawListener(resumePreDrawListener)
            PreparedBackdropSource.invalidateSource(b.detailBackdrop)
            removePaletteObserver?.invoke()
            removePaletteObserver = PaletteManager.observe(this, paletteObserver)
        }
    }

    override fun onPause() {
        renderingActive = false
        resumeBackdropEnabled = false
        if (::b.isInitialized) {
            b.root.removeCallbacks(resumeBackdropRunnable)
            if (b.root.viewTreeObserver.isAlive) b.root.viewTreeObserver.removeOnPreDrawListener(resumePreDrawListener)
        }
        if (::image.isInitialized) CrashDiagnostics.recordEvent("ImageDetailActivity", "paused image_id=${image.id} experiment=${renderExperiment.id}")
        updateGlassRendering()
        removePaletteObserver?.invoke()
        removePaletteObserver = null
        super.onPause()
    }

    private fun updateGlassRendering() {
        if (!::b.isInitialized || !::image.isInitialized) return
        b.detailImage.imageMatrix.getValues(artworkMatrixValues)
        var signature = artworkMatrixValues.contentHashCode()
        signature = 31 * signature + System.identityHashCode(b.detailImage.drawable)
        signature = 31 * signature + b.detailImage.translationX.hashCode()
        signature = 31 * signature + b.detailImage.translationY.hashCode()
        signature = 31 * signature + b.detailImage.alpha.hashCode()
        signature = 31 * signature + b.detailBackdrop.width
        signature = 31 * signature + b.detailBackdrop.height
        signature = 31 * signature + b.detailSpoilerCover.visibility
        signature = 31 * signature + ((b.detailBackdrop.background as? ColorDrawable)?.color ?: 0)
        val artworkChanged = lastArtworkSignature != signature
        lastArtworkSignature = signature
        val animated = (b.detailImage.drawable as? android.graphics.drawable.Animatable)?.isRunning == true ||
            b.detailVideoPlayer.isVisible
        if (artworkChanged || animated) PreparedBackdropSource.invalidateSource(b.detailBackdrop)
        fun update(island: LiquidGlassView) {
            if (island === b.detailTopIsland && !renderingActive) {
                lastGlassBounds.remove(island)
                b.detailTopIsland.setRenderingActive(
                    false,
                    b.detailBackdrop,
                    BackdropSourceMode.EXPLICIT_SOURCE
                )
                return
            }
            // Capture only artwork, never the transparent sheet or its text.
            val active = renderingActive && resumeBackdropEnabled &&
                renderExperiment.shouldUpdateDetailBackdrop() &&
                island.isShown && island.getGlobalVisibleRect(visibleBounds)
            val previous = lastGlassBounds[island]
            val moved = active && previous != visibleBounds
            if (active) {
                if (previous == null) lastGlassBounds[island] = Rect(visibleBounds)
                else previous.set(visibleBounds)
            } else lastGlassBounds.remove(island)
            // The source cache refreshes only when artwork, layout or scrolling changes.
            // Keep the GPU material active between those updates without repeating capture.
            GlassWidgetStyle.setRenderingActive(island, active, b.detailBackdrop, BackdropSourceMode.EXPLICIT_SOURCE)
            // The prepared source remains valid when only the consumer moves,
            // but the glass view still needs a frame so its renderer resolves
            // the new source-to-consumer matrix. This is bounded to real bounds
            // changes and does not recapture or redraw the source tree.
            if (moved) island.invalidate()
            island.enableSensorHighlight = false
        }
        if (::infoCard.isInitialized) update(infoCard)
        readingIslands.forEach(::update)
        update(b.detailTopIsland)
    }

    private fun bindInitial() {
        val requestedId = image.id
        val bindingGeneration = ++imageBindingGeneration
        compactStatistics.bind(null, loading = true)
        val bindingStartedAt = android.os.SystemClock.elapsedRealtime()
        spoilerRevealed = false
        commentsJob?.cancel()
        commentsRequestGeneration++
        b.detailFaves.text = image.faves.toString(); b.detailUpvotes.text = image.upvotes.toString()
        renderFavoriteState(LocalFavoritesStore(this).isFavoriteInDefaultFolder(image.id))
        b.detailCommentsCount.text = image.commentCount.toString()
        b.detailUploader.text = image.uploader ?: "未知上传者"; b.detailCreatedAt.text = image.createdAt?.take(10) ?: ""
        b.detailTitle.text = "#${image.id}"
        b.detailArtworkTitle.text = image.title
        b.detailArtworkTitle.visibility = if (image.title.isBlank()) View.GONE else View.VISIBLE
        bindDescription(image.description.orEmpty())
        bindSources(emptyList())
        b.detailCommentsPreview.removeAllViews()
        b.detailCommentsStatus.text = getString(com.kerybotu.derpibooru.mirror.R.string.detail_comments_loading)
        b.detailCommentsStatus.visibility = View.VISIBLE
        b.detailUploader.visibility = if (AppSettings.isUploaderHidden(this)) View.GONE else View.VISIBLE
        bindUploaderClick()
        b.detailScore.visibility = View.GONE
        bindTags(image.tags)
        showPreview(image.mimeType, if (image.mimeType?.startsWith("video/") == true) image.fullUrl ?: image.thumbnailUrl else image.thumbnailUrl)
        b.detailTitle.postOnAnimation {
            if (imageBindingGeneration == bindingGeneration && image.id == requestedId && !isFinishing && !isDestroyed) {
                CrashDiagnostics.recordEvent(
                    "ImageDetailActivity",
                    "preview_info_frame image_id=$requestedId latency_ms=${android.os.SystemClock.elapsedRealtime() - bindingStartedAt}"
                )
            }
        }
    }

    private fun renderFavoriteState(isFavorite: Boolean) {
        val palette = PaletteManager.colors(this)
        val drawable = androidx.appcompat.content.res.AppCompatResources.getDrawable(
            this,
            if (isFavorite) R.drawable.baseline_favorite else R.drawable.ic_favorite
        )?.mutate()
        drawable?.setTint(if (isFavorite) palette.primary else palette.onSurface)
        b.detailFaves.setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null)
        b.detailFaves.contentDescription = if (isFavorite) "取消本地收藏" else "加入本地收藏"
    }

    private fun bindTags(tags: List<String>) {
        tagJob?.cancel()
        val requestGeneration = ++tagRequestGeneration
        val requestedId = image.id
        if (tags.isEmpty()) {
            b.detailTagsGroup.removeAllViews()
            b.detailTagsPanel.visibility = View.GONE
            return
        }
        b.detailTagsGroup.removeAllViews()
        b.detailTagsPanel.visibility = View.VISIBLE
        tagJob = scope.launch {
            val translated = if (AppSettings.isTagTranslationEnabled(this@ImageDetailActivity)) {
                withContext(Dispatchers.IO) { TagDictionary.sortAndTranslate(this@ImageDetailActivity, tags) }
            } else {
                tags.map { TagEntry(it, it, -1, 0, emptyList(), sourceName = it) }
            }
            if (requestGeneration != tagRequestGeneration || image.id != requestedId || isFinishing || isDestroyed) return@launch
            // Initial feed data and full detail data can arrive in either order.
            // Only the newest binding owns the group, preventing duplicate chips and mixed sort orders.
            b.detailTagsGroup.removeAllViews()
            b.detailTagsPanel.visibility = if (translated.isEmpty()) View.GONE else View.VISIBLE
            val palette = PaletteManager.colors(this@ImageDetailActivity)
            translated.forEach { entry -> b.detailTagsGroup.addView(Chip(this@ImageDetailActivity).apply {
            text = entry.chineseName
            // The dictionary stores spaces as underscores for lookup. Search must
            // use the exact tag returned by the API, such as "oc:frostwing blade".
            val apiTag = entry.sourceName ?: entry.englishName
            contentDescription = apiTag
            isCheckable = false
            setTextColor(palette.glassText)
            chipBackgroundColor = ColorStateList.valueOf(palette.glassSurfaceElevated)
            chipStrokeColor = ColorStateList.valueOf(palette.glassBorder)
            chipStrokeWidth = dp(1).toFloat()
            rippleColor = ColorStateList.valueOf(palette.primary)
            maxWidth = (b.detailTagsGroup.width.takeIf { it > 0 }
                ?: (b.root.width - dp(56))).coerceAtLeast(dp(48))
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener {
                startActivity(Intent(this@ImageDetailActivity, SearchActivity::class.java)
                    .putExtra(SearchActivity.EXTRA_INITIAL_QUERY, apiTag))
            }
            }) }
        }
    }

    private fun applyPalette() {
        val palette = PaletteManager.colors(this)
        b.detailRoot.setBackgroundColor(palette.surface)
        b.detailBackdrop.setBackgroundColor(palette.surface)
        PreparedBackdropSource.invalidateSource(b.detailBackdrop)
        b.detailVideoPlayer.setBackgroundColor(palette.mediaSurface)
        GlassWidgetStyle.apply(b.detailAuthorPanel, Ui2DesignSystem.Shape.large, palette, GlassWidgetStyle.MaterialRole.READING)
        GlassWidgetStyle.apply(b.detailInteractionPanel, Ui2DesignSystem.Shape.island, palette, GlassWidgetStyle.MaterialRole.READING)
        listOf(b.detailDescriptionPanel, b.detailTagsPanel, b.detailSourcePanel, b.detailCommentsHeader).forEach {
            GlassWidgetStyle.apply(it, Ui2DesignSystem.Shape.large, palette, GlassWidgetStyle.MaterialRole.READING)
        }
        b.bottomSheet.background = ColorDrawable(android.graphics.Color.TRANSPARENT)
        b.detailDivider.setBackgroundColor(palette.divider)
        b.sheetHandle.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        if (::infoCard.isInitialized) GlassWidgetStyle.apply(infoCard, Ui2DesignSystem.Shape.large, palette, GlassWidgetStyle.MaterialRole.READING)
        if (::compactSummary.isInitialized) compactSummary.setTextColor(palette.glassText)
        if (::compactStatistics.isInitialized) compactStatistics.applyColor(palette.glassText)
        b.btnShare.imageTintList = ColorStateList.valueOf(palette.onSurface)
        b.btnMore.imageTintList = ColorStateList.valueOf(palette.onSurface)
        b.detailUploaderAvatar.imageTintList = ColorStateList.valueOf(palette.glassText)
        // Navigation crosses arbitrary artwork as well as page letterboxing.
        // Keep its label readable with the shared coating used for text on art.
        GlassWidgetStyle.apply(b.detailTopIsland, Ui2DesignSystem.Shape.topIsland, palette, GlassWidgetStyle.MaterialRole.READING)
        b.detailBack.imageTintList = ColorStateList.valueOf(palette.glassText)
        b.detailTitle.setTextColor(palette.glassText)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            bottomInset = bars.bottom
            updateDetailNavigationInsets()
            (b.detailTopIsland.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { params ->
                val margin = dp(8) + bars.top
                if (params.topMargin != margin) { params.topMargin = margin; b.detailTopIsland.layoutParams = params }
            }
            b.detailScroll.setPadding(b.detailScroll.paddingLeft, b.detailScroll.paddingTop, b.detailScroll.paddingRight, dp(16) + bars.bottom)
            applyIslandWidths()
            updateSheetBounds()
            insets
        }
        ViewCompat.requestApplyInsets(b.root)
        listOf(b.detailFaves, b.detailUpvotes, b.detailCommentsCount).forEach {
            it.compoundDrawableTintList = ColorStateList.valueOf(palette.onSurface)
            it.setTextColor(palette.onSurface)
        }
        if (::image.isInitialized) renderFavoriteState(LocalFavoritesStore(this).isFavoriteInDefaultFolder(image.id))
        listOf(b.detailDescriptionLabel, b.detailTagsLabel, b.detailSourceLabel, b.detailCreatedAt,
            b.detailDescription, b.detailCommentsStatus).forEach { it.setTextColor(palette.glassSecondaryText) }
        listOf(b.detailUploader, b.detailArtworkTitle, b.detailCommentsLabel).forEach { it.setTextColor(palette.glassText) }
        b.btnExpandDescription.setTextColor(palette.glassActionText)
        b.detailLoadStatus.setTextColor(palette.glassActionText)
        b.detailSource.setTextColor(palette.glassText)
        b.detailSource.setLinkTextColor(palette.glassActionText)
        for (i in 0 until b.detailTagsGroup.childCount) {
            (b.detailTagsGroup.getChildAt(i) as? Chip)?.let {
                it.setTextColor(palette.glassText)
                it.chipBackgroundColor = ColorStateList.valueOf(palette.glassSurfaceElevated)
                it.chipStrokeColor = ColorStateList.valueOf(palette.glassBorder)
                it.rippleColor = ColorStateList.valueOf(palette.primary)
            }
        }
        if (::image.isInitialized) updateGlassRendering()
    }

    private fun applyIslandWidths() = applyResponsiveDetailLayout()

    private fun applyResponsiveDetailLayout() {
        if (!::infoCard.isInitialized || b.root.width <= 0 || b.root.height <= 0) return
        val bars = ViewCompat.getRootWindowInsets(b.root)?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val safe = AdaptiveNavigationContent.horizontalInsets(b.root, bars?.left ?: 0, bars?.right ?: 0)
        val gutter = dp(Ui2DesignSystem.Spacing.islandGap)
        val available = (b.root.width - safe.left - safe.right - gutter * 2).coerceAtLeast(1)
        val spec = AdaptiveLayoutPolicy.specFor((available / resources.displayMetrics.density).toInt(),
            (b.root.height / resources.displayMetrics.density).toInt())
        val wide = spec.widthClass != AdaptiveLayoutPolicy.WindowWidthClass.COMPACT || AdaptiveLayoutPolicy.isHorizontalLayout(spec.availableWidthDp, spec.availableHeightDp)
        val top = (bars?.top ?: 0) + dp(80)
        val height = (b.root.height - top - (bars?.bottom ?: 0) - gutter).coerceAtLeast(1)
        val compact = if (wide) minOf(dp(280), (available * .42f).toInt()) else minOf(dp(320), available)
        val expandedWidth = if (wide) minOf(dp(460), (available * .6f).toInt()) else available
        val width = (compact + (expandedWidth - compact) * infoCard.progress).toInt().coerceAtLeast(1)
        infoCard.dragRangePx = (expandedWidth - compact).coerceAtLeast(dp(100)).toFloat()
        val cardHeight = if (wide) height else minOf(height, (dp(260) + (maxOf(dp(260), (height * .7f).toInt()) - dp(260)) * infoCard.progress).toInt())
        val cardTop = if (wide) top else top + height - cardHeight
        val params = infoCard.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
        if (params.width != width || params.height != cardHeight || params.topMargin != cardTop || params.rightMargin != safe.right + gutter) {
            params.width = width; params.height = cardHeight; params.topMargin = cardTop
            params.rightMargin = safe.right + gutter; params.gravity = android.view.Gravity.TOP or android.view.Gravity.RIGHT
            infoCard.layoutParams = params
        }
        val mediaWidth = if (wide) (available - width - gutter).coerceAtLeast(1) else available
        val mediaHeight = if (wide) height else (height - cardHeight - gutter).coerceAtLeast(1)
        val fit = Rect(safe.left + gutter, top, safe.left + gutter + mediaWidth, top + mediaHeight)
        if (imageFitBounds != fit) {
            imageFitBounds.set(fit)
            if (!isImageZoomed()) matrixDrawable = null
        }
        listOf(b.detailImage, b.detailVideoPlayer, b.detailSpoilerCover).forEach { media ->
            val lp = media.layoutParams as android.widget.FrameLayout.LayoutParams
            val imageCanvas = media === b.detailImage
            val targetWidth = if (imageCanvas) b.root.width else mediaWidth
            val targetHeight = if (imageCanvas) b.root.height else mediaHeight
            val targetLeft = if (imageCanvas) 0 else safe.left + gutter
            val targetTop = if (imageCanvas) 0 else top
            if (lp.width != targetWidth || lp.height != targetHeight || lp.leftMargin != targetLeft || lp.topMargin != targetTop) {
                lp.width = targetWidth; lp.height = targetHeight; lp.leftMargin = targetLeft; lp.topMargin = targetTop
                lp.gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT; media.layoutParams = lp
                if (media === b.detailImage && !isImageZoomed()) matrixDrawable = null
            }
        }
        b.bottomSheet.setPadding(0, 0, 0, 0)
        val expandedInfo = infoCard.progress >= .5f
        b.detailScroll.visibility = if (expandedInfo) View.VISIBLE else View.GONE
        b.detailAuthorPanel.visibility = if (expandedInfo) View.VISIBLE else View.GONE
        b.detailInteractionPanel.visibility = if (expandedInfo) View.VISIBLE else View.GONE
        b.detailDivider.visibility = View.GONE
        compactSummary.visibility = if (infoCard.progress < .5f) View.VISIBLE else View.GONE
        compactStatistics.visibility = compactSummary.visibility
        b.detailDescription.maxLines = if (infoCard.progress >= .5f) Int.MAX_VALUE else 3
        placeDetailActions(wide)
        if (b.detailContent.layoutParams.width != -1) b.detailContent.layoutParams = b.detailContent.layoutParams.apply { this.width = -1 }
        val header = b.detailTopIsland.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
        val headerWidth = minOf(dp(240), available)
        if (header.width != headerWidth || header.topMargin != (bars?.top ?: 0) + dp(8)) {
            header.width = headerWidth; header.topMargin = (bars?.top ?: 0) + dp(8)
            header.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            header.leftMargin = 0; header.rightMargin = 0; b.detailTopIsland.layoutParams = header
        }
    }

    private fun placeDetailActions(wide: Boolean) {
        val panel = b.detailInteractionPanel
        val author = b.detailAuthorPanel
        if (wide) {
            if (panel.parent !== b.detailContent) {
                (panel.parent as android.view.ViewGroup).removeView(panel)
                b.detailContent.addView(panel, 0, LinearLayout.LayoutParams(portraitInteractionParams).apply {
                    width = LinearLayout.LayoutParams.MATCH_PARENT
                    leftMargin = 0; rightMargin = 0; marginStart = 0; marginEnd = 0
                    topMargin = 0
                })
            }
            if (author.parent !== b.detailContent) {
                (author.parent as android.view.ViewGroup).removeView(author)
                b.detailContent.addView(author, 1, LinearLayout.LayoutParams(portraitAuthorParams).apply {
                    width = LinearLayout.LayoutParams.MATCH_PARENT
                    leftMargin = 0; rightMargin = 0; marginStart = 0; marginEnd = 0
                })
            }
        } else {
            if (panel.parent !== b.bottomSheet || author.parent !== b.bottomSheet) {
                (panel.parent as android.view.ViewGroup).removeView(panel)
                (author.parent as android.view.ViewGroup).removeView(author)
                b.bottomSheet.addView(author, 1, LinearLayout.LayoutParams(portraitAuthorParams))
                b.bottomSheet.addView(panel, 2, LinearLayout.LayoutParams(portraitInteractionParams))
            }
        }
        if (panel.layoutParams.width != LinearLayout.LayoutParams.MATCH_PARENT) {
            panel.layoutParams = panel.layoutParams.apply { width = LinearLayout.LayoutParams.MATCH_PARENT }
        }
        val row = panel.getChildAt(0)
        if (row.layoutParams.width != LinearLayout.LayoutParams.MATCH_PARENT) {
            row.layoutParams = row.layoutParams.apply { width = LinearLayout.LayoutParams.MATCH_PARENT }
        }
    }

    private fun updateDetailNavigationInsets() = Unit

    private fun updateTagWidths() {
        val width = b.detailTagsGroup.width.takeIf { it > 0 } ?: return
        for (i in 0 until b.detailTagsGroup.childCount) {
            (b.detailTagsGroup.getChildAt(i) as? Chip)?.let { if (it.maxWidth != width) it.maxWidth = width }
        }
    }

    private fun updateSheetBounds() = applyResponsiveDetailLayout()

    private fun setGestures() {
        b.detailImage.scaleType = ImageView.ScaleType.MATRIX
        b.detailImage.viewTreeObserver.addOnPreDrawListener {
            val drawable = b.detailImage.drawable
            if (drawable != null && drawable !== matrixDrawable && b.detailImage.width > 0 && b.detailImage.height > 0) {
                matrixDrawable = drawable
                fitImageToScreen(drawable.intrinsicWidth, drawable.intrinsicHeight)
            }
            true
        }
        var lastFocusX = 0f
        var lastFocusY = 0f
        val scaleGesture = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                draggingMedia = false
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                b.detailImage.parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = currentImageScale()
                val target = (current * detector.scaleFactor).coerceIn(baseScale, maxScale)
                if (current > 0f) {
                    imageMatrix.postScale(target / current, target / current, detector.focusX, detector.focusY)
                    val dx = detector.focusX - lastFocusX
                    val dy = detector.focusY - lastFocusY
                    imageMatrix.postTranslate(dx, dy)
                    lastFocusX = detector.focusX
                    lastFocusY = detector.focusY
                    clampImageMatrix()
                    b.detailImage.imageMatrix = imageMatrix
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                b.detailImage.parent?.requestDisallowInterceptTouchEvent(currentImageScale() > baseScale * 1.05f)
            }
        })
        val gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            // Consume the complete gesture stream so the ImageView's native long-click
            // scheduler cannot compete with horizontal swipes.
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!isImageZoomed()) openLargePreview()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!scaleGesture.isInProgress) {
                    showMore()
                }
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (b.detailImage.visibility != View.VISIBLE) return false
                val current = currentImageScale()
                val target = if (current > baseScale * 1.2f) baseScale else (baseScale * 2.5f).coerceAtMost(maxScale)
                imageMatrix.postScale(target / current, target / current, e.x, e.y)
                clampImageMatrix()
                b.detailImage.imageMatrix = imageMatrix
                b.detailImage.parent?.requestDisallowInterceptTouchEvent(target > baseScale * 1.05f)
                return true
            }
            override fun onScroll(
                downEvent: MotionEvent?,
                currentEvent: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (isImageZoomed()) {
                    imageMatrix.postTranslate(-distanceX, -distanceY)
                    clampImageMatrix()
                    b.detailImage.imageMatrix = imageMatrix
                    return true
                }
                // Treat horizontal movement as an active gesture immediately. This
                // cancels the detector's long-press timeout even for a slow swipe.
                return downEvent != null &&
                    kotlin.math.abs(currentEvent.x - downEvent.x) > kotlin.math.abs(currentEvent.y - downEvent.y)
            }

            override fun onFling(a: MotionEvent?, c: MotionEvent, vx: Float, vy: Float): Boolean {
                // Media translation and page selection are owned by handleTouch.
                // Starting a second transition here caused a rebound for flings
                // below the touch handler's velocity threshold.
                if (a == null || isImageZoomed()) return false
                if (c.y - a.y > 180 && vy > 900) { finish(); return true }
                return false
            }
        })
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val handleTouch: (View, MotionEvent) -> Boolean = { view, event ->
            if (ignoreTransitionGesture) {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    ignoreTransitionGesture = false
                }
                true
            } else if (adjacentLoading) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) ignoreTransitionGesture = true
                true
            } else {
            if (view === b.detailImage) scaleGesture.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                // A pinch owns this stream before ScaleGestureDetector reaches
                // its minimum span. Cancel pending single-finger long presses.
                val cancel = MotionEvent.obtain(event)
                cancel.action = MotionEvent.ACTION_CANCEL
                gesture.onTouchEvent(cancel)
                cancel.recycle()
                draggingMedia = false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.rawX
                    dragStartY = event.rawY
                    draggingMedia = false
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                    view.animate().cancel()
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (view === b.detailImage && (scaleGesture.isInProgress || event.pointerCount > 1)) {
                        draggingMedia = false
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    velocityTracker?.addMovement(event)
                    val dx = event.rawX - dragStartX
                    val dy = event.rawY - dragStartY
                    if (!draggingMedia && event.pointerCount == 1 && !scaleGesture.isInProgress && !isImageZoomed() && kotlin.math.abs(dx) > touchSlop && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.15f) {
                        draggingMedia = true
                        view.animate().cancel()
                    }
                    if (draggingMedia && !isImageZoomed()) {
                        val width = view.width.takeIf { it > 0 } ?: b.root.width.coerceAtLeast(1)
                        val damped = dx * (1f - (kotlin.math.abs(dx) / width.toFloat()).coerceIn(0f, 1f) * 0.12f)
                        view.translationX = damped
                        view.alpha = 1f - (kotlin.math.abs(damped) / width) * 0.22f
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000)
                    val dx = event.rawX - dragStartX
                    val width = view.width.takeIf { it > 0 } ?: b.root.width.coerceAtLeast(1)
                    if (draggingMedia && !isImageZoomed()) {
                        val velocityX = velocityTracker?.xVelocity ?: 0f
                        val direction = ImageGalleryNavigation.releaseDirection(
                            deltaX = dx,
                            velocityX = velocityX,
                            width = width.toFloat(),
                            cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                        )
                        if (direction != null) {
                            loadAdjacent(direction, view)
                        } else {
                            view.animate().translationX(0f).alpha(1f).setDuration(260L)
                                .setInterpolator(OvershootInterpolator(1.05f)).start()
                        }
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        draggingMedia = false
                        velocityTracker?.recycle()
                        velocityTracker = null
                        true
                    } else {
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        velocityTracker?.recycle()
                        velocityTracker = null
                    }
                }
            }
            // Keep tap and long-press behavior while horizontal movement is handled above.
            if (view !== b.detailImage || (!scaleGesture.isInProgress && event.pointerCount <= 1)) gesture.onTouchEvent(event)
            true
            }
        }
        b.detailImage.setOnTouchListener(View.OnTouchListener { view, event -> handleTouch(view, event) })
        b.detailVideoPlayer.setOnTouchListener(View.OnTouchListener { view, event -> handleTouch(view, event) })
        val updateGestureExclusion = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            updateGalleryGestureExclusion(view)
        }
        b.detailImage.addOnLayoutChangeListener(updateGestureExclusion)
        b.detailVideoPlayer.addOnLayoutChangeListener(updateGestureExclusion)
        updateGalleryGestureExclusion(b.detailImage)
        updateGalleryGestureExclusion(b.detailVideoPlayer)
    }

    /** Reserve narrow media-edge strips for gallery paging while leaving system Back elsewhere. */
    private fun updateGalleryGestureExclusion(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || view.width <= 0 || view.height <= 0) return
        val edge = dp(24).coerceAtMost(view.width / 2)
        val height = dp(200).coerceAtMost(view.height)
        val top = ((view.height - height) / 2).coerceAtLeast(0)
        val bottom = (top + height).coerceAtMost(view.height)
        view.systemGestureExclusionRects = listOf(
            Rect(0, top, edge, bottom),
            Rect(view.width - edge, top, view.width, bottom)
        )
    }

    private fun currentImageScale(): Float {
        val values = FloatArray(9)
        imageMatrix.getValues(values)
        return kotlin.math.sqrt(values[Matrix.MSCALE_X] * values[Matrix.MSCALE_X] + values[Matrix.MSKEW_Y] * values[Matrix.MSKEW_Y])
    }

    private fun isImageZoomed() = b.detailImage.visibility == View.VISIBLE && currentImageScale() > baseScale * 1.05f

    private fun fitImageToScreen(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || b.detailImage.width <= 0 || b.detailImage.height <= 0) return
        val fit = imageFitBounds.takeUnless { it.isEmpty } ?: Rect(0, 0, b.detailImage.width, b.detailImage.height)
        baseScale = minOf(fit.width().toFloat() / width, fit.height().toFloat() / height).coerceAtLeast(0.01f)
        maxScale = baseScale * 5f
        imageMatrix.reset()
        imageMatrix.postScale(baseScale, baseScale)
        imageMatrix.postTranslate(fit.left + (fit.width() - width * baseScale) / 2f,
            fit.top + (fit.height() - height * baseScale) / 2f)
        b.detailImage.imageMatrix = imageMatrix
    }

    private fun clampImageMatrix() {
        val drawable = b.detailImage.drawable ?: return
        val values = FloatArray(9)
        imageMatrix.getValues(values)
        val scale = currentImageScale()
        if (scale <= baseScale * 1.001f) {
            fitImageToScreen(drawable.intrinsicWidth, drawable.intrinsicHeight)
            return
        }
        val contentWidth = drawable.intrinsicWidth * scale
        val contentHeight = drawable.intrinsicHeight * scale
        val minX = if (contentWidth <= b.detailImage.width) (b.detailImage.width - contentWidth) / 2f else b.detailImage.width - contentWidth
        val maxX = if (contentWidth <= b.detailImage.width) minX else 0f
        val minY = if (contentHeight <= b.detailImage.height) (b.detailImage.height - contentHeight) / 2f else b.detailImage.height - contentHeight
        val maxY = if (contentHeight <= b.detailImage.height) minY else 0f
        imageMatrix.postTranslate((values[Matrix.MTRANS_X].coerceIn(minX, maxX) - values[Matrix.MTRANS_X]), (values[Matrix.MTRANS_Y].coerceIn(minY, maxY) - values[Matrix.MTRANS_Y]))
    }

    private fun loadDetails() {
        detailJob?.cancel()
        val requestedId = image.id
        val requestGeneration = ++detailRequestGeneration
        val requestStartedAt = android.os.SystemClock.elapsedRealtime()
        val hasPreviewDetails = image.title.isNotBlank() || !image.uploader.isNullOrBlank() ||
            image.tags.isNotEmpty() || !image.description.isNullOrBlank() || image.faves > 0 || image.commentCount > 0
        b.detailLoadStatus.isVisible = !hasPreviewDetails
        if (!hasPreviewDetails) b.detailLoadStatus.setText(com.kerybotu.derpibooru.mirror.R.string.detail_loading)
        b.detailLoadStatus.isEnabled = false
        detailJob = scope.launch {
            CrashDiagnostics.recordEvent("ImageDetailActivity", "details_load_started image_id=$requestedId")
            try {
                val json = withContext(Dispatchers.IO) {
                    val filter = NetworkManager.currentFilterParam(this@ImageDetailActivity, "?")
                    NetworkManager.getApi(this@ImageDetailActivity, "images/$requestedId$filter")
                }
                if (detailRequestGeneration != requestGeneration || image.id != requestedId || isFinishing || isDestroyed) return@launch
                if (json == null) {
                    showDetailLoadFailure()
                    CrashDiagnostics.recordEvent("ImageDetailActivity", "details_load_empty image_id=$requestedId")
                    return@launch
                }
                val item = JSONObject(json).getJSONObject("image")
                fun statistic(key: String): Int? = (item.opt(key) as? Number)?.toInt()?.takeIf { it >= 0 }
                compactStatistics.bind(DetailStatistics(
                    statistic("upvotes"), statistic("faves"), statistic("comment_count")
                ))
                val mime = item.optString("mime_type").takeIf(String::isNotBlank) ?: image.mimeType
                val title = item.takeUnless { it.isNull("name") }?.optString("name").orEmpty()
                val uploader = item.takeUnless { it.isNull("uploader") }?.optString("uploader")?.takeIf(String::isNotBlank)
                val createdAt = item.optString("created_at").takeIf(String::isNotBlank) ?: image.createdAt
                val description = item.takeUnless { it.isNull("description") }?.optString("description").orEmpty()
                val full = item.optJSONObject("representations")?.optString("full")?.takeIf(String::isNotBlank) ?: image.fullUrl
                val tags = item.optJSONArray("tags")?.let { array -> List(array.length()) { array.optString(it) } }
                    ?.takeIf { it.isNotEmpty() } ?: image.tags
                mediaMimeType = mime
                image = image.copy(
                    title = title, uploader = uploader, createdAt = createdAt, description = description,
                    tags = tags, fullUrl = full,
                    faves = item.optInt("faves", image.faves), upvotes = item.optInt("upvotes", image.upvotes),
                    downvotes = item.optInt("downvotes", image.downvotes),
                    commentCount = item.optInt("comment_count", image.commentCount), mimeType = mime,
                    uploaderId = item.optLong("uploader_id", image.uploaderId ?: -1L).takeIf { it > 0L },
                    spoilered = item.optBoolean("spoilered", image.spoilered)
                )
                fullUrl = full
                imageLoadFailedId = null
                showPreview(mime, if (mime?.startsWith("video/") == true) fullUrl ?: image.fullUrl ?: image.thumbnailUrl else fullUrl ?: image.fullUrl ?: image.thumbnailUrl)
                b.detailTitle.text = "#${image.id}"
                b.detailArtworkTitle.text = title
                b.detailArtworkTitle.visibility = if (title.isBlank()) View.GONE else View.VISIBLE
                b.detailUploader.text = uploader ?: "未知上传者"
                b.detailCreatedAt.text = createdAt?.take(10).orEmpty()
                b.detailUploader.visibility = if (AppSettings.isUploaderHidden(this@ImageDetailActivity)) View.GONE else View.VISIBLE
                uploaderId = image.uploaderId
                bindUploaderClick()
                bindDescription(description)
                b.detailFaves.text = image.faves.toString()
                b.detailUpvotes.text = image.upvotes.toString()
                b.detailScore.visibility = View.GONE
                b.detailCommentsCount.text = image.commentCount.toString()
                bindTags(tags)
                bindSources(item.optJSONArray("source_urls")?.let { urls ->
                    (0 until urls.length()).map { urls.optString(it) }
                }.orEmpty())
                loadComments()
                CrashDiagnostics.recordEvent("ImageDetailActivity", "details_load_succeeded image_id=$requestedId")
                b.detailTitle.postOnAnimation {
                    if (detailRequestGeneration == requestGeneration && image.id == requestedId && !isFinishing && !isDestroyed) {
                        CrashDiagnostics.recordEvent(
                            "ImageDetailActivity",
                            "details_info_frame image_id=$requestedId latency_ms=${android.os.SystemClock.elapsedRealtime() - requestStartedAt}"
                        )
                    }
                }
                if (detailRequestGeneration == requestGeneration && image.id == requestedId && imageLoadFailedId != requestedId) b.detailLoadStatus.isVisible = false
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                CrashDiagnostics.recordFailure("ImageDetailActivity", "details_load image_id=${image.id}", error)
                if (detailRequestGeneration == requestGeneration && !isFinishing && !isDestroyed && image.id == requestedId) {
                    showDetailLoadFailure()
                }
            }
        }
    }

    private fun showDetailLoadFailure() {
        compactStatistics.bind(null)
        b.detailLoadStatus.setText(com.kerybotu.derpibooru.mirror.R.string.detail_retry)
        b.detailLoadStatus.isVisible = true
        b.detailLoadStatus.isEnabled = true
    }

    /** Loads the nearest image in the current gallery/search space for a horizontal swipe. */
    private fun loadAdjacent(direction: Int, outgoing: View? = null) {
        if (adjacentLoading) return
        adjacentLoading = true
        val media = outgoing ?: if (b.detailVideoPlayer.visibility == View.VISIBLE) b.detailVideoPlayer else b.detailImage
        val width = (media.width.takeIf { it > 0 } ?: b.root.width.coerceAtLeast(1)).toFloat()
        val exitX = ImageGalleryNavigation.exitTranslationSign(direction) * width
        // Keep the finger position until the target is ready, then perform one
        // completion animation. A pre-flight exit here followed by
        // animateToAdjacent() used to produce a rebound and a second delayed
        // transition.
        scope.launch {
            val source = if (galleryImageIds == null) "id_order" else "gallery"
            CrashDiagnostics.recordEvent("ImageDetailActivity", "swipe direction=$direction source=$source image_id=${image.id}")
            var transitionStarted = false
            try {
                val currentId = image.id
                val next = galleryImageIds?.let { ids ->
                    val nextId = ImageGalleryNavigation.adjacentId(ids, currentId, direction)
                    nextId?.let { id ->
                        galleryImagePreviews?.firstOrNull { it.id == id }?.toImage()
                            ?: Image(id, "", null, 0, 0, 0, 0, 0, 0, 0, emptyList())
                    }
                } ?: if (galleryImageIds == null) {
                    val (comparator, sortDirection) = ImageGalleryNavigation.idOrderQuery(direction) ?: return@launch
                    val query = NetworkManager.encodeSearchQuery(this@ImageDetailActivity, "id.$comparator:$currentId")
                    val filter = NetworkManager.currentFilterParam(this@ImageDetailActivity)
                    val raw = withContext(Dispatchers.IO) {
                        NetworkManager.getApi(this@ImageDetailActivity, "search/images?q=$query&sf=id&sd=$sortDirection&per_page=1$filter")
                    }
                    runCatching { JSONObject(raw.orEmpty()).optJSONArray("images")?.optJSONObject(0)?.let(::parseImage) }.getOrNull()
                } else null
                if (image.id != currentId || isFinishing || isDestroyed) return@launch
                if (next != null) {
                    CrashDiagnostics.recordEvent("ImageDetailActivity", "swipe_target image_id=${next.id}")
                    transitionStarted = true
                    animateToAdjacent(next, direction, media, exitX)
                } else {
                    media.animate().translationX(0f).alpha(1f).setDuration(300L)
                        .setInterpolator(OvershootInterpolator(1.05f)).start()
                    val ids = galleryImageIds
                    val message = if (ids != null && image.id !in ids) {
                        "当前图片不在搜索结果列表中，无法按该列表浏览"
                    } else if (direction > 0) "已经是最后一张" else "已经是第一张"
                    Toast.makeText(this@ImageDetailActivity, message, Toast.LENGTH_SHORT).show()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                CrashDiagnostics.recordFailure("ImageDetailActivity", "adjacent_image image_id=${image.id}", error)
                if (!isFinishing && !isDestroyed) {
                    media.animate().translationX(0f).alpha(1f).setDuration(300L).start()
                    Toast.makeText(this@ImageDetailActivity, "图片切换失败，请重试", Toast.LENGTH_SHORT).show()
                }
            } finally {
                if (!transitionStarted) adjacentLoading = false
            }
        }
    }

    /** Slides the current media away, swaps data, then slides the new media in. */
    private fun animateToAdjacent(next: Image, direction: Int, outgoing: View? = null, exitXOverride: Float? = null) {
        val outgoing = outgoing ?: if (b.detailVideoPlayer.visibility == View.VISIBLE) b.detailVideoPlayer else b.detailImage
        val distance = (outgoing.width.takeIf { it > 0 } ?: b.root.width.coerceAtLeast(1)).toFloat()
        val exitX = exitXOverride ?: (ImageGalleryNavigation.exitTranslationSign(direction) * distance)
        val enterX = -exitX
        outgoing.animate()
            .translationX(exitX)
            .alpha(0f)
            .setDuration(180L)
            .withEndAction {
                val previousId = image.id
                image = next
                recordWatchHistory()
                CrashDiagnostics.recordEvent("ImageDetailActivity", "image_changed from=$previousId to=${next.id} experiment=${renderExperiment.id}")
                uploaderId = next.uploaderId
                fullUrl = next.fullUrl ?: next.thumbnailUrl
                mediaMimeType = next.mimeType
                expanded = false
                spoilerRevealed = false
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

    private fun recordWatchHistory() {
        if (!::image.isInitialized || image.id <= 0) return
        scope.launch(Dispatchers.IO) {
            runCatching { WatchHistoryStore(this@ImageDetailActivity).use { it.record(image) } }
        }
    }

    private fun parseImage(o: JSONObject): Image {
        val reps = o.optJSONObject("representations")
        val tags = o.optJSONArray("tags")?.let { array -> List(array.length()) { array.optString(it) } }.orEmpty()
        return Image(o.optInt("id"), o.optString("name", ""), reps?.optString("small", null) ?: reps?.optString("thumb", null), o.optInt("width"), o.optInt("height"), o.optInt("score"), o.optInt("faves"), o.optInt("upvotes"), o.optInt("downvotes"), o.optInt("comment_count"), tags, reps?.optString("full", null), o.optString("uploader", null), o.optString("created_at", null), o.optString("description", null), o.optString("mime_type", null), o.optLong("uploader_id", -1L).takeIf { it > 0L }, o.optBoolean("spoilered", false))
    }

    private fun bindUploaderClick() {
        b.detailUploader.isClickable = uploaderId != null
        b.detailUploader.setOnClickListener {
            uploaderId?.let { userId -> startActivity(Intent(this, ProfileActivity::class.java).putExtra(ProfileActivity.EXTRA_USER_ID, userId)) }
        }
    }

    private fun loadComments() {
        commentsJob?.cancel()
        val requestGeneration = ++commentsRequestGeneration
        val requestedId = image.id
        commentsJob = scope.launch {
            try {
                val query = java.net.URLEncoder.encode("image_id:$requestedId", "UTF-8")
                val raw = withContext(Dispatchers.IO) {
                    NetworkManager.getApi(this@ImageDetailActivity, "search/comments?q=$query&per_page=50")
                }
                if (commentsRequestGeneration != requestGeneration || image.id != requestedId || isFinishing || isDestroyed) return@launch
                val comments = runCatching { JSONObject(raw.orEmpty()).optJSONArray("comments") }.getOrNull()
                if (comments == null || comments.length() == 0) {
                    b.detailCommentsPreview.removeAllViews()
                    b.detailCommentsStatus.text = if (raw == null) "评论暂时无法加载" else "暂无评论"
                    b.detailCommentsStatus.visibility = View.VISIBLE
                    return@launch
                }
                b.detailCommentsStatus.visibility = View.GONE
                b.detailCommentsPreview.removeAllViews()
                for (index in 0 until comments.length()) {
                    val comment = comments.optJSONObject(index)?.let(Comment::fromJson) ?: continue
                    val card = GlassCommentCard(this@ImageDetailActivity)
                    card.bind(comment, null, { }, { imageId -> startActivity(Intent(this@ImageDetailActivity, ImageDetailActivity::class.java).putExtra("image_id", imageId)) })
                    b.detailCommentsPreview.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                CrashDiagnostics.recordFailure("ImageDetailActivity", "comments image_id=$requestedId", error)
                if (commentsRequestGeneration == requestGeneration && !isFinishing && !isDestroyed && image.id == requestedId) {
                    b.detailCommentsStatus.text = "评论暂时无法加载"
                    b.detailCommentsStatus.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::infoCard.isInitialized) outState.putBoolean("infoExpanded", infoCard.progress >= .5f)
        super.onSaveInstanceState(outState)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun bindDescription(raw: String) {
        descriptionGeneration++
        descriptionOriginal = raw
        descriptionTranslation = null
        expanded = false
        b.detailDescription.maxLines = 3
        b.btnExpandDescription.text = "展开阅读"
        b.detailDescriptionPanel.visibility = if (raw.isBlank() && image.title.isBlank()) View.GONE else View.VISIBLE
        if (::compactSummary.isInitialized) {
            compactSummary.text = "#${image.id} · ${image.tags.size} 个标签\n${raw.take(180)}\n← 向左拖动或点击展开完整信息"
        }
        b.detailDescription.visibility = if (raw.isBlank()) View.GONE else View.VISIBLE
        b.detailDescriptionActions.visibility = if (raw.isBlank()) View.GONE else View.VISIBLE
        RichText.bind(b.detailDescription, raw)
        b.btnTranslateDescription.visibility = if (raw.isBlank()) View.GONE else View.VISIBLE
        b.btnTranslateDescription.text = "翻译"
        b.btnTranslateDescription.isEnabled = true
    }

    private fun bindSources(urls: List<String>) {
        val text = urls.mapNotNull { RichTextParser.safeUrl(it) }.filter { it.startsWith("https://", true) || it.startsWith("http://", true) }.joinToString("\n")
        b.detailSource.text = text
        b.detailSourcePanel.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun toggleDescriptionTranslation() {
        descriptionTranslation?.let {
            descriptionTranslation = null
            RichText.bind(b.detailDescription, descriptionOriginal)
            b.btnTranslateDescription.text = "翻译"
            return
        }
        b.btnTranslateDescription.isEnabled = false
        b.btnTranslateDescription.text = "翻译中…"
        val requestedGeneration = descriptionGeneration
        val requestedDescription = descriptionOriginal
        scope.launch {
            NiuTransService.translate(requestedDescription).onSuccess {
                if (requestedGeneration != descriptionGeneration || isFinishing || isDestroyed) return@onSuccess
                descriptionTranslation = it
                RichText.bind(b.detailDescription, it)
                b.btnTranslateDescription.text = "原文"
            }.onFailure {
                if (requestedGeneration == descriptionGeneration && !isFinishing && !isDestroyed) {
                    Toast.makeText(this@ImageDetailActivity, "翻译失败", Toast.LENGTH_SHORT).show()
                    b.btnTranslateDescription.text = "翻译"
                }
            }
            if (requestedGeneration == descriptionGeneration) b.btnTranslateDescription.isEnabled = true
        }
    }

    private fun showPreview(mime: String?, url: String?) {
        if (mime?.lowercase()?.startsWith("video/") == true && url != null) {
            b.detailImage.visibility = View.GONE
            b.detailVideoPlayer.visibility = View.VISIBLE
            previewPlayer?.release()
            previewPlayer = MediaPreviewPlayer(this, b.detailVideoPlayer).also { it.load(url) }
            SpoilerCover.bind(b.detailSpoilerCover, b.detailVideoPlayer, image.spoilered, spoilerRevealed) {
                spoilerRevealed = true
            }
        } else {
            b.detailVideoPlayer.visibility = View.GONE
            b.detailImage.visibility = View.VISIBLE
            matrixDrawable = null
            imageMatrix.reset()
            url?.let { imageUrl ->
                val requestedId = image.id
                CrashDiagnostics.recordEvent("ImageDetailActivity", "image_load_started image_id=$requestedId")
                CdnImageGate.load(
                    b.detailImage,
                    imageUrl,
                    image.thumbnailUrl,
                    AppSettings.getCdnThreads(this)
                ) { error ->
                    if (!isFinishing && !isDestroyed && image.id == requestedId) {
                        imageLoadFailedId = requestedId
                        CrashDiagnostics.recordFailure("ImageDetailActivity", "image_load image_id=$requestedId", error)
                        b.detailLoadStatus.text = "图片暂时无法加载，点击重试"
                        b.detailLoadStatus.isVisible = true
                        b.detailLoadStatus.isEnabled = true
                    }
                }
            }
            SpoilerCover.bind(b.detailSpoilerCover, b.detailImage, image.spoilered, spoilerRevealed) {
                spoilerRevealed = true
            }
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

    private fun toggleDescription() { expanded = !expanded; b.detailDescription.maxLines = if (expanded) Int.MAX_VALUE else 3; b.btnExpandDescription.text = if (expanded) "收起" else "展开阅读"; infoCard.snap(DetailInfoState.EXPANDED) }
    private fun share() { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "https://${AppSettings.getTargetDomain(this@ImageDetailActivity)}/images/${image.id}") }, "分享图片")) }
    private fun showMore() {
        val items = listOf(
            GlassMenuItem("download", com.kerybotu.derpibooru.mirror.R.drawable.ic_download, "下载") { enqueueDownload() },
            GlassMenuItem("original", com.kerybotu.derpibooru.mirror.R.drawable.ic_image, "查看原图") { fullUrl?.let { url -> startActivity(Intent(this, FullScreenImageActivity::class.java).putExtra("full_url", url).putExtra("image_id", image.id)) } },
            GlassMenuItem("copy", com.kerybotu.derpibooru.mirror.R.drawable.ic_bookmark, "复制链接") { copyImageLink() }
        )
        GlassMenuCard(this, items, b.btnMore).show()
    }

    private fun enqueueDownload() {
        val url = fullUrl ?: image.fullUrl ?: image.thumbnailUrl
        if (url.isNullOrBlank()) Toast.makeText(this, "暂无可下载地址", Toast.LENGTH_SHORT).show()
        else { DownloadQueueManager.get(this).enqueueImages(listOf(image.copy(fullUrl = url))); Toast.makeText(this, "已加入下载队列", Toast.LENGTH_SHORT).show() }
    }

    private fun copyImageLink() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("图片链接", "https://${AppSettings.getTargetDomain(this)}/images/${image.id}"))
        Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show()
    }
    override fun onDestroy() {
        if (::image.isInitialized) CrashDiagnostics.recordEvent("ImageDetailActivity", "destroyed image_id=${image.id}")
        if (::b.isInitialized) {
            b.root.removeCallbacks(resumeBackdropRunnable)
            if (b.root.viewTreeObserver.isAlive) b.root.viewTreeObserver.removeOnPreDrawListener(resumePreDrawListener)
            PreparedBackdropSource.release(b.detailBackdrop)
        }
        removePaletteObserver?.invoke()
        if (b.root.viewTreeObserver.isAlive) b.root.viewTreeObserver.removeOnPreDrawListener(glassVisibilityListener)
        lastGlassBounds.clear()
        tagJob?.cancel(); previewPlayer?.release(); scope.cancel(); super.onDestroy()
    }
}
