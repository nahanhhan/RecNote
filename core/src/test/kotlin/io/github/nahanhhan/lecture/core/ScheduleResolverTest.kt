package io.github.nahanhhan.lecture.core

import kotlin.test.*

class ScheduleResolverTest {
    private val m = 60_000L
    private val t0 = 1_800_000_000_000L
    private val default = AutoStartWindow.DEFAULT

    private fun occ(start: Long, lengthMin: Long = 50, title: String = "高等数学", allDay: Boolean = false, key: String = "k$start") =
        Occurrence("s", "u$start", key, title, start, start + lengthMin * m, allDay)

    @Test fun defaultWindowIsClosedAtBothEnds() {
        val list = listOf(occ(t0))
        assertNotNull(ScheduleResolver.resolveAutoStart(t0 - 3 * m, list, default, emptySet()))
        assertNull(ScheduleResolver.resolveAutoStart(t0 - 3 * m - 1, list, default, emptySet()))
        assertNotNull(ScheduleResolver.resolveAutoStart(t0 + 3 * m, list, default, emptySet()))
        assertNull(ScheduleResolver.resolveAutoStart(t0 + 3 * m + 1, list, default, emptySet()))
    }
    @Test fun relaxedWindowAcceptsLateAndEarlyEntries() {
        val list = listOf(occ(t0))
        val window = AutoStartWindow(-10, 5)
        assertNotNull(ScheduleResolver.resolveAutoStart(t0 + 10 * m, list, window, emptySet()))
        assertNotNull(ScheduleResolver.resolveAutoStart(t0 - 5 * m, list, window, emptySet()))
        assertNull(ScheduleResolver.resolveAutoStart(t0 + 10 * m + 1, list, window, emptySet()))
    }
    @Test fun occurrencesWithExistingLessonsAreNotAutoStartedAgain() {
        val list = listOf(occ(t0, key = "done"))
        assertNull(ScheduleResolver.resolveAutoStart(t0, list, default, setOf("done")))
        assertNotNull(ScheduleResolver.resolveAutoStart(t0, list, default, setOf("other")))
    }
    @Test fun allDayEventsNeverParticipate() {
        val list = listOf(occ(t0, allDay = true))
        assertNull(ScheduleResolver.resolveAutoStart(t0, list, default, emptySet()))
        assertNull(ScheduleResolver.resolveActive(t0, list, default))
    }
    @Test fun nearestStartWinsAndTiesPickTheEarlierStart() {
        val window = AutoStartWindow(-10, 5)
        val a = occ(t0, title = "A")
        val closer = occ(t0 + 8 * m, title = "B")
        assertEquals("B", ScheduleResolver.resolveAutoStart(t0 + 5 * m, listOf(a, closer), window, emptySet())?.title)
        val tie = occ(t0 + 10 * m, title = "B")
        assertEquals("A", ScheduleResolver.resolveAutoStart(t0 + 5 * m, listOf(tie, a), window, emptySet())?.title)
    }
    @Test fun activeCoversFromEarliestStartUntilTheEnd() {
        val list = listOf(occ(t0))
        assertNull(ScheduleResolver.resolveActive(t0 - 4 * m, list, default))
        assertNotNull(ScheduleResolver.resolveActive(t0 - 2 * m, list, default))
        assertNotNull(ScheduleResolver.resolveActive(t0 + 30 * m, list, default))
        assertNull(ScheduleResolver.resolveActive(t0 + 50 * m, list, default))
    }
    @Test fun windowNormalizationAndStorageFallback() {
        assertEquals(AutoStartWindow(-10, 5), AutoStartWindow.clamped(5, -10))
        assertEquals(AutoStartWindow(-10, 5), AutoStartWindow.clamped(-20, 9))
        assertEquals(AutoStartWindow(-3, 3), AutoStartWindow.fromStorage(null, null))
        assertEquals(AutoStartWindow(-3, 3), AutoStartWindow.fromStorage(-11, 3))
        assertEquals(AutoStartWindow(-3, 3), AutoStartWindow.fromStorage(4, 2))
        assertEquals(AutoStartWindow(-8, 1), AutoStartWindow.fromStorage(-8, 1))
    }
}
