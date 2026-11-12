package com.kerybotu.derpibooru.mirror.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.R
import com.kerybotu.derpibooru.mirror.history.WatchHistoryEntry
import com.kerybotu.derpibooru.mirror.history.WatchHistoryStore
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date

class WatchHistoryActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: WatchHistoryStore
    private lateinit var adapter: HistoryAdapter
    private lateinit var status: TextView
    private lateinit var title: TextView
    private lateinit var back: ImageButton
    private lateinit var trash: ImageButton
    private val selected = linkedSetOf<Int>()
    private var selecting = false
    private var deleting = false
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        store = WatchHistoryStore(this)
        val colors = PaletteManager.colors(this)
        val root = FrameLayout(this).apply { setBackgroundColor(colors.surface) }
        val body = FrameLayout(this)
        val list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@WatchHistoryActivity); clipToPadding = false }
        adapter = HistoryAdapter()
        list.adapter = adapter
        status = TextView(this).apply { text = "正在读取观看历史…"; gravity = Gravity.CENTER; setTextColor(colors.onSurface) }
        body.addView(list, FrameLayout.LayoutParams(-1, -1))
        body.addView(status, FrameLayout.LayoutParams(-1, -1))
        root.addView(body, FrameLayout.LayoutParams(-1, -1))
        // Use the application's glass header surface and shape, not a separate toolbar material.
        val island = IslandGlassView(this).apply { setPalette(colors); elevation = dp(12).toFloat(); setPadding(dp(8), 0, dp(8), 0) }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        back = icon(R.drawable.ic_arrow_back, "返回") { if (selecting) exitSelection() else finish() }
        title = TextView(this).apply { text = "观看历史"; textSize = 17f; gravity = Gravity.CENTER; setTextColor(colors.glassText); maxLines = 1 }
        trash = icon(R.drawable.ic_delete, "选择历史记录删除") { if (selecting) confirmDelete() else { selecting = true; updateSelection() } }
        row.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        row.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(trash, LinearLayout.LayoutParams(dp(48), dp(48)))
        island.addView(row, ViewGroup.LayoutParams(-1, -1))
        root.addView(island, FrameLayout.LayoutParams(-1, dp(64), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { marginStart = dp(12); marginEnd = dp(12) })
        setContentView(root)
        AdaptiveNavigationShell.install(this, root, AdaptiveNavigationShell.NO_SELECTION)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val params = island.layoutParams as FrameLayout.LayoutParams
            params.topMargin = bars.top + dp(8)
            island.layoutParams = params
            body.setPadding(dp(12), bars.top + dp(80), dp(12), bars.bottom + dp(12))
            insets
        }
        ViewCompat.requestApplyInsets(root)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (deleting) return; if (selecting) exitSelection() else finish() }
        })
        load()
    }

    private fun icon(resource: Int, label: String, click: () -> Unit) = ImageButton(this).apply {
        setImageResource(resource); contentDescription = label; background = null
        setPadding(dp(12), dp(12), dp(12), dp(12))
        imageTintList = ColorStateList.valueOf(PaletteManager.colors(this@WatchHistoryActivity).glassText)
        setOnClickListener { if (!deleting) click() }
    }
    private fun load() {
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                val items = withContext(Dispatchers.IO) { store.list() }
                selected.retainAll(items.map { it.imageId }.toSet())
                adapter.items = items
                status.text = "暂无观看历史"
                status.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                updateSelection()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { status.text = "历史记录读取失败，点击重试"; status.visibility = View.VISIBLE; status.setOnClickListener { load() } }
        }
    }
    private fun exitSelection() { if (deleting) return; selecting = false; selected.clear(); updateSelection() }
    private fun updateSelection() {
        back.setImageResource(if (selecting) R.drawable.ic_close else R.drawable.ic_arrow_back)
        back.contentDescription = if (selecting) "取消清理" else "返回"
        title.text = if (selecting) "已选择 ${selected.size} 项" else "观看历史"
        val error = PaletteManager.colors(this).primary
        trash.imageTintList = ColorStateList.valueOf(if (selecting) error else PaletteManager.colors(this).glassText)
        trash.isEnabled = !deleting && (!selecting || selected.isNotEmpty())
        trash.contentDescription = if (selecting) "删除选中的 ${selected.size} 个作品" else "选择历史记录删除"
        adapter.notifyDataSetChanged()
    }
    private fun confirmDelete() {
        val ids = selected.toSet()
        if (ids.isEmpty() || deleting) return
        AlertDialog.Builder(this).setTitle("删除观看历史").setMessage("即将从历史记录中删除 ${ids.size} 个作品")
            .setNegativeButton("取消", null).setPositiveButton("确认删除") { _, _ ->
                deleting = true; updateSelection()
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { store.deleteMany(ids) }
                        deleting = false; exitSelection(); load()
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { deleting = false; updateSelection(); Toast.makeText(this@WatchHistoryActivity, "删除失败，记录未改变", Toast.LENGTH_LONG).show() }
                }
            }.show()
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    private inner class HistoryAdapter : RecyclerView.Adapter<HistoryHolder>() {
        var items = emptyList<WatchHistoryEntry>()
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = items[position].imageId.toLong()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = HistoryHolder(LinearLayout(this@WatchHistoryActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) })
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: HistoryHolder, position: Int) = holder.bind(items[position])
        override fun onViewRecycled(holder: HistoryHolder) { com.bumptech.glide.Glide.with(holder.preview).clear(holder.preview) }
    }
    private inner class HistoryHolder(private val row: LinearLayout) : RecyclerView.ViewHolder(row) {
        val preview = ImageView(this@WatchHistoryActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val text = TextView(this@WatchHistoryActivity).apply { textSize = 14f; setPadding(dp(12), 0, dp(8), 0); setTextColor(PaletteManager.colors(this@WatchHistoryActivity).onSurface) }
        private val check = TextView(this@WatchHistoryActivity).apply { gravity = Gravity.CENTER; textSize = 24f; setTextColor(PaletteManager.colors(this@WatchHistoryActivity).primary) }
        init { row.addView(preview, LinearLayout.LayoutParams(dp(88), dp(88))); row.addView(text, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(check, LinearLayout.LayoutParams(dp(48), dp(48))) }
        fun bind(entry: WatchHistoryEntry) {
            CdnImageGate.load(preview, entry.thumbnailUrl)
            val label = entry.title.ifBlank { "图片 #${entry.imageId}" }
            text.text = "$label\n#${entry.imageId} · ${entry.mediaType} · ${entry.watchCount} 次\n最近观看：${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.lastWatchedAt))}"
            check.visibility = if (selecting) View.VISIBLE else View.GONE
            check.text = if (entry.imageId in selected) "✓" else "○"
            row.isSelected = entry.imageId in selected
            row.setOnClickListener {
                if (deleting) return@setOnClickListener
                if (selecting) { if (!selected.add(entry.imageId)) selected.remove(entry.imageId); updateSelection() }
                else startActivity(Intent(this@WatchHistoryActivity, ImageDetailActivity::class.java).putExtra("image", entry.toImage()))
            }
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
