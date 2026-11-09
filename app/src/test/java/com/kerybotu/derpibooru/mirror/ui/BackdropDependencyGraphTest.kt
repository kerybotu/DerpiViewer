package com.kerybotu.derpibooru.mirror.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackdropDependencyGraphTest {
    @Test
    fun acceptsSingleNodeAndSingleDirection() {
        val graph = BackdropDependencyGraph()

        assertTrue(graph.register("source"))
        assertTrue(graph.register("consumer"))
        assertTrue(graph.setSource("consumer", "source").accepted)
        assertEquals(setOf("source"), graph.sourcesOf("consumer"))
    }

    @Test
    fun acceptsLongAcyclicChain() {
        val graph = graphOf("a", "b", "c", "d")

        assertTrue(graph.setSource("a", "b").accepted)
        assertTrue(graph.setSource("b", "c").accepted)
        assertTrue(graph.setSource("c", "d").accepted)
    }

    @Test
    fun rejectsSelfReferenceAndSourceContainingConsumer() {
        val graph = graphOf("owner", "parent")

        assertFalse(graph.setSource("owner", "owner").accepted)
        assertFalse(graph.setSource("owner", "parent", sourceContainsConsumer = true).accepted)
    }

    @Test
    fun rejectsTwoNodeAndThreeNodeCyclesWithDependencyChain() {
        val two = graphOf("a", "b")
        assertTrue(two.setSource("a", "b").accepted)
        val twoResult = two.setSource("b", "a")
        assertFalse(twoResult.accepted)
        assertEquals(listOf("b", "a", "b"), twoResult.chain)

        val three = graphOf("a", "b", "c")
        assertTrue(three.setSource("a", "b").accepted)
        assertTrue(three.setSource("b", "c").accepted)
        val threeResult = three.setSource("c", "a")
        assertFalse(threeResult.accepted)
        assertEquals(listOf("c", "a", "b", "c"), threeResult.chain)
    }

    @Test
    fun rejectsIndirectCycleAndKeepsExistingEdges() {
        val graph = graphOf("a", "b", "c")
        assertTrue(graph.setSource("a", "b").accepted)
        assertTrue(graph.setSource("b", "c").accepted)

        val result = graph.setSource("c", "a")

        assertFalse(result.accepted)
        assertEquals(setOf("b"), graph.sourcesOf("a"))
        assertEquals(setOf("c"), graph.sourcesOf("b"))
        assertEquals(emptySet<String>(), graph.sourcesOf("c"))
    }

    @Test
    fun replacingAndRemovingEdgeRestoresLegalTopology() {
        val graph = graphOf("a", "b", "c")
        assertTrue(graph.setSource("a", "b").accepted)
        assertTrue(graph.setSource("a", "c").accepted)
        assertEquals(setOf("c"), graph.sourcesOf("a"))

        graph.removeSource("a")

        assertTrue(graph.setSource("c", "a").accepted)
        assertEquals(setOf("a"), graph.sourcesOf("c"))
    }

    @Test
    fun detachClearsIncomingAndOutgoingEdges() {
        val graph = graphOf("a", "b", "c")
        assertTrue(graph.setSource("a", "b").accepted)
        assertTrue(graph.setSource("c", "a").accepted)

        graph.detach("a")

        assertEquals(emptySet<String>(), graph.sourcesOf("c"))
        assertEquals(emptySet<String>(), graph.sourcesOf("a"))
        assertFalse(graph.setSource("c", "a").accepted)
    }

    @Test
    fun duplicateRegistrationAndRemovalAreIdempotent() {
        val graph = BackdropDependencyGraph()
        assertTrue(graph.register("a"))
        assertTrue(graph.register("b"))
        assertFalse(graph.register("a"))
        assertTrue(graph.setSource("a", "a").rejectedReason != null)
        assertTrue(graph.setSource("a", "b").accepted)
        assertTrue(graph.removeSource("a"))
        assertFalse(graph.removeSource("a"))
    }

    private fun graphOf(vararg nodes: String): BackdropDependencyGraph {
        return BackdropDependencyGraph().also { graph -> nodes.forEach { graph.register(it) } }
    }
}
