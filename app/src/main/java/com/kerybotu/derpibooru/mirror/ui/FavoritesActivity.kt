package com.kerybotu.derpibooru.mirror.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.liquidglass.LiquidGlassButton
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.R
import com.kerybotu.derpibooru.mirror.auth.ApiKeyStore
import com.kerybotu.derpibooru.mirror.download.DownloadQueueManager
import com.kerybotu.derpibooru.mirror.favorites.FavoriteFolder
import com.kerybotu.derpibooru.mirror.favorites.FavoriteItem
import com.kerybotu.derpibooru.mirror.favorites.LocalFavoritesStore
import com.kerybotu.derpibooru.mirror.model.Image
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class FavoritesActivity : AppCompatActivity() {
    private sealed interface Row {
        data class Action(val isImport: Boolean) : Row
        data class Folder(val id: Long, val name: String, val count: Int) : Row
        data class Picture(val item: FavoriteItem, val local: Boolean) : Row
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var feed: GlassFeedLayout
    private lateinit var dialogs: GlassPageDialogs
    private lateinit var store: LocalFavoritesStore
    private lateinit var deleteMenu: MenuItem
    private val favoritesAdapter = FavoritesAdapter()
    private var rows: List<Row> = emptyList()
    private var folderId: Long? = null
    private val chosen = mutableSetOf<Int>()
    private var showingServer = false
    private var serverPage = 1
    private var serverLoading = false
    private var serverMore = true
    private var serverFailed = false
    private val serverItems = mutableListOf<FavoriteItem>()
    private var serverJob: Job? = null
    private var importing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        store = LocalFavoritesStore(this)
        feed = GlassFeedLayout(this, "收藏夹") { goBack() }
        dialogs = GlassPageDialogs(feed)
        deleteMenu = feed.toolbar.menu.add("删除").apply {
            setIcon(R.drawable.ic_delete)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            isVisible = false
            setOnMenuItemClickListener { confirmDelete(); true }
        }
        feed.setTabs(listOf("本地收藏夹", "Derpibooru收藏")) { index ->
            if (index == 0) showFolders() else showServer()
        }
        feed.results.layoutManager = GridLayoutManager(this, 2).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int = if (rows.getOrNull(position) is Row.Folder) 1 else 2
            }
        }
        feed.results.adapter = favoritesAdapter
        feed.refresh.setCanRefresh { chosen.isEmpty() && if (showingServer) !serverLoading else !importing }
        feed.refresh.setOnRefreshListener {
            if (showingServer) loadServer(reset = true, fromPull = true)
            else {
                store = LocalFavoritesStore(this)
                renderLocal()
                feed.refresh.isRefreshing = false
            }
        }
        feed.results.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val manager = recyclerView.layoutManager as GridLayoutManager
                if (showingServer && dy > 0 && !serverFailed && manager.findLastVisibleItemPosition() >= rows.size - 5)
                    loadServer(reset = false)
            }
        })
        feed.onPaletteChanged = {
            for (index in 0 until feed.results.childCount) {
                val holder = feed.results.getChildViewHolder(feed.results.getChildAt(index))
                if (holder is PictureHolder) holder.label.setTextColor(GlassWidgetStyle.TEXT_COLOR)
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { goBack() }
        })
        setContentView(feed)
        showFolders()
    }

    private fun goBack() {
        when {
            chosen.isNotEmpty() -> { chosen.clear(); updateTitle(); favoritesAdapter.notifyDataSetChanged() }
            !showingServer && folderId != null -> showFolders()
            else -> finish()
        }
    }

    private fun showFolders() {
        serverJob?.cancel()
        serverLoading = false
        showingServer = false
        folderId = null
        chosen.clear()
        feed.refresh.isRefreshing = false
        feed.showLoading(false)
        renderLocal()
        feed.results.scrollToPosition(0)
    }

    private fun showFolder(id: Long) {
        folderId = id
        chosen.clear()
        renderLocal()
        feed.results.scrollToPosition(0)
    }

    private fun renderLocal() {
        if (showingServer) return
        feed.hideStatus()
        val folder = store.allFolders().firstOrNull { it.id == folderId }
        folderId = folder?.id
        chosen.retainAll(folder?.items?.map { it.imageId }?.toSet() ?: emptySet())
        rows = if (folder == null) {
            listOf(Row.Action(false), Row.Action(true)) + store.allFolders().map { Row.Folder(it.id, it.name, it.items.size) }
        } else folder.items.map { Row.Picture(it, local = true) }
        updateTitle()
        favoritesAdapter.notifyDataSetChanged()
        if (folder != null && folder.items.isEmpty()) feed.showStatus("收藏夹为空", "在图片详情页将图片加入本地收藏夹")
    }

    private fun updateTitle() {
        val folder = store.allFolders().firstOrNull { it.id == folderId }
        feed.toolbar.title = when {
            chosen.isNotEmpty() -> "已选择 ${chosen.size} 项"
            showingServer -> "Derpibooru 收藏"
            folder != null -> "${folder.name}（${folder.items.size}）"
            else -> "收藏夹"
        }
        deleteMenu.isVisible = !showingServer && chosen.isNotEmpty()
    }

    private fun folderMenu(id: Long) {
        val folder = store.allFolders().firstOrNull { it.id == id } ?: return
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (!folder.isDefault) actions += "重命名" to {
            dialogs.prompt("重命名收藏夹", folder.name) { store.renameFolder(id, it); renderLocal() }
        }
        actions += "下载全部" to { downloadFolder(folder) }
        if (!folder.isDefault) actions += "删除收藏夹" to {
            dialogs.show("删除收藏夹？", "其中的本地收藏记录将被删除。此操作不可撤销。", positive = "删除") {
                store.deleteFolder(id)
                renderLocal()
            }
        }
        dialogs.choices(folder.name, actions)
    }

    private fun downloadFolder(folder: FavoriteFolder) {
        DownloadQueueManager.get(this).enqueueImages(folder.items.map {
            Image(it.imageId, "", it.thumbnailUrl, 0, 0, 0, 0, 0, 0, 0, emptyList(), mimeType = it.format)
        })
        dialogs.toast("已加入下载队列")
    }

    private fun toggle(id: Int) {
        if (showingServer || folderId == null) return
        if (!chosen.add(id)) chosen.remove(id)
        updateTitle()
        favoritesAdapter.notifyItemRangeChanged(0, rows.size, "selection")
    }

    private fun confirmDelete() {
        val id = folderId ?: return
        val selection = chosen.toSet()
        if (selection.isEmpty()) return
        dialogs.show("移除本地收藏？", "将从当前收藏夹移除已选择的 ${selection.size} 张图片。", positive = "删除") {
            selection.forEach { store.remove(id, it) }
            chosen.clear()
            renderLocal()
        }
    }

    private fun importFavorites() {
        if (importing) return
        if (!ApiKeyStore.isLoggedIn(this)) { dialogs.toast("请先登录"); return }
        importing = true
        renderLocal()
        scope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    NetworkManager.getApi(this@FavoritesActivity, "search/images?q=my%3Afaves&per_page=50")
                }
                val array = JSONObject(raw ?: error("Empty favorites response")).optJSONArray("images")
                    ?: error("Missing images in response")
                val received = parseItems(array)
                val target = store.allFolders().first { it.isDefault }
                received.forEach { store.add(target.id, it) }
                dialogs.toast("已导入 ${received.size} 张收藏")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                dialogs.toast("导入失败，请重试")
            } finally {
                if (currentCoroutineContext().isActive) {
                    importing = false
                    // Import may finish after navigating into a folder or switching to server favorites.
                    renderLocal()
                }
            }
        }
    }

    private fun showServer() {
        showingServer = true
        folderId = null
        chosen.clear()
        updateTitle()
        loadServer(reset = true)
    }

    private fun loadServer(reset: Boolean, fromPull: Boolean = false) {
        if (!showingServer || (!reset && (serverLoading || !serverMore))) return
        if (reset) {
            serverJob?.cancel()
            serverLoading = false
            serverMore = true
            if (!fromPull) {
                feed.refresh.isRefreshing = false
                serverItems.clear()
                rows = emptyList()
                favoritesAdapter.notifyDataSetChanged()
                feed.results.scrollToPosition(0)
            }
        }
        feed.hideStatus()
        if (!ApiKeyStore.isLoggedIn(this)) {
            serverItems.clear()
            rows = emptyList()
            favoritesAdapter.notifyDataSetChanged()
            feed.showLoading(false)
            feed.refresh.isRefreshing = false
            serverMore = false
            feed.showStatus("请先登录", "登录后查看你的 Derpibooru 收藏")
            return
        }
        serverLoading = true
        serverFailed = false
        feed.showLoading(!fromPull, centered = serverItems.isEmpty())
        val requestedPage = if (reset) 1 else serverPage
        serverJob = scope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    NetworkManager.getApi(this@FavoritesActivity, "search/images?q=my%3Afaves&page=$requestedPage&per_page=50")
                }
                val response = JSONObject(raw ?: error("Empty favorites response"))
                val array = response.optJSONArray("images") ?: error("Missing images in response")
                val received = parseItems(array)
                if (reset) serverItems.clear()
                val start = serverItems.size
                serverItems.addAll(received)
                rows = serverItems.map { Row.Picture(it, local = false) }
                if (reset) favoritesAdapter.notifyDataSetChanged()
                else if (received.isNotEmpty()) favoritesAdapter.notifyItemRangeInserted(start, received.size)
                val total = response.optLong("total", -1)
                serverMore = array.length() >= 50 && (total < 0 || serverItems.size < total)
                serverPage = requestedPage + 1
                if (serverItems.isEmpty()) feed.showStatus("暂无 Derpibooru 收藏", "在站点收藏图片后，下拉刷新查看")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                serverFailed = true
                feed.showStatus(if (reset) "收藏加载失败" else "加载更多失败", "点击重试") { loadServer(reset) }
            } finally {
                if (currentCoroutineContext().isActive) {
                    serverLoading = false
                    feed.showLoading(false)
                    feed.refresh.isRefreshing = false
                }
            }
        }
    }

    private fun parseItems(array: JSONArray): List<FavoriteItem> = (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optInt("id")
        if (id <= 0) return@mapNotNull null
        FavoriteItem(id, item.optJSONObject("representations")?.optString("small")?.takeIf { it.isNotBlank() },
            item.optString("mime_type"))
    }

    private inner class FavoritesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = rows.size
        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Action -> 0; is Row.Folder -> 1; is Row.Picture -> 2
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val holder = when (viewType) {
                0 -> ActionHolder(dialogs.button("") {}.apply { backdropSource = feed.pageBackdrop })
                1 -> FolderHolder(feed.trackGlass(GlassActionListItem(parent.context)).apply {
                    headlineTextView.maxLines = 2
                    headlineTextView.gravity = Gravity.CENTER
                    supportingTextView.gravity = Gravity.CENTER
                    minimumHeight = dp(104)
                })
                else -> PictureHolder(feed.trackGlass(GlassActionListItem(parent.context)))
            }
            holder.itemView.layoutParams = RecyclerView.LayoutParams(-1, if (viewType == 0) dp(52) else -2).apply {
                bottomMargin = dp(10)
                if (viewType == 1) { marginStart = dp(4); marginEnd = dp(4) }
            }
            return holder
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Action -> (holder as ActionHolder).button.apply {
                    text = if (!row.isImport) "+ 新建文件夹" else if (importing) "导入中…" else "导入 Derpibooru 收藏"
                    isEnabled = !row.isImport || !importing
                    setOnClickListener {
                        if (!isEnabled) return@setOnClickListener
                        if (row.isImport) importFavorites() else dialogs.prompt("新建收藏夹") { store.createFolder(it); renderLocal() }
                    }
                }
                is Row.Folder -> (holder as FolderHolder).card.apply {
                    headline = row.name
                    supportingText = "${row.count} 项"
                    setOnClickListener { showFolder(row.id) }
                    setOnLongClickListener { folderMenu(row.id); true }
                    GlassWidgetStyle.apply(this, 16f)
                }
                is Row.Picture -> (holder as PictureHolder).bind(row)
            }
        }
        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is PictureHolder) {
                Glide.with(this@FavoritesActivity).clear(holder.preview)
                holder.boundItem = null
            }
            super.onViewRecycled(holder)
        }
    }

    private class ActionHolder(val button: LiquidGlassButton) : RecyclerView.ViewHolder(button)
    private class FolderHolder(val card: GlassActionListItem) : RecyclerView.ViewHolder(card)
    private inner class PictureHolder(val card: GlassActionListItem) : RecyclerView.ViewHolder(card) {
        val preview = ImageView(this@FavoritesActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val label = TextView(this@FavoritesActivity).apply { textSize = 15f; setPadding(dp(12), 0, 0, 0) }
        var boundItem: FavoriteItem? = null
        init {
            card.contentView = LinearLayout(this@FavoritesActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                addView(preview, LinearLayout.LayoutParams(dp(76), dp(76)))
                addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        fun bind(row: Row.Picture) {
            if (boundItem != row.item) {
                Glide.with(this@FavoritesActivity).load(row.item.thumbnailUrl).into(preview)
                boundItem = row.item
            }
            val selected = row.local && row.item.imageId in chosen
            label.text = "图片 #${row.item.imageId}" + if (selected) "\n✓ 已选择" else ""
            label.setTextColor(GlassWidgetStyle.TEXT_COLOR)
            card.isSelected = selected
            ViewCompat.setStateDescription(card, if (selected) "已选择" else null)
            card.setOnClickListener { if (row.local && chosen.isNotEmpty()) toggle(row.item.imageId) else open(row.item.imageId) }
            card.setOnLongClickListener(if (row.local) View.OnLongClickListener { toggle(row.item.imageId); true } else null)
            card.isLongClickable = row.local
            GlassWidgetStyle.apply(card, 16f)
        }
    }

    private fun open(id: Int) = startActivity(Intent(this, ImageDetailActivity::class.java).putExtra("image_id", id))
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
        store = LocalFavoritesStore(this)
        renderLocal()
        feed.setActive(true)
    }
    override fun onPause() { feed.setActive(false); super.onPause() }
    override fun onDestroy() { feed.setActive(false); scope.cancel(); super.onDestroy() }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
