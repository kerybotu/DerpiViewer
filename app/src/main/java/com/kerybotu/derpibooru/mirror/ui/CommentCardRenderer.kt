package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.model.Comment
import com.kerybotu.derpibooru.mirror.translate.NiuTransService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Builds a consistent native comment card for details, profiles, and feeds. */
object CommentCardRenderer {
    fun create(
        context: Context,
        comment: Comment,
        scope: CoroutineScope,
        onImageClick: ((Int) -> Unit)? = null
    ): View {
        val colors = PaletteManager.colors(context)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 8).toFloat()
                setColor(colors.surfaceVariant)
            }
        }
        card.addView(TextView(context).apply {
            text = listOf(comment.author, comment.createdAt.take(10).takeIf { it.isNotBlank() })
                .filterNotNull().joinToString(" · ")
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colors.primary)
        })

        val body = TextView(context).apply {
            text = comment.body
            textSize = 14f
            setTextColor(colors.onSurface)
            setPadding(0, dp(context, 6), 0, 0)
        }
        card.addView(body)

        if (NiuTransService.shouldTranslate(comment.body)) {
            card.addView(Button(context).apply {
                text = "翻译"
                textSize = 12f
                PaletteManager.styleButton(this, colors)
                setOnClickListener {
                    val translated = tag as? String
                    if (translated != null) {
                        body.text = comment.body
                        tag = null
                        text = "翻译"
                        return@setOnClickListener
                    }
                    isEnabled = false
                    text = "翻译中…"
                    scope.launch {
                        NiuTransService.translate(comment.body)
                            .onSuccess {
                                body.text = it
                                tag = it
                                this@apply.text = "原文"
                            }
                            .onFailure { this@apply.text = "翻译" }
                        this@apply.isEnabled = true
                    }
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(context, 6) })
        }

        comment.imageId?.let { imageId ->
            if (onImageClick != null) {
                card.addView(TextView(context).apply {
                    text = "查看图片 #$imageId"
                    textSize = 13f
                    setTextColor(colors.primary)
                    setPadding(0, dp(context, 8), 0, 0)
                    isClickable = true
                    setOnClickListener { onImageClick(imageId) }
                })
            }
        }
        return card
    }

    fun layoutParams(context: Context) = LinearLayout.LayoutParams(-1, -2).apply {
        bottomMargin = dp(context, 10)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
