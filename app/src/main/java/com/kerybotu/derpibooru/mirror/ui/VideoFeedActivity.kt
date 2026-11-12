package com.kerybotu.derpibooru.mirror.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.databinding.ActivityVideoFeedBinding
import com.kerybotu.derpibooru.mirror.network.ResourceCoordinator

class VideoFeedActivity : AppCompatActivity(), VideoFeedAdapter.Actions {
    private lateinit var binding: ActivityVideoFeedBinding
    private lateinit var adapter: VideoFeedAdapter
    private lateinit var controller: VideoFeedController
    private var safeLeftInset = 0
    private var safeTopInset = 0
    private var safeRightInset = 0
    private var safeBottomInset = 0
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
        WindowCompat.getInsetsController(window, window.decorView).hide(
            androidx.core.view.WindowInsetsCompat.Type.statusBars() or androidx.core.view.WindowInsetsCompat.Type.navigationBars()
        )
        binding = ActivityVideoFeedBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AdaptiveNavigationShell.install(this, binding.root, AdaptiveNavigationShell.VIDEO)
        binding.videoGlassBackdrop.track(binding.videoTopIsland, Ui2DesignSystem.Shape.topIsland)
        ResourceCoordinator.enterVideoTab()
        CdnImageGate.pausePrefetch(this)
        PaletteManager.apply(this)
        applyVideoPalette()
        Ui2DesignSystem.applyPressFeedback(binding.videoAudio)
        Ui2DesignSystem.applyPressFeedback(binding.videoSort)
        adapter = VideoFeedAdapter(this, binding.videoGlassBackdrop)
        adapter.setSafeInsets(safeLeftInset, safeTopInset, safeRightInset, safeBottomInset)
        binding.root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                updateTopIslandPosition()
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val gestures = insets.getInsets(WindowInsetsCompat.Type.systemGestures())
            safeLeftInset = maxOf(bars.left, cutout.left, gestures.left)
            safeTopInset = maxOf(bars.top, cutout.top)
            safeRightInset = maxOf(bars.right, cutout.right, gestures.right)
            safeBottomInset = maxOf(bars.bottom, cutout.bottom, gestures.bottom)
            adapter.setSafeInsets(safeLeftInset, safeTopInset, safeRightInset, safeBottomInset)
            updateTopIslandPosition()
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
        binding.videoPager.apply {
            orientation = ViewPager2.ORIENTATION_VERTICAL
            offscreenPageLimit = 1
            this.adapter = this@VideoFeedActivity.adapter
        }
        val pagerRecycler = binding.videoPager.getChildAt(0) as RecyclerView
        binding.videoGlassBackdrop.results = pagerRecycler
        controller = VideoFeedController(
            this, adapter, binding.videoPager, pagerRecycler,
            onEmptyView = { visible, msg ->
                binding.videoEmptyGlass.visibility = if (visible) View.VISIBLE else View.GONE
                if (msg.isNotEmpty()) binding.videoEmpty.text = msg
            },
            onLoadingView = { loading ->
                binding.videoEmptyGlass.visibility = if (loading && adapter.itemCount == 0) View.VISIBLE else View.GONE
            }
        )
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
        controller.loadNextPage()
    }

    private fun updateVideoProgress() {
        val player = controller.playerPool.get(controller.currentPosition) ?: return
        val pagerRecycler = binding.videoPager.getChildAt(0) as RecyclerView
        val holder = pagerRecycler.findViewHolderForAdapterPosition(controller.currentPosition) as? VideoFeedAdapter.Holder ?: return
        val duration = player.duration.takeIf { it > 0 } ?: return
        val played = ((player.currentPosition * 1000L) / duration).toInt().coerceIn(0, 1000)
        if (!holder.binding.videoPlayProgress.isPressed) holder.binding.videoPlayProgress.progress = played
        holder.binding.videoBufferProgress.progress = (player.bufferedPercentage.coerceIn(0, 100) * 10)
        val buffering = player.playbackState == androidx.media3.common.Player.STATE_BUFFERING
        val prebuffering = player.playbackState == androidx.media3.common.Player.STATE_IDLE && player.playWhenReady
        val bitrate = controller.playerPool.bitrateEstimate()
        holder.binding.videoBufferGlass.visibility = if (buffering || prebuffering) View.VISIBLE else View.GONE
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

    override fun onToggle(position: Int) = controller.onToggle(position)
    override fun onDoubleTap(position: Int) = controller.onDoubleTap(position)
    override fun onLongPress(position: Int, active: Boolean) = controller.onLongPress(position, active)
    override fun onUpvote(position: Int) = controller.onUpvote(position)
    override fun onDownvote(position: Int) = controller.onDownvote(position)
    override fun onFavorite(position: Int) = controller.onFavorite(position)
    override fun onComments(position: Int) = controller.onComments(position)
    override fun onDownload(position: Int) = controller.onDownload(position)
    override fun onMore(position: Int, anchor: View) = controller.onMore(position, anchor)
    override fun onRetry(position: Int) = controller.onRetry(position)

    override fun onPause() { binding.videoGlassBackdrop.setActive(false); progressHandler.removeCallbacks(progressTick); controller.setActive(false); super.onPause() }
    override fun onResume() {
        super.onResume()
        controller.setActive(true)
        PaletteManager.apply(this)
        applyVideoPalette()
        binding.videoGlassBackdrop.setActive(true)
        progressHandler.post(progressTick)
        controller.activate(controller.currentPosition, automatic = true)
    }
    override fun onDestroy() {
        progressHandler.removeCallbacks(progressTick)
        controller.dispose()
        ResourceCoordinator.exitVideoTab()
        super.onDestroy()
    }

    private fun applyVideoPalette() {
        val colors = PaletteManager.colors(this)
        binding.videoGlassBackdrop.setBackgroundColor(colors.surface)
        GlassWidgetStyle.apply(binding.videoTopIsland, Ui2DesignSystem.Shape.topIsland, colors)
        binding.videoEmpty.setTextColor(colors.glassText)
        binding.videoMenu.imageTintList = android.content.res.ColorStateList.valueOf(colors.glassText)
        binding.videoAudio.imageTintList = android.content.res.ColorStateList.valueOf(colors.glassText)
        binding.videoSort.imageTintList = android.content.res.ColorStateList.valueOf(colors.glassText)
        binding.videoMenu.visibility = View.GONE
        if (::adapter.isInitialized) adapter.refreshPalette()
    }

    private fun updateTopIslandPosition() {
        if (binding.root.width <= 0 || binding.root.height <= 0) return
        val density = resources.displayMetrics.density
        val widthDp = (binding.root.width / density).toInt().coerceAtLeast(1)
        val heightDp = (binding.root.height / density).toInt().coerceAtLeast(1)
        val spec = AdaptiveLayoutPolicy.specFor(widthDp, heightDp)
        // This view is measured after AdaptiveNavigationShell removes the rail
        // from its content width. Use the window configuration as well so a
        // phone landscape window does not incorrectly center controls over the
        // media subject.
        val sidePlacement = spec.navigationMode == AdaptiveLayoutPolicy.NavigationMode.RAIL ||
            AdaptiveLayoutPolicy.isHorizontalLayout(resources.configuration)
        val margin = dp(spec.componentSpacingDp)
        val isRtl = ViewCompat.getLayoutDirection(binding.root) == ViewCompat.LAYOUT_DIRECTION_RTL
        val safeStart = if (isRtl) safeRightInset else safeLeftInset
        val safeEnd = if (isRtl) safeLeftInset else safeRightInset
        val params = binding.videoTopIsland.layoutParams as? FrameLayout.LayoutParams ?: return
        val availableWidth = (binding.root.width - safeLeftInset - safeRightInset - margin * 2).coerceAtLeast(dp(1))
        val islandWidth = dp(180).coerceAtMost(availableWidth)
        val gravity = if (sidePlacement) {
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        } else {
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        val startMargin = if (sidePlacement) 0 else safeStart + margin
        val endMargin = if (sidePlacement) 0 else safeEnd + margin
        val topMargin = safeTopInset
        val bottomMargin = 0
        if (params.width != islandWidth || params.gravity != gravity ||
            params.marginStart != startMargin || params.marginEnd != endMargin ||
            params.topMargin != topMargin || params.bottomMargin != bottomMargin
        ) {
            params.width = islandWidth
            params.gravity = gravity
            params.marginStart = startMargin
            params.marginEnd = endMargin
            params.topMargin = topMargin
            params.bottomMargin = bottomMargin
            binding.videoTopIsland.layoutParams = params
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
