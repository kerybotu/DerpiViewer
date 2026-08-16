package com.kerybotu.derpibooru.mirror

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.navigation.NavigationView
import com.kerybotu.derpibooru.mirror.databinding.ActivityMainBinding
import com.kerybotu.derpibooru.mirror.model.Image
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import com.kerybotu.derpibooru.mirror.ui.ImageAdapter
import com.kerybotu.derpibooru.mirror.ui.ImageDetailActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var adapter: ImageAdapter

    private var allImages: List<Image> = emptyList()
    private var currentImages: List<Image> = emptyList()
    private var columnCount = 2 // 默认竖屏2列
    private var page = 1
    private var currentQuery = "safe"
    private var loading = false
    private var startupStarted = false
    private var toolbarBasePaddingLeft = 0
    private var toolbarBasePaddingRight = 0
    private var toolbarBasePaddingBottom = 0
    private var bottomBarBaseHeight = 0
    private var fabBaseMarginBottom = 0

    private val activityJob = Job()
    private val activityScope = CoroutineScope(Dispatchers.Main + activityJob)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        PaletteManager.apply(this)

        // 处理状态栏与顶栏重叠问题
        applyWindowInsets()

        setSupportActionBar(binding.toolbar)

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            binding.bottomNavigation.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            when (item.itemId) {
                R.id.tab_home -> true
                R.id.tab_search -> { showSearchDialog(); false }
                R.id.tab_upload -> { Toast.makeText(this, "上传功能即将开放", Toast.LENGTH_SHORT).show(); false }
                R.id.tab_messages -> { Toast.makeText(this, "暂无未读消息", Toast.LENGTH_SHORT).show(); false }
                R.id.tab_profile -> { startActivity(Intent(this, com.kerybotu.derpibooru.mirror.ui.ProfileActivity::class.java)); false }
                else -> false
            }
        }
        binding.fabUpload.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            Toast.makeText(this, "上传功能即将开放", Toast.LENGTH_SHORT).show()
        }

        // 初始化 DrawerLayout 和侧滑菜单
        drawerLayout = binding.drawerLayout
        val toggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            binding.toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        binding.navView.setNavigationItemSelectedListener { menuItem ->
            handleNavigationItemClick(menuItem)
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        // 根据屏幕方向设置初始列数
        columnCount = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 4 else 2

        // 初始化 RecyclerView
        adapter = ImageAdapter(currentImages) { image ->
            val intent = Intent(this, ImageDetailActivity::class.java)
            intent.putExtra("image", image)
            startActivity(intent)
        }
        binding.recyclerView.layoutManager = GridLayoutManager(this, columnCount)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || loading) return
                val lm = rv.layoutManager as GridLayoutManager
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - columnCount * 2) {
                    activityScope.launch { loadPage(page + 1, currentQuery, append = true) }
                }
            }
        })

        // 密度按钮点击事件：切换列数
        binding.btnDensity.setOnClickListener {
            cycleDensity()
        }

        // 密度按钮长按事件：弹出自定义滑块调整列数
        binding.btnDensity.setOnLongClickListener {
            showDensitySliderDialog()
            true
        }

        // 搜索按钮点击事件
        binding.btnSearch.setOnClickListener {
            showSearchDialog()
        }

        // 网络初始化并加载数据
        activityScope.launch {
            startupStarted = true
            try {
                updateStartup("正在优选网络节点…", "正在连接最快的 Cloudflare 节点")
                NetworkManager.init(applicationContext)
                updateStartup("正在准备首页…", "正在预加载最新图片")
                loadPage(1, currentQuery, append = false)
                updateStartup("准备就绪", "欢迎回来")
            } catch (e: Exception) {
                Log.e("MainActivity", "网络初始化失败", e)
                Toast.makeText(this@MainActivity, "网络初始化失败，显示模拟数据", Toast.LENGTH_SHORT).show()
                loadMockImages()
                updateStartup("准备就绪", "当前使用离线预览")
            }
            binding.startupOverlay.postDelayed({ binding.startupOverlay.visibility = View.GONE }, 300)
        }
    }

    /**
     * 处理窗口 insets，动态设置 Toolbar 的顶部 padding 等于状态栏高度。
     */
    private fun applyWindowInsets() {
        toolbarBasePaddingLeft = binding.toolbar.paddingLeft
        toolbarBasePaddingRight = binding.toolbar.paddingRight
        toolbarBasePaddingBottom = binding.toolbar.paddingBottom
        if (bottomBarBaseHeight == 0) {
            bottomBarBaseHeight = binding.bottomNavigation.layoutParams.height
                .takeIf { it > 0 } ?: (64 * resources.displayMetrics.density).toInt()
        }
        if (fabBaseMarginBottom == 0) {
            fabBaseMarginBottom = (binding.fabUpload.layoutParams as? android.view.ViewGroup.MarginLayoutParams)
                ?.bottomMargin ?: (34 * resources.displayMetrics.density).toInt()
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navigationBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val toolbarParams = binding.toolbar.layoutParams
            toolbarParams.height = resources.getDimensionPixelSize(androidx.appcompat.R.dimen.abc_action_bar_default_height_material) + statusBarHeight
            binding.toolbar.layoutParams = toolbarParams
            binding.toolbar.setPadding(
                toolbarBasePaddingLeft,
                statusBarHeight,
                toolbarBasePaddingRight,
                toolbarBasePaddingBottom
            )

            // BottomNavigationView must own the navigation-bar safe area instead of
            // letting the system draw over its labels and touch targets.
            val bottomParams = binding.bottomNavigation.layoutParams
            bottomParams.height = bottomBarBaseHeight + navigationBarHeight
            binding.bottomNavigation.layoutParams = bottomParams
            binding.bottomNavigation.setPadding(
                binding.bottomNavigation.paddingLeft,
                binding.bottomNavigation.paddingTop,
                binding.bottomNavigation.paddingRight,
                navigationBarHeight
            )

            val fabParams = binding.fabUpload.layoutParams as? android.view.ViewGroup.MarginLayoutParams
            fabParams?.let {
                it.bottomMargin = fabBaseMarginBottom + navigationBarHeight
                binding.fabUpload.layoutParams = it
            }

            binding.recyclerView.setPadding(
                binding.recyclerView.paddingLeft,
                binding.recyclerView.paddingTop,
                binding.recyclerView.paddingRight,
                bottomBarBaseHeight + navigationBarHeight + (8 * resources.displayMetrics.density).toInt()
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun cycleDensity() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        columnCount = when {
            landscape -> if (columnCount >= 6) 4 else columnCount + 1
            else -> if (columnCount >= 4) 2 else columnCount + 1
        }
        updateGridColumns()
        Toast.makeText(this, "排列密度：$columnCount 列", Toast.LENGTH_SHORT).show()
    }

    private fun showDensitySliderDialog() {
        val seekBar = SeekBar(this)
        seekBar.max = 5 // 2到6列
        seekBar.progress = columnCount - 2

        val dialog = AlertDialog.Builder(this)
            .setTitle("调整排列密度")
            .setView(seekBar)
            .setPositiveButton("确定") { _, _ ->
                columnCount = seekBar.progress + 2
                updateGridColumns()
                Toast.makeText(this, "已设置为 $columnCount 列", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
    }

    private fun updateGridColumns() {
        (binding.recyclerView.layoutManager as GridLayoutManager).spanCount = columnCount
        binding.recyclerView.adapter?.notifyDataSetChanged()
    }

    private fun handleNavigationItemClick(item: MenuItem) {
        when (item.itemId) {
            R.id.nav_forums -> Toast.makeText(this, "论坛", Toast.LENGTH_SHORT).show()
            R.id.nav_tags -> Toast.makeText(this, "标签", Toast.LENGTH_SHORT).show()
            R.id.nav_rankings -> Toast.makeText(this, "排行榜", Toast.LENGTH_SHORT).show()
            R.id.nav_filters -> startActivity(Intent(this, com.kerybotu.derpibooru.mirror.ui.FilterActivity::class.java))
            R.id.nav_galleries -> Toast.makeText(this, "图库", Toast.LENGTH_SHORT).show()
            R.id.nav_comments -> Toast.makeText(this, "评论", Toast.LENGTH_SHORT).show()
            R.id.nav_channels -> Toast.makeText(this, "频道", Toast.LENGTH_SHORT).show()
            R.id.nav_settings -> startActivity(Intent(this, com.kerybotu.derpibooru.mirror.ui.SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if (startupStarted && !NetworkManager.isReady()) {
            activityScope.launch {
                runCatching { NetworkManager.init(applicationContext) }
            }
        }
    }

    private fun showSearchDialog() {
        val editText = EditText(this)
        editText.hint = "输入标签搜索（例如 safe, artist:xxx）"
        val dialog = AlertDialog.Builder(this)
            .setTitle("搜索")
            .setView(editText)
            .setPositiveButton("搜索") { _, _ ->
                val query = editText.text.toString().trim()
                if (query.isNotEmpty()) activityScope.launch { loadPage(1, query, append = false) }
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
    }

    private suspend fun loadRealImages() {
        binding.progressBar.visibility = View.VISIBLE
        val json = withContext(Dispatchers.IO) {
                NetworkManager.getApi(this@MainActivity, "search/images?q=safe&per_page=20&sf=created_at&sd=desc")
        }

        if (json.isNullOrBlank()) {
            Log.e("MainActivity", "API 返回内容为空")
            Toast.makeText(this, "API 请求失败：返回内容为空", Toast.LENGTH_SHORT).show()
            loadMockImages()
            binding.progressBar.visibility = View.GONE
            return
        }

        Log.d("MainActivity", "API 返回 JSON（前 500 字符）: ${json.take(500)}")

        try {
            val images = parseImages(json)
            if (images.isNotEmpty()) {
                allImages = images
                currentImages = images
                adapter.updateData(images)
                Toast.makeText(this, "加载成功：${images.size} 张图片", Toast.LENGTH_SHORT).show()
            } else {
                Log.w("MainActivity", "解析成功但图片列表为空")
                Toast.makeText(this, "没有找到图片", Toast.LENGTH_SHORT).show()
                loadMockImages()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "解析 API 数据失败", e)
            Toast.makeText(this, "API 请求失败：解析错误 ${e.message}", Toast.LENGTH_SHORT).show()
            loadMockImages()
        }
        binding.progressBar.visibility = View.GONE
    }

    private suspend fun loadPage(targetPage: Int, query: String, append: Boolean) {
        if (loading) return
        loading = true
        binding.progressBar.visibility = View.VISIBLE
        try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val filterParam = AppSettings.getCurrentFilterId(this@MainActivity)?.let { "&filter_id=$it" } ?: ""
            val json = withContext(Dispatchers.IO) {
                NetworkManager.getApi(this@MainActivity, "search/images?q=$encoded&per_page=30&page=$targetPage&sf=created_at&sd=desc$filterParam")
            }
            val images = json?.let { parseImages(it) }.orEmpty()
            if (!append) {
                page = 1; currentQuery = query; allImages = images; currentImages = images
                adapter.updateData(images)
            } else if (images.isNotEmpty()) {
                page = targetPage; allImages = allImages + images; currentImages = allImages; adapter.updateData(allImages)
            }
            if (images.isEmpty() && !append) Toast.makeText(this, "没有找到匹配图片", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "加载图片失败", e)
            if (!append) Toast.makeText(this, "网络异常，请稍后重试", Toast.LENGTH_SHORT).show()
        } finally {
            loading = false; binding.progressBar.visibility = View.GONE
        }
    }

    private fun parseImages(json: String): List<Image> {
        val root = JSONObject(json)
        val imagesArray = root.optJSONArray("images")
        if (imagesArray == null) {
            Log.e("MainActivity", "返回 JSON 中没有 images 数组")
            return emptyList()
        }
        val result = mutableListOf<Image>()
        for (i in 0 until imagesArray.length()) {
            val obj = imagesArray.getJSONObject(i)
            val id = obj.optInt("id", -1)
            val width = obj.optInt("width", 0)
            val height = obj.optInt("height", 0)
            val score = obj.optInt("score", 0)
            val faves = obj.optInt("faves", 0)
            val upvotes = obj.optInt("upvotes", 0)
            val downvotes = obj.optInt("downvotes", 0)
            val commentCount = obj.optInt("comment_count", 0)

            val tagsArray = obj.optJSONArray("tags")
            val tags = mutableListOf<String>()
            if (tagsArray != null) {
                for (j in 0 until tagsArray.length()) {
                    tags.add(tagsArray.optString(j, ""))
                }
            }

            val representations = obj.optJSONObject("representations")
            val thumbnailUrl = (if (AppSettings.isHighResolution(this)) representations?.optString("medium", null) else representations?.optString("small", null))
                ?: representations?.optString("small", null)
                ?: representations?.optString("thumb", null)

            result.add(
                Image(
                    id = id,
                    title = "",
                    thumbnailUrl = thumbnailUrl,
                    width = width,
                    height = height,
                    score = score,
                    faves = faves,
                    upvotes = upvotes,
                    downvotes = downvotes,
                    commentCount = commentCount,
                    tags = tags,
                    fullUrl = representations?.optString("full", null),
                    uploader = obj.optString("uploader", null),
                    createdAt = obj.optString("created_at", null),
                    description = obj.optString("description", null)
                )
            )
        }
        return result
    }

    private fun loadMockImages() {
        val mock = listOf(
            Image(
                id = 3862014,
                title = "",
                thumbnailUrl = null,
                width = 1080,
                height = 1440,
                score = 1098,
                faves = 734,
                upvotes = 1103,
                downvotes = 5,
                commentCount = 28,
                tags = listOf("safe", "artist:anoraknr", "gif")
            ),
            Image(
                id = 3862015,
                title = "",
                thumbnailUrl = null,
                width = 1600,
                height = 900,
                score = 520,
                faves = 312,
                upvotes = 550,
                downvotes = 30,
                commentCount = 15,
                tags = listOf("safe", "rainbow dash")
            )
        )
        allImages = mock
        currentImages = mock
        adapter.updateData(mock)
    }

    private fun updateStartup(status: String, detail: String) {
        binding.startupStatus.text = status
        binding.startupDetail.text = detail
    }

    override fun onDestroy() {
        activityJob.cancel()
        NetworkManager.shutdown()
        super.onDestroy()
    }
}
