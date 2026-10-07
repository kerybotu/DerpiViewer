package com.kerybotu.derpibooru.mirror.ui.menu

import android.app.Dialog
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.ui.IslandGlassView
import com.kerybotu.derpibooru.mirror.ui.GlassWidgetStyle
import com.kerybotu.derpibooru.mirror.ui.Ui2DesignSystem

class GlassMenuCard(context: Context, val items: List<GlassMenuItem>) : Dialog(context) {
    private val density = context.resources.displayMetrics.density
    private val glassContainer = IslandGlassView(context)
    private val panelWidth: Int
    private val panelHeight: Int

    init {
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setGravity(Gravity.START or Gravity.TOP)
        val params = window?.attributes
        params?.dimAmount = 0.35f
        window?.attributes = params
        setCanceledOnTouchOutside(true)

        val colors = PaletteManager.colors(context)
        GlassWidgetStyle.apply(glassContainer, GlassMenuTokens.cornerRadiusDp)
        glassContainer.setPalette(colors)
        glassContainer.elevation = Ui2DesignSystem.Elevation.dialogDp * density

        val padding = (GlassMenuTokens.contentPaddingDp * density).toInt()
        val cardLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        items.forEach { item ->
            val itemView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                isEnabled = item.enabled
                alpha = if (item.enabled) 1f else 0.5f
                val hPad = (16 * density).toInt()
                val vPad = (14 * density).toInt()
                setPadding(hPad, vPad, hPad, vPad)

                if (item.selected) {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = GlassMenuTokens.itemCornerRadiusDp * density
                        setColor(colors.glassSurfaceElevated)
                        setStroke((density).toInt().coerceAtLeast(1), colors.glassBorder)
                    }
                } else {
                    val outValue = android.util.TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                    setBackgroundResource(outValue.resourceId)
                }

                setOnClickListener {
                    if (item.enabled) {
                        dismiss()
                        item.onClick()
                    }
                }
            }

            val iconView = ImageView(context).apply {
                setImageResource(item.iconRes)
                imageTintList = android.content.res.ColorStateList.valueOf(if (item.selected) colors.primary else colors.onSurface)
            }
            val iconParams = LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt()).apply {
                marginEnd = (16 * density).toInt()
            }
            itemView.addView(iconView, iconParams)

            val titleView = TextView(context).apply {
                text = item.title
                textSize = 15f
                setTextColor(if (item.selected) colors.primary else colors.onSurface)
                typeface = if (item.selected) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            }
            itemView.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))

            container.addView(itemView, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = (4 * density).toInt()
            })
        }

        val scroll = ScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            addView(container)
        }

        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val maxW = (GlassMenuTokens.maxMenuWidthDp * density).toInt()
        panelWidth = (screenWidth - (32 * density).toInt()).coerceAtMost(maxW)
        val availableHeight = (metrics.heightPixels - (64 * density).toInt()).coerceAtLeast(1)
        val desiredHeight = (items.size * GlassMenuTokens.itemHeightDp * density + padding * 2).toInt()
        panelHeight = desiredHeight.coerceAtMost(availableHeight)

        cardLayout.addView(scroll, LinearLayout.LayoutParams(panelWidth, panelHeight - padding * 2))
        glassContainer.addView(cardLayout, FrameLayout.LayoutParams(panelWidth, panelHeight))
        setContentView(glassContainer)

        window?.decorView?.let { decor ->
            decor.scaleX = 0.96f
            decor.scaleY = 0.96f
            decor.alpha = 0f
            decor.translationX = -16f * density
        }
    }

    override fun show() {
        super.show()
        window?.setLayout(panelWidth, panelHeight)
        glassContainer.setPalette(PaletteManager.colors(context))
        glassContainer.post {
            glassContainer.setRenderingActive(true, activityContentRoot())
        }
        window?.decorView?.let { decor ->
            decor.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .translationX(0f)
                .setDuration(220L)
                .setInterpolator(Ui2DesignSystem.Motion.spring)
                .start()
        }
    }

    override fun dismiss() {
        glassContainer.setRenderingActive(false, null)
        window?.decorView?.let { decor ->
            decor.animate()
                .scaleX(0.97f)
                .scaleY(0.97f)
                .alpha(0f)
                .translationX(-12f * density)
                .setDuration(180L)
                .setInterpolator(Ui2DesignSystem.Motion.standard)
                .withEndAction { super.dismiss() }
                .start()
        } ?: super.dismiss()
    }

    private fun activityContentRoot(): View? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) {
                return current.findViewById(android.R.id.content) ?: current.window.decorView
            }
            current = current.baseContext
        }
        return null
    }
}
