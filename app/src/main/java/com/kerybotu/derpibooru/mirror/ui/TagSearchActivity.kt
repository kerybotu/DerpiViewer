package com.kerybotu.derpibooru.mirror.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.liquidglass.LiquidGlassButton
import com.example.liquidglass.LiquidGlassListItem
import com.example.liquidglass.LiquidGlassView
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.R
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import com.kerybotu.derpibooru.mirror.network.ApiFailureKind
import com.kerybotu.derpibooru.mirror.network.ApiRequestDiagnostic
import com.kerybotu.derpibooru.mirror.dict.TagDictionary
import com.kerybotu.derpibooru.mirror.model.Filter
import com.kerybotu.derpibooru.mirror.model.FilterSearchInput
import com.kerybotu.derpibooru.mirror.model.TagSearchMode
import com.kerybotu.derpibooru.mirror.model.TagSearchRouting
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URLEncoder
import java.text.NumberFormat
import java.util.Locale

class TagSearchActivity : AppCompatActivity() {
    private data class Tag(val name: String, val images: Long, val description: String)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /* Legacy non-glass tag search implementation retained in the checkpoint.
    private lateinit var input: EditText; private lateinit var list: LinearLayout; private lateinit var scroll: ScrollView; private lateinit var progress: ProgressBar
    private var page = 1; private var loading = false; private var query = "*"
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContentView(buildView()); load(true) }
    private fun buildView(): View {
        val c = PaletteManager.colors(this); val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(c.surface) }
        root.addView(SafeToolbar(this).apply { title = "标签"; setNavigationIcon(com.kerybotu.derpibooru.mirror.R.drawable.ic_arrow_back); setNavigationOnClickListener { finish() } }, LinearLayout.LayoutParams(-1, dp(56)))
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        input = EditText(this).apply { hint = "搜索标签（* 表示全部）"; setSingleLine(); setText("*"); setSelectAllOnFocus(false) }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f)); bar.addView(Button(this).apply { text = "搜索"; PaletteManager.styleButton(this); setOnClickListener { query = input.text.toString().trim().ifBlank { "*" }; load(true) } })
        root.addView(bar); scroll = ScrollView(this); list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(24)) }; scroll.addView(list); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); progress = ProgressBar(this).apply { visibility = View.GONE }; root.addView(progress, LinearLayout.LayoutParams(-2, dp(40)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        scroll.viewTreeObserver.addOnScrollChangedListener { if (!loading && scroll.getChildAt(0).bottom - scroll.height - scroll.scrollY < dp(500)) load(false) }
        input.addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit; override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit; override fun afterTextChanged(s: Editable?) = Unit })
    */
    private val tags = mutableListOf<Tag>()
    private val filters = mutableListOf<Filter>()
    private val numberFormat = NumberFormat.getIntegerInstance(Locale.US)
    private val attachedGlass = mutableMapOf<LiquidGlassView, Float>()
    private val visibleRect = Rect()
    private val tagAdapter = TagAdapter()
    private lateinit var root: FrameLayout
    private lateinit var pageBackdrop: View
    private lateinit var resultsSurface: IosPullRefreshLayout
    private lateinit var results: RecyclerView
    private lateinit var controls: LinearLayout
    private lateinit var header: FrameLayout
    private lateinit var headerGlass: IslandGlassView
    private lateinit var toolbar: Toolbar
    private lateinit var searchRow: LinearLayout
    private lateinit var input: EditText
    private lateinit var modeSelector: LinearLayout
    private lateinit var parameterButton: LiquidGlassButton
    private lateinit var loadingPanel: LiquidGlassView
    private lateinit var loadingIndicator: IosActivityIndicator
    private lateinit var statusPanel: LiquidGlassListItem
    private var loadJob: Job? = null
    private var page = 1
    private var query = "*"
    private var mode = TagSearchMode.LOCAL_TAG
    private var requestGeneration = 0L
    private var loading = false
    private var hasMore = true
    private var failed = false
    private var resumed = false
    private var loadingVisible = false
    private var summaryText = "正在加载标签…"
    private var leftInset = 0
    private var rightInset = 0
    private var bottomInset = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = buildView()
        setContentView(root)
        applyPalette()
        ViewCompat.requestApplyInsets(root)
        load(reset = true)
    }

    private fun buildView(): FrameLayout {
        val root = FrameLayout(this).apply { isFocusableInTouchMode = true }
        pageBackdrop = View(this)
        root.addView(pageBackdrop, FrameLayout.LayoutParams(-1, -1))
        resultsSurface = IosPullRefreshLayout(this)
        results = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@TagSearchActivity)
            adapter = tagAdapter
            clipToPadding = false
            // Whole-card translations cannot be represented by the glass backdrop capture.
            itemAnimator = null
        }
        resultsSurface.addView(results, FrameLayout.LayoutParams(-1, -1))
        resultsSurface.scrollTarget = results
        resultsSurface.setCanRefresh { !loading }
        resultsSurface.setOnRefreshListener { load(reset = true, fromPull = true) }
        root.addView(resultsSurface, FrameLayout.LayoutParams(-1, -1))

        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headerGlass = IslandGlassView(this).apply {
            GlassWidgetStyle.apply(this, Ui2DesignSystem.Shape.topIsland)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        toolbar = Toolbar(this).apply {
            title = "标签"
            minimumHeight = 0
            setBackgroundColor(Color.TRANSPARENT)
            setNavigationIcon(R.drawable.ic_arrow_back)
            navigationContentDescription = "返回"
            setNavigationOnClickListener { finish() }
        }
        header = FrameLayout(this).apply {
            elevation = dp(Ui2DesignSystem.Elevation.islandDp.toInt()).toFloat()
            // The toolbar and GPU surface share the home header's rounded boundary.
            outlineProvider = headerGlass.outlineProvider
            clipToOutline = true
            addView(headerGlass, FrameLayout.LayoutParams(-1, -1))
            addView(toolbar, FrameLayout.LayoutParams(-1, -1))
        }
        controls.addView(header, LinearLayout.LayoutParams(-1, dp(60)).apply { gravity = Gravity.CENTER_HORIZONTAL })

        modeSelector = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        controls.addView(modeSelector, LinearLayout.LayoutParams(-1, dp(48)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        addModeButton("本地标签", TagSearchMode.LOCAL_TAG)
        addModeButton("云端标签", TagSearchMode.REMOTE_TAG)
        addModeButton("云端过滤器", TagSearchMode.REMOTE_FILTER)

        input = EditText(this).apply {
            hint = "搜索标签（* 表示全部）"
            setSingleLine()
            setText("*")
            setSelectAllOnFocus(false)
            setPadding(dp(16), 0, dp(16), 0)
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) { load(reset = true); true } else false
            }
        }
        val inputSurface = trackGlass(LiquidGlassView(this), resultsSurface).apply {
            addView(input, FrameLayout.LayoutParams(-1, -1))
        }
        val searchButton = trackGlass(LiquidGlassButton(this), resultsSurface).apply {
            text = "搜索"
            setOnClickListener { load(reset = true) }
        }
        parameterButton = trackGlass(LiquidGlassButton(this), resultsSurface).apply {
            text = "参数"
            setOnClickListener { showParameterMenu() }
            visibility = View.GONE
        }
        searchRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(inputSurface, LinearLayout.LayoutParams(0, dp(56), 1f))
            addView(searchButton, LinearLayout.LayoutParams(-2, dp(56)).apply { marginStart = dp(8) })
            addView(parameterButton, LinearLayout.LayoutParams(-2, dp(56)).apply { marginStart = dp(8) })
        }
        controls.addView(searchRow, LinearLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(Ui2DesignSystem.Spacing.sm)
        })
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        controls.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateContentInsets() }
        results.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) updateContentInsets()
        }

        loadingIndicator = IosActivityIndicator(this)
        loadingPanel = trackGlass(LiquidGlassView(this), resultsSurface).apply {
            visibility = View.GONE
            addView(loadingIndicator, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        }
        root.addView(loadingPanel, FrameLayout.LayoutParams(dp(80), dp(80), Gravity.CENTER))
        statusPanel = trackGlass(LiquidGlassListItem(this), pageBackdrop).apply { visibility = View.GONE }
        root.addView(statusPanel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
            setMargins(dp(24), 0, dp(24), 0)
        })

        results.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateGlassRendering()
                val manager = recyclerView.layoutManager as LinearLayoutManager
                if (dy > 0 && !failed && manager.findLastVisibleItemPosition() >= tagAdapter.itemCount - 5) load(reset = false)
            }
        })
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            leftInset = bars.left
            rightInset = bars.right
            bottomInset = maxOf(bars.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            controls.setPadding(bars.left + dp(16), bars.top + dp(Ui2DesignSystem.Spacing.sm), bars.right + dp(16), 0)
            updateContentInsets()
            insets
        }
        root.requestFocus()
        updateModeUi()
        return root
    }

    private fun addModeButton(label: String, target: TagSearchMode) {
        val button = LiquidGlassButton(this).apply {
            text = label
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener {
                if (mode == target) return@setOnClickListener
                loadJob?.cancel()
                requestGeneration++
                mode = target
                loading = false
                failed = false
                tags.clear()
                filters.clear()
                page = 1
                hasMore = true
                summaryText = modeLabel()
                statusPanel.visibility = View.GONE
                setLoadingVisible(false)
                tagAdapter.notifyDataSetChanged()
                updateModeUi()
            }
        }
        modeSelector.addView(button, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            marginStart = dp(3); marginEnd = dp(3)
        })
    }

    private fun updateModeUi() {
        if (!::modeSelector.isInitialized) return
        val colors = PaletteManager.colors(this)
        for (i in 0 until modeSelector.childCount) {
            val selected = when (i) {
                0 -> mode == TagSearchMode.LOCAL_TAG
                1 -> mode == TagSearchMode.REMOTE_TAG
                else -> mode == TagSearchMode.REMOTE_FILTER
            }
            (modeSelector.getChildAt(i) as? LiquidGlassButton)?.apply {
                alpha = if (selected) 1f else 0.62f
                setTextColor(if (selected) colors.onSurface else colors.glassSecondaryText)
            }
        }
        input.hint = when (mode) {
            TagSearchMode.LOCAL_TAG -> "搜索本地标签（字典）"
            TagSearchMode.REMOTE_TAG -> "搜索云端标签（作品搜索）"
            TagSearchMode.REMOTE_FILTER -> "输入过滤器查询，例如 creator:495pygly"
        }
        input.setText(if (mode == TagSearchMode.LOCAL_TAG && input.text.isBlank()) "" else input.text)
        parameterButton.visibility = if (mode == TagSearchMode.REMOTE_FILTER) View.VISIBLE else View.GONE
        summaryText = when (mode) {
            TagSearchMode.LOCAL_TAG -> "本地标签"
            TagSearchMode.REMOTE_TAG -> "云端标签"
            TagSearchMode.REMOTE_FILTER -> "云端过滤器"
        }
        tagAdapter.notifyDataSetChanged()
    }

    private fun updateContentInsets() {
        if (!::results.isInitialized) return
        val windowWidthPx = (resources.configuration.screenWidthDp * resources.displayMetrics.density).toInt()
        val safeWidth = ((results.width.takeIf { it > 0 } ?: windowWidthPx) - leftInset - rightInset).coerceAtLeast(1)
        val available = (safeWidth - dp(32)).coerceAtLeast(1)
        val headerWidth = AdaptiveLayoutPolicy.topIslandWidthPx(this, available)
        val contentWidth = AdaptiveLayoutPolicy.contentIslandWidthPx(this, available)
        if (header.layoutParams.width != headerWidth) header.layoutParams = header.layoutParams.apply { this.width = headerWidth }
        if (searchRow.layoutParams.width != contentWidth) searchRow.layoutParams = searchRow.layoutParams.apply { this.width = contentWidth }
        val gutter = (safeWidth - contentWidth) / 2
        results.setPadding(leftInset + gutter, controls.height + dp(8), rightInset + gutter, bottomInset + dp(16))
        resultsSurface.contentTopInset = results.paddingTop
        if (::loadingPanel.isInitialized) {
            loadingPanel.layoutParams = (loadingPanel.layoutParams as FrameLayout.LayoutParams).apply {
                gravity = (if (tags.isEmpty()) Gravity.CENTER_VERTICAL else Gravity.BOTTOM) or Gravity.LEFT
                leftMargin = leftInset + (safeWidth - width) / 2
                rightMargin = 0
                bottomMargin = if (tags.isEmpty()) 0 else bottomInset + dp(16)
            }
        }
        if (::statusPanel.isInitialized) {
            statusPanel.layoutParams = (statusPanel.layoutParams as FrameLayout.LayoutParams).apply {
                this.width = contentWidth
                gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
                leftMargin = leftInset + gutter
                rightMargin = 0
            }
        }
    }

    private fun load(reset: Boolean, fromPull: Boolean = false) {
        if (!reset && (loading || !hasMore)) return
        if (reset && mode == TagSearchMode.REMOTE_TAG && input.text.isNullOrBlank()) {
            statusPanel.headline = "请输入作品查询"
            statusPanel.supportingText = "云端标签模式会使用作品搜索链路"
            statusPanel.visibility = View.VISIBLE
            return
        }
        if (reset) {
            loadJob?.cancel()
            requestGeneration++
            query = input.text.toString().trim().ifBlank { "*" }
            page = 1
            hasMore = true
            if (!fromPull) {
                resultsSurface.isRefreshing = false
                tags.clear()
                filters.clear()
                summaryText = when (mode) {
                    TagSearchMode.LOCAL_TAG -> "正在搜索本地标签…"
                    TagSearchMode.REMOTE_TAG -> "正在请求云端标签…"
                    TagSearchMode.REMOTE_FILTER -> "正在请求云端过滤器…"
                }
                tagAdapter.notifyDataSetChanged()
                results.scrollToPosition(0)
            }
        }
        loading = true
        failed = false
        statusPanel.visibility = View.GONE
        setLoadingVisible(!fromPull)
        updateContentInsets()
        updateGlassRendering()
        val requestedPage = page
        val requestedQuery = query
        val requestedMode = mode
        val generation = requestGeneration
        loadJob = scope.launch {
            try {
                when (TagSearchRouting.handler(requestedMode)) {
                    TagSearchRouting.Handler.LOCAL_DICTIONARY -> {
                        val local = withContext(Dispatchers.IO) { TagDictionary.search(this@TagSearchActivity, requestedQuery) }
                        if (generation != requestGeneration || requestedMode != mode) return@launch
                        tags.clear()
                        tags += local.map { Tag(it.englishName, it.imageCount.toLong(), it.chineseName.takeIf { name -> name != it.englishName }.orEmpty()) }
                        hasMore = false
                        summaryText = "本地标签 · ${numberFormat.format(tags.size)} 个结果"
                        tagAdapter.notifyDataSetChanged()
                    }
                    TagSearchRouting.Handler.IMAGE_SEARCH -> {
                        // Cloud tag search is the normal image search chain. It must not
                        // read TagDictionary or the local tag cache; SearchActivity sends
                        // the query as search/images?q=... through NetworkManager.
                        if (generation != requestGeneration || requestedMode != mode) return@launch
                        startActivity(Intent(this@TagSearchActivity, SearchActivity::class.java)
                            .putExtra(SearchActivity.EXTRA_INITIAL_QUERY, requestedQuery))
                        hasMore = false
                        summaryText = "已转到云端作品搜索"
                        tagAdapter.notifyDataSetChanged()
                    }
                    TagSearchRouting.Handler.FILTER_HTML -> {
                        val result = withContext(Dispatchers.IO) { FilterSearchClient.search(this@TagSearchActivity, requestedQuery, requestedPage) }
                        if (generation != requestGeneration || requestedMode != mode) return@launch
                        if (reset) filters.clear()
                        val start = filters.size + 1
                        filters += result.filters
                        if (reset) tagAdapter.notifyDataSetChanged()
                        else if (result.filters.isNotEmpty()) tagAdapter.notifyItemRangeInserted(start, result.filters.size)
                        summaryText = "云端过滤器 · ${numberFormat.format(filters.size)} 个结果"
                        tagAdapter.notifyItemChanged(0)
                        hasMore = result.hasMore
                        if (hasMore) page = result.page + 1
                    }
                }
                val empty = if (requestedMode == TagSearchMode.REMOTE_FILTER) filters.isEmpty() else tags.isEmpty()
                if (empty) {
                    statusPanel.headline = "没有找到结果"
                    statusPanel.supportingText = when (requestedMode) {
                        TagSearchMode.LOCAL_TAG -> "本地字典中没有匹配标签"
                        TagSearchMode.REMOTE_TAG -> "云端没有找到匹配标签"
                        TagSearchMode.REMOTE_FILTER -> "云端没有找到匹配过滤器"
                    }
                    statusPanel.setOnClickListener(null)
                    statusPanel.visibility = View.VISIBLE
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: ApiTagFailure) {
                failed = true
                if (tags.isEmpty() && filters.isEmpty()) {
                    summaryText = "${modeLabel()}加载失败：${failure.diagnostic.kind.name}"
                    tagAdapter.notifyItemChanged(0)
                }
                statusPanel.headline = if (reset) "${modeLabel()}加载失败" else "加载更多失败"
                statusPanel.supportingText = "${tagFailureMessage(failure.diagnostic)} · 诊断 ${failure.diagnostic.requestId}，点击重试"
                statusPanel.setOnClickListener { load(reset = reset) }
                statusPanel.visibility = View.VISIBLE
            } catch (failure: FilterSearchClient.RequestFailedException) {
                failed = true
                summaryText = "云端过滤器请求失败：${failure.diagnostic.kind.name}"
                tagAdapter.notifyItemChanged(0)
                statusPanel.headline = "云端过滤器请求失败"
                statusPanel.supportingText = "${failure.diagnostic.summary()} · 点击重试"
                statusPanel.setOnClickListener { load(reset = true) }
                statusPanel.visibility = View.VISIBLE
            } catch (_: FilterSearchClient.LoginRequiredException) {
                failed = true
                summaryText = "云端过滤器需要登录"
                tagAdapter.notifyItemChanged(0)
                statusPanel.headline = "需要登录"
                statusPanel.supportingText = "my:filters 需要有效登录状态，点击重试"
                statusPanel.setOnClickListener { load(reset = true) }
                statusPanel.visibility = View.VISIBLE
            } catch (_: FilterSearchClient.HtmlPageException) {
                failed = true
                summaryText = "云端过滤器页面暂不可解析"
                tagAdapter.notifyItemChanged(0)
                statusPanel.headline = "服务器返回了验证页面"
                statusPanel.supportingText = "请稍后重试"
                statusPanel.setOnClickListener { load(reset = true) }
                statusPanel.visibility = View.VISIBLE
            } catch (failure: Exception) {
                failed = true
                if (tags.isEmpty() && filters.isEmpty()) {
                    summaryText = "${modeLabel()}响应格式异常"
                    tagAdapter.notifyItemChanged(0)
                }
                val diagnostic = ApiRequestDiagnostic(
                    requestId = "parse-${System.currentTimeMillis().toString(16).takeLast(6)}",
                    operation = when (mode) { TagSearchMode.REMOTE_FILTER -> "site:filters"; else -> "search/tags" }, host = "derpibooru.org", phase = "parse",
                    kind = ApiFailureKind.INVALID_JSON, elapsedMs = 0, attempt = 1,
                    maxAttempts = 1, networkConfigurationVersion = NetworkManager.configurationVersion.value, retryable = false,
                    rootCause = failure.javaClass.simpleName
                )
                NetworkManager.recordClientDiagnostic(diagnostic)
                statusPanel.headline = if (reset) "${modeLabel()}加载失败" else "加载更多失败"
                statusPanel.supportingText = "服务器响应格式异常 · 诊断 ${diagnostic.requestId}，点击重试"
                statusPanel.setOnClickListener { load(reset = reset) }
                statusPanel.visibility = View.VISIBLE
            } finally {
                // A cancelled search must not hide the next search's indicator.
                if (currentCoroutineContext().isActive) {
                    loading = false
                    setLoadingVisible(false)
                    resultsSurface.isRefreshing = false
                    updateGlassRendering()
                }
            }
        }
    }

    private fun modeLabel(): String = when (mode) {
        TagSearchMode.LOCAL_TAG -> "本地标签"
        TagSearchMode.REMOTE_TAG -> "云端标签"
        TagSearchMode.REMOTE_FILTER -> "云端过滤器"
    }

    private fun showParameterMenu() {
        val entries = listOf(
            "名称：name:" to "name:", "创建者：creator:" to "creator:",
            "描述：description:\"\"" to "description:\"\"", "创建时间：created_at:" to "created_at:",
            "过滤器 ID：id:" to "id:", "剧透标签数量：spoilered_count:" to "spoilered_count:",
            "隐藏标签数量：hidden_count:" to "hidden_count:", "我的过滤器：my:filters" to "my:filters",
            "系统过滤器：system:true" to "system:true", "公共过滤器：public:true" to "public:true",
            "私人过滤器：public:false" to "public:false", "用户 ID：user_id:" to "user_id:"
        )
        AlertDialog.Builder(this).setTitle("快速填入参数")
            .setItems(entries.map { it.first }.toTypedArray()) { dialog, which ->
                val value = entries[which].second
                val cursorOffset = if (value == "description:\"\"") value.length - 1 else value.length
                val inserted = FilterSearchInput.insert(input.text.toString(), input.selectionStart, input.selectionEnd, value, cursorOffset)
                input.setText(inserted.text)
                input.setSelection(inserted.cursor)
                input.requestFocus()
                dialog.dismiss()
            }.setNegativeButton("取消", null).show()
    }

    private class ApiTagFailure(val diagnostic: ApiRequestDiagnostic) : RuntimeException()

    private fun tagFailureMessage(diagnostic: ApiRequestDiagnostic): String = when (diagnostic.kind) {
        ApiFailureKind.DNS -> "无法解析服务器地址"
        ApiFailureKind.TCP, ApiFailureKind.CONNECT_TIMEOUT -> "无法连接服务器"
        ApiFailureKind.READ_TIMEOUT -> "服务器响应超时"
        ApiFailureKind.RATE_LIMITED -> "请求过于频繁，请稍后再试"
        ApiFailureKind.HTTP_4XX -> "服务器拒绝请求（HTTP ${diagnostic.statusCode ?: "4xx"}）"
        ApiFailureKind.HTTP_5XX -> "服务器暂时异常（HTTP ${diagnostic.statusCode ?: "5xx"}）"
        ApiFailureKind.BUSINESS_ERROR -> "服务器返回了业务错误，请检查搜索条件"
        ApiFailureKind.CONFIGURATION_CHANGED -> "网络配置已变化"
        else -> "标签请求失败"
    }

    private inner class TagAdapter : RecyclerView.Adapter<TagHolder>() {
        override fun getItemCount(): Int = (if (mode == TagSearchMode.REMOTE_FILTER) filters.size else tags.size) + 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TagHolder {
            val row = trackGlass(LiquidGlassListItem(parent.context), pageBackdrop, 16f).apply {
                headlineTextView.maxLines = 2
                supportingTextView.maxLines = Int.MAX_VALUE
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            }
            return TagHolder(row)
        }

        override fun onBindViewHolder(holder: TagHolder, position: Int) {
            GlassWidgetStyle.apply(holder.row, 16f)
            if (position == 0) {
                holder.row.headline = summaryText
                holder.row.supportingText = null
                holder.row.trailingText = null
                holder.row.setOnClickListener(null)
            } else if (mode == TagSearchMode.REMOTE_FILTER) {
                val filter = filters[position - 1]
                holder.row.headline = filter.name
                holder.row.supportingText = buildString {
                    append("维护者：").append(filter.creator ?: "未知")
                    if (filter.description.isNotBlank()) append("\n").append(filter.description)
                }
                holder.row.trailingText = "剧透 ${if (filter.spoilerCount >= 0) filter.spoilerCount else "?"} · 隐藏 ${if (filter.hiddenCount >= 0) filter.hiddenCount else "?"}"
                holder.row.setOnClickListener {
                    startActivity(Intent(this@TagSearchActivity, FilterDetailsActivity::class.java)
                        .putExtra(FilterDetailsActivity.EXTRA_FILTER_ID, filter.id))
                }
            } else {
                val tag = tags[position - 1]
                holder.row.headline = tag.name
                holder.row.supportingText = tag.description.takeIf { it.isNotBlank() }
                holder.row.trailingText = "${numberFormat.format(tag.images)} 张"
                holder.row.setOnClickListener {
                    startActivity(Intent(this@TagSearchActivity, SearchActivity::class.java)
                        .putExtra(SearchActivity.EXTRA_INITIAL_QUERY, tag.name))
                }
            }
        }
    }

    private class TagHolder(val row: LiquidGlassListItem) : RecyclerView.ViewHolder(row)

    private fun <T : LiquidGlassView> trackGlass(view: T, source: View, radius: Float = 24f): T {
        GlassWidgetStyle.apply(view, radius)
        view.backdropSource = source
        GlassWidgetStyle.setRenderingActive(view, false, null)
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                attachedGlass[view] = radius
                GlassWidgetStyle.apply(view, radius)
                view.post { updateGlassRendering() }
            }
            override fun onViewDetachedFromWindow(v: View) {
                attachedGlass.remove(view)
                GlassWidgetStyle.setRenderingActive(view, false, null)
            }
        })
        return view
    }

    private fun updateGlassRendering() {
        headerGlass.setRenderingActive(resumed && header.isShown, resultsSurface)
        attachedGlass.keys.toList().forEach { glass ->
            val active = resumed && glass.isShown && glass.getGlobalVisibleRect(visibleRect)
            GlassWidgetStyle.setRenderingActive(glass, active, resultsSurface)
        }
    }

    private fun setLoadingVisible(visible: Boolean) {
        loadingVisible = visible
        if (!::loadingPanel.isInitialized || !::loadingIndicator.isInitialized) return
        loadingPanel.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible && resumed) loadingIndicator.post { loadingIndicator.startLoading() }
        else if (!visible) loadingIndicator.stopLoading()
    }

    private fun applyPalette() {
        val colors = PaletteManager.colors(this)
        pageBackdrop.setBackgroundColor(colors.surface)
        resultsSurface.setBackgroundColor(colors.surface)
        window.statusBarColor = colors.surface
        window.navigationBarColor = colors.surface
        WindowCompat.getInsetsController(window, root).apply {
            val light = Color.luminance(colors.surface) > 0.5f
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        headerGlass.setPalette(colors)
        val foreground = colors.glassText
        toolbar.setTitleTextColor(colors.glassText)
        toolbar.navigationIcon?.mutate()?.setTint(foreground)
        input.setTextColor(colors.glassText)
        input.setHintTextColor(colors.glassSecondaryText)
        loadingIndicator.applyPalette(colors)
        resultsSurface.applyPalette(colors)
        attachedGlass.toMap().forEach { (glass, radius) -> GlassWidgetStyle.apply(glass, radius) }
        tagAdapter.notifyDataSetChanged()
        updateGlassRendering()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        applyPalette()
        if (loadingVisible) loadingIndicator.post { loadingIndicator.startLoading() }
    }

    override fun onPause() {
        resumed = false
        if (::loadingIndicator.isInitialized) loadingIndicator.stopLoading()
        updateGlassRendering()
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        headerGlass.setRenderingActive(false, null)
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
