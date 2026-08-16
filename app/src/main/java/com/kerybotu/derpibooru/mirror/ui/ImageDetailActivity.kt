package com.kerybotu.derpibooru.mirror.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bumptech.glide.Glide
import com.google.android.material.chip.Chip
import com.kerybotu.derpibooru.mirror.R
import com.kerybotu.derpibooru.mirror.databinding.ActivityImageDetailBinding
import com.kerybotu.derpibooru.mirror.model.Image
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ImageDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageDetailBinding
    private lateinit var image: Image
    private val activityJob = Job()
    private val activityScope = CoroutineScope(Dispatchers.Main + activityJob)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.kerybotu.derpibooru.mirror.PaletteManager.apply(this)

        // 状态栏适配由布局中的 fitsSystemWindows 处理
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        image = intent.getSerializableExtra("image") as? Image ?: return

        // 设置折叠标题逻辑
        binding.collapsingToolbar.title = ""
        binding.appBar.addOnOffsetChangedListener { _, verticalOffset ->
            if (Math.abs(verticalOffset) >= binding.appBar.totalScrollRange) {
                binding.collapsingToolbar.title = "#${image.id}"
            } else {
                binding.collapsingToolbar.title = ""
            }
        }

        // 绑定基本数据
        bindCommonData()

        // 加载图片和详情
        loadImageAndDetails()

        // 设置点击进入全屏
        binding.detailImage.setOnClickListener {
            val intent = Intent(this, FullScreenImageActivity::class.java)
            intent.putExtra("image_id", image.id)
            intent.putExtra("thumbnail_url", image.thumbnailUrl)
            intent.putExtra("full_url", fullImageUrl ?: image.fullUrl)
            startActivity(intent)
        }
    }

    private fun bindCommonData() {
        binding.detailFaves.text = image.faves.toString()
        binding.detailUpvotes.text = image.upvotes.toString()
        binding.detailScore.text = image.score.toString()
        binding.detailDownvotes.text = image.downvotes.toString()
        binding.detailCommentsCount.text = image.commentCount.toString()
        val hideScore = com.kerybotu.derpibooru.mirror.AppSettings.isScoreHidden(this)
        binding.detailScore.visibility = if (hideScore) android.view.View.GONE else android.view.View.VISIBLE
        binding.detailUploader.visibility = if (com.kerybotu.derpibooru.mirror.AppSettings.isUploaderHidden(this)) android.view.View.GONE else android.view.View.VISIBLE
        binding.detailDimensions.text = "分辨率：${image.width} × ${image.height}"

        binding.detailTagsGroup.removeAllViews()
        image.tags.forEach { tag ->
            val chip = Chip(this)
            chip.text = tag
            chip.isChipIconVisible = false
            binding.detailTagsGroup.addView(chip)
        }
    }

    private var fullImageUrl: String? = null

    private fun loadImageAndDetails() {
        // 先加载缩略图
        Glide.with(this)
            .load(image.thumbnailUrl)
            .placeholder(R.drawable.ic_image_placeholder)
            .into(binding.detailImage)

        activityScope.launch {
            val json = withContext(Dispatchers.IO) {
                NetworkManager.getApi(this@ImageDetailActivity, "images/${image.id}")
            }
            if (json != null) {
                try {
                    val root = JSONObject(json)
                    val imageObj = root.getJSONObject("image")

                    val reps = imageObj.optJSONObject("representations")
                    val fullUrl = reps?.optString("full") ?: reps?.optString("large")
                    if (fullUrl != null) {
                        fullImageUrl = fullUrl
                        Glide.with(this@ImageDetailActivity).load(fullUrl).into(binding.detailImage)
                    }

                    val description = imageObj.optString("description", "")
                    if (description.isNotEmpty()) {
                        binding.detailDescription.text = description
                    }

                    binding.detailUploader.text = "上传者：${imageObj.optString("uploader", "未知")}"
                    val created = imageObj.optString("created_at", "")
                    binding.detailCreatedAt.text = "上传时间：${created.take(10).ifBlank { "未知" }}"
                    binding.detailFileSize.text = "文件大小：${formatSize(imageObj.optLong("size", 0))}"

                    val sourceUrl = imageObj.optJSONArray("source_urls")?.optString(0)
                    if (!sourceUrl.isNullOrBlank()) {
                        binding.detailSource.text = "来源：$sourceUrl"
                    }
                } catch (e: Exception) {
                    Log.e("ImageDetail", "解析详情失败", e)
                }
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "未知"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1 -> String.format("%.2f MiB", mb)
            kb >= 1 -> String.format("%.2f KiB", kb)
            else -> "$bytes B"
        }
    }

    override fun onDestroy() {
        activityJob.cancel()
        super.onDestroy()
    }
}
