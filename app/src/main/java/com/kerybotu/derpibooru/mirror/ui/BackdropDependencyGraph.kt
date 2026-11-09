package com.kerybotu.derpibooru.mirror.ui

/**
 * Small, UI-independent registry for backdrop dependencies.
 *
 * An edge is stored as consumer -> source. The registry is deliberately
 * separate from LiquidGlassView so topology validation can be tested without
 * starting a View hierarchy or invoking synchronous drawing.
 */
internal class BackdropDependencyGraph {
    private val nodes = linkedSetOf<String>()
    private val sources = linkedMapOf<String, String>()

    fun register(node: String): Boolean = nodes.add(node)

    fun setSource(
        consumer: String,
        source: String,
        sourceContainsConsumer: Boolean = false
    ): DependencyDecision {
        if (consumer !in nodes || source !in nodes) {
            return reject(DependencyRejection.UNKNOWN_NODE, listOf(consumer, source))
        }
        if (consumer == source) {
            return reject(DependencyRejection.SELF_REFERENCE, listOf(consumer, source))
        }
        if (sourceContainsConsumer) {
            return reject(DependencyRejection.SOURCE_CONTAINS_CONSUMER, listOf(consumer, source))
        }

        val previous = sources[consumer]
        if (previous == source) return DependencyDecision.accepted()

        val pathFromSource = pathTo(source, consumer)
        if (pathFromSource != null) {
            return reject(
                DependencyRejection.CYCLE,
                listOf(consumer) + pathFromSource
            )
        }

        sources[consumer] = source
        return DependencyDecision.accepted()
    }

    fun removeSource(consumer: String): Boolean = sources.remove(consumer) != null

    /** Removes the node and every edge touching it, as required on detach. */
    fun detach(node: String): Boolean {
        val removedNode = nodes.remove(node)
        val removedOutgoing = sources.remove(node) != null
        val removedIncoming = sources.entries.removeIf { it.value == node }
        val removedEdges = removedOutgoing || removedIncoming
        return removedNode || removedEdges
    }

    fun sourcesOf(consumer: String): Set<String> =
        sources[consumer]?.let(::setOf) ?: emptySet()

    fun contains(node: String): Boolean = node in nodes

    private fun pathTo(start: String, target: String): List<String>? {
        val path = ArrayList<String>()
        val visited = HashSet<String>()

        fun visit(node: String): Boolean {
            if (!visited.add(node)) return false
            path += node
            if (node == target) return true
            val next = sources[node]
            if (next != null && visit(next)) return true
            path.removeAt(path.lastIndex)
            return false
        }

        return if (visit(start)) path.toList() else null
    }

    private fun reject(reason: DependencyRejection, chain: List<String>) =
        DependencyDecision(accepted = false, rejectedReason = reason, chain = chain)
}

internal enum class DependencyRejection {
    UNKNOWN_NODE,
    SELF_REFERENCE,
    SOURCE_CONTAINS_CONSUMER,
    CYCLE
}

internal data class DependencyDecision(
    val accepted: Boolean,
    val rejectedReason: DependencyRejection? = null,
    val chain: List<String> = emptyList()
) {
    companion object {
        fun accepted() = DependencyDecision(accepted = true)
    }
}
