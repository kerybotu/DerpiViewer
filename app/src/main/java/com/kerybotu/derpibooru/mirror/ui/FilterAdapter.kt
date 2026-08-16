package com.kerybotu.derpibooru.mirror.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import com.google.android.material.chip.Chip
import androidx.recyclerview.widget.RecyclerView
import com.kerybotu.derpibooru.mirror.R
import com.kerybotu.derpibooru.mirror.databinding.ItemFilterBinding
import com.kerybotu.derpibooru.mirror.model.Filter

class FilterAdapter(private var items: List<Filter>, private val currentId: () -> Int?, private val onUse: (Filter) -> Unit) : RecyclerView.Adapter<FilterAdapter.Holder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemFilterBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size
    fun update(newItems: List<Filter>) { items = newItems; notifyDataSetChanged() }

    inner class Holder(private val b: ItemFilterBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(filter: Filter) {
            b.filterName.text = filter.name
            b.filterOwner.text = if (filter.system) "官方维护" else "维护者：${filter.userId ?: "未知"}"
            b.filterDescription.text = filter.description.ifBlank { "公共场合可用的安全过滤器" }
            b.filterChips.removeAllViews()
            addChip(b.filterChips, "剧透 ${filter.spoilerCount}")
            addChip(b.filterChips, "隐藏 ${filter.hiddenCount}")
            val isCurrent = currentId() == filter.id
            b.filterUse.text = if (isCurrent) "✓ 使用中" else "使用此过滤器"
            b.filterUse.isEnabled = !isCurrent
            b.filterUse.alpha = if (isCurrent) 0.75f else 1f
            b.filterUse.setOnClickListener { onUse(filter) }
            b.filterMore.setOnClickListener { showMenu(filter) }
        }

        private fun addChip(parent: LinearLayout, text: String) {
            parent.addView(Chip(parent.context).apply { this.text = text; isClickable = false; isCheckable = false })
        }

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
