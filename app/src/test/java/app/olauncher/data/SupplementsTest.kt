package app.olauncher.data

import app.olauncher.data.Supplement.Kind
import app.olauncher.data.Supplement.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class SupplementsTest {

    private val start = LocalDate.of(2026, 11, 2) // a Monday

    private fun supplement(schedule: Schedule, amount: Float = 400f) =
        Supplement("p1", "Magnesium", "mg", 0, listOf(Supplement.Change(start, amount, schedule)))

    private fun taken(s: Supplement, days: Int) = (0 until days).map { s.isTaken(start.plusDays(it.toLong())) }

    @Test
    fun everyDayFromItsStartOnly() {
        val s = supplement(Schedule(Kind.DAILY))
        assertFalse(s.isTaken(start.minusDays(1)))
        assertEquals(List(10) { true }, taken(s, 10))
        assertNull(s.amountOn(start.minusDays(1)))
        assertEquals(400f, s.amountOn(start))
    }

    @Test
    fun daysOnThenOff() {
        val s = supplement(Schedule(Kind.DAYS, on = 5, off = 2))
        assertEquals(listOf(true, true, true, true, true, false, false, true, true), taken(s, 9))
        // Every other day
        assertEquals(listOf(true, false, true, false), taken(supplement(Schedule(Kind.DAYS, on = 1, off = 1)), 4))
    }

    @Test
    fun weeksOnThenOff() {
        val s = supplement(Schedule(Kind.WEEKS, on = 1, off = 1))
        assertEquals(List(7) { true } + List(7) { false } + List(7) { true }, taken(s, 21))
        val long = supplement(Schedule(Kind.WEEKS, on = 8, off = 4))
        assertEquals(Triple(3, 8, true), long.weekOf(start.plusDays(15)))
        assertEquals(Triple(2, 4, false), long.weekOf(start.plusDays(9 * 7)))
    }

    @Test
    fun setWeekdaysStayOnTheirDays() {
        val s = supplement(Schedule(Kind.WEEKDAYS, weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)))
        assertEquals(listOf(true, false, true, false, true, false, false), taken(s, 7))
    }

    @Test
    fun aNewAmountKeepsTheCycleButANewScheduleRestartsIt() {
        val s = supplement(Schedule(Kind.DAYS, on = 5, off = 2))
        // Day 3 of the cycle: only the amount changes, so days 5 and 6 are still off
        val lower = Supplements.change(s, start.plusDays(3), 300f, Schedule(Kind.DAYS, on = 5, off = 2))
        assertEquals(400f, lower.amountOn(start.plusDays(2)))
        assertEquals(300f, lower.amountOn(start.plusDays(3)))
        assertFalse(lower.isTaken(start.plusDays(5)))
        assertTrue(lower.isTaken(start.plusDays(7)))
        // A new schedule counts from its own day
        val every = Supplements.change(s, start.plusDays(3), 400f, Schedule(Kind.DAYS, on = 2, off = 1))
        assertEquals(listOf(true, true, false, true), (3..6).map { every.isTaken(start.plusDays(it.toLong())) })
        // Nothing changes when it's the same
        assertEquals(s, Supplements.change(s, start.plusDays(3), 400f, Schedule(Kind.DAYS, on = 5, off = 2)))
    }

    @Test
    fun aChangeOnTheSameDayReplacesIt() {
        val s = Supplements.change(supplement(Schedule(Kind.DAILY)), start.plusDays(3), 300f, Schedule(Kind.DAILY))
        val again = Supplements.change(s, start.plusDays(3), 200f, Schedule(Kind.DAILY))
        assertEquals(2, again.changes.size)
        assertEquals(200f, again.amountOn(start.plusDays(3)))
    }

    @Test
    fun stoppingAndStartingAgain() {
        val s = Supplements.stop(supplement(Schedule(Kind.DAYS, on = 5, off = 2)), start.plusDays(3))
        assertTrue(s.isTaken(start.plusDays(2)))
        assertFalse(s.isTaken(start.plusDays(3)))
        assertTrue(s.isStopped(start.plusDays(10)))
        assertFalse(s.isActive(start.plusDays(10)))
        // A fix can't mark a day it's stopped
        assertEquals(s, Supplements.flip(s, start.plusDays(4)))
        // Undo takes the stop back
        assertTrue(Supplements.undoStop(s, start.plusDays(3)).isTaken(start.plusDays(3)))
        // Starting again counts its cycle from then
        val again = Supplements.change(s, start.plusDays(10), 400f, Schedule(Kind.DAYS, on = 5, off = 2))
        assertEquals(listOf(true, true, true, true, true, false), (10..15).map { again.isTaken(start.plusDays(it.toLong())) })
        assertFalse(again.isTaken(start.plusDays(9)))
    }

    @Test
    fun undoingAStopBringsBackWhatWasThereThatDay() {
        // Started that day: stop then undo leaves it as it was, not empty
        val fresh = supplement(Schedule(Kind.DAYS, on = 5, off = 2))
        assertEquals(fresh, Supplements.undoStop(Supplements.stop(fresh, start), start))
        // A new amount that day comes back too
        val lower = Supplements.change(fresh, start.plusDays(3), 300f, Schedule(Kind.DAYS, on = 5, off = 2))
        val stopped = Supplements.stop(lower, start.plusDays(3))
        assertFalse(stopped.isTaken(start.plusDays(3)))
        assertEquals(lower, Supplements.undoStop(stopped, start.plusDays(3)))
        // A stop on its own is simply removed
        assertEquals(lower, Supplements.undoStop(Supplements.stop(lower, start.plusDays(8)), start.plusDays(8)))
    }

    @Test
    fun markedDaysWinAndUnmarkWhenTheyMatch() {
        val s = supplement(Schedule(Kind.DAYS, on = 5, off = 2))
        val missed = Supplements.flip(s, start.plusDays(1))
        assertFalse(missed.isTaken(start.plusDays(1)))
        assertTrue(missed.isMissed(start.plusDays(1)))
        // Flipping back drops the mark
        assertEquals(s, Supplements.flip(missed, start.plusDays(1)))
        // Taken on an off day
        val extra = Supplements.flip(s, start.plusDays(5))
        assertTrue(extra.isTaken(start.plusDays(5)))
        assertFalse(extra.isMissed(start.plusDays(5)))
        assertEquals(400f, extra.amountOn(start.plusDays(5)))
        // Not before it starts
        assertEquals(s, Supplements.flip(s, start.minusDays(1)))
    }

    @Test
    fun checkInAsksOnlyWhileOneIsBeingTaken() {
        val s = supplement(Schedule(Kind.WEEKDAYS, weekdays = setOf(DayOfWeek.MONDAY)))
        // Being taken on its plan counts, even on a day off
        assertTrue(Supplements.asked(listOf(s), start.plusDays(1)))
        assertFalse(Supplements.asked(listOf(s), start.minusDays(1)))
        assertFalse(Supplements.asked(listOf(Supplements.stop(s, start.plusDays(2))), start.plusDays(3)))
        assertFalse(Supplements.asked(emptyList(), start))
        val both = listOf(s, supplement(Schedule(Kind.DAYS, on = 1, off = 1)))
        assertEquals(2, Supplements.takenCount(both, start))
        assertEquals(0, Supplements.takenCount(both, start.plusDays(1)))
    }

    @Test
    fun daysToCompare() {
        val s = Supplements.change(supplement(Schedule(Kind.DAYS, on = 1, off = 1)), start.plusDays(10), 300f, Schedule(Kind.DAYS, on = 1, off = 1))
        val today = start.plusDays(13)
        val (taken, not) = Supplements.takenAndNot(s, today)
        assertEquals(14, taken.size + not.size)
        assertEquals(7, taken.size)
        val (before, since) = Supplements.beforeAndSince(s, s.changes[0], today)
        assertEquals(14, before.size)
        assertEquals(start.minusDays(1), before.last())
        // Up to the day before the next change
        assertEquals(start.plusDays(9), since.last())
        assertEquals(start.plusDays(13), Supplements.beforeAndSince(s, s.changes[1], today).second.last())
    }

    @Test
    fun amountsReadWithoutTrailingZeros() {
        assertEquals("5000", Supplements.amountText(5000f))
        assertEquals("0.5", Supplements.amountText(0.5f))
        assertEquals("2.25", Supplements.amountText(2.25f))
    }

    @Test
    fun cyclesNeedSensibleNumbers() {
        assertTrue(Schedule(Kind.DAYS, on = 5, off = 0).isValid)
        assertFalse(Schedule(Kind.DAYS, on = 0, off = 2).isValid)
        assertFalse(Schedule(Kind.WEEKS, on = 1, off = -1).isValid)
        assertFalse(Schedule(Kind.WEEKDAYS).isValid)
    }
}
