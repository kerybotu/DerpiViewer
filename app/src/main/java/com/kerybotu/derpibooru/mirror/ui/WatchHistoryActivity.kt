package com.kerybotu.derpibooru.mirror.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
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
    private lateinit var empty: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = WatchHistoryStore(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 12, 16, 12) }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val back = ImageButton(this).apply { setImageResource(R.drawable.ic_arrow_back); setOnClickListener { finish() }; contentDescription = "返回" }
        bar.addView(back, LinearLayout.LayoutParams(48, 48))
        bar.addView(TextView(this).apply { text = "观看历史"; textSize = 20f; setTextColor(com.kerybotu.derpibooru.mirror.PaletteManager.colors(this@WatchHistoryActivity).onSurface) }, LinearLayout.LayoutParams(0, 48, 1f))
        val clear = Button(this).apply { text = "清空"; setOnClickListener { confirmClear() } }
        bar.addView(clear)
        root.addView(bar)
        empty = TextView(this).apply { text = "暂无观看历史"; gravity = Gravity.CENTER; visibility = TextView.GONE }
        val list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@WatchHistoryActivity) }
        adapter = HistoryAdapter { entry -> startActivity(Intent(this, ImageDetailActivity::class.java).putExtra("image", entry.toImage())) }
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(empty, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        AdaptiveNavigationShell.install(this, root, AdaptiveNavigationShell.HOME, AdaptiveNavigationShell.ContentMode.SAFE_AREA)
        ViewCompat.requestApplyInsets(root)
        load()
    }
    private fun load() { scope.launch { val items = withContext(Dispatchers.IO) { store.list() }; adapter.submit(items); empty.visibility = if (items.isEmpty()) TextView.VISIBLE else TextView.GONE } }
    private fun confirmClear() { AlertDialog.Builder(this).setTitle("清空观看历史").setMessage("确定删除全部观看历史吗？").setNegativeButton("取消", null).setPositiveButton("清空") { _, _ -> scope.launch { withContext(Dispatchers.IO) { store.clear() }; load() } }.show() }
    override fun onDestroy() { scope.cancel(); store.close(); super.onDestroy() }

    private inner class HistoryAdapter(private val click: (WatchHistoryEntry) -> Unit) : RecyclerView.Adapter<HistoryHolder>() {
        private var items = emptyList<WatchHistoryEntry>()
        fun submit(value: List<WatchHistoryEntry>) { items = value; notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryHolder = HistoryHolder(LinearLayout(this@WatchHistoryActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(8, 8, 8, 8) }, click)
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: HistoryHolder, position: Int) = holder.bind(items[position])
    }
    private inner class HistoryHolder(private val row: LinearLayout, private val onClick: (WatchHistoryEntry) -> Unit) : RecyclerView.ViewHolder(row) {
        private val image = ImageView(this@WatchHistoryActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val text = TextView(this@WatchHistoryActivity).apply { textSize = 14f; setPadding(12, 0, 8, 0) }
        private val delete = ImageButton(this@WatchHistoryActivity).apply { setImageResource(R.drawable.ic_delete); contentDescription = "删除" }
        init { row.addView(image, LinearLayout.LayoutParams(88, 88)); row.addView(text, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(delete, LinearLayout.LayoutParams(48, 48)); row.setOnClickListener { onClick(current) } }
        private lateinit var current: WatchHistoryEntry
        fun bind(entry: WatchHistoryEntry) { current = entry; Glide.with(image).load(entry.thumbnailUrl).placeholder(R.drawable.ic_image_placeholder).into(image); val label = entry.title.ifBlank { "图片 #${entry.imageId}" }; text.text = "$label\n#${entry.imageId} · ${entry.mediaType} · ${entry.watchCount} 次\n最近观看：${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.lastWatchedAt))}"; delete.setOnClickListener { scope.launch { withContext(Dispatchers.IO) { store.delete(entry.imageId) }; load() } } }
    }
}
