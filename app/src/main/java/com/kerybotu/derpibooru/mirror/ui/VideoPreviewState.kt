package com.kerybotu.derpibooru.mirror.ui

/** Small holder-local state machine; player callbacks must match the currently bound post. */
internal class VideoPreviewState {
    enum class Phase { PREVIEW, LOADING, PLAYING, PAUSED, FAILED }

    var imageId: Int? = null
        private set
    var phase: Phase = Phase.PREVIEW
        private set

    val previewVisible: Boolean get() = phase != Phase.PLAYING && phase != Phase.PAUSED

    fun bind(newImageId: Int) {
        imageId = newImageId
        phase = Phase.PREVIEW
    }

    fun preparing(callbackImageId: Int): Boolean {
        if (imageId != callbackImageId) return false
        if (phase != Phase.PLAYING && phase != Phase.PAUSED) phase = Phase.LOADING
        return true
    }

    fun firstFrame(callbackImageId: Int): Boolean {
        if (imageId != callbackImageId) return false
        phase = Phase.PLAYING
        return true
    }

    fun pause(callbackImageId: Int): Boolean {
        if (imageId != callbackImageId || phase != Phase.PLAYING) return false
        phase = Phase.PAUSED
        return true
    }

    fun error(callbackImageId: Int): Boolean {
        if (imageId != callbackImageId) return false
        phase = Phase.FAILED
        return true
    }

    fun retry(callbackImageId: Int): Boolean {
        if (imageId != callbackImageId) return false
        phase = Phase.LOADING
        return true
    }
}
