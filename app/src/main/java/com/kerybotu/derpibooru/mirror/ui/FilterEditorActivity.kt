package com.kerybotu.derpibooru.mirror.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.model.CustomFilterCondition
import com.kerybotu.derpibooru.mirror.model.CustomFilterQueryBuilder
import com.kerybotu.derpibooru.mirror.model.Filter
import com.kerybotu.derpibooru.mirror.model.FilterEditorMapper
import com.kerybotu.derpibooru.mirror.model.FilterJsonCodec
import com.kerybotu.derpibooru.mirror.model.FilterTagLabels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Edits local filters and imported copies; server-owned filters remain read-only. */
class FilterEditorActivity : AppCompatActivity() {
    private lateinit var name: EditText
    private lateinit var description: EditText
    private lateinit var include: EditText
    private lateinit var exclude: EditText
    private lateinit var spoilerComplex: EditText
    private lateinit var hiddenComplex: EditText
    private lateinit var preview: TextView
    private lateinit var spoilerList: LinearLayout
    private lateinit var hiddenList: LinearLayout
    private lateinit var spoilerInput: EditText
    private lateinit var hiddenInput: EditText
    private lateinit var spoilerResults: LinearLayout
    private lateinit var hiddenResults: LinearLayout
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var original: Filter? = null
    private val spoilerIds = mutableListOf<Int>()
    private val hiddenIds = mutableListOf<Int>()
    private val tagNames = mutableMapOf<Int, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PaletteManager.apply(this)
        original = intent.getStringExtra(EXTRA_FILTER_JSON)
            ?.let { runCatching { FilterJsonCodec.parseResponse(it) }.getOrNull() }
        original?.let {
            spoilerIds.addAll(it.spoileredTagIds.distinct())
            hiddenIds.addAll(it.hiddenTagIds.distinct())
        }

        val colors = PaletteManager.colors(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(32))
        }
        root.setBackgroundColor(colors.surface)
        root.addView(TextView(this).apply {
            text = if (original?.isLocal == true) "编辑本地过滤器" else "自定义过滤器副本"
            textSize = 23f
            setTextColor(colors.onSurface)
            setPadding(0, 0, 0, dp(12))
        })
        if (original?.sourceId != null) {
            root.addView(helperText("更改只保存在本地副本，不会写回服务器；通过来源 ID 启用时，服务器仍使用原过滤器规则。"))
        }

        val base = original
        name = field("过滤器名称", base?.name.orEmpty())
        description = field("描述", base?.description.orEmpty(), multiline = true)
        root.addView(section("基本信息", "名称和描述保存在本地过滤器副本中。", name, description))

        val savedConditions = base?.localQuery?.let(CustomFilterQueryBuilder::parse).orEmpty()
        include = field("包含标签（逗号或换行分隔）", savedConditions.filterNot { it.excluded }.joinToString("\n") { it.value }, multiline = true)
        exclude = field("排除标签（逗号或换行分隔）", savedConditions.filter { it.excluded }.joinToString("\n") { it.value }, multiline = true)
        preview = TextView(this).apply {
            textSize = 13f
            setTextColor(colors.onSurface)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        Ui2DesignSystem.styleIsland(preview, colors, 14f)
        root.addView(section("本地作品查询", "这些包含/排除条件用于 App 的作品搜索。保存不会自动启用过滤器。", include, exclude, preview))

        spoilerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        spoilerInput = field("搜索剧透标签名称", "")
        spoilerResults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(tagSection("剧透标签", "匹配作品将标记为剧透。标签规则会与复杂剧透规则叠加生效。", spoilerList, spoilerInput, spoilerResults, true))

        spoilerComplex = field("复杂剧透过滤器", base?.spoileredComplex.orEmpty(), multiline = true, minLines = 4)
        root.addView(complexSection("复杂剧透过滤器", "按 Derpibooru 搜索语法输入；与剧透标签共同生效。", spoilerComplex))

        hiddenList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hiddenInput = field("搜索隐藏标签名称", "")
        hiddenResults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(tagSection("隐藏标签", "匹配作品将在站点中隐藏。标签规则会与复杂隐藏规则叠加生效。", hiddenList, hiddenInput, hiddenResults, false))

        hiddenComplex = field("复杂隐藏过滤器", base?.hiddenComplex.orEmpty(), multiline = true, minLines = 4)
        root.addView(complexSection("复杂隐藏过滤器", "按 Derpibooru 搜索语法输入；与隐藏标签共同生效。", hiddenComplex))

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updatePreview()
        }
        include.addTextChangedListener(watcher)
        exclude.addTextChangedListener(watcher)
        updatePreview()
        renderTagLists()
        resolveExistingTagNames()

        root.addView(MaterialButton(this).apply {
            text = "保存本地过滤器"
            setOnClickListener { save() }
            layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) }
        })
        val pageRoot = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        setContentView(pageRoot)
        AdaptiveNavigationShell.install(this, pageRoot, AdaptiveNavigationShell.HOME)
    }

    private fun section(title: String, helper: String, vararg children: android.view.View) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            addView(sectionTitle(title))
            addView(helperText(helper))
            children.forEach { child ->
                addView(child, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
            Ui2DesignSystem.styleIsland(this, PaletteManager.colors(this@FilterEditorActivity), 18f)
        }

    private fun tagSection(
        title: String,
        helper: String,
        list: LinearLayout,
        input: EditText,
        results: LinearLayout,
        spoiler: Boolean
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(14))
        addView(sectionTitle(title))
        addView(helperText(helper))
        addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addView(MaterialButton(this@FilterEditorActivity).apply {
            text = "搜索标签"
            setOnClickListener { searchTags(input.text.toString(), results, spoiler) }
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(4) })
        addView(results, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        Ui2DesignSystem.styleIsland(this, PaletteManager.colors(this@FilterEditorActivity), 18f)
    }

    private fun complexSection(title: String, helper: String, input: EditText) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            addView(sectionTitle(title))
            addView(helperText(helper))
            addView(MaterialButton(this@FilterEditorActivity).apply {
                text = "语法说明"
                setOnClickListener { showSyntaxHelp() }
            }, LinearLayout.LayoutParams(-2, dp(40)).apply { topMargin = dp(6) })
            addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
            Ui2DesignSystem.styleIsland(this, PaletteManager.colors(this@FilterEditorActivity), 18f)
        }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 16f
        setTextColor(PaletteManager.colors(this@FilterEditorActivity).onSurface)
    }

    private fun helperText(value: String) = TextView(this).apply {
        text = value
        textSize = 12f
        setTextColor(PaletteManager.colors(this@FilterEditorActivity).muted)
        setPadding(0, dp(3), 0, dp(2))
    }

    private fun field(hint: String, value: String, multiline: Boolean = false, minLines: Int = 2) = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        else InputType.TYPE_CLASS_TEXT
        setSingleLine(!multiline)
        if (multiline) this.minLines = minLines
        minHeight = dp(if (multiline) 88 else 48)
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }

    private fun resolveExistingTagNames() {
        val ids = (spoilerIds + hiddenIds).distinct()
        if (ids.isEmpty()) return
        scope.launch {
            val names = withContext(Dispatchers.IO) { FilterTagRepository.resolve(this@FilterEditorActivity, ids) }
            tagNames.putAll(names)
            renderTagLists()
        }
    }

    private fun searchTags(query: String, results: LinearLayout, spoiler: Boolean) {
        val text = query.trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "请输入标签名称", Toast.LENGTH_SHORT).show()
            return
        }
        results.removeAllViews()
        results.addView(helperText("正在搜索…"))
        scope.launch {
            try {
                val matches = withContext(Dispatchers.IO) { FilterTagRepository.search(this@FilterEditorActivity, text) }
                results.removeAllViews()
                if (matches.isEmpty()) {
                    results.addView(helperText("没有找到标签"))
                } else {
                    matches.take(12).forEach { tag ->
                        results.addView(MaterialButton(this@FilterEditorActivity).apply {
                            this.text = "${tag.name} · ${tag.id}"
                            setOnClickListener {
                                tagNames[tag.id] = tag.name
                                val target = if (spoiler) spoilerIds else hiddenIds
                                if (tag.id !in target) target += tag.id
                                renderTagLists()
                                results.removeAllViews()
                                (if (spoiler) spoilerInput else hiddenInput).setText("")
                            }
                        }, LinearLayout.LayoutParams(-1, dp(44)))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                results.removeAllViews()
                results.addView(helperText("标签搜索失败，请检查网络后重试"))
            }
        }
    }

    private fun renderTagLists() {
        renderTagList(spoilerList, spoilerIds, true)
        renderTagList(hiddenList, hiddenIds, false)
    }

    private fun renderTagList(container: LinearLayout, ids: List<Int>, spoiler: Boolean) {
        container.removeAllViews()
        if (ids.isEmpty()) {
            container.addView(helperText("尚未添加标签"))
            return
        }
        FilterTagLabels.display(ids, tagNames).forEachIndexed { index, label ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(PaletteManager.colors(this@FilterEditorActivity).onSurface)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(MaterialButton(this).apply {
                text = "移除"
                minWidth = dp(64)
                setOnClickListener {
                    (if (spoiler) spoilerIds else hiddenIds).removeAt(index)
                    renderTagLists()
                }
            }, LinearLayout.LayoutParams(-2, dp(40)))
            container.addView(row)
        }
    }

    private fun showSyntaxHelp() {
        AlertDialog.Builder(this)
            .setTitle("Derpibooru 搜索语法")
            .setMessage("复杂规则会按原样保存，不会被拆成标签。示例：(drama || mouthpiece)、score.lte:-50。标签列表与复杂规则会同时生效。")
            .setPositiveButton("打开官方搜索帮助") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://${AppSettings.getTargetDomain(this)}/pages/search_syntax")))
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun save() {
        val title = name.text.toString().trim()
        if (title.isEmpty()) {
            name.error = "请输入名称"
            return
        }
        val newId = -((System.currentTimeMillis() % Int.MAX_VALUE).toInt().coerceAtLeast(1))
        val filter = FilterEditorMapper.apply(
            base = original,
            newLocalId = newId,
            name = title,
            description = description.text.toString().trim(),
            spoileredTagIds = spoilerIds,
            spoileredComplex = spoilerComplex.text.toString(),
            hiddenTagIds = hiddenIds,
            hiddenComplex = hiddenComplex.text.toString(),
            localQuery = buildQuery(),
            defaultSourceDomain = AppSettings.getTargetDomain(this)
        )
        try {
            FilterStore.saveLocalFilter(this, filter)
            Toast.makeText(this, "本地过滤器已保存", Toast.LENGTH_SHORT).show()
            finish()
        } catch (failure: Exception) {
            Toast.makeText(this, "保存失败：${failure.localizedMessage ?: failure.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
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

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object { const val EXTRA_FILTER_JSON = "filter_json" }
}
