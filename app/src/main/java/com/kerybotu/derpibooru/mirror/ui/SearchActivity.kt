package com.kerybotu.derpibooru.mirror.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.*
import android.graphics.Color
import android.content.res.ColorStateList
import android.text.InputType
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.R
import com.kerybotu.derpibooru.mirror.auth.ApiKeyStore
import com.kerybotu.derpibooru.mirror.model.Image
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import com.kerybotu.derpibooru.mirror.dict.TagDictionary
import com.kerybotu.derpibooru.mirror.dict.TagEntry
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URLEncoder

private enum class SearchFieldType { NUMERIC, DATE, LITERAL, BOOLEAN }
private data class SearchFieldDef(val key: String, val label: String, val type: SearchFieldType)

private val SEARCH_FIELDS = listOf(
    SearchFieldDef("score", "评分", SearchFieldType.NUMERIC), SearchFieldDef("created_at", "创建时间", SearchFieldType.DATE),
    SearchFieldDef("id", "图片 ID", SearchFieldType.NUMERIC), SearchFieldDef("faves", "收藏数", SearchFieldType.NUMERIC),
    SearchFieldDef("upvotes", "点赞数", SearchFieldType.NUMERIC), SearchFieldDef("downvotes", "点踩数", SearchFieldType.NUMERIC),
    SearchFieldDef("comment_count", "评论数", SearchFieldType.NUMERIC), SearchFieldDef("uploader", "上传者", SearchFieldType.LITERAL),
    SearchFieldDef("mime_type", "MIME 类型", SearchFieldType.LITERAL), SearchFieldDef("animated", "是否动图", SearchFieldType.BOOLEAN),
    SearchFieldDef("width", "宽度", SearchFieldType.NUMERIC), SearchFieldDef("height", "高度", SearchFieldType.NUMERIC),
    SearchFieldDef("duration", "时长（秒）", SearchFieldType.NUMERIC), SearchFieldDef("size", "文件大小", SearchFieldType.NUMERIC)
)

private val SORT_FIELDS = listOf(
    "first_seen_at" to "首次收录时间", "id" to "图片 ID", "updated_at" to "最后修改时间", "faves" to "收藏数",
    "upvotes" to "点赞数", "downvotes" to "点踩数", "score" to "评分", "wilson_score" to "Wilson 评分",
    "_score" to "相关度", "width" to "宽度", "height" to "高度", "comment_count" to "评论数", "tag_count" to "标签数量", "pixels" to "像素数", "size" to "文件大小", "duration" to "时长"
)

class SearchActivity : AppCompatActivity() {
    companion object { const val EXTRA_INITIAL_QUERY = "initial_query" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var queryInput: AutoCompleteTextView
    private lateinit var chips: ChipGroup
    private lateinit var results: RecyclerView
    private lateinit var searchProgress: ProgressBar
    private lateinit var adapter: ImageAdapter
    private lateinit var sortField: Spinner
    private lateinit var sortDirection: Spinner
    private var suggestionJob: Job? = null
    private var suggestionEntries: List<TagEntry> = emptyList()
    private lateinit var searchRoot: View
    private lateinit var headerExtras: LinearLayout
    private var headerCollapsed = false
    private var scrollDistance = 0
    private var ignoreScrollUntil = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        searchRoot = buildView()
        setContentView(searchRoot)
        // The search screen is built programmatically, so apply the selected
        // palette after its views exist.
        PaletteManager.apply(this)
        applySearchPalette()
    }

    private fun buildView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PaletteManager.colors(this@SearchActivity).surface)
        }
        val toolbar = SafeToolbar(this).apply { title = "搜索"; setNavigationIcon(R.drawable.ic_arrow_back); setNavigationOnClickListener { finish() } }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
        queryInput = AutoCompleteTextView(this).apply {
            hint = "输入标签或搜索条件"
            setSingleLine(true)
            setPadding(28, 0, 28, 0)
            threshold = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        }
        root.addView(queryInput, LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(dp(16), dp(12), dp(16), 0) })
        headerExtras = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chips = ChipGroup(this).apply { isSingleLine = true; setPadding(dp(16), dp(8), dp(16), 0) }
        headerExtras.addView(chips)
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(4)) }
        actions.addView(Button(this).apply { text = "高级筛选"; setOnClickListener { showAdvanced() } }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(Button(this).apply { text = "搜索"; setOnClickListener { runSearch() } })
        headerExtras.addView(actions)
        val sortRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(16), dp(8)) }
        sortField = Spinner(this); sortDirection = Spinner(this)
        sortField.adapter = PaletteArrayAdapter(SORT_FIELDS.map { it.second })
        sortDirection.adapter = PaletteArrayAdapter(listOf("降序", "升序"))
        sortRow.addView(TextView(this).apply { text = "排序"; setTextColor(PaletteManager.colors(this@SearchActivity).onSurface) }, LinearLayout.LayoutParams(-2, -2))
        sortRow.addView(sortField, LinearLayout.LayoutParams(0, -2, 1f)); sortRow.addView(sortDirection, LinearLayout.LayoutParams(0, -2, 1f))
        headerExtras.addView(sortRow)
        root.addView(headerExtras)
        results = RecyclerView(this).apply { layoutManager = GridLayoutManager(this@SearchActivity, 2) }
        adapter = ImageAdapter(emptyList(), { startActivity(Intent(this, ImageDetailActivity::class.java).putExtra("image", it)) })
        results.adapter = adapter
        val resultContainer = FrameLayout(this)
        resultContainer.addView(results, FrameLayout.LayoutParams(-1, -1))
        searchProgress = ProgressBar(this).apply {
            visibility = View.GONE
            indeterminateTintList = ColorStateList.valueOf(PaletteManager.colors(this@SearchActivity).primary)
        }
        resultContainer.addView(searchProgress, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        root.addView(resultContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        results.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (SystemClock.elapsedRealtime() < ignoreScrollUntil || dy == 0) return
                val threshold = dp(28)
                scrollDistance = when {
                    dy > 0 -> (scrollDistance + dy).coerceAtMost(threshold)
                    else -> (scrollDistance + dy).coerceAtLeast(-threshold)
                }
                if (!headerCollapsed && scrollDistance >= threshold) setSearchPanelsCollapsed(true)
                else if (headerCollapsed && scrollDistance <= -threshold) setSearchPanelsCollapsed(false)
            }
        })
        addQuickChip("安全", "safe"); addQuickChip("动图", "animated:true")
        if (ApiKeyStore.isLoggedIn(this)) { addQuickChip("我的收藏", "my:faves"); addQuickChip("我的点赞", "my:upvotes") }
        val recent = getSharedPreferences("search_history", 0).getStringSet("items", emptySet()).orEmpty()
        queryInput.setAdapter(PaletteArrayAdapter(recent.toList()))
        queryInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun afterTextChanged(s: Editable?) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { loadSuggestions(s?.toString().orEmpty()) }
        })
        queryInput.setOnItemClickListener { _, _, position, _ ->
            suggestionEntries.getOrNull(position)?.let { entry ->
                queryInput.setText(entry.englishName)
                queryInput.setSelection(queryInput.length())
            }
        }
        intent.getStringExtra(EXTRA_INITIAL_QUERY)?.takeIf { it.isNotBlank() }?.let { query ->
            queryInput.setText(query)
            queryInput.setSelection(query.length)
            queryInput.post { runSearch() }
        }
        return root
    }

    private fun addQuickChip(label: String, value: String) {
        chips.addView(Chip(this).apply {
            text = label
            isCheckable = false
            applyQuickChipPalette(this)
            setOnClickListener { queryInput.append(if (queryInput.text.isNullOrBlank()) value else ", $value") }
        })
    }

    private fun loadSuggestions(prefix: String) {
        suggestionJob?.cancel()
        if (prefix.length < 1 || prefix.contains(':')) return
        suggestionJob = scope.launch {
            delay(300)
            val local = TagDictionary.search(this@SearchActivity, prefix)
            if (local.isNotEmpty()) {
                suggestionEntries = local
                val labels = local.map { "${it.chineseName}  ·  ${it.englishName}" }
                queryInput.setAdapter(PaletteArrayAdapter(labels).apply { filterEnabled = false })
                if (queryInput.hasFocus()) queryInput.showDropDown()
            } else {
                val encoded = URLEncoder.encode("$prefix*", "UTF-8")
                val json = withContext(Dispatchers.IO) { NetworkManager.getApi(this@SearchActivity, "search/tags?q=$encoded") }
                val names = runCatching { JSONObject(json.orEmpty()).optJSONArray("tags")?.let { a -> List(minOf(a.length(), 8)) { a.getJSONObject(it).optString("name") } } }.getOrNull().orEmpty()
                suggestionEntries = names.map { TagEntry(it, it, 0, 0, emptyList()) }
                queryInput.setAdapter(PaletteArrayAdapter(names).apply { filterEnabled = false })
                if (names.isNotEmpty() && queryInput.hasFocus()) queryInput.showDropDown()
            }
        }
    }

    private fun showAdvanced() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        val fieldSpinner = Spinner(this).apply { adapter = PaletteArrayAdapter(SEARCH_FIELDS.map { it.label }) }
        val comparator = Spinner(this).apply { adapter = PaletteArrayAdapter(listOf("≥", "≤", ">", "<")) }
        val value = EditText(this).apply { hint = "数值、日期或文本" }
        val negate = CheckBox(this).apply { text = "排除此条件（NOT）" }
        box.addView(fieldSpinner); box.addView(comparator); box.addView(value); box.addView(negate)
        val dialog = AlertDialog.Builder(this).setTitle("高级筛选").setView(box).setNegativeButton("取消", null).setPositiveButton("添加") { _, _ ->
            val field = SEARCH_FIELDS[fieldSpinner.selectedItemPosition]; val raw = value.text.toString().trim(); if (raw.isBlank()) return@setPositiveButton
            val prefix = if (negate.isChecked) "-" else ""
            val fragment = when (field.type) {
                SearchFieldType.NUMERIC -> "$prefix${field.key}${listOf(".gte", ".lte", ".gt", ".lt")[comparator.selectedItemPosition]}:$raw"
                SearchFieldType.DATE -> "$prefix${field.key}.gte:$raw"
                SearchFieldType.BOOLEAN, SearchFieldType.LITERAL -> "$prefix${field.key}:$raw"
            }
            queryInput.append(if (queryInput.text.isNullOrBlank()) fragment else ", $fragment")
        }.create()
        dialog.setOnShowListener {
            val c = PaletteManager.colors(this)
            dialog.window?.decorView?.setBackgroundColor(c.surface)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(c.primary)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(c.primary)
            listOf(value, negate).forEach { it.setTextColor(c.onSurface) }
            value.setHintTextColor(c.muted)
        }
        dialog.show()
    }

    private fun runSearch() {
        val query = queryInput.text.toString().trim(); if (query.isBlank()) return
        adapter.updateData(emptyList())
        searchProgress.visibility = View.VISIBLE
        val encoded = URLEncoder.encode(query, "UTF-8")
        val sf = SORT_FIELDS[sortField.selectedItemPosition].first; val sd = if (sortDirection.selectedItemPosition == 0) "desc" else "asc"
        val historyPrefs = getSharedPreferences("search_history", 0)
        val history = (historyPrefs.getStringSet("items", emptySet()).orEmpty() + query).toList().takeLast(10).toSet()
        historyPrefs.edit().putStringSet("items", history).apply()
        scope.launch {
            try {
                val filter = NetworkManager.currentFilterParam(this@SearchActivity)
                val json = withContext(Dispatchers.IO) { NetworkManager.getApi(this@SearchActivity, "search/images?q=$encoded&sf=$sf&sd=$sd&per_page=50$filter") }
                adapter.updateData(json?.let { parseImages(it) }.orEmpty())
            } finally {
                searchProgress.visibility = View.GONE
            }
        }
    }

    private fun parseImages(json: String): List<Image> {
        val a = JSONObject(json).optJSONArray("images") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> val o = a.optJSONObject(i) ?: return@mapNotNull null; val r = o.optJSONObject("representations"); Image(o.optInt("id"), "", r?.optString("small", null) ?: r?.optString("thumb", null), o.optInt("width"), o.optInt("height"), o.optInt("score"), o.optInt("faves"), o.optInt("upvotes"), o.optInt("downvotes"), o.optInt("comment_count"), emptyList(), r?.optString("full", null), o.optString("uploader", null), o.optString("created_at", null), o.optString("description", null), o.optString("mime_type", null)) }
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun applySearchPalette() {
        val c = PaletteManager.colors(this)
        searchRoot.setBackgroundColor(c.surface)
        queryInput.setTextColor(c.onSurface)
        queryInput.setHintTextColor(c.muted)
        queryInput.backgroundTintList = ColorStateList.valueOf(c.primary)
        listOf(sortField, sortDirection).forEach { it.setBackgroundColor(c.surface); it.setPopupBackgroundDrawable(android.graphics.drawable.ColorDrawable(c.surface)) }
        results.setBackgroundColor(c.surface)
        searchProgress.indeterminateTintList = ColorStateList.valueOf(c.primary)
        for (i in 0 until chips.childCount) (chips.getChildAt(i) as? Chip)?.let { applyQuickChipPalette(it) }
    }

    private fun setSearchPanelsCollapsed(collapsed: Boolean) {
        if (headerCollapsed == collapsed) return
        headerCollapsed = collapsed
        scrollDistance = 0
        ignoreScrollUntil = SystemClock.elapsedRealtime() + 220L
        headerExtras.animate().cancel()
        if (collapsed) {
            headerExtras.animate().alpha(0f).translationY(-dp(12).toFloat()).setDuration(180L)
                .withEndAction {
                    if (headerCollapsed) {
                        headerExtras.visibility = View.GONE
                        headerExtras.alpha = 1f
                        headerExtras.translationY = 0f
                    }
                }.start()
        } else {
            headerExtras.visibility = View.VISIBLE
            headerExtras.alpha = 0f
            headerExtras.translationY = -dp(12).toFloat()
            headerExtras.animate().alpha(1f).translationY(0f).setDuration(180L).start()
        }
    }

    private fun applyQuickChipPalette(chip: Chip) {
        val c = PaletteManager.colors(this)
        chip.setTextColor(c.onSurface)
        chip.chipBackgroundColor = ColorStateList.valueOf(c.surfaceVariant)
        chip.rippleColor = ColorStateList.valueOf(c.primary)
    }

    private inner class PaletteArrayAdapter(private val items: List<String>) : ArrayAdapter<String>(this@SearchActivity, 0, items), SpinnerAdapter {
        var filterEnabled: Boolean = true
        override fun getFilter(): Filter = if (filterEnabled) super.getFilter() else object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults = FilterResults().apply { values = items; count = items.size }
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) { notifyDataSetChanged() }
        }
        private val palette get() = PaletteManager.colors(this@SearchActivity)
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = textView(position, parent)
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = textView(position, parent)
        private fun textView(position: Int, parent: ViewGroup): TextView = TextView(this@SearchActivity).apply {
            text = getItem(position).orEmpty()
            setTextColor(palette.onSurface)
            setBackgroundColor(palette.surface)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = AbsListView.LayoutParams(-1, -2)
        }
    }

    override fun onResume() { super.onResume(); if (::searchRoot.isInitialized) { PaletteManager.apply(this); applySearchPalette() } }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
