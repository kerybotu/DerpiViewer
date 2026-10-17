/*
 * Derived from QWEA0/Liquid-Glass-Android v2.0.11, commit
 * fc6bd34fa3290638c3594cc39386f549d1316ab5 (DisplacementMapGenerator.kt).
 * Copyright (c) 2025-2026 pandadog. MIT license: see NOTICE below.
 * Retains the upstream ABI and displacement functions. Maps are normalized
 * textures, sampled across the view by the existing renderer; their storage
 * need not grow with the screen resolution. Each caller owns its bitmaps.
 */
package com.example.liquidglass

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.*

class DisplacementMapGenerator(width: Int, height: Int) {
    private val scale = min(1f, 256f / max(width, height).coerceAtLeast(1))
    private val width = (width * scale).roundToInt().coerceAtLeast(1)
    private val height = (height * scale).roundToInt().coerceAtLeast(1)

    fun generate(mode: DisplacementMode = DisplacementMode.STANDARD): Bitmap {
        val pixels = synchronized(pixelCache) {
            val key = "$width:$height:$mode"
            pixelCache.get(key) ?: generatePixels(mode).also { pixelCache.put(key, it) }
        }
        // A view recycles its maps, so cache immutable pixels, never Bitmap.
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun generatePixels(mode: DisplacementMode): IntArray {
        // Two streaming passes replace the full-size FloatArray matrix.
        // Use the same normalization as upstream, without retaining raw dx/dy.
        var maxScale = 1f
        for (y in 0 until height) for (x in 0 until width) {
            val p = fragment(mode, x, y)
            maxScale = max(maxScale, max(abs(px(p) * width - x), abs(py(p) * height - y)))
        }
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = fragment(mode, x, y)
                val edge = min(1f, min(min(x, y), min(width - x - 1, height - y - 1)) / 2f)
                val r = ((px(p) * width - x) * edge / maxScale + 0.5f).coerceIn(0f, 1f)
                val g = ((py(p) * height - y) * edge / maxScale + 0.5f).coerceIn(0f, 1f)
                pixels[y * width + x] = Color.argb(255, (r * 255).toInt(), (g * 255).toInt(), (g * 255).toInt())
            }
        }
        return pixels
    }

    // Pack two floats in a primitive long instead of allocating two Vec2
    // objects per pixel while many feed cards are being attached.
    private fun pack(x: Float, y: Float) = (x.toRawBits().toLong() shl 32) or (y.toRawBits().toLong() and 0xffffffffL)
    private fun px(p: Long) = Float.fromBits((p shr 32).toInt())
    private fun py(p: Long) = Float.fromBits(p.toInt())

    private fun fragment(mode: DisplacementMode, x: Int, y: Int): Long {
        val ix = x.toFloat() / width - 0.5f
        val iy = y.toFloat() / height - 0.5f
        return when (mode) {
            DisplacementMode.STANDARD -> {
                val displacement = smoothStep(0.8f, 0f, roundedRectSDF(ix, iy, 0.3f, 0.2f, 0.6f) - 0.15f)
                val scaled = smoothStep(0f, 1f, displacement)
                pack(ix * scaled + 0.5f, iy * scaled + 0.5f)
            }
            DisplacementMode.POLAR -> {
                val radius = sqrt(ix * ix + iy * iy)
                val angle = atan2(iy, ix)
                val displacement = smoothStep(0.7f, 0f, roundedRectSDF(ix, iy, 0.35f, 0.25f, 0.5f) - 0.1f)
                val newRadius = radius * (1f - displacement * 0.3f)
                pack(newRadius * cos(angle) + 0.5f, newRadius * sin(angle) + 0.5f)
            }
            DisplacementMode.PROMINENT -> {
                val displacement = smoothStep(0.9f, 0f, roundedRectSDF(ix, iy, 0.25f, 0.15f, 0.7f) - 0.2f)
                val pushFactor = 1f + displacement.pow(1.5f) * 0.2f
                pack(ix * pushFactor + 0.5f, iy * pushFactor + 0.5f)
            }
        }
    }

    private fun smoothStep(a: Float, b: Float, t: Float): Float {
        val clamped = ((t - a) / (b - a)).coerceIn(0f, 1f)
        return clamped * clamped * (3f - 2f * clamped)
    }

    private fun roundedRectSDF(x: Float, y: Float, width: Float, height: Float, radius: Float): Float {
        val qx = abs(x) - width + radius
        val qy = abs(y) - height + radius
        val len = sqrt(max(qx, 0f).pow(2) + max(qy, 0f).pow(2))
        return min(max(qx, qy), 0f) + len - radius
    }

    companion object {
        private val pixelCache = object : android.util.LruCache<String, IntArray>(2 * 1024 * 1024) {
            override fun sizeOf(key: String, value: IntArray) = value.size * 4
        }

        fun generateStandardMaps(width: Int = 270, height: Int = 69): Map<DisplacementMode, Bitmap> =
            DisplacementMapGenerator(width, height).let { generator ->
                DisplacementMode.entries.associateWith { generator.generate(it) }
            }
    }
}

/* NOTICE — MIT License
 * Copyright (c) 2025-2026 pandadog
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
