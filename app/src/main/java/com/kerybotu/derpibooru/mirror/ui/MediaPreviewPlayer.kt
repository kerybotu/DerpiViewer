package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.PlayerView
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** Shares the feed's bounded player, cache and current media transport. */
class MediaPreviewPlayer(context: Context, private val view: PlayerView) : DefaultLifecycleObserver {
    private val appContext = context.applicationContext
    private val pool = VideoPlayerPool(context)
    private val owner = context as? LifecycleOwner
    private var configurationJob: Job? = null
    private var url: String? = null
    private var resumePlayback = false
    private var released = false
    private var networkPosition = 0L
    private var networkPlayWhenReady = false

    init {
        view.useController = true
        view.controllerHideOnTouch = true
        view.setErrorMessageProvider { error -> android.util.Pair.create(error.errorCode, "视频播放失败（${error.errorCodeName}）") }
        owner?.lifecycle?.addObserver(this)
        configurationJob = owner?.lifecycleScope?.launch {
            var version = NetworkManager.configurationVersion.value
            NetworkManager.configurationVersion.collect { next ->
                if (next != version) {
                    version = next
                    val currentUrl = url
                    val previous = pool.get(0)
                    if (previous != null) {
                        networkPosition = previous.currentPosition
                        networkPlayWhenReady = previous.playWhenReady
                    }
                    view.player = null
                    pool.releaseAll()
                    if (!released && currentUrl != null && !NetworkManager.isReady() && !ensureNetwork()) return@collect
                    if (!released && currentUrl != null && NetworkManager.isReady()) {
                        load(currentUrl)
                        pool.get(0)?.apply { seekTo(networkPosition); playWhenReady = networkPlayWhenReady }
                    }
                }
            }
        }
    }

    fun load(url: String) {
        check(!released)
        if (this.url == url && view.player != null) return
        this.url = url
        if (NetworkManager.isReady()) prepare(url)
        else owner?.lifecycleScope?.launch {
            if (ensureNetwork() && !released && this@MediaPreviewPlayer.url == url && view.player == null) prepare(url)
        }
    }

    private fun prepare(url: String) {
        view.setCustomErrorMessage(null)
        pool.prepare(0, url) { view.player = it }.playWhenReady = true
        view.showController()
    }

    private suspend fun ensureNetwork(): Boolean = try {
        NetworkManager.init(appContext)
        NetworkManager.isReady()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        android.util.Log.w("MediaPreviewPlayer", "media_network_init_failed cause=${error.javaClass.simpleName}")
        if (!released) view.setCustomErrorMessage("视频网络暂时不可用")
        false
    }

    override fun onStop(owner: LifecycleOwner) {
        resumePlayback = pool.get(0)?.playWhenReady == true
        pool.pauseAll()
    }

    override fun onStart(owner: LifecycleOwner) {
        if (resumePlayback) pool.get(0)?.play()
    }

    override fun onDestroy(owner: LifecycleOwner) = release()

    fun release() {
        if (released) return
        released = true
        configurationJob?.cancel()
        owner?.lifecycle?.removeObserver(this)
        view.player = null
        pool.dispose()
    }
}
