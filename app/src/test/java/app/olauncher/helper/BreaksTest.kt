package app.olauncher.helper

import app.olauncher.helper.Breaks.Can
import app.olauncher.helper.Breaks.Config
import app.olauncher.helper.Breaks.Entry
import app.olauncher.helper.Breaks.Pool
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

    /** 5m every hour */
    private val five = Config(minutes = 5)

    private fun pool(minutes: Int) = Pool(banked = minutes * minute)

    private fun check(config: Config, pool: Pool, now: Long, locked: Boolean = true, breakUntil: Long = 0) =
        Breaks.check(config, pool, now, locked, breakUntil, zone)

    /** [pool] last looked at [from], earning, brought up to [to] while still earning */
    private fun earn(config: Config, pool: Pool, from: Long, to: Long, runStart: Long = 0L) =
        Breaks.accrue(config, pool.copy(at = from, earning = true), to, earningNow = true, runStart = runStart, zone = zone)

    private fun took(day: Int, hour: Int, min: Int = 0, device: String = Breaks.PHONE, length: Int = 5) =
        Entry("$day-$hour-$min-$device", at(day, hour, min), at(day, hour, min) + length * minute, device)

    @Test
    fun offOrNotLockedMeansNoBreak() {
        assertEquals(Can.Off, check(Config(), pool(5), at(18, 12)))
        assertEquals(Can.NotLocked, check(five, pool(5), at(18, 12), locked = false))
        assertEquals(Can.Yes, check(five, pool(5), at(18, 12)))
    }

    @Test
    fun aBreakNeedsAMinuteInThePool() {
        // Empty, with more coming in the rest of the hour
        assertEquals(Can.Empty(at(18, 12, 20)), check(five, Pool(progress = 40 * minute), at(18, 12)))
        assertEquals(Can.Empty(at(18, 13)), check(five, Pool(banked = 59_000L), at(18, 12)))
        assertEquals(Can.Yes, check(five, Pool(banked = minute), at(18, 12)))
    }

    @Test
    fun aBreakCantStartDuringOne() {
        val until = at(18, 12, 3)
        assertEquals(Can.OnBreak(until), check(five, pool(10), at(18, 12), breakUntil = until))
    }

    @Test
    fun eachFullHourOfLockdownAddsItsMinutes() {
        val start = at(18, 9)
        assertEquals(0L, earn(five, Pool(), start, at(18, 9, 59)).banked)
        assertEquals(5 * minute, earn(five, Pool(), start, at(18, 10)).banked)
        val later = earn(five, Pool(), start, at(18, 12, 30))
        assertEquals(15 * minute, later.banked)
        assertEquals(30 * minute, later.progress)
        // Every 30 minutes instead
        assertEquals(25 * minute, earn(five.copy(every = 30), Pool(), start, at(18, 11, 30)).banked)
    }

    @Test
    fun partHoursCarryOverAfterLockdownTurnsOff() {
        // 40 minutes locked, then off for a while, then 20 more: an hour, 5m
        val morning = earn(five, Pool(), at(18, 9), at(18, 9, 40))
        val off = Breaks.accrue(five, morning, at(18, 13), earningNow = false, zone = zone)
        assertEquals(40 * minute, off.progress)
        val afternoon = Breaks.accrue(five, off, at(18, 13, 1), earningNow = true, zone = zone)
        assertEquals(0L, afternoon.banked)
        assertEquals(5 * minute, earn(five, afternoon, at(18, 13, 1), at(18, 13, 20)).banked)
    }

    @Test
    fun aLongGapOnlyCountsWhenEarningAtBothEnds() {
        val wasOff = Pool(at = at(18, 9), earning = false)
        assertEquals(0L, Breaks.accrue(five, wasOff, at(18, 11), earningNow = true, zone = zone).banked)
        // A short gap counts by what's on now
        assertEquals(5 * minute, Breaks.accrue(five, Pool(progress = 55 * minute, at = at(18, 9), earning = false), at(18, 9, 5), earningNow = true, zone = zone).banked)
    }

    @Test
    fun thePoolStopsAtTheCap() {
        val capped = five.copy(cap = 10)
        val full = earn(capped, Pool(), at(18, 9), at(18, 14))
        assertEquals(10 * minute, full.banked)
        assertEquals(0L, full.progress)
        assertEquals(null, Breaks.nextEarn(capped, full, at(18, 14)))
        // With no cap it keeps going
        assertEquals(25 * minute, earn(five, Pool(), at(18, 9), at(18, 14)).banked)
    }

    @Test
    fun thePoolEmptiesAtMidnightOnlyWhenSetTo() {
        val evening = Pool(banked = 20 * minute, progress = 40 * minute)
        val reset = five.copy(reset = true)
        // Locked from 23:30 to 00:30: only the half hour after midnight counts
        val next = earn(reset, evening, at(18, 23, 30), at(19, 0, 30))
        assertEquals(0L, next.banked)
        assertEquals(30 * minute, next.progress)
        val kept = earn(five, evening, at(18, 23, 30), at(19, 0, 30))
        assertEquals(25 * minute, kept.banked)
        assertEquals(40 * minute, kept.progress)
    }

    @Test
    fun timesOffAndAutomaticBreaksDontEarn() {
        val config = five.copy(off = listOf(Lockdown.Schedule(Lockdown.EVERY_DAY, 10 * 60, 11 * 60)))
        // 9:00–12:00 locked with 10:00–11:00 off: two hours
        assertEquals(10 * minute, earn(config, Pool(), at(18, 9), at(18, 12)).banked)
        // Automatic 10m breaks every 60m from 9:00: 10:00–10:10 and 11:00–11:10 don't count, so 9:00–12:30 is 3 hours
        val auto = five.copy(autoMinutes = 10, autoEvery = 60)
        val p = earn(auto, Pool(), at(18, 9), at(18, 12, 30), runStart = at(18, 9))
        assertEquals(15 * minute, p.banked)
        assertEquals(0L, p.progress)
    }

    @Test
    fun aBreakUsesThePoolUpToLockdownsEndTimesOffAndAResetMidnight() {
        assertEquals(15 * minute, Breaks.breakLength(five, 15 * minute, at(18, 12), zone = zone))
        assertEquals(10 * minute, Breaks.breakLength(five, 15 * minute, at(18, 12), lockdownEnd = at(18, 12, 10), zone = zone))
        val off = five.copy(off = listOf(Lockdown.Schedule(Lockdown.EVERY_DAY, 12 * 60 + 5, 13 * 60)))
        assertEquals(5 * minute, Breaks.breakLength(off, 15 * minute, at(18, 12), zone = zone))
        assertEquals(15 * minute, Breaks.breakLength(five.copy(reset = true), 15 * minute, at(18, 12), zone = zone))
        assertEquals(10 * minute, Breaks.breakLength(five.copy(reset = true), 15 * minute, at(18, 23, 50), zone = zone))
    }

    @Test
    fun breaksAreAlwaysWrittenAsShared() {
        val old = Config.decode("""{"minutes":5,"shared":false}""")
        assertEquals(Config(minutes = 5), old)
        assertTrue(org.json.JSONObject(old.encode()).getBoolean("shared"))
    }

    @Test
    fun settingsFromBeforeThePoolKeepTheirLength() {
        // The old length becomes what's earned each hour; the old limits are dropped
        val old = Config.decode("""{"minutes":10,"cooldown":30,"stack":true,"perDay":3,"makeUp":true}""")
        assertEquals(Config(minutes = 10, every = 60, makeUp = true), old)
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
    fun automaticBreaksWorkWithManualOnesOff() {
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
        assertEquals(Can.OffNow(at(19, 6)), check(config, pool(5), at(18, 23)))
        assertEquals(Can.Yes, check(config, pool(5), at(18, 21)))
    }

    @Test
    fun configAndPoolSurviveBeingWrittenAndReadBack() {
        val config = Config(5, 30, 60, true, true, listOf(Lockdown.Schedule(31, 60, 120)), Challenge(Challenge.Type.TAPS, 100), 5, 50)
        assertEquals(config, Config.decode(config.encode()))
        assertEquals(Config(), Config.decode("not json"))
        val p = Pool(banked = 5 * minute, progress = 7 * minute, at = at(18, 9), earning = true)
        assertEquals(p, Pool.decode(p.encode()))
        assertEquals(Pool(), Pool.decode(""))
        val log = listOf(took(18, 9), took(18, 10, device = Breaks.MAC))
        assertEquals(log, Breaks.decodeLog(Breaks.encodeLog(log)))
    }

    @Test
    fun easierBreaksLoosen() {
        val config = Config(minutes = 5, every = 60, cap = 30, reset = true, challenge = Challenge(Challenge.Type.TAPS, 100))
        assertTrue(config.loosensTo(config.copy(minutes = 10)))
        assertTrue(config.loosensTo(config.copy(every = 30)))
        assertTrue(config.loosensTo(config.copy(cap = 0)))
        assertTrue(config.loosensTo(config.copy(cap = 60)))
        assertTrue(config.loosensTo(config.copy(reset = false)))
        assertTrue(config.loosensTo(config.copy(challenge = Challenge(Challenge.Type.NONE))))
        assertFalse(config.loosensTo(config.copy(minutes = 2)))
        assertFalse(config.loosensTo(config.copy(every = 120)))
        assertFalse(config.loosensTo(config.copy(cap = 10)))
        assertFalse(config.copy(cap = 0).loosensTo(config))
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
