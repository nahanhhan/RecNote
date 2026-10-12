package io.github.nahanhhan.lecture.core
import org.junit.Test
import kotlin.test.*
class MathMarkupTest {
    @Test fun multilineDisplayInlineAndEscapedCodeRemainDistinct() {
        val source = "文字 \$x^2$。\n$$\na+b\n$$\n\\(c\\)\n`\$skip$`\n```text\n\$also_skip$\n```\n价格 \\$5"
        val document = MathMarkup.extract(source)
        assertEquals(listOf("x^2", "\na+b\n", "c"), document.formulas.map { it.source })
        assertEquals(listOf(false, true, false), document.formulas.map { it.display })
        assertTrue(document.markdown.contains("`\$skip$`"))
        assertTrue(document.markdown.contains("\$also_skip$"))
        assertTrue(document.markdown.contains("\\$5"))
    }
}
