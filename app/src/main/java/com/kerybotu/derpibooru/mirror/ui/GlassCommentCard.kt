package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.example.liquidglass.LiquidGlassButton
import com.example.liquidglass.LiquidGlassListItem
import com.example.liquidglass.LiquidGlassView
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.model.Comment
import com.kerybotu.derpibooru.mirror.translate.NiuTransService

/** State belongs to the comment, not the recycled view displaying it. */
internal class CommentTranslation {
    var text: String? = null
    var showTranslation = false
    var loading = false
}

internal class GlassCommentCard(context: Context) : LiquidGlassListItem(context) {
    private val author = TextView(context).apply {
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
    }
    private val body = TextView(context).apply {
        textSize = 14f
        setPadding(0, dp(6), 0, dp(8))
    }
    private val translateButton = actionButton()
    private val imageButton = actionButton()
    private val actions = LinearLayout(context).apply {
        addView(translateButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        addView(imageButton, LinearLayout.LayoutParams(0, dp(48), 1f))
    }
    val glassSurfaces: List<LiquidGlassView> get() = listOf(this, translateButton, imageButton)

    init {
        contentView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(author)
            addView(body)
            addView(actions, LinearLayout.LayoutParams(-1, -2))
        }
        isClickable = false
        isFocusable = false
        applyPalette()
    }

    fun bind(
        comment: Comment,
        translation: CommentTranslation?,
        onTranslate: () -> Unit,
        onImageClick: (Int) -> Unit
    ) {
        author.text = listOfNotNull(comment.author, comment.createdAt.take(10).takeIf { it.isNotBlank() })
            .joinToString(" · ")
        body.text = if (translation?.showTranslation == true) translation.text ?: comment.body else comment.body
        translateButton.visibility = if (NiuTransService.shouldTranslate(comment.body)) View.VISIBLE else View.GONE
        translateButton.text = when {
            translation?.loading == true -> "翻译中…"
            translation?.showTranslation == true -> "原文"
            else -> "翻译"
        }
        translateButton.isEnabled = translation?.loading != true
        translateButton.setOnClickListener { if (translateButton.isEnabled) onTranslate() }
        imageButton.visibility = if (comment.imageId != null) View.VISIBLE else View.GONE
        imageButton.text = "查看图片 #${comment.imageId ?: ""}"
        imageButton.setOnClickListener { comment.imageId?.let(onImageClick) }
        actions.visibility = if (translateButton.visibility == View.VISIBLE || comment.imageId != null) View.VISIBLE else View.GONE
        applyPalette()
    }

    fun applyPalette() {
        glassSurfaces.forEach { GlassWidgetStyle.apply(it, 16f) }
        val colors = PaletteManager.colors(context)
        author.setTextColor(colors.glassText)
        body.setTextColor(colors.glassSecondaryText)
    }

    // Child action buttons handle clicks; the card's empty area belongs to the scrolling list.
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    private fun actionButton() = LiquidGlassButton(context).apply {
        setTextSize(12f)
        textView.setPadding(dp(8), dp(8), dp(8), dp(8))
        textView.ellipsize = TextUtils.TruncateAt.END
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
