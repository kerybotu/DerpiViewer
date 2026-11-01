package com.kerybotu.derpibooru.mirror.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.model.Filter
import com.kerybotu.derpibooru.mirror.model.FilterJsonCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Displays the complete documented filter response, including tag IDs/rules. */
class FilterDetailsActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var content: LinearLayout
    private var filter: Filter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PaletteManager.apply(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(PaletteManager.colors(this@FilterDetailsActivity).surface) }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(32))
        }
        scroll.addView(content)
        setContentView(scroll)
        renderLoading()
        val supplied = intent.getStringExtra(EXTRA_FILTER_JSON)?.let { runCatching { FilterJsonCodec.parseResponse(it) }.getOrNull() }
        if (supplied != null) render(supplied) else load(intent.getIntExtra(EXTRA_FILTER_ID, 0))
    }

    private fun load(id: Int) {
        if (id <= 0) { renderError("过滤器 ID 无效"); return }
        scope.launch {
            val result = withContext(Dispatchers.IO) { FilterSearchClient.getById(this@FilterDetailsActivity, id) }
            if (result == null) renderError("无法获取过滤器详情，请检查网络或权限") else render(result)
        }
    }

    private fun renderLoading() {
        content.removeAllViews()
        content.addView(TextView(this).apply { text = "正在加载过滤器详情…"; textSize = 17f })
    }

    private fun renderError(message: String) {
        content.removeAllViews()
        content.addView(TextView(this).apply { text = message; textSize = 16f })
    }

    private fun render(value: Filter) {
        filter = value
        val colors = PaletteManager.colors(this)
        content.removeAllViews()
        content.addView(TextView(this).apply {
            text = value.name
            textSize = 24f
            setTextColor(colors.onSurface)
        })
        content.addView(TextView(this).apply {
            text = "ID ${value.id} · ${visibilityLabel(value)} · 剧透 ${value.spoilerCount} · 隐藏 ${value.hiddenCount}"
            textSize = 13f
            setTextColor(colors.muted)
            setPadding(0, dp(8), 0, dp(8))
        })
        value.creator?.takeIf { it.isNotBlank() }?.let { creator ->
            content.addView(TextView(this).apply { text = "维护者：$creator"; setTextColor(colors.muted) })
        }
        content.addView(section("描述", value.description.ifBlank { "（无描述）" }, colors))
        content.addView(section("剧透标签 ID", value.spoileredTagIds.joinToString(", ").ifBlank { "（无）" }, colors))
        content.addView(section("隐藏标签 ID", value.hiddenTagIds.joinToString(", ").ifBlank { "（无）" }, colors))
        value.spoileredComplex?.takeIf { it.isNotBlank() }?.let { content.addView(section("复杂剧透规则", it, colors)) }
        value.hiddenComplex?.takeIf { it.isNotBlank() }?.let { content.addView(section("复杂隐藏规则", it, colors)) }

        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(16), 0, 0) }
        actions.addView(action("导入为本地过滤器") { importLocally(value) })
        actions.addView(action("使用此服务器过滤器") { use(value) })
        actions.addView(action("在网页中编辑") { openWeb("filters/${value.id}/edit") })
        content.addView(actions)
    }

    private fun section(title: String, value: String, colors: com.kerybotu.derpibooru.mirror.PaletteDefinitions.Scheme): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(TextView(this@FilterDetailsActivity).apply { text = title; textSize = 13f; setTextColor(colors.muted) })
            addView(TextView(this@FilterDetailsActivity).apply { text = value; textSize = 15f; setTextColor(colors.onSurface); setPadding(0, dp(5), 0, 0) })
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
            Ui2DesignSystem.styleIsland(this, colors, 18f)
        }

    private fun action(label: String, callback: () -> Unit) = Button(this).apply {
        text = label
        PaletteManager.styleButton(this)
        setOnClickListener { callback() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(10) }
    }

    private fun importLocally(remote: Filter) {
        FilterStore.saveLocalFilter(this, remote.copy(isLocal = true, sourceId = remote.id, sourceDomain = AppSettings.getTargetDomain(this)))
        Toast.makeText(this, "已导入本地过滤器，可在过滤器页的“本地”中编辑或启用", Toast.LENGTH_LONG).show()
    }

    private fun use(remote: Filter) {
        if (remote.isLocal && remote.localQuery?.isNotBlank() == true) {
            AppSettings.setCurrentLocalFilter(this, remote.id, remote.name, remote.localQuery)
        } else {
            AppSettings.setCurrentFilter(this, remote.sourceId ?: remote.id, remote.name)
        }
        Toast.makeText(this, "已使用过滤器：${remote.name}", Toast.LENGTH_SHORT).show()
    }

    private fun openWeb(path: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://${AppSettings.getTargetDomain(this)}/$path")))
    }

    private fun visibilityLabel(value: Filter): String = when {
        value.system -> "系统"
        value.public -> "公共"
        else -> "私人"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val EXTRA_FILTER_ID = "filter_id"
        const val EXTRA_FILTER_JSON = "filter_json"
    }
}
