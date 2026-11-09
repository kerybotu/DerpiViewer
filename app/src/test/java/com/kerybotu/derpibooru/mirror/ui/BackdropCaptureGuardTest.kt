package com.kerybotu.derpibooru.mirror.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackdropCaptureGuardTest {
    private data class Node(var capturingBackdrop: Boolean)

    @Test
    fun suppressesEveryGlassNodeForTheCaptureAndRestoresPriorState() {
        val nodes = listOf(Node(false), Node(true), Node(false))

        BackdropCaptureGuard.withSuppressed(
            nodes = nodes,
            isSuppressed = Node::capturingBackdrop,
            setSuppressed = { node, value -> node.capturingBackdrop = value }
        ) {
            assertTrue(nodes.all(Node::capturingBackdrop))
        }

        assertEquals(listOf(false, true, false), nodes.map(Node::capturingBackdrop))
    }

    @Test
    fun restoresEveryGlassNodeWhenCaptureThrows() {
        val nodes = listOf(Node(false), Node(true))

        runCatching {
            BackdropCaptureGuard.withSuppressed(
                nodes = nodes,
                isSuppressed = Node::capturingBackdrop,
                setSuppressed = { node, value -> node.capturingBackdrop = value }
            ) {
                error("capture failed")
            }
        }

        assertEquals(listOf(false, true), nodes.map(Node::capturingBackdrop))
    }
}
