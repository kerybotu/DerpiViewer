package com.kerybotu.derpibooru.mirror.ui

internal enum class DetailInfoState { COMPACT, EXPANDED }
internal object DetailInfoSnap {
    fun target(progress: Float, velocityX: Float): DetailInfoState = when {
        velocityX < -700f -> DetailInfoState.EXPANDED
        velocityX > 700f -> DetailInfoState.COMPACT
        progress >= .5f -> DetailInfoState.EXPANDED
        else -> DetailInfoState.COMPACT
    }
}
