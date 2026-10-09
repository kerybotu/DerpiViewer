package com.kerybotu.derpibooru.mirror.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.liquidglass.LiquidGlassButton
import com.example.liquidglass.LiquidGlassView
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.PaletteDefinitions
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.R
import com.kerybotu.derpibooru.mirror.auth.ApiKeyStore
import com.kerybotu.derpibooru.mirror.auth.LoginActivity
import com.kerybotu.derpibooru.mirror.network.DeepOptimizeActivity
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import com.kerybotu.derpibooru.mirror.IpOptimizer
import com.kerybotu.derpibooru.mirror.CacheManager
import com.kerybotu.derpibooru.mirror.theme.AccentColor
import com.kerybotu.derpibooru.mirror.theme.ThemeGenerator
import com.kerybotu.derpibooru.mirror.theme.ThemeMode
import com.kerybotu.derpibooru.mirror.update.AppUpdateManager
import com.kerybotu.derpibooru.mirror.update.UpdateFrequency
import com.kerybotu.derpibooru.mirror.update.UpdateUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI 2.0 settings control centre.
 *
 * The screen is intentionally composed from the same glass surfaces as the
 * feed pages. Business preferences remain in AppSettings; this class only
 * composes them into responsive sections and binds events.
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var root: FrameLayout
    private lateinit var pageBackdrop: View
    private lateinit var scrollBackdrop: ScrollContentBackdrop
    private lateinit var scroll: ScrollView
    private lateinit var contentColumn: LinearLayout
    private lateinit var topIsland: IslandGlassView
    private lateinit var titleView: TextView
    private lateinit var backButton: ImageButton

    private lateinit var accountStatus: TextView
    private lateinit var accountLogin: LiquidGlassButton
    private lateinit var accountLogout: LiquidGlassButton
    private lateinit var paletteGroup: GlassSegmentedControl
    private lateinit var siteGroup: GlassSegmentedControl
    private lateinit var accentSwatches: ResponsiveWrapLayout
    private lateinit var switchHighRes: GlassSwitch
    private lateinit var switchVideoThumb: GlassSwitch
    private lateinit var switchVideoAudio: GlassSwitch
    private lateinit var switchVideoWifiOnly: GlassSwitch
    private lateinit var switchHideUploader: GlassSwitch
    private lateinit var switchHideScore: GlassSwitch
    private lateinit var switchTagTranslation: GlassSwitch
    private lateinit var spinnerSpoilerMode: Spinner
    private lateinit var spoilerModeInfo: ImageButton
    private lateinit var switchAntiEmbarrassment: GlassSwitch
    private lateinit var antiEmbarrassmentInfo: ImageButton
    private lateinit var antiEmbarrassmentFilter: LiquidGlassButton
    private lateinit var customDomain: EditText
    private lateinit var switchIp: GlassSwitch
    private lateinit var switchCdnDirect: GlassSwitch
    private lateinit var manualIp: EditText
    private lateinit var saveNetwork: LiquidGlassButton
    private lateinit var optimizeIp: LiquidGlassButton
    private lateinit var deepOptimizeIp: LiquidGlassButton
    private lateinit var restoreAutoIp: LiquidGlassButton
    private lateinit var networkStatus: TextView
    private lateinit var clearCache: LiquidGlassButton
    private lateinit var updateFrequency: Spinner
    private lateinit var checkUpdate: LiquidGlassButton

    private val glassSurfaces = mutableListOf<LiquidGlassView>()
    private val glassButtons = mutableListOf<LiquidGlassButton>()
    private val glassSwitches = mutableListOf<GlassSwitch>()
    private val segmentedControls = mutableListOf<GlassSegmentedControl>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var systemTopInset = 0
    private var systemBottomInset = 0
    private var systemLeftInset = 0
    private var systemRightInset = 0
    private var paletteUpdating = false
    private var glassRenderingActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        root = FrameLayout(this).apply {
            id = R.id.settings_root
            clipChildren = false
        }
        setContentView(root)
        buildScreen()
        PaletteManager.apply(this)
        applyPalette()
        bindValues()
        bindActions()
        bindUpdateSettings()
        updateAccountStatus()
        refreshNetworkStatus()
    }

    private fun buildScreen() {
        scroll = ScrollView(this).apply {
            id = View.generateViewId()
            clipToPadding = false
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
        }
        contentColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        scroll.addView(contentColumn, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        pageBackdrop = View(this).apply { setBackgroundColor(PaletteManager.colors(this@SettingsActivity).surface) }
        scrollBackdrop = ScrollContentBackdrop(this, scroll).apply {
            setBackgroundColor(PaletteManager.colors(this@SettingsActivity).surface)
        }
        root.addView(pageBackdrop, FrameLayout.LayoutParams(-1, -1))
        root.addView(scrollBackdrop, FrameLayout.LayoutParams(-1, -1))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        scroll.setOnScrollChangeListener { _, _, _, _, _ ->
            scrollBackdrop.invalidate()
            updateGlassRendering()
        }
        contentColumn.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            scrollBackdrop.invalidate()
            updateGlassRendering()
        }

        buildAccountSection()
        buildAppearanceSection()
        buildBrowsingSection()
        buildNetworkSection()
        buildUpdateSection()
        buildTopIsland()

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            systemTopInset = bars.top
            systemBottomInset = bars.bottom
            systemLeftInset = bars.left
            systemRightInset = bars.right
            applyResponsiveLayout()
            insets
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyResponsiveLayout()
            updateGlassRendering()
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun buildTopIsland() {
        topIsland = IslandGlassView(this).apply {
            id = View.generateViewId()
            elevation = dp(Ui2DesignSystem.Elevation.topIslandDp).toFloat()
            setPadding(dp(8), 0, dp(8), 0)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        backButton = ImageButton(this).apply {
            id = View.generateViewId()
            setImageResource(R.drawable.ic_arrow_back)
            background = null
            contentDescription = "返回"
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { finish() }
        }
        titleView = TextView(this).apply {
            id = View.generateViewId()
            text = "设置"
            gravity = Gravity.CENTER
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        row.addView(backButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        row.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Space(this), LinearLayout.LayoutParams(dp(48), dp(48)))
        topIsland.addView(row, ViewGroup.LayoutParams(-1, -1))
        root.addView(topIsland, FrameLayout.LayoutParams(-2, dp(64), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
    }

    private fun buildAccountSection() {
        val section = section("账户")
        accountStatus = bodyText()
        sectionContent(section).addView(accountStatus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        val actions = ResponsiveWrapLayout(this)
        accountLogin = actionButton("登录并导入 Key")
        accountLogout = actionButton("清除本地凭据")
        actions.addView(accountLogin, wrapButtonParams())
        actions.addView(accountLogout, wrapButtonParams())
        sectionContent(section).addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        addSection(section)
    }

    private fun buildAppearanceSection() {
        val section = section("外观")
        paletteGroup = GlassSegmentedControl(this)
        paletteGroup.addOption(View.generateViewId(), "跟随系统")
        paletteGroup.addOption(View.generateViewId(), "暗色")
        paletteGroup.addOption(View.generateViewId(), "浅色")
        paletteGroup.addOption(View.generateViewId(), "彩色")
        segmentedControls += paletteGroup
        sectionContent(section).addView(paletteGroup, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })
        sectionContent(section).addView(labelText("强调色"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        accentSwatches = ResponsiveWrapLayout(this).apply {
            horizontalGapDp = 12
            verticalGapDp = 12
            setPadding(dp(2), dp(10), dp(2), dp(2))
        }
        sectionContent(section).addView(accentSwatches, LinearLayout.LayoutParams(-1, -2))
        addSection(section)
    }

    private fun buildBrowsingSection() {
        val section = section("浏览体验")
        switchHighRes = settingSwitch("主页使用高分辨率缩略图")
        switchVideoThumb = settingSwitch("开启视频缩略图")
        switchVideoAudio = settingSwitch("默认开启视频音频")
        switchVideoWifiOnly = settingSwitch("仅 Wi-Fi 下自动播放视频")
        switchHideUploader = settingSwitch("隐藏上传者")
        switchHideScore = settingSwitch("隐藏分数")
        switchTagTranslation = settingSwitch("启用标签翻译")
        listOf(switchHighRes, switchVideoThumb, switchVideoAudio, switchVideoWifiOnly,
            switchHideUploader, switchHideScore, switchTagTranslation).forEach { sectionContent(section).addView(it) }

        val spoilerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        spoilerRow.addView(labelText("剧透内容显示方式"), LinearLayout.LayoutParams(0, -2, 1f))
        spinnerSpoilerMode = glassSpinner()
        spoilerRow.addView(spinnerSpoilerMode, LinearLayout.LayoutParams(dp(150), dp(48)))
        spoilerModeInfo = infoButton("剧透显示方式说明")
        spoilerRow.addView(spoilerModeInfo, LinearLayout.LayoutParams(dp(48), dp(48)))
        sectionContent(section).addView(spoilerRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val antiRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        switchAntiEmbarrassment = settingSwitch("防社死：启动时自动切换过滤器")
        antiRow.addView(switchAntiEmbarrassment, LinearLayout.LayoutParams(0, -2, 1f))
        antiEmbarrassmentInfo = infoButton("防社死功能说明")
        antiRow.addView(antiEmbarrassmentInfo, LinearLayout.LayoutParams(dp(48), dp(48)))
        sectionContent(section).addView(antiRow)
        antiEmbarrassmentFilter = actionButton("指定过滤器：Default")
        sectionContent(section).addView(antiEmbarrassmentFilter, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(4) })
        sectionContent(section).addView(bodyText("开启后，每次打开应用都会先使用指定过滤器。默认使用官方 Default 过滤器。", 12f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        addSection(section)
    }

    private fun buildNetworkSection() {
        val section = section("站点与网络")
        siteGroup = GlassSegmentedControl(this)
        siteGroup.addOption(View.generateViewId(), "Derpibooru")
        siteGroup.addOption(View.generateViewId(), "Trixiebooru")
        siteGroup.addOption(View.generateViewId(), "自定义")
        segmentedControls += siteGroup
        sectionContent(section).addView(siteGroup, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })
        customDomain = glassEditText("自定义域名，例如 example.org", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        sectionContent(section).addView(inputSurface(customDomain), LinearLayout.LayoutParams(-1, dp(60)).apply { topMargin = dp(10) })
        switchIp = settingSwitch("优选 IP（关闭则直连）")
        switchCdnDirect = settingSwitch("derpicdn.net 使用直连")
        sectionContent(section).addView(switchIp, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        sectionContent(section).addView(switchCdnDirect)
        manualIp = glassEditText("手动优选节点 IP（支持 IPv4 / IPv6，留空为自动）", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        sectionContent(section).addView(inputSurface(manualIp), LinearLayout.LayoutParams(-1, dp(60)).apply { topMargin = dp(6) })

        val saveRow = ResponsiveWrapLayout(this)
        saveNetwork = actionButton("保存网络设置")
        optimizeIp = actionButton("重新优选 IP")
        saveRow.addView(saveNetwork, wrapButtonParams())
        saveRow.addView(optimizeIp, wrapButtonParams())
        sectionContent(section).addView(saveRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val advancedRow = ResponsiveWrapLayout(this)
        deepOptimizeIp = actionButton("深度 IP 优选")
        restoreAutoIp = actionButton("恢复自动优选")
        advancedRow.addView(deepOptimizeIp, wrapButtonParams())
        advancedRow.addView(restoreAutoIp, wrapButtonParams())
        sectionContent(section).addView(advancedRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        networkStatus = bodyText()
        sectionContent(section).addView(infoSurface(networkStatus), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        clearCache = actionButton("清除缓存")
        sectionContent(section).addView(clearCache, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        addSection(section)
    }

    private fun buildUpdateSection() {
        val section = section("应用更新")
        updateFrequency = glassSpinner()
        sectionContent(section).addView(updateFrequency, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })
        checkUpdate = actionButton("立即检查更新")
        sectionContent(section).addView(checkUpdate, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        addSection(section)
    }

    private fun section(title: String): LiquidGlassView {
        val surface = LiquidGlassView(this).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            elevation = dp(Ui2DesignSystem.Elevation.cardDp).toFloat()
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, -2))
        surface.addView(column, ViewGroup.LayoutParams(-1, -2))
        surface.tag = column
        glassSurfaces += surface
        return surface
    }

    private fun addSection(surface: LiquidGlassView) {
        contentColumn.addView(surface, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
    }

    private fun sectionContent(surface: LiquidGlassView): LinearLayout = surface.tag as LinearLayout

    private fun bodyText(text: String = "", size: Float = 14f) = TextView(this).apply {
        this.text = text
        textSize = size
    }

    private fun labelText(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
    }

    private fun settingSwitch(label: String) = GlassSwitch(this).apply {
        text = label
        textSize = 14f
        glassSwitches += this
    }

    private fun actionButton(label: String, action: (() -> Unit)? = null) = LiquidGlassButton(this).apply {
        text = label
        setTextSize(14f)
        textView.setPadding(dp(12), 0, dp(12), 0)
        action?.let { setOnClickListener { if (isEnabled) it() } }
        glassButtons += this
    }

    private fun wrapButtonParams() = ViewGroup.LayoutParams(-2, dp(48))

    private fun infoButton(description: String) = ImageButton(this).apply {
        contentDescription = description
        setImageResource(R.drawable.ic_help_circle)
        background = null
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    private fun glassSpinner() = Spinner(this).apply {
        background = roundedBackground(PaletteManager.colors(this@SettingsActivity).glassSurface, PaletteManager.colors(this@SettingsActivity).glassBorder, 16f)
        setPadding(dp(12), 0, dp(8), 0)
    }

    private fun glassEditText(hint: String, inputTypeValue: Int) = EditText(this).apply {
        this.hint = hint
        inputType = inputTypeValue
        setSingleLine(true)
        background = null
        setPadding(dp(4), 0, dp(4), 0)
        textSize = 14f
    }

    private fun inputSurface(input: EditText): LiquidGlassView = LiquidGlassView(this).apply {
        setPadding(dp(12), 0, dp(12), 0)
        addView(input, FrameLayout.LayoutParams(-1, -1))
        glassSurfaces += this
    }

    private fun infoSurface(text: TextView): LiquidGlassView = LiquidGlassView(this).apply {
        setPadding(dp(12), dp(10), dp(12), dp(10))
        addView(text, FrameLayout.LayoutParams(-1, -2))
        glassSurfaces += this
    }

    private fun applyResponsiveLayout() {
        if (!::root.isInitialized || root.width <= 0) return
        val density = resources.displayMetrics.density
        val safeWidth = (root.width - systemLeftInset - systemRightInset).coerceAtLeast(1)
        val widthDp = (safeWidth / density).toInt().coerceAtLeast(1)
        val tokens = AdaptiveLayoutPolicy.tokensForWidth(widthDp)
        val gutter = dp(tokens.screenGutterDp)
        val maxContent = dp(tokens.contentMaxWidthDp)
        val contentWidth = (safeWidth - gutter * 2).coerceAtMost(maxContent).coerceAtLeast(1)
        val contentParams = contentColumn.layoutParams as FrameLayout.LayoutParams
        if (contentParams.width != contentWidth) {
            contentParams.width = contentWidth
            contentColumn.layoutParams = contentParams
        }
        val topWidth = AdaptiveLayoutPolicy.topIslandWidthPx(this, (safeWidth - gutter * 2).coerceAtLeast(1))
        (topIsland.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            val topHeight = dp(64)
            val topMargin = systemTopInset + dp(Ui2DesignSystem.Spacing.sm)
            // FrameLayout adds the full difference between asymmetric margins when centering.
            // Position the island explicitly so it shares the scroll content's safe-area center.
            val gravity = Gravity.TOP or Gravity.LEFT
            val leftMargin = systemLeftInset + (safeWidth - topWidth) / 2
            if (params.width != topWidth || params.height != topHeight ||
                params.gravity != gravity || params.topMargin != topMargin ||
                params.leftMargin != leftMargin
            ) {
                params.width = topWidth
                params.height = topHeight
                params.gravity = gravity
                params.topMargin = topMargin
                params.leftMargin = leftMargin
                topIsland.layoutParams = params
            }
        }
        val topPadding = systemTopInset + dp(64 + Ui2DesignSystem.Spacing.xl)
        val bottomPadding = systemBottomInset + dp(Ui2DesignSystem.Spacing.xl)
        if (scroll.paddingTop != topPadding || scroll.paddingBottom != bottomPadding ||
            scroll.paddingLeft != systemLeftInset || scroll.paddingRight != systemRightInset
        ) {
            scroll.setPadding(systemLeftInset, topPadding, systemRightInset, bottomPadding)
        }
    }

    private fun bindValues() {
        val palette = AppSettings.getPalette(this)
        paletteGroup.check(paletteOptionId(palette))
        switchHighRes.isChecked = AppSettings.isHighResolution(this)
        switchVideoThumb.isChecked = AppSettings.isVideoThumbnailsEnabled(this)
        switchVideoAudio.isChecked = AppSettings.isVideoAudioEnabled(this)
        switchVideoWifiOnly.isChecked = AppSettings.isVideoWifiOnly(this)
        switchHideUploader.isChecked = AppSettings.isUploaderHidden(this)
        switchHideScore.isChecked = AppSettings.isScoreHidden(this)
        switchTagTranslation.isChecked = AppSettings.isTagTranslationEnabled(this)
        val spoilerModes = AppSettings.SpoilerDisplayMode.values().toList()
        spinnerSpoilerMode.adapter = spinnerAdapter(spoilerModes.map { it.label })
        spinnerSpoilerMode.setSelection(spoilerModes.indexOf(AppSettings.getSpoilerDisplayMode(this)).coerceAtLeast(0))
        switchAntiEmbarrassment.isChecked = AppSettings.isAntiEmbarrassmentEnabled(this)
        updateAntiEmbarrassmentFilterLabel()
        switchIp.isChecked = AppSettings.isIpOptimizationEnabled(this)
        switchCdnDirect.isChecked = AppSettings.isCdnDirect(this)
        manualIp.setText(AppSettings.getManualIp(this).orEmpty())
        customDomain.setText(AppSettings.getCustomDomain(this).orEmpty())
        siteGroup.check(siteOptionId(AppSettings.getSelectedSite(this)))
        buildAccentSwatches()
    }

    private fun paletteOptionId(palette: AppSettings.Palette): Int = paletteGroup.getChildAt(
        when (palette) {
            AppSettings.Palette.SYSTEM -> 0
            AppSettings.Palette.DARK -> 1
            AppSettings.Palette.LIGHT -> 2
            AppSettings.Palette.COLORFUL -> 3
        }
    ).id

    private fun siteOptionId(site: AppSettings.Site): Int = siteGroup.getChildAt(
        when (site) {
            AppSettings.Site.DERPIBOORU -> 0
            AppSettings.Site.TRIXIEBOORU -> 1
            AppSettings.Site.CUSTOM -> 2
        }
    ).id

    private fun buildAccentSwatches() {
        if (!::accentSwatches.isInitialized) return
        val selected = AppSettings.getAccentColor(this)
        accentSwatches.removeAllViews()
        val palette = AppSettings.getPalette(this)
        val systemDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val dark = palette == AppSettings.Palette.DARK || (palette == AppSettings.Palette.SYSTEM && systemDark)
        val accents = if (dark) setOf(AccentColor.BLUE, AccentColor.PURPLE, AccentColor.GREEN, AccentColor.TEAL, AccentColor.ORANGE, AccentColor.ROSE) else AccentColor.values().toSet()
        accents.forEach { accent ->
            val scheme = ThemeGenerator.generate(accent, if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
            val swatch = TextView(this).apply {
                text = if (accent == selected) "✓" else ""
                gravity = Gravity.CENTER
                contentDescription = accent.displayName
                setTextColor(scheme.onPrimary)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(scheme.primary)
                    setStroke(dp(if (accent == selected) 3 else 1), if (accent == selected) PaletteManager.colors(this@SettingsActivity).onSurface else Color.TRANSPARENT)
                }
                setOnClickListener {
                    AppSettings.setAccentColor(this@SettingsActivity, accent)
                    PaletteManager.apply(this@SettingsActivity)
                    buildAccentSwatches()
                    applyPalette()
                }
            }
            accentSwatches.addView(swatch, ViewGroup.LayoutParams(dp(44), dp(44)))
        }
    }

    private fun spinnerAdapter(items: List<String>) = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)

    private fun bindActions() {
        accountLogin.setOnClickListener {
            AlertDialog.Builder(this).setTitle("登录方式")
                .setItems(arrayOf("手动输入 API Key", "通过网页登录")) { _, which ->
                    val intent = android.content.Intent(this, LoginActivity::class.java)
                    intent.putExtra(if (which == 0) LoginActivity.EXTRA_MANUAL_KEY else LoginActivity.EXTRA_WEB_LOGIN, true)
                    startActivity(intent)
                }.show()
        }
        accountLogout.setOnClickListener { confirmClearCredentials() }
        paletteGroup.setSelectionListener { _, id ->
            if (paletteUpdating) return@setSelectionListener
            val palette = when (id) {
                paletteGroup.getChildAt(0).id -> AppSettings.Palette.SYSTEM
                paletteGroup.getChildAt(1).id -> AppSettings.Palette.DARK
                paletteGroup.getChildAt(2).id -> AppSettings.Palette.LIGHT
                else -> AppSettings.Palette.COLORFUL
            }
            AppSettings.setPalette(this, palette)
            PaletteManager.apply(this)
            buildAccentSwatches()
            applyPalette()
        }
        switchHighRes.setOnCheckedChangeListener { _, value -> AppSettings.setHighResolution(this, value) }
        switchVideoThumb.setOnCheckedChangeListener { _, value -> AppSettings.setVideoThumbnailsEnabled(this, value) }
        switchVideoAudio.setOnCheckedChangeListener { _, value -> AppSettings.setVideoAudioEnabled(this, value) }
        switchVideoWifiOnly.setOnCheckedChangeListener { _, value -> AppSettings.setVideoWifiOnly(this, value) }
        switchHideUploader.setOnCheckedChangeListener { _, value -> AppSettings.setUploaderHidden(this, value) }
        switchHideScore.setOnCheckedChangeListener { _, value -> AppSettings.setScoreHidden(this, value) }
        switchTagTranslation.setOnCheckedChangeListener { _, value ->
            AppSettings.setTagTranslationEnabled(this, value)
            Toast.makeText(this, if (value) "标签翻译已开启" else "标签翻译已关闭", Toast.LENGTH_SHORT).show()
        }
        spinnerSpoilerMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                AppSettings.setSpoilerDisplayMode(this@SettingsActivity, AppSettings.SpoilerDisplayMode.values()[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        spoilerModeInfo.setOnClickListener { showSpoilerModeInfo() }
        switchAntiEmbarrassment.setOnCheckedChangeListener { _, enabled ->
            AppSettings.setAntiEmbarrassmentEnabled(this, enabled)
            Toast.makeText(this, if (enabled) "防社死已开启：下次启动将自动切换过滤器" else "防社死已关闭", Toast.LENGTH_SHORT).show()
        }
        antiEmbarrassmentInfo.setOnClickListener { showAntiEmbarrassmentInfo() }
        antiEmbarrassmentFilter.setOnClickListener { showAntiEmbarrassmentFilterDialog() }
        switchIp.setOnCheckedChangeListener { _, value -> AppSettings.setIpOptimizationEnabled(this, value) }
        switchCdnDirect.setOnCheckedChangeListener { _, value ->
            AppSettings.setCdnDirect(this, value)
            NetworkManager.shutdown()
            networkStatus.text = "CDN 直连设置已保存，网络客户端将在下次请求前重建"
        }
        siteGroup.setSelectionListener { _, id -> when (id) {
            siteGroup.getChildAt(0).id -> AppSettings.setSelectedSite(this, AppSettings.Site.DERPIBOORU)
            siteGroup.getChildAt(1).id -> AppSettings.setSelectedSite(this, AppSettings.Site.TRIXIEBOORU)
            siteGroup.getChildAt(2).id -> saveCustomDomain()
        } }
        saveNetwork.setOnClickListener { saveNetwork() }
        restoreAutoIp.setOnClickListener { AppSettings.setManualIp(this, null); manualIp.setText(""); networkStatus.text = "已恢复自动优选" }
        optimizeIp.setOnClickListener { optimize() }
        deepOptimizeIp.setOnClickListener {
            AlertDialog.Builder(this).setTitle("深度 IP 优选")
                .setMessage("将获取 Cloudflare IPv4/IPv6 网段，进行四次 TCPing、前 20 节点并发连通性验证和 CDN 下载测速。此过程可能耗时 1-2 分钟并产生较多网络请求，结果会同时用于主站和 CDN。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始") { _, _ -> startActivityForResult(android.content.Intent(this, DeepOptimizeActivity::class.java), 9042) }
                .show()
        }
        clearCache.setOnClickListener { clearCache() }
    }

    private fun bindUpdateSettings() {
        val options = UpdateFrequency.values().toList()
        updateFrequency.adapter = spinnerAdapter(options.map { it.label })
        updateFrequency.setSelection(options.indexOf(AppUpdateManager.frequency(this)).coerceAtLeast(0))
        updateFrequency.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { AppUpdateManager.setFrequency(this@SettingsActivity, options[position]) }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        checkUpdate.setOnClickListener {
            checkUpdate.isEnabled = false
            scope.launch {
                try {
                    when (val result = AppUpdateManager.checkDetailed(this@SettingsActivity, force = true)) {
                        is com.kerybotu.derpibooru.mirror.update.UpdateCheckResult.Available -> UpdateUi.show(this@SettingsActivity, result.info, scope)
                        com.kerybotu.derpibooru.mirror.update.UpdateCheckResult.NoUpdate -> Toast.makeText(this@SettingsActivity, "当前已是最新版本", Toast.LENGTH_SHORT).show()
                        is com.kerybotu.derpibooru.mirror.update.UpdateCheckResult.Failed -> Toast.makeText(this@SettingsActivity, result.reason, Toast.LENGTH_LONG).show()
                    }
                } finally { checkUpdate.isEnabled = true }
            }
        }
    }

    private fun applyPalette() {
        val colors = PaletteManager.colors(this)
        root.setBackgroundColor(colors.surface)
        pageBackdrop.setBackgroundColor(colors.surface)
        scrollBackdrop.setBackgroundColor(colors.surface)
        glassSurfaces.forEach { surface ->
            GlassWidgetStyle.apply(surface, if (surface === topIsland) Ui2DesignSystem.Shape.topIsland else Ui2DesignSystem.Shape.large, colors)
            surface.backdropSource = pageBackdrop
            surface.enableDynamicBackground = false
            surface.enableSensorHighlight = false
        }
        if (::topIsland.isInitialized) {
            topIsland.setPalette(colors)
            topIsland.setRenderingActive(true, scrollBackdrop)
        }
        glassButtons.forEach { button ->
            GlassWidgetStyle.apply(button, Ui2DesignSystem.Shape.medium, colors)
            button.backdropSource = pageBackdrop
            button.enableDynamicBackground = false
            button.enableSensorHighlight = false
        }
        glassSwitches.forEach { it.applyPalette(colors) }
        segmentedControls.forEach { it.applyPalette(colors) }
        backButton.imageTintList = android.content.res.ColorStateList.valueOf(colors.glassText)
        titleView.setTextColor(colors.glassText)
        updateEditTextPalette(customDomain, colors)
        updateEditTextPalette(manualIp, colors)
        networkStatus.setTextColor(colors.glassSecondaryText)
        accountStatus.setTextColor(colors.glassSecondaryText)
        updateSpinnerPalette(updateFrequency, colors)
        updateSpinnerPalette(spinnerSpoilerMode, colors)
        applyTextPalette(contentColumn, colors)
        updateGlassRendering()
    }

    private fun updateGlassRendering() {
        if (!::root.isInitialized) return
        val active = glassRenderingActive && root.isAttachedToWindow && root.isShown
        if (::topIsland.isInitialized) topIsland.setRenderingActive(active, if (active) scrollBackdrop else null)
        // Section surfaces share a static page backdrop. Only the header samples the
        // scrolling content, avoiding a full scroll-tree capture for every visible row.
        glassSurfaces.forEach {
            it.enableDynamicBackground = false
            it.enableSensorHighlight = false
        }
        glassButtons.forEach {
            it.enableDynamicBackground = false
            it.enableSensorHighlight = false
        }
    }

    private fun applyTextPalette(view: View, colors: PaletteDefinitions.Scheme) {
        if (view === accountStatus || view === networkStatus || view is EditText) return
        if (view is TextView && view !is LiquidGlassButton && view !is GlassSwitch) view.setTextColor(colors.glassText)
        if (view is ViewGroup) for (index in 0 until view.childCount) applyTextPalette(view.getChildAt(index), colors)
    }

    private fun updateEditTextPalette(edit: EditText, colors: PaletteDefinitions.Scheme) {
        edit.setTextColor(colors.glassText)
        edit.setHintTextColor(colors.glassSecondaryText)
    }

    private fun updateSpinnerPalette(spinner: Spinner, colors: PaletteDefinitions.Scheme) {
        spinner.background = roundedBackground(colors.glassSurface, colors.glassBorder, 16f)
    }

    private fun roundedBackground(fill: Int, stroke: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    private fun updateAccountStatus() {
        val loggedIn = ApiKeyStore.isLoggedIn(this)
        accountStatus.text = if (loggedIn) "已登录 · API Key：${ApiKeyStore.masked(this)}" else "未登录。登录仅通过 Derpibooru 官方页面完成。"
        accountLogout.isEnabled = loggedIn
    }

    private fun updateAntiEmbarrassmentFilterLabel() {
        antiEmbarrassmentFilter.text = "指定过滤器：${AppSettings.getAntiEmbarrassmentFilterName(this)}"
    }

    private fun showAntiEmbarrassmentInfo() = infoDialog("防社死", "开启后，每次打开 APP 时都会自动设置为指定过滤器，有效防止在公共场合因展示不合适内容而社死。\n\n默认使用官方 Default 过滤器；你也可以在下方“指定过滤器”中填写自己的过滤器 ID。关闭本开关后，应用不会在启动时改动当前过滤器。")

    private fun showSpoilerModeInfo() = infoDialog("剧透内容显示方式", "直接隐藏：从图片列表中移除剧透图片卡片。\n\n点击显示：卡片显示遮罩，点击遮罩上的“显示”按钮后查看缩略图；点击其它区域仍可进入详情页。\n\n直接显示：不显示剧透遮罩。此设置只影响图片卡片，详情页和全屏大图仍可按页面操作查看。")

    private fun infoDialog(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("知道了", null).show()
    }

    private fun showAntiEmbarrassmentFilterDialog() {
        val input = EditText(this).apply {
            hint = "过滤器 ID（留空使用 Default）"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(AppSettings.getAntiEmbarrassmentFilterId(this@SettingsActivity)?.toString().orEmpty())
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        AlertDialog.Builder(this).setTitle("指定防社死过滤器")
            .setMessage("留空时使用官方 Default 过滤器；填写 ID 后，每次启动会自动切换到该过滤器。")
            .setView(input).setNegativeButton("取消", null)
            .setNeutralButton("使用 Default") { _, _ -> AppSettings.setAntiEmbarrassmentFilter(this, null, "Default"); updateAntiEmbarrassmentFilterLabel() }
            .setPositiveButton("保存") { _, _ ->
                val raw = input.text.toString().trim()
                val id = raw.toIntOrNull()
                if (raw.isNotEmpty() && (id == null || id <= 0)) Toast.makeText(this, "请输入有效的过滤器 ID", Toast.LENGTH_SHORT).show()
                else { AppSettings.setAntiEmbarrassmentFilter(this, id); updateAntiEmbarrassmentFilterLabel() }
            }.show()
    }

    private fun saveCustomDomain() {
        if (!AppSettings.setCustomSite(this, customDomain.text.toString())) networkStatus.text = "自定义域名格式无效"
    }

    private fun saveNetwork() {
        val ip = manualIp.text.toString().trim()
        if (ip.isNotEmpty() && !AppSettings.isValidIpFormat(ip)) { networkStatus.text = "IP 地址格式无效"; return }
        AppSettings.setManualIp(this, ip.ifBlank { null })
        if (siteGroup.checkedRadioButtonId == siteGroup.getChildAt(2).id) saveCustomDomain()
        NetworkManager.shutdown()
        networkStatus.text = "网络设置已保存，返回主页后生效"
    }

    private fun optimize() {
        networkStatus.text = "正在测速优选节点…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                NetworkManager.reinitialize(this@SettingsActivity, forceRefresh = true)
                IpOptimizer.getBestIpSmart(this@SettingsActivity)
            }
            networkStatus.text = "当前优选节点：${result.ip}"
        }
    }

    private fun clearCache() {
        clearCache.isEnabled = false
        scope.launch {
            val webView = WebView(this@SettingsActivity)
            val cleared = CacheManager.clearCache(this@SettingsActivity, webView)
            webView.destroy()
            clearCache.isEnabled = true
            Toast.makeText(this@SettingsActivity, "已清除 ${CacheManager.formatSize(cleared)}", Toast.LENGTH_SHORT).show()
            refreshNetworkStatus()
        }
    }

    private fun refreshNetworkStatus() {
        scope.launch {
            val size = withContext(Dispatchers.IO) { CacheManager.calculateCacheSize(this@SettingsActivity) }
            val routes = NetworkManager.currentPreferredIps()
            val routeText = if (routes.isEmpty()) "直连（未使用优选节点）" else routes.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            networkStatus.text = "当前节点 IP:\n$routeText\n缓存大小：${CacheManager.formatSize(size)}"
        }
    }

    private fun confirmClearCredentials() {
        AlertDialog.Builder(this)
            .setTitle("清除本地凭据")
            .setMessage("这会删除本机保存的 API Key 和网站会话，不会注销服务器账户。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清除") { _, _ ->
                ApiKeyStore.clear(this)
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                updateAccountStatus()
                Toast.makeText(this, "本地凭据已清除", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        glassRenderingActive = true
        PaletteManager.apply(this)
        applyPalette()
        updateAccountStatus()
        refreshNetworkStatus()
    }

    override fun onPause() {
        glassRenderingActive = false
        if (::topIsland.isInitialized) topIsland.setRenderingActive(false, null)
        super.onPause()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
