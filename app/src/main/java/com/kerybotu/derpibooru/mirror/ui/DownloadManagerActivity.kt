package com.kerybotu.derpibooru.mirror.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.liquidglass.LiquidGlassButton
import com.example.liquidglass.LiquidGlassChip
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.R
import com.kerybotu.derpibooru.mirror.download.DownloadQueueManager
import com.kerybotu.derpibooru.mirror.download.DownloadStatus
import com.kerybotu.derpibooru.mirror.download.DownloadTask
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class DownloadManagerActivity : AppCompatActivity() {
    private data class TaskRow(val task: DownloadTask, val selected: Boolean, val selectionMode: Boolean)
    private val queue by lazy { DownloadQueueManager.get(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observation: Job? = null
    private lateinit var feed: GlassFeedLayout
    private lateinit var dialogs: GlassPageDialogs
    private lateinit var deleteMenu: MenuItem
    private val taskAdapter = TaskAdapter()
    private val bulkAdapter = BulkActionAdapter()
    private var selectedTab = 0
    private val selectedTaskIds = mutableSetOf<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        feed = GlassFeedLayout(this, "下载管理") { goBack() }
        dialogs = GlassPageDialogs(feed)
        deleteMenu = feed.toolbar.menu.add("删除").apply {
            setIcon(R.drawable.ic_delete)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            isVisible = false
            setOnMenuItemClickListener { confirmDelete(); true }
        }
        feed.setTabs(listOf("进行中", "已完成", "失败")) { index ->
            selectedTab = index
            selectedTaskIds.clear()
            render(queue.state.value)
            feed.results.scrollToPosition(0)
        }
        feed.results.adapter = ConcatAdapter(bulkAdapter, taskAdapter)
        // The queue pushes progress live; it does not need a separate network refresh gesture.
        feed.refresh.setCanRefresh { false }
        feed.onPaletteChanged = {
            for (index in 0 until feed.results.childCount) {
                (feed.results.getChildViewHolder(feed.results.getChildAt(index)) as? TaskHolder)?.applyPalette()
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { goBack() }
        })
        setContentView(feed)
    }

    private fun goBack() {
        if (selectedTaskIds.isEmpty()) finish()
        else { selectedTaskIds.clear(); render(queue.state.value) }
    }

    private fun render(all: List<DownloadTask>) {
        val tasks = all.filter { task ->
            when (selectedTab) {
                0 -> task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.DOWNLOADING
                1 -> task.status == DownloadStatus.COMPLETED
                else -> task.status == DownloadStatus.FAILED
            }
        }
        // Tasks can disappear or move to a different tab while selected.
        selectedTaskIds.retainAll(tasks.map { it.taskId }.toSet())
        deleteMenu.isVisible = selectedTaskIds.isNotEmpty()
        feed.toolbar.title = if (selectedTaskIds.isEmpty()) "下载管理" else "已选择 ${selectedTaskIds.size} 项"
        bulkAdapter.update(if (tasks.isEmpty()) null else when (selectedTab) {
            1 -> "清空已完成记录（不会删除文件）"
            2 -> "全部重试"
            else -> null
        }, selectedTaskIds.isEmpty())
        taskAdapter.submitList(tasks.map { TaskRow(it, it.taskId in selectedTaskIds, selectedTaskIds.isNotEmpty()) })
        if (tasks.isEmpty()) {
            feed.showStatus(when (selectedTab) {
                0 -> "暂无进行中的下载"; 1 -> "暂无已完成下载"; else -> "暂无失败任务"
            }, "下载状态会自动更新")
        } else feed.hideStatus()
    }

    private fun toggleSelection(id: Long) {
        if (!selectedTaskIds.add(id)) selectedTaskIds.remove(id)
        render(queue.state.value)
    }

    private fun confirmDelete() {
        val ids = selectedTaskIds.toSet()
        if (ids.isEmpty()) return
        val deleteFiles = feed.trackGlass(LiquidGlassChip(this), feed.refresh, 20f).apply {
            text = "同时删除文件"
            isCheckable = true
            isChecked = false
            checkedTint = glassTint
            minimumHeight = dp(48)
        }
        dialogs.show("删除 ${ids.size} 条下载记录？", "默认仅删除记录，进行中的任务会取消。勾选下方选项会同时删除已下载文件。",
            extra = deleteFiles, positive = "删除") {
            queue.delete(ids, deleteFiles.isChecked)
            selectedTaskIds.clear()
            render(queue.state.value)
        }
    }

    private fun runBulkAction() {
        if (selectedTaskIds.isNotEmpty()) return
        when (selectedTab) {
            1 -> queue.clearCompleted()
            2 -> queue.state.value.filter { it.status == DownloadStatus.FAILED }.forEach { queue.retry(it.taskId) }
        }
    }

    private fun openTask(task: DownloadTask) {
        if (selectedTaskIds.isNotEmpty()) { toggleSelection(task.taskId); return }
        if (task.status != DownloadStatus.COMPLETED) return
        val output = task.outputUri
        if (output == null) { dialogs.toast("找不到已下载文件"); return }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(output)).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) })
        }.onFailure { dialogs.toast("无法打开文件，请检查文件是否存在或是否已安装对应应用") }
    }

    private inner class BulkActionAdapter : RecyclerView.Adapter<BulkHolder>() {
        private var label: String? = null
        private var enabled = true
        fun update(next: String?, nextEnabled: Boolean) {
            val previous = label
            val changed = previous != next || enabled != nextEnabled
            label = next
            enabled = nextEnabled
            when {
                previous == null && next != null -> notifyItemInserted(0)
                previous != null && next == null -> notifyItemRemoved(0)
                next != null && changed -> notifyItemChanged(0)
            }
        }
        override fun getItemCount(): Int = if (label == null) 0 else 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BulkHolder =
            BulkHolder(dialogs.button("") { runBulkAction() }.apply {
                backdropSource = feed.pageBackdrop
                layoutParams = RecyclerView.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(10) }
            })
        override fun onBindViewHolder(holder: BulkHolder, position: Int) {
            holder.button.text = label.orEmpty()
            holder.button.isEnabled = enabled
        }
    }
    private class BulkHolder(val button: LiquidGlassButton) : RecyclerView.ViewHolder(button)

    private inner class TaskAdapter : ListAdapter<TaskRow, TaskHolder>(object : DiffUtil.ItemCallback<TaskRow>() {
        override fun areItemsTheSame(oldItem: TaskRow, newItem: TaskRow): Boolean = oldItem.task.taskId == newItem.task.taskId
        override fun areContentsTheSame(oldItem: TaskRow, newItem: TaskRow): Boolean = oldItem == newItem
        override fun getChangePayload(oldItem: TaskRow, newItem: TaskRow): Any? =
            if (oldItem.copy(task = oldItem.task.copy(downloadedBytes = newItem.task.downloadedBytes,
                    totalBytes = newItem.task.totalBytes)) == newItem) "progress" else null
    }) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = TaskHolder(feed.trackGlass(GlassActionListItem(parent.context)))
        override fun onBindViewHolder(holder: TaskHolder, position: Int) { holder.bind(getItem(position)) }
        override fun onBindViewHolder(holder: TaskHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty() && payloads.all { it == "progress" }) holder.updateProgress(getItem(position).task)
            else holder.bind(getItem(position))
        }
        override fun onViewRecycled(holder: TaskHolder) {
            Glide.with(this@DownloadManagerActivity).clear(holder.preview)
            holder.thumbnail = null
            super.onViewRecycled(holder)
        }
    }

    private inner class TaskHolder(val card: GlassActionListItem) : RecyclerView.ViewHolder(card) {
        val preview = ImageView(this@DownloadManagerActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val title = label(15f)
        private val fileName = label(12f).apply { maxLines = 2 }
        private val status = label(12f)
        // The library has no determinate progress widget; retain byte progress inside its glass card.
        private val progress = ProgressBar(this@DownloadManagerActivity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        private val action = feed.trackGlass(LiquidGlassButton(this@DownloadManagerActivity)).apply {
            setTextSize(13f)
            textView.setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        var thumbnail: String? = null
        init {
            val details = LinearLayout(this@DownloadManagerActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(title)
                addView(fileName)
                addView(progress, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(6) })
                addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            }
            val summary = LinearLayout(this@DownloadManagerActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(preview, LinearLayout.LayoutParams(dp(64), dp(64)))
                addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            }
            card.contentView = LinearLayout(this@DownloadManagerActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                addView(summary, LinearLayout.LayoutParams(-1, -2))
                addView(action, LinearLayout.LayoutParams(dp(100), dp(48)).apply { gravity = Gravity.END; topMargin = dp(8) })
            }
            card.layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
            applyPalette()
        }

        fun bind(row: TaskRow) {
            val task = row.task
            if (thumbnail != task.thumbnailUrl || preview.drawable == null) {
                thumbnail = task.thumbnailUrl
                Glide.with(this@DownloadManagerActivity).load(task.thumbnailUrl).into(preview)
            }
            title.text = "图片 #${task.imageId}" + if (row.selected) " · 已选择" else ""
            fileName.text = task.fileName
            card.isSelected = row.selected
            ViewCompat.setStateDescription(card, if (row.selected) "已选择" else null)
            card.setOnClickListener { openTask(task) }
            card.setOnLongClickListener { toggleSelection(task.taskId); true }
            action.visibility = if (task.status == DownloadStatus.COMPLETED) View.GONE else View.VISIBLE
            action.text = if (task.status == DownloadStatus.FAILED) "重试" else "取消"
            action.isEnabled = !row.selectionMode
            action.setOnClickListener {
                if (selectedTaskIds.isEmpty()) {
                    val current = queue.state.value.firstOrNull { it.taskId == task.taskId }
                    when (current?.status) {
                        DownloadStatus.FAILED -> queue.retry(task.taskId)
                        DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> queue.cancel(task.taskId)
                        else -> Unit
                    }
                }
            }
            updateProgress(task)
            applyPalette()
        }

        fun updateProgress(task: DownloadTask) {
            progress.visibility = if (task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.DOWNLOADING) View.VISIBLE else View.GONE
            progress.progress = if (task.totalBytes > 0)
                (task.downloadedBytes.toDouble() * 100 / task.totalBytes).toInt().coerceIn(0, 100) else 0
            status.text = when (task.status) {
                DownloadStatus.QUEUED -> "排队中…"
                DownloadStatus.DOWNLOADING -> formatBytes(task.downloadedBytes) +
                    if (task.totalBytes > 0) " / ${formatBytes(task.totalBytes)}（${progress.progress}%）" else "（总大小未知）"
                DownloadStatus.FAILED -> task.errorMessage ?: "下载失败"
                DownloadStatus.COMPLETED -> "下载完成"
            }
        }

        fun applyPalette() {
            listOf(title, fileName, status).forEach { it.setTextColor(GlassWidgetStyle.TEXT_COLOR) }
            progress.progressTintList = ColorStateList.valueOf(GlassWidgetStyle.TEXT_COLOR)
            progress.progressBackgroundTintList = ColorStateList.valueOf(0x40FFFFFF)
            GlassWidgetStyle.apply(card, 16f)
            GlassWidgetStyle.apply(action, 16f)
        }
    }

    private fun label(size: Float) = TextView(this).apply { textSize = size; setTextColor(GlassWidgetStyle.TEXT_COLOR) }
    private fun formatBytes(value: Long): String = when {
        value >= 1_000_000 -> "%.1f MB".format(value / 1_000_000.0)
        value >= 1_000 -> "%.0f KB".format(value / 1_000.0)
        else -> "$value B"
    }

    override fun onResume() {
        super.onResume()
        val colors = PaletteManager.colors(this)
        window.statusBarColor = colors.surface
        window.navigationBarColor = colors.surface
        WindowCompat.getInsetsController(window, feed).apply {
            val light = Color.luminance(colors.surface) > 0.5f
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        feed.setActive(true)
        observation = scope.launch { queue.state.collectLatest { render(it) } }
    }
    override fun onPause() { observation?.cancel(); feed.setActive(false); super.onPause() }
    override fun onDestroy() { feed.setActive(false); scope.cancel(); super.onDestroy() }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
