package com.kerybotu.derpibooru.mirror.ui

import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import com.kerybotu.derpibooru.mirror.databinding.ItemVideoFeedBinding

data class VideoPost(
    val id: Int,
    val url: String,
    val uploader: String,
    val tags: List<String>,
    var upvotes: Int,
    var downvotes: Int,
    val commentCount: Int
)

class VideoFeedAdapter(private val actions: Actions) : RecyclerView.Adapter<VideoFeedAdapter.Holder>() {
    interface Actions {
        fun onToggle(position: Int)
        fun onDoubleTap(position: Int)
        fun onLongPress(position: Int, active: Boolean)
        fun onUpvote(position: Int)
        fun onDownvote(position: Int)
        fun onFavorite(position: Int)
        fun onComments(position: Int)
        fun onDownload(position: Int)
        fun onMore(position: Int, anchor: View)
    }

    private val items = mutableListOf<VideoPost>()

    fun replace(posts: List<VideoPost>) { items.clear(); items.addAll(posts); notifyDataSetChanged() }
    fun append(posts: List<VideoPost>) { val start = items.size; items.addAll(posts); notifyItemRangeInserted(start, posts.size) }
    fun item(position: Int): VideoPost? = items.getOrNull(position)
    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemVideoFeedBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position], position, actions)
    override fun onViewRecycled(holder: Holder) { holder.binding.videoPlayer.player = null; super.onViewRecycled(holder) }

    class Holder(val binding: ItemVideoFeedBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(post: VideoPost, position: Int, actions: Actions) = with(binding) {
            videoUploader.text = post.uploader
            videoTags.text = post.tags.take(3).joinToString("  ·  ")
            videoUpvoteCount.text = post.upvotes.toString()
            videoUpvote.setOnClickListener { actions.onUpvote(bindingAdapterPosition) }
            videoDownvote.setOnClickListener { actions.onDownvote(bindingAdapterPosition) }
            videoFavorite.setOnClickListener { actions.onFavorite(bindingAdapterPosition) }
            videoComments.setOnClickListener { actions.onComments(bindingAdapterPosition) }
            videoDownload.setOnClickListener { actions.onDownload(bindingAdapterPosition) }
            videoMore.setOnClickListener { actions.onMore(bindingAdapterPosition, it) }
            val detector = GestureDetector(root.context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean { actions.onToggle(bindingAdapterPosition); return true }
                override fun onDoubleTap(e: MotionEvent): Boolean { actions.onDoubleTap(bindingAdapterPosition); return true }
                override fun onLongPress(e: MotionEvent) { actions.onLongPress(bindingAdapterPosition, true) }
            })
            videoPlayer.setOnTouchListener { _, event ->
                detector.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    actions.onLongPress(bindingAdapterPosition, false)
                }
                true
            }
        }
    }
}
