package app.olauncher.helper

import app.olauncher.helper.Lockdown.Schedule
import app.olauncher.helper.Lockdown.WaitState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class LockdownTest {

    // 2026-11-16 is a Monday
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 11, day, hour, minute)

    private val evenings = Schedule(Lockdown.WEEKDAYS, 22 * 60, 7 * 60)
    private val workHours = Schedule(Lockdown.WEEKDAYS, 9 * 60, 17 * 60)

    @Test
    fun daytimeScheduleCoversOnlyItsHoursOnItsDays() {
        assertTrue(workHours.covers(at(16, 9)))
        assertTrue(workHours.covers(at(16, 16, 59)))
        assertFalse(workHours.covers(at(16, 17)))
        assertFalse(workHours.covers(at(16, 8, 59)))
        // Saturday
        assertFalse(workHours.covers(at(21, 12)))
    }

    @Test
    fun overnightScheduleRunsIntoTheNextMorning() {
        // Monday night and Tuesday morning
        assertTrue(evenings.covers(at(16, 23)))
        assertTrue(evenings.covers(at(17, 6, 59)))
        assertFalse(evenings.covers(at(17, 7)))
        // Friday night runs into Saturday morning, but Saturday night isn't scheduled
        assertTrue(evenings.covers(at(21, 3)))
        assertFalse(evenings.covers(at(21, 23)))
        // Sunday night isn't scheduled, so Monday morning isn't either
        assertFalse(evenings.covers(at(16, 3)))
    }

    @Test
    fun sameStartAndEndIsTheWholeDay() {
        val sundays = Schedule(1 shl 6, 0, 0)
        assertTrue(sundays.covers(at(22, 0)))
        assertTrue(sundays.covers(at(22, 23, 59)))
        assertFalse(sundays.covers(at(23, 0)))
        assertEquals(at(23, 0), sundays.endAfter(at(22, 10)))
    }

    @Test
    fun endsAreFoundForTheWindowNowIn() {
        assertEquals(at(16, 17), workHours.endAfter(at(16, 10)))
        assertEquals(at(17, 7), evenings.endAfter(at(16, 23)))
        assertEquals(at(17, 7), evenings.endAfter(at(17, 2)))
    }

    @Test
    fun aTimerAndSchedulesThatFollowOnEndTogether() {
        // A timer to 10:00 runs into work hours, which run to 17:00
        assertEquals(at(16, 17), Lockdown.coveredUntil(listOf(workHours), at(16, 10), at(16, 8)))
        // A timer that outlasts the schedule
        assertEquals(at(16, 18), Lockdown.coveredUntil(listOf(workHours), at(16, 18), at(16, 10)))
        // Work hours, then a gap before the evening: only work hours count
        assertEquals(at(16, 17), Lockdown.coveredUntil(listOf(workHours, evenings), null, at(16, 12)))
        // Nothing on
        assertNull(Lockdown.coveredUntil(listOf(workHours), null, at(16, 8)))
        assertNull(Lockdown.coveredUntil(emptyList(), at(16, 7), at(16, 8)))
    }

    @Test
    fun nextStartSkipsDaysNotScheduled() {
        assertEquals(at(16, 22), evenings.nextStart(at(16, 12)))
        // Friday night, then nothing until Monday night
        assertEquals(at(23, 22), evenings.nextStart(at(20, 23)))
        assertNull(Schedule(0, 0, 60).nextStart(at(16, 0)))
        // An all-day schedule saved as 22:00–22:00 starts at midnight
        assertEquals(at(22, 0), Schedule(1 shl 6, 22 * 60, 22 * 60).nextStart(at(20, 12)))
    }

    @Test
    fun schedulesSurviveBeingWrittenAndReadBack() {
        val list = listOf(evenings, workHours, Schedule(Lockdown.EVERY_DAY, 0, 0))
        assertEquals(list, Lockdown.parseSchedules(Lockdown.formatSchedules(list)))
        assertEquals(emptyList<Schedule>(), Lockdown.parseSchedules(""))
    }

    @Test
    fun unreadableSchedulesAreSkipped() {
        val text = "31:600:1020;junk;0:1:2;31:2000:5;128:0:0;31:60"
        assertEquals(listOf(Schedule(31, 600, 1020)), Lockdown.parseSchedules(text))
    }

    @Test
    fun theClockSinceStartingIsUsedUntilTheNextRestart() {
        // Set with 1 hour left at wall 10_000_000, elapsed 50_000, boot 3
        val wall = 10_000_000L + 3_600_000L
        val elapsed = 50_000L + 3_600_000L
        // 10 minutes later, but the wall clock was moved a day forward
        assertEquals(3_000_000L, Lockdown.msUntil(wall, elapsed, 3, 10_000_000L + 86_400_000L, 50_000L + 600_000L, 3))
        // After a restart, only the wall clock is left
        assertEquals(3_000_000L, Lockdown.msUntil(wall, elapsed, 3, 10_000_000L + 600_000L, 1_000L, 4))
        // Without a boot count, the wall clock
        assertEquals(-82_800_000L, Lockdown.msUntil(wall, elapsed, -1, 10_000_000L + 86_400_000L, 50_000L + 600_000L, -1))
    }

    @Test
    fun waitingThenReadyForAWhileThenOver() {
        val wait = 15 * 60_000L
        assertEquals(WaitState.None, Lockdown.waitState(null, wait))
        assertEquals(WaitState.None, Lockdown.waitState(-5L, wait))
        assertEquals(WaitState.Waiting(wait), Lockdown.waitState(0L, wait))
        assertEquals(WaitState.Waiting(60_000L), Lockdown.waitState(wait - 60_000L, wait))
        assertEquals(WaitState.Ready(Lockdown.READY_MS), Lockdown.waitState(wait, wait))
        assertEquals(WaitState.None, Lockdown.waitState(wait + Lockdown.READY_MS, wait))
    }

    @Test
    fun theTvIsADeviceInSchedulesAndTriggers() {
        val tv = Lockdown.Device.TV.bit
        val schedules = Lockdown.parseSchedules("31:1080:1320:$tv;127:0:0:${Lockdown.ALL};31:60:120:8;31:60:120")
        assertEquals(listOf(tv, Lockdown.ALL, Lockdown.BOTH), schedules.map { it.devices })
        assertEquals(schedules, Lockdown.parseSchedules(Lockdown.formatSchedules(schedules)))
        val triggers = Lockdown.parseTriggers("TV:120:420:$tv;TOTAL:180:420:${Lockdown.ALL};PHONE:60:420:9")
        assertEquals(listOf(tv, Lockdown.ALL), triggers.map { it.devices })
        assertTrue(Lockdown.covers(Lockdown.ALL, Lockdown.Device.TV))
        assertFalse(Lockdown.covers(Lockdown.BOTH, Lockdown.Device.TV))
    }
}
