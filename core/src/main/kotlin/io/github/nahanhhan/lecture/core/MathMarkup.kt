package io.github.nahanhhan.lecture.core

data class Formula(val id: String, val source: String, val display: Boolean)
data class MathDocument(val markdown: String, val formulas: List<Formula>)

/** Extract supported math delimiters while leaving fenced/inline code untouched. */
object MathMarkup {
    fun extract(markdown: String): MathDocument {
        val formulas = mutableListOf<Formula>()
        val output = markdown
        // Preserve code ranges before examining math (including multiline display equations).
        val protected = BooleanArray(output.length)
        var fence: String? = null
        var offset = 0
        output.splitToSequence('\n').forEach { line ->
            val marker = listOf("```", "~~~").firstOrNull { line.trimStart().startsWith(it) }
            val inCode = fence != null || marker != null
            if (marker != null) { if (fence == null) fence = marker else if (fence == marker) fence = null }
            if (inCode) for (i in offset until minOf(output.length, offset + line.length + 1)) protected[i] = true
            offset += line.length + 1
        }
        var i = 0
        val result = StringBuilder()
        while (i < output.length) {
            if (protected[i]) { result.append(output[i++]); continue }
            if (output[i] == '`') {
                var count = 1
                while (i + count < output.length && output[i + count] == '`') count++
                val end = output.indexOf("`".repeat(count), i + count)
                if (end >= 0) { result.append(output.substring(i, end + count)); i = end + count; continue }
            }
            val opening = when {
                output.startsWith("$$", i) -> "$$"
                output.startsWith("\\[", i) -> "\\["
                output.startsWith("\\(", i) -> "\\("
                output[i] == '$' && i + 1 < output.length && !output[i + 1].isWhitespace() -> "$"
                else -> null
            }
            if (opening == null) {
                if (output[i] == '\\' && i + 1 < output.length) { result.append(output.substring(i, i + 2)); i += 2 }
                else result.append(output[i++])
                continue
            }
            val closing = when (opening) { "\\[" -> "\\]"; "\\(" -> "\\)"; else -> opening }
            var end = output.indexOf(closing, i + opening.length)
            while (end > 0 && output[end - 1] == '\\') end = output.indexOf(closing, end + closing.length)
            if (end < 0 || (i until end + closing.length).any { protected[it] } ||
                (opening == "$" && (output[end - 1].isWhitespace() || output.substring(i + 1, end).contains('\n')))) {
                result.append(output[i++]); continue
            }
            val source = output.substring(i + opening.length, end)
            if (source.isBlank()) { result.append(output.substring(i, end + closing.length)); i = end + closing.length; continue }
            val id = "formula_${formulas.size}"
            val display = opening == "$$" || opening == "\\["
            formulas += Formula(id, source, display)
            if (display) result.append("\n\n")
            result.append("![公式](pdfmath/$id)")
            if (display) result.append("\n\n")
            i = end + closing.length
        }
        return MathDocument(result.toString(), formulas)
    }
}
