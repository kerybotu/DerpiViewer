package com.kerybotu.derpibooru.mirror.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.liquidglass.LiquidGlassView
import com.kerybotu.derpibooru.mirror.CrashDiagnostics

/** Isolated RenderNode topology page for API 36 diagnostics; not linked from product navigation. */
class RenderIsolationActivity : AppCompatActivity() {
    private lateinit var host: LinearLayout
    private var glassViews = emptyList<LiquidGlassView>()
    private val experiment by lazy { RenderCrashExperiment.from(this) }
    private val topology by lazy { intent.getStringExtra(EXTRA_TOPOLOGY) ?: "siblings" }
    private val traceDrawChain by lazy { intent.getBooleanExtra(EXTRA_TRACE_DRAW_CHAIN, false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashDiagnostics.activityEvent(this, "render_isolation_open experiment=${experiment.id} topology=$topology")
        host = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            setBackgroundColor(Color.rgb(24, 29, 35))
        }
        setContentView(host)
        buildTopology()
        host.addView(TextView(this).apply {
            text = "Experiment ${experiment.id} / $topology"
            setTextColor(Color.WHITE)
        })
        host.addView(TextView(this).apply {
            text = "Close and reopen this page to exercise detach and attach."
            setTextColor(Color.LTGRAY)
        })
    }

    override fun onResume() {
        super.onResume()
        CrashDiagnostics.activityEvent(this, "render_isolation_resumed experiment=${experiment.id} topology=$topology")
    }

    override fun onDestroy() {
        CrashDiagnostics.activityEvent(this, "render_isolation_destroyed topology=$topology")
        super.onDestroy()
    }

    private fun buildTopology() {
        if (!experiment.usesGlassRenderer()) {
            addSurface(FrameLayout(this).apply { setBackgroundColor(Color.rgb(68, 100, 120)) }, "plain-1")
            if (experiment == RenderCrashExperiment.I_MULTIPLE_GLASS) {
                addSurface(FrameLayout(this).apply { setBackgroundColor(Color.rgb(91, 117, 83)) }, "plain-2")
            }
            CrashDiagnostics.recordEvent("RenderIsolation", "topology=$topology surfaces=plain")
            return
        }

        when (topology) {
            "none" -> {
                glassViews = listOf(newGlass("no-backdrop"))
                glassViews.forEach { it.setCustomBackdropCapture { null } }
                addSurface(glassViews.single(), "no-backdrop")
            }
            "static" -> {
                glassViews = listOf(newGlass("static-backdrop"))
                glassViews.forEach { glass ->
                    glass.setCustomBackdropCapture {
                        Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
                            eraseColor(Color.rgb(55, 66, 78))
                        }
                    }
                }
                addSurface(glassViews.single(), "static-backdrop")
            }
            "two-none" -> {
                glassViews = listOf(newGlass("glass-1"), newGlass("glass-2"))
                glassViews.forEachIndexed { index, glass ->
                    glass.setCustomBackdropCapture { null }
                    addSurface(glass, "glass-${index + 1}")
                }
            }
            "three-none" -> {
                glassViews = listOf(newGlass("glass-1"), newGlass("glass-2"), newGlass("glass-3"))
                glassViews.forEachIndexed { index, glass ->
                    glass.setCustomBackdropCapture { null }
                    addSurface(glass, "glass-${index + 1}")
                }
            }
            "two-siblings" -> {
                glassViews = listOf(newGlass("sibling-1"), newGlass("sibling-2"))
                glassViews.forEachIndexed { index, glass -> addSurface(glass, "sibling-${index + 1}") }
            }
            "independent" -> {
                val sources = listOf(
                    View(this).apply { setBackgroundColor(Color.rgb(50, 75, 95)) },
                    View(this).apply { setBackgroundColor(Color.rgb(80, 70, 55)) }
                )
                sources.forEachIndexed { index, source -> addSurface(source, "source-${index + 1}") }
                glassViews = sources.mapIndexed { index, source ->
                    newGlass("glass-${index + 1}").apply { backdropSource = source }
                }
                glassViews.forEachIndexed { index, glass -> addSurface(glass, "glass-${index + 1}") }
            }
            "shared" -> {
                val source = View(this).apply { setBackgroundColor(Color.rgb(65, 78, 90)) }
                addSurface(source, "shared-static-source")
                glassViews = listOf(newGlass("glass-1"), newGlass("glass-2"))
                    .onEach { it.backdropSource = source }
                glassViews.forEachIndexed { index, glass -> addSurface(glass, "glass-${index + 1}") }
            }
            "peer" -> {
                val first = newGlass("glass-a")
                val second = newGlass("glass-b")
                first.backdropSource = second
                second.backdropSource = first
                glassViews = listOf(first, second)
                addSurface(first, "glass-a")
                addSurface(second, "glass-b")
            }
            "common-parent" -> {
                val parent = FrameLayout(this).apply { setBackgroundColor(Color.rgb(55, 68, 78)) }
                glassViews = listOf(newGlass("glass-1"), newGlass("glass-2"))
                glassViews.forEach { glass ->
                    glass.backdropSource = parent
                    parent.addView(glass, FrameLayout.LayoutParams(-1, dp(80)))
                }
                addSurface(parent, "common-parent")
            }
            "product" -> {
                val content = FrameLayout(this).apply { setBackgroundColor(Color.rgb(55, 68, 78)) }
                glassViews = listOf(newGlass("top-island"), newGlass("second-island"), newGlass("card-overlay"))
                glassViews[0].backdropSource = content
                glassViews[1].backdropSource = content
                glassViews[2].backdropSource = content
                addSurface(content, "product-backdrop-source")
                glassViews.forEachIndexed { index, glass -> addSurface(glass, "product-glass-${index + 1}") }
            }
            "rapid-update" -> {
                val sources = List(2) { index ->
                    View(this).apply { setBackgroundColor(if (index == 0) Color.DKGRAY else Color.GRAY) }
                }
                sources.forEachIndexed { index, source -> addSurface(source, "source-${index + 1}") }
                glassViews = listOf(newGlass("glass-1"), newGlass("glass-2"))
                glassViews.forEachIndexed { index, glass ->
                    glass.backdropSource = sources[index]
                    addSurface(glass, "glass-${index + 1}")
                }
                repeat(20) { index ->
                    host.postDelayed({
                        glassViews.forEachIndexed { glassIndex, glass ->
                            glass.backdropSource = sources[(index + glassIndex) % sources.size]
                        }
                        CrashDiagnostics.recordEvent("RenderIsolation", "rapid_backdrop_update=$index")
                    }, index * 100L)
                }
            }
            "reattach" -> {
                glassViews = listOf(newGlass("glass-1"), newGlass("glass-2"))
                glassViews.forEachIndexed { index, glass -> addSurface(glass, "glass-${index + 1}") }
                host.postDelayed({
                    glassViews.forEach { glass ->
                        (glass.parent as? ViewGroup)?.removeView(glass)
                        host.addView(glass, 0, LinearLayout.LayoutParams(-1, dp(112)))
                    }
                    CrashDiagnostics.recordEvent("RenderIsolation", "glass_pair_reattached")
                }, 1500L)
            }
            else -> when {
            experiment == RenderCrashExperiment.H_SINGLE_GLASS -> {
                glassViews = listOf(newGlass("single"))
                addSurface(glassViews.single(), "single")
            }
            experiment == RenderCrashExperiment.I_MULTIPLE_GLASS && topology == "parent" -> {
                val parent = newGlass("parent")
                val child = newGlass("child")
                parent.addView(child, LinearLayout.LayoutParams(-1, dp(96)))
                glassViews = listOf(parent, child)
                addSurface(parent, "parent-child")
            }
            experiment == RenderCrashExperiment.I_MULTIPLE_GLASS && topology == "backdrop" -> {
                val source = View(this).apply { setBackgroundColor(Color.rgb(68, 100, 120)) }
                addSurface(source, "static-source")
                val first = newGlass("source-glass")
                val second = newGlass("consumer-glass").apply {
                    backdropSource = first
                    enableDynamicBackground = false
                }
                glassViews = listOf(first, second)
                addSurface(first, "glass-source")
                addSurface(second, "glass-consumer")
            }
            experiment == RenderCrashExperiment.I_MULTIPLE_GLASS && topology == "one-way" -> {
                val first = newGlass("source-glass").apply {
                    setCustomBackdropCapture { null }
                }
                val second = newGlass("consumer-glass").apply {
                    backdropSource = first
                    enableDynamicBackground = false
                }
                glassViews = listOf(first, second)
                addSurface(first, "glass-source-no-capture")
                addSurface(second, "consumer-captures-source")
            }
            experiment == RenderCrashExperiment.I_MULTIPLE_GLASS -> {
                glassViews = listOf(newGlass("sibling-1"), newGlass("sibling-2"), newGlass("sibling-3"))
                glassViews.forEachIndexed { index, glass -> addSurface(glass, "sibling-${index + 1}") }
            }
            else -> {
                glassViews = listOf(newGlass("surface-1"), newGlass("surface-2"))
                glassViews.forEachIndexed { index, glass -> addSurface(glass, "surface-${index + 1}") }
            }
            }
        }
        CrashDiagnostics.recordEvent("RenderIsolation", "topology=$topology glass_count=${glassViews.size} experiment=${experiment.id}")
        glassViews.forEachIndexed { index, glass ->
            val source = glass.backdropSource ?: glass.parent as? View
            CrashDiagnostics.recordEvent(
                "RenderIsolation",
                "edge glass-${index + 1}@${System.identityHashCode(glass)}->${source?.javaClass?.simpleName ?: "none"}@${source?.let { System.identityHashCode(it) } ?: 0}"
            )
        }
    }

    private fun newGlass(label: String): LiquidGlassView {
        val view = if (experiment == RenderCrashExperiment.K_BYPASS_GLASS_RENDERING) {
            NoCustomGlassDrawView(this)
        } else if (traceDrawChain) {
            TracingGlassView(this, label)
        } else {
            LiquidGlassView(this)
        }
        if (experiment == RenderCrashExperiment.L_SOURCE_TREE_GUARD) {
            GlassWidgetStyle.apply(view, 18f)
        } else {
            view.cornerRadius = dp(18).toFloat()
        }
        view.glassTint = Color.argb(80, 170, 195, 210)
        view.setBackgroundColor(Color.TRANSPARENT)
        when (experiment) {
            RenderCrashExperiment.F_NO_BACKDROP,
            RenderCrashExperiment.H_SINGLE_GLASS -> view.setCustomBackdropCapture { null }
            RenderCrashExperiment.J_STATIC_BACKDROP -> view.setCustomBackdropCapture {
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.rgb(55, 66, 78))
                }
            }
            else -> Unit
        }
        view.addView(TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -1))
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                CrashDiagnostics.recordEvent("RenderIsolation", "attach $label")
            }
            override fun onViewDetachedFromWindow(v: View) {
                CrashDiagnostics.recordEvent("RenderIsolation", "detach $label")
            }
        })
        return view
    }

    private fun addSurface(view: View, label: String) {
        val params = LinearLayout.LayoutParams(-1, dp(112)).apply { bottomMargin = dp(14) }
        host.addView(view, params)
        CrashDiagnostics.recordEvent("RenderIsolation", "added $label class=${view.javaClass.simpleName}")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class NoCustomGlassDrawView(context: android.content.Context) : LiquidGlassView(context) {
        override fun onDraw(canvas: android.graphics.Canvas) {
            // View.draw still renders the normal background and dispatches children after onDraw.
        }
    }

    private class TracingGlassView(
        context: android.content.Context,
        private val label: String
    ) : LiquidGlassView(context) {
        override fun draw(canvas: Canvas) {
            val chain = drawChain.get() ?: mutableListOf<String>().also(drawChain::set)
            val id = label
            val repeated = id in chain
            if (chain.isEmpty() || repeated) {
                CrashDiagnostics.recordEvent(
                    "GlassDrawTrace",
                    "draw depth=${chain.size} repeated=$repeated chain=${(chain + id).joinToString("/")}"
                )
            }
            chain += id
            try {
                super.draw(canvas)
            } finally {
                chain.removeAt(chain.lastIndex)
                if (chain.isEmpty()) drawChain.remove()
            }
        }

        private companion object {
            val drawChain = ThreadLocal<MutableList<String>>()
        }
    }

    companion object {
        const val EXTRA_TOPOLOGY = "render_topology"
        const val EXTRA_TRACE_DRAW_CHAIN = "trace_draw_chain"
    }
}
