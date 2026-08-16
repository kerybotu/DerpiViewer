package com.kerybotu.derpibooru.mirror.ui

import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomViewTarget
import com.bumptech.glide.request.transition.Transition
import com.kerybotu.derpibooru.mirror.R
import com.kerybotu.derpibooru.mirror.network.NetworkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class FullScreenImageActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector
    private val matrix = Matrix()
    private var baseScale = 1f
    private var minScale = 0.5f
    private var maxScale = 5f

    private val activityJob = Job()
    private val activityScope = CoroutineScope(Dispatchers.Main + activityJob)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fullscreen_image)
        com.kerybotu.derpibooru.mirror.PaletteManager.apply(this)

        imageView = findViewById(R.id.fullscreen_image)
        progressBar = findViewById(R.id.loading_progress)
        val closeBtn: ImageView = findViewById(R.id.btn_close)

        imageView.scaleType = ImageView.ScaleType.MATRIX

        val thumbnailUrl = intent.getStringExtra("thumbnail_url")
        val fullUrl = intent.getStringExtra("full_url")
        val imageId = intent.getIntExtra("image_id", -1)

        // 先显示缩略图（如果存在）
        if (thumbnailUrl != null) {
            Glide.with(this)
                .asDrawable()
                .load(thumbnailUrl)
                .into(object : CustomViewTarget<ImageView, Drawable>(imageView) {
                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        imageView.setImageResource(R.drawable.ic_image_placeholder)
                    }

                    override fun onResourceCleared(placeholder: Drawable?) {
                    }

                    override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                        imageView.setImageDrawable(resource)
                        fitImageToScreen(resource.intrinsicWidth, resource.intrinsicHeight)
                    }
                })
        } else {
            imageView.setImageResource(R.drawable.ic_image_placeholder)
        }

        // 初始化手势
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                val currentScale = getCurrentScale()
                val newScale = currentScale * scaleFactor
                if (newScale in minScale..maxScale) {
                    matrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                    imageView.imageMatrix = matrix
                }
                return true
            }
        })

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (!scaleGestureDetector.isInProgress) {
                    matrix.postTranslate(-distanceX, -distanceY)
                    imageView.imageMatrix = matrix
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val currentScale = getCurrentScale()
                val targetScale = if (currentScale > baseScale * 1.5f) baseScale else maxScale
                val scaleFactor = targetScale / currentScale
                matrix.postScale(scaleFactor, scaleFactor, e.x, e.y)
                imageView.imageMatrix = matrix
                return true
            }
        })

        imageView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (!scaleGestureDetector.isInProgress) {
                gestureDetector.onTouchEvent(event)
            }
            true
        }

        closeBtn.setOnClickListener { finish() }

        // 加载完整图片
        loadFullImage(thumbnailUrl, fullUrl, imageId)
    }

    private fun loadFullImage(thumbnailUrl: String?, fullUrl: String?, imageId: Int) {
        if (fullUrl != null) {
            // 已提供完整 URL，直接加载，并显示转圈
            showLoading()
            loadImageWithGlide(fullUrl) {
                hideLoading()
                // 重新适应屏幕
                val drawable = imageView.drawable
                if (drawable != null) {
                    fitImageToScreen(drawable.intrinsicWidth, drawable.intrinsicHeight)
                }
            }
        } else if (imageId > 0) {
            // 需要请求 API 获取完整图片 URL
            showLoading()
            activityScope.launch {
                val fetchedUrl = withContext(Dispatchers.IO) {
                    fetchFullImageUrlFromApi(imageId)
                }
                if (fetchedUrl != null) {
                    loadImageWithGlide(fetchedUrl) {
                        hideLoading()
                        val drawable = imageView.drawable
                        if (drawable != null) {
                            fitImageToScreen(drawable.intrinsicWidth, drawable.intrinsicHeight)
                        }
                    }
                } else {
                    // 请求失败，隐藏转圈，保留缩略图
                    hideLoading()
                }
            }
        } else {
            // 没有 imageId，也没有 fullUrl，只能保持缩略图
            hideLoading()
        }
    }

    private suspend fun fetchFullImageUrlFromApi(imageId: Int): String? = withContext(Dispatchers.IO) {
        try {
            // 确保网络管理器已初始化（如果主 Activity 已初始化则直接使用）
            val json = NetworkManager.get("https://derpibooru.org/api/v1/json/images/$imageId")
            if (json != null) {
                val root = JSONObject(json)
                val imageObj = root.getJSONObject("image")
                val reps = imageObj.optJSONObject("representations")
                reps?.optString("full") ?: reps?.optString("large")
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun loadImageWithGlide(url: String, onComplete: () -> Unit) {
        Glide.with(this)
            .asDrawable()
            .load(url)
            .into(object : CustomViewTarget<ImageView, Drawable>(imageView) {
                override fun onLoadFailed(errorDrawable: Drawable?) {
                    onComplete()
                }

                override fun onResourceCleared(placeholder: Drawable?) {
                }

                override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                    imageView.setImageDrawable(resource)
                    onComplete()
                }
            })
    }

    private fun showLoading() {
        progressBar.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        progressBar.visibility = View.GONE
    }

    private fun getCurrentScale(): Float {
        val values = FloatArray(9)
        matrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun fitImageToScreen(drawableWidth: Int, drawableHeight: Int) {
        if (drawableWidth <= 0 || drawableHeight <= 0) return
        val viewWidth = imageView.width.toFloat()
        val viewHeight = imageView.height.toFloat()
        if (viewWidth <= 0 || viewHeight <= 0) {
            imageView.post { fitImageToScreen(drawableWidth, drawableHeight) }
            return
        }

        val scaleX = viewWidth / drawableWidth
        val scaleY = viewHeight / drawableHeight
        baseScale = minOf(scaleX, scaleY)
        minScale = baseScale * 0.5f
        maxScale = baseScale * 5f

        matrix.reset()
        matrix.postScale(baseScale, baseScale)
        val dx = (viewWidth - drawableWidth * baseScale) / 2f
        val dy = (viewHeight - drawableHeight * baseScale) / 2f
        matrix.postTranslate(dx, dy)
        imageView.imageMatrix = matrix
    }

    override fun onDestroy() {
        activityJob.cancel()
        super.onDestroy()
    }
}
