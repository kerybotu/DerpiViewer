package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import okhttp3.Call
import java.util.concurrent.Future
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import kotlinx.coroutines.CoroutineScope
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Keeps decoder usage bounded: only the visible item owns an ExoPlayer. */
class VideoPlayerPool(context: Context, private val poolSize: Int = 1) {
    private val appContext = context.applicationContext
    private val bandwidthMeter = DefaultBandwidthMeter.Builder(appContext).build()
    private val assignments = LinkedHashMap<Int, ExoPlayer>()
    private val players = mutableListOf<ExoPlayer>()
    private val listeners = mutableListOf<Player.Listener>()
    private var prefetchExecutor: ExecutorService = newPrefetchExecutor()
    private val prefetching = Collections.synchronizedSet(mutableSetOf<String>())
    private val prefetchWriters = Collections.synchronizedList(mutableListOf<CacheWriter>())
    private val prefetchTasks = mutableListOf<Future<*>>()
    private var session = 0L
    private var muted = !AppSettings.isVideoAudioEnabled(appContext)
    var onPlaybackError: ((Int, String, PlaybackException) -> Unit)? = null
    var onFirstFrameRendered: ((Int, String) -> Unit)? = null

    private fun newPrefetchExecutor(): ExecutorService = Executors.newSingleThreadExecutor()

    private fun upstreamFactory(purpose: String): DefaultDataSource.Factory {
        // Resolve on every HTTP call, never retain the proxy from player creation.
        val calls = Call.Factory { request ->
            (NetworkManager.videoHttpClient() ?: error("媒体网络尚未就绪")).newCall(request)
        }
        val httpFactory = OkHttpDataSource.Factory(calls)
            .setContentTypePredicate { VideoSourcePolicy.acceptsContentType(it) }
            .setTransferListener(bandwidthMeter)
        return DefaultDataSource.Factory(appContext, httpFactory).setTransferListener(VideoTransferMetrics(purpose))
    }

    private fun cacheDataSourceFactory(purpose: String): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(VideoStreamCache.get(appContext))
        .setUpstreamDataSourceFactory(upstreamFactory(purpose))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        .setEventListener(object : CacheDataSource.EventListener {
            override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
                Log.d(TAG, "cache_read purpose=$purpose bytes=$cachedBytesRead cacheSize=$cacheSizeBytes")
            }
            override fun onCacheIgnored(reason: Int) { Log.d(TAG, "cache_ignored purpose=$purpose reason=$reason") }
        })

    // No BLOCK_ON_CACHE: an active request must never wait for a prefetch writer.
    private fun playbackDataSourceFactory(): CacheDataSource.Factory = cacheDataSourceFactory("playback")

    private fun createPlayer(): ExoPlayer {
        val dataSource = playbackDataSourceFactory()
        val player = ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                volume = if (muted) 0f else 1f
                listeners.forEach(::addListener)
            }
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long
            ) {
                val position = ownerOf(player)
                val url = player.currentMediaItem?.localConfiguration?.uri?.toString()
                Log.d(TAG, "first_frame position=$position media=${player.currentMediaItem?.mediaId} output=${output.javaClass.simpleName} renderTimeMs=$renderTimeMs url=${url?.substringBefore('?')}")
                if (position != null && url != null) onFirstFrameRendered?.invoke(position, url)
            }

            override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
                ownerOf(player)?.let { position ->
                    player.currentMediaItem?.localConfiguration?.uri?.toString()?.let { url ->
                        onPlaybackError?.invoke(position, url, error)
                    }
                }
            }

            override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
                if (!eventMatches(eventTime, player)) return
                val ms = loadEventInfo.loadDurationMs
                Log.d(TAG, "media_load media=${player.currentMediaItem?.mediaId} config=${NetworkManager.configurationVersion.value} bytes=${loadEventInfo.bytesLoaded} durationMs=$ms bytesPerSec=${if (ms > 0) loadEventInfo.bytesLoaded * 1000 / ms else 0} bufferedMs=${player.totalBufferedDuration}")
            }
        })
        return player
    }

    private fun ownerOf(player: ExoPlayer): Int? =
        assignments.entries.firstOrNull { it.value === player }?.key

    private fun eventMatches(event: AnalyticsListener.EventTime, player: ExoPlayer): Boolean =
        !event.timeline.isEmpty && event.windowIndex in 0 until event.timeline.windowCount &&
            event.timeline.getWindow(event.windowIndex, Timeline.Window()).mediaItem.mediaId == player.currentMediaItem?.mediaId

    /**
     * Bind the output before starting preparation. A fast/cached source can
     * render its first frame synchronously; attaching the PlayerView first
     * prevents that callback from being lost while a recycled ViewPager holder
     * is still detached.
     */
    fun prepare(
        position: Int,
        url: String,
        imageId: Int = position,
        beforePrepare: ((ExoPlayer) -> Unit)? = null
    ): ExoPlayer {
        val player = assignments[position] ?: acquire(position)
        if (player.currentMediaItem?.localConfiguration?.uri.toString() != url) {
            player.setMediaItem(MediaItem.Builder().setUri(url).setMediaId("$imageId:${++session}").build())
            beforePrepare?.invoke(player)
            player.prepare()
        } else if (player.playbackState == Player.STATE_IDLE) {
            beforePrepare?.invoke(player)
            player.prepare()
        } else {
            beforePrepare?.invoke(player)
        }
        return player
    }

    fun play(position: Int, url: String) = prepare(position, url).also { it.playWhenReady = true }

    fun prefetch(urls: List<String>, scope: CoroutineScope) {
        urls.asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .take(1)
            .forEach { url ->
                if (!prefetching.add(url)) return@forEach
                val executor = prefetchExecutor
                if (executor.isShutdown) {
                    prefetching.remove(url)
                    return@forEach
                }
                runCatching { prefetchTasks += executor.submit {
                    val dataSource = runCatching { cacheDataSourceFactory("prefetch").createDataSource() }.getOrNull()
                    if (dataSource == null) {
                        prefetching.remove(url)
                        return@submit
                    }
                    try {
                        val request = DataSpec.Builder()
                            .setUri(url)
                            .setPosition(0L)
                            .setLength(VideoStreamCache.PREFETCH_BYTES)
                            .build()
                        // This task is deliberately not tied to the caller's
                        // cancellation scope. Letting CacheWriter finish its
                        // current span prevents interrupted SimpleCache locks.
                        val writer = CacheWriter(dataSource, request, null, null)
                        prefetchWriters.add(writer)
                        try { writer.cache() } finally { prefetchWriters.remove(writer) }
                        Log.d(TAG, "prefetch_complete config=${NetworkManager.configurationVersion.value}")
                    } catch (error: Exception) {
                        Log.d(TAG, "prefetch_end reason=${error.javaClass.simpleName}")
                    } finally {
                        runCatching { dataSource.close() }
                        prefetching.remove(url)
                    }
                } }.onFailure {
                    prefetching.remove(url)
                    Log.d(TAG, "视频预缓存无法排队: ${it.message}")
                }
            }
    }

    fun pause(position: Int) {
        assignments[position]?.playWhenReady = false
    }

    fun get(position: Int): ExoPlayer? = assignments[position]

    fun setMuted(muted: Boolean) {
        this.muted = muted
        players.forEach { it.volume = if (muted) 0f else 1f }
    }

    fun setSpeed(position: Int, speed: Float) {
        assignments[position]?.setPlaybackSpeed(speed)
    }

    fun addListener(listener: Player.Listener) {
        listeners += listener
        players.forEach { it.addListener(listener) }
    }

    fun bitrateEstimate(): Long = bandwidthMeter.bitrateEstimate

    fun retainOnly(positions: Set<Int>) {
        assignments.keys.filter { it !in positions }.toList().forEach { release(it) }
    }

    fun pauseAll() = players.forEach { it.playWhenReady = false }

    fun releaseAll() {
        cancelPrefetch()
        assignments.clear()
        players.forEach { it.release() }
        players.clear()
        // reloadFeed() reuses this pool; keep its prefetch executor alive.
    }

    fun dispose() {
        releaseAll()
        prefetching.clear()
        // Let active CacheWriter operations finish to avoid locking a cache span.
        prefetchExecutor.shutdown()
    }

    fun cancelPrefetch() {
        synchronized(prefetchWriters) { prefetchWriters.forEach { it.cancel() } }
        prefetchTasks.forEach { it.cancel(false) }
        prefetchTasks.clear()
    }

    fun releaseNonCurrent(currentPosition: Int) {
        assignments.keys.filter { it != currentPosition }.toList().forEach { release(it) }
    }

    private fun acquire(position: Int): ExoPlayer {
        val free = players.firstOrNull { candidate -> assignments.values.none { it === candidate } }
            ?: if (players.size < poolSize) createPlayer().also(players::add) else null
        val player = free ?: assignments.entries.first().let { (oldPosition, oldPlayer) ->
            assignments.remove(oldPosition)
            oldPlayer.stop()
            oldPlayer
        }
        assignments[position] = player
        return player
    }

    private fun release(position: Int) {
        assignments.remove(position)?.apply {
            playWhenReady = false
            clearMediaItems()
        }
    }

    private companion object {
        const val TAG = "VideoPlayerPool"
    }
}
