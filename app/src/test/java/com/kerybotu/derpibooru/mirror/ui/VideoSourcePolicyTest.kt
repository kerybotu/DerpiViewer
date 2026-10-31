package com.kerybotu.derpibooru.mirror.ui

import org.junit.Assert.*
import org.junit.Test

class VideoSourcePolicyTest {
    @Test fun verifiedCdnMappingPreservesEscapedQuery() {
        assertEquals("https://derpicdn.net/img/view/2026/8/4/3868041.mp4?value=a%2Fb", VideoSourcePolicy.verifiedMp4Candidate("https://derpicdn.net/img/view/2026/8/4/3868041.webm?value=a%2Fb"))
        assertNull(VideoSourcePolicy.verifiedMp4Candidate("https://example.com/a.webm"))
        assertNull(VideoSourcePolicy.verifiedMp4Candidate("https://derpicdn.net/other/a.webm"))
        assertNull(VideoSourcePolicy.verifiedMp4Candidate("https://evil.derpicdn.net/img/2026/8/4/3868041/full.webm"))
    }

    @Test fun highResolutionUsesBoundedMp4BeforeOriginal() {
        val reps = mapOf("full" to "https://derpicdn.net/img/view/2025/12/31/3739179.webm", "medium" to "https://derpicdn.net/img/2025/12/31/3739179/medium.webm")
        val sources = VideoSourcePolicy.candidates(reps, 3508, 4960)
        assertEquals("https://derpicdn.net/img/2025/12/31/3739179/medium.mp4", sources.first())
        assertEquals(reps["medium"], sources[2])
        assertEquals(sources.size, sources.distinct().size)
    }

    @Test fun explicitApiMp4DoesNotNeedAnInventedRule() {
        assertEquals("https://cdn.example/movie?signature=abc", VideoSourcePolicy.candidates(mapOf("mp4" to "https://cdn.example/movie?signature=abc"), 400, 300).single())
    }

    @Test fun htmlAndJsonAreRejectedAsMedia() {
        assertFalse(VideoSourcePolicy.acceptsContentType("text/html; charset=utf-8"))
        assertFalse(VideoSourcePolicy.acceptsContentType("application/json"))
        assertTrue(VideoSourcePolicy.acceptsContentType("video/mp4"))
        assertTrue(VideoSourcePolicy.acceptsContentType("video/webm"))
    }

    @Test fun videoRepresentationsAreNotInventedAsGifPreviews() {
        assertNull(VideoSourcePolicy.previewCandidate("https://derpicdn.net/img/view/2026/10/31/3923410/small.webm"))
        assertEquals(
            "https://derpicdn.net/img/view/2026/10/31/3923410/small.jpg",
            VideoSourcePolicy.previewCandidate("https://derpicdn.net/img/view/2026/10/31/3923410/small.jpg")
        )
    }

    @Test fun seekPreservesIntentAndLastGestureWins() {
        val seek = VideoSeekState()
        seek.start(true)
        assertTrue(seek.dragging)
        assertTrue(seek.finish())
        assertFalse(seek.dragging)
        seek.start(false)
        assertFalse(seek.finish())
        seek.start(true)
        seek.cancel()
        seek.start(false)
        assertFalse(seek.finish())
    }
}
