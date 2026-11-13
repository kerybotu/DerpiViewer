package com.kerybotu.derpibooru.mirror.ui

import android.widget.ImageView
import android.content.Context
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.Priority
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
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
        load(view, url, null, maxConcurrent)
    }

    fun load(
        view: ImageView,
        url: String?,
        placeholderUrl: String?,
        @Suppress("UNUSED_PARAMETER") maxConcurrent: Int = 0,
        onResourceChanged: (() -> Unit)? = null,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        ensureRegistered(view.context)
        val primary = url?.trim()?.takeIf { it.isNotEmpty() }
        val fallback = placeholderUrl?.trim()?.takeIf { it.isNotEmpty() && it != primary }
        val model = primary ?: fallback
        var req = Glide.with(view).load(model).priority(Priority.HIGH)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .signature(ObjectKey(model ?: "empty-image"))
            .placeholder(R.drawable.ic_image_placeholder)
            .fallback(R.drawable.ic_image_error)
            .error(R.drawable.ic_image_error)
        if (primary != null && fallback != null) {
            req = req.thumbnail(Glide.with(view).load(fallback).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC))
        }
        if (onFailure != null || onResourceChanged != null) {
            req.listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    onFailure?.invoke(e ?: java.io.IOException("Image load failed"))
                    onResourceChanged?.invoke()
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>,
                    dataSource: com.bumptech.glide.load.DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    onResourceChanged?.invoke()
                    return false
                }
            })
        }
        req.into(view)
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
