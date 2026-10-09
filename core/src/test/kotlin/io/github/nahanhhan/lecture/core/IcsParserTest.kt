package io.github.nahanhhan.lecture.core

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.*

class IcsParserTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val farFuture = ms("UTC", 2030, 1, 1, 0)

    private fun ms(zone: String, y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneId.of(zone)).toInstant().toEpochMilli()
    private fun ics(vararg lines: String): String =
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0") + lines + "END:VCALENDAR").joinToString("\r\n")
    private fun event(vararg lines: String): String = ics("BEGIN:VEVENT", *lines, "END:VEVENT")
    private fun parse(text: String, zone: ZoneId = shanghai) = IcsParser.parse(text, zone)
    private fun all(calendar: IcsCalendar) = calendar.occurrences("s", 0L, farFuture)

    // 1.1 行展开与转义
    @Test fun foldedLinesAndEscapesAreRestored() {
        val calendar = parse(event("UID:a", "DTSTART;TZID=Asia/Shanghai:20260302T080000", "SUMMARY:高等", " 数学\\, A\\; B\\nC"))
        assertEquals("高等数学, A; B\nC", all(calendar).single().title)
    }
    @Test fun quotedParameterContainingColonDoesNotSplitTheLine() {
        val property = IcsLines.parse("DTSTART;TZID=\"A:B\":20260302T080000")
        assertNotNull(property)
        assertEquals("DTSTART", property.name)
        assertEquals("A:B", property.params["TZID"])
        assertEquals("20260302T080000", property.value)
    }
    @Test fun notIcsTextAndCalendarsWithoutEventsAreRejected() {
        assertFailsWith<IcsParseException> { parse("hello") }
        assertFailsWith<IcsParseException> { parse(ics()) }
        assertFailsWith<IcsParseException> { parse(event("SUMMARY:没有开始时间")) }
    }

    // 1.2 时间解析
    @Test fun tzidTimeIsIndependentOfDeviceZone() {
        val text = event("UID:a", "DTSTART;TZID=Asia/Shanghai:20260302T080000", "SUMMARY:课")
        val expected = ms("Asia/Shanghai", 2026, 3, 2, 8)
        assertEquals(expected, all(parse(text, ZoneId.of("UTC"))).single().startMs)
        assertEquals(expected, all(parse(text, ZoneId.of("America/Los_Angeles"))).single().startMs)
    }
    @Test fun utcAndFloatingTimes() {
        val utc = event("UID:a", "DTSTART:20260302T000000Z", "SUMMARY:课")
        assertEquals(ms("UTC", 2026, 3, 2, 0), all(parse(utc, ZoneId.of("America/Los_Angeles"))).single().startMs)
        val floating = event("UID:a", "DTSTART:20260302T080000", "SUMMARY:课")
        assertEquals(ms("America/Los_Angeles", 2026, 3, 2, 8), all(parse(floating, ZoneId.of("America/Los_Angeles"))).single().startMs)
    }
    @Test fun unknownTzidFallsBackToDeviceAndWindowsNamesAreMapped() {
        val unknown = event("UID:a", "DTSTART;TZID=Mars/Phobos:20260302T080000", "SUMMARY:课")
        assertEquals(ms("Asia/Shanghai", 2026, 3, 2, 8), all(parse(unknown, shanghai)).single().startMs)
        val windows = event("UID:a", "DTSTART;TZID=China Standard Time:20260302T080000", "SUMMARY:课")
        assertEquals(ms("Asia/Shanghai", 2026, 3, 2, 8), all(parse(windows, ZoneId.of("UTC"))).single().startMs)
    }
    @Test fun allDayEventsAreFlaggedAndSpanOneDay() {
        val occurrence = all(parse(event("UID:a", "DTSTART;VALUE=DATE:20260302", "SUMMARY:放假"))).single()
        assertTrue(occurrence.allDay)
        assertEquals(IcsEvent.DAY_MS, occurrence.endMs - occurrence.startMs)
    }
    @Test fun durationDefinesTheEnd() {
        val occurrence = all(parse(event("UID:a", "DTSTART;TZID=Asia/Shanghai:20260302T080000", "DURATION:PT1H40M", "SUMMARY:课"))).single()
        assertEquals(100L * 60 * 1000, occurrence.endMs - occurrence.startMs)
    }
    @Test fun missingSummaryGetsDefaultTitleAndNestedAlarmIsIgnored() {
        val noSummary = all(parse(event("UID:a", "DTSTART;TZID=Asia/Shanghai:20260302T080000"))).single()
        assertEquals(IcsEvent.DEFAULT_TITLE, noSummary.title)
        val withAlarm = all(parse(event("UID:a", "DTSTART;TZID=Asia/Shanghai:20260302T080000", "SUMMARY:真课",
            "BEGIN:VALARM", "ACTION:DISPLAY", "SUMMARY:提醒", "END:VALARM"))).single()
        assertEquals("真课", withAlarm.title)
    }

    // 1.3 重复规则
    private val weeklyBase = arrayOf("UID:u1", "DTSTART;TZID=Asia/Shanghai:20260302T080000", "DTEND;TZID=Asia/Shanghai:20260302T085000", "SUMMARY:高数")
    @Test fun weeklyCountWithExdateSkipsTheExcludedWeek() {
        val calendar = parse(event(*weeklyBase, "RRULE:FREQ=WEEKLY;COUNT=4", "EXDATE;TZID=Asia/Shanghai:20260309T080000"))
        assertEquals(listOf(ms("Asia/Shanghai", 2026, 3, 2, 8), ms("Asia/Shanghai", 2026, 3, 16, 8), ms("Asia/Shanghai", 2026, 3, 23, 8)),
            all(calendar).map { it.startMs })
    }
    @Test fun expansionOnlyReturnsOccurrencesInTheQueriedRange() {
        val calendar = parse(event(*weeklyBase, "RRULE:FREQ=WEEKLY"))
        val found = calendar.occurrences("s", ms("Asia/Shanghai", 2026, 3, 10, 0), ms("Asia/Shanghai", 2026, 3, 20, 0))
        assertEquals(listOf(ms("Asia/Shanghai", 2026, 3, 16, 8)), found.map { it.startMs })
    }
    @Test fun byDayWithUntilIsInclusiveAtTheBoundary() {
        val expected = listOf(ms("Asia/Shanghai", 2026, 3, 2, 8), ms("Asia/Shanghai", 2026, 3, 4, 8), ms("Asia/Shanghai", 2026, 3, 9, 8))
        val utcUntil = parse(event(*weeklyBase, "RRULE:FREQ=WEEKLY;BYDAY=MO,WE;UNTIL=20260309T000000Z"))
        assertEquals(expected, all(utcUntil).map { it.startMs })
        val dateUntil = parse(event(*weeklyBase, "RRULE:FREQ=WEEKLY;BYDAY=MO,WE;UNTIL=20260309"))
        assertEquals(expected, all(dateUntil).map { it.startMs })
    }
    @Test fun recurrenceIdOverrideMovesOneOccurrenceAndKeepsBaseDuration() {
        val text = ics(
            "BEGIN:VEVENT", *weeklyBase, "RRULE:FREQ=WEEKLY;COUNT=3", "END:VEVENT",
            "BEGIN:VEVENT", "UID:u1", "RECURRENCE-ID;TZID=Asia/Shanghai:20260309T080000",
            "DTSTART;TZID=Asia/Shanghai:20260310T100000", "SUMMARY:高数（调课）", "END:VEVENT")
        val found = all(parse(text))
        assertEquals(listOf(ms("Asia/Shanghai", 2026, 3, 2, 8), ms("Asia/Shanghai", 2026, 3, 10, 10), ms("Asia/Shanghai", 2026, 3, 16, 8)),
            found.map { it.startMs })
        val moved = found[1]
        assertEquals("高数（调课）", moved.title)
        assertEquals(50L * 60 * 1000, moved.endMs - moved.startMs)
    }
    @Test fun cancelledOverrideRemovesThatOccurrenceAndCancelledEventsNeverAppear() {
        val text = ics(
            "BEGIN:VEVENT", *weeklyBase, "RRULE:FREQ=WEEKLY;COUNT=3", "END:VEVENT",
            "BEGIN:VEVENT", "UID:u1", "RECURRENCE-ID;TZID=Asia/Shanghai:20260309T080000",
            "DTSTART;TZID=Asia/Shanghai:20260309T080000", "STATUS:CANCELLED", "END:VEVENT",
            "BEGIN:VEVENT", "UID:u2", "DTSTART;TZID=Asia/Shanghai:20260304T080000", "SUMMARY:取消", "STATUS:CANCELLED", "END:VEVENT")
        assertEquals(listOf(ms("Asia/Shanghai", 2026, 3, 2, 8), ms("Asia/Shanghai", 2026, 3, 16, 8)), all(parse(text)).map { it.startMs })
    }
    @Test fun weeklyRuleKeepsWallClockAcrossDaylightSavingChange() {
        val calendar = parse(event("UID:a", "DTSTART;TZID=America/New_York:20260302T090000", "RRULE:FREQ=WEEKLY;COUNT=2", "SUMMARY:课"))
        val starts = all(calendar).map { it.startMs }
        assertEquals(listOf(ms("America/New_York", 2026, 3, 2, 9), ms("America/New_York", 2026, 3, 9, 9)), starts)
        assertEquals(7L * 24 * 3600 * 1000 - 3600 * 1000, starts[1] - starts[0])
    }
    @Test fun monthlyRuleSkipsMonthsWithoutTheDay() {
        val calendar = parse(event("UID:a", "DTSTART;TZID=Asia/Shanghai:20260131T080000", "RRULE:FREQ=MONTHLY;COUNT=3", "SUMMARY:课"))
        assertEquals(listOf(ms("Asia/Shanghai", 2026, 1, 31, 8), ms("Asia/Shanghai", 2026, 3, 31, 8), ms("Asia/Shanghai", 2026, 5, 31, 8)),
            all(calendar).map { it.startMs })
    }
    @Test fun unsupportedRuleKeepsFirstOccurrenceAndRecordsWarning() {
        val calendar = parse(event(*weeklyBase, "RRULE:FREQ=MONTHLY;BYSETPOS=1;BYDAY=MO"))
        assertEquals(1, all(calendar).size)
        assertTrue(calendar.warnings.isNotEmpty())
    }
    @Test fun openEndedDailyRuleFarInThePastStillQueriesQuickly() {
        val calendar = parse(event("UID:a", "DTSTART;TZID=Asia/Shanghai:20000101T080000", "RRULE:FREQ=DAILY", "SUMMARY:课"))
        val found = calendar.occurrences("s", ms("Asia/Shanghai", 2026, 3, 1, 0), ms("Asia/Shanghai", 2026, 3, 3, 23, 59))
        assertEquals(3, found.size)
    }

    // 1.4 稳定 key
    @Test fun occurrenceKeyIsStableAcrossRefreshAndOverrideUsesOriginalTime() {
        val base = arrayOf(*weeklyBase, "RRULE:FREQ=WEEKLY;COUNT=3")
        val before = all(parse(event(*base)))
        val after = all(parse(event(*base.map { if (it.startsWith("SUMMARY")) "SUMMARY:高等数学" else it }.toTypedArray())))
        assertEquals(before.map { it.key }, after.map { it.key })
        val overridden = ics(
            "BEGIN:VEVENT", *base, "END:VEVENT",
            "BEGIN:VEVENT", "UID:u1", "RECURRENCE-ID;TZID=Asia/Shanghai:20260309T080000",
            "DTSTART;TZID=Asia/Shanghai:20260310T100000", "END:VEVENT")
        val moved = all(parse(overridden)).first { it.startMs == ms("Asia/Shanghai", 2026, 3, 10, 10) }
        assertEquals(IcsCalendar.keyOf("s", "u1", ms("Asia/Shanghai", 2026, 3, 9, 8)), moved.key)
        assertEquals(before[1].key, moved.key)
    }
}
