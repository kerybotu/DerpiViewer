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
import com.kerybotu.derpibooru.mirror.databinding.ItemVideoFeedBinding

data class VideoPost(
    val id: Int,
    val url: String,
    val thumbnailUrl: String? = null,
    val mimeType: String = "video/mp4",
    val width: Int = 0,
    val height: Int = 0,
    val uploader: String,
    val tags: List<String>,
    var upvotes: Int,
    var downvotes: Int,
    val commentCount: Int
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
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
    }
    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemVideoFeedBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    ).also { holder ->
        with(holder.binding) {
            glassBackdrop.track(videoActionRail, Ui2DesignSystem.Shape.island)
            glassBackdrop.track(videoInfoOverlay, Ui2DesignSystem.Shape.large)
            glassBackdrop.track(videoBufferGlass, Ui2DesignSystem.Shape.pill)
            glassBackdrop.track(videoSpeedGlass, Ui2DesignSystem.Shape.pill)
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(
        items[position], position, actions, bottomInset, endInset, landscape
    )
    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PALETTE_PAYLOAD }) holder.applyPalette()
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
        fun bind(
            post: VideoPost,
            position: Int,
            actions: Actions,
            bottomInset: Int,
            endInset: Int,
            landscape: Boolean
        ) = with(binding) {
            boundId = post.id
            previewState.bind(post.id)
            imageRequests.clear(videoAmbientBackground)
            imageRequests.clear(videoPreview)
            videoPreview.visibility = View.VISIBLE
            videoPreviewLoading.visibility = View.VISIBLE
            videoPlaybackError.visibility = View.GONE
            applyNavigationInsets(bottomInset, endInset, landscape)
            applyPalette()
            imageRequests
                .load(post.thumbnailUrl ?: post.url)
                .centerCrop()
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, first: Boolean): Boolean {
                        if (boundId == post.id) videoPreviewLoading.visibility = View.GONE
                        return false
                    }
                    override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>, dataSource: com.bumptech.glide.load.DataSource, first: Boolean): Boolean {
                        if (boundId == post.id) videoPreviewLoading.visibility = View.GONE
                        return false
                    }
                })
                .into(videoAmbientBackground)
            imageRequests.load(post.thumbnailUrl)
                .fitCenter()
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, first: Boolean): Boolean {
                        if (boundId == post.id) videoPreviewLoading.visibility = View.GONE
                        return false
                    }
                    override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>, dataSource: com.bumptech.glide.load.DataSource, first: Boolean): Boolean {
                        if (boundId == post.id) videoPreviewLoading.visibility = View.GONE
                        return false
                    }
                })
                .into(videoPreview)
            videoUploader.text = post.uploader
            videoTags.text = post.tags.take(3).joinToString("  ·  ")
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
            videoPlayProgress.progressTintList = ColorStateList.valueOf(colors.primary)
            videoPlayProgress.thumbTintList = ColorStateList.valueOf(colors.onSurface)
            videoBufferProgress.progressTintList = ColorStateList.valueOf(uiColors.glassHighlight)
            videoBufferProgress.backgroundTintList = ColorStateList.valueOf(uiColors.glassBorder)
        }

        fun applyNavigationInsets(bottomInset: Int, endInset: Int, landscape: Boolean) {
            binding.root.post {
                val horizontalMargin = dp(binding.root, 16)
                val infoParams = binding.videoInfoOverlay.layoutParams as android.widget.FrameLayout.LayoutParams
                val availableWidth = (binding.root.width - horizontalMargin * 2 - if (landscape) endInset else 0)
                    .coerceAtLeast(0)
                val preferredWidth = dp(binding.root, if (landscape) 520 else 560)
                infoParams.width = availableWidth.coerceAtMost(preferredWidth)
                infoParams.gravity = if (landscape) {
                    android.view.Gravity.BOTTOM or android.view.Gravity.START
                } else {
                    android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                }
                infoParams.leftMargin = horizontalMargin
                infoParams.rightMargin = if (landscape) endInset + horizontalMargin else horizontalMargin
                infoParams.bottomMargin = bottomInset + dp(binding.root, 12)
                binding.videoInfoOverlay.layoutParams = infoParams

                val railParams = binding.videoActionRail.layoutParams as android.widget.FrameLayout.LayoutParams
                railParams.rightMargin = endInset + horizontalMargin
                binding.videoActionRail.layoutParams = railParams
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
