package com.kerybotu.derpibooru.mirror.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.kerybotu.derpibooru.mirror.R
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.databinding.ItemImageBinding
import com.kerybotu.derpibooru.mirror.model.Image

class ImageAdapter(
    private var items: List<Image>,
    private val onClick: (Image) -> Unit
) : RecyclerView.Adapter<ImageAdapter.ImageViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val binding = ItemImageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ImageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        holder.bind(items[position], onClick)
    }

    override fun getItemCount(): Int = items.size

    fun updateData(newItems: List<Image>) {
        items = newItems
        notifyDataSetChanged()
    }

    class ImageViewHolder(
        private val binding: ItemImageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(image: Image, onClick: (Image) -> Unit) {
            Glide.with(binding.root.context)
                .load(image.thumbnailUrl)
                .placeholder(R.drawable.ic_image_placeholder)
                .into(binding.imageThumbnail)

            binding.textFaves.text = image.faves.toString()
            binding.textUpvotes.text = image.upvotes.toString()
            binding.textComments.text = image.commentCount.toString()
            binding.textScore.text = "评分 ${image.score}"
            binding.textDimensions.text = "${image.width}×${image.height}"
            binding.textScore.visibility = if (AppSettings.isScoreHidden(binding.root.context)) android.view.View.GONE else android.view.View.VISIBLE

            binding.root.setOnClickListener { onClick(image) }
        }
    }
}
