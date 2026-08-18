package com.kerybotu.derpibooru.mirror.network

import android.content.ComponentCallbacks2
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.util.concurrent.atomic.AtomicBoolean

/** Coordinates page-level demand so background prefetch yields to the video tab. */
object ResourceCoordinator {
    private val videoActive = AtomicBoolean(false)
    private val memoryPressure = AtomicBoolean(false)

    fun enterVideoTab() { videoActive.set(true) }
    fun exitVideoTab() { videoActive.set(false); memoryPressure.set(false) }
    fun isVideoTabActive(): Boolean = videoActive.get()
    fun canPrefetch(): Boolean = !videoActive.get() && !memoryPressure.get()

    fun imagePreloadDistance(context: Context): Int {
        if (!canPrefetch()) return 0
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return 0
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return 3
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) 2 else 1
        }
        return 0
    }

    fun onTrimMemory(context: Context, level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) memoryPressure.set(true)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            // Glide's in-memory cache is deliberately discarded before visible content.
            com.bumptech.glide.Glide.get(context.applicationContext).trimMemory(level)
        }
    }
}
