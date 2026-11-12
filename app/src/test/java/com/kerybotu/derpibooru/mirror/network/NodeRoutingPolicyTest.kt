package com.kerybotu.derpibooru.mirror.network

import org.junit.Assert.*
import org.junit.Test

class NodeRoutingPolicyTest {
    @Test fun freshnessUsesNewIntervalAndRejectsClockRollback() {
        val hour = 3_600_000L
        assertTrue(NodeRoutingPolicy.isFresh(hour, 6 * hour, 6 * hour))
        assertFalse(NodeRoutingPolicy.isFresh(hour, 7 * hour, 6 * hour))
        assertFalse(NodeRoutingPolicy.isFresh(hour, 6 * hour, 3 * hour))
        assertFalse(NodeRoutingPolicy.isFresh(hour, hour - 1, 6 * hour))
        assertFalse(NodeRoutingPolicy.isFresh(0, hour, 6 * hour))
    }

    @Test fun sustainedFailureSingleFlightAndCooldown() {
        val policy = NodeRoutingPolicy()
        assertFalse(policy.observe("site", true, false, 0))
        assertFalse(policy.observe("site", true, false, 1))
        assertTrue(policy.observe("site", true, false, 2))
        repeat(10) { assertFalse(policy.observe("site", true, false, 400_000)) }
        policy.finished()
        assertFalse(policy.observe("site", true, false, 100))
        assertTrue(policy.observe("site", true, false, 400_001))
    }

    @Test fun singleSlowCallDoesNotSwitchAndHealthyCallsResetStreaks() {
        val policy = NodeRoutingPolicy()
        assertFalse(policy.observe("site", false, true, 0))
        assertFalse(policy.observe("site", false, false, 1))
        repeat(3) { assertFalse(policy.observe("site", false, true, 2L + it)) }
        assertTrue(policy.observe("site", false, true, 5))
    }

    @Test fun hostsDoNotCombineUnrelatedFailures() {
        val policy = NodeRoutingPolicy()
        repeat(2) { assertFalse(policy.observe("site", true, false, it.toLong())) }
        repeat(2) { assertFalse(policy.observe("cdn", true, false, it.toLong())) }
        assertFalse(policy.observe("site", false, false, 3))
        assertFalse(policy.observe("site", true, false, 4))
    }
}
