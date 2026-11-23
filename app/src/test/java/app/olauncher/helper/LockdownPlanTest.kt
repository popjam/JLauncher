package app.olauncher.helper

import app.olauncher.helper.Lockdown.Plan
import app.olauncher.helper.Lockdown.Schedule
import app.olauncher.helper.Lockdown.Trigger
import app.olauncher.helper.Lockdown.Window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Lockdown's windows working together: devices, screen time triggers, and adding time */
class LockdownPlanTest {

    // 2026-11-16 is a Monday
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 11, day, hour, minute)

    private val nights = Schedule(Lockdown.EVERY_DAY, 22 * 60, 7 * 60)

    @Test
    fun aShorterLockdownInsideALongerOneChangesNothing() {
        // 22:00–07:00 every night, and screen time locks from 23:00 to 06:00
        val plan = Plan(schedules = listOf(nights), windows = listOf(Window(at(16, 23), at(17, 6))))
        assertEquals(at(17, 7), plan.until(at(16, 22)))
        assertEquals(at(17, 7), plan.until(at(16, 23, 30)))
    }

    @Test
    fun addingTimePushesTheWholeRunLater() {
        // At 22:00 the night's lockdown runs to 07:00; adding 15 minutes makes the timer end at 07:15
        val plan = Plan(schedules = listOf(nights))
        val base = plan.until(at(16, 22))!!
        val added = plan.copy(timedEnd = base.plusMinutes(15))
        assertEquals(at(17, 7, 15), added.until(at(16, 22)))
        // Something else that ends sooner doesn't take it back
        val withTrigger = added.copy(windows = listOf(Window(at(16, 23), at(17, 6))))
        assertEquals(at(17, 7, 15), withTrigger.until(at(16, 23)))
    }

    @Test
    fun windowsThatFollowOnMakeOneLockdown() {
        val plan = Plan(windows = listOf(Window(at(16, 20), at(16, 22))), schedules = listOf(nights))
        assertEquals(at(17, 7), plan.until(at(16, 21)))
        assertFalse(plan.on(at(16, 19)))
        assertTrue(plan.on(at(16, 20)))
    }

    @Test
    fun switchedOnHasNoEnd() {
        assertNull(Plan(manual = true, schedules = listOf(nights)).until(at(16, 23)))
        assertTrue(Plan(manual = true).on(at(16, 12)))
    }

    @Test
    fun matchedDevicesTakeTheGreater() {
        val phone = Plan(timedEnd = at(16, 13))
        val mac = Plan(timedEnd = at(16, 15), schedules = listOf(nights))
        val both = phone + mac
        assertEquals(at(16, 15), both.timedEnd)
        assertTrue(both.on(at(16, 14)))
        assertTrue(both.on(at(16, 23)))
        assertFalse((phone + Plan()).on(at(16, 14)))
    }

    @Test
    fun schedulesKeepTheirDevices() {
        val list = listOf(Schedule(31, 600, 1020), Schedule(31, 600, 1020, Lockdown.Device.MAC.bit), Schedule(96, 0, 0, Lockdown.Device.PHONE.bit))
        val text = Lockdown.formatSchedules(list)
        // Both devices is written the way it always was
        assertEquals("31:600:1020;31:600:1020:2;96:0:0:1", text)
        assertEquals(list, Lockdown.parseSchedules(text))
        assertEquals(emptyList<Schedule>(), Lockdown.parseSchedules("31:600:1020:0;31:600:1020:8"))
    }

    @Test
    fun scheduleWindowsCoverTheirDays() {
        // Monday to Wednesday's nights; Sunday night's (starting the 15th) isn't on a weekday... it's every day
        val windows = nights.windows(at(16, 0).toLocalDate(), at(17, 0).toLocalDate())
        assertEquals(listOf(Window(at(15, 22), at(16, 7)), Window(at(16, 22), at(17, 7)), Window(at(17, 22), at(18, 7))), windows)
    }

    @Test
    fun triggersGoOffOnceTheirScreenTimeIsReached() {
        val total = Trigger(Lockdown.Source.TOTAL, 240, 7 * 60)
        val phone = Trigger(Lockdown.Source.PHONE, 120, 0, Lockdown.Device.PHONE.bit)
        val hour = 3_600_000L
        val used = mapOf(Lockdown.Source.TOTAL to 4 * hour + 1, Lockdown.Source.PHONE to hour)
        assertEquals(listOf(total), Lockdown.dueTriggers(listOf(total, phone), used, emptySet()))
        // Once a day
        assertEquals(emptyList<Trigger>(), Lockdown.dueTriggers(listOf(total, phone), used, setOf(total.key)))
    }

    @Test
    fun aTriggerLocksUntilTheNextTimeItsSetTo() {
        val trigger = Trigger(Lockdown.Source.TOTAL, 240, 7 * 60)
        assertEquals(Window(at(16, 21), at(17, 7)), trigger.windowFrom(at(16, 21)))
        // After midnight, the same morning
        assertEquals(Window(at(17, 1), at(17, 7)), trigger.windowFrom(at(17, 1)))
        // Until midnight
        assertEquals(at(17, 0), Trigger(Lockdown.Source.PHONE, 60, 0).windowFrom(at(16, 15)).end)
    }

    @Test
    fun triggersAndWindowsSurviveBeingWrittenAndReadBack() {
        val triggers = listOf(Trigger(Lockdown.Source.TOTAL, 240, 420), Trigger(Lockdown.Source.MAC, 90, 0, 2))
        assertEquals(triggers, Lockdown.parseTriggers(Lockdown.formatTriggers(triggers)))
        assertEquals(emptyList<Trigger>(), Lockdown.parseTriggers("NOPE:1:2:3;TOTAL:0:420:3;TOTAL:60:1440:3"))
        val zone = ZoneOffset.UTC
        val windows = listOf(Window(at(16, 21), at(17, 7), 3))
        assertEquals(windows, Lockdown.parseWindows(Lockdown.formatWindows(windows, zone), zone))
    }
}
