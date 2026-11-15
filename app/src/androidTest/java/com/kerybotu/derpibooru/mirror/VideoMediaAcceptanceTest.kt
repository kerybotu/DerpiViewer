package com.kerybotu.derpibooru.mirror

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.kerybotu.derpibooru.mirror.model.Image
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import com.kerybotu.derpibooru.mirror.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/** Requires a real MP4 at target external-files/video-p0.mp4; never fabricates READY. */
@RunWith(AndroidJUnit4::class)
class VideoMediaAcceptanceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private fun main(block: () -> Unit) = inst.runOnMainSync(block)
    private fun waitFor(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 25_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ok = false
            main { ok = predicate() }
            if (ok) return
            Thread.sleep(100)
        }
        capture("failure-${label.replace(Regex("[^A-Za-z0-9]"), "-")}")
        fail("Timed out: $label")
    }
    private fun capture(name: String) {
        val file = File(context.getExternalFilesDir(null), "video-acceptance/$name.png")
        file.parentFile!!.mkdirs()
        inst.uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun tap(view: View) {
        val rect = Rect()
        main { screenBounds(view, rect) }
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            inst.uiAutomation.injectInputEvent(event, true)
            event.recycle()
        }
        inst.waitForIdleSync()
    }
    private fun screenBounds(view: View, rect: Rect) {
        assertTrue(view.isShown)
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        rect.set(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }
    private fun fixture(): ByteArray = File(context.getExternalFilesDir(null),
        InstrumentationRegistry.getArguments().getString("fixture") ?: "video-p0.mp4").also {
        assertTrue("Push a real local/CDN MP4 as ${it.absolutePath}", it.isFile)
    }.readBytes()

    @Test fun embeddedVideoBottomNavigationUsesVideoFramesAcrossTabsAndThemes() {
        val optimized = AppSettings.isIpOptimizationEnabled(context)
        val wifiOnly = AppSettings.isVideoWifiOnly(context)
        var page: MainActivity? = null
        fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        try {
            AppSettings.setIpOptimizationEnabled(context, false)
            AppSettings.setVideoWifiOnly(context, false)
            runBlocking { NetworkManager.init(context) }
            Server(fixture()).use { server ->
                page = inst.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                val requestedLandscape = InstrumentationRegistry.getArguments().getString("orientation") == "landscape"
                main { page!!.requestedOrientation = if (requestedLandscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                waitFor("embedded orientation") {
                    page = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
                    page?.resources?.configuration?.orientation == if (requestedLandscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                }
                val activity = page!!
                lateinit var embedded: EmbeddedVideoView
                lateinit var controller: VideoFeedController
                lateinit var adapter: VideoFeedAdapter
                main {
                    activity.javaClass.getDeclaredMethod("showEmbeddedScreen", Int::class.javaPrimitiveType)
                        .apply { isAccessible = true }.invoke(activity, R.id.tab_video_feed)
                    embedded = field(activity, "embeddedVideo") as EmbeddedVideoView
                    controller = field(embedded, "controller") as VideoFeedController
                    adapter = field(embedded, "adapter") as VideoFeedAdapter
                    (field(controller, "feedJob") as? kotlinx.coroutines.Job)?.cancel()
                    controller.loading = false
                    adapter.replace(listOf(-99, -98).map { id -> VideoPost(id, server.url("range") + "?item=$id", uploader="Local video acceptance", tags=emptyList(), upvotes=0, downvotes=0, commentCount=0) })
                    controller.activate(0, true)
                }
                waitFor("embedded video playing") { controller.playerPool.get(0)?.isPlaying == true }
                for (position in listOf(1, 0)) {
                    main { (field(embedded, "binding") as com.kerybotu.derpibooru.mirror.databinding.ActivityVideoFeedBinding).videoPager.setCurrentItem(position, false) }
                    waitFor("video switch position=$position") {
                        controller.currentPosition == position && controller.playerPool.get(position)?.isPlaying == true
                    }
                }
                val binding = field(activity, "binding") as com.kerybotu.derpibooru.mirror.databinding.ActivityMainBinding
                waitFor("bottom navigation receives video source") {
                    binding.glassBottomNavigation.backdropSource === embedded.glassBackdropSource
                }
                for (playing in listOf(false, true)) {
                    main { controller.playerPool.get(0)!!.playWhenReady = playing }
                    Thread.sleep(800)
                    capture("embedded-bottom-${if (playing) "playing" else "paused"}")
                }
                val palette = AppSettings.getPalette(context)
                try {
                    for (mode in listOf(AppSettings.Palette.LIGHT, AppSettings.Palette.DARK)) {
                        main {
                            AppSettings.setPalette(context, mode)
                            embedded.refreshPalette()
                            activity.javaClass.getDeclaredMethod("updateShellGlass").apply { isAccessible = true }.invoke(activity)
                        }
                        Thread.sleep(800)
                        capture("embedded-bottom-${mode.name.lowercase()}")
                    }
                } finally { main { AppSettings.setPalette(context, palette) } }
                main {
                    val switch = activity.javaClass.getDeclaredMethod("showEmbeddedScreen", Int::class.javaPrimitiveType).apply { isAccessible = true }
                    switch.invoke(activity, R.id.tab_home)
                    switch.invoke(activity, R.id.tab_video_feed)
                }
                waitFor("embedded video returns with video backdrop") {
                    controller.playerPool.get(0)?.isPlaying == true && binding.glassBottomNavigation.backdropSource === embedded.glassBackdropSource
                }
                capture("embedded-bottom-return")
            }
        } finally {
            main { page?.finish() }
            inst.waitForIdleSync()
            Thread.sleep(300)
            AppSettings.setIpOptimizationEnabled(context, optimized)
            AppSettings.setVideoWifiOnly(context, wifiOnly)
            NetworkManager.shutdown()
        }
    }

    @Test fun realMedia200206RedirectAndHttpErrors() {
        val original = AppSettings.isIpOptimizationEnabled(context)
        try {
            AppSettings.setIpOptimizationEnabled(context, false)
            runBlocking { NetworkManager.init(context) }
            val media = fixture()
            Server(media).use { server ->
                for (path in listOf("range", "full", "redirect")) {
                    val data = OkHttpDataSource.Factory(MediaHttpDiagnostics.calls("acceptance"))
                        .setContentTypePredicate(VideoSourcePolicy::acceptsContentType).createDataSource()
                    try {
                        data.open(DataSpec.Builder().setUri(server.url(path)).setPosition(128).setLength(256).build())
                        val actual = ByteArray(256)
                        var read = 0
                        while (read < actual.size) {
                            val count = data.read(actual, read, actual.size - read)
                            assertTrue("premature EOF $path", count > 0)
                            read += count
                        }
                        assertArrayEquals("$path must return exact media byte range", media.copyOfRange(128, 384), actual)
                    } finally { data.close() }
                }
                for (code in listOf(403, 404)) {
                    val data = OkHttpDataSource.Factory(MediaHttpDiagnostics.calls("acceptance")).createDataSource()
                    try {
                        data.open(DataSpec.Builder().setUri(server.url("error$code")).build())
                        fail("Expected HTTP $code")
                    } catch (error: HttpDataSource.InvalidResponseCodeException) {
                        assertEquals(code, error.responseCode)
                    } finally { data.close() }
                }
                val html = OkHttpDataSource.Factory(MediaHttpDiagnostics.calls("acceptance"))
                    .setContentTypePredicate(VideoSourcePolicy::acceptsContentType).createDataSource()
                try {
                    html.open(DataSpec.Builder().setUri(server.url("html")).build())
                    fail("HTML is not playable media")
                } catch (error: HttpDataSource.InvalidContentTypeException) {
                    assertTrue(error.contentType.startsWith("text/html"))
                } finally { html.close() }
                assertTrue(server.requests.any { it.contains("bytes=128-383") })
            }
        } finally {
            AppSettings.setIpOptimizationEnabled(context, original)
            NetworkManager.shutdown()
        }
    }

    @Test fun stillAndGifKeepImageViewerWithoutVideoControls() {
        val still = File(context.getExternalFilesDir(null), "video-test-still.png")
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).let { bitmap ->
            bitmap.eraseColor(android.graphics.Color.CYAN)
            still.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        for ((file, mime) in listOf(still to "image/png", File(context.getExternalFilesDir(null), "video-original.gif") to "image/gif")) {
            assertTrue("Requires real GIF fixture: $file", file.isFile)
            Server(file.readBytes(), mime).use { server ->
            val page = inst.startActivitySync(Intent(context, FullScreenImageActivity::class.java)
                .putExtra("full_url", server.url("full")).putExtra("mime_type", mime)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try {
                val image = page.findViewById<android.widget.ImageView>(R.id.fullscreen_image)
                waitFor("Image decoded mime=$mime") {
                    image.drawable != null && (mime != "image/gif" || image.drawable is com.bumptech.glide.load.resource.gif.GifDrawable)
                }
                main {
                    assertEquals(View.GONE, page.findViewById<PlayerView>(R.id.fullscreen_video).visibility)
                    assertNull(page.findViewById<PlayerView>(R.id.fullscreen_video).player)
                }
                capture("fullscreen-${if (mime == "image/gif") "gif" else "still"}")
            } finally { main { page.finish() }; inst.waitForIdleSync() }
            }
        }
    }

    @Test fun detailAndFullscreenNativeControlsReceiveRealTouches() {
        val original = AppSettings.isIpOptimizationEnabled(context)
        var page: Activity? = null
        try {
            AppSettings.setIpOptimizationEnabled(context, false)
            runBlocking { NetworkManager.init(context) }
            Server(fixture()).use { server ->
                for (detail in listOf(true, false)) {
                    val intent = if (detail) Intent(context, ImageDetailActivity::class.java).putExtra("image",
                        Image(-42, "Local media acceptance", null, 640, 360, 0, 0, 0, 0, 0, emptyList(),
                            fullUrl = server.url("range"), mimeType = "video/mp4"))
                    else Intent(context, FullScreenImageActivity::class.java).putExtra("full_url", server.url("range"))
                        .putExtra("mime_type", "video/mp4")
                    page = inst.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    val requestedLandscape = InstrumentationRegistry.getArguments().getString("orientation") == "landscape"
                    val requested = if (requestedLandscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    main { page!!.requestedOrientation = requested }
                    waitFor("requested orientation") {
                        page = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .firstOrNull { if (detail) it is ImageDetailActivity else it is FullScreenImageActivity }
                        page?.resources?.configuration?.orientation == if (requestedLandscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                    }
                    val activity = page!!
                    val view = activity.findViewById<PlayerView>(if (detail) R.id.detail_video_player else R.id.fullscreen_video)
                    waitFor("real rendered playback detail=$detail") {
                        view.player?.isPlaying == true && view.player!!.duration > 1000 &&
                            (view.videoSurfaceView as? android.view.TextureView)?.isAvailable == true
                    }
                    val orientation = if (activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
                    run {
                        main { view.showController() }
                        Thread.sleep(400)
                        val play = view.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)
                        capture("before-pause-${if (detail) "detail" else "fullscreen"}-$orientation")
                        tap(play)
                        waitFor("pause tap detail=$detail") { view.player?.playWhenReady == false }
                        Thread.sleep(700)
                        capture("${if (detail) "detail" else "fullscreen"}-paused-$orientation")
                        val bar = view.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
                        val bounds = Rect()
                        main {
                            screenBounds(bar, bounds)
                            // Compact Media3 controls draw the track at the bottom of the
                            // timebar's full touch target, below overlapping central buttons.
                            val track = bar.javaClass.getDeclaredField("progressBar").apply { isAccessible = true }.get(bar) as Rect
                            bounds.set(bounds.left + track.left, bounds.top + track.top,
                                bounds.left + track.right, bounds.top + track.bottom)
                            android.util.Log.i("VideoAcceptance", "track=$bounds enabled=${bar.isEnabled} duration=${view.player!!.duration}")
                            bar.setOnTouchListener { _, event ->
                                android.util.Log.i("VideoAcceptance", "timebar action=${event.actionMasked} x=${event.x} y=${event.y}")
                                false
                            }
                        }
                        val start = SystemClock.uptimeMillis()
                        for (i in 0..5) {
                            val action = if (i == 0) MotionEvent.ACTION_DOWN else if (i == 5) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
                            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                                bounds.left + bounds.width() * (0.2f + i * .1f), bounds.exactCenterY(), 0)
                            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                            inst.uiAutomation.injectInputEvent(event, true)
                            event.recycle()
                            Thread.sleep(60)
                        }
                        waitFor("seek from real timebar touch detail=$detail") {
                            kotlin.math.abs(view.player!!.currentPosition.toDouble() / view.player!!.duration - .7) < .15 && !view.player!!.playWhenReady
                        }
                        tap(play)
                        waitFor("resume tap detail=$detail") { view.player?.isPlaying == true }
                        capture("${if (detail) "detail" else "fullscreen"}-playing-$orientation")
                    }
                    main { activity.finish() }
                    page = null
                }
            }
        } finally {
            main { page?.finish() }
            inst.waitForIdleSync()
            Thread.sleep(300)
            AppSettings.setIpOptimizationEnabled(context, original)
            NetworkManager.shutdown()
        }
    }

    private class Server(private val media: ByteArray, private val mime: String = "video/mp4") : AutoCloseable {
        private val socket = ServerSocket(0)
        val requests = CopyOnWriteArrayList<String>()
        private val thread = Thread {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                Thread {
                    client.use {
                        val reader = it.getInputStream().bufferedReader()
                        val request = reader.readLine() ?: return@use
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isBlank()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        val path = request.split(' ')[1].substringAfter('/').substringBefore('?')
                        requests += "$path Range=${headers["range"]}"
                        var status = 200
                        var body = media
                        var extra = "Content-Type: $mime\r\nAccept-Ranges: bytes\r\n"
                        if (path == "redirect") { status = 302; body = byteArrayOf(); extra = "Location: /range\r\n" }
                        else if (path.startsWith("error")) { status = path.removePrefix("error").toInt(); body = byteArrayOf() }
                        else if (path == "html") { body = "<html>not media</html>".toByteArray(); extra = "Content-Type: text/html\r\n" }
                        else if (path != "full" && headers["range"] != null) {
                            val parts = headers.getValue("range").removePrefix("bytes=").split('-')
                            val from = parts[0].toInt()
                            val to = parts.getOrNull(1)?.toIntOrNull()?.coerceAtMost(media.lastIndex) ?: media.lastIndex
                            body = media.copyOfRange(from, to + 1)
                            status = 206
                            extra += "Content-Range: bytes $from-$to/${media.size}\r\n"
                        }
                        try {
                            it.getOutputStream().apply {
                                write("HTTP/1.1 $status Result\r\n${extra}Content-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                write(body)
                                flush()
                            }
                        } catch (_: java.io.IOException) { /* Media3 can cancel buffered streams. */ }
                    }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true; start() }
        fun url(path: String) = "http://127.0.0.1:${socket.localPort}/$path"
        override fun close() { socket.close(); thread.join(1000) }
    }
}
