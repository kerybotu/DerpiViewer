package com.kerybotu.derpibooru.mirror.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPreviewStateTest {
    @Test fun previewRemainsVisibleUntilFirstRenderedFrame() {
        val state = VideoPreviewState()
        state.bind(101)
        assertTrue(state.previewVisible)
        assertTrue(state.preparing(101))
        assertTrue(state.previewVisible)
        assertTrue(state.firstFrame(101))
        assertFalse(state.previewVisible)
    }

    @Test fun failedPlaybackRestoresPreviewAndRetryWaitsForAnotherFrame() {
        val state = VideoPreviewState()
        state.bind(101)
        state.firstFrame(101)
        assertTrue(state.error(101))
        assertEquals(VideoPreviewState.Phase.FAILED, state.phase)
        assertTrue(state.previewVisible)
        assertTrue(state.retry(101))
        assertEquals(VideoPreviewState.Phase.LOADING, state.phase)
        assertTrue(state.previewVisible)
        state.firstFrame(101)
        assertFalse(state.previewVisible)
    }

    @Test fun callbacksFromRecycledPostCannotChangeCurrentCard() {
        val state = VideoPreviewState()
        state.bind(202)
        assertFalse(state.firstFrame(101))
        assertFalse(state.error(101))
        assertEquals(VideoPreviewState.Phase.PREVIEW, state.phase)
        assertTrue(state.previewVisible)
    }

    @Test fun pauseAfterRenderedFrameKeepsVideoVisibleAndIsNotAnError() {
        val state = VideoPreviewState()
        state.bind(303)
        state.firstFrame(303)
        assertTrue(state.pause(303))
        assertEquals(VideoPreviewState.Phase.PAUSED, state.phase)
        assertFalse(state.previewVisible)
        assertFalse(state.error(999))
    }
}
