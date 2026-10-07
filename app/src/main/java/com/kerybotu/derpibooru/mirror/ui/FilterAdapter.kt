package com.kerybotu.derpibooru.mirror.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.kerybotu.derpibooru.mirror.R
import com.kerybotu.derpibooru.mirror.databinding.ItemFilterBinding
import com.kerybotu.derpibooru.mirror.model.Filter

class FilterAdapter(private var items: List<Filter>, private val currentId: () -> Int?, private val onUse: (Filter) -> Unit) : RecyclerView.Adapter<FilterAdapter.Holder>() {
    private val animatedPositions = mutableSetOf<Int>()
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemFilterBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position], position)
    override fun getItemCount() = items.size
    fun update(newItems: List<Filter>) { items = newItems; notifyDataSetChanged() }

    inner class Holder(private val b: ItemFilterBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(filter: Filter, position: Int) {
            val colors = Ui2DesignSystem.colors(b.root.context)
            b.root.setCardBackgroundColor(colors.surface)
            b.root.strokeColor = colors.glassBorder
            b.root.strokeWidth = (b.root.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            b.filterName.text = filter.name
            b.filterId.text = "过滤器 ID：${filter.id}"
            b.filterOwner.text = if (filter.system) "官方维护" else "维护者：${filter.creator ?: filter.userId ?: "未知"}"
            b.filterName.setTextColor(colors.onSurface)
            b.filterId.setTextColor(PaletteManager.colors(b.root.context).muted)
            b.filterOwner.setTextColor(PaletteManager.colors(b.root.context).muted)
            b.filterDescription.text = filter.description.ifBlank { "公共场合可用的安全过滤器" }
            b.filterDescription.setTextColor(PaletteManager.colors(b.root.context).muted)
            b.filterChips.removeAllViews()
            addChip(b.filterChips, "剧透 ${filter.spoilerCount}")
            addChip(b.filterChips, "隐藏 ${filter.hiddenCount}")
            val isCurrent = currentId() == filter.id
            b.filterUse.text = if (isCurrent) "✓ 使用中" else "使用此过滤器"
            b.filterUse.isEnabled = !isCurrent
            b.filterUse.alpha = if (isCurrent) 0.75f else 1f
            b.filterUse.setTextColor(colors.onSurface)
            b.filterUse.background = pillBackground(colors.primary.copyAlpha(if (isCurrent) 0.30f else 0.78f), colors.glassBorder, 15f)
            b.filterMore.imageTintList = android.content.res.ColorStateList.valueOf(PaletteManager.colors(b.root.context).muted)
            Ui2DesignSystem.applyPressFeedback(b.filterUse)
            Ui2DesignSystem.applyPressFeedback(b.filterMore)
            b.filterUse.setOnClickListener { onUse(filter) }
            b.filterMore.setOnClickListener { showMenu(filter) }
            if (position < 6 && animatedPositions.add(position)) {
                b.root.alpha = 0f
                b.root.translationY = (12 * b.root.resources.displayMetrics.density)
                b.root.post {
                    b.root.animate().alpha(1f).translationY(0f)
                        .setStartDelay((position * 30L).coerceAtMost(150L))
                        .setDuration(Ui2DesignSystem.Motion.normalMs)
                        .setInterpolator(Ui2DesignSystem.Motion.standard)
                        .start()
                }
            } else {
                b.root.alpha = 1f
                b.root.translationY = 0f
            }
        }

        private fun addChip(parent: LinearLayout, text: String) {
            val colors = Ui2DesignSystem.colors(parent.context)
            parent.addView(TextView(parent.context).apply {
                this.text = text
                textSize = 12f
                setTextColor(colors.onSurface)
                gravity = android.view.Gravity.CENTER
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = pillBackground(colors.secondaryContainer.copyAlpha(0.92f), colors.glassBorder, 50f)
            }, LinearLayout.LayoutParams(-2, dp(30)).apply { marginEnd = dp(8) })
        }

        private fun pillBackground(fill: Int, stroke: Int, radiusDp: Float) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * b.root.resources.displayMetrics.density
            setColor(fill)
            setStroke(dp(1), stroke)
        }

        private fun dp(value: Int) = (value * b.root.resources.displayMetrics.density).toInt()

        private fun Int.copyAlpha(alpha: Float): Int = android.graphics.Color.argb(
            (alpha.coerceIn(0f, 1f) * 255).toInt(),
            android.graphics.Color.red(this), android.graphics.Color.green(this), android.graphics.Color.blue(this)
        )

        private fun showMenu(filter: Filter) {
            PopupMenu(b.root.context, b.filterMore).apply {
                menu.add("查看详情")
                menu.add("复制并自定义")
                menu.add("编辑")
                setOnMenuItemClickListener {
                    Toast.makeText(b.root.context, "${it.title}：${filter.name}", Toast.LENGTH_SHORT).show(); true
                }
            }.show()
        }
    }
}
