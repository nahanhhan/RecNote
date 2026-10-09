package io.github.nahanhhan.lecture.core

import kotlin.test.*

class StopTriggerRegistryTest {
    private val m = 60_000L
    private val t0 = 1_800_000_000_000L
    private val occurrence = Occurrence("s", "u", "k", "高等数学", t0, t0 + 50 * m, false)
    private val inClass = SessionContext("lesson", occurrence, t0)

    private class FakeTrigger(override val id: String, override val arming: ArmingPolicy) : StopTrigger {
        var starts = 0
        var stops = 0
        var stopRequest: ((StopReason) -> Unit)? = null
        override fun start(context: SessionContext, requestStop: (StopReason) -> Unit) { starts++; stopRequest = requestStop }
        override fun stop() { stops++ }
    }

    private val reasons = mutableListOf<StopReason>()
    private val registry = StopTriggerRegistry { reasons.add(it) }

    @Test fun manualOnlyRegistryHasNothingToSchedule() {
        registry.register(ManualStopTrigger())
        assertFalse(registry.hasAutomatic)
        registry.beginSession(inClass)
        registry.tick(t0 + 100 * m)
        assertTrue(registry.requestStop(StopReason.MANUAL))
        assertEquals(listOf(StopReason.MANUAL), reasons)
    }
    @Test fun afterScheduleEndTriggerIsNotStartedDuringClassAndStartsOnceAtTheEnd() {
        val trigger = FakeTrigger("motion", ArmingPolicy.AfterScheduleEnd)
        registry.register(trigger)
        assertTrue(registry.hasAutomatic)
        registry.beginSession(inClass)
        registry.tick(t0 + 10 * m)
        registry.tick(t0 + 50 * m - 1)
        assertEquals(0, trigger.starts)
        registry.tick(t0 + 50 * m)
        registry.tick(t0 + 51 * m)
        assertEquals(1, trigger.starts)
    }
    @Test fun afterScheduleEndTriggerNeverArmsWithoutASchedule() {
        val trigger = FakeTrigger("keyword", ArmingPolicy.AfterScheduleEnd)
        registry.register(trigger)
        registry.beginSession(SessionContext("lesson", null, t0))
        registry.tick(t0 + 1000 * m)
        assertEquals(0, trigger.starts)
    }
    @Test fun customAndImmediatePoliciesAreHonoured() {
        val now = FakeTrigger("now", ArmingPolicy.Immediately)
        val custom = FakeTrigger("custom", ArmingPolicy.Custom { _, at -> at >= t0 + 5 * m })
        registry.register(now); registry.register(custom)
        registry.beginSession(SessionContext("lesson", null, t0))
        registry.tick(t0)
        assertEquals(1, now.starts)
        assertEquals(0, custom.starts)
        registry.tick(t0 + 5 * m)
        assertEquals(1, custom.starts)
    }
    @Test fun switchToManualStopsRunningTriggersAndPreventsLaterStarts() {
        val early = FakeTrigger("early", ArmingPolicy.Immediately)
        val late = FakeTrigger("late", ArmingPolicy.AfterScheduleEnd)
        registry.register(early); registry.register(late)
        registry.beginSession(inClass)
        registry.tick(t0)
        registry.switchToManual()
        assertEquals(1, early.stops)
        registry.tick(t0 + 100 * m)
        assertEquals(0, late.starts)
        assertEquals(1, early.starts)
        assertTrue(registry.isManualOnly)
        assertFalse(registry.requestStop(StopReason.MOTION))
        assertTrue(registry.requestStop(StopReason.MANUAL))
        assertEquals(listOf(StopReason.MANUAL), reasons)
    }
    @Test fun nextSessionIsUnaffectedByAPreviousSwitchToManual() {
        val trigger = FakeTrigger("late", ArmingPolicy.AfterScheduleEnd)
        registry.register(trigger)
        registry.beginSession(inClass)
        registry.switchToManual()
        registry.endSession()
        registry.beginSession(inClass)
        assertFalse(registry.isManualOnly)
        registry.tick(t0 + 60 * m)
        assertEquals(1, trigger.starts)
    }
    @Test fun repeatedStopRequestsAreIdempotent() {
        val trigger = FakeTrigger("late", ArmingPolicy.AfterScheduleEnd)
        registry.register(trigger)
        registry.beginSession(inClass)
        registry.tick(t0 + 60 * m)
        trigger.stopRequest!!.invoke(StopReason.SCHEDULE_END)
        assertFalse(registry.requestStop(StopReason.MANUAL))
        assertFalse(registry.requestStop(StopReason.MANUAL))
        assertEquals(listOf(StopReason.SCHEDULE_END), reasons)
        assertEquals(1, trigger.stops)
    }
    @Test fun endSessionStopsRunningTriggers() {
        val trigger = FakeTrigger("now", ArmingPolicy.Immediately)
        registry.register(trigger)
        registry.beginSession(inClass)
        registry.tick(t0)
        registry.endSession()
        assertEquals(1, trigger.stops)
    }
    @Test fun duplicateTriggerIdsAreRejected() {
        registry.register(ManualStopTrigger())
        assertFailsWith<IllegalArgumentException> { registry.register(ManualStopTrigger()) }
    }
}
