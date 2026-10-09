package com.kerybotu.derpibooru.mirror.ui

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.liquidglass.*
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.PaletteDefinitions
import com.kerybotu.derpibooru.mirror.R
import com.kerybotu.derpibooru.mirror.auth.ApiKeyStore
import com.kerybotu.derpibooru.mirror.dict.TagDictionary
import com.kerybotu.derpibooru.mirror.dict.TagEntry
import com.kerybotu.derpibooru.mirror.download.DownloadQueueManager
import com.kerybotu.derpibooru.mirror.model.Image
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.roundToInt

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
    private lateinit var queryInput: MultiAutoCompleteTextView
    private lateinit var results: RecyclerView
    private lateinit var adapter: ImageAdapter
    private lateinit var searchRoot: FrameLayout
    private lateinit var pageBackdrop: View
    private lateinit var resultsSurface: IosPullRefreshLayout
    private lateinit var controls: LinearLayout
    private lateinit var headerExtras: LinearLayout
    private lateinit var loadingPanel: LiquidGlassView
    private lateinit var loadingIndicator: IosActivityIndicator
    private lateinit var statusPanel: LiquidGlassListItem
    private lateinit var selectionActions: LinearLayout
    private lateinit var downloadButton: LiquidGlassButton
    private var sortIndex = 0
    private var directionIndex = 0
    private var suggestionJob: Job? = null
    private var searchJob: Job? = null
    private var resumed = false
    private val attachedGlass = mutableMapOf<LiquidGlassView, Float>()
    private val visibleRect = Rect()
    private var headerContentHeight = 0
    private var headerHiddenPixels = 0f
    private var headerSnapAnimator: ValueAnimator? = null
    private var bottomInset = 0
    private var systemLeftInset = 0
    private var systemRightInset = 0
    private var appliedPalette: PaletteDefinitions.Scheme? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        searchRoot = buildView()
        setContentView(searchRoot)
        applySearchPalette()
        /* Legacy non-glass search layout retained in the checkpoint.
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
        actions.addView(Button(this).apply { text = "高级筛选"; PaletteManager.styleButton(this); setOnClickListener { showAdvanced() } }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(Button(this).apply { text = "搜索"; PaletteManager.styleButton(this); setOnClickListener { runSearch() } })
        headerExtras.addView(actions)
        val sortRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(16), dp(8)) }
        sortField = Spinner(this); sortDirection = Spinner(this)
        sortField.adapter = PaletteArrayAdapter(SORT_FIELDS.map { it.second })
        sortDirection.adapter = PaletteArrayAdapter(listOf("降序", "升序"))
        sortRow.addView(TextView(this).apply { text = "排序"; setTextColor(PaletteManager.colors(this@SearchActivity).onSurface) }, LinearLayout.LayoutParams(-2, -2))
        sortRow.addView(sortField, LinearLayout.LayoutParams(0, -2, 1f)); sortRow.addView(sortDirection, LinearLayout.LayoutParams(0, -2, 1f))
        headerExtras.addView(sortRow)
        root.addView(headerExtras)
        // Capture the natural height before any interactive collapsing starts.
        headerExtras.post {
            headerContentHeight = headerExtras.height
            setHeaderProgress(0f)
        }
        results = RecyclerView(this).apply { layoutManager = GridLayoutManager(this@SearchActivity, AdaptiveLayoutPolicy.artworkColumnCount(this@SearchActivity)) }
        AdaptiveLayoutPolicy.configureArtworkGrid(this, results)
        adapter = ImageAdapter(emptyList(), { startActivity(Intent(this, ImageDetailActivity::class.java).putExtra("image", it)) }, settingsContext = this)
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
                if (dy == 0 || headerContentHeight <= 0) return
                headerSnapAnimator?.cancel()
                headerHiddenPixels = (headerHiddenPixels + dy.toFloat())
                    .coerceIn(0f, headerContentHeight.toFloat())
                setHeaderProgress(headerHiddenPixels)
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE || headerContentHeight <= 0) return
                setSearchPanelsCollapsed(headerHiddenPixels >= headerContentHeight * 0.5f)
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
        */
        ViewCompat.requestApplyInsets(searchRoot)
        intent.getStringExtra(EXTRA_INITIAL_QUERY)?.takeIf { it.isNotBlank() }?.let {
            queryInput.setText(it)
            queryInput.setSelection(it.length)
            queryInput.post { runSearch() }
        }
    }

    private fun buildView(): FrameLayout {
        val root = FrameLayout(this)
        pageBackdrop = View(this).apply { setBackgroundColor(PaletteManager.colors(this@SearchActivity).surface) }
        root.addView(pageBackdrop, FrameLayout.LayoutParams(-1, -1))
        resultsSurface = IosPullRefreshLayout(this).apply { setBackgroundColor(PaletteManager.colors(this@SearchActivity).surface) }
        results = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@SearchActivity, AdaptiveLayoutPolicy.artworkColumnCount(this@SearchActivity))
            clipToPadding = false
        }
        // Keep grid sizing and system insets in one place; a second layout listener
        // would overwrite the safe-area padding when the results width changes.
        results.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) updateResultInsets()
        }
        adapter = ImageAdapter(emptyList(), {
            startActivity(Intent(this, ImageDetailActivity::class.java).putExtra("image", it))
        }, { count ->
            selectionActions.visibility = if (count > 0) View.VISIBLE else View.GONE
            downloadButton.text = "下载 $count 张"
        }, this, glassBackdrop = pageBackdrop, onGlassCreated = { glass ->
            trackGlass(glass, glass.backdropSource ?: pageBackdrop, 16f)
        })
        results.adapter = adapter
        resultsSurface.addView(results, FrameLayout.LayoutParams(-1, -1))
        resultsSurface.scrollTarget = results
        resultsSurface.setCanRefresh {
            searchJob?.isActive != true && queryInput.text.toString().trim().trimEnd(',').isNotBlank()
        }
        resultsSurface.onPullStarted = { headerSnapAnimator?.cancel() }
        resultsSurface.setOnRefreshListener { runSearch(fromPull = true) }
        root.addView(resultsSurface, FrameLayout.LayoutParams(-1, -1))

        controls = column()
        val toolbar = trackGlass(LiquidGlassListItem(this)).apply {
            headline = "搜索"
            setLeadingIconResource(R.drawable.ic_arrow_back)
            trailingText = "筛选 ▾"
            contentDescription = "返回；筛选按钮可展开或收起搜索条件"
            leadingImageView.apply { isClickable = true; contentDescription = "返回"; setOnClickListener { finish() } }
            setOnClickListener { setSearchPanelsCollapsed(headerHiddenPixels < headerContentHeight / 2f) }
        }
        controls.addView(toolbar, rowParams())
        queryInput = object : MultiAutoCompleteTextView(this) {
            override fun convertSelectionToString(selectedItem: Any?): CharSequence =
                (selectedItem as? TagEntry)?.englishName ?: super.convertSelectionToString(selectedItem)
        }.apply {
            hint = "输入标签或搜索条件"
            setSingleLine(true)
            setPadding(dp(16), 0, dp(16), 0)
            background = null
            threshold = 1
            setTokenizer(MultiAutoCompleteTextView.CommaTokenizer())
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setDropDownBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) { runSearch(); true } else false
            }
        }
        val inputSurface = trackGlass(LiquidGlassView(this)).apply {
            addView(queryInput, FrameLayout.LayoutParams(-1, dp(56)))
        }
        controls.addView(inputSurface, rowParams())
        headerExtras = column()
        val chips = LiquidGlassChipGroup(this).apply {
            isSingleLine = true
            backdropSource = resultsSurface
        }
        fun addChip(label: String, value: String) {
            chips.addView(trackGlass(LiquidGlassChip(this)).apply {
                text = label
                minimumHeight = dp(48)
                setOnClickListener { appendQuery(value) }
            })
        }
        addChip("安全", "safe"); addChip("动图", "animated:true")
        if (ApiKeyStore.isLoggedIn(this)) { addChip("我的收藏", "my:faves"); addChip("我的点赞", "my:upvotes") }
        headerExtras.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }, rowParams())
        val actions = row()
        actions.addView(button("高级筛选") { showAdvanced() }, weightedParams())
        actions.addView(button("搜索") { runSearch() }, weightedParams())
        headerExtras.addView(actions, rowParams())
        val sorts = row()
        val field = button("排序：${SORT_FIELDS[sortIndex].second}") { }
        field.setOnClickListener {
            showChoices("排序字段", SORT_FIELDS.map { it.second }, sortIndex) {
                sortIndex = it; field.text = "排序：${SORT_FIELDS[it].second}"
            }
        }
        val direction = button("降序 ▾") { }
        direction.setOnClickListener {
            showChoices("排序方向", listOf("降序", "升序"), directionIndex) {
                directionIndex = it; direction.text = if (it == 0) "降序 ▾" else "升序 ▴"
            }
        }
        sorts.addView(field, LinearLayout.LayoutParams(0, dp(52), 2f).apply { marginEnd = dp(6) })
        sorts.addView(direction, LinearLayout.LayoutParams(0, dp(52), 1f))
        headerExtras.addView(sorts, rowParams())
        controls.addView(headerExtras, LinearLayout.LayoutParams(-1, -2))
        selectionActions = row().apply { visibility = View.GONE }
        downloadButton = button("下载") {
            DownloadQueueManager.get(this).enqueueImages(adapter.selectedItems())
            adapter.clearSelection()
            LiquidGlassToast.makeText(this, "已加入下载队列", LiquidGlassToast.LENGTH_SHORT)
                .setTextColor(PaletteManager.colors(this@SearchActivity).glassText).show()
        }
        selectionActions.addView(downloadButton, weightedParams())
        selectionActions.addView(button("取消选择") { adapter.clearSelection() }, weightedParams())
        controls.addView(selectionActions, rowParams())
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        controls.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateResultInsets() }
        headerExtras.post { headerContentHeight = headerExtras.height; setHeaderProgress(0f) }

        loadingIndicator = IosActivityIndicator(this).apply { contentDescription = "正在搜索" }
        loadingPanel = trackGlass(LiquidGlassView(this)).apply {
            visibility = View.GONE
            addView(loadingIndicator, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        }
        root.addView(loadingPanel, FrameLayout.LayoutParams(dp(80), dp(80), Gravity.CENTER))
        statusPanel = trackGlass(LiquidGlassListItem(this)).apply { visibility = View.GONE }
        root.addView(statusPanel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
            setMargins(dp(24), 0, dp(24), 0)
        })
        results.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy == 0 || headerContentHeight <= 0 || resultsSurface.isPulling) return
                headerSnapAnimator?.cancel()
                setHeaderProgress(headerHiddenPixels + dy)
            }
            override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                if (state == RecyclerView.SCROLL_STATE_IDLE && headerContentHeight > 0 && !resultsSurface.isPulling)
                    setSearchPanelsCollapsed(headerHiddenPixels >= headerContentHeight * 0.5f)
            }
        })
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            systemLeftInset = bars.left
            systemRightInset = bars.right
            controls.setPadding(bars.left + dp(12), bars.top + dp(8), bars.right + dp(12), 0)
            bottomInset = maxOf(bars.bottom, ime.bottom)
            updateResultInsets()
            insets
        }
        val recent = getSharedPreferences("search_history", 0).getStringSet("items", emptySet()).orEmpty()
        queryInput.setAdapter(SuggestionAdapter(recent.map { TagEntry(it, it, 0, 0, emptyList()) }))
        queryInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun afterTextChanged(s: Editable?) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { loadSuggestions() }
        })
        return root
    }

    private fun appendQuery(value: String) {
        val current = queryInput.text.toString().trim().trimEnd(',')
        queryInput.setText(if (current.isBlank()) value else "$current, $value")
        queryInput.setSelection(queryInput.length())
    }

    private fun loadSuggestions() {
        suggestionJob?.cancel()
        val text = queryInput.text.toString()
        val cursor = queryInput.selectionStart.coerceIn(0, text.length)
        val prefix = text.substring(0, cursor).substringAfterLast(',').trim()
        if (prefix.isBlank() || ':' in prefix) { queryInput.dismissDropDown(); return }
        suggestionJob = scope.launch {
            delay(300)
            try {
                val local = TagDictionary.search(this@SearchActivity, prefix)
                val entries = if (local.isNotEmpty()) local else {
                    val encoded = URLEncoder.encode("$prefix*", "UTF-8")
                    val json = withContext(Dispatchers.IO) { NetworkManager.getApi(this@SearchActivity, "search/tags?q=$encoded") }
                    val tags = JSONObject(json.orEmpty()).optJSONArray("tags")
                    List(minOf(tags?.length() ?: 0, 8)) { index ->
                        val name = tags!!.getJSONObject(index).optString("name")
                        TagEntry(name, name, 0, 0, emptyList())
                    }
                }
                queryInput.setAdapter(SuggestionAdapter(entries))
                if (entries.isNotEmpty() && queryInput.hasFocus()) queryInput.showDropDown()
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { queryInput.dismissDropDown() }
        }
    }

    private fun showChoices(title: String, labels: List<String>, selected: Int, onSelected: (Int) -> Unit) {
        val group = LiquidGlassListGroup(this).apply {
            style = LiquidGlassListGroup.Style.SEPARATED
            backdropSource = resultsSurface
        }
        lateinit var dialog: AlertDialog
        labels.forEachIndexed { index, label ->
            group.addView(trackGlass(LiquidGlassListItem(this), radius = 16f).apply {
                headline = label
                trailingText = if (index == selected) "✓" else null
                setOnClickListener { onSelected(index); dialog.dismiss() }
            })
        }
        dialog = glassDialog(title, ScrollView(this).apply { addView(group) })
        dialog.show()
    }

    private fun showAdvanced() {
        var fieldIndex = 0
        var compareIndex = 0
        val box = column().apply { setPadding(dp(16), dp(8), dp(16), dp(16)) }
        val field = button(SEARCH_FIELDS[0].label) { }
        field.setOnClickListener { showChoices("筛选字段", SEARCH_FIELDS.map { it.label }, fieldIndex) {
            fieldIndex = it; field.text = SEARCH_FIELDS[it].label
        } }
        val comparison = button("≥") { }
        comparison.setOnClickListener { showChoices("比较方式", listOf("≥", "≤", ">", "<"), compareIndex) {
            compareIndex = it; comparison.text = listOf("≥", "≤", ">", "<")[it]
        } }
        val value = EditText(this).apply {
            hint = "数值、日期或文本"; setSingleLine(true); background = null
            setPadding(dp(16), 0, dp(16), 0)
            setTextColor(PaletteManager.colors(this@SearchActivity).glassText); setHintTextColor(PaletteManager.colors(this@SearchActivity).glassSecondaryText)
        }
        val valueSurface = trackGlass(LiquidGlassView(this)).apply { addView(value, FrameLayout.LayoutParams(-1, dp(56))) }
        val negate = trackGlass(LiquidGlassChip(this)).apply {
            text = "排除此条件（NOT）"; isCheckable = true; minimumHeight = dp(48)
            checkedTint = androidx.core.graphics.ColorUtils.setAlphaComponent(PaletteManager.colors(this@SearchActivity).surfaceVariant, 200)
        }
        listOf(field, comparison, valueSurface, negate).forEach { box.addView(it, rowParams()) }
        lateinit var dialog: AlertDialog
        val actions = row()
        actions.addView(button("取消") { dialog.dismiss() }, weightedParams())
        actions.addView(button("添加") {
            val raw = value.text.toString().trim()
            if (raw.isBlank()) {
                LiquidGlassToast.makeText(this, "请输入条件", LiquidGlassToast.LENGTH_SHORT)
                    .setTextColor(PaletteManager.colors(this@SearchActivity).glassText).show()
                value.requestFocus()
                return@button
            }
            val def = SEARCH_FIELDS[fieldIndex]
            val prefix = if (negate.isChecked) "-" else ""
            val fragment = when (def.type) {
                SearchFieldType.NUMERIC -> "$prefix${def.key}${listOf(".gte", ".lte", ".gt", ".lt")[compareIndex]}:$raw"
                SearchFieldType.DATE -> "$prefix${def.key}.gte:$raw"
                SearchFieldType.BOOLEAN, SearchFieldType.LITERAL -> "$prefix${def.key}:$raw"
            }
            appendQuery(fragment); dialog.dismiss()
        }, weightedParams())
        box.addView(actions, rowParams())
        dialog = glassDialog("高级筛选", ScrollView(this).apply { addView(box) })
        dialog.show()
    }

    private fun glassDialog(title: String, content: View): AlertDialog {
        val builder = LiquidGlassDialogBuilder(this, animateShow = false, glassSetup = {
            trackGlass(this, resultsSurface, 28f)
        })
        val color = PaletteManager.colors(this).glassText
        builder.overLightTextColor = color; builder.overDarkTextColor = color
        return builder.setTitle(title).setView(content).create()
    }

    private fun runSearch(fromPull: Boolean = false) {
        val query = queryInput.text.toString().trim().trimEnd(',')
        if (query.isBlank()) { resultsSurface.isRefreshing = false; return }
        suggestionJob?.cancel(); queryInput.dismissDropDown()
        searchJob?.cancel()
        if (!fromPull) {
            resultsSurface.isRefreshing = false
            adapter.updateData(emptyList())
        }
        statusPanel.visibility = View.GONE
        loadingPanel.visibility = if (fromPull) View.GONE else View.VISIBLE
        updateGlassRendering()
        val historyPrefs = getSharedPreferences("search_history", 0)
        val history = (historyPrefs.getStringSet("items", emptySet()).orEmpty() + query).toList().takeLast(10).toSet()
        historyPrefs.edit().putStringSet("items", history).apply()
        val sf = SORT_FIELDS[sortIndex].first
        val sd = if (directionIndex == 0) "desc" else "asc"
        searchJob = scope.launch {
            try {
                val filter = NetworkManager.currentFilterParam(this@SearchActivity)
                val encoded = URLEncoder.encode(query, "UTF-8")
                val json = withContext(Dispatchers.IO) { NetworkManager.getApi(this@SearchActivity, "search/images?q=$encoded&sf=$sf&sd=$sd&per_page=50$filter") }
                val images = parseImages(json ?: error("empty response"))
                adapter.updateData(images)
                if (adapter.itemCount == 0) {
                    statusPanel.headline = "没有找到图片"; statusPanel.supportingText = "试试其他标签或筛选条件"
                    statusPanel.setOnClickListener(null); statusPanel.visibility = View.VISIBLE
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                statusPanel.headline = "搜索失败"; statusPanel.supportingText = "点击重试"
                statusPanel.setOnClickListener { runSearch() }; statusPanel.visibility = View.VISIBLE
            } finally {
                if (currentCoroutineContext().isActive) {
                    loadingPanel.visibility = View.GONE
                    resultsSurface.isRefreshing = false
                    updateGlassRendering()
                }
            }
        }
    }

    private fun <T : LiquidGlassView> trackGlass(view: T, source: View = resultsSurface, radius: Float = 24f): T {
        GlassWidgetStyle.apply(view, radius)
        view.backdropSource = source
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                attachedGlass[view] = radius
                GlassWidgetStyle.apply(view, radius)
                view.post { updateGlassRendering() }
            }
            override fun onViewDetachedFromWindow(v: View) {
                attachedGlass.remove(view); view.enableDynamicBackground = false; view.enableSensorHighlight = false
            }
        })
        return view
    }

    private fun updateGlassRendering() {
        attachedGlass.keys.toList().forEach { glass ->
            val active = resumed && glass.isShown && glass.getGlobalVisibleRect(visibleRect)
            glass.enableDynamicBackground = active
            glass.enableSensorHighlight = active
        }
    }

    private fun applySearchPalette() {
        val colors = PaletteManager.colors(this)
        pageBackdrop.setBackgroundColor(colors.surface); resultsSurface.setBackgroundColor(colors.surface)
        window.statusBarColor = colors.surface; window.navigationBarColor = colors.surface
        val light = Color.luminance(colors.surface) > 0.5f
        WindowCompat.getInsetsController(window, searchRoot).apply {
            isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light
        }
        queryInput.setTextColor(colors.glassText); queryInput.setHintTextColor(colors.glassSecondaryText)
        loadingIndicator.applyPalette(colors)
        resultsSurface.applyPalette(colors)
        attachedGlass.toMap().forEach { (glass, radius) -> GlassWidgetStyle.apply(glass, radius) }
        adapter.refreshDisplayMode()
        if (appliedPalette != colors) adapter.notifyDataSetChanged()
        appliedPalette = colors
        updateGlassRendering()
    }

    private fun setSearchPanelsCollapsed(collapsed: Boolean) {
        if (headerContentHeight <= 0) return
        headerSnapAnimator?.cancel()
        headerSnapAnimator = ValueAnimator.ofFloat(headerHiddenPixels, if (collapsed) headerContentHeight.toFloat() else 0f).apply {
            duration = 180L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { setHeaderProgress(it.animatedValue as Float) }
            start()
        }
    }

    private fun setHeaderProgress(hidden: Float) {
        if (headerContentHeight <= 0) return
        headerHiddenPixels = hidden.coerceIn(0f, headerContentHeight.toFloat())
        headerExtras.layoutParams = headerExtras.layoutParams.apply { height = (headerContentHeight - headerHiddenPixels).roundToInt() }
        headerExtras.alpha = 1f - headerHiddenPixels / headerContentHeight
        updateGlassRendering()
    }

    private fun updateResultInsets() {
        if (!::results.isInitialized) return
        val density = resources.displayMetrics.density
        val windowWidth = (resources.configuration.screenWidthDp * density).toInt()
        val safeWidth = ((results.width.takeIf { it > 0 } ?: windowWidth) - systemLeftInset - systemRightInset).coerceAtLeast(1)
        val safeWidthDp = (safeWidth / density).toInt().coerceAtLeast(1)
        val horizontalInset = AdaptiveLayoutPolicy.artworkHorizontalInsetPx(this, safeWidthDp)
        val columns = AdaptiveLayoutPolicy.artworkColumnCountForWidth(safeWidthDp)
        (results.layoutManager as? GridLayoutManager)?.let { grid ->
            if (grid.spanCount != columns) grid.spanCount = columns
        }
        results.setPadding(
            systemLeftInset + horizontalInset,
            controls.height + dp(8),
            systemRightInset + horizontalInset,
            bottomInset + dp(8)
        )
        resultsSurface.contentTopInset = results.paddingTop
        if (::loadingPanel.isInitialized) {
            loadingPanel.layoutParams = (loadingPanel.layoutParams as FrameLayout.LayoutParams).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
                leftMargin = systemLeftInset + (safeWidth - width) / 2
                rightMargin = 0
            }
        }
        if (::statusPanel.isInitialized) {
            statusPanel.layoutParams = (statusPanel.layoutParams as FrameLayout.LayoutParams).apply {
                width = (safeWidth - horizontalInset * 2).coerceAtLeast(1)
                gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
                leftMargin = systemLeftInset + horizontalInset
                rightMargin = 0
            }
        }
        queryInput.dropDownWidth = queryInput.width
        updateGlassRendering()
    }

    private fun button(label: String, action: () -> Unit) = trackGlass(LiquidGlassButton(this)).apply {
        text = label; setTextSize(14f); textView.setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener { action() }
    }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun rowParams() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    private fun weightedParams() = LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private inner class SuggestionAdapter(private val entries: List<TagEntry>) : ArrayAdapter<TagEntry>(this@SearchActivity, 0, entries) {
        override fun getFilter(): Filter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?) = FilterResults().apply { values = entries; count = entries.size }
            override fun publishResults(constraint: CharSequence?, result: FilterResults?) { notifyDataSetChanged() }
        }
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val entry = getItem(position)!!
            return ((convertView as? LiquidGlassListItem) ?: trackGlass(object : LiquidGlassListItem(this@SearchActivity) {
                // AutoCompleteTextView's ListView owns selection. The library's
                // base view consumes touches even when isClickable is false.
                override fun onTouchEvent(event: MotionEvent): Boolean = false
            }, radius = 12f)).apply {
                headline = entry.chineseName; supportingText = entry.englishName.takeIf { it != entry.chineseName }
                layoutParams = AbsListView.LayoutParams(-1, -2)
                isClickable = false; isFocusable = false
            }
        }
    }

    override fun onResume() { super.onResume(); resumed = true; if (::searchRoot.isInitialized) applySearchPalette() }
    override fun onPause() { resumed = false; headerSnapAnimator?.cancel(); updateGlassRendering(); super.onPause() }
    override fun onDestroy() { headerSnapAnimator?.cancel(); scope.cancel(); attachedGlass.clear(); super.onDestroy() }
    private fun parseImages(json: String): List<Image> {
        val a = JSONObject(json).optJSONArray("images") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> val o = a.optJSONObject(i) ?: return@mapNotNull null; val r = o.optJSONObject("representations"); Image(o.optInt("id"), "", r?.optString("small", null) ?: r?.optString("thumb", null), o.optInt("width"), o.optInt("height"), o.optInt("score"), o.optInt("faves"), o.optInt("upvotes"), o.optInt("downvotes"), o.optInt("comment_count"), emptyList(), r?.optString("full", null), o.optString("uploader", null), o.optString("created_at", null), o.optString("description", null), o.optString("mime_type", null)) }
    }
}
