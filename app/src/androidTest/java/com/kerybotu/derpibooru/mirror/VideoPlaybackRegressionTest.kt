package com.kerybotu.derpibooru.mirror

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.TextureView
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import com.kerybotu.derpibooru.mirror.ui.VideoFeedActivity
import com.kerybotu.derpibooru.mirror.ui.VideoFeedController
import com.kerybotu.derpibooru.mirror.ui.VideoFeedAdapter
import com.kerybotu.derpibooru.mirror.databinding.ActivityVideoFeedBinding
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real CDN/player regression checks; no READY-only or preview-only success. */
@RunWith(AndroidJUnit4::class)
class VideoPlaybackRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var controller: VideoFeedController
    private lateinit var binding: ActivityVideoFeedBinding
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun holder(): VideoFeedAdapter.Holder? =
        (binding.videoPager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(controller.currentPosition) as? VideoFeedAdapter.Holder

    private fun waitFor(label: String, timeoutMs: Long = 90_000, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < until) {
            var done = false
            main { done = predicate() }
            if (done) return
            Thread.sleep(250)
        }
        var details = ""
        main {
            val player = controller.playerPool.get(controller.currentPosition)
            val holder = holder()
            details = "position=${controller.currentPosition} bound=${holder?.boundId} preview=${holder?.binding?.videoPreview?.visibility} phase=${holder?.previewState?.phase} player=${player != null} state=${player?.playbackState} isPlaying=${player?.isPlaying} playWhenReady=${player?.playWhenReady} url=${player?.currentMediaItem?.localConfiguration?.uri} surface=${holder?.binding?.videoPlayer?.videoSurfaceView?.isShown}"
        }
        fail("Timed out: $label ($details)")
    }

    private fun frame(): Int {
        var hash = 0
        main {
            val bitmap = (holder()?.binding?.videoPlayer?.videoSurfaceView as? TextureView)?.getBitmap(64, 64)
            if (bitmap != null) {
                val pixels = IntArray(4096)
                bitmap.getPixels(pixels, 0, 64, 0, 0, 64, 64)
                hash = pixels.contentHashCode()
                bitmap.recycle()
            }
        }
        return hash
    }

    private fun assertMotion(label: String) {
        waitFor("$label first frame") {
            holder()?.binding?.videoPreview?.visibility == android.view.View.GONE &&
                controller.playerPool.get(controller.currentPosition)?.isPlaying == true
        }
        val initial = frame()
        assertNotEquals("No rendered texture: $label", 0, initial)
        var moved = false
        repeat(12) { if (!moved) { Thread.sleep(400); moved = frame() != initial } }
        assertTrue("Texture did not change: $label", moved)
        Log.i("VideoAcceptance", "PASS motion $label")
    }

    private fun scrub(fraction: Float) = main {
        val seek = holder()!!.binding.videoPlayProgress
        val start = SystemClock.uptimeMillis()
        val y = seek.height / 2f
        val x = (seek.width * fraction).coerceIn(1f, seek.width - 1f)
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP).forEachIndexed { i, action ->
            val event = MotionEvent.obtain(start, start + 20L * i, action, x, y, 0)
            seek.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    @Test fun mp4PlaybackRevisitSeekAndNetworkRecovery() {
        val context = instrumentation.targetContext
        val oldOptimization = AppSettings.isIpOptimizationEnabled(context)
        val oldCdnDirect = AppSettings.isCdnDirect(context)
        val oldWifiOnly = AppSettings.isVideoWifiOnly(context)
        var page: VideoFeedActivity? = null
        try {
            main { context.startActivity(Intent(context, VideoFeedActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            waitFor("video Activity resumed", 45_000) {
                page = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<VideoFeedActivity>().firstOrNull()
                page != null
            }
            run {
                main {
                    // Emulator active transport is reported as Ethernet on some API/device images;
                    // this test explicitly validates playback, not the user's Wi-Fi-only preference.
                    AppSettings.setVideoWifiOnly(context, false)
                    val activity = page!!
                    controller = activity.javaClass.getDeclaredField("controller").apply { isAccessible = true }.get(activity) as VideoFeedController
                    binding = activity.javaClass.getDeclaredField("binding").apply { isAccessible = true }.get(activity) as ActivityVideoFeedBinding
                    controller.filterQuery = "id:1756065 OR id:3868041 OR id:3739179"
                    controller.sort = "id"
                    controller.reloadFeed()
                }
                assertMotion("first sample")
                main { binding.videoPager.setCurrentItem(1, false) }
                waitFor("second page selected and bound") { controller.currentPosition == 1 && holder()?.boundId != null }
                assertMotion("second sample")
                main { binding.videoPager.setCurrentItem(2, false) }
                waitFor("third page selected and bound") { controller.currentPosition == 2 && holder()?.boundId != null }
                assertMotion("third sample")
                main { binding.videoPager.setCurrentItem(0, false) }
                waitFor("first page revisited and bound") { controller.currentPosition == 0 && holder()?.boundId != null }
                assertMotion("revisit A")
                main { binding.videoPager.setCurrentItem(1, false) }
                waitFor("second page revisited and bound") { controller.currentPosition == 1 && holder()?.boundId != null }
                assertMotion("revisit B")
                scrub(0.5f)
                assertMotion("seek playing")
                main { controller.onToggle(controller.currentPosition) }
                scrub(0.25f)
                Thread.sleep(2000)
                main { assertFalse("Paused seek resumed playback", controller.playerPool.get(controller.currentPosition)!!.playWhenReady) }
                main { controller.onToggle(controller.currentPosition) }
                scrub(0.7f); scrub(0.2f); scrub(0.6f)
                assertMotion("consecutive seek")
                for (optimized in listOf(false, true)) {
                    var previous: Any? = null
                    main {
                        previous = controller.playerPool.get(controller.currentPosition)
                        AppSettings.setIpOptimizationEnabled(context, optimized)
                        NetworkManager.shutdown()
                    }
                    waitFor("new network player optimized=$optimized") {
                        NetworkManager.isReady() && controller.playerPool.get(controller.currentPosition)?.let { it !== previous } == true
                    }
                    assertMotion("network optimized=$optimized")
                }
                main { controller.setActive(false) }
                Thread.sleep(500)
                main { controller.setActive(true) }
                assertMotion("leave return")
            }
        } finally {
            main {
                page?.finish()
                AppSettings.setIpOptimizationEnabled(context, oldOptimization)
                AppSettings.setCdnDirect(context, oldCdnDirect)
                AppSettings.setVideoWifiOnly(context, oldWifiOnly)
                NetworkManager.shutdown()
            }
        }
    }
}
