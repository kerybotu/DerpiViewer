package com.kerybotu.derpibooru.mirror.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import com.kerybotu.derpibooru.mirror.translate.NiuTransService
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URLEncoder

class RecentCommentsActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); private lateinit var list: LinearLayout; private lateinit var scroll: ScrollView; private lateinit var progress: ProgressBar; private var page = 1; private var loading = false
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContentView(buildView()); load() }
    private fun buildView(): View { val c = PaletteManager.colors(this); val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(c.surface) }; root.addView(SafeToolbar(this).apply { title = "最近评论"; setNavigationIcon(com.kerybotu.derpibooru.mirror.R.drawable.ic_arrow_back); setNavigationOnClickListener { finish() } }, LinearLayout.LayoutParams(-1, dp(56))); scroll = ScrollView(this); list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }; scroll.addView(list); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); progress = ProgressBar(this).apply { visibility = View.GONE }; root.addView(progress, LinearLayout.LayoutParams(-2, dp(40)).apply { gravity = Gravity.CENTER_HORIZONTAL }); scroll.viewTreeObserver.addOnScrollChangedListener { if (!loading && scroll.getChildAt(0).bottom - scroll.height - scroll.scrollY < dp(500)) load() }; return root }
    private fun load() { if (loading) return; loading = true; progress.visibility = View.VISIBLE; scope.launch { val q = URLEncoder.encode("*", "UTF-8"); val raw = withContext(Dispatchers.IO) { NetworkManager.getApi(this@RecentCommentsActivity, "search/comments?q=$q&page=$page") }; val arr = runCatching { JSONObject(raw.orEmpty()).optJSONArray("comments") }.getOrNull(); repeat(arr?.length() ?: 0) { i -> arr?.optJSONObject(i)?.let { addComment(it) } }; if ((arr?.length() ?: 0) > 0) page++; loading = false; progress.visibility = View.GONE } }
    private fun addComment(comment: JSONObject) { val c = PaletteManager.colors(this); val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)); setBackgroundColor(c.surfaceVariant) }; row.addView(TextView(this).apply { text = "${comment.optString("author", "匿名用户")} · ${comment.optString("created_at").take(10)}"; textSize = 14f; setTextColor(c.primary) }); val original = comment.optString("body"); val body = TextView(this).apply { text = original; setTextColor(c.onSurface); setPadding(0, dp(6), 0, dp(4)) }; row.addView(body); if (NiuTransService.shouldTranslate(original)) row.addView(Button(this).apply { text = "翻译"; textSize = 12f; backgroundTintList = android.content.res.ColorStateList.valueOf(c.primary); setTextColor(c.onPrimary); setOnClickListener { if (tag is String) { body.text = original; tag = null; text = "翻译" } else { isEnabled = false; text = "翻译中…"; scope.launch { NiuTransService.translate(original).onSuccess { body.text = it; tag = it; this@apply.text = "原文" }.onFailure { this@apply.text = "翻译" }; this@apply.isEnabled = true } } } }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) }); val imageId = comment.optInt("image_id", -1); if (imageId > 0) row.addView(TextView(this).apply { text = "查看图片 #$imageId"; setTextColor(c.primary); setOnClickListener { startActivity(Intent(this@RecentCommentsActivity, ImageDetailActivity::class.java).putExtra("image_id", imageId)) } }); list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) }) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt(); override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
