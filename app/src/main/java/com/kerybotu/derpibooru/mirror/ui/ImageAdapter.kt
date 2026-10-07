package com.kerybotu.derpibooru.mirror.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.MotionEvent
import android.widget.FrameLayout
import com.example.liquidglass.LiquidGlassListItem
import com.example.liquidglass.LiquidGlassView
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.ColorUtils
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
    initialItems: List<Image>,
    private val onClick: (Image) -> Unit,
    private val onSelectionChanged: ((Int) -> Unit)? = null,
    private val settingsContext: Context,
    private val glassBackdrop: View? = null,
    private val onGlassCreated: ((LiquidGlassView) -> Unit)? = null
) : RecyclerView.Adapter<ImageAdapter.ImageViewHolder>() {
    private var allItems: List<Image> = initialItems
    private var items: List<Image> = filterForDisplay(initialItems)
    private val selectedIds = mutableSetOf<Int>()
    private var selectionSnackbar: Snackbar? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val binding = ItemImageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        if (glassBackdrop == null) return ImageViewHolder(binding)
        val card = LiquidGlassListItem(parent.context).apply {
            layoutParams = binding.root.layoutParams
            backdropSource = glassBackdrop
            groupCornerRadius = 16f * resources.displayMetrics.density
            isClickable = false
            isFocusable = false
        }
        binding.root.layoutParams = FrameLayout.LayoutParams(-1, -2)
        card.contentView = binding.root
        val surfaces = mutableListOf<LiquidGlassView>(card)
        // Capture artwork together with its spoiler cover; never sample hidden
        // artwork directly into a visible metadata badge.
        val artwork = FrameLayout(parent.context).apply {
            setBackgroundColor(PaletteManager.colors(context).surface)
        }
        binding.thumbnailContainer.removeView(binding.imageThumbnail)
        binding.thumbnailContainer.removeView(binding.thumbnailSpoilerCover)
        artwork.addView(binding.imageThumbnail)
        artwork.addView(binding.thumbnailSpoilerCover)
        binding.thumbnailContainer.addView(artwork, 0, FrameLayout.LayoutParams(-1, -1))
        val overlays = mutableMapOf<View, LiquidGlassView>()
        listOf(binding.infoBar, binding.statsBar, binding.selectionMark, binding.mediaTypeBadge).forEach { overlay ->
            val index = binding.thumbnailContainer.indexOfChild(overlay)
            val params = overlay.layoutParams
            binding.thumbnailContainer.removeView(overlay)
            val glass = object : LiquidGlassView(parent.context) {
                // Decorative badges must not consume the card's click or long press.
                override fun onTouchEvent(event: MotionEvent): Boolean = false
            }.apply {
                backdropSource = artwork
                addView(overlay, FrameLayout.LayoutParams(
                    if (params.width > 0) -1 else -2,
                    if (params.height > 0) -1 else -2
                ))
            }
            binding.thumbnailContainer.addView(glass, index, params)
            overlays[overlay] = glass
            surfaces += glass
        }
        surfaces.forEach { GlassWidgetStyle.apply(it, 16f); onGlassCreated?.invoke(it) }
        return ImageViewHolder(binding, card, surfaces, overlays)
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
        allItems = newItems
        items = filterForDisplay(newItems)
        selectedIds.retainAll(items.map { it.id }.toSet())
        onSelectionChanged?.invoke(selectedIds.size)
        notifyDataSetChanged()
    }

    fun appendData(newItems: List<Image>) {
        if (newItems.isEmpty()) return
        allItems = allItems + newItems
        val visibleItems = filterForDisplay(newItems)
        if (visibleItems.isEmpty()) return
        val start = items.size
        items = items + visibleItems
        notifyItemRangeInserted(start, visibleItems.size)
    }

    /** Re-evaluates cards after the user changes the global spoiler setting. */
    fun refreshDisplayMode() {
        val refreshed = filterForDisplay(allItems)
        if (refreshed == items) return
        items = refreshed
        selectedIds.retainAll(items.map { it.id }.toSet())
        onSelectionChanged?.invoke(selectedIds.size)
        notifyDataSetChanged()
    }

    private fun filterForDisplay(source: List<Image>): List<Image> =
        if (AppSettings.getSpoilerDisplayMode(settingsContext) == AppSettings.SpoilerDisplayMode.HIDE) {
            source.filterNot { it.spoilered }
        } else source

    fun selectedItems(): List<Image> = items.filter { selectedIds.contains(it.id) }
    fun clearSelection() {
        selectedIds.clear()
        selectionSnackbar?.dismiss(); selectionSnackbar = null
        onSelectionChanged?.invoke(0); notifyDataSetChanged()
    }

    class ImageViewHolder(
        val binding: ItemImageBinding,
        root: View = binding.root,
        private val glassSurfaces: List<LiquidGlassView> = emptyList(),
        private val glassOverlays: Map<View, LiquidGlassView> = emptyMap()
    ) : RecyclerView.ViewHolder(root) {

        fun bind(image: Image, onClick: (Image) -> Unit, selectionActive: Boolean, selected: Boolean, toggle: (Image, android.view.View) -> Unit) {
            val palette = PaletteManager.colors(binding.root.context)
            val glass = glassSurfaces.isNotEmpty()
            (binding.root as? androidx.cardview.widget.CardView)?.apply {
                setCardBackgroundColor(if (glass) Color.TRANSPARENT else palette.surface)
                if (glass) cardElevation = 0f
            }
            if (glass) {
                glassSurfaces.forEach { GlassWidgetStyle.apply(it, 16f) }
                (binding.imageThumbnail.parent as? View)?.setBackgroundColor(palette.surface)
                binding.infoBar.background = null
                binding.statsBar.background = null
                binding.selectionMark.background = null
                binding.mediaTypeBadge.background = null
            } else {
                styleOverlay(binding.infoBar, palette)
                styleOverlay(binding.statsBar, palette)
            }
            val foreground = if (glass) GlassWidgetStyle.ICON_COLOR else palette.onSurface
            val textColor = if (glass) GlassWidgetStyle.TEXT_COLOR else palette.onSurface
            if (glass) {
                binding.selectionMark.setTextColor(textColor)
                binding.mediaTypeBadge.imageTintList = android.content.res.ColorStateList.valueOf(foreground)
            }
            binding.textUpvotes.setTextColor(textColor)
            binding.textComments.setTextColor(textColor)
            binding.textFaves.setTextColor(textColor)
            tintInfoIcons(binding.infoBar, foreground)
            tintInfoIcons(binding.statsBar, foreground)
            CdnImageGate.load(binding.imageThumbnail, image.thumbnailUrl, AppSettings.getCdnThreads(binding.root.context))
            bindMediaTypeBadge(image)
            when (AppSettings.getSpoilerDisplayMode(binding.root.context)) {
                AppSettings.SpoilerDisplayMode.SHOW ->
                    SpoilerCover.bind(binding.thumbnailSpoilerCover, binding.imageThumbnail, false, interactive = false)
                AppSettings.SpoilerDisplayMode.HIDE ->
                    SpoilerCover.bind(binding.thumbnailSpoilerCover, binding.imageThumbnail, image.spoilered, interactive = false, showRevealButton = false, showLabel = false)
                AppSettings.SpoilerDisplayMode.CLICK_TO_SHOW ->
                    SpoilerCover.bind(binding.thumbnailSpoilerCover, binding.imageThumbnail, image.spoilered, interactive = false, showRevealButton = true, showLabel = false)
            }

            binding.textFaves.text = image.faves.toString()
            binding.textUpvotes.text = image.upvotes.toString()
            binding.textComments.text = image.commentCount.toString()
            binding.textDimensions.visibility = android.view.View.GONE

            binding.selectionMark.visibility = if (selected) android.view.View.VISIBLE else android.view.View.GONE
            glassOverlays.forEach { (content, surface) -> surface.visibility = content.visibility }
            binding.root.setOnClickListener {
                if (selectionActive) toggle(image, binding.root) else onClick(image)
            }
            binding.root.setOnLongClickListener {
                toggle(image, binding.root)
                true
            }
            if (!glass) Ui2DesignSystem.applyPressFeedback(binding.root)
        }

        private fun bindMediaTypeBadge(image: Image) {
            val mime = image.mimeType.orEmpty().lowercase()
            val url = image.thumbnailUrl.orEmpty().substringBefore('?').lowercase()
            val isVideo = mime.startsWith("video/") || url.endsWith(".webm") || url.endsWith(".mp4") || url.endsWith(".mov")
            val isGif = !isVideo && (mime == "image/gif" || url.endsWith(".gif"))
            binding.mediaTypeBadge.visibility = if (isVideo || isGif) android.view.View.VISIBLE else android.view.View.GONE
            if (isVideo) {
                binding.mediaTypeBadge.setImageResource(R.drawable.ic_video)
                binding.mediaTypeBadge.contentDescription = "视频"
            } else if (isGif) {
                binding.mediaTypeBadge.setImageResource(R.drawable.ic_gif)
                binding.mediaTypeBadge.contentDescription = "GIF 动图"
            }
        }

        private fun tintInfoIcons(view: android.view.View, color: Int) {
            if (view is android.widget.ImageView) view.imageTintList = android.content.res.ColorStateList.valueOf(color)
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) tintInfoIcons(view.getChildAt(index), color)
        }

        private fun styleOverlay(view: android.view.View, palette: com.kerybotu.derpibooru.mirror.PaletteDefinitions.Scheme) {
            val density = view.resources.displayMetrics.density
            val lightSurface = ColorUtils.calculateLuminance(palette.surface) > 0.5
            val fillAlpha = if (lightSurface) 190 else 170
            val borderAlpha = if (lightSurface) 140 else 112
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * density
                setColor(ColorUtils.setAlphaComponent(palette.surfaceVariant, fillAlpha))
                setStroke((density).toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(palette.divider, borderAlpha))
            }
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
