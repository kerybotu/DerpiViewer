package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayout
import com.kerybotu.derpibooru.mirror.*
import com.kerybotu.derpibooru.mirror.databinding.ActivityFiltersBinding
import com.kerybotu.derpibooru.mirror.model.Filter
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import kotlinx.coroutines.*
import org.json.JSONObject

class FilterActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFiltersBinding
    private lateinit var adapter: FilterAdapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var allFilters = emptyList<Filter>()
    private val prefs by lazy { getSharedPreferences("filter_state", Context.MODE_PRIVATE) }
    private val currentId: Int? get() = prefs.getInt("current_id", -1).takeIf { it > 0 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFiltersBinding.inflate(layoutInflater)
        setContentView(binding.root)
        PaletteManager.apply(this)
        setSupportActionBar(binding.filterToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.filterToolbar.setNavigationOnClickListener { finish() }
        adapter = FilterAdapter(emptyList(), { currentId }) { useFilter(it) }
        binding.currentFilterName.text = prefs.getString("current_name", "默认安全过滤器")
        binding.filterList.layoutManager = LinearLayoutManager(this)
        binding.filterList.adapter = adapter
        binding.filterRefresh.setOnRefreshListener { loadFilters() }
        binding.filterSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = filterLocal(s?.toString().orEmpty())
            override fun afterTextChanged(s: Editable?) = Unit
        })
        binding.filterHelp.setOnClickListener { showSearchHelp() }
        binding.changeFilter.setOnClickListener { showCurrentChooser() }
        binding.addFilter.setOnClickListener { showCreateSheet() }
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
            allFilters = result?.let { parseFilters(it) }.orEmpty()
            filterLocal(binding.filterSearch.text?.toString().orEmpty())
            binding.filterRefresh.isRefreshing = false
        }
    }

    private fun parseFilters(json: String): List<Filter> {
        val arr = JSONObject(json).optJSONArray("filters") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Filter(o.optInt("id"), o.optString("name", "未命名过滤器"), o.optString("description", ""), if (o.isNull("user_id")) null else o.optInt("user_id"), o.optBoolean("system"), o.optBoolean("public"), o.optJSONArray("spoilered_tag_ids")?.length() ?: 0, o.optJSONArray("hidden_tag_ids")?.length() ?: 0)
        }
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

    private fun showSearchHelp() {
        val examples = arrayOf("creator: 用户名", "name: 关键词", "system:true", "public:true")
        AlertDialog.Builder(this).setTitle("搜索字段示例").setItems(examples) { _, which -> binding.filterSearch.setText(examples[which].substringBefore(":") + ":") }.show()
    }

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

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
