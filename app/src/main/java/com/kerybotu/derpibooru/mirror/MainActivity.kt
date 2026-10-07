package com.kerybotu.derpibooru.mirror

import android.content.Intent
import android.graphics.Outline
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.view.ViewOutlineProvider
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
import com.google.android.material.appbar.AppBarLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.navigation.NavigationView
import com.kerybotu.derpibooru.mirror.databinding.ActivityMainBinding
import com.kerybotu.derpibooru.mirror.model.Image
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import com.kerybotu.derpibooru.mirror.network.ResourceCoordinator
import com.kerybotu.derpibooru.mirror.ui.ImageAdapter
import com.kerybotu.derpibooru.mirror.ui.ImageDetailActivity
import com.kerybotu.derpibooru.mirror.ui.FilterCache
import com.kerybotu.derpibooru.mirror.ui.CdnImageGate
import com.kerybotu.derpibooru.mirror.ui.AdaptiveLayoutPolicy
import com.kerybotu.derpibooru.mirror.ui.Ui2DesignSystem
import com.kerybotu.derpibooru.mirror.ui.HomeTopIslandController
import com.kerybotu.derpibooru.mirror.ui.GlassWidgetStyle
import com.example.liquidglass.LiquidGlassTabBar
import com.kerybotu.derpibooru.mirror.download.DownloadQueueManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.kerybotu.derpibooru.mirror.update.AppUpdateManager
import com.kerybotu.derpibooru.mirror.update.UpdateUi

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var adapter: ImageAdapter
    private lateinit var drawerToggle: ActionBarDrawerToggle

    private var allImages: List<Image> = emptyList()
    private var currentImages: List<Image> = emptyList()
    private var columnCount = 2 // 默认竖屏2列
    private var page = 1
    private var currentQuery = "*"
    private var currentSort = "created_at"
    private var loading = false
    private var homeHasLoaded = false
    private var startupStarted = false
    private var appliedFilterId: Int? = null
    private var lastPrefetchedFrom = -1
    private var toolbarBasePaddingLeft = 0
    private var toolbarBasePaddingRight = 0
    private var toolbarBasePaddingBottom = 0
    private var fabBaseMarginBottom = 0
    private var navigationBarInsetBottom = 0
    private var artworkBasePaddingLeft = 0
    private var artworkBasePaddingRight = 0
    private var artworkBasePaddingTop = 0
    private var artworkBasePaddingBottom = 0
    private var homeLoadJob: Job? = null
    private var featuredPanel: com.kerybotu.derpibooru.mirror.ui.FeaturedPanel? = null
    private var lastPaletteSignature: String? = null
    private var lastUi2Enabled: Boolean? = null
    private var shellGlassResumed = false
    private var selectedPrimaryId: Int = R.id.tab_home
    private var embeddedVideo: com.kerybotu.derpibooru.mirror.ui.EmbeddedVideoView? = null
    private var embeddedFeatured: com.kerybotu.derpibooru.mirror.ui.FeaturedPanel? = null
    private var embeddedMessages: com.kerybotu.derpibooru.mirror.ui.EmbeddedMessagesView? = null
    private var embeddedProfile: com.kerybotu.derpibooru.mirror.ui.EmbeddedProfileView? = null
    private var embeddedScreenId: Int = R.id.tab_home
    private var currentStatusBarHeight = 0
    private var homeTopIslandController: HomeTopIslandController? = null
    private var glassMenuEnabled = false

    private val activityJob = Job()
    private val activityScope = CoroutineScope(Dispatchers.Main + activityJob)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        PaletteManager.apply(this)
        lastPaletteSignature = paletteSignature()
        applyStartupPalette()

        // 处理状态栏与顶栏重叠问题
        applyWindowInsets()
        // The top island is measured after insets are applied and can change size
        // on rotation, window resizing, or when its visibility is restored. Keep
        // the grid's initial content offset tied to that measured size without
        // changing the full-screen overlay layout or touching padding while scrolling.
        binding.appBarLayout.addOnLayoutChangeListener { _, left, top, right, bottom,
                                                         oldLeft, oldTop, oldRight, oldBottom ->
            if (left == oldLeft && top == oldTop && right == oldRight && bottom == oldBottom) {
                return@addOnLayoutChangeListener
            }
            binding.appBarLayout.post {
                if (isFinishing || isDestroyed) return@post
                updateArtworkTopPadding()
                homeTopIslandController?.onLayoutChanged()
            }
        }

        setSupportActionBar(binding.toolbar)
        homeTopIslandController = HomeTopIslandController(binding.appBarLayout, binding.toolbar)

        val navigationIds = listOf(R.id.tab_home, R.id.tab_video_feed, R.id.tab_featured, R.id.tab_messages, R.id.tab_profile)
        val navigationTabs = listOf(
            LiquidGlassTabBar.TabItem("首页", getDrawable(R.drawable.ic_home)),
            LiquidGlassTabBar.TabItem("视频", getDrawable(R.drawable.ic_video)),
            LiquidGlassTabBar.TabItem("热门", getDrawable(R.drawable.ic_whatshot)),
            LiquidGlassTabBar.TabItem("评论", getDrawable(R.drawable.ic_comment)),
            LiquidGlassTabBar.TabItem("我的", getDrawable(R.drawable.ic_account_box))
        )
        binding.glassBottomNavigation.configureTabs(navigationTabs)
        binding.glassBottomNavigation.onItemSelected = { index -> handlePrimaryNavigation(navigationIds[index]) }
        binding.glassBottomNavigation.onItemReselected = { index -> if (index == 0) resetHomeAndRefresh() }
        applyUi2Shell()
        configureUploadFab()

        // 初始化 DrawerLayout 和侧滑菜单
        drawerLayout = binding.drawerLayout
        drawerToggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            binding.toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(drawerToggle)
        drawerToggle.syncState()
        updateNavigationMenuMode()
        val toolbarColors = PaletteManager.colors(this)
        tintToolbarNavigationIcon(
            toolbarColors.onSurface
        )

        binding.navView.setNavigationItemSelectedListener { menuItem ->
            handleNavigationItemClick(menuItem)
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        // 根据屏幕方向设置初始列数
        columnCount = AdaptiveLayoutPolicy.artworkColumnCount(this)

        // 初始化 RecyclerView
        adapter = ImageAdapter(currentImages, { image ->
            val intent = Intent(this, ImageDetailActivity::class.java)
            intent.putExtra("image", image)
            startActivity(intent)
        }, { count -> updateSelectionUi(count) }, this)
        binding.recyclerView.layoutManager = GridLayoutManager(this, columnCount)
        AdaptiveLayoutPolicy.configureArtworkGrid(this, binding.recyclerView)
        artworkBasePaddingLeft = binding.recyclerView.paddingLeft
        artworkBasePaddingRight = binding.recyclerView.paddingRight
        artworkBasePaddingTop = binding.recyclerView.paddingTop
        // The XML bottom padding is a legacy navigation-bar workaround.  In
        // the island layout the navigation surface overlays the grid, so use
        // the grid's own small spacing instead of reserving island height.
        artworkBasePaddingBottom = dp(4)
        binding.recyclerView.adapter = adapter
        binding.homeRefresh.scrollTarget = binding.recyclerView
        binding.homeRefresh.setCanRefresh { !loading && binding.startupOverlay.visibility != View.VISIBLE }
        initializeEmbeddedScreens()
        binding.homeRefresh.setOnRefreshListener {
            if (featuredPanel?.visibility == View.VISIBLE) {
                featuredPanel?.refresh()
            } else {
                launchHomePage(1, currentQuery, append = false)
            }
        }
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    if (embeddedScreenId == R.id.tab_home) {
                        homeTopIslandController?.onScrollIdle(rv)
                    }
                    val lm = rv.layoutManager as GridLayoutManager
                    val distance = ResourceCoordinator.imagePreloadDistance(this@MainActivity)
                    CdnImageGate.prefetch(this@MainActivity, currentImages.drop(lm.findLastVisibleItemPosition() + 1).map { it.thumbnailUrl }, columnCount * distance)
                } else {
                    CdnImageGate.pausePrefetch(this@MainActivity)
                }
            }

            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (embeddedScreenId == R.id.tab_home) homeTopIslandController?.onScrolled(dy)

                if (dy <= 0 || loading) return
                val lm = rv.layoutManager as GridLayoutManager
                val firstPrefetch = lm.findLastVisibleItemPosition() + 1
                if (firstPrefetch > lastPrefetchedFrom) {
                    lastPrefetchedFrom = firstPrefetch
                    CdnImageGate.prefetch(
                        this@MainActivity,
                        currentImages.drop(firstPrefetch).map { it.thumbnailUrl },
                        limit = columnCount * ResourceCoordinator.imagePreloadDistance(this@MainActivity)
                    )
                }
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - columnCount * 2) {
                    launchHomePage(page + 1, currentQuery, append = true)
                }
            }
        })
        binding.appBarLayout.addOnOffsetChangedListener(AppBarLayout.OnOffsetChangedListener { appBar, verticalOffset ->
            // Home scrolling is controlled directly from RecyclerView dy. Avoid a
            // second AppBarLayout offset source overwriting the finger-following state.
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
            startActivity(Intent(this, com.kerybotu.derpibooru.mirror.ui.SearchActivity::class.java))
        }

        // 网络初始化并加载数据
        activityScope.launch {
            startupStarted = true
            try {
                updateStartup("正在优选网络节点…", "正在连接最快的 Cloudflare 节点")
                NetworkManager.init(applicationContext) { domain, tested, total ->
                    runOnUiThread {
                        updateStartup("正在测速 $domain", "已完成 $tested / $total 个节点")
                    }
                }
                activityScope.launch { preloadSystemFilters() }
                applyAntiEmbarrassmentFilterIfNeeded()
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
        activityScope.launch {
            kotlinx.coroutines.delay(1200)
            val update = AppUpdateManager.check(this@MainActivity)
            if (update != null && !isFinishing) UpdateUi.show(this@MainActivity, update, activityScope)
        }
    }

    /**
     * 处理窗口 insets，动态设置 Toolbar 的顶部 padding 等于状态栏高度。
     */
    private fun applyWindowInsets() {
        // The root listener below already places the island above the system bar
        // and supplies legacy padding. Material must not add that inset again
        // inside the 64dp island, where it would clip the navigation labels.
        toolbarBasePaddingLeft = binding.toolbar.paddingLeft
        toolbarBasePaddingRight = binding.toolbar.paddingRight
        toolbarBasePaddingBottom = binding.toolbar.paddingBottom
        if (fabBaseMarginBottom == 0) {
            fabBaseMarginBottom = (binding.fabUploadContainer.layoutParams as? android.view.ViewGroup.MarginLayoutParams)
                ?.bottomMargin ?: (34 * resources.displayMetrics.density).toInt()
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            currentStatusBarHeight = statusBarHeight
            val navigationInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val navigationBarHeight = navigationInsets.bottom
            navigationBarInsetBottom = navigationBarHeight
            val landscape = AdaptiveLayoutPolicy.isLandscape(this)
            val safeHorizontalInsets = navigationInsets.left + navigationInsets.right
            val islandGutter = dp(Ui2DesignSystem.Spacing.islandMargin)
            val rootWidth = binding.root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val availableIslandWidth = (rootWidth - safeHorizontalInsets - islandGutter * 2).coerceAtLeast(1)
            homeTopIslandController?.reset()
            val toolbarParams = binding.toolbar.layoutParams
            toolbarParams.height = dp(60)
            binding.toolbar.layoutParams = toolbarParams
            binding.toolbar.minimumHeight = 0
            binding.appBarLayout.minimumHeight = 0
            binding.toolbar.setPadding(
                toolbarBasePaddingLeft,
                0,
                toolbarBasePaddingRight,
                toolbarBasePaddingBottom
            )

            val appBarParams = binding.appBarLayout.layoutParams as android.view.ViewGroup.MarginLayoutParams
            appBarParams.width = AdaptiveLayoutPolicy.topIslandWidthPx(this, availableIslandWidth, landscape)
            appBarParams.height = -2
            appBarParams.topMargin = statusBarHeight + dp(Ui2DesignSystem.Spacing.sm)
            appBarParams.bottomMargin = 0
            appBarParams.marginStart = 0
            appBarParams.marginEnd = 0
            if (appBarParams is androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) {
                appBarParams.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            }
            binding.appBarLayout.layoutParams = appBarParams
            binding.appBarLayout.post { homeTopIslandController?.onLayoutChanged() }

            // Landscape uses the same bottom island as portrait. The legacy
            // side rail was a separate layout system and caused the oversized
            // right-hand navigation surface.

            val refreshParams = binding.homeRefresh.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
            refreshParams.behavior = null
            // Top/bottom/side islands are overlays.  Keep the refresh/grid
            // surface full-screen so artwork can continue behind them.
            refreshParams.leftMargin = 0
            refreshParams.rightMargin = 0
            binding.homeRefresh.layoutParams = refreshParams

            val bottomParams = binding.glassBottomNavigation.layoutParams as android.view.ViewGroup.MarginLayoutParams
            // 64dp keeps the floating island compact while fitting both icon and label.
            bottomParams.height = dp(64)
            bottomParams.width = AdaptiveLayoutPolicy.bottomIslandWidthPx(this, availableIslandWidth)
            if (bottomParams is androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) {
                bottomParams.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            }
            bottomParams.marginStart = 0
            bottomParams.marginEnd = 0
            bottomParams.bottomMargin = navigationBarHeight + dp(Ui2DesignSystem.Spacing.md)
            binding.glassBottomNavigation.layoutParams = bottomParams

            val fabParams = binding.fabUploadContainer.layoutParams as? android.view.ViewGroup.MarginLayoutParams
            fabParams?.let {
                it.bottomMargin = binding.glassBottomNavigation.height + navigationBarHeight + fabBaseMarginBottom
                it.marginEnd = navigationInsets.right + dp(Ui2DesignSystem.Spacing.xl)
                if (it is androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) {
                    it.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                }
                binding.fabUploadContainer.layoutParams = it
            }

            binding.glassBottomNavigation.post {
                if (!isFinishing) updateMainContentInsets(refreshParams)
            }
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun updateMainContentInsets(
        refreshParams: androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
    ) {
        // Navigation islands remain visual overlays and never constrain the
        // full-screen grid's measured width or height.
        refreshParams.leftMargin = 0
        refreshParams.rightMargin = 0
        binding.homeRefresh.layoutParams = refreshParams

        val navHeight = binding.glassBottomNavigation.measuredHeight
        val leftInset = artworkBasePaddingLeft
        val rightInset = artworkBasePaddingRight
        val topPadding = topIslandContentPadding()
        val bottomInset = artworkBasePaddingBottom
        binding.recyclerView.setPadding(leftInset, topPadding, rightInset, bottomInset)
        binding.homeRefresh.contentTopInset = topPadding
        binding.recyclerView.clipToPadding = false
        binding.appBarLayout.post {
            if (!isFinishing) updateArtworkTopPadding()
        }
        binding.homeRefresh.post {
            if (binding.homeRefresh.width <= 0) return@post
            val availableWidthDp = (binding.homeRefresh.width / resources.displayMetrics.density).toInt()
            columnCount = AdaptiveLayoutPolicy.artworkColumnCountForWidth(availableWidthDp)
            (binding.recyclerView.layoutManager as? GridLayoutManager)?.spanCount = columnCount
        }

        (binding.fabUploadContainer.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { params ->
            params.bottomMargin = navHeight + navigationBarInsetBottom +
                dp(Ui2DesignSystem.Spacing.md + Ui2DesignSystem.Spacing.islandGap)
            params.marginEnd = navigationBarInsetBottom + dp(Ui2DesignSystem.Spacing.xl)
            if (params is androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) {
                params.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            }
            binding.fabUploadContainer.layoutParams = params
        }
        // Video controls have their own relationship to the Bottom Island;
        // keep that safe-area calculation independent from the artwork grid.
        val videoBottomInset = navHeight + navigationBarInsetBottom + dp(Ui2DesignSystem.Spacing.xl)
        embeddedVideo?.setNavigationInsets(videoBottomInset, 0, false)
        embeddedProfile?.setBottomInset(videoBottomInset)
    }

    /** Keeps the grid full-screen while offsetting only its initial content position. */
    private fun updateArtworkTopPadding() {
        if (!::binding.isInitialized) return
        val topPadding = topIslandContentPadding()
        if (binding.recyclerView.paddingTop == topPadding) return
        binding.recyclerView.setPadding(
            binding.recyclerView.paddingLeft,
            topPadding,
            binding.recyclerView.paddingRight,
            binding.recyclerView.paddingBottom
        )
        binding.recyclerView.clipToPadding = false
    }

    private fun topIslandContentPadding(): Int {
        val appBar = binding.appBarLayout
        if (appBar.visibility != View.VISIBLE || appBar.height <= 0) {
            return artworkBasePaddingTop
        }
        val topMargin = (appBar.layoutParams as? android.view.ViewGroup.MarginLayoutParams)
            ?.topMargin?.coerceAtLeast(0) ?: 0
        return topMargin + appBar.height + dp(Ui2DesignSystem.Spacing.sm)
    }

    private fun cycleDensity() {
        val maxColumns = AdaptiveLayoutPolicy.artworkColumnCount(this).coerceAtLeast(2)
        columnCount = if (columnCount >= maxColumns) 2 else columnCount + 1
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
        val grid = binding.recyclerView.layoutManager as GridLayoutManager
        grid.spanCount = columnCount.coerceAtMost(AdaptiveLayoutPolicy.artworkColumnCount(this))
        columnCount = grid.spanCount
        binding.recyclerView.adapter?.notifyDataSetChanged()
    }

    private fun handleNavigationItemClick(item: MenuItem) {
        when (item.itemId) {
            R.id.nav_forums -> startActivity(Intent(this, com.kerybotu.derpibooru.mirror.ui.ForumActivity::class.java))
            R.id.nav_tags -> startActivity(Intent(this, com.kerybotu.derpibooru.mirror.ui.TagSearchActivity::class.java))
            R.id.nav_rankings -> showRankings()
            R.id.nav_filters -> startActivity(Intent(this, com.kerybotu.derpibooru.mirror.ui.FilterActivity::class.java))
            R.id.nav_galleries -> startActivity(Intent(this, com.kerybotu.derpibooru.mirror.ui.GalleryActivity::class.java))
            R.id.nav_comments -> startActivity(Intent(this, com.kerybotu.derpibooru.mirror.ui.RecentCommentsActivity::class.java))
            R.id.nav_downloads -> startActivity(Intent(this, com.kerybotu.derpibooru.mirror.ui.DownloadManagerActivity::class.java))
            R.id.nav_favorites -> startActivity(Intent(this, com.kerybotu.derpibooru.mirror.ui.FavoritesActivity::class.java))
            R.id.nav_settings -> startActivity(Intent(this, com.kerybotu.derpibooru.mirror.ui.SettingsActivity::class.java))
        }
    }

    private fun handlePrimaryNavigation(itemId: Int): Boolean {
        binding.glassBottomNavigation.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        clearSelectionState()
        if (itemId !in setOf(R.id.tab_home, R.id.tab_video_feed, R.id.tab_featured, R.id.tab_messages, R.id.tab_profile)) return false
        if (itemId == R.id.tab_featured) showFeaturedContent() else showEmbeddedScreen(itemId)
        selectedPrimaryId = itemId
        syncNavigationSelection(itemId)
        return true
    }

    private fun syncNavigationSelection(itemId: Int) {
        val index = when (itemId) {
            R.id.tab_home -> 0
            R.id.tab_video_feed -> 1
            R.id.tab_featured -> 2
            R.id.tab_messages -> 3
            R.id.tab_profile -> 4
            else -> return
        }
        if (::binding.isInitialized) {
            binding.glassBottomNavigation.selectItem(index)
        }
    }

    private fun initializeEmbeddedScreens() {
        val host = binding.mainContentHost
        embeddedVideo = com.kerybotu.derpibooru.mirror.ui.EmbeddedVideoView(this)
        embeddedFeatured = com.kerybotu.derpibooru.mirror.ui.FeaturedPanel(this).also {
            it.onRefreshFinished = { binding.homeRefresh.isRefreshing = false }
            it.onSelectionChanged = { updateSelectionUi(it) }
        }
        featuredPanel = embeddedFeatured
        embeddedMessages = com.kerybotu.derpibooru.mirror.ui.EmbeddedMessagesView(this)
        embeddedProfile = com.kerybotu.derpibooru.mirror.ui.EmbeddedProfileView(this)
        host.addView(embeddedVideo, android.widget.FrameLayout.LayoutParams(-1, -1))
        host.addView(embeddedFeatured, android.widget.FrameLayout.LayoutParams(-1, -1))
        host.addView(embeddedMessages, android.widget.FrameLayout.LayoutParams(-1, -1))
        host.addView(embeddedProfile, android.widget.FrameLayout.LayoutParams(-1, -1))
        embeddedVideo?.visibility = View.GONE
        embeddedFeatured?.visibility = View.GONE
        embeddedMessages?.visibility = View.GONE
        embeddedProfile?.visibility = View.GONE
    }

    private fun showEmbeddedScreen(itemId: Int) {
        val host = binding.mainContentHost
        val target: View? = when (itemId) {
            R.id.tab_home -> null
            R.id.tab_video_feed -> embeddedVideo
            R.id.tab_featured -> embeddedFeatured
            R.id.tab_messages -> embeddedMessages
            R.id.tab_profile -> embeddedProfile
            else -> null
        }
        if (itemId == R.id.tab_home) {
            host.visibility = View.GONE
            binding.appBarLayout.visibility = View.VISIBLE
            binding.homeRefresh.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.VISIBLE
            binding.appBarLayout.post {
                updateArtworkTopPadding()
                homeTopIslandController?.onLayoutChanged()
                homeTopIslandController?.restoreForHome()
            }
            embeddedVideo?.setActive(false)
            // Returning to Home restores the existing adapter and scroll position.
            // A refresh is reserved for an explicit reselection of the Home tab.
            if ((!homeHasLoaded || allImages.isEmpty()) && !loading && homeLoadJob?.isActive != true) {
                launchHomePage(1, currentQuery, append = false)
            }
        } else if (target != null) {
            binding.homeRefresh.visibility = View.GONE
            binding.appBarLayout.visibility = View.GONE
            host.visibility = View.VISIBLE
            listOf(embeddedVideo, embeddedFeatured, embeddedMessages, embeddedProfile).forEach { it?.visibility = if (it === target) View.VISIBLE else View.GONE }
            embeddedVideo?.setActive(target === embeddedVideo)
            if (itemId == R.id.tab_featured) {
                // Keep the in-memory featured list and scroll position when
                // returning from another primary tab. Pull-to-refresh remains
                // the explicit path for requesting fresh content.
                embeddedFeatured?.ensureLoaded()
            }
            if (embeddedScreenId != itemId) {
                target.alpha = 0f; target.translationX = dp(12).toFloat()
                target.animate().alpha(1f).translationX(0f).setDuration(220L).start()
            }
        }
        embeddedScreenId = itemId
        updateShellGlass()
    }

    private suspend fun applyAntiEmbarrassmentFilterIfNeeded() {
        if (!AppSettings.isAntiEmbarrassmentEnabled(this)) return

        var filterId = AppSettings.getAntiEmbarrassmentFilterId(this)
        var filterName = AppSettings.getAntiEmbarrassmentFilterName(this)

        if (filterId == null && filterName.equals("Default", ignoreCase = true)) {
            val cached = FilterCache.getSystemFilters(this)
            val json = cached ?: NetworkManager.getApi(this, "filters/system?page=1")?.also {
                FilterCache.saveSystemFilters(this, it)
            }
            val defaultFilter = json?.let(::findDefaultSystemFilter)
            if (defaultFilter != null) {
                filterId = defaultFilter.first
                filterName = defaultFilter.second
                AppSettings.setAntiEmbarrassmentFilter(this, filterId, filterName)
            }
        }

        if (filterId != null) {
            AppSettings.setCurrentFilter(this, filterId, filterName)
            appliedFilterId = filterId
        } else {
            Log.w("MainActivity", "防社死已开启，但 Default 过滤器尚未能解析")
        }
    }

    private fun findDefaultSystemFilter(json: String): Pair<Int, String>? {
        val filters = runCatching { JSONObject(json).optJSONArray("filters") }.getOrNull() ?: return null
        for (index in 0 until filters.length()) {
            val filter = filters.optJSONObject(index) ?: continue
            if (filter.optString("name").equals("Default", ignoreCase = true)) {
                val id = filter.optInt("id", -1)
                if (id > 0) return id to filter.optString("name", "Default")
            }
        }
        return null
    }

    private fun configureUploadFab() {
        animateFabAction(R.drawable.ic_add, false)
        Ui2DesignSystem.applyPressFeedback(binding.fabUpload)
        Ui2DesignSystem.applyPressFeedback(binding.fabUploadGlass, binding.fabUploadGlass.imageView)
        val onClick = View.OnClickListener {
            val featuredCount = featuredPanel?.selectedImages()?.size ?: 0
            val selected = if (featuredPanel?.visibility == View.VISIBLE && featuredCount > 0) {
                featuredPanel!!.selectedImages()
            } else adapter.selectedItems()
            if (selected.isNotEmpty()) {
                DownloadQueueManager.get(this).enqueueImages(selected)
                adapter.clearSelection(); featuredPanel?.clearSelection()
                Toast.makeText(this, "已加入下载队列", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "上传功能即将开放", Toast.LENGTH_SHORT).show()
            }
        }
        binding.fabUpload.setOnClickListener(onClick)
        binding.fabUploadGlass.setOnClickListener(onClick)
    }

    private fun clearSelectionState() {
        adapter.clearSelection()
        featuredPanel?.clearSelection()
    }

    private fun updateSelectionUi(count: Int) {
        val featuredCount = featuredPanel?.selectedImages()?.size ?: 0
        val total = if (featuredPanel?.visibility == View.VISIBLE) featuredCount else count
        if (total > 0) {
            binding.toolbar.title = "已选择${total}张图片"
            binding.toolbar.setNavigationIcon(R.drawable.ic_close)
            binding.toolbar.setNavigationOnClickListener { adapter.clearSelection(); featuredPanel?.clearSelection() }
            binding.btnDensity.visibility = View.GONE
            binding.btnSearch.visibility = View.GONE
            animateFabAction(R.drawable.ic_download, true)
        } else {
            binding.toolbar.title = "DerpiViewer"
            // Restore the drawer toggle after the temporary selection close action.
            updateNavigationMenuMode()
            binding.btnDensity.visibility = View.VISIBLE
            binding.btnSearch.visibility = View.VISIBLE
            configureUploadFab()
        }
        updateGlassHeaderTint()
    }

    private fun animateFabAction(icon: Int, selected: Boolean) {
        // Backdrop capture only accounts for position, not an ancestor's scale or
        // rotation. Animate the foreground icon while keeping the glass stationary.
        val fab = binding.fabUpload
        if (fab.tag == icon) return
        val firstAction = fab.tag == null
        fab.tag = icon
        fab.animate().cancel()
        // Apply the action immediately so an interrupted animation cannot leave
        // the previous icon attached to the new action.
        fab.setImageResource(icon)
        binding.fabUploadGlass.setIconResource(icon)
        fab.contentDescription = if (selected) "下载所选图片" else "上传"
        binding.fabUploadGlass.contentDescription = fab.contentDescription
        updateFabSize()
        val animatedIcon = binding.fabUploadGlass.imageView
        animatedIcon.animate().cancel()
        animatedIcon.scaleX = if (firstAction) 1f else 0.72f
        animatedIcon.scaleY = animatedIcon.scaleX
        animatedIcon.alpha = if (firstAction) 1f else 0.35f
        animatedIcon.rotation = if (firstAction) 0f else if (selected) -90f else 90f
        if (firstAction) return
        animatedIcon.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .rotation(0f)
            .setDuration(Ui2DesignSystem.Motion.normalMs)
            .setInterpolator(Ui2DesignSystem.Motion.spring)
            .start()
    }

    private fun updateFabSize() {
        val selected = binding.fabUpload.tag == R.drawable.ic_download
        // A stable glass size avoids rebuilding the backdrop during action changes.
        val target = dp(56)
        binding.fabUpload.customSize = target
        val params = binding.fabUploadContainer.layoutParams
        if (params.width != target || params.height != target) {
            params.width = target
            params.height = target
            binding.fabUploadContainer.layoutParams = params
        }
    }

    override fun onResume() {
        super.onResume()
        shellGlassResumed = true
        if (::adapter.isInitialized) adapter.refreshDisplayMode()
        featuredPanel?.refreshDisplayMode()
        PaletteManager.apply(this)
        applyStartupPalette()
        applyUi2Shell()
        embeddedVideo?.refreshPalette()
        ViewCompat.requestApplyInsets(binding.root)
        val paletteChanged = paletteSignature() != lastPaletteSignature
        if (paletteChanged) lastPaletteSignature = paletteSignature()
        if (startupStarted && !NetworkManager.isReady()) {
            activityScope.launch {
                runCatching { NetworkManager.init(applicationContext) }
            }
        }
        val selectedFilter = AppSettings.getCurrentFilterId(this)
        if (startupStarted && (paletteChanged || selectedFilter != appliedFilterId) && !loading) {
            launchHomePage(1, currentQuery, append = false)
        }
    }

    override fun onPause() {
        shellGlassResumed = false
        updateShellGlass()
        super.onPause()
    }

    private fun updateShellGlass() {
        if (!::binding.isInitialized) return
        embeddedMessages?.setActive(shellGlassResumed && embeddedScreenId == R.id.tab_messages)
        val enabled = true
        val visible = true
        val source = if (embeddedScreenId == R.id.tab_home) binding.homeRefresh else binding.mainContentHost
        binding.glassBottomNavigation.visibility = if (visible) View.VISIBLE else View.GONE
        binding.glassBottomNavigation.setRenderingActive(visible && shellGlassResumed, source)

        binding.headerGlass.visibility = View.VISIBLE
        val headerVisible = enabled && embeddedScreenId == R.id.tab_home &&
            binding.appBarLayout.visibility == View.VISIBLE && binding.appBarLayout.alpha > 0f
        // Sample only the opaque page content; the header must never capture itself.
        binding.headerGlass.setRenderingActive(headerVisible && shellGlassResumed, binding.homeRefresh)

        binding.fabUploadGlass.visibility = View.VISIBLE
        binding.fabUpload.visibility = View.GONE
        val fabVisible = enabled && binding.fabUploadContainer.visibility == View.VISIBLE
        binding.fabUploadGlass.backdropSource = source
        binding.fabUploadGlass.enableDynamicBackground = fabVisible && shellGlassResumed
        binding.fabUploadGlass.enableSensorHighlight = fabVisible && shellGlassResumed
    }

    private fun updateGlassHeaderTint() {
        val foreground = GlassWidgetStyle.ICON_COLOR
        binding.toolbar.setTitleTextColor(GlassWidgetStyle.TEXT_COLOR)
        binding.toolbar.setSubtitleTextColor(GlassWidgetStyle.TEXT_COLOR)
        tintToolbarNavigationIcon(foreground)
        binding.toolbar.overflowIcon?.setTint(foreground)
        binding.btnDensity.imageTintList = android.content.res.ColorStateList.valueOf(foreground)
        binding.btnSearch.imageTintList = android.content.res.ColorStateList.valueOf(foreground)
    }

    private fun updateGlassShellTint() {
        val tint = android.content.res.ColorStateList.valueOf(GlassWidgetStyle.ICON_COLOR)
        binding.fabUpload.imageTintList = tint
        binding.fabUploadGlass.setIconTint(GlassWidgetStyle.ICON_COLOR)
        binding.glassBottomNavigation.applyPalette()
        updateGlassHeaderTint()
    }

    private fun paletteSignature(): String = "${AppSettings.getPalette(this)}:${AppSettings.getAccentColor(this)}:${resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK}"

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun applyUi2Shell() {
        if (!::binding.isInitialized) return
        val colors = PaletteManager.colors(this)
        val enabled = true
        window.statusBarColor = colors.surface
        window.navigationBarColor = colors.surface
        Ui2DesignSystem.styleIsland(binding.appBarLayout, colors, Ui2DesignSystem.Shape.topIsland)
        binding.toolbar.background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        binding.toolbar.elevation = 0f
        Ui2DesignSystem.styleIsland(binding.fabUpload, colors, Ui2DesignSystem.Shape.fabIsland)
        val navigationTint = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(colors.primary, colors.onSurface)
        )
        binding.toolbar.setTitleTextColor(colors.onSurface)
        tintToolbarNavigationIcon(colors.onSurface)
        binding.toolbar.overflowIcon?.setTint(colors.onSurface)
        // TopIsland tools are transparent icon controls, not global action buttons.
        binding.btnDensity.background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        binding.btnSearch.background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        binding.btnDensity.imageTintList = android.content.res.ColorStateList.valueOf(colors.onSurface)
        binding.btnSearch.imageTintList = android.content.res.ColorStateList.valueOf(colors.onSurface)
        binding.fabUpload.imageTintList = android.content.res.ColorStateList.valueOf(colors.onSurface)
        binding.fabUpload.backgroundTintList = android.content.res.ColorStateList.valueOf(Ui2DesignSystem.colors(this).glassTint)
        binding.toolbar.alpha = 1f
        // Glass captures these views directly, without their ancestors' background.
        // Paint the page surface into the capture so gaps between cards stay opaque
        // and retain the current theme color instead of sampling transparent black.
        binding.homeRefresh.setBackgroundColor(colors.surface)
        binding.mainContentHost.setBackgroundColor(colors.surface)
        if (enabled) {
            window.statusBarColor = colors.surface
            window.navigationBarColor = colors.surface
        }
        val appBarParams = binding.appBarLayout.layoutParams as? android.view.ViewGroup.MarginLayoutParams
        val bottomParams = binding.glassBottomNavigation.layoutParams as? android.view.ViewGroup.MarginLayoutParams
        // Keep the header fixed and fully visible while artwork scrolls beneath it.
        binding.appBarLayout.translationY = 0f
        binding.appBarLayout.alpha = 1f
        binding.appBarLayout.scaleX = 1f
        binding.appBarLayout.scaleY = 1f
        if (enabled) {
            // Keep both ancestors transparent so the live glass backdrop stays visible.
            binding.appBarLayout.background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            binding.appBarLayout.elevation = dp(Ui2DesignSystem.Elevation.islandDp.toInt()).toFloat()
            binding.headerGlass.cornerRadius = Ui2DesignSystem.Shape.topIsland * resources.displayMetrics.density
            // Clip the toolbar and the glass's GPU layer to the same rounded boundary.
            binding.appBarLayout.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val radius = binding.headerGlass.cornerRadius.coerceAtMost(minOf(view.width, view.height) / 2f)
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            binding.appBarLayout.clipToOutline = true
            binding.headerGlass.setPalette(colors)
            binding.toolbar.background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            binding.toolbar.elevation = 0f
            GlassWidgetStyle.apply(binding.fabUploadGlass, Ui2DesignSystem.Shape.fabIsland)
            binding.fabUploadContainer.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val radius = binding.fabUploadGlass.cornerRadius.coerceAtMost(minOf(view.width, view.height) / 2f)
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            binding.fabUploadContainer.clipToOutline = true
            binding.fabUploadContainer.clipChildren = true
            binding.fabUploadContainer.clipToPadding = true
            updateGlassShellTint()
            binding.toolbar.alpha = 1f
        } else {
            binding.appBarLayout.clipToOutline = false
            binding.appBarLayout.outlineProvider = ViewOutlineProvider.BACKGROUND
            binding.appBarLayout.setBackgroundColor(colors.primary)
            binding.appBarLayout.elevation = dp(4).toFloat()
            binding.toolbar.background = android.graphics.drawable.ColorDrawable(colors.primary)
            binding.toolbar.alpha = 1f
            binding.toolbar.elevation = 0f
            val navigationTint = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(colors.primary, colors.onSurface)
            )
            binding.fabUploadContainer.clipToOutline = false
            binding.fabUploadContainer.outlineProvider = ViewOutlineProvider.BACKGROUND
            binding.fabUploadContainer.clipChildren = false
            binding.fabUploadContainer.clipToPadding = false
            binding.fabUpload.backgroundTintList = android.content.res.ColorStateList.valueOf(colors.primary)
            binding.toolbar.setTitleTextColor(colors.onPrimary)
            tintToolbarNavigationIcon(colors.onPrimary)
            binding.toolbar.overflowIcon?.setTint(colors.onPrimary)
            binding.btnDensity.imageTintList = android.content.res.ColorStateList.valueOf(colors.onPrimary)
            binding.btnSearch.imageTintList = android.content.res.ColorStateList.valueOf(colors.onPrimary)
            binding.fabUpload.imageTintList = android.content.res.ColorStateList.valueOf(colors.onPrimary)
            binding.appBarLayout.layoutParams?.let { it.width = -1; (it as? android.view.ViewGroup.MarginLayoutParams)?.let { lp -> lp.marginStart = 0; lp.marginEnd = 0 }; binding.appBarLayout.layoutParams = it }
        }
        appBarParams?.let {
            if (!enabled) {
                it.topMargin = 0
                it.marginStart = 0
                it.marginEnd = 0
            }
            binding.appBarLayout.layoutParams = it
        }
        bottomParams?.let {
            if (!enabled) {
                it.marginStart = 0
                it.marginEnd = 0
                it.bottomMargin = 0
            }
        }
        syncNavigationSelection(selectedPrimaryId)
    }

    /** Keep the legacy drawer available during startup, then switch the same
     * toolbar affordance to the shared GlassMenu once the home content exists. */
    private fun updateNavigationMenuMode() {
        if (!::binding.isInitialized || !::drawerLayout.isInitialized) return
        val shouldUseGlassMenu = homeHasLoaded
        glassMenuEnabled = shouldUseGlassMenu
        binding.toolbar.setNavigationOnClickListener {
            if (glassMenuEnabled) showGlassMenu()
            else drawerLayout.openDrawer(GravityCompat.START)
        }
        updateShellGlass()
    }

    private fun tintToolbarNavigationIcon(color: Int) {
        if (::drawerToggle.isInitialized) {
            drawerToggle.drawerArrowDrawable.color = color
        }
        val icon = binding.toolbar.navigationIcon ?: return
        val tintedIcon = androidx.core.graphics.drawable.DrawableCompat.wrap(icon.mutate())
        androidx.core.graphics.drawable.DrawableCompat.setTint(tintedIcon, color)
        binding.toolbar.navigationIcon = tintedIcon
    }

    private fun applyStartupPalette() {
        if (!::binding.isInitialized) return
        val c = PaletteManager.colors(this)
        binding.startupOverlay.setBackgroundColor(c.surface)
        binding.startupStatus.setTextColor(c.onSurface)
        binding.startupDetail.setTextColor(c.muted)
        binding.startupProgress.applyPalette(c)
        binding.progressBar.applyPalette(c)
        binding.homeRefresh.applyPalette(c)
    }

    private fun showSearchDialog() {
        val editText = EditText(this)
        editText.hint = "输入标签搜索（例如 safe, artist:xxx）"
        val dialog = AlertDialog.Builder(this)
            .setTitle("搜索")
            .setView(editText)
            .setPositiveButton("搜索") { _, _ ->
                val query = editText.text.toString().trim()
                if (query.isNotEmpty()) launchHomePage(1, query, append = false)
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
    }

    private suspend fun loadRealImages() {
        binding.progressBar.visibility = View.VISIBLE
        val json = withContext(Dispatchers.IO) {
                NetworkManager.getApi(this@MainActivity, "search/images?q=*&per_page=50&sf=created_at&sd=desc")
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
                homeHasLoaded = true
                updateNavigationMenuMode()
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
        binding.progressBar.visibility = if (binding.homeRefresh.isRefreshing) View.GONE else View.VISIBLE
        try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val filterParam = NetworkManager.currentFilterParam(this@MainActivity)
            val json = withContext(Dispatchers.IO) {
                NetworkManager.getApi(this@MainActivity, "search/images?q=$encoded&per_page=50&page=$targetPage&sf=$currentSort&sd=desc$filterParam")
            }
            if (json.isNullOrBlank()) {
                if (!append) Toast.makeText(this, "API 请求失败：服务器无响应或网络不可用", Toast.LENGTH_LONG).show()
                return
            }
            val root = runCatching { JSONObject(json) }.getOrNull()
            val apiError = root?.optString("error")?.takeIf { it.isNotBlank() }
                ?: root?.optJSONArray("errors")?.optString(0)?.takeIf { it.isNotBlank() }
            if (apiError != null) {
                if (!append) Toast.makeText(this, "API 返回错误: $apiError", Toast.LENGTH_LONG).show()
                return
            }

            val images = parseImages(json)
            if (!append) {
                page = 1; currentQuery = query; allImages = images; currentImages = images
                adapter.updateData(images)
                homeHasLoaded = true
                updateNavigationMenuMode()
                lastPrefetchedFrom = -1
                CdnImageGate.prefetch(this@MainActivity, images.map { it.thumbnailUrl }, limit = columnCount * ResourceCoordinator.imagePreloadDistance(this@MainActivity))
                appliedFilterId = AppSettings.getCurrentFilterId(this@MainActivity)
            } else if (images.isNotEmpty()) {
                page = targetPage; allImages = allImages + images; currentImages = allImages; adapter.updateData(allImages)
                homeHasLoaded = true
                updateNavigationMenuMode()
            }
            if (images.isEmpty() && !append) Toast.makeText(this, "没有找到匹配图片", Toast.LENGTH_SHORT).show()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            Log.e("MainActivity", "加载图片失败", e)
            if (!append) Toast.makeText(this, "API 解析异常：${e.localizedMessage ?: e.message}", Toast.LENGTH_LONG).show()
        } finally {
            if (currentCoroutineContext().isActive) {
                loading = false
                binding.progressBar.visibility = View.GONE
                binding.homeRefresh.isRefreshing = false
            }
        }
    }

    private fun launchHomePage(targetPage: Int, query: String, append: Boolean) {
        homeLoadJob?.cancel()
        loading = false
        homeLoadJob = activityScope.launch { loadPage(targetPage, query, append) }
    }

    /** Explicit Home-tab reselection refresh; preserve old content until new data arrives. */
    private fun resetHomeAndRefresh() {
        adapter.clearSelection(); featuredPanel?.clearSelection()
        featuredPanel?.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE
        homeLoadJob?.cancel()
        loading = false
        appliedFilterId = AppSettings.getCurrentFilterId(this)
        lastPrefetchedFrom = -1
        binding.recyclerView.scrollToPosition(0)
        binding.homeRefresh.isRefreshing = true
        launchHomePage(1, currentQuery.ifBlank { "*" }, append = false)
    }

    private fun showRankings() {
        featuredPanel?.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE
        currentSort = "score"
        currentQuery = "*"
        page = 1
        binding.recyclerView.scrollToPosition(0)
        binding.homeRefresh.isRefreshing = true
        launchHomePage(1, currentQuery, append = false)
    }

    private fun showFeaturedContent() {
        // This helper is called by the primary navigation handler. Calling
        // handlePrimaryNavigation() here recursively caused an endless stream
        // of haptic feedback and eventually exhausted the UI thread stack.
        showEmbeddedScreen(R.id.tab_featured)
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
                    description = obj.optString("description", null),
                    mimeType = obj.optString("mime_type", null),
                    uploaderId = obj.optLong("uploader_id", -1L).takeIf { it > 0L },
                    spoilered = obj.optBoolean("spoilered", false)
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
        homeHasLoaded = true
        updateNavigationMenuMode()
    }

    private fun updateStartup(status: String, detail: String) {
        binding.startupStatus.text = status
        binding.startupDetail.text = detail
    }

    private suspend fun preloadSystemFilters() {
        val json = withContext(Dispatchers.IO) {
            NetworkManager.getApi(this@MainActivity, "filters/system?page=1")
        }
        if (!json.isNullOrBlank()) FilterCache.saveSystemFilters(this, json)
    }

    /** Shared 2.0 glass menu entry point for every primary destination. */
    fun showUnifiedGlassMenu() = showGlassMenu()

    private fun showGlassMenu() {
        val items = listOf(
            com.kerybotu.derpibooru.mirror.ui.menu.GlassMenuItem("tags", R.drawable.ic_bookmark, "标签", onClick = {
                startActivity(Intent(this, com.kerybotu.derpibooru.mirror.ui.TagSearchActivity::class.java))
            }),
            com.kerybotu.derpibooru.mirror.ui.menu.GlassMenuItem("filters", R.drawable.ic_filter, "过滤器", onClick = {
                startActivity(Intent(this, com.kerybotu.derpibooru.mirror.ui.FilterActivity::class.java))
            }),
            com.kerybotu.derpibooru.mirror.ui.menu.GlassMenuItem("galleries", R.drawable.ic_image, "图集", onClick = {
                startActivity(Intent(this, com.kerybotu.derpibooru.mirror.ui.GalleryActivity::class.java))
            }),
            com.kerybotu.derpibooru.mirror.ui.menu.GlassMenuItem("rankings", R.drawable.ic_leaderboard, "排行榜", onClick = {
                showRankings()
            }),
            com.kerybotu.derpibooru.mirror.ui.menu.GlassMenuItem("comments", R.drawable.ic_comment, "全站评论", onClick = {
                startActivity(Intent(this, com.kerybotu.derpibooru.mirror.ui.RecentCommentsActivity::class.java))
            }),
            com.kerybotu.derpibooru.mirror.ui.menu.GlassMenuItem("forums", R.drawable.ic_forum, "论坛", onClick = {
                startActivity(Intent(this, com.kerybotu.derpibooru.mirror.ui.ForumActivity::class.java))
            }),
            com.kerybotu.derpibooru.mirror.ui.menu.GlassMenuItem("favorites", R.drawable.ic_star, "收藏夹", onClick = {
                startActivity(Intent(this, com.kerybotu.derpibooru.mirror.ui.FavoritesActivity::class.java))
            }),
            com.kerybotu.derpibooru.mirror.ui.menu.GlassMenuItem("downloads", R.drawable.ic_download, "下载管理", onClick = {
                startActivity(Intent(this, com.kerybotu.derpibooru.mirror.ui.DownloadManagerActivity::class.java))
            }),
            com.kerybotu.derpibooru.mirror.ui.menu.GlassMenuItem("settings", R.drawable.ic_settings, "设置", onClick = {
                startActivity(Intent(this, com.kerybotu.derpibooru.mirror.ui.SettingsActivity::class.java))
            })
        )
        com.kerybotu.derpibooru.mirror.ui.menu.GlassMenuCard(this, items).show()
    }

    override fun onDestroy() {
        embeddedVideo?.dispose()
        embeddedMessages?.dispose()
        embeddedProfile?.dispose()
        featuredPanel?.dispose()
        activityJob.cancel()
        NetworkManager.shutdown()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        ResourceCoordinator.onTrimMemory(this, level)
        super.onTrimMemory(level)
    }
}
