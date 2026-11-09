package com.kerybotu.derpibooru.mirror.ui

import android.view.View
import com.example.liquidglass.LiquidGlassView
import com.kerybotu.derpibooru.mirror.CrashDiagnostics
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Compatibility facade for former live View captures. Sources now contribute only a
 * resolved solid tint; the source View is never synchronously drawn or retained by consumers.
 */
internal class PreparedBackdropSource private constructor(private val source: View) : AutoCloseable {
    private val consumers = WeakHashMap<LiquidGlassView, Unit>()
    private val attachListeners = WeakHashMap<LiquidGlassView, View.OnAttachStateChangeListener>()
    private var closed = false
    private val sourceAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) = close()
    }

    init {
        source.addOnAttachStateChangeListener(sourceAttachListener)
    }

    fun bind(consumer: LiquidGlassView) {
        checkMainThread()
        if (closed) return
        consumers[consumer] = Unit
        if (!attachListeners.containsKey(consumer)) {
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = bind(consumer)
                override fun onViewDetachedFromWindow(view: View) = unbind(consumer)
            }
            attachListeners[consumer] = listener
            consumer.addOnAttachStateChangeListener(listener)
        }
        GlassWidgetStyle.setRenderingActive(
            consumer,
            active = true,
            source = source,
            sourceMode = BackdropSourceMode.EXPLICIT_SOURCE
        )
        CrashDiagnostics.recordEvent(
            "BackdropSource",
            "static_bind consumer=${identity(consumer)} source=${identity(source)} consumers=${consumers.size}"
        )
    }

    fun invalidate() {
        checkMainThread()
        if (closed) return
        consumers.keys.toList().forEach(::bind)
    }

    fun unbind(consumer: LiquidGlassView) {
        checkMainThread()
        consumers.remove(consumer)
        attachListeners.remove(consumer)?.let(consumer::removeOnAttachStateChangeListener)
        if (consumers.isEmpty()) close()
    }

    override fun close() {
        checkMainThread()
        if (closed) return
        closed = true
        source.removeOnAttachStateChangeListener(sourceAttachListener)
        attachListeners.forEach { (consumer, listener) -> consumer.removeOnAttachStateChangeListener(listener) }
        attachListeners.clear()
        consumers.clear()
        synchronized(registry) {
            if (registry[source]?.get() === this) registry.remove(source)
        }
        CrashDiagnostics.recordEvent("BackdropSource", "closed source=${identity(source)}")
    }

    private fun checkMainThread() {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            "Backdrop source configuration is main-thread confined"
        }
    }

    companion object {
        private val registry = WeakHashMap<View, WeakReference<PreparedBackdropSource>>()

        fun bind(consumer: LiquidGlassView, source: View): PreparedBackdropSource {
            val prepared = synchronized(registry) {
                registry[source]?.get() ?: PreparedBackdropSource(source).also {
                    registry[source] = WeakReference(it)
                }
            }
            prepared.bind(consumer)
            return prepared
        }

        fun release(source: View) {
        BackdropRenderNodeCache.release(source)
            registry[source]?.get()?.close()
        }

        fun unbind(consumer: LiquidGlassView) {
        BackdropRenderNodeCache.unbind(consumer)
            registry.values.mapNotNull { it.get() }.forEach { it.unbind(consumer) }
        }

        fun invalidateSource(source: View) {
        BackdropRenderNodeCache.request(source)
            registry[source]?.get()?.invalidate()
        }

        private fun identity(view: View) = "${view.javaClass.simpleName}@${System.identityHashCode(view)}"
    }
}
