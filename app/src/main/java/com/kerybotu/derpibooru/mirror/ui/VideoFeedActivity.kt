package com.kerybotu.derpibooru.mirror.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.databinding.ActivityVideoFeedBinding
import com.kerybotu.derpibooru.mirror.network.ResourceCoordinator

class VideoFeedActivity : AppCompatActivity(), VideoFeedAdapter.Actions {
    private lateinit var binding: ActivityVideoFeedBinding
    private lateinit var adapter: VideoFeedAdapter
    private lateinit var controller: VideoFeedController
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
        binding.videoGlassBackdrop.track(binding.videoTopIsland, Ui2DesignSystem.Shape.topIsland)
        ResourceCoordinator.enterVideoTab()
        CdnImageGate.pausePrefetch(this)
        PaletteManager.apply(this)
        applyVideoPalette()
        Ui2DesignSystem.applyPressFeedback(binding.videoAudio)
        Ui2DesignSystem.applyPressFeedback(binding.videoSort)
        adapter = VideoFeedAdapter(this, binding.videoGlassBackdrop)
        binding.videoPager.apply {
            orientation = ViewPager2.ORIENTATION_VERTICAL
            offscreenPageLimit = 1
            this.adapter = adapter
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

    override fun onPause() { binding.videoGlassBackdrop.setActive(false); progressHandler.removeCallbacks(progressTick); controller.playerPool.pauseAll(); super.onPause() }
    override fun onResume() {
        super.onResume()
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
}
