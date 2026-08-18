package com.kerybotu.derpibooru.mirror.network

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.content.res.ColorStateList

class DeepOptimizeActivity : AppCompatActivity() {
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var apply: Button
    private var result: DeepOptimizeResult? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); setContentView(R.layout.activity_deep_optimize); PaletteManager.apply(this)
        log = findViewById(R.id.deep_optimize_log); scroll = findViewById(R.id.deep_optimize_scroll); apply = findViewById(R.id.deep_optimize_confirm_btn)
        applyPalette()
        apply.isEnabled = false; findViewById<Button>(R.id.deep_optimize_close_btn).setOnClickListener { finish() }; apply.setOnClickListener {
            result?.let { selected ->
                apply.isEnabled = false
                lifecycleScope.launch {
                    AppSettings.setManualIp(this@DeepOptimizeActivity, selected.bestIp)
                    withContext(Dispatchers.IO) { NetworkManager.reinitialize(this@DeepOptimizeActivity, forceRefresh = false) }
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
        lifecycleScope.launch {
            val r = DeepIpOptimizer.run { entry -> launch(Dispatchers.Main) { append(entry) } }
            withContext(Dispatchers.Main) { result = r; apply.isEnabled = r != null; if (r != null) apply.text = "应用 ${r.bestIp}" else append(DeepOptimizeLog.Warn("未找到可用节点，请稍后重试")) }
        }
    }
    private fun applyPalette() {
        val c = PaletteManager.colors(this)
        findViewById<View>(android.R.id.content).setBackgroundColor(c.surface)
        scroll.setBackgroundColor(c.surfaceVariant)
        log.setTextColor(c.onSurface)
        apply.backgroundTintList = ColorStateList.valueOf(c.primary)
        apply.setTextColor(c.onPrimary)
        findViewById<Button>(R.id.deep_optimize_close_btn).backgroundTintList = ColorStateList.valueOf(c.primary)
        findViewById<Button>(R.id.deep_optimize_close_btn).setTextColor(c.onPrimary)
    }
    override fun onResume() { super.onResume(); if (::log.isInitialized) { PaletteManager.apply(this); applyPalette() } }
    private fun append(entry: DeepOptimizeLog) { val s = when (entry) { is DeepOptimizeLog.Info -> entry.message; is DeepOptimizeLog.Success -> "✓ ${entry.message}"; is DeepOptimizeLog.Warn -> "⚠ ${entry.message}"; is DeepOptimizeLog.Progress -> "[${entry.stage}] ${entry.current}/${entry.total}" }; log.append("$s\n"); scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }
}
