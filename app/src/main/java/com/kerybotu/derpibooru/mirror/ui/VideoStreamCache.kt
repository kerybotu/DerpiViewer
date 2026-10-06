package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/** Shared cache for short forward prefetches and normal video playback. */
object VideoStreamCache {
    const val PREFETCH_BYTES = 2L * 1024L * 1024L
    private const val MAX_CACHE_BYTES = 64L * 1024L * 1024L

    @Volatile private var instance: SimpleCache? = null

    fun get(context: Context): SimpleCache = instance ?: synchronized(this) {
        instance ?: SimpleCache(
            File(context.cacheDir, "video_stream_cache"),
            LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
            StandaloneDatabaseProvider(context.applicationContext)
        ).also { instance = it }
    }
}
