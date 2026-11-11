package com.kerybotu.derpibooru.mirror.ui

import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.os.Build
import android.annotation.SuppressLint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Color
import android.content.res.ColorStateList
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.load.engine.GlideException
import android.graphics.drawable.Drawable
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.R
import com.kerybotu.derpibooru.mirror.databinding.ItemVideoFeedBinding
import com.kerybotu.derpibooru.mirror.favorites.LocalFavoritesStore

data class VideoPost(
    val id: Int,
    var url: String,
    val thumbnailUrl: String? = null,
    val mimeType: String = "video/mp4",
    val width: Int = 0,
    val height: Int = 0,
    val uploader: String?,
    val tags: List<String>?,
    var upvotes: Int,
    var downvotes: Int,
    val commentCount: Int,
    val sources: List<String> = listOf(url),
    val originalUrl: String = url
)

@SuppressLint("UnsafeOptInUsageError")
class VideoFeedAdapter(
    private val actions: Actions,
    private val glassBackdrop: VideoGlassBackdrop
) : RecyclerView.Adapter<VideoFeedAdapter.Holder>() {
    interface Actions {
        fun onToggle(position: Int)
        fun onDoubleTap(position: Int)
        fun onLongPress(position: Int, active: Boolean)
        fun onUpvote(position: Int)
        fun onDownvote(position: Int)
        fun onFavorite(position: Int)
        fun onComments(position: Int)
        fun onDownload(position: Int)
        fun onMore(position: Int, anchor: View)
        fun onRetry(position: Int)
    }

    private val items = mutableListOf<VideoPost>()
    private var bottomInset = 0
    private var endInset = 0
    private var landscape = false
    private var safeLeftInset = 0
    private var safeTopInset = 0
    private var safeRightInset = 0
    private var safeBottomInset = 0
    var onHolderBound: ((Int) -> Unit)? = null
    /** Called after a ViewPager2 page has actually been attached to the inner RecyclerView. */
    var onHolderAttached: ((Holder, Int) -> Unit)? = null

    fun replace(posts: List<VideoPost>) { items.clear(); items.addAll(posts); notifyDataSetChanged() }
    fun append(posts: List<VideoPost>) { val start = items.size; items.addAll(posts); notifyItemRangeInserted(start, posts.size) }
    fun item(position: Int): VideoPost? = items.getOrNull(position)
    fun refreshPalette() {
        // A full change animation replaces holders and can clear the active
        // PlayerView after the controller has attached its player. A theme
        // payload reuses the holder and changes only its material/foreground.
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size, PALETTE_PAYLOAD)
    }
    fun setBottomInset(value: Int) {
        setNavigationInsets(value, endInset, landscape)
    }

    /** Insets occupied by the shared navigation island, never by the video canvas. */
    fun setNavigationInsets(bottom: Int, end: Int, isLandscape: Boolean) {
        val normalizedBottom = bottom.coerceAtLeast(0)
        val normalizedEnd = end.coerceAtLeast(0)
        if (bottomInset == normalizedBottom && endInset == normalizedEnd && landscape == isLandscape) return
        bottomInset = normalizedBottom
        endInset = normalizedEnd
        landscape = isLandscape
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size, PALETTE_PAYLOAD)
    }

    fun setSafeInsets(left: Int, top: Int, right: Int, bottom: Int) {
        val normalizedLeft = left.coerceAtLeast(0)
        val normalizedTop = top.coerceAtLeast(0)
        val normalizedRight = right.coerceAtLeast(0)
        val normalizedBottom = bottom.coerceAtLeast(0)
        if (safeLeftInset == normalizedLeft && safeTopInset == normalizedTop &&
            safeRightInset == normalizedRight && safeBottomInset == normalizedBottom
        ) return
        safeLeftInset = normalizedLeft
        safeTopInset = normalizedTop
        safeRightInset = normalizedRight
        safeBottomInset = normalizedBottom
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size, PALETTE_PAYLOAD)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val holder = Holder(ItemVideoFeedBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        holder.itemView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                holder.applyNavigationInsets(
                    bottomInset, endInset, landscape,
                    safeLeftInset, safeTopInset, safeRightInset, safeBottomInset
                )
            }
        }
        with(holder.binding) {
            glassBackdrop.track(videoActionRail, Ui2DesignSystem.Shape.island)
            glassBackdrop.track(videoInfoOverlay, Ui2DesignSystem.Shape.large)
            glassBackdrop.track(videoBufferGlass, Ui2DesignSystem.Shape.pill)
            glassBackdrop.track(videoSpeedGlass, Ui2DesignSystem.Shape.pill)
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(
            items[position], position, actions, bottomInset, endInset, landscape,
            safeLeftInset, safeTopInset, safeRightInset, safeBottomInset
        )
        holder.itemView.post {
            val boundPosition = holder.bindingAdapterPosition
            if (boundPosition != RecyclerView.NO_POSITION) onHolderBound?.invoke(boundPosition)
        }
    }

    override fun onViewAttachedToWindow(holder: Holder) {
        super.onViewAttachedToWindow(holder)
        val position = holder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) onHolderAttached?.invoke(holder, position)
    }

    override fun onViewDetachedFromWindow(holder: Holder) {
        onHolderAttached?.invoke(holder, RecyclerView.NO_POSITION)
        super.onViewDetachedFromWindow(holder)
    }
    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PALETTE_PAYLOAD }) {
            holder.applyPalette()
            holder.applyNavigationInsets(
                bottomInset, endInset, landscape,
                safeLeftInset, safeTopInset, safeRightInset, safeBottomInset
            )
        }
        else onBindViewHolder(holder, position)
    }
    override fun onViewRecycled(holder: Holder) {
        glassBackdrop.forgetVideo(holder.binding.videoPlayer.videoSurfaceView)
        holder.binding.videoPlayer.player = null
        // Theme rebind animations can recycle views after Activity destruction.
        // Reuse its existing lifecycle-bound manager instead of retrieving
        // a new one from the destroyed Activity's View context.
        holder.imageRequests.clear(holder.binding.videoAmbientBackground)
        holder.imageRequests.clear(holder.binding.videoPreview)
        holder.boundId = null
        super.onViewRecycled(holder)
    }

    class Holder(val binding: ItemVideoFeedBinding) : RecyclerView.ViewHolder(binding.root) {
        val imageRequests = Glide.with(binding.videoAmbientBackground)
        var boundId: Int? = null
        internal val previewState = VideoPreviewState()
        internal val seekState = VideoSeekState()
        fun bind(
            post: VideoPost,
            position: Int,
            actions: Actions,
            bottomInset: Int,
            endInset: Int,
            landscape: Boolean,
            safeLeftInset: Int,
            safeTopInset: Int,
            safeRightInset: Int,
            safeBottomInset: Int
        ) = with(binding) {
            boundId = post.id
            seekState.cancel()
            videoPlayer.player = null
            previewState.bind(post.id)
            imageRequests.clear(videoAmbientBackground)
            imageRequests.clear(videoPreview)
            videoPreview.visibility = View.VISIBLE
            videoPreviewLoading.visibility = View.VISIBLE
            videoPlaybackError.visibility = View.GONE
            applyNavigationInsets(
                bottomInset, endInset, landscape,
                safeLeftInset, safeTopInset, safeRightInset, safeBottomInset
            )
            applyPalette()
            imageRequests.asBitmap()
                .load(post.thumbnailUrl)
                .centerCrop()
                .listener(object : RequestListener<android.graphics.Bitmap> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<android.graphics.Bitmap>, first: Boolean): Boolean {
                        if (boundId == post.id) videoPreviewLoading.visibility = View.GONE
                        return false
                    }
                    override fun onResourceReady(resource: android.graphics.Bitmap, model: Any, target: Target<android.graphics.Bitmap>, dataSource: com.bumptech.glide.load.DataSource, first: Boolean): Boolean {
                        if (boundId == post.id) videoPreviewLoading.visibility = View.GONE
                        return false
                    }
                })
                .into(videoAmbientBackground)
            imageRequests.asBitmap().load(post.thumbnailUrl)
                .fitCenter()
                .listener(object : RequestListener<android.graphics.Bitmap> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<android.graphics.Bitmap>, first: Boolean): Boolean {
                        if (boundId == post.id) videoPreviewLoading.visibility = View.GONE
                        return false
                    }
                    override fun onResourceReady(resource: android.graphics.Bitmap, model: Any, target: Target<android.graphics.Bitmap>, dataSource: com.bumptech.glide.load.DataSource, first: Boolean): Boolean {
                        if (boundId == post.id) videoPreviewLoading.visibility = View.GONE
                        return false
                    }
                })
                .into(videoPreview)
            val uploader = post.uploader?.trim().orEmpty().takeUnless { it.equals("null", ignoreCase = true) }.orEmpty()
            videoUploader.text = uploader
            videoUploader.visibility = if (uploader.isBlank()) View.GONE else View.VISIBLE
            val tags = post.tags.orEmpty().asSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
                .take(3)
                .joinToString("  ·  ")
            videoTags.text = tags
            videoTags.visibility = if (tags.isBlank()) View.GONE else View.VISIBLE
            videoUpvoteCount.text = post.upvotes.toString()
            videoUpvote.setOnClickListener { actions.onUpvote(bindingAdapterPosition) }
            videoDownvote.setOnClickListener { actions.onDownvote(bindingAdapterPosition) }
            videoFavorite.setOnClickListener { actions.onFavorite(bindingAdapterPosition) }
            videoComments.setOnClickListener { actions.onComments(bindingAdapterPosition) }
            videoDownload.setOnClickListener { actions.onDownload(bindingAdapterPosition) }
            videoMore.setOnClickListener { actions.onMore(bindingAdapterPosition, it) }
            videoPlaybackRetry.setOnClickListener { actions.onRetry(bindingAdapterPosition) }
            val detector = GestureDetector(root.context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean { actions.onToggle(bindingAdapterPosition); return true }
                override fun onDoubleTap(e: MotionEvent): Boolean { actions.onDoubleTap(bindingAdapterPosition); return true }
                override fun onLongPress(e: MotionEvent) { actions.onLongPress(bindingAdapterPosition, true) }
            })
            videoPlayer.setOnTouchListener { _, event ->
                detector.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    actions.onLongPress(bindingAdapterPosition, false)
                }
                true
            }
        }

        fun applyPalette() = with(binding) {
            val colors = PaletteManager.colors(root.context)
            val uiColors = Ui2DesignSystem.colors(root.context)
            val lightSurface = Color.luminance(colors.surface) > 0.5f
            videoAmbientBackground.alpha = if (lightSurface) 0.5f else 0.42f
            videoPreview.setBackgroundColor(colors.surface)
            videoPreviewLoading.indeterminateTintList = ColorStateList.valueOf(colors.primary)
            videoPlaybackErrorText.setTextColor(colors.glassText)
            videoPlaybackRetry.setTextColor(colors.primary)
            videoAmbientBackground.scaleX = 1.18f
            videoAmbientBackground.scaleY = 1.18f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                videoAmbientBackground.setRenderEffect(RenderEffect.createBlurEffect(24f, 24f, Shader.TileMode.CLAMP))
            }
            GlassWidgetStyle.apply(videoActionRail, Ui2DesignSystem.Shape.island, colors)
            GlassWidgetStyle.apply(videoInfoOverlay, Ui2DesignSystem.Shape.large, colors, GlassWidgetStyle.MaterialRole.READING)
            GlassWidgetStyle.apply(videoBufferGlass, Ui2DesignSystem.Shape.pill, colors)
            GlassWidgetStyle.apply(videoSpeedGlass, Ui2DesignSystem.Shape.pill, colors)
            videoAmbientScrim.setBackgroundColor(withAlpha(colors.scrim, 0.58f))
            listOf(videoActionDividerPrimary, videoActionDividerSecondary, videoActionDividerTertiary).forEach {
                it.setBackgroundColor(withAlpha(colors.divider, 0.62f))
            }
            videoUploader.setTextColor(colors.glassText)
            videoTags.setTextColor(colors.glassSecondaryText)
            videoUpvoteCount.setTextColor(colors.glassText)
            listOf(videoUpvote, videoDownvote, videoFavorite, videoComments, videoDownload, videoMore).forEach {
                it.imageTintList = ColorStateList.valueOf(colors.glassText)
                Ui2DesignSystem.applyPressFeedback(it)
            }
            renderFavoriteState()
            videoPlayProgress.progressTintList = ColorStateList.valueOf(colors.primary)
            videoPlayProgress.thumbTintList = ColorStateList.valueOf(colors.onSurface)
            videoBufferProgress.progressTintList = ColorStateList.valueOf(uiColors.glassHighlight)
            videoBufferProgress.backgroundTintList = ColorStateList.valueOf(uiColors.glassBorder)
        }

        fun renderFavoriteState() {
            val isFavorite = boundId?.let { LocalFavoritesStore(binding.root.context).isFavoriteInDefaultFolder(it) } == true
            val colors = PaletteManager.colors(binding.root.context)
            binding.videoFavorite.setImageResource(if (isFavorite) R.drawable.baseline_favorite else R.drawable.ic_favorite)
            binding.videoFavorite.imageTintList = ColorStateList.valueOf(if (isFavorite) colors.primary else colors.glassText)
        }

        fun applyNavigationInsets(
            bottomInset: Int,
            endInset: Int,
            landscape: Boolean,
            safeLeftInset: Int,
            safeTopInset: Int,
            safeRightInset: Int,
            safeBottomInset: Int
        ) {
            binding.root.post {
                if (binding.root.width <= 0 || binding.root.height <= 0) return@post
                val density = binding.root.resources.displayMetrics.density
                val widthDp = (binding.root.width / density).toInt().coerceAtLeast(1)
                val heightDp = (binding.root.height / density).toInt().coerceAtLeast(1)
                val spec = AdaptiveLayoutPolicy.specFor(widthDp, heightDp)
                val horizontalMargin = dp(binding.root, spec.componentSpacingDp)
                val safeStart = if (androidx.core.view.ViewCompat.getLayoutDirection(binding.root) == androidx.core.view.ViewCompat.LAYOUT_DIRECTION_RTL) {
                    safeRightInset
                } else {
                    safeLeftInset
                }
                val safeEnd = if (androidx.core.view.ViewCompat.getLayoutDirection(binding.root) == androidx.core.view.ViewCompat.LAYOUT_DIRECTION_RTL) {
                    safeLeftInset
                } else {
                    safeRightInset
                }
                val bottomMargin = maxOf(bottomInset, safeBottomInset) + horizontalMargin
                val measuredRailHeight = binding.videoActionRail.height.takeIf { it > 0 } ?: dp(binding.root, 340)
                val panelHeight = binding.videoInfoOverlay.height.takeIf { it > 0 } ?: dp(binding.root, 120)
                val safeTop = safeTopInset + horizontalMargin
                val safeBottom = bottomMargin
                val railTop = safeTop + ((binding.root.height - safeTop - safeBottom - measuredRailHeight).coerceAtLeast(0) / 2)
                val panelTop = binding.root.height - bottomMargin - panelHeight
                val verticalOverlap = railTop + measuredRailHeight + horizontalMargin > panelTop
                val sideBySide = landscape || endInset > 0 ||
                    spec.navigationMode == AdaptiveLayoutPolicy.NavigationMode.RAIL ||
                    spec.heightClass == AdaptiveLayoutPolicy.WindowHeightClass.COMPACT || verticalOverlap
                val railWidth = binding.videoActionRail.width.takeIf { it > 0 } ?: dp(binding.root, 52)
                val infoStartMargin = safeStart + horizontalMargin
                val infoEndMargin = safeEnd + endInset + horizontalMargin +
                    if (sideBySide) railWidth + horizontalMargin else 0
                val availableWidth = (binding.root.width - infoStartMargin - infoEndMargin).coerceAtLeast(dp(binding.root, 1))
                val preferredWidth = dp(binding.root, minOf(560, spec.contentMaxWidthDp))
                val infoParams = binding.videoInfoOverlay.layoutParams as android.widget.FrameLayout.LayoutParams
                infoParams.width = availableWidth.coerceAtMost(preferredWidth)
                infoParams.gravity = if (sideBySide) {
                    android.view.Gravity.BOTTOM or android.view.Gravity.START
                } else {
                    android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                }
                infoParams.marginStart = infoStartMargin
                infoParams.marginEnd = infoEndMargin
                infoParams.leftMargin = 0
                infoParams.rightMargin = 0
                infoParams.bottomMargin = bottomMargin
                binding.videoInfoOverlay.layoutParams = infoParams

                val railParams = binding.videoActionRail.layoutParams as android.widget.FrameLayout.LayoutParams
                val railEndMargin = safeEnd + endInset + horizontalMargin
                val railTopMargin = safeTopInset + horizontalMargin
                val railBottomMargin = maxOf(bottomInset, safeBottomInset) + horizontalMargin
                val railNeedsBoundedHeight = landscape ||
                    spec.navigationMode == AdaptiveLayoutPolicy.NavigationMode.RAIL ||
                    spec.heightClass == AdaptiveLayoutPolicy.WindowHeightClass.COMPACT
                val usableRailHeight = (binding.root.height - railTopMargin - railBottomMargin)
                    .coerceAtLeast(dp(binding.root, 64))
                val railHeight = if (railNeedsBoundedHeight) usableRailHeight else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                if (railParams.marginEnd != railEndMargin || railParams.topMargin != railTopMargin ||
                    railParams.bottomMargin != railBottomMargin || railParams.height != railHeight
                ) {
                    railParams.marginEnd = railEndMargin
                    railParams.topMargin = railTopMargin
                    railParams.bottomMargin = railBottomMargin
                    railParams.height = railHeight
                    binding.videoActionRail.layoutParams = railParams
                }
            }
        }

        private fun dp(view: View, value: Int): Int =
            (value * view.resources.displayMetrics.density).toInt()

        private fun withAlpha(color: Int, fraction: Float): Int = Color.argb(
            (Color.alpha(color) * fraction.coerceIn(0f, 1f)).toInt(),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private companion object {
        const val PALETTE_PAYLOAD = "glass_palette"
    }
}
