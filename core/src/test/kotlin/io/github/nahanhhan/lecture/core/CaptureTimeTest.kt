package io.github.nahanhhan.lecture.core
import java.time.*
import kotlin.test.*
import org.junit.Test
class CaptureTimeTest {
    @Test fun publicExifOffsetAndSubsecondsMapToEpochAndInvalidTimeIsIgnored() {
        assertEquals(Instant.parse("2026-10-12T03:00:00.123Z").toEpochMilli(),
            CaptureTime.epoch("2026:10:12 11:00:00", "+08:00", "1234", ZoneOffset.UTC))
        assertEquals(Instant.parse("2026-10-12T11:00:00Z").toEpochMilli(),
            CaptureTime.epoch("2026:10:12 11:00:00", null, null, ZoneOffset.UTC))
        assertNull(CaptureTime.epoch("bad", null, null))
    }
}
