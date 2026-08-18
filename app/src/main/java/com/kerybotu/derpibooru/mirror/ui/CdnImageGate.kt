package com.kerybotu.derpibooru.mirror.ui

import android.widget.ImageView
import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.Priority
import com.bumptech.glide.request.target.Target
import com.kerybotu.derpibooru.mirror.R
import com.kerybotu.derpibooru.mirror.network.ResourceCoordinator
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 所有图片请求都经同一个 OkHttp 客户端，使 CDN 也会使用本地优选 IP 和 HTTP/2 连接池。
 * Glide 自己负责内存和磁盘缓存；这里不人为限制流的数量。
 */
object CdnImageGate {
    private val registered = AtomicBoolean(false)
    private val prefetchTargets = mutableListOf<Target<*>>()

    fun load(view: ImageView, url: String?, @Suppress("UNUSED_PARAMETER") maxConcurrent: Int = 0) {
        ensureRegistered(view.context)
        Glide.with(view)
            .load(url)
            .priority(Priority.HIGH)
            .placeholder(R.drawable.ic_image_placeholder)
            .into(view)
    }

    fun prefetch(context: Context, urls: List<String?>, limit: Int = 8) {
        if (!ResourceCoordinator.canPrefetch()) return
        ensureRegistered(context)
        urls.asSequence()
            .filterNotNull()
            .distinct()
            .take(limit)
            .forEach {
                val target = Glide.with(context).load(it).priority(Priority.LOW).preload()
                synchronized(prefetchTargets) { prefetchTargets.add(target) }
            }
    }

    fun pausePrefetch(context: Context) {
        synchronized(prefetchTargets) {
            prefetchTargets.forEach { Glide.with(context).clear(it) }
            prefetchTargets.clear()
        }
    }

    private fun ensureRegistered(context: Context) {
        if (!registered.compareAndSet(false, true)) return
        Glide.get(context.applicationContext).registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            CdnOkHttpUrlLoader.Factory()
        )
    }
}
