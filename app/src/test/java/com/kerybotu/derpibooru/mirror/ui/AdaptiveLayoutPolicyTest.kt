package com.kerybotu.derpibooru.mirror.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLayoutPolicyTest {
    @Test
    fun widthClassesUseAvailableWindowWidthBreakpoints() {
        assertEquals(AdaptiveLayoutPolicy.WindowWidthClass.COMPACT, AdaptiveLayoutPolicy.widthClass(599))
        assertEquals(AdaptiveLayoutPolicy.WindowWidthClass.MEDIUM, AdaptiveLayoutPolicy.widthClass(600))
        assertEquals(AdaptiveLayoutPolicy.WindowWidthClass.MEDIUM, AdaptiveLayoutPolicy.widthClass(839))
        assertEquals(AdaptiveLayoutPolicy.WindowWidthClass.EXPANDED, AdaptiveLayoutPolicy.widthClass(840))
    }

    @Test
    fun tokensIncreaseContentRoomWithoutUnboundedIslands() {
        val compact = AdaptiveLayoutPolicy.tokensForWidth(411)
        val medium = AdaptiveLayoutPolicy.tokensForWidth(800)
        val expanded = AdaptiveLayoutPolicy.tokensForWidth(1200)

        assertTrue(compact.screenGutterDp < medium.screenGutterDp)
        assertTrue(medium.screenGutterDp < expanded.screenGutterDp)
        assertTrue(compact.topIslandMaxWidthDp < expanded.topIslandMaxWidthDp)
        assertEquals(AdaptiveLayoutPolicy.BOTTOM_ISLAND_MAX_WIDTH_DP, expanded.bottomIslandMaxWidthDp)
    }

    @Test
    fun artworkGridGrowsWithWindowAndRemainsCapped() {
        assertEquals(2, AdaptiveLayoutPolicy.artworkColumnCountForWidth(360))
        assertEquals(2, AdaptiveLayoutPolicy.artworkColumnCountForWidth(411))
        assertEquals(3, AdaptiveLayoutPolicy.artworkColumnCountForWidth(600))
        assertEquals(4, AdaptiveLayoutPolicy.artworkColumnCountForWidth(800))
        assertEquals(6, AdaptiveLayoutPolicy.artworkColumnCountForWidth(1600))
    }
}
