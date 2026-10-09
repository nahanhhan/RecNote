package io.github.nahanhhan.lecture.core

import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.*

class ScheduleStorageTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val from = ZonedDateTime.of(2026, 3, 1, 0, 0, 0, 0, shanghai).toInstant().toEpochMilli()
    private val to = ZonedDateTime.of(2026, 3, 31, 0, 0, 0, 0, shanghai).toInstant().toEpochMilli()

    private fun ics(uid: String, title: String, day: Int, hour: Int = 8) = listOf(
        "BEGIN:VCALENDAR", "VERSION:2.0", "BEGIN:VEVENT", "UID:$uid", "SUMMARY:$title",
        "DTSTART;TZID=Asia/Shanghai:202603%02dT%02d0000".format(day, hour), "DURATION:PT50M", "END:VEVENT", "END:VCALENDAR"
    ).joinToString("\r\n")

    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("schedule-test").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    @Test fun webcalIsRewrittenToHttpsAndOtherSchemesAreRejected() {
        assertEquals("https://example.edu/class.ics", ScheduleUrl.normalize("webcal://example.edu/class.ics"))
        assertEquals("https://example.edu/class.ics", ScheduleUrl.normalize("  WEBCALS://example.edu/class.ics "))
        assertEquals("https://example.edu/a.ics?token=x", ScheduleUrl.normalize("https://example.edu/a.ics?token=x"))
        assertEquals("仅支持 HTTPS 订阅链接", assertFailsWith<IllegalArgumentException> { ScheduleUrl.normalize("http://example.edu/class.ics") }.message)
        assertFailsWith<IllegalArgumentException> { ScheduleUrl.normalize("ftp://example.edu/class.ics") }
        assertFailsWith<IllegalArgumentException> { ScheduleUrl.normalize("   ") }
        assertFailsWith<IllegalArgumentException> { ScheduleUrl.normalize("https://") }
    }
    @Test fun hostHidesPathAndQuery() {
        assertEquals("example.edu", ScheduleUrl.hostOf("https://example.edu/private/a.ics?token=secret"))
        assertNull(ScheduleUrl.hostOf("not a url"))
    }

    @Test fun commitReplacesOnlyAfterSuccessfulParse() = withDirectory { directory ->
        val target = File(directory, "schedules/a.ics")
        IcsFiles.commit(target, ics("u1", "旧课", 2), shanghai)
        val old = target.readText()
        assertFailsWith<IcsParseException> { IcsFiles.commit(target, "这不是 ICS", shanghai) }
        assertFailsWith<IcsParseException> { IcsFiles.commit(target, "BEGIN:VCALENDAR\r\nEND:VCALENDAR", shanghai) }
        assertEquals(old, target.readText())
        IcsFiles.commit(target, ics("u1", "新课", 3), shanghai)
        assertTrue(target.readText().contains("新课"))
        assertEquals(listOf("a.ics"), target.parentFile.list()!!.toList())
    }

    @Test fun calendarsMergeSourcesAndDroppedSourcesDisappear() = withDirectory { directory ->
        val a = File(directory, "a.ics").apply { writeText(ics("a", "高等数学", 9, 10)) }
        val b = File(directory, "b.ics").apply { writeText(ics("b", "大学物理", 9, 8)) }
        val calendars = ScheduleCalendars { shanghai }
        val both = calendars.occurrences(listOf("a" to a, "b" to b), from, to)
        assertEquals(listOf("大学物理", "高等数学"), both.map { it.title })
        assertEquals(listOf("高等数学"), calendars.occurrences(listOf("a" to a), from, to).map { it.title })
    }
    @Test fun calendarsReloadWhenTheFileChangesAndIgnoreBrokenOrMissingFiles() = withDirectory { directory ->
        val a = File(directory, "a.ics").apply { writeText(ics("a", "高等数学", 9)) }
        val calendars = ScheduleCalendars { shanghai }
        assertEquals("高等数学", calendars.occurrences(listOf("a" to a), from, to).single().title)
        a.writeText(ics("a", "线性代数与几何", 9))
        assertEquals("线性代数与几何", calendars.occurrences(listOf("a" to a), from, to).single().title)
        a.writeText("损坏的内容")
        assertTrue(calendars.occurrences(listOf("a" to a), from, to).isEmpty())
        assertTrue(calendars.occurrences(listOf("missing" to File(directory, "missing.ics")), from, to).isEmpty())
    }
}
