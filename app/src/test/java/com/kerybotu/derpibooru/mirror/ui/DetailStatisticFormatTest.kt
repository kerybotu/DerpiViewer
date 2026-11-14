package com.kerybotu.derpibooru.mirror.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DetailStatisticFormatTest {
    @Test
    fun unavailableAndSmallCountsRemainExplicit() {
        assertEquals("\u2014", DetailStatisticFormat.count(null))
        assertEquals("\u2014", DetailStatisticFormat.count(-1))
        assertEquals("0", DetailStatisticFormat.count(0))
        assertEquals("999", DetailStatisticFormat.count(999))
    }

    @Test
    fun largeCountsUseCompactLocaleIndependentUnits() {
        assertEquals("1.2K", DetailStatisticFormat.count(1_200))
        assertEquals("12.3K", DetailStatisticFormat.count(12_345))
        assertEquals("1.2M", DetailStatisticFormat.count(1_200_000))
        assertEquals("1.2B", DetailStatisticFormat.count(1_200_000_000))
    }
}
