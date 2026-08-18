package com.kerybotu.derpibooru.mirror.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.Color
import android.content.res.ColorStateList
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.android.material.tabs.TabLayout
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.R
import com.kerybotu.derpibooru.mirror.download.DownloadQueueManager
import com.kerybotu.derpibooru.mirror.download.DownloadStatus
import com.kerybotu.derpibooru.mirror.download.DownloadTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DownloadManagerActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private lateinit var tabs: TabLayout
    private val queue by lazy { DownloadQueueManager.get(this) }
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var selectedTab = 0
    private lateinit var toolbar: SafeToolbar
    private val selectedTaskIds = mutableSetOf<Long>()
    private var deleteMenu: android.view.MenuItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PaletteManager.apply(this)
        setContentView(buildView())
        PaletteManager.apply(this)
        scope.launch { queue.state.collectLatest { render(it) } }
    }

    private fun buildView(): View {
        val colors = PaletteManager.colors(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(colors.surface) }
        toolbar = SafeToolbar(this).apply { title = "下载管理"; setNavigationIcon(R.drawable.ic_arrow_back); setNavigationOnClickListener { finish() } }
        deleteMenu = toolbar.menu.add("删除").apply { setIcon(R.drawable.ic_delete); setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS); isVisible = false; setOnMenuItemClickListener { confirmDelete(); true } }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
        tabs = TabLayout(this).apply {
            // TabLayout is not a TextView, so apply the selected palette explicitly.
            setBackgroundColor(colors.surfaceVariant)
            tabTextColors = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
                intArrayOf(colors.primary, colors.onSurface)
            )
            setSelectedTabIndicatorColor(colors.primary)
            addTab(newTab().setText("进行中")); addTab(newTab().setText("已完成")); addTab(newTab().setText("失败"))
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) { selectedTab = tab.position; render(queue.state.value) }
                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        }
        root.addView(tabs, LinearLayout.LayoutParams(-1, dp(48)))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(24)) }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun render(all: List<DownloadTask>) {
        if (!::list.isInitialized) return
        selectedTaskIds.retainAll(all.map { it.taskId }.toSet())
        deleteMenu?.isVisible = selectedTaskIds.isNotEmpty()
        list.removeAllViews()
        val status = when (selectedTab) { 0 -> setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING); 1 -> setOf(DownloadStatus.COMPLETED); else -> setOf(DownloadStatus.FAILED) }
        val tasks = all.filter { it.status in status }
        if (selectedTab == 1 && tasks.isNotEmpty()) {
            list.addView(Button(this).apply { text = "清空已完成记录（不会删除文件）"; setOnClickListener { queue.clearCompleted() } })
        }
        if (selectedTab == 2 && tasks.isNotEmpty()) {
            list.addView(Button(this).apply { text = "全部重试"; setOnClickListener { tasks.forEach { queue.retry(it.taskId) } } })
        }
        if (tasks.isEmpty()) {
            list.addView(TextView(this).apply { text = when (selectedTab) { 0 -> "暂无进行中的下载"; 1 -> "暂无已完成下载"; else -> "暂无失败任务" }; gravity = Gravity.CENTER; setPadding(0, dp(36), 0, dp(36)); setTextColor(PaletteManager.colors(this@DownloadManagerActivity).muted) })
            return
        }
        tasks.forEach { task -> list.addView(taskView(task)) }
    }

    private fun taskView(task: DownloadTask): View {
        val colors = PaletteManager.colors(this)
        val selected = selectedTaskIds.contains(task.taskId)
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10)); setBackgroundColor(if (selected) colors.primary else colors.surfaceVariant) }
        val preview = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP }
        task.thumbnailUrl?.let { Glide.with(this).load(it).into(preview) }
        box.addView(preview, LinearLayout.LayoutParams(dp(64), dp(64)))
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        details.addView(TextView(this).apply { text = "图片 #${task.imageId}"; setTextColor(colors.onSurface) })
        details.addView(TextView(this).apply { text = task.fileName; maxLines = 1; setTextColor(colors.muted) })
        if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.QUEUED) {
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = if (task.totalBytes > 0) (task.downloadedBytes * 100 / task.totalBytes).toInt() else 0 }
            details.addView(bar, LinearLayout.LayoutParams(-1, dp(8)))
            details.addView(TextView(this).apply { text = if (task.status == DownloadStatus.QUEUED) "排队中…" else formatBytes(task.downloadedBytes) + if (task.totalBytes > 0) " / ${formatBytes(task.totalBytes)}" else ""; setTextColor(colors.muted) })
            box.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            box.addView(Button(this).apply { text = "取消"; isEnabled = selectedTaskIds.isEmpty(); setOnClickListener { queue.cancel(task.taskId) } })
        } else if (task.status == DownloadStatus.FAILED) {
            details.addView(TextView(this).apply { text = task.errorMessage ?: "下载失败"; setTextColor(colors.muted) })
            box.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            box.addView(Button(this).apply { text = "重试"; isEnabled = selectedTaskIds.isEmpty(); setOnClickListener { queue.retry(task.taskId) } })
        } else {
            details.addView(TextView(this).apply { text = "下载完成"; setTextColor(colors.muted) })
            box.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        }
        box.setOnClickListener {
            if (selectedTaskIds.isNotEmpty()) {
                toggleTaskSelection(task.taskId)
            } else if (task.status == DownloadStatus.COMPLETED) {
                task.outputUri?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }) }
            }
        }
        box.setOnLongClickListener { toggleTaskSelection(task.taskId); true }
        return box.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) } }
    }

    private fun toggleTaskSelection(id: Long) {
        if (!selectedTaskIds.add(id)) selectedTaskIds.remove(id)
        deleteMenu?.isVisible = selectedTaskIds.isNotEmpty()
        toolbar.title = if (selectedTaskIds.isEmpty()) "下载管理" else "已选择 ${selectedTaskIds.size} 项"
        render(queue.state.value)
    }

    private fun confirmDelete() {
        if (selectedTaskIds.isEmpty()) return
        val check = android.widget.CheckBox(this).apply { text = "同时删除文件"; setTextColor(Color.RED) }
        AlertDialog.Builder(this)
            .setTitle("确认删除图片下载记录")
            .setView(check)
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                queue.delete(selectedTaskIds.toSet(), check.isChecked)
                selectedTaskIds.clear()
                deleteMenu?.isVisible = false
            }
            .create().also { dialog -> dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.RED) } }
            .show()
    }

    private fun formatBytes(value: Long): String = when { value >= 1_000_000 -> "%.1f MB".format(value / 1_000_000.0); value >= 1_000 -> "%.0f KB".format(value / 1_000.0); else -> "$value B" }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() { scope.coroutineContext[Job]?.cancel(); super.onDestroy() }
}
