package app.olauncher.helper

import app.olauncher.helper.Limits.Over
import app.olauncher.helper.Limits.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitsTest {

    private val minute = 60_000L

    @Test
    fun rulesSurviveBeingWrittenAndReadBack() {
        val rules = mapOf("com.google.android.youtube" to Rule(30, 0), "com.reddit.frontpage" to Rule(0, 3), "a.b" to Rule(15, 5))
        assertEquals(rules, Limits.parseRules(Limits.formatRules(rules)))
    }

    @Test
    fun emptyAndUnreadableRulesAreDropped() {
        assertEquals(emptyMap<String, Rule>(), Limits.parseRules(""))
        assertEquals(emptyMap<String, Rule>(), Limits.parseRules("junk"))
        assertEquals(mapOf("a.b" to Rule(5, 0)), Limits.parseRules("a.b:5:0;c.d:0:0;e.f:x:1;g.h:3;:4:4"))
        assertEquals("", Limits.formatRules(mapOf("a.b" to Rule())))
    }

    @Test
    fun timeLimitStopsOnceUsedUp() {
        val rule = Rule(minutes = 30)
        assertNull(Limits.over(rule, 29 * minute, 0, opening = true))
        assertEquals(Over.TIME, Limits.over(rule, 30 * minute, 0, opening = false))
    }

    @Test
    fun opensLimitLetsTheLastAllowedOpenThrough() {
        val rule = Rule(opens = 1)
        // The first open is allowed; the second is stopped
        assertNull(Limits.over(rule, 0, 0, opening = true))
        assertEquals(Over.OPENS, Limits.over(rule, 0, 1, opening = true))
        // Staying in the app after the last allowed open isn't stopped
        assertNull(Limits.over(rule, 0, 1, opening = false))
    }

    @Test
    fun warnsOnceInTheLastFiveMinutes() {
        val rule = Rule(minutes = 30)
        assertFalse(Limits.shouldWarn(rule, 20 * minute, warned = false))
        assertTrue(Limits.shouldWarn(rule, 26 * minute, warned = false))
        assertFalse(Limits.shouldWarn(rule, 26 * minute, warned = true))
        assertFalse(Limits.shouldWarn(rule, 30 * minute, warned = false))
        assertFalse(Limits.shouldWarn(Rule(opens = 3), 0, warned = false))
    }

    @Test
    fun raisingOrRemovingALimitLoosensIt() {
        assertTrue(Limits.loosens(30, 60))
        assertTrue(Limits.loosens(30, 0))
        assertFalse(Limits.loosens(30, 15))
        assertFalse(Limits.loosens(0, 15))
        assertFalse(Limits.loosens(30, 30))
    }

    @Test
    fun stageIsTheHighestOneReached() {
        val stages = listOf(120, 180, 240)
        assertEquals(0, Limits.stage(stages, 119 * minute))
        assertEquals(1, Limits.stage(stages, 120 * minute))
        assertEquals(2, Limits.stage(stages, 200 * minute))
        assertEquals(3, Limits.stage(stages, 300 * minute))
        // A stage that's off is skipped
        assertEquals(1, Limits.stage(listOf(120, 0, 240), 200 * minute))
        assertEquals(0, Limits.stage(listOf(0, 0, 0), 900 * minute))
    }

    @Test
    fun nextStageIsTheFirstNotYetReached() {
        val stages = listOf(120, 180, 240)
        assertEquals(1 to 120, Limits.nextStage(stages, 60 * minute))
        assertEquals(3 to 240, Limits.nextStage(stages, 200 * minute))
        assertNull(Limits.nextStage(stages, 300 * minute))
        assertEquals(3 to 240, Limits.nextStage(listOf(120, 0, 240), 150 * minute))
    }

    @Test
    fun stageTimesStayInOrder() {
        val stages = listOf(120, 180, 240)
        assertTrue(Limits.fitsStage(stages, 1, 150))
        assertFalse(Limits.fitsStage(stages, 1, 120))
        assertFalse(Limits.fitsStage(stages, 1, 240))
        assertTrue(Limits.fitsStage(stages, 1, 0))
        assertTrue(Limits.fitsStage(listOf(120, 0, 0), 2, 60).not())
        assertTrue(Limits.fitsStage(listOf(0, 0, 240), 0, 60))
    }

    @Test
    fun stagesReadBackWithMissingOnesOff() {
        assertEquals(listOf(0, 0, 0), Limits.parseStages(""))
        assertEquals(listOf(120, 0, 0), Limits.parseStages("120"))
        assertEquals(listOf(120, 180, 240), Limits.parseStages(Limits.formatStages(listOf(120, 180, 240))))
        assertEquals(listOf(0, 180, 0), Limits.parseStages("x,180,-5"))
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
    fun aSiteCoversItsSubdomainsOnly() {
        assertTrue(Limits.matches("reddit.com", "reddit.com"))
        assertTrue(Limits.matches("old.reddit.com", "reddit.com"))
        assertFalse(Limits.matches("notreddit.com", "reddit.com"))
        assertTrue(Limits.matches("reddit.com", "www.reddit.com"))
    }

    @Test
    fun stagesSlowThenBlockThenLockDown() {
        val blocked = setOf("reddit")
        val essentials = setOf("maps")
        // Stage 1: blocked apps get the pause, nothing is blocked
        assertTrue(Lockdown.stagePauses(1, "reddit", blocked))
        assertFalse(Lockdown.stagePauses(1, "news", blocked))
        assertFalse(Lockdown.stageBlocks(1, "reddit", blocked, essentials))
        // Stage 2: blocked apps are blocked, others aren't
        assertTrue(Lockdown.stageBlocks(2, "reddit", blocked, essentials))
        assertFalse(Lockdown.stageBlocks(2, "news", blocked, essentials))
        // Stage 3: everything but essentials
        assertTrue(Lockdown.stageBlocks(3, "news", blocked, essentials))
        assertFalse(Lockdown.stageBlocks(3, "maps", blocked, essentials))
        // No stage reached
        assertFalse(Lockdown.stageBlocks(0, "reddit", blocked, essentials))
        assertFalse(Lockdown.stagePauses(0, "reddit", blocked))
    }

    @Test
    fun stagesAndLimitsEndAtMidnight() {
        val zone = java.time.ZoneOffset.UTC
        val evening = java.time.LocalDateTime.of(2026, 11, 17, 21, 30)
        assertEquals(java.time.LocalDateTime.of(2026, 11, 18, 0, 0).toInstant(zone).toEpochMilli(), Lockdown.midnightAfter(evening, zone))
        assertEquals(java.time.LocalDateTime.of(2026, 11, 19, 0, 0).toInstant(zone).toEpochMilli(),
            Lockdown.midnightAfter(java.time.LocalDateTime.of(2026, 11, 18, 0, 0), zone))
        // Today's opens and warnings start again on a new day
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
