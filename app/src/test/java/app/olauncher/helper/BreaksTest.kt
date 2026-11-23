package app.olauncher.helper

import app.olauncher.helper.Breaks.Can
import app.olauncher.helper.Breaks.Config
import app.olauncher.helper.Breaks.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class BreaksTest {

    private val zone = ZoneOffset.UTC
    private val minute = 60_000L

    // 2026-11-18 is a Wednesday
    private fun at(day: Int, hour: Int, min: Int = 0) = LocalDateTime.of(2026, 11, day, hour, min).toInstant(zone).toEpochMilli()

    private val five = Config(minutes = 5)

    private fun check(config: Config, log: List<Entry>, now: Long, locked: Boolean = true, breakUntil: Long = 0) =
        Breaks.check(config, log, now, locked, breakUntil, zone)

    private fun took(day: Int, hour: Int, min: Int = 0, device: String = Breaks.PHONE, length: Int = 5) =
        Entry("$day-$hour-$min-$device", at(day, hour, min), at(day, hour, min) + length * minute, device)

    @Test
    fun offOrNotLockedMeansNoBreak() {
        assertEquals(Can.Off, check(Config(), emptyList(), at(18, 12)))
        assertEquals(Can.NotLocked, check(five, emptyList(), at(18, 12), locked = false))
        assertEquals(Can.Yes, check(five, emptyList(), at(18, 12)))
    }

    @Test
    fun aBreakDuringABreakOnlyWhenTheyStack() {
        val until = at(18, 12, 3)
        assertEquals(Can.OnBreak(until), check(five, emptyList(), at(18, 12), breakUntil = until))
        assertEquals(Can.Yes, check(five.copy(stack = true), emptyList(), at(18, 12), breakUntil = until))
        // Stacked, the new one is added to the end of the one running
        assertEquals(until + 5 * minute, Breaks.newEnd(five.copy(stack = true), at(18, 12), until))
        assertEquals(at(18, 12) + 5 * minute, Breaks.newEnd(five, at(18, 12), 0))
    }

    @Test
    fun timeLeftFromABreakEndedEarlyGoesOnTheNextWhenTheyStack() {
        val stack = five.copy(stack = true)
        // A 5m break ended after 2m keeps 3m
        val saved = Breaks.savedAfterEnd(stack, at(18, 12, 2), at(18, 12, 5), 0)
        assertEquals(3 * minute, saved)
        // Which adds up with more ended early
        assertEquals(4 * minute, Breaks.savedAfterEnd(stack, at(18, 13, 4), at(18, 13, 5), saved))
        // And goes on the next break
        assertEquals(at(18, 14) + 8 * minute, Breaks.newEnd(stack, at(18, 14), 0, saved))
        // Not kept or used when breaks don't stack
        assertEquals(0L, Breaks.savedAfterEnd(five, at(18, 12, 2), at(18, 12, 5), 0))
        assertEquals(at(18, 14) + 5 * minute, Breaks.newEnd(five, at(18, 14), 0, saved))
        // An automatic break (nothing taken by hand) adds nothing
        assertEquals(saved, Breaks.savedAfterEnd(stack, at(18, 12, 2), 0, saved))
    }

    @Test
    fun timeBetweenBreaksRunsFromTheLastOnesEnd() {
        val config = five.copy(cooldown = 30)
        val log = listOf(took(18, 11))
        // Ended 11:05, so the next is from 11:35
        assertEquals(Can.Cooldown(at(18, 11, 35)), check(config, log, at(18, 11, 20)))
        assertEquals(Can.Yes, check(config, log, at(18, 11, 35)))
        // Ended early at 11:02
        val early = Breaks.ended(log, at(18, 11, 2), setOf(Breaks.PHONE))
        assertEquals(Can.Cooldown(at(18, 11, 32)), check(config, early, at(18, 11, 20)))
    }

    @Test
    fun limitsByTheHourDayAndWeek() {
        val hourly = five.copy(perHour = 2)
        val log = listOf(took(18, 10, 10), took(18, 10, 40))
        assertEquals(Can.Limit("hour", 2, at(18, 11, 10)), check(hourly, log, at(18, 10, 50)))
        assertEquals(Can.Yes, check(hourly, log, at(18, 11, 10)))

        val daily = five.copy(perDay = 2)
        assertEquals(Can.Limit("day", 2, at(19, 0)), check(daily, log, at(18, 20)))
        assertEquals(Can.Yes, check(daily, log, at(19, 0, 1)))

        // Monday the 16th to Sunday the 22nd
        val weekly = five.copy(perWeek = 3)
        val week = listOf(took(16, 9), took(17, 9), took(18, 9))
        assertEquals(Can.Limit("week", 3, at(23, 0)), check(weekly, week, at(22, 23)))
        assertEquals(Can.Yes, check(weekly, week, at(23, 1)))
    }

    @Test
    fun breaksAreAlwaysSharedSoOneOnTheMacCountsForAll() {
        val log = listOf(took(18, 9, device = Breaks.MAC))
        val shared = five.copy(perDay = 1)
        assertTrue(check(shared, log, at(18, 12)) is Can.Limit)
        assertEquals(1, Breaks.countSince(log, at(18, 0)))
        // A setting from when breaks could be kept apart is read as shared, and written as shared for the Mac
        val old = Config.decode("""{"minutes":5,"shared":false}""")
        assertEquals(Config(minutes = 5), old)
        assertTrue(org.json.JSONObject(old.encode()).getBoolean("shared"))
    }

    @Test
    fun automaticBreaksStartEveryGapAfterLockdownStarts() {
        val auto = Config(autoMinutes = 5, autoEvery = 50)
        val start = at(18, 9)
        // None in the first 50 minutes, then 9:50–9:55, 10:40–10:45…
        assertEquals(null, Breaks.autoWindow(auto, start, at(18, 9, 49)))
        assertEquals(at(18, 9, 50) to at(18, 9, 55), Breaks.autoWindow(auto, start, at(18, 9, 50)))
        assertEquals(at(18, 9, 50) to at(18, 9, 55), Breaks.autoWindow(auto, start, at(18, 9, 54)))
        assertEquals(null, Breaks.autoWindow(auto, start, at(18, 9, 55)))
        assertEquals(at(18, 10, 40) to at(18, 10, 45), Breaks.autoWindow(auto, start, at(18, 10, 41)))
        assertEquals(at(18, 10, 40), Breaks.nextAuto(auto, start, at(18, 9, 56)))
        // Off, with no lockdown, or a break as long as the gap
        assertEquals(null, Breaks.autoWindow(Config(), start, at(18, 9, 50)))
        assertEquals(null, Breaks.autoWindow(auto, 0L, at(18, 9, 50)))
        assertEquals(null, Breaks.autoWindow(Config(autoMinutes = 50, autoEvery = 50), start, at(18, 9, 50)))
        assertEquals(null, Breaks.nextAuto(auto, 0L, at(18, 9)))
    }

    @Test
    fun automaticBreaksDontUseUpManualOnes() {
        // Not in the log, so a manual break can still be taken after one, up to the limit
        val config = Config(minutes = 5, perDay = 1, autoMinutes = 5, autoEvery = 50)
        assertEquals(Can.Yes, check(config, emptyList(), at(18, 11)))
        // Automatic breaks work with manual ones off
        assertFalse(Config(autoMinutes = 5, autoEvery = 50).on)
        assertTrue(Config(autoMinutes = 5, autoEvery = 50).autoOn)
    }

    @Test
    fun lockdownsRunCountsFromWhenItStarted() {
        assertEquals(0L, Lockdown.runStart(on = false, since = 500L, now = 900L))
        assertEquals(500L, Lockdown.runStart(on = true, since = 500L, now = 900L))
        assertEquals(900L, Lockdown.runStart(on = true, since = 0L, now = 900L))
    }

    @Test
    fun noBreaksDuringTheirTimesOff() {
        val config = five.copy(off = listOf(Lockdown.Schedule(Lockdown.EVERY_DAY, 22 * 60, 6 * 60)))
        assertEquals(Can.OffNow(at(19, 6)), check(config, emptyList(), at(18, 23)))
        assertEquals(Can.Yes, check(config, emptyList(), at(18, 21)))
    }

    @Test
    fun configSurvivesBeingWrittenAndReadBack() {
        val config = Config(5, 30, true, true, 2, 4, 10, listOf(Lockdown.Schedule(31, 60, 120)), Challenge(Challenge.Type.TAPS, 100), 5, 50)
        assertEquals(config, Config.decode(config.encode()))
        assertEquals(Config(), Config.decode("not json"))
        val log = listOf(took(18, 9), took(18, 10, device = Breaks.MAC))
        assertEquals(log, Breaks.decodeLog(Breaks.encodeLog(log)))
    }

    @Test
    fun easierBreaksLoosen() {
        val config = Config(minutes = 5, cooldown = 30, perDay = 3, challenge = Challenge(Challenge.Type.TAPS, 100))
        assertTrue(config.loosensTo(config.copy(minutes = 10)))
        assertTrue(config.loosensTo(config.copy(cooldown = 0)))
        assertTrue(config.loosensTo(config.copy(perDay = 0)))
        assertTrue(config.loosensTo(config.copy(perDay = 5)))
        assertTrue(config.loosensTo(config.copy(challenge = Challenge(Challenge.Type.NONE))))
        assertFalse(config.loosensTo(config.copy(minutes = 2)))
        assertFalse(config.loosensTo(config.copy(perDay = 1, perHour = 1)))
        assertFalse(config.loosensTo(config.copy(challenge = Challenge(Challenge.Type.TAPS, 1000))))
        // Automatic breaks turned on, longer or more often loosen; shorter, rarer or off don't
        val auto = config.copy(autoMinutes = 5, autoEvery = 50)
        assertTrue(config.loosensTo(auto))
        assertTrue(auto.loosensTo(auto.copy(autoMinutes = 10)))
        assertTrue(auto.loosensTo(auto.copy(autoEvery = 25)))
        assertFalse(auto.loosensTo(auto.copy(autoEvery = 90)))
        assertFalse(auto.loosensTo(config))
    }

    @Test
    fun theLogKeepsOneEntryPerBreakAndDropsOldOnes() {
        val a = took(18, 9)
        val trimmed = Breaks.trim(listOf(a, a.copy(end = a.end + minute), took(1, 9)), at(18, 12))
        assertEquals(listOf(a.copy(end = a.end + minute)), trimmed)
    }

    @Test
    fun timesOffLoosenOnlyWhenTheyCoverLess() {
        val nights = Lockdown.Schedule(Lockdown.EVERY_DAY, 22 * 60, 6 * 60)
        val config = five.copy(off = listOf(nights))
        assertFalse(config.loosensTo(config.copy(off = listOf(Lockdown.Schedule(Lockdown.EVERY_DAY, 21 * 60, 7 * 60)))))
        assertTrue(config.loosensTo(config.copy(off = listOf(Lockdown.Schedule(Lockdown.EVERY_DAY, 23 * 60, 6 * 60)))))
        assertTrue(config.loosensTo(config.copy(off = emptyList())))
        assertFalse(five.loosensTo(config))
    }

    @Test
    fun automaticBreaksSkipTheirTimesOff() {
        // No breaks 10:00–11:00: the 10:40 one is skipped, 9:50 and 11:30 aren't
        val auto = Config(autoMinutes = 5, autoEvery = 50, off = listOf(Lockdown.Schedule(Lockdown.EVERY_DAY, 10 * 60, 11 * 60)))
        val start = at(18, 9)
        assertEquals(at(18, 9, 50) to at(18, 9, 55), Breaks.autoWindow(auto, start, at(18, 9, 51), zone))
        assertEquals(null, Breaks.autoWindow(auto, start, at(18, 10, 41), zone))
        assertEquals(at(18, 11, 30) to at(18, 11, 35), Breaks.autoWindow(auto, start, at(18, 11, 31), zone))
        assertEquals(at(18, 11, 30), Breaks.nextAuto(auto, start, at(18, 9, 56), zone))
        assertEquals(listOf(at(18, 9, 50) to at(18, 9, 55), at(18, 11, 30) to at(18, 11, 35)),
            Breaks.autoWindows(auto, start, start, at(18, 12), zone))
        // One running at the start of the span counts
        assertEquals(listOf(at(18, 9, 50) to at(18, 9, 55)), Breaks.autoWindows(auto, start, at(18, 9, 52), at(18, 10, 30), zone))
        assertEquals(emptyList<Pair<Long, Long>>(), Breaks.autoWindows(auto, 0L, start, at(18, 12), zone))
    }
}
