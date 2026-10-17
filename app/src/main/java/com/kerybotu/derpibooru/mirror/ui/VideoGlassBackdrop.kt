package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.TextureView
import android.view.View
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.RecyclerView
import com.example.liquidglass.LiquidGlassView
import com.kerybotu.derpibooru.mirror.PaletteManager
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** A video-page backdrop that draws artwork only, never its floating controls. */
class VideoGlassBackdrop @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var results: RecyclerView? = null
    private val surfaces = mutableSetOf<LiquidGlassView>()
    private val frames = WeakHashMap<TextureView, Frame>()
    private val origin = IntArray(2)
    private val location = IntArray(2)
    private val visibleRect = Rect()
    private val destination = RectF()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var active = false
    private var observer: ViewTreeObserver? = null
    private val beforeDraw = ViewTreeObserver.OnPreDrawListener {
        updateRendering()
        true
    }

    private class Frame(val bitmap: Bitmap, var capturedAt: Long = Long.MIN_VALUE)

    init {
        setWillNotDraw(false)
        setBackgroundColor(PaletteManager.colors(context).surface)
    }

    fun track(view: LiquidGlassView, radius: Float) {
        GlassWidgetStyle.apply(view, radius)
        view.elevation = Ui2DesignSystem.Elevation.islandDp * resources.displayMetrics.density
        view.isClickable = false
        view.isFocusable = false
        view.enableDynamicBackground = false
        view.enableSensorHighlight = false
        view.backdropSource = this
        view.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                surfaces += view
                updateRendering()
            }

            override fun onViewDetachedFromWindow(v: View) {
                surfaces -= view
                view.enableDynamicBackground = false
                view.enableSensorHighlight = false
            }
        })
        if (view.isAttachedToWindow) surfaces += view
    }

    fun setActive(value: Boolean) {
        active = value
        updateRendering()
        if (!value) frames.clear()
    }

    private fun updateRendering() {
        val visible = active && isAttachedToWindow && isShown && windowVisibility == VISIBLE
        surfaces.forEach { glass ->
            val render = visible && glass.isShown && glass.getGlobalVisibleRect(visibleRect)
            if (glass.enableDynamicBackground != render) glass.enableDynamicBackground = render
            if (glass.enableSensorHighlight != render) glass.enableSensorHighlight = render
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!active) return
        val grid = results ?: return
        getLocationOnScreen(origin)
        grid.getLocationOnScreen(location)
        val viewport = canvas.save()
        val gridX = (location[0] - origin[0]).toFloat()
        val gridY = (location[1] - origin[1]).toFloat()
        canvas.clipRect(gridX, gridY, gridX + grid.width, gridY + grid.height)
        for (index in 0 until grid.childCount) {
            val holder = grid.getChildViewHolder(grid.getChildAt(index)) as? VideoFeedAdapter.Holder ?: continue
            val binding = holder.binding
            val artwork = binding.videoBackdrop
            if (!artwork.isShown || artwork.width == 0 || artwork.height == 0) continue
            artwork.getLocationOnScreen(location)
            val item = canvas.save()
            canvas.translate((location[0] - origin[0]).toFloat(), (location[1] - origin[1]).toFloat())
            if (canvas.clipRect(0f, 0f, artwork.width.toFloat(), artwork.height.toFloat())) {
                artwork.draw(canvas)
                val texture = binding.videoPlayer.videoSurfaceView as? TextureView
                val shutter = binding.videoPlayer.findViewById<View>(androidx.media3.ui.R.id.exo_shutter)
                if (binding.videoPlayer.player != null && texture != null && shutter?.isShown != true) {
                    drawVideoFrame(canvas, texture, artwork)
                }
            }
            canvas.restoreToCount(item)
        }
        canvas.restoreToCount(viewport)
    }

    private fun drawVideoFrame(canvas: Canvas, texture: TextureView, artwork: View) {
        if (!texture.isAvailable || !texture.isShown || texture.width == 0 || texture.height == 0) return
        val scale = minOf(1f, 320f / maxOf(texture.width, texture.height))
        val width = (texture.width * scale).roundToInt().coerceAtLeast(1)
        val height = (texture.height * scale).roundToInt().coerceAtLeast(1)
        val frame = frames[texture]?.takeIf { it.bitmap.width == width && it.bitmap.height == height }
            ?: Frame(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)).also { frames[texture] = it }
        val now = SystemClock.uptimeMillis()
        if (frame.capturedAt == Long.MIN_VALUE || now - frame.capturedAt >= 33L) {
            try {
                texture.getBitmap(frame.bitmap)
            } catch (_: IllegalStateException) {
                return
            }
            frame.capturedAt = now
        }
        artwork.getLocationOnScreen(location)
        val artworkX = location[0]
        val artworkY = location[1]
        texture.getLocationOnScreen(location)
        val x = (location[0] - artworkX).toFloat()
        val y = (location[1] - artworkY).toFloat()
        destination.set(x, y, x + texture.width, y + texture.height)
        canvas.drawBitmap(frame.bitmap, null, destination, bitmapPaint)
    }

    fun forgetVideo(texture: View?) {
        if (texture is TextureView) frames.remove(texture)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observer = viewTreeObserver.also { it.addOnPreDrawListener(beforeDraw) }
    }

    override fun onDetachedFromWindow() {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(beforeDraw)
        observer = null
        surfaces.forEach {
            it.enableDynamicBackground = false
            it.enableSensorHighlight = false
        }
        frames.clear()
        super.onDetachedFromWindow()
    }
}
