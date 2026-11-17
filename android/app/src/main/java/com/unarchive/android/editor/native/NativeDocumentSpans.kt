package com.unarchive.android.editor.native

import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Editable
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.ReplacementSpan
import android.text.style.CharacterStyle
import android.text.TextPaint
import com.unarchive.android.editor.session.DocumentProjection
import com.unarchive.android.editor.session.InlineMark

/** Only these owned spans are removed; IME, selection and accessibility spans remain untouched. */
object NativeDocumentSpans {
    private class OwnedStyle(style: Int) : StyleSpan(style)
    private class OwnedSize(size: Float) : RelativeSizeSpan(size)
    private class OwnedStrike : StrikethroughSpan()
    private class OwnedTypeface : TypefaceSpan("monospace")
    private class OwnedUnderline : UnderlineSpan()
    private class OwnedSecondary : CharacterStyle() {
        override fun updateDrawState(paint: TextPaint) { paint.alpha = (paint.alpha * 0.70f).toInt().coerceAtLeast(1) }
    }
    private class OwnedMargin(private val label: String, private val indent: Int, fontSizePx: Float) : LeadingMarginSpan {
        private val em = fontSizePx.coerceAtLeast(1f)
        private val labelWidth = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = em }.measureText(label)
        private val indentWidth = indent * em * 0.5f
        override fun getLeadingMargin(first: Boolean): Int = (labelWidth + em * 0.75f + indentWidth).toInt()
        override fun drawLeadingMargin(canvas: Canvas, paint: Paint, x: Int, dir: Int, top: Int,
            baseline: Int, bottom: Int, text: CharSequence, start: Int, end: Int, first: Boolean, layout: android.text.Layout?) {
            if (first) canvas.drawText(label, x.toFloat() + indentWidth, baseline.toFloat(), paint)
        }
    }
    private class OwnedQuote(fontSizePx: Float) : QuoteSpan(0xFF8A929C.toInt(),
        (fontSizePx * 0.10f).toInt().coerceAtLeast(2), (fontSizePx * 0.4f).toInt().coerceAtLeast(4))
    private fun isOwned(span: Any): Boolean = span is OwnedStyle || span is OwnedSize || span is OwnedStrike ||
        span is OwnedTypeface || span is OwnedUnderline || span is OwnedMargin || span is OwnedQuote || span is OwnedAtomic || span is OwnedSecondary

    /** Remove old metric atoms before replacing their text, not after DynamicLayout reflow. */
    internal fun clearOwnedInRange(editable: Editable, start: Int, end: Int) {
        editable.getSpans(start.coerceIn(0, editable.length), end.coerceIn(start.coerceIn(0, editable.length), editable.length), Any::class.java)
            .filter(::isOwned).forEach(editable::removeSpan)
    }
    internal class OwnedAtomic(val node: com.unarchive.android.editor.session.DocumentAtomicNode) : ReplacementSpan() {
        var horizontalOffset = 0f
        var availableWidth = 1000
        var bitmap: android.graphics.Bitmap? = null
        var imageFailed = false
        private var width = 0f
        private var lineHeight = 0
        private var measuredSize = -1f
        private var columnWidths = emptyList<Float>()
        init { contentDescription = node.text }
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            lineHeight = (paint.fontSpacing * 1.5f).toInt().coerceAtLeast(1)
            if (node.kind == "image") {
                width = availableWidth.toFloat()
                val image = bitmap
                val height = if (image != null) (image.height.toFloat() / image.width * width).toInt().coerceIn(lineHeight * 3, availableWidth * 3)
                    else (availableWidth * 0.56f).toInt().coerceAtLeast(lineHeight * 3)
                fm?.let { it.ascent = -lineHeight; it.top = it.ascent; it.descent = height - lineHeight; it.bottom = it.descent }
                return availableWidth
            }
            val rows = if (node.kind == "table") node.rows else listOf(listOf(node.text))
            if (measuredSize != paint.textSize) {
                measuredSize = paint.textSize
                columnWidths = (0 until (rows.maxOfOrNull { it.size } ?: 0)).map { column -> rows.maxOf { paint.measureText(it.getOrNull(column).orEmpty()) } + 32 }
                width = columnWidths.sum().coerceAtLeast(100f)
            }
            fm?.let { it.ascent = -lineHeight; it.top = it.ascent; it.descent = (rows.size - 1).coerceAtLeast(0) * lineHeight + 16; it.bottom = it.descent }
            return width.coerceAtMost(availableWidth.toFloat()).toInt()
        }
        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int,
            y: Int, bottom: Int, paint: Paint) {
            val oldColor = paint.color
            if (node.kind == "image") {
                val image = bitmap
                if (image != null) canvas.drawBitmap(image, null, android.graphics.RectF(x, top.toFloat(), x + availableWidth, bottom.toFloat()), paint)
                else {
                    paint.alpha = 24; canvas.drawRect(x, top.toFloat(), x + availableWidth, bottom.toFloat(), paint); paint.color = oldColor
                    canvas.drawText(if (imageFailed) "图片无法显示，点按查看" else node.text, x + 16, y.toFloat(), paint)
                }
                return
            }
            if (node.kind == "divider") { canvas.drawLine(x, (top + bottom) / 2f, x + width.coerceAtMost(availableWidth.toFloat()), (top + bottom) / 2f, paint); return }
            val rows = if (node.kind == "table") node.rows else listOf(listOf(if (node.kind == "image") "▧ ${node.text}" else node.text))
            val cellWidths = columnWidths
            canvas.save()
            canvas.clipRect(x, top.toFloat(), x + width.coerceAtMost(availableWidth.toFloat()), bottom.toFloat())
            val clip = canvas.clipBounds
            val firstVisible = ((clip.top - y) / lineHeight - 1).coerceAtLeast(0)
            val lastVisible = ((clip.bottom - y) / lineHeight + 1).coerceAtMost(rows.lastIndex)
            rows.forEachIndexed { rowIndex, row ->
                if (rowIndex < firstVisible || rowIndex > lastVisible) return@forEachIndexed
                var left = x - horizontalOffset
                row.forEachIndexed { column, value ->
                    canvas.drawText(value, left + 12, y + rowIndex * lineHeight.toFloat(), paint)
                    left += cellWidths.getOrElse(column) { 100f }
                }
                if (node.kind == "table") {
                    paint.alpha = 60
                    canvas.drawLine(x, y + rowIndex * lineHeight + 10f, x + width, y + rowIndex * lineHeight + 10f, paint)
                    paint.color = oldColor
                }
            }
            canvas.restore(); paint.color = oldColor
        }
    }

    fun apply(editable: Editable, projection: DocumentProjection, dirtyStart: Int = 0, dirtyEnd: Int = editable.length, fontSizePx: Float = 17f) {
        val touched = projection.nodes.filter { it.end >= dirtyStart && it.start <= dirtyEnd }
        val from = touched.minOfOrNull { it.start } ?: dirtyStart.coerceIn(0, editable.length)
        val to = touched.maxOfOrNull { it.end } ?: dirtyEnd.coerceIn(from, editable.length)
        editable.getSpans(from, to, Any::class.java).forEach {
            if (isOwned(it)) editable.removeSpan(it)
        }
        fun put(span: Any, start: Int, end: Int) {
            if (start >= 0 && end <= editable.length && end > start) editable.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        touched.forEach { block ->
            if (block.node.kind == "synthetic-source-summary") {
                put(OwnedSize(0.76f), block.start, block.end)
                put(OwnedSecondary(), block.start, block.end)
            }
            block.node.atomic?.let { put(OwnedAtomic(it), block.start, block.end) }
            block.node.inlineAtoms.forEach { atom -> put(OwnedAtomic(atom.node), block.start + atom.start, block.start + atom.end) }
            if (block.node.headingLevel > 0) {
                put(OwnedStyle(Typeface.BOLD), block.start, block.end)
                put(OwnedSize(when (block.node.headingLevel) { 1 -> 1.55f; 2 -> 1.3f; else -> 1.15f }), block.start, block.end)
            }
            if (block.node.kind == "code_fence" || block.node.kind == "code_block") put(OwnedTypeface(), block.start, block.end)
            block.node.lines.forEach { line ->
                val start = block.start + line.start
                val end = (block.start + line.end).coerceAtLeast(start + 1).coerceAtMost(editable.length)
                val label = when (line.listStyle) {
                    com.unarchive.android.editor.session.DocumentListStyle.UNORDERED -> "•"
                    com.unarchive.android.editor.session.DocumentListStyle.ORDERED -> "${line.ordinal}."
                    com.unarchive.android.editor.session.DocumentListStyle.TASK -> if (line.checked) "☑" else "☐"
                    null -> null
                }
                if (label != null) put(OwnedMargin(label, line.indent, fontSizePx), start, end)
                if (line.quoteDepth > 0) put(OwnedQuote(fontSizePx), start, end)
            }
            block.node.marks.forEach { mark ->
                val start = block.start + mark.start; val end = block.start + mark.end
                when (mark.kind) {
                    InlineMark.BOLD -> put(OwnedStyle(Typeface.BOLD), start, end)
                    InlineMark.ITALIC -> put(OwnedStyle(Typeface.ITALIC), start, end)
                    InlineMark.STRIKE -> put(OwnedStrike(), start, end)
                    InlineMark.CODE -> put(OwnedTypeface(), start, end)
                    InlineMark.LINK -> put(OwnedUnderline(), start, end)
                }
            }
        }
    }
}
