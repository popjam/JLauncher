package app.olauncher.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkTest {

    private val hour = 3_600_000L

    private fun day(vararg stretches: Pair<Int, Long>): Social.Day {
        var at = 0L
        return Social.Day(stretches.map { (place, ms) -> Social.Stretch(at, at + ms, place).also { at += ms } })
    }

    @Test
    fun anHourAtWorkIsAWorkDay() {
        assertEquals(true, Social.workedOn(day(0 to 8 * hour, Social.WORK to hour, 0 to 15 * hour), isToday = false))
        assertEquals(true, Social.workedOn(day(0 to 9 * hour, Social.WORK to 2 * hour), isToday = true))
    }

    @Test
    fun aTrackedDayWithoutWorkIsOff() {
        assertEquals(false, Social.workedOn(day(0 to 20 * hour, Social.OUT to 4 * hour), isToday = false))
        // Under an hour there doesn't count
        assertEquals(false, Social.workedOn(day(0 to 20 * hour, Social.WORK to hour / 2, Social.OUT to 3 * hour), isToday = false))
    }

    @Test
    fun notEnoughToTell() {
        // Today isn't over yet
        assertNull(Social.workedOn(day(0 to 20 * hour), isToday = true))
        // Mostly unknown
        assertNull(Social.workedOn(day(0 to 3 * hour, Social.UNKNOWN to 21 * hour), isToday = false))
    }

    @Test
    fun workCountsAsOutOfTheHouse() {
        val d = day(0 to 8 * hour, Social.WORK to 8 * hour, Social.OUT to hour, 1 to 7 * hour)
        assertEquals(8 * hour, d.workMs)
        assertEquals(9 * hour, d.outMs)
        // The home most of the day was at, not work
        assertEquals(0, d.mainHome)
        assertFalse(d.stayedIn)
        assertTrue(day(0 to 23 * hour, Social.OUT to hour / 2).stayedIn)
    }
}
