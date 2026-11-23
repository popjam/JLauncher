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
    fun twoPlansTogetherTakeTheGreater() {
        val phone = Plan(timedEnd = at(16, 13))
        val mac = Plan(timedEnd = at(16, 15), schedules = listOf(nights))
        val both = phone + mac
        assertEquals(at(16, 15), both.timedEnd)
        assertTrue(both.on(at(16, 14)))
        assertTrue(both.on(at(16, 23)))
        assertFalse((phone + Plan()).on(at(16, 14)))
    }

    @Test
    fun aSwitchedOffDeviceIsSentALockdownThatsOff() {
        val shared = Plan(manual = true, timedEnd = at(16, 13), schedules = listOf(nights), windows = listOf(Window(at(16, 20), at(16, 21))))
        // Switched on, a device takes the one lockdown
        assertEquals(shared, Lockdown.planFor(shared, deviceOn = true))
        // Switched off, nothing at all
        val off = Lockdown.planFor(shared, deviceOn = false)
        assertEquals(Plan(), off)
        assertFalse(off.on(at(16, 23)))
        // What it's sent says off in the form the Mac app and the TV's PC read
        val zone = ZoneOffset.UTC
        val now = Lockdown.epoch(at(16, 12), zone)
        val json = LockdownSync.deviceJson(off, 0L, zone, at(16, 0).toLocalDate(), now)
        assertFalse(json.getBoolean("manual"))
        assertEquals(0L, json.getLong("timedUntil"))
        assertEquals(0, json.getJSONArray("windows").length())
        assertEquals(0L, json.getLong("breakUntil"))
        // Switched on, its timer and windows are there
        val on = LockdownSync.deviceJson(shared, 5L, zone, at(16, 0).toLocalDate(), now)
        assertTrue(on.getBoolean("manual"))
        assertEquals(Lockdown.epoch(at(16, 13), zone), on.getLong("timedUntil"))
        assertTrue(on.getJSONArray("windows").length() > 0)
        assertEquals(5L, on.getLong("breakUntil"))
    }

    @Test
    fun scheduleWindowsCoverTheirDays() {
        // Monday to Wednesday's nights; Sunday night's (starting the 15th) isn't on a weekday... it's every day
        val windows = nights.windows(at(16, 0).toLocalDate(), at(17, 0).toLocalDate())
        assertEquals(listOf(Window(at(15, 22), at(16, 7)), Window(at(16, 22), at(17, 7)), Window(at(17, 22), at(18, 7))), windows)
    }

    @Test
    fun triggersGoOffOnceTheTotalScreenTimeIsReached() {
        val four = Trigger(240, 7 * 60)
        val two = Trigger(120, 0)
        val hour = 3_600_000L
        assertEquals(listOf(two), Lockdown.dueTriggers(listOf(four, two), 3 * hour, emptySet()))
        assertEquals(listOf(four, two), Lockdown.dueTriggers(listOf(four, two), 4 * hour + 1, emptySet()))
        // Once a day
        assertEquals(listOf(four), Lockdown.dueTriggers(listOf(four, two), 5 * hour, setOf(two.key)))
        // Went off today under the key written before there was one lockdown
        assertEquals(emptyList<Trigger>(), Lockdown.dueTriggers(listOf(four), 5 * hour, setOf("TOTAL:240:420:3")))
        assertEquals(emptyList<Trigger>(), Lockdown.dueTriggers(listOf(four), 5 * hour, setOf("PHONE:240:420:1")))
    }

    @Test
    fun oldTriggersAllCountTheTotalOnEveryDevice() {
        // Whose screen time and which devices, from before: the phone's on the phone, the TV's on the TV, the total on both
        val old = Lockdown.parseTriggers("PHONE:120:0:1;TV:90:420:4;TOTAL:240:420:3;MAC:120:0:2;NOPE:1:2:3")
        assertEquals(listOf(Trigger(120, 0), Trigger(90, 420), Trigger(240, 420)), old)
        // Written with the total and every device, as the Mac app's older readers expect four parts
        assertEquals("TOTAL:120:0:7;TOTAL:90:420:7;TOTAL:240:420:7", Lockdown.formatTriggers(old))
    }

    @Test
    fun aTriggerLocksUntilTheNextTimeItsSetTo() {
        val trigger = Trigger(240, 7 * 60)
        assertEquals(Window(at(16, 21), at(17, 7)), trigger.windowFrom(at(16, 21)))
        // After midnight, the same morning
        assertEquals(Window(at(17, 1), at(17, 7)), trigger.windowFrom(at(17, 1)))
        // Until midnight
        assertEquals(at(17, 0), Trigger(60, 0).windowFrom(at(16, 15)).end)
    }

    @Test
    fun triggersAndWindowsSurviveBeingWrittenAndReadBack() {
        val triggers = listOf(Trigger(240, 420), Trigger(90, 0))
        assertEquals(triggers, Lockdown.parseTriggers(Lockdown.formatTriggers(triggers)))
        assertEquals(emptyList<Trigger>(), Lockdown.parseTriggers("NOPE:1:2:3;TOTAL:0:420:3;TOTAL:60:1440:3"))
        val zone = ZoneOffset.UTC
        val windows = listOf(Window(at(16, 21), at(17, 7)))
        assertEquals(windows, Lockdown.parseWindows(Lockdown.formatWindows(windows, zone), zone))
        // Windows written with their devices before still read
        val old = "${Lockdown.epoch(at(16, 21), zone)}:${Lockdown.epoch(at(17, 7), zone)}:3"
        assertEquals(windows, Lockdown.parseWindows(old, zone))
    }
}
