package io.github.nahanhhan.lecture.core
import org.junit.Test
import kotlin.test.*
class CaptureClockTest {
    @Test fun pauseDoesNotAddAudioTimeAndClockResetDoesNotReusePreviousLesson() {
        val clock = CaptureClock()
        clock.record(1000, 16000); clock.record(2000, 32000)
        clock.record(5000, 32000); clock.record(6000, 48000)
        assertEquals(32000L, clock.samplesAt(5500, 0))
        assertEquals(48000L, clock.samplesAt(6100, 0))
        clock.clear(); assertEquals(7000L, clock.samplesAt(6100, 7000))
    }
}
