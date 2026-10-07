package com.kerybotu.derpibooru.mirror.ui

import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.os.Build
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Color
import android.content.res.ColorStateList
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
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

class VideoFeedAdapter(private val actions: Actions) : RecyclerView.Adapter<VideoFeedAdapter.Holder>() {
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
    }

    private val items = mutableListOf<VideoPost>()
    private var bottomInset = 0
    private var endInset = 0
    private var landscape = false

    fun replace(posts: List<VideoPost>) { items.clear(); items.addAll(posts); notifyDataSetChanged() }
    fun append(posts: List<VideoPost>) { val start = items.size; items.addAll(posts); notifyItemRangeInserted(start, posts.size) }
    fun item(position: Int): VideoPost? = items.getOrNull(position)
    fun refreshPalette() {
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
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
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(
        items[position], position, actions, bottomInset, endInset, landscape
    )
    override fun onViewRecycled(holder: Holder) {
        holder.binding.videoPlayer.player = null
        Glide.with(holder.binding.videoAmbientBackground).clear(holder.binding.videoAmbientBackground)
        super.onViewRecycled(holder)
    }

    class Holder(val binding: ItemVideoFeedBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            post: VideoPost,
            position: Int,
            actions: Actions,
            bottomInset: Int,
            endInset: Int,
            landscape: Boolean
        ) = with(binding) {
            applyNavigationInsets(bottomInset, endInset, landscape)
            val colors = PaletteManager.colors(root.context)
            val uiColors = Ui2DesignSystem.colors(root.context)
            Glide.with(videoAmbientBackground)
                .load(post.thumbnailUrl ?: post.url)
                .centerCrop()
                .into(videoAmbientBackground)
            val lightSurface = Color.luminance(colors.surface) > 0.5f
            videoAmbientBackground.alpha = if (lightSurface) 0.5f else 0.42f
            videoAmbientBackground.scaleX = 1.18f
            videoAmbientBackground.scaleY = 1.18f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                videoAmbientBackground.setRenderEffect(RenderEffect.createBlurEffect(24f, 24f, Shader.TileMode.CLAMP))
            }
            videoActionRail.setPalette(colors)
            videoActionRail.setRenderingActive(true, videoPlayer)
            videoInfoOverlay.setPalette(colors)
            videoInfoOverlay.setRenderingActive(true, videoPlayer)
            Ui2DesignSystem.styleIsland(videoBufferLabel, colors, Ui2DesignSystem.Shape.pill)
            Ui2DesignSystem.styleIsland(videoSpeed, colors, Ui2DesignSystem.Shape.pill)
            videoAmbientScrim.setBackgroundColor(withAlpha(colors.scrim, 0.58f))
            listOf(videoActionDividerPrimary, videoActionDividerSecondary, videoActionDividerTertiary).forEach {
                it.setBackgroundColor(withAlpha(colors.divider, 0.62f))
            }
            videoUploader.setTextColor(colors.onSurface)
            videoTags.setTextColor(colors.muted)
            videoUpvoteCount.setTextColor(colors.onSurface)
            listOf(videoUpvote, videoDownvote, videoFavorite, videoComments, videoDownload, videoMore).forEach {
                it.imageTintList = ColorStateList.valueOf(colors.onSurface)
                Ui2DesignSystem.applyPressFeedback(it)
            }
            videoPlayProgress.progressTintList = ColorStateList.valueOf(colors.primary)
            videoPlayProgress.thumbTintList = ColorStateList.valueOf(colors.onSurface)
            videoBufferProgress.progressTintList = ColorStateList.valueOf(uiColors.glassHighlight)
            videoBufferProgress.backgroundTintList = ColorStateList.valueOf(uiColors.glassBorder)
            videoUploader.text = post.uploader
            videoTags.text = post.tags.take(3).joinToString("  ·  ")
            videoUpvoteCount.text = post.upvotes.toString()
            videoUpvote.setOnClickListener { actions.onUpvote(bindingAdapterPosition) }
            videoDownvote.setOnClickListener { actions.onDownvote(bindingAdapterPosition) }
            videoFavorite.setOnClickListener { actions.onFavorite(bindingAdapterPosition) }
            videoComments.setOnClickListener { actions.onComments(bindingAdapterPosition) }
            videoDownload.setOnClickListener { actions.onDownload(bindingAdapterPosition) }
            videoMore.setOnClickListener { actions.onMore(bindingAdapterPosition, it) }
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
}
