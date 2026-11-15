package com.kerybotu.derpibooru.mirror

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.kerybotu.derpibooru.mirror.databinding.ActivityVideoFeedBinding
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import com.kerybotu.derpibooru.mirror.ui.VideoFeedActivity
import com.kerybotu.derpibooru.mirror.ui.VideoFeedAdapter
import com.kerybotu.derpibooru.mirror.ui.VideoFeedController
import com.kerybotu.derpibooru.mirror.ui.VideoPost
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real reported site records, provided as unmodified API JSON in target external-files. */
@RunWith(AndroidJUnit4::class)
class ReportedGifPlaybackTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private fun main(block: () -> Unit) = inst.runOnMainSync(block)
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun waitFor(label: String, check: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 90_000
        while (SystemClock.elapsedRealtime() < until) {
            var ok = false
            main { ok = check() }
            if (ok) return
            Thread.sleep(200)
        }
        fail(label)
    }

    @Test fun missingTranscodesFallBackToEachReportedOriginalGif() {
        val optimization = AppSettings.isIpOptimizationEnabled(context)
        val wifiOnly = AppSettings.isVideoWifiOnly(context)
        var page: VideoFeedActivity? = null
        try {
            AppSettings.setIpOptimizationEnabled(context, false)
            AppSettings.setVideoWifiOnly(context, false)
            runBlocking { NetworkManager.init(context) }
            val records = listOf(350706, 486065, 561551).map { id ->
                val file = File(context.getExternalFilesDir(null), "video-$id.json")
                assertTrue("Requires actual API response: $file", file.isFile)
                JSONObject(file.readText()).getJSONObject("image")
            }
            page = inst.startActivitySync(Intent(context, VideoFeedActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VideoFeedActivity
            val activity = page!!
            lateinit var controller: VideoFeedController
            lateinit var adapter: VideoFeedAdapter
            lateinit var binding: ActivityVideoFeedBinding
            main {
                controller = field(activity, "controller") as VideoFeedController
                adapter = field(activity, "adapter") as VideoFeedAdapter
                binding = field(activity, "binding") as ActivityVideoFeedBinding
                (field(controller, "feedJob") as? Job)?.cancel()
                controller.loading = false
                val json = JSONObject().put("images", org.json.JSONArray(records)).toString()
                @Suppress("UNCHECKED_CAST")
                val posts = controller.javaClass.getDeclaredMethod("parseVideoPosts", String::class.java).apply { isAccessible = true }.invoke(controller, json) as List<VideoPost>
                assertEquals(3, posts.size)
                adapter.replace(posts)
                binding.videoEmptyGlass.visibility = View.GONE
                controller.activate(0, true)
            }
            fun holder(): VideoFeedAdapter.Holder? = (binding.videoPager.getChildAt(0) as RecyclerView)
                .findViewHolderForAdapterPosition(controller.currentPosition) as? VideoFeedAdapter.Holder
            for (position in listOf(0, 1, 2, 0)) {
                main { binding.videoPager.setCurrentItem(position, false) }
                waitFor("Reported GIF position=$position must animate after real HTTP transcode failures") {
                    adapter.item(position)?.useOriginalAnimation == true &&
                        holder()?.binding?.videoPreview?.drawable.let { it is GifDrawable && it.isRunning } &&
                        holder()?.binding?.videoPlaybackError?.visibility == View.GONE
                }
                val file = File(context.getExternalFilesDir(null), "video-acceptance/reported-${records[position].getInt("id")}.png")
                file.parentFile!!.mkdirs()
                inst.uiAutomation.takeScreenshot().let { bitmap ->
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                }
            }
            main { controller.setActive(false) }
            main { assertFalse((holder()?.binding?.videoPreview?.drawable as GifDrawable).isRunning) }
            main { controller.setActive(true) }
            waitFor("Animation resumes on page return") { (holder()?.binding?.videoPreview?.drawable as? GifDrawable)?.isRunning == true }
        } finally {
            main { page?.finish() }
            AppSettings.setIpOptimizationEnabled(context, optimization)
            AppSettings.setVideoWifiOnly(context, wifiOnly)
            NetworkManager.shutdown()
        }
    }
}
