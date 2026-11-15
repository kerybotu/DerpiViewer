package com.kerybotu.derpibooru.mirror.ui

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class MediaTypePolicyTest {
    @Test fun metadataClassifiesVideoAndKeepsOriginalGifDistinct() {
        assertTrue(MediaTypePolicy.isVideo(" Video/WebM; codecs=vp9 "))
        assertTrue(MediaTypePolicy.isVideo("video/mp4"))
        assertFalse(MediaTypePolicy.isVideo("image/gif"))
        assertTrue(MediaTypePolicy.isGif("IMAGE/GIF"))
        assertFalse(MediaTypePolicy.isVideo(null))
        assertNull(MediaTypePolicy.normalize("null"))
        assertNull(MediaTypePolicy.normalize(""))
    }

    @Test fun apiWebmRemainsAvailableWhenAdvertisedMp4IsMissing() {
        val urls = VideoSourcePolicy.candidates(mapOf(
            "full" to "https://cdn.example/original.gif",
            "mp4" to "https://cdn.example/unavailable",
            "webm" to "https://cdn.example/valid?token=secret"
        ), 400, 300)
        assertEquals(listOf("https://cdn.example/unavailable", "https://cdn.example/valid?token=secret"), urls.take(2))
    }

    @Test fun diagnosticUrlNeverContainsCredentialsQueriesOrFragments() {
        val safe = MediaHttpDiagnostics.safeUrl("https://user:password@cdn.example/media.mp4?key=secret&cookie=value#token".toHttpUrl())
        assertEquals("https://cdn.example/media.mp4", safe)
    }
}
