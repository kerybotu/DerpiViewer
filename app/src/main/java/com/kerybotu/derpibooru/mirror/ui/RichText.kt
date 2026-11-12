package com.kerybotu.derpibooru.mirror.ui

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.*
import android.view.View
import android.widget.TextView
import com.kerybotu.derpibooru.mirror.AppSettings
import com.kerybotu.derpibooru.mirror.PaletteManager
import kotlinx.coroutines.*
import java.util.WeakHashMap

/** Shared asynchronous binding for descriptions and comments. Rebinding cancels stale work. */
object RichText {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = WeakHashMap<TextView, Job>()
    fun bind(view: TextView, raw: String) {
        jobs.remove(view)?.cancel()
        view.text = ""
        jobs[view] = scope.launch {
            val document = withContext(Dispatchers.Default) { RichTextParser.parse(raw) }
            val result = SpannableString(document.text)
            for (mark in document.marks) {
                if (mark.start == mark.end) continue
                val span: Any = when (mark.kind) {
                    "bold" -> StyleSpan(Typeface.BOLD)
                    "italic" -> StyleSpan(Typeface.ITALIC)
                    "underline" -> UnderlineSpan()
                    "strike" -> StrikethroughSpan()
                    "sup" -> SuperscriptSpan()
                    "sub" -> SubscriptSpan()
                    "code" -> TypefaceSpan("monospace")
                    "spoiler" -> object : ClickableSpan() {
                        private var revealed = false
                        override fun updateDrawState(ds: TextPaint) {
                            if (!revealed) { ds.bgColor = PaletteManager.colors(view.context).glassSecondaryText; ds.color = ds.bgColor }
                            ds.isUnderlineText = false
                        }
                        override fun onClick(widget: View) { revealed = !revealed; widget.invalidate() }
                    }
                    "tag", "link" -> object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            val context = widget.context
                            val target = mark.target ?: return
                            if (mark.kind == "tag") {
                                context.startActivity(Intent(context, SearchActivity::class.java).putExtra(SearchActivity.EXTRA_INITIAL_QUERY, target)); return
                            }
                            val safe = RichTextParser.safeUrl(target) ?: return
                            val uri = Uri.parse(if (safe.startsWith('/')) "https://${AppSettings.getTargetDomain(context)}$safe" else safe)
                            val internal = uri.host in setOf("derpibooru.org", "www.derpibooru.org", "trixiebooru.org", AppSettings.getTargetDomain(context))
                            val id = uri.path?.removePrefix("/images/")?.takeIf { internal && uri.path?.startsWith("/images/") == true }?.toIntOrNull()
                            if (id != null) context.startActivity(Intent(context, ImageDetailActivity::class.java).putExtra("image_id", id))
                            else runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
                        }
                    }
                    else -> continue
                }
                result.setSpan(span, mark.start, mark.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or (if (mark.kind == "spoiler") (1 shl Spanned.SPAN_PRIORITY_SHIFT) else 0))
            }
            view.text = result; view.movementMethod = LinkMovementMethod.getInstance()
            jobs.remove(view)
        }
    }
}
