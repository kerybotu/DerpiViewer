package com.kerybotu.derpibooru.mirror.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URLEncoder
import java.text.NumberFormat
import java.util.Locale

class TagSearchActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var input: EditText; private lateinit var list: LinearLayout; private lateinit var scroll: ScrollView; private lateinit var progress: ProgressBar
    private var page = 1; private var loading = false; private var query = "*"
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContentView(buildView()); load(true) }
    private fun buildView(): View {
        val c = PaletteManager.colors(this); val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(c.surface) }
        root.addView(SafeToolbar(this).apply { title = "标签"; setNavigationIcon(com.kerybotu.derpibooru.mirror.R.drawable.ic_arrow_back); setNavigationOnClickListener { finish() } }, LinearLayout.LayoutParams(-1, dp(56)))
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        input = EditText(this).apply { hint = "搜索标签（* 表示全部）"; setSingleLine(); setText("*"); setSelectAllOnFocus(false) }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f)); bar.addView(Button(this).apply { text = "搜索"; setOnClickListener { query = input.text.toString().trim().ifBlank { "*" }; load(true) } })
        root.addView(bar); scroll = ScrollView(this); list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(24)) }; scroll.addView(list); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); progress = ProgressBar(this).apply { visibility = View.GONE }; root.addView(progress, LinearLayout.LayoutParams(-2, dp(40)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        scroll.viewTreeObserver.addOnScrollChangedListener { if (!loading && scroll.getChildAt(0).bottom - scroll.height - scroll.scrollY < dp(500)) load(false) }
        input.addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit; override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit; override fun afterTextChanged(s: Editable?) = Unit })
        return root
    }
    private fun load(reset: Boolean) { if (loading) return; if (reset) { page = 1; list.removeAllViews() }; loading = true; progress.visibility = View.VISIBLE; scope.launch {
        val encoded = URLEncoder.encode(query, "UTF-8"); val raw = withContext(Dispatchers.IO) { NetworkManager.getApi(this@TagSearchActivity, "search/tags?q=$encoded&page=$page") }
        val root = runCatching { JSONObject(raw.orEmpty()) }.getOrNull(); val tags = root?.optJSONArray("tags")
        if (reset) list.addView(TextView(this@TagSearchActivity).apply { text = "共 ${NumberFormat.getIntegerInstance(Locale.US).format(root?.optInt("total", tags?.length() ?: 0))} 个标签"; setTextColor(PaletteManager.colors(this@TagSearchActivity).muted); setPadding(0, dp(8), 0, dp(12)) })
        repeat(tags?.length() ?: 0) { i -> tags?.optJSONObject(i)?.let { addTag(it) } }; if ((tags?.length() ?: 0) > 0) page++; loading = false; progress.visibility = View.GONE
    } }
    private fun addTag(tag: JSONObject) { val c = PaletteManager.colors(this); val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); setBackgroundColor(c.surfaceVariant) }; val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply { text = tag.optString("name"); textSize = 16f; setTextColor(c.primary) }, LinearLayout.LayoutParams(0, -2, 1f)); top.addView(TextView(this).apply { text = NumberFormat.getIntegerInstance(Locale.US).format(tag.optInt("images")); setTextColor(c.onSurface) }); row.addView(top)
        tag.optString("short_description").takeIf { it.isNotBlank() }?.let { row.addView(TextView(this).apply { text = it; setTextColor(c.muted); setPadding(0, dp(4), 0, 0) }) }; row.setOnClickListener { startActivity(Intent(this, SearchActivity::class.java).putExtra(SearchActivity.EXTRA_INITIAL_QUERY, tag.optString("name"))) }; list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(6)) }) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt(); override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
