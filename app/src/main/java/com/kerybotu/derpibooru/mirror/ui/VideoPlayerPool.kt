package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import okhttp3.OkHttpClient

/** Keeps decoder usage bounded: current, next buffer and previous paused item only. */
class VideoPlayerPool(context: Context, private val poolSize: Int = 3) {
    private val appContext = context.applicationContext
    private val bandwidthMeter = DefaultBandwidthMeter.Builder(appContext).build()
    private val assignments = LinkedHashMap<Int, ExoPlayer>()
    private val players = List(poolSize) { createPlayer() }

    private fun createPlayer(): ExoPlayer {
        val httpFactory = OkHttpDataSource.Factory(NetworkManager.imageHttpClient() ?: OkHttpClient())
            .setTransferListener(bandwidthMeter)
        val dataSource = DefaultDataSource.Factory(appContext, httpFactory)
        return ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                volume = if (AppSettings.isVideoAudioEnabled(appContext)) 1f else 0f
            }
    }

    fun prepare(position: Int, url: String): ExoPlayer {
        val player = assignments[position] ?: acquire(position)
        if (player.currentMediaItem?.localConfiguration?.uri.toString() != url) {
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
        }
        return player
    }

    fun play(position: Int, url: String) = prepare(position, url).also { it.playWhenReady = true }

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
    }

    fun releaseNonCurrent(currentPosition: Int) {
        assignments.keys.filter { it != currentPosition }.toList().forEach { release(it) }
    }

    private fun acquire(position: Int): ExoPlayer {
        val free = players.firstOrNull { candidate -> assignments.values.none { it === candidate } }
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
}
