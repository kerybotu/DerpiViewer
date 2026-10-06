package com.kerybotu.derpibooru.mirror.ui

/** Refresh is committed on release, so retreating or cancelling a pull never sends a request. */
internal class PullRefreshState(val triggerDistance: Float) {
    init { require(triggerDistance > 0f) }

    var distance = 0f
        private set
    var isRefreshing = false
        private set
    val progress: Float get() = (distance / triggerDistance).coerceIn(0f, 1f)

    fun pull(distance: Float) {
        if (!isRefreshing) this.distance = distance.coerceIn(0f, triggerDistance * 1.5f)
    }

    fun release(cancelled: Boolean = false): Boolean {
        if (isRefreshing) return false
        val shouldRefresh = !cancelled && distance >= triggerDistance
        setRefreshing(shouldRefresh)
        return shouldRefresh
    }

    fun setRefreshing(refreshing: Boolean) {
        isRefreshing = refreshing
        distance = if (refreshing) triggerDistance else 0f
    }
}
