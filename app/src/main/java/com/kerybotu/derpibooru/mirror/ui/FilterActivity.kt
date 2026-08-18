package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayout
import com.kerybotu.derpibooru.mirror.*
import com.kerybotu.derpibooru.mirror.databinding.ActivityFiltersBinding
import com.kerybotu.derpibooru.mirror.auth.ApiKeyStore
import com.kerybotu.derpibooru.mirror.model.Filter
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import kotlinx.coroutines.*
import org.json.JSONObject

class FilterActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFiltersBinding
    private lateinit var adapter: FilterAdapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var searchJob: Job? = null
    private var allFilters = emptyList<Filter>()
    private var systemFilters = emptyList<Filter>()
    private var userFilters = emptyList<Filter>()
    private var showingUserFilters = false
    private val prefs by lazy { getSharedPreferences("filter_state", Context.MODE_PRIVATE) }
    private val currentId: Int? get() = prefs.getInt("current_id", -1).takeIf { it > 0 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFiltersBinding.inflate(layoutInflater)
        setContentView(binding.root)
        PaletteManager.apply(this)
        val palette = PaletteManager.colors(this)
        binding.root.setBackgroundColor(palette.surface)
        binding.currentFilterCard.setBackgroundColor(palette.surfaceVariant)
        val toolbar = binding.filterToolbar.appToolbar
        setSupportActionBar(toolbar)
        toolbar.title = "过滤器"
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        adapter = FilterAdapter(emptyList(), { currentId }) { useFilter(it) }
        binding.currentFilterName.text = prefs.getString("current_name", "默认安全过滤器")
        binding.filterList.layoutManager = LinearLayoutManager(this)
        binding.filterList.adapter = adapter
        binding.filterRefresh.setOnRefreshListener { loadFilters() }
        binding.filterSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = Unit
        })
        binding.filterSearchContainer.setEndIconOnClickListener { triggerSearch() }
        binding.filterSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { triggerSearch(); true } else false
        }
        binding.filterHelp.setOnClickListener { showSearchHelp() }
        binding.changeFilter.setOnClickListener { showCurrentChooser() }
        binding.addFilter.setOnClickListener { showCreateSheet() }
        setupFilterTabs()
        FilterCache.getSystemFilters(this)?.let {
            systemFilters = parseFilters(it)
            showFilterSource()
        }
        loadFilters()
        if (!prefs.getBoolean("notice_seen", false)) {
            binding.root.post { showSafetyNotice() }
            prefs.edit().putBoolean("notice_seen", true).apply()
        }
    }

    private fun loadFilters() {
        binding.filterRefresh.isRefreshing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { NetworkManager.getApi(this@FilterActivity, "filters/system?page=1") }
            result?.let { systemFilters = parseFilters(it); FilterCache.saveSystemFilters(this@FilterActivity, it) }
            if (ApiKeyStore.isLoggedIn(this@FilterActivity)) {
                val userResult = withContext(Dispatchers.IO) { NetworkManager.getApi(this@FilterActivity, "filters/user?page=1") }
                userFilters = userResult?.let(::parseFilters).orEmpty()
            }
            showFilterSource()
            binding.filterRefresh.isRefreshing = false
        }
    }

    private fun setupFilterTabs() {
        binding.filterTabs.apply {
            visibility = View.VISIBLE
            addTab(newTab().setText("系统过滤器"))
            if (ApiKeyStore.isLoggedIn(this@FilterActivity)) addTab(newTab().setText("我的过滤器"))
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    showingUserFilters = tab.position == 1 && ApiKeyStore.isLoggedIn(this@FilterActivity)
                    showFilterSource()
                }
                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        }
    }

    private fun showFilterSource() {
        allFilters = if (showingUserFilters) userFilters else systemFilters
        filterLocal(binding.filterSearch.text?.toString().orEmpty())
    }

    private fun triggerSearch() {
        searchJob?.cancel()
        val normalized = binding.filterSearch.text?.toString().orEmpty().trim()
        if (normalized.isBlank()) {
            showFilterSource()
            return
        }
        val filterId = normalized.toIntOrNull()
        if (filterId == null || filterId <= 0) {
            adapter.update(emptyList())
            binding.filterEmpty.text = "请输入有效的过滤器 ID"
            binding.filterEmpty.visibility = View.VISIBLE
            return
        }
        if (ApiKeyStore.get(this).isNullOrBlank()) {
            adapter.update(emptyList())
            binding.filterEmpty.text = "查询过滤器需要登录 API key"
            binding.filterEmpty.visibility = View.VISIBLE
            Toast.makeText(this, "请先登录后再查询过滤器", Toast.LENGTH_SHORT).show()
            return
        }
        searchJob = scope.launch {
            binding.filterList.visibility = View.INVISIBLE
            binding.filterEmpty.visibility = View.GONE
            binding.filterLoading.visibility = View.VISIBLE
            val remote = withContext(Dispatchers.IO) {
                NetworkManager.getApi(this@FilterActivity, "filters/$filterId")
            }
            if (remote != null) {
                val filter = parseFilterResponse(remote)
                adapter.update(filter?.let(::listOf).orEmpty())
            } else {
                filterLocal(normalized)
            }
            binding.filterLoading.visibility = View.GONE
            binding.filterList.visibility = View.VISIBLE
            binding.filterEmpty.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
        }
    }

    private fun parseFilters(json: String): List<Filter> {
        val arr = JSONObject(json).optJSONArray("filters") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Filter(
                o.optInt("id"), o.optString("name", "未命名过滤器"), o.optString("description", ""),
                if (o.isNull("user_id")) null else o.optInt("user_id"), o.optBoolean("system"),
                o.optBoolean("public"), o.optJSONArray("spoilered_tag_ids")?.length() ?: 0,
                o.optJSONArray("hidden_tag_ids")?.length() ?: 0,
                o.optString("creator", o.optString("user_name", null)), o.optString("created_at", null)
            )
        }
    }

    private fun parseFilterResponse(json: String): Filter? {
        val filter = JSONObject(json).optJSONObject("filter") ?: return null
        return Filter(
            id = filter.optInt("id"),
            name = filter.optString("name", "未命名过滤器"),
            description = filter.optString("description", ""),
            userId = if (filter.isNull("user_id")) null else filter.optInt("user_id"),
            system = filter.optBoolean("system"),
            public = filter.optBoolean("public"),
            spoilerCount = filter.optJSONArray("spoilered_tag_ids")?.length() ?: 0,
            hiddenCount = filter.optJSONArray("hidden_tag_ids")?.length() ?: 0,
            creator = filter.optString("creator", filter.optString("user_name", null)),
            createdAt = filter.optString("created_at", null)
        )
    }

    private fun filterLocal(query: String) = adapter.update(allFilters.filter { query.isBlank() || it.name.contains(query, true) || it.description.contains(query, true) })

    private fun useFilter(filter: Filter) {
        prefs.edit().putInt("current_id", filter.id).putString("current_name", filter.name).apply()
        binding.currentFilterName.text = filter.name
        com.kerybotu.derpibooru.mirror.AppSettings.setCurrentFilterId(this, filter.id)
        adapter.notifyDataSetChanged()
        Toast.makeText(this, "已使用过滤器：${filter.name}", Toast.LENGTH_SHORT).show()
    }

    private fun showCurrentChooser() {
        if (allFilters.isEmpty()) return
        AlertDialog.Builder(this).setTitle("切换当前过滤器").setItems(allFilters.map { it.name }.toTypedArray()) { _, which -> useFilter(allFilters[which]) }.show()
    }

    private fun showSafetyNotice() {
        AlertDialog.Builder(this).setTitle("内容安全提示").setMessage("过滤器会影响图片的剧透和隐藏显示。请确认你了解当前过滤器的作用，并根据使用场景选择合适的设置。")
            .setPositiveButton("了解并继续", null).show()
    }

    private fun showSearchHelp() = AlertDialog.Builder(this)
        .setTitle("查询过滤器")
        .setMessage("请输入过滤器 ID，例如 56027。查询会调用需要登录 key 的过滤器详情接口。")
        .setPositiveButton("知道了", null)
        .show()

    private fun showCreateSheet() {
        val dialog = BottomSheetDialog(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 32) }
        val name = EditText(this).apply { hint = "过滤器名称" }
        val tags = EditText(this).apply { hint = "快速添加标签，例如 safe, cute" }
        box.addView(name); box.addView(tags)
        box.addView(Button(this).apply { text = "保存过滤器"; setOnClickListener { Toast.makeText(this@FilterActivity, "登录后才能创建过滤器", Toast.LENGTH_SHORT).show(); dialog.dismiss() } })
        dialog.setContentView(box); dialog.show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean { menuInflater.inflate(R.menu.filter_menu, menu); return true }
    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_filter_info -> { showSafetyNotice(); true }
        R.id.action_filter_clear_recent -> { AlertDialog.Builder(this).setTitle("清空近期过滤器？").setMessage("这只会清理本地记录，不会删除服务器数据。").setNegativeButton("取消", null).setPositiveButton("清空") { _, _ -> prefs.edit().remove("current_id").remove("current_name").apply(); AppSettings.setCurrentFilterId(this, null); binding.currentFilterName.text = "默认安全过滤器"; adapter.notifyDataSetChanged() }.show(); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onDestroy() { searchJob?.cancel(); scope.cancel(); super.onDestroy() }
}
