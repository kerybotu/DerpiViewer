package com.kerybotu.derpibooru.mirror.ui

/** Captures intent once; scrubbing never sends a sequence of competing seeks. */
internal class VideoSeekState {
    var dragging = false
        private set
    private var resume = false
    fun start(playWhenReady: Boolean) { dragging = true; resume = playWhenReady }
    fun finish(): Boolean { dragging = false; return resume }
    fun cancel() { dragging = false; resume = false }
}
