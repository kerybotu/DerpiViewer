package com.kerybotu.derpibooru.mirror.ui

import org.junit.Assert.*
import org.junit.Test

class PullRefreshStateTest {
    @Test fun incompletePullReturnsToIdleWithoutRefreshing() {
        val state = PullRefreshState(80f)
        state.pull(40f)
        assertEquals(0.5f, state.progress, 0f)
        assertFalse(state.release())
        assertEquals(0f, state.distance, 0f)
        assertFalse(state.isRefreshing)
    }

    @Test fun reachingThresholdWaitsForReleaseAndRefreshesOnlyOnce() {
        val state = PullRefreshState(80f)
        state.pull(80f)
        assertEquals(1f, state.progress, 0f)
        assertFalse(state.isRefreshing)
        assertTrue(state.release())
        assertTrue(state.isRefreshing)
        assertFalse(state.release())
        state.pull(200f)
        assertEquals(80f, state.distance, 0f)
    }

    @Test fun retreatingBeforeReleaseDisarmsRefresh() {
        val state = PullRefreshState(80f)
        state.pull(100f)
        state.pull(79f)
        assertFalse(state.release())
        assertFalse(state.isRefreshing)
    }

    @Test fun cancelledGestureNeverRefreshesEvenBeyondThreshold() {
        val state = PullRefreshState(80f)
        state.pull(120f)
        assertFalse(state.release(cancelled = true))
        assertEquals(0f, state.distance, 0f)
        assertFalse(state.isRefreshing)
    }

    @Test fun completingRefreshAllowsAnotherGesture() {
        val state = PullRefreshState(80f)
        state.setRefreshing(true)
        assertFalse(state.release())
        state.setRefreshing(false)
        assertEquals(0f, state.progress, 0f)
        state.pull(85f)
        assertTrue(state.release())
    }

    @Test fun pullDistanceAndRevealStayBounded() {
        val state = PullRefreshState(80f)
        state.pull(1000f)
        assertEquals(120f, state.distance, 0f)
        assertEquals(1f, state.progress, 0f)
        state.pull(-10f)
        assertEquals(0f, state.distance, 0f)
        assertEquals(0f, state.progress, 0f)
    }
}
