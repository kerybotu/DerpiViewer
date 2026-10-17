package com.kerybotu.derpibooru.mirror.network

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ChallengeSessionGateTest {
    private val site = "https://derpibooru.org:443"

    @Test
    fun concurrentAndLateResponsesShareOneVerification() = runBlocking {
        withTimeout(5_000) {
            val gate = ChallengeSessionGate()
            val version = gate.version(site)
            val launches = mutableListOf<Long>()
            val requests = List(8) {
                async(start = CoroutineStart.UNDISPATCHED) {
                    gate.awaitResolved(site, version) { launches.add(it) }
                }
            }
            assertEquals(1, launches.size)
            gate.complete(launches.single(), true)
            requests.forEach { assertTrue(it.await()) }

            // This HTTP request left before verification but its challenge response
            // arrived after the first screen closed. It must retry without a screen.
            assertTrue(gate.awaitResolved(site, version) { fail("Reopened verification for a stale response") })
            assertEquals(version + 1, gate.version(site))
        }
    }

    @Test
    fun freshChallengeStillOpensAndOldActivityCannotCompleteIt() = runBlocking {
        withTimeout(5_000) {
            val gate = ChallengeSessionGate()
            var firstId = -1L
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                gate.awaitResolved(site, gate.version(site)) { firstId = it }
            }
            gate.complete(firstId, true)
            assertTrue(first.await())

            var secondId = -1L
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                gate.awaitResolved(site, gate.version(site)) { secondId = it }
            }
            assertNotEquals(firstId, secondId)
            gate.complete(firstId, false)
            assertFalse(second.isCompleted)
            gate.complete(secondId, true)
            assertTrue(second.await())
        }
    }

    @Test
    fun cancellationAlsoReleasesLateRequestsWithoutReopening() = runBlocking {
        withTimeout(5_000) {
            val gate = ChallengeSessionGate()
            val version = gate.version(site)
            var sessionId = -1L
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                gate.awaitResolved(site, version) { sessionId = it }
            }
            gate.complete(sessionId, false)
            assertFalse(request.await())
            assertFalse(gate.awaitResolved(site, version) { fail("Cancelled batch reopened verification") })
        }
    }

    @Test
    fun differentOriginsWaitTheirTurnAndDoNotShareClearance() = runBlocking {
        withTimeout(5_000) {
            val gate = ChallengeSessionGate()
            var firstId = -1L
            var secondId = -1L
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                gate.awaitResolved(site, 0) { firstId = it }
            }
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                gate.awaitResolved("https://trixiebooru.org:443", 0) { secondId = it }
            }
            assertEquals(-1L, secondId)
            gate.complete(firstId, true)
            assertTrue(first.await())
            // Let the second waiter resume from the first site's result.
            kotlinx.coroutines.yield()
            assertTrue(secondId > firstId)
            assertFalse(second.isCompleted)
            gate.complete(secondId, false)
            assertFalse(second.await())
        }
    }

    @Test
    fun failedWindowLaunchDoesNotLeaveAnUnresolvableSession() = runBlocking {
        withTimeout(5_000) {
            val gate = ChallengeSessionGate()
            try {
                gate.awaitResolved(site, 0) { throw IllegalStateException("Cannot launch") }
                fail("Expected launch failure")
            } catch (_: IllegalStateException) {
                // A new request must be able to open a replacement window.
            }
            var sessionId = -1L
            val next = async(start = CoroutineStart.UNDISPATCHED) {
                gate.awaitResolved(site, gate.version(site)) { sessionId = it }
            }
            assertTrue(sessionId > 0)
            gate.complete(sessionId, true)
            assertTrue(next.await())
        }
    }
}
