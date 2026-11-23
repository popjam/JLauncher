package app.olauncher.helper

import app.olauncher.helper.Limits.Over
import app.olauncher.helper.Limits.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class LimitsTest {

    private val minute = 60_000L

    @Test
    fun rulesSurviveBeingWrittenAndReadBack() {
        val rules = mapOf(
            "com.google.android.youtube" to Rule.every(30, 0), "com.reddit.frontpage" to Rule.every(0, 3), "a.b" to Rule.every(15, 5, gentle = true),
            "c.d" to Rule(listOf(30, 30, 30, 30, 30, 120, 120), listOf(0, 0, 0, 0, 0, 5, 5)),
        )
        assertEquals(rules, Limits.parseRules(Limits.formatRules(rules)))
    }

    @Test
    fun oldRulesAreTheSameEveryDayAndStillWrittenThatWay() {
        // As written before limits could differ by day: package, minutes, opens
        val old = Limits.parseRules("com.google.android.youtube:30:0;com.reddit.frontpage:0:3")
        assertEquals(Rule.every(30, 0), old["com.google.android.youtube"])
        assertEquals(Rule.every(0, 3), old["com.reddit.frontpage"])
        DayOfWeek.values().forEach { assertEquals(30, old.getValue("com.google.android.youtube").minutesOn(it)) }
        assertFalse(old.getValue("com.reddit.frontpage").gentle)
        // A rule the same every day is written the old way, so nothing changes for it
        assertEquals("com.google.android.youtube:30:0;com.reddit.frontpage:0:3", Limits.formatRules(old))
        assertEquals("a.b:15:5:g", Limits.formatRules(mapOf("a.b" to Rule.every(15, 5, gentle = true))))
    }

    @Test
    fun emptyAndUnreadableRulesAreDropped() {
        assertEquals(emptyMap<String, Rule>(), Limits.parseRules(""))
        assertEquals(emptyMap<String, Rule>(), Limits.parseRules("junk"))
        assertEquals(mapOf("a.b" to Rule.every(5, 0)), Limits.parseRules("a.b:5:0;c.d:0:0;e.f:x:1;g.h:3;:4:4;i.j:1,2:0;k.l:5:0:x"))
        assertEquals("", Limits.formatRules(mapOf("a.b" to Rule())))
    }

    @Test
    fun eachDayHasItsOwnLimits() {
        val weekends = Rule.every(30, 3).withMinutes(DayOfWeek.SATURDAY, 120).withMinutes(DayOfWeek.SUNDAY, 0).withOpens(DayOfWeek.SUNDAY, 10)
        assertEquals(30, weekends.minutesOn(DayOfWeek.MONDAY))
        assertEquals(120, weekends.minutesOn(DayOfWeek.SATURDAY))
        assertFalse(weekends.sameEveryDay)
        // An hour is over the limit on Monday, not on Saturday; Sunday has no time limit
        assertEquals(Over.TIME, Limits.over(weekends, 60 * minute, 0, opening = false, day = DayOfWeek.MONDAY))
        assertNull(Limits.over(weekends, 60 * minute, 0, opening = false, day = DayOfWeek.SATURDAY))
        assertNull(Limits.over(weekends, 600 * minute, 5, opening = true, day = DayOfWeek.SUNDAY))
        assertEquals(Over.OPENS, Limits.over(weekends, 0, 3, opening = true, day = DayOfWeek.FRIDAY))
        // The warning is about that day's limit
        assertTrue(Limits.shouldWarn(weekends, 26 * minute, warned = false, day = DayOfWeek.MONDAY))
        assertFalse(Limits.shouldWarn(weekends, 26 * minute, warned = false, day = DayOfWeek.SATURDAY))
        // Setting every day at once
        assertEquals(Rule.every(15, 3).copy(opens = weekends.opens), weekends.withMinutes(null, 15))
    }

    @Test
    fun timeLimitStopsOnceUsedUp() {
        val rule = Rule.every(minutes = 30)
        assertNull(Limits.over(rule, 29 * minute, 0, opening = true))
        assertEquals(Over.TIME, Limits.over(rule, 30 * minute, 0, opening = false))
    }

    @Test
    fun opensLimitLetsTheLastAllowedOpenThrough() {
        val rule = Rule.every(opens = 1)
        // The first open is allowed; the second is stopped
        assertNull(Limits.over(rule, 0, 0, opening = true))
        assertEquals(Over.OPENS, Limits.over(rule, 0, 1, opening = true))
        // Staying in the app after the last allowed open isn't stopped
        assertNull(Limits.over(rule, 0, 1, opening = false))
    }

    @Test
    fun warnsOnceInTheLastFiveMinutes() {
        val rule = Rule.every(minutes = 30)
        assertFalse(Limits.shouldWarn(rule, 20 * minute, warned = false))
        assertTrue(Limits.shouldWarn(rule, 26 * minute, warned = false))
        assertFalse(Limits.shouldWarn(rule, 26 * minute, warned = true))
        assertFalse(Limits.shouldWarn(rule, 30 * minute, warned = false))
        assertFalse(Limits.shouldWarn(Rule.every(opens = 3), 0, warned = false))
    }

    @Test
    fun raisingOrRemovingALimitLoosensIt() {
        assertTrue(Limits.loosens(30, 60))
        assertTrue(Limits.loosens(30, 0))
        assertFalse(Limits.loosens(30, 15))
        assertFalse(Limits.loosens(0, 15))
        assertFalse(Limits.loosens(30, 30))
        // One day raised is enough; lowering every day isn't
        val rule = Rule.every(30, 3)
        assertTrue(Limits.loosens(rule, rule.withMinutes(DayOfWeek.SATURDAY, 60)))
        assertTrue(Limits.loosens(rule, rule.withOpens(DayOfWeek.MONDAY, 0)))
        assertFalse(Limits.loosens(rule, rule.withMinutes(null, 15)))
        // Gentle loosens, strict doesn't
        assertTrue(Limits.loosens(rule, rule.copy(gentle = true)))
        assertFalse(Limits.loosens(rule.copy(gentle = true), rule))
        assertFalse(Limits.loosens(Rule(), Rule.every(30, gentle = true)))
    }

    @Test
    fun gentleLimitsShowANoticeUntilAMinuteMoreOrIgnored() {
        val now = 1_000_000L
        // A strict limit blocks; ignoring or a minute more don't apply to it
        val strict = Lockdown.limitBlock(Over.TIME, gentle = false, allowed = true, until = 5L, pkg = "a")
        assertEquals(Lockdown.Block(Lockdown.Reason.TIME_LIMIT, 5L, gentle = false, pkg = "a"), strict)
        // A gentle one shows its notice
        val gentle = Lockdown.limitBlock(Over.OPENS, gentle = true, allowed = false, until = 5L, pkg = "a")
        assertEquals(Lockdown.Block(Lockdown.Reason.OPENS_LIMIT, 5L, gentle = true, pkg = "a"), gentle)
        // Nothing until it's reached
        assertNull(Lockdown.limitBlock(null, gentle = true, allowed = false, until = 5L, pkg = "a"))
        // One more minute lets it be used for a minute, then the notice comes back; ignoring lasts the day
        assertTrue(Limits.gentleAllows(ignored = false, moreUntil = now + Limits.ONE_MORE_MS, now = now))
        assertFalse(Limits.gentleAllows(ignored = false, moreUntil = now + Limits.ONE_MORE_MS, now = now + Limits.ONE_MORE_MS))
        assertTrue(Limits.gentleAllows(ignored = true, moreUntil = 0, now = now))
        assertFalse(Limits.gentleAllows(ignored = false, moreUntil = 0, now = now))
        assertNull(Lockdown.limitBlock(Over.TIME, gentle = true, allowed = true, until = 5L, pkg = "a"))
    }

    @Test
    fun thePauseGrowsWithEachOpenUpToAMinute() {
        assertEquals(5, AppPause.grownSeconds(5, 0, grows = true))
        assertEquals(10, AppPause.grownSeconds(5, 1, grows = true))
        assertEquals(15, AppPause.grownSeconds(5, 2, grows = true))
        assertEquals(60, AppPause.grownSeconds(5, 11, grows = true))
        assertEquals(60, AppPause.grownSeconds(5, 500, grows = true))
        assertEquals(60, AppPause.grownSeconds(45, 1, grows = true))
        // Not growing, it's always the same
        assertEquals(5, AppPause.grownSeconds(5, 7, grows = false))
        // A base of a minute stays a minute
        assertEquals(60, AppPause.grownSeconds(60, 3, grows = true))
    }

    @Test
    fun sitesAreReadFromAddressBars() {
        assertEquals("reddit.com", Limits.host("https://www.reddit.com/r/android?x=1"))
        assertEquals("youtube.com", Limits.host("m.youtube.com/watch"))
        assertEquals("news.ycombinator.com", Limits.host("news.ycombinator.com"))
        assertEquals("example.com", Limits.host("Example.com:8080/"))
        assertEquals("reddit.com", Limits.host("reddit.com."))
        assertNull(Limits.host("search words here"))
        assertNull(Limits.host("localhost"))
        assertNull(Limits.host(""))
    }

    @Test
    fun aSiteCoversItsSubdomainsOtherEndingsAndLongerNames() {
        assertTrue(Limits.matches("reddit.com", "reddit.com"))
        assertTrue(Limits.matches("old.reddit.com", "reddit.com"))
        assertFalse(Limits.matches("notreddit.com", "reddit.com"))
        assertTrue(Limits.matches("reddit.com", "www.reddit.com"))
        assertTrue(Limits.matches("old.reddit.com", "reddit.com"))
        assertTrue(Limits.matches("np.reddit.com", "reddit.com"))
        assertTrue(Limits.matches("reddit.co.uk", "reddit.com"))
        assertTrue(Limits.matches("redditmedia.com", "reddit.com"))
        assertTrue(Limits.matches("i.redd.it", "redd.it"))
        assertTrue(Limits.matches("m.gamespot.co.uk", "gamespot.com"))
        assertTrue(Limits.matches("mobile.x.com", "x.com"))
        assertFalse(Limits.matches("xbox.com", "x.com"))
        assertTrue(Limits.matches("reddit.com.", "reddit.com"))
        assertTrue(Limits.matches("youtube-nocookie.com", "youtube.com"))
        assertFalse(Limits.matches("redditor.example.com", "reddit.com"))
        assertFalse(Limits.matches("google.com", "reddit.com"))
        assertEquals("reddit", Limits.siteName("old.reddit.co.uk"))
    }

    @Test
    fun limitsEndAtMidnight() {
        val zone = java.time.ZoneOffset.UTC
        val evening = java.time.LocalDateTime.of(2026, 11, 17, 21, 30)
        assertEquals(java.time.LocalDateTime.of(2026, 11, 18, 0, 0).toInstant(zone).toEpochMilli(), Lockdown.midnightAfter(evening, zone))
        assertEquals(java.time.LocalDateTime.of(2026, 11, 19, 0, 0).toInstant(zone).toEpochMilli(),
            Lockdown.midnightAfter(java.time.LocalDateTime.of(2026, 11, 18, 0, 0), zone))
        // Today's opens, warnings and pauses start again on a new day
        val today = java.time.LocalDate.of(2026, 11, 18)
        assertTrue(Limits.isNewDay("2026-11-17", today))
        assertTrue(Limits.isNewDay(null, today))
        assertFalse(Limits.isNewDay("2026-11-18", today))
    }

    @Test
    fun oldPauseChoicesCarryOverToEachApp() {
        val apps = setOf("a", "b")
        assertEquals(apps to emptySet<String>(), AppPause.migrate(pauseOn = true, useForOn = false, apps = apps))
        assertEquals(emptySet<String>() to apps, AppPause.migrate(pauseOn = false, useForOn = true, apps = apps))
        assertEquals(apps to apps, AppPause.migrate(pauseOn = true, useForOn = true, apps = apps))
        assertEquals(emptySet<String>() to emptySet<String>(), AppPause.migrate(pauseOn = false, useForOn = false, apps = apps))
    }
}
