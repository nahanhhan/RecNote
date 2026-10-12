package io.github.nahanhhan.lecture.core
import org.junit.Test
import kotlin.test.*
class MathMarkupTest {
    @Test fun multilineDisplayInlineAndEscapedCodeRemainDistinct() {
        val dollar = '$'
        val source = "文字 ${dollar}x^2${dollar}。\n${dollar}${dollar}\na+b\n${dollar}${dollar}\n\\(c\\)\n`${dollar}skip${dollar}`\n```text\n${dollar}also_skip${dollar}\n```\n价格 \\${dollar}5"
        val document = MathMarkup.extract(source)
        assertEquals(listOf("x^2", "\na+b\n", "c"), document.formulas.map { it.source })
        assertEquals(listOf(false, true, false), document.formulas.map { it.display })
        assertTrue(document.markdown.contains("`${dollar}skip${dollar}`"))
        assertTrue(document.markdown.contains("${dollar}also_skip${dollar}"))
        assertTrue(document.markdown.contains("\\${dollar}5"))
    }
}
