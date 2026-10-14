package com.kerybotu.derpibooru.mirror.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.liquidglass.LiquidGlassButton
import com.example.liquidglass.LiquidGlassView
import com.kerybotu.derpibooru.mirror.PaletteDefinitions
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.R

/** Project facts and third-party relationship disclosures, sourced from README.md. */
class AboutDeveloperActivity : AppCompatActivity() {
    private lateinit var root: FrameLayout
    private lateinit var pageBackdrop: View
    private lateinit var scrollBackdrop: ScrollContentBackdrop
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var topIsland: IslandGlassView
    private lateinit var title: TextView
    private lateinit var back: ImageButton
    private val surfaces = mutableListOf<LiquidGlassView>()
    private val buttons = mutableListOf<LiquidGlassButton>()
    private var topInset = 0
    private var bottomInset = 0
    private var leftInset = 0
    private var rightInset = 0

    companion object {
        private const val REPOSITORY_URL = "https://github.com/kerybotu/DerpiViewer"
        private const val README_URL = "$REPOSITORY_URL/blob/main/README.md"
        private const val MIT_LICENSE_URL = "https://opensource.org/licenses/MIT"
        private const val ISSUES_URL = "$REPOSITORY_URL/issues"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        root = FrameLayout(this).apply { clipChildren = false }
        setContentView(root)
        buildScreen()
        PaletteManager.apply(this)
        applyPalette()
    }

    private fun buildScreen() {
        pageBackdrop = View(this)
        scroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        scroll.addView(content, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        scrollBackdrop = ScrollContentBackdrop(this, scroll)
        root.addView(pageBackdrop, FrameLayout.LayoutParams(-1, -1))
        root.addView(scrollBackdrop, FrameLayout.LayoutParams(-1, -1))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        buildTopIsland()
        buildContent()
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topInset = bars.top
            bottomInset = bars.bottom
            leftInset = bars.left
            rightInset = bars.right
            applyResponsiveLayout()
            insets
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyResponsiveLayout() }
        ViewCompat.requestApplyInsets(root)
    }

    private fun buildTopIsland() {
        topIsland = IslandGlassView(this).apply {
            elevation = dp(Ui2DesignSystem.Elevation.topIslandDp.toInt()).toFloat()
            setPadding(dp(8), 0, dp(8), 0)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        back = ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            background = null
            contentDescription = "返回"
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { finish() }
        }
        title = TextView(this).apply {
            text = "关于与开发者声明"
            gravity = Gravity.CENTER
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        row.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Space(this), LinearLayout.LayoutParams(dp(48), dp(48)))
        topIsland.addView(row, ViewGroup.LayoutParams(-1, -1))
        root.addView(topIsland, FrameLayout.LayoutParams(-2, dp(64), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
    }

    private fun buildContent() {
        content.addView(TextView(this).apply {
            text = "DerpiViewer"
            textSize = 28f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        content.addView(body("基于 Derpibooru 官方 JSON API 的原生 Android 客户端，支持 Derpibooru / Trixiebooru 浏览、搜索、视频流、翻译、本地收藏和网络优化。"))
        content.addView(body("开发者：KeryBotu", 13f).apply { setPadding(0, dp(8), 0, 0) })
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val versionName = packageInfo.versionName ?: "未知"
        content.addView(body("版本 $versionName（${packageInfo.versionCode}）", 13f).apply {
            setPadding(0, dp(8), 0, 0)
        })
        section("开源项目与使用") {
            text("项目源代码公开在官方仓库，欢迎查看代码并通过 GitHub Issues 反馈问题或提出建议。README.md 介绍了项目 APK 的发布与更新渠道；具体复制、修改和再分发条件以项目实际许可证为准。")
            action("打开官方代码仓库") { openUrl(REPOSITORY_URL) }
        }
        section("第三方客户端声明") {
            text("DerpiViewer 是由独立开发者开发的第三方客户端，并非 Derpibooru 官方应用。本项目与 Derpibooru 官方不存在隶属、赞助、认可或官方合作关系，除非相关关系另有明确的正式说明。")
        }
        section("商标与作品版权") {
            text("Derpibooru 网站及其相关名称、标识等权利归相应权利人所有。通过客户端展示的用户作品、图片、评论及其他内容，其版权及相关权利仍归相应作者或权利人所有；DerpiViewer 不因展示或访问这些内容而取得作品所有权。作品的使用、转载和授权等问题应遵循相应权利人的要求及适用规则。")
        }
        section("API 与第三方依赖") {
            text("客户端通过 Derpibooru 相关服务和 API 获取数据，相关服务由其各自运营者管理。项目使用的第三方依赖包括 AndroidX、Material Components、Liquid-Glass-Android、OkHttp、Retrofit、Glide、Media3 等；第三方依赖的版权与许可证以各自项目的声明为准。")
        }
        section("反馈与贡献") {
            text("可以通过 GitHub Issues 反馈问题或提出功能建议。README 未单独列出贡献流程；如希望贡献代码，可先通过 Issues 与项目维护者沟通。")
            action("打开 GitHub Issues") { openUrl(ISSUES_URL) }
        }
        section("开源许可证") {
            text("DerpiViewer 基于 MIT License 开源发布。项目 README.md 的「许可证」章节包含完整条款及版权声明：Copyright (c) 2026 KeryBotu。")
            text("MIT License 允许使用、复制、修改和再分发本软件，但须在软件副本或重要部分中保留版权声明和许可声明。软件按「原样」提供，不附带任何担保，具体条件以完整条款为准。本许可不适用于客户端展示的用户作品，也不替代第三方依赖各自的许可证。")
            action("查看 MIT License 完整条款") { openUrl(MIT_LICENSE_URL) }
            action("查看 README 中的项目许可声明") { openUrl(README_URL) }
        }
        content.addView(Space(this), LinearLayout.LayoutParams(1, dp(8)))
    }

    private fun section(titleText: String, build: SectionBuilder.() -> Unit) {
        val surface = LiquidGlassView(this).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            elevation = dp(Ui2DesignSystem.Elevation.cardDp.toInt()).toFloat()
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(this).apply {
            text = titleText
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, -2))
        SectionBuilder(column).apply(build)
        surface.addView(column, ViewGroup.LayoutParams(-1, -2))
        surfaces += surface
        content.addView(surface, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    }

    private inner class SectionBuilder(private val column: LinearLayout) {
        fun text(value: String, size: Float = 14f) {
            column.addView(TextView(this@AboutDeveloperActivity).apply {
                text = value
                textSize = size
                setLineSpacing(0f, 1.16f)
                includeFontPadding = true
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        fun action(label: String, callback: () -> Unit) {
            val button = LiquidGlassButton(this@AboutDeveloperActivity).apply {
                text = label
                setOnClickListener { callback() }
            }
            buttons += button
            column.addView(button, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        }
    }

    private fun body(value: String, size: Float = 15f) = TextView(this).apply {
        text = value
        textSize = size
        setLineSpacing(0f, 1.16f)
        includeFontPadding = true
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "没有可用的应用打开链接", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyPalette() {
        val colors = PaletteManager.colors(this)
        root.setBackgroundColor(colors.surface)
        pageBackdrop.setBackgroundColor(colors.surface)
        scrollBackdrop.setBackgroundColor(colors.surface)
        surfaces.forEach {
            GlassWidgetStyle.apply(it, Ui2DesignSystem.Shape.large, colors)
            it.backdropSource = pageBackdrop
            it.enableDynamicBackground = false
            it.enableSensorHighlight = false
        }
        buttons.forEach {
            GlassWidgetStyle.apply(it, Ui2DesignSystem.Shape.medium, colors)
            it.backdropSource = pageBackdrop
            it.enableDynamicBackground = false
            it.enableSensorHighlight = false
        }
        topIsland.setPalette(colors)
        back.imageTintList = android.content.res.ColorStateList.valueOf(colors.glassText)
        title.setTextColor(colors.glassText)
        applyTextPalette(content, colors)
    }

    private fun applyTextPalette(view: View, colors: PaletteDefinitions.Scheme) {
        if (view is TextView && view !is LiquidGlassButton) view.setTextColor(colors.glassText)
        if (view is ViewGroup) for (i in 0 until view.childCount) applyTextPalette(view.getChildAt(i), colors)
    }

    private fun applyResponsiveLayout() {
        if (!::root.isInitialized || root.width <= 0) return
        val safeWidth = (root.width - leftInset - rightInset).coerceAtLeast(1)
        val widthDp = (safeWidth / resources.displayMetrics.density).toInt()
        val tokens = AdaptiveLayoutPolicy.tokensForWidth(widthDp)
        val gutter = dp(tokens.screenGutterDp)
        val maxContent = dp(tokens.contentMaxWidthDp)
        val contentWidth = (safeWidth - gutter * 2).coerceAtMost(maxContent).coerceAtLeast(1)
        (content.layoutParams as? FrameLayout.LayoutParams)?.let {
            if (it.width != contentWidth) { it.width = contentWidth; content.layoutParams = it }
        }
        val topWidth = AdaptiveLayoutPolicy.topIslandWidthPx(this, (safeWidth - gutter * 2).coerceAtLeast(1))
        (topIsland.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.width = topWidth
            it.height = dp(64)
            it.gravity = Gravity.TOP or Gravity.LEFT
            it.leftMargin = leftInset + (safeWidth - topWidth) / 2
            it.topMargin = topInset + dp(Ui2DesignSystem.Spacing.sm)
            topIsland.layoutParams = it
        }
        scroll.setPadding(leftInset, topInset + dp(64 + Ui2DesignSystem.Spacing.xl), rightInset, bottomInset + dp(Ui2DesignSystem.Spacing.xl))
    }

    override fun onResume() {
        super.onResume()
        PaletteManager.apply(this)
        applyPalette()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
