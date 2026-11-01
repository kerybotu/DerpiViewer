package com.kerybotu.derpibooru.mirror.ui

import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.model.CustomFilterCondition
import com.kerybotu.derpibooru.mirror.model.CustomFilterQueryBuilder
import com.kerybotu.derpibooru.mirror.model.Filter
import com.kerybotu.derpibooru.mirror.model.FilterJsonCodec
import com.google.android.material.button.MaterialButton

/** Editor for local search presets. Server filters remain immutable until imported. */
class FilterEditorActivity : AppCompatActivity() {
    private lateinit var name: EditText
    private lateinit var description: EditText
    private lateinit var include: EditText
    private lateinit var exclude: EditText
    private lateinit var preview: TextView
    private var original: Filter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PaletteManager.apply(this)
        original = intent.getStringExtra(EXTRA_FILTER_JSON)?.let { runCatching { FilterJsonCodec.parseResponse(it) }.getOrNull() }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(28)) }
        name = field("过滤器名称", original?.name.orEmpty())
        description = field("描述（可选）", original?.description.orEmpty())
        val savedConditions = original?.localQuery?.let(CustomFilterQueryBuilder::parse).orEmpty()
        include = field("包含标签（逗号或换行分隔）", savedConditions.filterNot { it.excluded }.joinToString("\n") { it.value })
        exclude = field("排除标签（逗号或换行分隔）", savedConditions.filter { it.excluded }.joinToString("\n") { it.value })
        root.addView(TextView(this).apply { text = "本地自定义过滤器"; textSize = 23f })
        listOf(name, description, include, exclude).forEach { root.addView(it) }
        preview = TextView(this).apply {
            textSize = 13f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextColor(PaletteManager.colors(this@FilterEditorActivity).onSurface)
        }
        Ui2DesignSystem.styleIsland(preview, PaletteManager.colors(this), 16f)
        root.addView(TextView(this).apply { text = "生成查询将在保存前去重；带空格的标签会自动加引号。保存后不会自动替换当前过滤器。"; textSize = 12f; setPadding(0, dp(10), 0, dp(10)) })
        root.addView(preview)
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = updatePreview()
        }
        include.addTextChangedListener(watcher)
        exclude.addTextChangedListener(watcher)
        updatePreview()
        root.addView(MaterialButton(this).apply {
            text = "保存"
            setOnClickListener { save() }
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun field(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setSingleLine(false)
        minHeight = dp(52)
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }

    private fun save() {
        val title = name.text.toString().trim()
        if (title.isEmpty()) { name.error = "请输入名称"; return }
        val query = buildQuery()
        if (query.isBlank()) { include.error = "至少填写一个包含或排除标签"; return }
        val base = original
        val id = base?.id?.takeIf { it < 0 } ?: -((System.currentTimeMillis() % Int.MAX_VALUE).toInt().coerceAtLeast(1))
        val filter = (base ?: Filter(id, title, "", null, false, false, 0, 0, isLocal = true)).copy(
            id = id, name = title, description = description.text.toString().trim(), localQuery = query, isLocal = true
        )
        FilterStore.saveLocalFilter(this, filter)
        Toast.makeText(this, "本地过滤器已保存", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun buildQuery(): String {
        val conditions = mutableListOf<CustomFilterCondition>()
        include.text.toString().split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            .forEach { conditions += CustomFilterCondition(it) }
        exclude.text.toString().split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            .forEach { conditions += CustomFilterCondition(it, excluded = true) }
        return CustomFilterQueryBuilder.serialize(conditions)
    }

    private fun updatePreview() {
        if (::preview.isInitialized) preview.text = "查询预览：${buildQuery().ifBlank { "（空）" }}"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object { const val EXTRA_FILTER_JSON = "filter_json" }
}
