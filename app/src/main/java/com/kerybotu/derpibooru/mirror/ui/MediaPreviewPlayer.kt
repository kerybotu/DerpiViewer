package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import okhttp3.OkHttpClient

class MediaPreviewPlayer(context: Context, private val view: PlayerView) {
    private val player = ExoPlayer.Builder(context.applicationContext)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(
            context.applicationContext,
            OkHttpDataSource.Factory(NetworkManager.imageHttpClient() ?: OkHttpClient())
        )))
        .build()

    init {
        view.player = player
        view.useController = true
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true
        )
        player.volume = if (AppSettings.isVideoAudioEnabled(context)) 1f else 0f
    }

    fun load(url: String) {
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
    }

    fun release() {
        view.player = null
        player.release()
    }
}
