package io.github.nahanhhan.lecturerecording.export

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.pdf.PdfDocument
import android.text.*
import android.text.style.*
import io.github.nahanhhan.lecture.core.MathMarkup
import io.github.nahanhhan.lecturerecording.photos.PhotoImages
import kotlinx.coroutines.*
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.ext.gfm.tables.*
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.min

/** Native A4 pagination keeps text selectable and embeds local photos/KaTeX images. */
object PdfExporter {
    suspend fun generate(context: Context, markdown: String, title: String, photos: Map<String, File>, output: File): File {
        check(markdown.isNotBlank()) { "笔记为空，无法生成 PDF" }
        val math = MathMarkup.extract(markdown)
        check(math.formulas.size <= 1000) { "公式过多，请分开导出" }
        val formulaImages = mutableMapOf<String, FormulaImage>()
        val temporary = File(output.parentFile, ".pdf-${UUID.randomUUID()}.part")
        try {
            math.formulas.forEach { currentCoroutineContext().ensureActive(); formulaImages[it.id] = MathImages.render(context, it) }
            withContext(Dispatchers.IO) {
                val writer = Pages(context, title, photos, formulaImages)
                try {
                    val document = Parser.builder().extensions(listOf(TablesExtension.create())).build().parse(math.markdown)
                    writer.render(document)
                    writer.finish()
                    FileOutputStream(temporary).use { stream -> writer.document.writeTo(stream); stream.fd.sync() }
                    check(temporary.length() > 100) { "生成的 PDF 不完整，请重试" }
                    check(temporary.inputStream().use { val header = ByteArray(5); it.read(header) == 5 && String(header, Charsets.US_ASCII) == "%PDF-" }) { "PDF 文件校验失败" }
                    check(!output.exists() && temporary.renameTo(output)) { "PDF 保存失败，请检查本机空间后重试" }
                } finally { writer.close() }
            }
            return output
        } finally { temporary.delete(); formulaImages.values.forEach { it.bitmap.recycle() } }
    }

    private class Pages(val context: Context, val title: String, val photos: Map<String, File>, val formulas: Map<String, FormulaImage>) {
        val document = PdfDocument()
        private var page: PdfDocument.Page? = null
        private var number = 0
        private var y = 50f
        private val left = 42f
        private val right = 553f
        private val bottom = 785f
        private val retained = mutableListOf<Bitmap>()
        private val ink = Color.rgb(38, 60, 52)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(205, 215, 209); strokeWidth = 0.6f }
        private val canvas get() = requireNotNull(page).canvas
        private fun newPage() {
            finish()
            check(number < 512) { "笔记超过 512 页，请分开导出" }
            number++
            page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, number).create())
            y = 50f
        }
        fun finish() {
            page?.let {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; textSize = 8f }
                it.canvas.drawLine(left, 801f, right, 801f, line)
                it.canvas.drawText(TextUtils.ellipsize(title, TextPaint(paint), 430f, TextUtils.TruncateAt.END).toString(), left, 815f, paint)
                it.canvas.drawText(number.toString(), right - 15, 815f, paint)
                document.finishPage(it); page = null
            }
        }
        fun close() { finish(); document.close(); retained.forEach { it.recycle() } }
        private fun children(node: Node): List<Node> = buildList { var child = node.firstChild; while (child != null) { add(child); child = child.next } }
        private fun styled(node: Node, prefix: String = ""): CharSequence {
            val text = SpannableStringBuilder(prefix)
            fun visit(current: Node) {
                val start = text.length
                when (current) {
                    is Text -> text.append(current.literal)
                    is SoftLineBreak -> text.append(" ")
                    is HardLineBreak -> text.append("\n")
                    is Code -> { text.append(current.literal); text.setSpan(TypefaceSpan("monospace"), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                    is Image -> {
                        val formula = current.destination.removePrefix("pdfmath/").let { if (current.destination.startsWith("pdfmath/")) formulas[it] else null }
                        val bitmap = formula?.bitmap ?: photos[current.destination]?.let { PhotoImages.decode(it, 1400).also(retained::add) }
                        if (bitmap != null) {
                            val naturalW = formula?.width?.toFloat() ?: bitmap.width.toFloat()
                            val naturalH = formula?.height?.toFloat() ?: bitmap.height.toFloat()
                            val scale = min(1f, min((right - left - 12) / naturalW, 640f / naturalH))
                            val drawable = BitmapDrawable(context.resources, bitmap).apply {
                                setBounds(0, 0, (naturalW * scale).toInt().coerceAtLeast(1), (naturalH * scale).toInt().coerceAtLeast(1))
                            }
                            text.append("\uFFFC"); text.setSpan(ImageSpan(drawable, ImageSpan.ALIGN_BASELINE), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        } else {
                            check(current.destination !in photos && !current.destination.startsWith("pdfmath/")) { "笔记中的照片或公式无法读取" }
                            text.append("[图片："); children(current).forEach(::visit); text.append("]")
                        }
                    }
                    else -> {
                        children(current).forEach(::visit)
                        if (text.length > start) when (current) {
                            is StrongEmphasis -> text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            is Emphasis -> text.setSpan(StyleSpan(Typeface.ITALIC), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            is Link -> text.setSpan(ForegroundColorSpan(Color.rgb(64, 111, 97)), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                }
            }
            children(node).forEach(::visit)
            return text
        }
        private fun layout(text: CharSequence, width: Int, size: Float = 11f, bold: Boolean = false): StaticLayout {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ink; textSize = size; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(10))
                .setIncludePad(false).setLineSpacing(3f, 1.15f).setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY).build()
        }
        private fun endLine(layout: StaticLayout, start: Int, available: Float): Int {
            var end = start
            while (end < layout.lineCount && layout.getLineBottom(end) - layout.getLineTop(start) <= available) end++
            return end
        }
        private fun drawPart(layout: StaticLayout, start: Int, end: Int, x: Float, top: Float, width: Float) {
            val height = layout.getLineBottom(end - 1) - layout.getLineTop(start)
            canvas.save(); canvas.clipRect(x, top, x + width, top + height)
            canvas.translate(x, top - layout.getLineTop(start)); layout.draw(canvas); canvas.restore()
        }
        private fun paragraph(text: CharSequence, size: Float = 11f, bold: Boolean = false, indent: Int = 0) {
            val x = left + indent * 12
            val width = right - x
            val layout = layout(text, width.toInt(), size, bold)
            var start = 0
            while (start < layout.lineCount) {
                if (page == null) newPage()
                var end = endLine(layout, start, bottom - y)
                if (end == start) { newPage(); end = endLine(layout, start, bottom - y) }
                check(end > start) { "图片或段落尺寸超出单页，请缩小后重试" }
                drawPart(layout, start, end, x, y, width)
                y += layout.getLineBottom(end - 1) - layout.getLineTop(start)
                start = end
                if (start < layout.lineCount) newPage()
            }
            y += 9
        }
        private fun table(node: TableBlock) {
            val rows = children(node).flatMap(::children).filterIsInstance<TableRow>()
            val columns = rows.maxOfOrNull { children(it).size } ?: return
            if (columns == 0) return
            val cellWidth = (right - left) / columns
            rows.forEach { row ->
                val cells = children(row)
                val layouts = (0 until columns).map { index ->
                    layout(cells.getOrNull(index)?.let(::styled) ?: "", (cellWidth - 12).toInt(), 9.5f, (cells.getOrNull(index) as? TableCell)?.isHeader == true)
                }
                val starts = IntArray(columns)
                while (layouts.indices.any { starts[it] < layouts[it].lineCount }) {
                    if (page == null || bottom - y < 30) newPage()
                    var ends = layouts.indices.map { endLine(layouts[it], starts[it], bottom - y - 12) }
                    if (layouts.indices.any { starts[it] < layouts[it].lineCount && ends[it] == starts[it] }) {
                        newPage(); ends = layouts.indices.map { endLine(layouts[it], starts[it], bottom - y - 12) }
                    }
                    check(layouts.indices.all { starts[it] == layouts[it].lineCount || ends[it] > starts[it] }) { "表格单元格尺寸过大" }
                    val height = layouts.indices.maxOf { if (ends[it] == starts[it]) 0 else layouts[it].getLineBottom(ends[it] - 1) - layouts[it].getLineTop(starts[it]) } + 12
                    layouts.indices.forEach { index ->
                        val x = left + cellWidth * index
                        canvas.drawRect(x, y, x + cellWidth, y + height, Paint(line).apply { style = Paint.Style.STROKE })
                        if (ends[index] > starts[index]) drawPart(layouts[index], starts[index], ends[index], x + 6, y + 6, cellWidth - 12)
                        starts[index] = ends[index]
                    }
                    y += height
                }
            }
            y += 12
        }
        fun render(node: Node, indent: Int = 0, prefix: String = "") {
            when (node) {
                is Heading -> {
                    if (page != null && bottom - y < 70) newPage()
                    paragraph(styled(node), when (node.level) { 1 -> 22f; 2 -> 16f; else -> 13f }, true, indent)
                }
                is Paragraph -> paragraph(styled(node, prefix), indent = indent)
                is FencedCodeBlock -> paragraph(node.literal, 9.5f, indent = indent)
                is IndentedCodeBlock -> paragraph(node.literal, 9.5f, indent = indent)
                is TableBlock -> table(node)
                is BulletList -> children(node).forEach { render(it, indent + 1, "• ") }
                is OrderedList -> children(node).forEachIndexed { index, item -> render(item, indent + 1, "${(node.markerStartNumber ?: 1) + index}. ") }
                is ListItem -> children(node).forEachIndexed { index, child -> render(child, indent, if (index == 0) prefix else "") }
                is BlockQuote -> children(node).forEach { render(it, indent + 1, "│ ") }
                is ThematicBreak -> { if (page == null || bottom - y < 12) newPage(); canvas.drawLine(left, y, right, y, line); y += 12 }
                is HtmlBlock -> paragraph(node.literal, indent = indent)
                else -> children(node).forEach { render(it, indent, prefix) }
            }
        }
    }
}
