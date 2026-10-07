package com.kerybotu.derpibooru.mirror.ui

import android.content.res.ColorStateList
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.R

/** Shared spoiler presentation for feed cards and the image-detail preview. */
object SpoilerCover {
    fun bind(
        overlay: FrameLayout,
        contentView: View,
        spoilered: Boolean,
        revealed: Boolean = false,
        interactive: Boolean = true,
        showRevealButton: Boolean = interactive,
        showLabel: Boolean = interactive,
        onRevealed: () -> Unit = {}
    ) {
        overlay.animate().cancel()
        overlay.alpha = 1f
        overlay.removeAllViews()
        if (!spoilered || revealed) {
            overlay.visibility = View.GONE
            obscure(contentView, false)
            return
        }

        val context = overlay.context
        val palette = PaletteManager.colors(context)
        overlay.visibility = View.VISIBLE
        // Keep the cover transparent to card taps. Only the explicit reveal
        // button consumes a tap, so a spoiler card can still open its detail page.
        overlay.isClickable = false
        overlay.isFocusable = false
        overlay.setBackgroundColor(ColorUtils.setAlphaComponent(palette.surface, 218))
        overlay.setOnClickListener(if (interactive) View.OnClickListener { } else null)
        obscure(contentView, true)

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(context, 20), dp(context, 20), dp(context, 20), dp(context, 20))
        }
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_visibility_off)
            imageTintList = ColorStateList.valueOf(palette.onSurface)
            contentDescription = "剧透内容已隐藏"
        }
        content.addView(icon, LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        if (showLabel) {
            content.addView(TextView(context).apply {
                text = "剧透内容"
                textSize = 14f
                setTextColor(palette.onSurface)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(context, 8); gravity = Gravity.CENTER_HORIZONTAL })
        }
        val reveal = Button(context).apply {
            text = "显示"
            minHeight = 0
            minimumHeight = 0
            minWidth = dp(context, 76)
            minimumWidth = 0
            PaletteManager.styleButton(this, palette)
        }
        if (showRevealButton) {
            content.addView(reveal, LinearLayout.LayoutParams(-2, dp(context, 40)).apply { topMargin = dp(context, 8); gravity = Gravity.CENTER_HORIZONTAL })
        }
        overlay.addView(content, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        reveal.setOnClickListener {
            overlay.animate()
                .alpha(0f)
                .setDuration(180L)
                .withEndAction {
                    overlay.visibility = View.GONE
                    overlay.alpha = 1f
                    obscure(contentView, false)
                    onRevealed()
                }
                .start()
        }
    }

    private fun obscure(view: View, enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(if (enabled) RenderEffect.createBlurEffect(28f, 28f, Shader.TileMode.CLAMP) else null)
        } else {
            view.alpha = if (enabled) 0.22f else 1f
        }
    }

    private fun dp(context: android.content.Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
