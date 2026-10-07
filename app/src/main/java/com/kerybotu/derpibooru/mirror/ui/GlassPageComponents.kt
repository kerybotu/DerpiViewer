package com.kerybotu.derpibooru.mirror.ui

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import com.example.liquidglass.LiquidGlassButton
import com.example.liquidglass.LiquidGlassDialogBuilder
import com.example.liquidglass.LiquidGlassListItem
import com.example.liquidglass.LiquidGlassToast
import com.kerybotu.derpibooru.mirror.PaletteManager
import com.example.liquidglass.LiquidGlassView

/** The library dispatches taps itself, bypassing Android's normal long-click detection. */
internal class GlassActionListItem(context: Context) : LiquidGlassListItem(context) {
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapUp(e: MotionEvent): Boolean =
            isClickable && e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat() && performClick()
        override fun onLongPress(e: MotionEvent) {
            if (isLongClickable) performLongClick()
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || (!isClickable && !isLongClickable)) return false
        gestures.onTouchEvent(event)
        return true
    }

    override fun onDetachedFromWindow() {
        // A recycled row must never receive a delayed long press for its previous item.
        val cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        gestures.onTouchEvent(cancel)
        cancel.recycle()
        super.onDetachedFromWindow()
    }
}

/** Shared glass dialogs and actions, using the same opaque backdrop and material as the page. */
internal class GlassPageDialogs(private val feed: GlassFeedLayout) {
    private val context get() = feed.context

    fun button(label: String, action: () -> Unit): LiquidGlassButton =
        feed.trackGlass(LiquidGlassButton(context), feed.refresh).apply {
            text = label
            setTextSize(14f)
            textView.setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { if (isEnabled) action() }
        }

    fun show(
        title: String,
        message: String? = null,
        extra: View? = null,
        positive: String? = null,
        onConfirm: () -> Unit = {}
    ): AlertDialog {
        lateinit var dialog: AlertDialog
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
            extra?.let { addView(it, LinearLayout.LayoutParams(-1, -2)) }
        }
        val actions = LinearLayout(context)
        actions.addView(button(if (positive == null) "关闭" else "取消") { dialog.dismiss() },
            LinearLayout.LayoutParams(0, dp(48), 1f))
        if (positive != null) actions.addView(button(positive) {
            dialog.dismiss()
            onConfirm()
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        content.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val builder = LiquidGlassDialogBuilder(context, animateShow = false, glassSetup = {
            feed.trackGlass(this, feed.refresh, 28f)
        }).apply {
            overLightTextColor = PaletteManager.colors(context).glassText
            overDarkTextColor = PaletteManager.colors(context).glassText
        }
        dialog = builder.setTitle(title).setMessage(message).setView(content).create()
        dialog.show()
        return dialog
    }

    fun prompt(title: String, initial: String = "", onConfirm: (String) -> Unit) {
        val input = EditText(context).apply {
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = null
            hint = "收藏夹名称"
            setText(initial)
            setSelection(text.length)
            setTextColor(PaletteManager.colors(context).glassText)
            setHintTextColor(PaletteManager.colors(context).glassSecondaryText)
            setPadding(dp(16), 0, dp(16), 0)
        }
        val surface = feed.trackGlass(LiquidGlassView(context), feed.refresh, 24f).apply {
            addView(input, FrameLayout.LayoutParams(-1, dp(56)))
        }
        val dialog = show(title, extra = surface, positive = "完成") { onConfirm(input.text.toString()) }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                dialog.dismiss()
                onConfirm(input.text.toString())
                true
            } else false
        }
    }

    fun choices(title: String, choices: List<Pair<String, () -> Unit>>) {
        lateinit var dialog: AlertDialog
        val items = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        choices.forEach { (label, action) ->
            items.addView(button(label) { dialog.dismiss(); action() },
                LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(8) })
        }
        dialog = show(title, extra = ScrollView(context).apply { addView(items) })
    }

    fun toast(message: String) {
        if (feed.isActive) LiquidGlassToast.makeText(context, message, LiquidGlassToast.LENGTH_SHORT)
            .setTextColor(PaletteManager.colors(context).glassText).show()
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
