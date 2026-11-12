package com.kerybotu.derpibooru.mirror.ui
import org.junit.Assert.*
import org.junit.Test
class DetailInfoSnapTest {
    @Test fun midpointAndVelocitySelectOnlyStableStates() {
        assertEquals(DetailInfoState.COMPACT, DetailInfoSnap.target(.49f, 0f))
        assertEquals(DetailInfoState.EXPANDED, DetailInfoSnap.target(.5f, 0f))
        assertEquals(DetailInfoState.EXPANDED, DetailInfoSnap.target(.1f, -900f))
        assertEquals(DetailInfoState.COMPACT, DetailInfoSnap.target(.9f, 900f))
    }
}
