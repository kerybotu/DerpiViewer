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
    var onPlaybackError: ((Int, String, PlaybackException) -> Unit)? = null
    var onFirstFrameRendered: ((Int, String) -> Unit)? = null

    private fun newPrefetchExecutor(): ExecutorService = Executors.newFixedThreadPool(
        AppSettings.getCdnThreads(appContext).coerceIn(1, 4)
    )

    private fun upstreamFactory(): DefaultDataSource.Factory {
        val client = NetworkManager.videoHttpClient()
            ?: error("NetworkManager 尚未初始化，拒绝使用未优化的普通网络连接")
        val httpFactory = OkHttpDataSource.Factory(client).setTransferListener(bandwidthMeter)
        return DefaultDataSource.Factory(appContext, httpFactory)
    }

    private fun cacheDataSourceFactory(): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(VideoStreamCache.get(appContext))
        .setUpstreamDataSourceFactory(upstreamFactory())
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** Main playback stays on the direct OkHttp path. The cache is only for bounded
     * forward prefetches, so stale/partial entries cannot break a healthy request. */
    private fun playbackDataSourceFactory(): DefaultDataSource.Factory = upstreamFactory()

    private fun createPlayer(): ExoPlayer {
        val dataSource = playbackDataSourceFactory()
        val player = ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                volume = if (AppSettings.isVideoAudioEnabled(appContext)) 1f else 0f
                listeners.forEach(::addListener)
            }
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long
            ) {
                ownerOf(player)?.let { position ->
                    player.currentMediaItem?.localConfiguration?.uri?.toString()?.let { url ->
                        onFirstFrameRendered?.invoke(position, url)
                    }
                }
            }

            override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
                ownerOf(player)?.let { position ->
                    player.currentMediaItem?.localConfiguration?.uri?.toString()?.let { url ->
                        onPlaybackError?.invoke(position, url, error)
                    }
                }
            }
        })
        return player
    }

    private fun ownerOf(player: ExoPlayer): Int? =
        assignments.entries.firstOrNull { it.value === player }?.key

    fun prepare(position: Int, url: String): ExoPlayer {
        val player = assignments[position] ?: acquire(position)
        if (player.currentMediaItem?.localConfiguration?.uri.toString() != url) {
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
        }
        return player
    }

    fun play(position: Int, url: String) = prepare(position, url).also { it.playWhenReady = true }

    fun prefetch(urls: List<String>, scope: CoroutineScope) {
        urls.asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .take(2)
            .forEach { url ->
                if (!prefetching.add(url)) return@forEach
                val executor = prefetchExecutor
                if (executor.isShutdown) {
                    prefetching.remove(url)
                    return@forEach
                }
                runCatching { executor.execute {
                    val dataSource = runCatching { cacheDataSourceFactory().createDataSource() }.getOrNull()
                    if (dataSource == null) {
                        prefetching.remove(url)
                        return@execute
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
                        CacheWriter(dataSource, request, null, null).cache()
                        Log.d(TAG, "视频预缓存完成: $url")
                    } catch (error: Exception) {
                        Log.d(TAG, "视频预缓存跳过: ${error.message}")
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
