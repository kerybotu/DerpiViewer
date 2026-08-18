package com.kerybotu.derpibooru.mirror.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URLEncoder

class GalleryActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); private lateinit var list: LinearLayout; private lateinit var scroll: ScrollView; private lateinit var progress: ProgressBar; private var page = 1; private var query = "*"; private var loading = false
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContentView(buildView()); load(true) }
    private fun buildView(): LinearLayout { val c = PaletteManager.colors(this); return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(c.surface); val toolbar = SafeToolbar(this@GalleryActivity).apply { title = "图集"; setNavigationIcon(com.kerybotu.derpibooru.mirror.R.drawable.ic_arrow_back); setNavigationOnClickListener { finish() }; inflateMenu(com.kerybotu.derpibooru.mirror.R.menu.filter_menu); setOnMenuItemClickListener { showFilters(); true } }; addView(toolbar, LinearLayout.LayoutParams(-1, dp(56))); scroll = ScrollView(this@GalleryActivity); list = LinearLayout(this@GalleryActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }; scroll.addView(list); addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); progress = ProgressBar(this@GalleryActivity).apply { visibility = View.GONE }; addView(progress, LinearLayout.LayoutParams(-2, dp(40)).apply { gravity = Gravity.CENTER_HORIZONTAL }); scroll.viewTreeObserver.addOnScrollChangedListener { if (!loading && scroll.getChildAt(0).bottom - scroll.height - scroll.scrollY < dp(500)) load(false) } } }
    private fun showFilters() { val input = EditText(this).apply { hint = "标题、描述或创建者"; setText(query.takeUnless { it == "*" }) }; BottomSheetDialog(this).apply { setContentView(LinearLayout(this@GalleryActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(32)); addView(input); addView(Button(this@GalleryActivity).apply { text = "应用筛选"; setOnClickListener { query = input.text.toString().trim().ifBlank { "*" }; load(true); dismiss() } }) }); show() } }
    private fun load(reset: Boolean) { if (loading) return; if (reset) { page = 1; list.removeAllViews() }; loading = true; progress.visibility = View.VISIBLE; scope.launch { val q = URLEncoder.encode(query, "UTF-8"); val raw = withContext(Dispatchers.IO) { NetworkManager.getApi(this@GalleryActivity, "search/galleries?q=$q&page=$page") }; val arr = runCatching { JSONObject(raw.orEmpty()).optJSONArray("galleries") }.getOrNull(); repeat(arr?.length() ?: 0) { i -> arr?.optJSONObject(i)?.let { add(it) } }; if ((arr?.length() ?: 0) > 0) page++; loading = false; progress.visibility = View.GONE } }
    private fun add(gallery: JSONObject) {
        val c = PaletteManager.colors(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(c.surfaceVariant)
            addView(TextView(this@GalleryActivity).apply { text = gallery.optString("title"); textSize = 17f; setTextColor(c.primary) })
            gallery.optString("description").takeIf { it.isNotBlank() }?.let { description -> addView(TextView(this@GalleryActivity).apply { text = description; setTextColor(c.onSurface); setPadding(0, dp(5), 0, 0) }) }
            addView(TextView(this@GalleryActivity).apply { text = gallery.optString("user", "未知创建者"); setTextColor(c.muted); setPadding(0, dp(6), 0, 0) })
        }
        card.setOnClickListener { startActivity(Intent(this, SearchActivity::class.java).putExtra(SearchActivity.EXTRA_INITIAL_QUERY, "gallery_id:${gallery.optInt("id")}")) }
        list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) })
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt(); override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
