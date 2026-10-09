package io.github.nahanhhan.lecture.core

import kotlin.test.*

class SessionNamingTest {
    @Test fun firstSessionUsesTheScheduleTitleThenNumbersFromOne() {
        assertEquals("高等数学", SessionNaming.titleFor("高等数学", emptyList()))
        assertEquals("高等数学-1", SessionNaming.titleFor("高等数学", listOf("高等数学")))
        assertEquals("高等数学-2", SessionNaming.titleFor("高等数学", listOf("高等数学", "高等数学-1")))
    }
    @Test fun deletingAnEarlierSessionNeverReusesAnExistingTitle() {
        val title = SessionNaming.titleFor("高等数学", listOf("高等数学", "高等数学-2"))
        assertEquals("高等数学-3", title)
        assertNotEquals("高等数学-2", title)
    }
    @Test fun renamedLessonsAreIgnoredForNumberingButStillAvoided() {
        assertEquals("高等数学-1", SessionNaming.titleFor("高等数学", listOf("我改了名")))
        assertEquals("高等数学-2", SessionNaming.titleFor("高等数学", listOf("高等数学-1", "高等数学-x")))
    }
    @Test fun titlesWithRegexCharactersAreMatchedLiterally() {
        assertEquals("C++(上)-2", SessionNaming.titleFor("C++(上)", listOf("C++(上)", "C++(上)-1")))
        assertEquals("C++(上)-1", SessionNaming.titleFor("C++(上)", listOf("C++(上)", "CCC(上)-5")))
    }
}
