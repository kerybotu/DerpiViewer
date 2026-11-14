package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.kerybotu.derpibooru.mirror.R
import java.util.Locale

internal data class DetailStatistics(val likes: Int?, val favourites: Int?, val comments: Int?)

internal object DetailStatisticFormat {
    fun count(value: Int?): String {
        if (value == null || value < 0) return "\u2014"
        if (value < 1_000) return value.toString()
        var divisor = when {
            value >= 1_000_000_000 -> 1_000_000_000.0
            value >= 1_000_000 -> 1_000_000.0
            else -> 1_000.0
        }
        var suffix = when (divisor) {
            1_000_000_000.0 -> "B"
            1_000_000.0 -> "M"
            else -> "K"
        }
        var amount = value / divisor
        var precision = if (amount < 100.0) 1 else 0
        if (amount >= 999.95 && divisor < 1_000_000_000.0) {
            divisor *= 1_000.0
            suffix = if (suffix == "K") "M" else "B"
            amount = value / divisor
            precision = 1
        }
        val rounded = String.format(Locale.ROOT, "%." + precision + "f", amount).trimEnd('0').trimEnd('.')
        return rounded + suffix
    }
}

internal class DetailStatisticsView(context: Context) : LinearLayout(context) {
    private val items = listOf(Item(R.drawable.ic_arrow_up, "点赞"), Item(R.drawable.ic_favorite, "收藏"), Item(R.drawable.ic_comment, "评论"))
    private val labels = items.map { item ->
        TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            compoundDrawablePadding = dp(2)
            contentDescription = item.label
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            minHeight = dp(48)
            isSingleLine = true
        }.also { addView(it) }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        items.forEachIndexed { index, item -> labels[index].setCompoundDrawablesRelative(null, icon(item.icon), null, null) }
        bind(null, loading = true)
    }

    fun bind(statistics: DetailStatistics?, loading: Boolean = false) {
        val values = listOf(statistics?.likes, statistics?.favourites, statistics?.comments)
        labels.forEachIndexed { index, label ->
            label.text = if (loading) "\u2026" else DetailStatisticFormat.count(values[index])
            label.contentDescription = when {
                loading -> items[index].label + "加载中"
                values[index] == null || values[index]!! < 0 -> items[index].label + "不可用"
                else -> items[index].label + " " + values[index]
            }
        }
    }

    fun applyColor(color: Int) {
        labels.forEach { label ->
            label.setTextColor(color)
            label.compoundDrawablesRelative.forEach { drawable -> drawable?.let { DrawableCompat.setTint(it, color) } }
        }
    }

    private fun icon(resource: Int): Drawable? = ContextCompat.getDrawable(context, resource)?.mutate()?.let {
        DrawableCompat.setTint(it, Color.GRAY)
        val size = dp(18)
        it.setBounds(0, 0, size, size)
        it
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private data class Item(val icon: Int, val label: String)
}
