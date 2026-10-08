package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.databinding.ActivityVideoFeedBinding
import com.kerybotu.derpibooru.mirror.network.ResourceCoordinator

/** Video feed hosted by MainActivity. Its ViewPager and player pool survive tab changes. */
class EmbeddedVideoView(context: Context) : androidx.cardview.widget.CardView(context), VideoFeedAdapter.Actions {
    private val binding = ActivityVideoFeedBinding.inflate(android.view.LayoutInflater.from(context), this, true)
    private val progressHandler = Handler(Looper.getMainLooper())
    private lateinit var adapter: VideoFeedAdapter
    private lateinit var controller: VideoFeedController
    private lateinit var pagerRecycler: RecyclerView
    private var loaded = false
    private var navigationBottomInset = 0
    private var navigationEndInset = 0
    private var landscapeNavigation = false
    private var statusBarInset = 0
    val glassBackdropSource: View get() = binding.videoGlassBackdrop
    private val progressTick = object : Runnable { override fun run() { updateProgress(); progressHandler.postDelayed(this, 250) } }

    init {
        setCardBackgroundColor(PaletteManager.colors(context).surface)
        radius = 0f; preventCornerOverlap = false
        val colors = PaletteManager.colors(context)
        binding.videoGlassBackdrop.track(binding.videoTopIsland, Ui2DesignSystem.Shape.topIsland)
        binding.videoMenu.imageTintList = android.content.res.ColorStateList.valueOf(colors.glassText)
        binding.videoAudio.imageTintList = android.content.res.ColorStateList.valueOf(colors.glassText)
        binding.videoSort.imageTintList = android.content.res.ColorStateList.valueOf(colors.glassText)
        Ui2DesignSystem.applyPressFeedback(binding.videoMenu)
        Ui2DesignSystem.applyPressFeedback(binding.videoAudio)
        Ui2DesignSystem.applyPressFeedback(binding.videoSort)
        // The video canvas is edge-to-edge. Only the floating control island
        // consumes the status-bar safe area, keeping it independent from the
        // pager's measured bounds.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            statusBarInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            updateTopIslandPosition()
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
        ResourceCoordinator.enterVideoTab()
        CdnImageGate.pausePrefetch(context)
        adapter = VideoFeedAdapter(this, binding.videoGlassBackdrop)
        binding.videoPager.orientation = ViewPager2.ORIENTATION_VERTICAL
        binding.videoPager.offscreenPageLimit = 1
        binding.videoPager.adapter = adapter
        pagerRecycler = binding.videoPager.getChildAt(0) as RecyclerView
        binding.videoGlassBackdrop.results = pagerRecycler

        controller = VideoFeedController(
            context, adapter, binding.videoPager, pagerRecycler,
            onEmptyView = { visible, msg ->
                binding.videoEmptyGlass.visibility = if (visible) View.VISIBLE else View.GONE
                if (msg.isNotEmpty()) binding.videoEmpty.text = msg
            },
            onLoadingView = { loading ->
                binding.videoEmptyGlass.visibility = if (loading && adapter.itemCount == 0) View.VISIBLE else View.GONE
            }
        )

        binding.videoMenu.setOnClickListener { (context as? com.kerybotu.derpibooru.mirror.MainActivity)?.showUnifiedGlassMenu() }
        binding.videoAudio.setOnClickListener { controller.toggleMute() }
        binding.videoEmptyGlass.setOnClickListener { controller.reloadFeed() }
        binding.videoSort.setOnClickListener { controller.showSortMenu(it) }
        binding.videoPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                controller.currentPosition = position
                controller.activate(position, automatic = true)
                if (position >= adapter.itemCount - 4) controller.loadNextPage()
            }
        })
    }

    private fun updateProgress() {
        val player = controller.playerPool.get(controller.currentPosition) ?: return
        val holder = pagerRecycler.findViewHolderForAdapterPosition(controller.currentPosition) as? VideoFeedAdapter.Holder ?: return
        val duration = player.duration.takeIf { it > 0 } ?: return
        val played = ((player.currentPosition * 1000L) / duration).toInt().coerceIn(0, 1000)
        if (!holder.binding.videoPlayProgress.isPressed) holder.binding.videoPlayProgress.progress = played
        holder.binding.videoBufferProgress.progress = (player.bufferedPercentage.coerceIn(0, 100) * 10)
        val buffering = player.playbackState == androidx.media3.common.Player.STATE_BUFFERING
        val prebuffering = player.playbackState == androidx.media3.common.Player.STATE_IDLE && player.playWhenReady
        val bitrate = controller.playerPool.bitrateEstimate()
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

    /** Keeps the canvas full screen; only overlays avoid the shared navigation island. */
    fun setBottomInset(px: Int) = setNavigationInsets(px, navigationEndInset, landscapeNavigation)

    /** Rebinds persistent pager items after a global palette or accent change. */
    fun refreshPalette() {
        val colors = PaletteManager.colors(context)
        binding.videoGlassBackdrop.setBackgroundColor(colors.surface)
        GlassWidgetStyle.apply(binding.videoTopIsland, Ui2DesignSystem.Shape.topIsland, colors)
        binding.videoEmpty.setTextColor(colors.glassText)
        binding.videoMenu.imageTintList = android.content.res.ColorStateList.valueOf(colors.glassText)
        binding.videoAudio.imageTintList = android.content.res.ColorStateList.valueOf(colors.glassText)
        binding.videoSort.imageTintList = android.content.res.ColorStateList.valueOf(colors.glassText)
        adapter.refreshPalette()
    }

    fun setNavigationInsets(bottom: Int, end: Int, landscape: Boolean) {
        navigationBottomInset = bottom.coerceAtLeast(0)
        navigationEndInset = end.coerceAtLeast(0)
        landscapeNavigation = landscape
        adapter.setNavigationInsets(navigationBottomInset, navigationEndInset, landscapeNavigation)
        updateTopIslandPosition()
    }
    fun setActive(active: Boolean) {
        binding.videoTopIsland.setRenderingActive(active, if (active) binding.videoPager else null)
        if (active) {
            if (!loaded && !controller.loading) { controller.loadNextPage(); loaded = true }
            progressHandler.post(progressTick)
            if (loaded) controller.activate(controller.currentPosition, automatic = true)
        } else {
            progressHandler.removeCallbacks(progressTick); controller.playerPool.pauseAll()
        }
    }
    fun setGlassRenderingActive(active: Boolean) = binding.videoGlassBackdrop.setActive(active)
    fun dispose() { setGlassRenderingActive(false); progressHandler.removeCallbacks(progressTick); controller.dispose() }

    override fun onToggle(position: Int) = controller.onToggle(position)
    override fun onDoubleTap(position: Int) = controller.onDoubleTap(position)
    override fun onLongPress(position: Int, active: Boolean) = controller.onLongPress(position, active)
    override fun onUpvote(position: Int) = controller.onUpvote(position)
    override fun onDownvote(position: Int) = controller.onDownvote(position)
    override fun onFavorite(position: Int) = controller.onFavorite(position)
    override fun onComments(position: Int) = controller.onComments(position)
    override fun onDownload(position: Int) = controller.onDownload(position)
    override fun onMore(position: Int, anchor: View) = controller.onMore(position, anchor)

    private fun updateTopIslandPosition() {
        val params = binding.videoTopIsland.layoutParams as FrameLayout.LayoutParams
        params.gravity = if (landscapeNavigation) {
            android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        } else {
            android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
        }
        params.topMargin = if (landscapeNavigation) 0 else statusBarInset + dp(12)
        params.bottomMargin = 0
        params.marginStart = if (landscapeNavigation) dp(16) else 0
        params.marginEnd = 0
        binding.videoTopIsland.layoutParams = params
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
