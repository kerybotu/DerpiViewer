package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayout
import com.kerybotu.derpibooru.mirror.*
import com.kerybotu.derpibooru.mirror.auth.ApiKeyStore
import com.kerybotu.derpibooru.mirror.databinding.ActivityFiltersBinding
import com.kerybotu.derpibooru.mirror.databinding.ItemFiltersHeaderBinding
import com.kerybotu.derpibooru.mirror.model.Filter
import com.kerybotu.derpibooru.mirror.model.FilterImportParser
import com.kerybotu.derpibooru.mirror.model.FilterJsonCodec
import com.kerybotu.derpibooru.mirror.model.FilterSearchInput
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import kotlinx.coroutines.*

/** 2.0 Filters screen. One RecyclerView owns the complete scroll range. */
class FilterActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFiltersBinding
    private lateinit var header: ItemFiltersHeaderBinding
    private lateinit var adapter: FilterAdapter
    private lateinit var headerAdapter: FilterHeaderAdapter
    private lateinit var islandMotion: HomeTopIslandController
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var searchJob: Job? = null
    private var allFilters = emptyList<Filter>()
    private var systemFilters = emptyList<Filter>()
    private var userFilters = emptyList<Filter>()
    private var localFilters = emptyList<Filter>()
    private enum class Source { SYSTEM, USER, LOCAL, SERVER }
    private var source = Source.SYSTEM
    private var serverQuery = ""
    private var serverPage = 1
    private var serverHasMore = false
    private var serverLoading = false
    private var initialContentOffsetApplied = false
    private val prefs by lazy { getSharedPreferences("filter_state", Context.MODE_PRIVATE) }
    private val currentId: Int? get() = AppSettings.getCurrentFilterId(this) ?: AppSettings.getCurrentLocalFilterId(this)
    private val palette get() = PaletteManager.colors(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFiltersBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AdaptiveNavigationShell.install(this, binding.root, AdaptiveNavigationShell.HOME)
        PaletteManager.apply(this)
        binding.root.setBackgroundColor(palette.surface)
        configureTopIsland()
        setSupportActionBar(binding.filterToolbar)
        // AppCompat may install its own navigation callback while registering
        // the Toolbar as the support ActionBar, so bind the back action after
        // that registration as well as in configureTopIsland().
        binding.filterToolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        configureFabAndInsets()

        binding.filterList.layoutManager = LinearLayoutManager(this)
        header = ItemFiltersHeaderBinding.inflate(layoutInflater, binding.filterList, false)
        headerAdapter = FilterHeaderAdapter(header.root)
        adapter = FilterAdapter(
            emptyList(), { currentId }, { useFilter(it) },
            onView = { openDetails(it) },
            onCustomize = { customize(it) },
            onEdit = { openWebEdit(it) }
        )
        binding.filterList.adapter = ConcatAdapter(headerAdapter, adapter)
        binding.filterList.clipToPadding = false
        binding.filterList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                islandMotion.onScrolled(dy)
                if (source == Source.SERVER && serverHasMore && !serverLoading && !recyclerView.canScrollVertically(1)) {
                    searchServerPage(serverPage + 1, append = true)
                }
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) islandMotion.onScrollIdle(recyclerView)
            }
        })
        configureHeader()
        islandMotion = HomeTopIslandController(binding.filterToolbar, null)
        binding.filterToolbar.post {
            islandMotion.onLayoutChanged()
            updateInitialContentOffset()
        }

        FilterCache.getSystemFilters(this)?.let {
            systemFilters = parseFilters(it)
            showFilterSource()
        }
        localFilters = FilterStore.getLocalFilters(this)
        loadFilters()
        if (!prefs.getBoolean("notice_seen", false)) {
            binding.root.post { showSafetyNotice() }
            prefs.edit().putBoolean("notice_seen", true).apply()
        }
    }

    private fun configureTopIsland() {
        binding.filterToolbar.apply {
            title = "过滤器"
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationOnClickListener { finishAfterTransition() }
            applyUi2Appearance()
            setTitleTextColor(palette.onSurface)
            navigationIcon?.setTint(palette.onSurface)
        }
        binding.filterToolbar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateInitialContentOffset()
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.filterToolbar) { view, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            (view.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.topMargin = status + dp(Ui2DesignSystem.Spacing.sm)
                view.layoutParams = params
            }
            updateInitialContentOffset()
            insets
        }
        ViewCompat.requestApplyInsets(binding.filterToolbar)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun configureFabAndInsets() {
        Ui2DesignSystem.applyPressFeedback(binding.addFilter)
        binding.addFilter.backgroundTintList = android.content.res.ColorStateList.valueOf(palette.primary)
        binding.addFilter.setOnClickListener { showCreateSheet() }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = maxOf(bars.bottom, ime.bottom)
            (binding.addFilter.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.bottomMargin = bottom + dp(Ui2DesignSystem.Spacing.lg)
                params.marginEnd = bars.right + dp(Ui2DesignSystem.Spacing.lg)
                binding.addFilter.layoutParams = params
            }
            val listBottom = bottom + dp(96)
            binding.filterList.setPadding(binding.filterList.paddingLeft, binding.filterList.paddingTop, binding.filterList.paddingRight, listBottom)
            updateInitialContentOffset()
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun updateInitialContentOffset() {
        if (!::islandMotion.isInitialized || !::header.isInitialized || binding.filterToolbar.height <= 0) return
        val top = (binding.filterToolbar.y + binding.filterToolbar.height).toInt() +
            dp(Ui2DesignSystem.Spacing.md)
        // RecyclerView is the sole scroll container. Keep its viewport full
        // screen and put the measured island clearance on the header item so
        // the first content starts below the overlay without reserving a
        // permanent non-scrollable band in the list.
        if (binding.filterList.paddingTop != 0) {
            binding.filterList.setPadding(
                binding.filterList.paddingLeft,
                0,
                binding.filterList.paddingRight,
                binding.filterList.paddingBottom
            )
        }
        if (header.root.paddingTop != top) {
            header.root.setPadding(
                header.root.paddingLeft,
                top,
                header.root.paddingRight,
                header.root.paddingBottom
            )
        }
        if (!initialContentOffsetApplied) {
            initialContentOffsetApplied = true
            binding.filterList.post {
                header.root.alpha = 0f
                header.root.translationY = dp(12).toFloat()
                header.root.animate().alpha(1f).translationY(0f)
                    .setDuration(Ui2DesignSystem.Motion.normalMs)
                    .setInterpolator(Ui2DesignSystem.Motion.standard)
                    .start()
            }
        }
    }

    private fun configureHeader() {
        Ui2DesignSystem.styleIsland(header.currentFilterCard, palette, 22f)
        Ui2DesignSystem.styleIsland(header.filterTabs, palette, Ui2DesignSystem.Shape.pill)
        Ui2DesignSystem.styleIsland(header.filterSearchContainer, palette, 22f)
        val muted = palette.muted
        header.currentFilterLabel.setTextColor(muted)
        header.currentFilterName.setTextColor(palette.onSurface)
        header.changeFilter.setTextColor(palette.primary)
        header.filterSearch.setTextColor(palette.onSurface)
        header.filterSearch.setHintTextColor(muted)
        header.filterSearchIcon.imageTintList = android.content.res.ColorStateList.valueOf(muted)
        header.filterSearchAction.imageTintList = android.content.res.ColorStateList.valueOf(palette.primary)
        header.filterTabs.setTabTextColors(muted, palette.onSurface)
        header.filterTabs.setSelectedTabIndicatorColor(palette.primary)
        header.filterTabs.removeAllTabs()
        header.filterTabs.addTab(header.filterTabs.newTab().setText("系统过滤器"))
        header.filterTabs.addTab(header.filterTabs.newTab().setText("我的过滤器"))
        header.filterTabs.addTab(header.filterTabs.newTab().setText("本地自定义"))
        header.filterTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                source = when (tab.position) {
                    1 -> Source.USER
                    2 -> Source.LOCAL
                    else -> Source.SYSTEM
                }
                showFilterSource()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        header.currentFilterName.text = AppSettings.getCurrentFilterName(this) ?: prefs.getString("current_name", "默认安全过滤器")
        header.changeFilter.setOnClickListener { showCurrentChooser() }
        header.filterSearchAction.setOnClickListener { triggerSearch() }
        header.filterParameterAction.setTextColor(palette.primary)
        header.filterParameterAction.setOnClickListener { showParameterMenu() }
        header.filterSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { triggerSearch(); true } else false
        }
        header.filterSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (searchJob?.isActive != true) filterLocal(s?.toString().orEmpty())
            }
        })
    }

    private fun loadFilters() {
        binding.filterLoading.visibility = View.VISIBLE
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { NetworkManager.getApi(this@FilterActivity, "filters/system?page=1") }
                result?.let { systemFilters = parseFilters(it); FilterCache.saveSystemFilters(this@FilterActivity, it) }
                if (ApiKeyStore.isLoggedIn(this@FilterActivity)) {
                    val userResult = withContext(Dispatchers.IO) { NetworkManager.getApi(this@FilterActivity, "filters/user?page=1") }
                    userFilters = userResult?.let(::parseFilters).orEmpty()
                }
                localFilters = FilterStore.getLocalFilters(this@FilterActivity)
                showFilterSource()
            } catch (_: Throwable) {
                if (allFilters.isEmpty()) showEmpty("过滤器加载失败，请下拉或重新打开页面重试")
            } finally {
                binding.filterLoading.visibility = View.GONE
            }
        }
    }

    private fun showFilterSource() {
        allFilters = when (source) {
            Source.USER -> userFilters
            Source.LOCAL -> localFilters
            else -> systemFilters
        }
        if (source == Source.USER && !ApiKeyStore.isLoggedIn(this)) {
            adapter.update(emptyList())
            showEmpty("登录后可以查看我的过滤器")
            return
        }
        filterLocal(header.filterSearch.text?.toString().orEmpty())
    }

    private fun triggerSearch() {
        searchJob?.cancel()
        val normalized = header.filterSearch.text?.toString().orEmpty().trim()
        if (normalized.isBlank()) { showFilterSource(); return }
        source = Source.SERVER
        serverQuery = normalized
        serverPage = 1
        searchServerPage(1, append = false)
    }

    private fun showParameterMenu() {
        val entries = listOf(
            "名称：name:" to "name:",
            "创建者：creator:" to "creator:",
            "描述：description:\"\"" to "description:\"\"",
            "创建时间：created_at:" to "created_at:",
            "过滤器 ID：id:" to "id:",
            "剧透标签数量：spoilered_count:" to "spoilered_count:",
            "隐藏标签数量：hidden_count:" to "hidden_count:",
            "我的过滤器：my:filters" to "my:filters",
            "系统过滤器：system:true" to "system:true",
            "公共过滤器：public:true" to "public:true",
            "私人过滤器：public:false" to "public:false",
            "用户 ID：user_id:" to "user_id:"
        )
        AlertDialog.Builder(this)
            .setTitle("快速填入参数")
            .setItems(entries.map { it.first }.toTypedArray()) { dialog, which ->
                val syntax = entries[which].second
                val cursorOffset = if (syntax == "description:\"\"") syntax.length - 1 else syntax.length
                insertSearchParameter(syntax, cursorOffset)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun insertSearchParameter(value: String, cursorOffset: Int) {
        val input = header.filterSearch
        val insertion = FilterSearchInput.insert(
            input.text?.toString().orEmpty(), input.selectionStart, input.selectionEnd, value, cursorOffset
        )
        input.setText(insertion.text)
        input.setSelection(insertion.cursor)
        input.requestFocus()
    }

    private fun searchServerPage(page: Int, append: Boolean) {
        searchJob = scope.launch {
            serverLoading = true
            binding.filterEmpty.visibility = View.GONE
            binding.filterLoading.visibility = View.VISIBLE
            try {
                val result = withContext(Dispatchers.IO) { FilterSearchClient.search(this@FilterActivity, serverQuery, page) }
                val merged = if (append) allFilters + result.filters else result.filters
                allFilters = merged.distinctBy { it.id }
                adapter.update(allFilters)
                serverPage = result.page
                serverHasMore = result.hasMore
                if (allFilters.isEmpty()) showEmpty("服务器没有找到匹配的过滤器")
            } catch (_: FilterSearchClient.LoginRequiredException) {
                adapter.update(emptyList())
                showEmpty("my:filters 需要登录 API key")
            } catch (failure: FilterSearchClient.RequestFailedException) {
                adapter.update(emptyList())
                showEmpty("服务器搜索失败：${failure.diagnostic.kind.name}（诊断编号 ${failure.diagnostic.requestId}）")
            } catch (failure: FilterSearchClient.HtmlPageException) {
                adapter.update(emptyList())
                if (failure.message == "access_denied") {
                    showEmpty("服务器暂时拒绝访问过滤器搜索；这不是无匹配结果，请稍后重试")
                } else {
                    showEmpty("服务器返回了验证页面，暂时无法解析过滤器结果；请完成验证后重试")
                }
            } catch (_: Throwable) {
                adapter.update(emptyList())
                showEmpty("服务器过滤器搜索失败，请重试")
            } finally {
                serverLoading = false
                binding.filterLoading.visibility = View.GONE
                if (adapter.itemCount > 0) binding.filterEmpty.visibility = View.GONE
            }
        }
    }

    private fun showEmpty(message: String) {
        binding.filterEmpty.text = message
        binding.filterEmpty.visibility = View.VISIBLE
    }

    private fun parseFilters(json: String): List<Filter> = FilterJsonCodec.parseList(json)

    private fun filterLocal(query: String) {
        val value = query.trim()
        val result = allFilters.filter { value.isBlank() || it.name.contains(value, true) || it.description.contains(value, true) || it.id.toString() == value }
        adapter.update(result)
        binding.filterEmpty.visibility = if (result.isEmpty() && binding.filterLoading.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        if (result.isEmpty() && value.isNotBlank()) binding.filterEmpty.text = "没有找到符合条件的过滤器"
    }

    private fun useFilter(filter: Filter) {
        if (filter.isLocal) {
            val localQuery = filter.localQuery.orEmpty()
            if (localQuery.isBlank() && filter.sourceId != null) {
                AppSettings.setCurrentFilter(this, filter.sourceId, filter.name)
            } else {
                AppSettings.setCurrentLocalFilter(this, filter.id, filter.name, localQuery)
            }
        } else {
            AppSettings.setCurrentFilter(this, filter.id, filter.name)
        }
        header.currentFilterName.text = filter.name
        adapter.notifyDataSetChanged()
        Toast.makeText(this, "已使用过滤器：${filter.name}", Toast.LENGTH_SHORT).show()
    }

    private fun showCurrentChooser() {
        if (allFilters.isEmpty()) { Toast.makeText(this, "当前列表没有可切换的过滤器", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle("切换当前过滤器").setItems(allFilters.map { it.name }.toTypedArray()) { _, which -> useFilter(allFilters[which]) }.show()
    }

    private fun openDetails(filter: Filter) {
        startActivity(Intent(this, FilterDetailsActivity::class.java).apply {
            if (!filter.isLocal && filter.id > 0) putExtra(FilterDetailsActivity.EXTRA_FILTER_ID, filter.id)
            else putExtra(FilterDetailsActivity.EXTRA_FILTER_JSON, FilterJsonCodec.toJson(filter).toString())
        })
    }

    private fun customize(filter: Filter) {
        startActivity(Intent(this, FilterEditorActivity::class.java).apply {
            putExtra(FilterEditorActivity.EXTRA_FILTER_JSON, FilterJsonCodec.toJson(filter).toString())
        })
    }

    private fun openWebEdit(filter: Filter) {
        val path = filter.remoteEditPath ?: if (filter.id > 0) "/filters/${filter.id}/edit" else null
        if (path == null) { customize(filter); return }
        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://${AppSettings.getTargetDomain(this)}$path")))
    }

    private fun showSafetyNotice() {
        AlertDialog.Builder(this).setTitle("内容安全提示").setMessage("过滤器会影响图片的剧透和隐藏显示。请确认你了解当前过滤器的作用，并根据使用场景选择合适的设置。")
            .setPositiveButton("了解并继续", null).show()
    }

    private fun showCreateSheet() {
        val dialog = BottomSheetDialog(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(32)) }
        val name = EditText(this).apply { hint = "过滤器名称" }
        val tags = EditText(this).apply { hint = "快速添加标签，例如 safe, cute" }
        box.addView(name, LinearLayout.LayoutParams(-1, -2)); box.addView(tags, LinearLayout.LayoutParams(-1, -2))
        box.addView(Button(this).apply {
            text = "打开自定义编辑器"
            PaletteManager.styleButton(this)
            setOnClickListener {
                startActivity(Intent(this@FilterActivity, FilterEditorActivity::class.java))
                dialog.dismiss()
            }
        })
        box.addView(Button(this).apply {
            text = "从服务器导入 ID 或 URL"
            PaletteManager.styleButton(this)
            setOnClickListener {
                dialog.dismiss()
                showImportDialog()
            }
        })
        dialog.setContentView(box); dialog.show()
    }

    private fun showImportDialog() {
        val input = EditText(this).apply { hint = "例如 215340 或 https://derpibooru.org/filters/215340" }
        AlertDialog.Builder(this).setTitle("导入服务器过滤器")
            .setView(input).setNegativeButton("取消", null)
            .setPositiveButton("获取详情") { _, _ ->
                val id = FilterImportParser.parseId(input.text.toString(), setOf(AppSettings.getTargetDomain(this), "derpibooru.org", "trixiebooru.org"))
                if (id == null) Toast.makeText(this, "请输入有效的过滤器 ID 或同站点过滤器 URL", Toast.LENGTH_LONG).show()
                else startActivity(Intent(this, FilterDetailsActivity::class.java).putExtra(FilterDetailsActivity.EXTRA_FILTER_ID, id))
            }.show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean { menuInflater.inflate(R.menu.filter_menu, menu); return true }
    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_filter_info -> { showSafetyNotice(); true }
        R.id.action_filter_clear_recent -> {
            AlertDialog.Builder(this).setTitle("清空近期过滤器？").setMessage("这只会清理本地记录，不会删除服务器数据。")
                .setNegativeButton("取消", null).setPositiveButton("清空") { _, _ ->
                    AppSettings.clearCurrentFilter(this)
                    header.currentFilterName.text = "默认安全过滤器"
                    adapter.notifyDataSetChanged()
                }.show(); true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onResume() {
        super.onResume()
        if (!::adapter.isInitialized) return
        localFilters = FilterStore.getLocalFilters(this)
        if (source == Source.LOCAL) showFilterSource()
        header.currentFilterName.text = AppSettings.getCurrentFilterName(this) ?: "默认安全过滤器"
    }
    override fun onDestroy() { searchJob?.cancel(); scope.cancel(); super.onDestroy() }

    /** Keeps the header in the same single RecyclerView scroll range as cards. */
    private class FilterHeaderAdapter(private val header: View) : RecyclerView.Adapter<FilterHeaderAdapter.Holder>() {
        class Holder(view: View) : RecyclerView.ViewHolder(view)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            (header.parent as? ViewGroup)?.removeView(header)
            return Holder(header)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) = Unit
        override fun getItemCount(): Int = 1
    }
}
