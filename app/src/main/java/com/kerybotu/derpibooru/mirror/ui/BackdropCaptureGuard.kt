package com.kerybotu.derpibooru.mirror.ui

import android.view.View
import com.example.liquidglass.LiquidGlassView
import com.kerybotu.derpibooru.mirror.CrashDiagnostics

internal object BackdropCaptureGuard {
    fun <T, R> withSuppressed(
        nodes: Iterable<T>,
        isSuppressed: (T) -> Boolean,
        setSuppressed: (T, Boolean) -> Unit,
        draw: () -> R
    ): R {
        val originalStates = nodes.map { it to isSuppressed(it) }
        var changedCount = 0
        try {
            originalStates.forEach { (node, _) ->
                setSuppressed(node, true)
                changedCount++
            }
            return draw()
        } finally {
            originalStates.take(changedCount).asReversed().forEach { (node, wasSuppressed) ->
                setSuppressed(node, wasSuppressed)
            }
        }
    }
}

internal object BackdropCaptureTrace {
    private val stack = ThreadLocal<MutableList<String>>()

    fun begin(owner: LiquidGlassView, source: View): Int {
        val chain = stack.get() ?: mutableListOf<String>().also(stack::set)
        val ownerId = identity(owner)
        val sourceId = identity(source)
        val cycleAt = chain.indexOf(ownerId)
        val depth = chain.size
        if (depth == 0 || cycleAt >= 0) {
            val path = (chain + ownerId).joinToString("->")
            CrashDiagnostics.recordEvent(
                "BackdropCapture",
                "begin depth=$depth owner=$ownerId source=$sourceId cycle=${cycleAt >= 0} chain=$path"
            )
        }
        chain += ownerId
        return depth
    }

    fun end(depth: Int) {
        val chain = stack.get() ?: return
        if (depth < chain.size) {
            while (chain.size > depth) chain.removeAt(chain.lastIndex)
        }
        if (chain.isEmpty()) stack.remove()
    }

    private fun identity(view: View): String =
        "${view.javaClass.simpleName}@${System.identityHashCode(view)}"
}
