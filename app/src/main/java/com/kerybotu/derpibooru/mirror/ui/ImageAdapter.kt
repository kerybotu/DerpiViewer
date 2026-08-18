package com.kerybotu.derpibooru.mirror.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.kerybotu.derpibooru.mirror.R
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.databinding.ItemImageBinding
import com.kerybotu.derpibooru.mirror.model.Image
import com.kerybotu.derpibooru.mirror.download.DownloadQueueManager
import com.google.android.material.snackbar.Snackbar
import com.kerybotu.derpibooru.mirror.PaletteManager

class ImageAdapter(
    private var items: List<Image>,
    private val onClick: (Image) -> Unit,
    private val onSelectionChanged: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<ImageAdapter.ImageViewHolder>() {
    private val selectedIds = mutableSetOf<Int>()
    private var selectionSnackbar: Snackbar? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val binding = ItemImageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ImageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        holder.bind(items[position], onClick, selectedIds.isNotEmpty(), selectedIds.contains(items[position].id)) { image, anchor -> toggleSelection(image, anchor) }
    }

    override fun getItemCount(): Int = items.size

    override fun onViewRecycled(holder: ImageViewHolder) {
        Glide.with(holder.binding.imageThumbnail).clear(holder.binding.imageThumbnail)
        super.onViewRecycled(holder)
    }

    fun updateData(newItems: List<Image>) {
        items = newItems
        selectedIds.retainAll(newItems.map { it.id }.toSet())
        onSelectionChanged?.invoke(selectedIds.size)
        notifyDataSetChanged()
    }

    fun selectedItems(): List<Image> = items.filter { selectedIds.contains(it.id) }
    fun clearSelection() {
        selectedIds.clear()
        selectionSnackbar?.dismiss(); selectionSnackbar = null
        onSelectionChanged?.invoke(0); notifyDataSetChanged()
    }

    class ImageViewHolder(
        val binding: ItemImageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(image: Image, onClick: (Image) -> Unit, selectionActive: Boolean, selected: Boolean, toggle: (Image, android.view.View) -> Unit) {
            val palette = PaletteManager.colors(binding.root.context)
            (binding.root as? androidx.cardview.widget.CardView)?.setCardBackgroundColor(palette.surface)
            binding.infoBar.setBackgroundColor(palette.surfaceVariant)
            binding.textUpvotes.setTextColor(palette.onSurface)
            binding.textComments.setTextColor(palette.onSurface)
            binding.textDimensions.setTextColor(palette.muted)
            for (index in 0 until binding.infoBar.childCount) {
                val child = binding.infoBar.getChildAt(index)
                tintInfoIcons(child, palette.onSurface)
            }
            CdnImageGate.load(binding.imageThumbnail, image.thumbnailUrl, AppSettings.getCdnThreads(binding.root.context))

            binding.textFaves.text = image.faves.toString()
            binding.textUpvotes.text = image.upvotes.toString()
            binding.textComments.text = image.commentCount.toString()
            binding.textScore.text = "评分 ${image.score}"
            binding.textDimensions.text = "${image.width}×${image.height}"
            binding.textScore.visibility = if (AppSettings.isScoreHidden(binding.root.context)) android.view.View.GONE else android.view.View.VISIBLE

            binding.selectionMark.visibility = if (selected) android.view.View.VISIBLE else android.view.View.GONE
            binding.root.setOnClickListener {
                if (selectionActive) toggle(image, binding.root) else onClick(image)
            }
            binding.root.setOnLongClickListener {
                toggle(image, binding.root)
                true
            }
        }

        private fun tintInfoIcons(view: android.view.View, color: Int) {
            if (view is android.widget.ImageView) view.imageTintList = android.content.res.ColorStateList.valueOf(color)
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) tintInfoIcons(view.getChildAt(index), color)
        }
    }

    private fun toggleSelection(image: Image, anchor: android.view.View) {
        if (selectedIds.contains(image.id)) selectedIds.remove(image.id) else selectedIds.add(image.id)
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedIds.size)
        if (selectedIds.isEmpty()) {
            selectionSnackbar?.dismiss(); selectionSnackbar = null
            return
        }
        // Pages with a dedicated selection toolbar handle the action themselves;
        // other grids retain the compact inline download action.
        if (onSelectionChanged == null) {
            selectionSnackbar?.dismiss()
            selectionSnackbar = Snackbar.make(anchor, "已选 ${selectedIds.size} 张图片", Snackbar.LENGTH_INDEFINITE)
                .setAction("下载") {
                    val chosen = items.filter { selectedIds.contains(it.id) }
                    DownloadQueueManager.get(anchor.context).enqueueImages(chosen)
                    selectedIds.clear()
                    selectionSnackbar = null
                    onSelectionChanged?.invoke(0)
                    notifyDataSetChanged()
                    Snackbar.make(anchor, "已加入下载队列", Snackbar.LENGTH_SHORT).show()
                }
            selectionSnackbar?.show()
        }
    }
}
