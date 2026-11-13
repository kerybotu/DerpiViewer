package com.kerybotu.derpibooru.mirror.ui

import android.graphics.Canvas
import android.graphics.RenderNode
import android.os.Build
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.example.liquidglass.LiquidGlassView
import com.kerybotu.derpibooru.mirror.CrashDiagnostics
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Records safe source display lists before consumer drawing and shares them across glass views. */
internal object BackdropRenderNodeCache {
    private class SourceEntry(source: View) {
        val source = WeakReference(source)
        val node = RenderNode("DerpiBackdropSource-${System.identityHashCode(source)}")
        val consumers = WeakHashMap<LiquidGlassView, Unit>()
        val consumerNodes = WeakHashMap<LiquidGlassView, RenderNode>()
        val consumerListeners = WeakHashMap<LiquidGlassView, WeakReference<View.OnAttachStateChangeListener>>()
        var dirty = true
        var scheduled = false
        var rejected = false

        val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> dirty = true; schedule(this) }
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { dirty = true; schedule(this@SourceEntry) }
            override fun onViewDetachedFromWindow(view: View) {
                node.discardDisplayList()
                consumerNodes.values.forEach(RenderNode::discardDisplayList)
                dirty = true
                consumers.keys.toList().forEach { consumer ->
                    GlassWidgetStyle.onBackdropFrameAvailabilityChanged(consumer, view)
                }
            }
        }
        val scrollListener = android.view.ViewTreeObserver.OnScrollChangedListener {
            dirty = true
            schedule(this)
        }
    }

    private val entries = WeakHashMap<View, SourceEntry>()
    private var recordings = 0
    private var cacheRejects = 0

    fun bind(consumer: LiquidGlassView, source: View): Boolean {
        checkMainThread()
        if (Build.VERSION.SDK_INT < 29) return false
        val entry = entries[source] ?: SourceEntry(source).also { created ->
            entries[source] = created
            source.addOnLayoutChangeListener(created.layoutListener)
            source.addOnAttachStateChangeListener(created.attachListener)
            if (source.viewTreeObserver.isAlive) {
                source.viewTreeObserver.addOnScrollChangedListener(created.scrollListener)
            }
        }
        if (!isSafeSource(source, consumer)) {
            reject(entry, "source_contains_glass_or_consumer")
            return false
        }
        entry.rejected = false
        val alreadyBound = entry.consumers.containsKey(consumer)
        entry.consumers[consumer] = Unit
        if (source is PreparedBackdropContentSource) {
            entry.consumerNodes.getOrPut(consumer) {
                RenderNode("DerpiBackdrop-${System.identityHashCode(source)}-${System.identityHashCode(consumer)}")
            }
        }
        if (entry.consumerListeners[consumer]?.get() == null) {
            val consumerRef = WeakReference(consumer)
            val sourceRef = WeakReference(source)
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    sourceRef.get()?.let(::request)
                }
                override fun onViewDetachedFromWindow(view: View) {
                    val target = consumerRef.get() ?: return
                    unbind(target)
                }
            }
            entry.consumerListeners[consumer] = WeakReference(listener)
            consumer.addOnAttachStateChangeListener(listener)
        }
        if (!alreadyBound || entry.dirty) request(source)
        return true
    }

    fun unbind(consumer: LiquidGlassView) {
        checkMainThread()
        entries.values.forEach { entry ->
            entry.consumers.remove(consumer)
            entry.consumerListeners.remove(consumer)?.get()?.let(consumer::removeOnAttachStateChangeListener)
        }
    }

    fun request(source: View) {
        checkMainThread()
        val entry = entries[source] ?: return
        entry.dirty = true
        schedule(entry)
    }

    fun release(source: View) {
        checkMainThread()
        val entry = entries.remove(source) ?: return
        source.removeOnLayoutChangeListener(entry.layoutListener)
        source.removeOnAttachStateChangeListener(entry.attachListener)
        if (source.viewTreeObserver.isAlive) {
            source.viewTreeObserver.removeOnScrollChangedListener(entry.scrollListener)
        }
        entry.consumerListeners.forEach { (consumer, reference) ->
            reference.get()?.let(consumer::removeOnAttachStateChangeListener)
        }
        entry.consumerListeners.clear()
        entry.consumers.clear()
        entry.consumerNodes.values.forEach(RenderNode::discardDisplayList)
        entry.consumerNodes.clear()
        entry.node.discardDisplayList()
    }

    fun drawPrepared(source: View, canvas: Canvas) {
        drawPrepared(source, null, canvas)
    }

    fun drawPrepared(source: View, consumer: LiquidGlassView?, canvas: Canvas) {
        if (Build.VERSION.SDK_INT < 29) return
        val entry = entries[source] ?: return
        if (entry.rejected) return
        val node = consumer?.let(entry.consumerNodes::get) ?: entry.node
        if (node.hasDisplayList()) {
            // GlassLensRenderer/HardwareBackdropBlur already translates the
            // capture canvas using the current source/consumer screen offset.
            // The prepared node is source-local; applying another matrix here
            // double-translates scrolling content and makes it drift away from
            // the glass island.
            canvas.drawRenderNode(node)
        }
    }

    fun hasPreparedFrame(source: View, consumer: LiquidGlassView): Boolean {
        val entry = entries[source] ?: return false
        if (entry.rejected) return false
        val node = consumer.let(entry.consumerNodes::get) ?: entry.node
        return node.hasDisplayList()
    }

    fun prepareForTest(source: View, consumer: LiquidGlassView? = null): Boolean {
        checkMainThread()
        val entry = entries[source] ?: SourceEntry(source).also { entries[source] = it }
        if (!isSafeSource(source, null)) {
            reject(entry, "source_contains_glass")
            return false
        }
        if (consumer != null && source is PreparedBackdropContentSource) {
            entry.consumers[consumer] = Unit
            entry.consumerNodes.getOrPut(consumer) {
                RenderNode("DerpiBackdropTest-${System.identityHashCode(consumer)}")
            }
        }
        return record(entry)
    }

    fun drawPreparedForTest(source: View, consumer: LiquidGlassView, canvas: Canvas) =
        drawPrepared(source, consumer, canvas)

    fun releaseForTest(source: View) = release(source)

    internal fun statsForTest(): Pair<Int, Int> = recordings to cacheRejects

    private fun schedule(entry: SourceEntry) {
        val source = entry.source.get() ?: return
        if (entry.scheduled || !entry.dirty || !source.isAttachedToWindow) return
        entry.scheduled = true
        source.postOnAnimation {
            entry.scheduled = false
            if (entry.dirty && entry.source.get() === source && source.isAttachedToWindow) record(entry)
        }
    }

    private fun record(entry: SourceEntry): Boolean {
        val source = entry.source.get() ?: return false
        if (!isSafeSource(source, null)) {
            reject(entry, "source_tree_changed_to_include_glass")
            return false
        }
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return false
        val contentSource = source as? PreparedBackdropContentSource
        val consumersWaitingForFrame = entry.consumers.keys.filterNot { hasPreparedFrame(source, it) }
        if (contentSource != null && entry.consumers.isNotEmpty()) {
            entry.consumers.keys.toList().forEach { consumer ->
                val node = entry.consumerNodes.getOrPut(consumer) {
                    RenderNode("DerpiBackdrop-${System.identityHashCode(source)}-${System.identityHashCode(consumer)}")
                }
                recordNode(node, width, height) { canvas -> contentSource.drawPreparedBackdrop(canvas, consumer) }
            }
        } else {
            recordNode(entry.node, width, height) { canvas ->
                if (contentSource != null) contentSource.drawPreparedBackdrop(canvas, null)
                else source.draw(canvas)
            }
        }
        entry.dirty = false
        entry.rejected = false
        recordings++
        entry.consumers.keys.toList().forEach { consumer ->
            if (consumer in consumersWaitingForFrame) {
                GlassWidgetStyle.onBackdropFrameAvailabilityChanged(consumer, source)
            }
            consumer.invalidate()
        }
        return true
    }

    private inline fun recordNode(node: RenderNode, width: Int, height: Int, draw: (Canvas) -> Unit) {
        node.setPosition(0, 0, width, height)
        val canvas = node.beginRecording(width, height)
        try {
            draw(canvas)
        } finally {
            node.endRecording()
        }
    }

    private fun isSafeSource(source: View, consumer: LiquidGlassView?): Boolean {
        var parent = source
        while (parent.parent is View) {
            if (parent === consumer) return false
            parent = parent.parent as View
        }
        fun containsGlass(view: View): Boolean {
            if (view is LiquidGlassView) return true
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) if (containsGlass(view.getChildAt(index))) return true
            }
            return false
        }
        return !containsGlass(source) && source !== consumer
    }

    private fun reject(entry: SourceEntry, reason: String) {
        if (entry.rejected) return
        entry.rejected = true
        entry.dirty = false
        entry.node.discardDisplayList()
        entry.consumerNodes.values.forEach(RenderNode::discardDisplayList)
        entry.consumers.keys.toList().forEach { consumer ->
            GlassWidgetStyle.onBackdropSourceRejected(consumer, entry.source.get())
        }
        cacheRejects++
        CrashDiagnostics.recordEvent(
            "BackdropRenderNode",
            "source_rejected reason=$reason source=${entry.source.get()?.let(::identity) ?: "collected"}"
        )
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Backdrop display lists are main-thread prepared" }
    }

    private fun identity(view: View) = "${view.javaClass.simpleName}@${System.identityHashCode(view)}"
}
