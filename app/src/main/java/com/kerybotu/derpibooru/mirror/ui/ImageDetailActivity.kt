package com.kerybotu.derpibooru.mirror.ui

import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
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
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.chip.Chip
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.databinding.ActivityImageDetailBinding
import com.kerybotu.derpibooru.mirror.model.Image
import com.kerybotu.derpibooru.mirror.model.Comment
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import com.kerybotu.derpibooru.mirror.dict.TagDictionary
import com.kerybotu.derpibooru.mirror.dict.TagEntry
import com.kerybotu.derpibooru.mirror.translate.NiuTransService
import com.kerybotu.derpibooru.mirror.download.DownloadQueueManager
import com.kerybotu.derpibooru.mirror.favorites.FavoriteItem
import com.kerybotu.derpibooru.mirror.favorites.LocalFavoritesStore
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
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var draggingMedia = false
    private var velocityTracker: VelocityTracker? = null
    private var spoilerRevealed = false
    private val imageMatrix = Matrix()
    private var baseScale = 1f
    private var maxScale = 5f
    private var matrixDrawable: Drawable? = null
    private var renderingActive = false
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
        PaletteManager.apply(this)
        applyPalette()
        image = intent.getSerializableExtra("image") as? Image
            ?: intent.getIntExtra("image_id", -1).takeIf { it > 0 }?.let { Image(id = it, title = "", thumbnailUrl = null, width = 0, height = 0, score = 0, faves = 0, upvotes = 0, downvotes = 0, commentCount = 0, tags = emptyList()) }
            ?: run { finish(); return }
        mediaMimeType = image.mimeType
        uploaderId = image.uploaderId
        sheet = BottomSheetBehavior.from(b.bottomSheet)
        sheet.state = BottomSheetBehavior.STATE_COLLAPSED
        b.detailBack.setOnClickListener { finish() }
        Ui2DesignSystem.applyPressFeedback(b.detailBack)
        b.btnShare.setOnClickListener { share() }
        b.btnMore.setOnClickListener { showMore() }
        b.detailLoadStatus.setOnClickListener { loadDetails() }
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
        b.root.addOnLayoutChangeListener { _, left, _, right, bottom, oldLeft, _, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom != oldBottom) {
                applyIslandWidths()
                updateSheetBounds()
            }
        }
        b.detailTopIsland.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSheetBounds() }
        b.root.post { applyIslandWidths(); updateSheetBounds() }
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
            removePaletteObserver?.invoke()
            removePaletteObserver = PaletteManager.observe(this, paletteObserver)
        }
    }

    override fun onPause() {
        renderingActive = false
        updateGlassRendering()
        removePaletteObserver?.invoke()
        removePaletteObserver = null
        super.onPause()
    }

    private fun updateGlassRendering() {
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
        fun update(island: LiquidGlassView) {
            if (island === b.detailTopIsland && !renderingActive) {
                // setRenderingActive has detached this source. Reattaching it
                // here would invalidate it on every paused-window pre-draw.
                lastGlassBounds.remove(island)
                return
            }
            // Capture only artwork, never the transparent sheet or its text.
            island.backdropSource = b.detailBackdrop
            val active = renderingActive && island.isShown && island.getGlobalVisibleRect(visibleBounds)
            val previous = lastGlassBounds[island]
            val moved = active && previous != visibleBounds
            if (active) {
                if (previous == null) lastGlassBounds[island] = Rect(visibleBounds)
                else previous.set(visibleBounds)
            } else lastGlassBounds.remove(island)
            // Retain the actual blurred/refracted texture while artwork is
            // still. The CPU renderer otherwise re-blurs it on every frame.
            // Gestures, sheet movement, GIF/video and theme backgrounds refresh
            // the optical result; no material or optical effect is disabled.
            val dynamic = active && (animated || artworkChanged || moved)
            if (island.enableDynamicBackground != dynamic) island.enableDynamicBackground = dynamic
            island.enableSensorHighlight = false
        }
        b.detailTopIsland.setRenderingActive(renderingActive, b.detailBackdrop)
        readingIslands.forEach(::update)
        update(b.detailTopIsland)
    }

    private fun bindInitial() {
        spoilerRevealed = false
        b.detailFaves.text = image.faves.toString(); b.detailUpvotes.text = image.upvotes.toString()
        b.detailCommentsCount.text = image.commentCount.toString()
        b.detailUploader.text = image.uploader ?: "未知上传者"; b.detailCreatedAt.text = image.createdAt?.take(10) ?: ""
        b.detailTitle.text = image.title.ifBlank { "图片详情" }
        b.detailArtworkTitle.text = image.title
        b.detailArtworkTitle.visibility = if (image.title.isBlank()) View.GONE else View.VISIBLE
        bindDescription(image.description.orEmpty())
        bindSources(emptyList())
        b.detailUploader.visibility = if (AppSettings.isUploaderHidden(this)) View.GONE else View.VISIBLE
        bindUploaderClick()
        b.detailScore.visibility = View.GONE
        bindTags(image.tags)
        showPreview(image.mimeType, if (image.mimeType?.startsWith("video/") == true) image.fullUrl ?: image.thumbnailUrl else image.thumbnailUrl)
    }

    private fun bindTags(tags: List<String>) {
        tagJob?.cancel()
        if (tags.isEmpty()) {
            b.detailTagsGroup.removeAllViews()
            b.detailTagsPanel.visibility = View.GONE
            return
        }
        tagJob = scope.launch {
            val translated = if (AppSettings.isTagTranslationEnabled(this@ImageDetailActivity)) {
                withContext(Dispatchers.IO) { TagDictionary.sortAndTranslate(this@ImageDetailActivity, tags) }
            } else {
                tags.map { TagEntry(it, it, -1, 0, emptyList(), sourceName = it) }
            }
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
        b.detailVideoPlayer.setBackgroundColor(palette.mediaSurface)
        GlassWidgetStyle.apply(b.detailAuthorPanel, Ui2DesignSystem.Shape.large, palette, GlassWidgetStyle.MaterialRole.READING)
        GlassWidgetStyle.apply(b.detailInteractionPanel, Ui2DesignSystem.Shape.island, palette, GlassWidgetStyle.MaterialRole.READING)
        listOf(b.detailDescriptionPanel, b.detailTagsPanel, b.detailSourcePanel, b.detailCommentsHeader).forEach {
            GlassWidgetStyle.apply(it, Ui2DesignSystem.Shape.large, palette, GlassWidgetStyle.MaterialRole.READING)
        }
        b.bottomSheet.background = ColorDrawable(android.graphics.Color.TRANSPARENT)
        b.detailDivider.setBackgroundColor(palette.divider)
        b.sheetHandle.setBackgroundColor(palette.muted)
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
            b.bottomSheet.setPadding(bars.left, 0, bars.right, 0)
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
    }

    private fun applyIslandWidths() {
        val available = (b.root.width - b.bottomSheet.paddingLeft - b.bottomSheet.paddingRight).takeIf { it > 0 } ?: return
        // Reuse the window-width policy's bounded island column for readable text.
        val width = AdaptiveLayoutPolicy.topIslandWidthPx(this, available)
            .coerceAtMost((available - dp(24)).coerceAtLeast(dp(1)))
        listOf(b.detailAuthorPanel, b.detailInteractionPanel).forEach { island ->
            val params = island.layoutParams as? android.widget.LinearLayout.LayoutParams ?: return@forEach
            if (params.width != width || params.gravity != android.view.Gravity.CENTER_HORIZONTAL) {
                params.width = width
                params.gravity = android.view.Gravity.CENTER_HORIZONTAL
                island.layoutParams = params
            }
        }
        b.detailTopIsland.layoutParams = b.detailTopIsland.layoutParams.apply { this.width = width }
        b.detailContent.layoutParams = b.detailContent.layoutParams.apply { this.width = width + dp(24) }
        b.detailTagsGroup.post { updateTagWidths() }
    }

    private fun updateTagWidths() {
        val width = b.detailTagsGroup.width.takeIf { it > 0 } ?: return
        for (i in 0 until b.detailTagsGroup.childCount) {
            (b.detailTagsGroup.getChildAt(i) as? Chip)?.let { if (it.maxWidth != width) it.maxWidth = width }
        }
    }

    private fun updateSheetBounds() {
        if (!::sheet.isInitialized || b.root.height <= 0 || b.detailTopIsland.height <= 0) return
        // Use the laid-out, inset-aware navigation boundary. Restoring its
        // elevation must not place it over the expanded author island.
        val top = (b.detailTopIsland.bottom + dp(Ui2DesignSystem.Spacing.islandGap))
            .coerceIn(0, b.root.height - 1)
        if (sheet.expandedOffset != top) sheet.expandedOffset = top
        // A full-height sheet moved down would clip the last comments. Keep
        // its viewport inside the root instead of padding by a screen offset.
        val height = b.root.height - top
        if (b.bottomSheet.layoutParams.height != height) {
            b.bottomSheet.layoutParams = b.bottomSheet.layoutParams.apply { this.height = height }
        }
        val peek = b.detailInteractionPanel.bottom + bottomInset
        if (peek > 0 && sheet.peekHeight != peek) sheet.peekHeight = peek.coerceAtMost(height)
    }

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
                if (a == null) return false
                if (isImageZoomed()) return false
                if (kotlin.math.abs(vx) > kotlin.math.abs(vy) && kotlin.math.abs(vx) > 500f) {
                    loadAdjacent(if (vx < 0f) 1 else -1)
                    return true
                }
                if (c.y - a.y > 180 && vy > 900) { finish(); return true }
                return false
            }
        })
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val handleTouch: (View, MotionEvent) -> Boolean = { view, event ->
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
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        val velocityX = velocityTracker?.xVelocity ?: 0f
                        val threshold = width * 0.22f
                        val commit = kotlin.math.abs(dx) > threshold || kotlin.math.abs(velocityX) > 900f
                        if (commit) {
                            loadAdjacent(if (dx < 0f) 1 else -1, view, dx)
                        } else {
                            view.animate().translationX(0f).alpha(1f).setDuration(260L)
                                .setInterpolator(OvershootInterpolator(1.05f)).start()
                        }
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
        b.detailImage.setOnTouchListener(View.OnTouchListener { view, event -> handleTouch(view, event) })
        b.detailVideoPlayer.setOnTouchListener(View.OnTouchListener { view, event -> handleTouch(view, event) })
    }

    private fun currentImageScale(): Float {
        val values = FloatArray(9)
        imageMatrix.getValues(values)
        return kotlin.math.sqrt(values[Matrix.MSCALE_X] * values[Matrix.MSCALE_X] + values[Matrix.MSKEW_Y] * values[Matrix.MSKEW_Y])
    }

    private fun isImageZoomed() = b.detailImage.visibility == View.VISIBLE && currentImageScale() > baseScale * 1.05f

    private fun fitImageToScreen(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || b.detailImage.width <= 0 || b.detailImage.height <= 0) return
        baseScale = minOf(b.detailImage.width.toFloat() / width, b.detailImage.height.toFloat() / height).coerceAtLeast(0.01f)
        maxScale = baseScale * 5f
        imageMatrix.reset()
        imageMatrix.postScale(baseScale, baseScale)
        imageMatrix.postTranslate((b.detailImage.width - width * baseScale) / 2f, (b.detailImage.height - height * baseScale) / 2f)
        b.detailImage.imageMatrix = imageMatrix
    }

    private fun clampImageMatrix() {
        val drawable = b.detailImage.drawable ?: return
        val values = FloatArray(9)
        imageMatrix.getValues(values)
        val scale = currentImageScale()
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
        b.detailLoadStatus.setText(com.kerybotu.derpibooru.mirror.R.string.detail_loading)
        b.detailLoadStatus.isVisible = true
        b.detailLoadStatus.isEnabled = false
        detailJob = scope.launch {
        val json = withContext(Dispatchers.IO) {
            val filter = NetworkManager.currentFilterParam(this@ImageDetailActivity, "?")
            NetworkManager.getApi(this@ImageDetailActivity, "images/${image.id}$filter")
        }
        if (json == null) {
            showDetailLoadFailure()
            return@launch
        }
        runCatching {
            val item = JSONObject(json).getJSONObject("image")
            val mime = item.optString("mime_type", image.mimeType)
            mediaMimeType = mime
            image = image.copy(spoilered = item.optBoolean("spoilered", image.spoilered))
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
            bindSources(item.optJSONArray("source_urls")?.let { urls ->
                (0 until urls.length()).map { urls.optString(it) }
            }.orEmpty())
            loadComments()
        }.onSuccess { b.detailLoadStatus.isVisible = false }
            .onFailure { showDetailLoadFailure() }
        }
    }

    private fun showDetailLoadFailure() {
        b.detailLoadStatus.setText(com.kerybotu.derpibooru.mirror.R.string.detail_retry)
        b.detailLoadStatus.isVisible = true
        b.detailLoadStatus.isEnabled = true
    }

    /** Loads the nearest image in the current gallery/search space for a horizontal swipe. */
    private fun loadAdjacent(direction: Int, outgoing: View? = null, dragOffset: Float = 0f) {
        if (adjacentLoading) return
        adjacentLoading = true
        val media = outgoing ?: if (b.detailVideoPlayer.visibility == View.VISIBLE) b.detailVideoPlayer else b.detailImage
        val width = (media.width.takeIf { it > 0 } ?: b.root.width.coerceAtLeast(1)).toFloat()
        val exitX = if (direction > 0) -width else width
        // Continue from the finger position immediately while the adjacent item loads.
        if (outgoing != null) {
            media.animate().translationX(exitX).alpha(0f).setDuration(150L)
                .setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
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
                animateToAdjacent(next, direction, media, exitX)
            } else {
                media.animate().translationX(0f).alpha(1f).setDuration(300L)
                    .setInterpolator(OvershootInterpolator(1.05f)).start()
                Toast.makeText(this@ImageDetailActivity, if (direction > 0) "已经是最后一张" else "已经是第一张", Toast.LENGTH_SHORT).show()
                adjacentLoading = false
            }
        }
    }

    /** Slides the current media away, swaps data, then slides the new media in. */
    private fun animateToAdjacent(next: Image, direction: Int, outgoing: View? = null, exitXOverride: Float? = null) {
        val outgoing = outgoing ?: if (b.detailVideoPlayer.visibility == View.VISIBLE) b.detailVideoPlayer else b.detailImage
        val distance = (outgoing.width.takeIf { it > 0 } ?: b.root.width.coerceAtLeast(1)).toFloat()
        val exitX = exitXOverride ?: if (direction > 0) -distance else distance
        val enterX = -exitX
        outgoing.animate()
            .translationX(exitX)
            .alpha(0f)
            .setDuration(180L)
            .withEndAction {
                image = next
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

    private fun parseImage(o: JSONObject): Image {
        val reps = o.optJSONObject("representations")
        val tags = o.optJSONArray("tags")?.let { array -> List(array.length()) { array.optString(it) } }.orEmpty()
        return Image(o.optInt("id"), "", reps?.optString("small", null) ?: reps?.optString("thumb", null), o.optInt("width"), o.optInt("height"), o.optInt("score"), o.optInt("faves"), o.optInt("upvotes"), o.optInt("downvotes"), o.optInt("comment_count"), tags, reps?.optString("full", null), o.optString("uploader", null), o.optString("created_at", null), o.optString("description", null), o.optString("mime_type", null), o.optLong("uploader_id", -1L).takeIf { it > 0L }, o.optBoolean("spoilered", false))
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
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun bindDescription(raw: String) {
        descriptionOriginal = raw
        descriptionTranslation = null
        expanded = false
        b.detailDescription.maxLines = 3
        b.btnExpandDescription.text = "展开阅读"
        b.detailDescriptionPanel.visibility = if (raw.isBlank() && image.title.isBlank()) View.GONE else View.VISIBLE
        b.detailDescription.visibility = if (raw.isBlank()) View.GONE else View.VISIBLE
        b.detailDescriptionActions.visibility = if (raw.isBlank()) View.GONE else View.VISIBLE
        b.detailDescription.text = raw
        b.btnTranslateDescription.visibility = if (raw.isBlank()) View.GONE else View.VISIBLE
        b.btnTranslateDescription.text = "翻译"
        b.btnTranslateDescription.isEnabled = true
    }

    private fun bindSources(urls: List<String>) {
        val text = urls.filter { it.isNotBlank() }.joinToString("\n")
        b.detailSource.text = text
        b.detailSourcePanel.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
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
            url?.let { CdnImageGate.load(b.detailImage, it, image.thumbnailUrl, AppSettings.getCdnThreads(this)) }
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

    private fun toggleDescription() { expanded = !expanded; b.detailDescription.maxLines = if (expanded) Int.MAX_VALUE else 3; b.btnExpandDescription.text = if (expanded) "收起" else "展开阅读"; sheet.state = BottomSheetBehavior.STATE_HALF_EXPANDED }
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
        removePaletteObserver?.invoke()
        if (b.root.viewTreeObserver.isAlive) b.root.viewTreeObserver.removeOnPreDrawListener(glassVisibilityListener)
        lastGlassBounds.clear()
        tagJob?.cancel(); previewPlayer?.release(); scope.cancel(); super.onDestroy()
    }
}
