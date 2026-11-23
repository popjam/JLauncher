package app.olauncher.helper

import app.olauncher.helper.LockdownLog.State
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class LockdownLogTest {

    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 11, day, hour, minute)
    private fun date(day: Int) = LocalDate.of(2026, 11, day)

    @Test
    fun timeIsSplitAtMidnight() {
        val days = LockdownLog.credit(emptyMap(), at(16, 23), at(17, 1), State(phone = true, mac = true, onBreak = false))
        assertEquals(3_600_000L, days[date(16)]!!.phoneMs)
        assertEquals(3_600_000L, days[date(17)]!!.phoneMs)
        assertEquals(3_600_000L, days[date(17)]!!.macMs)
        assertEquals(listOf(23 * 60 until 1440), days[date(16)]!!.spans)
        assertEquals(listOf(0 until 60), days[date(17)]!!.spans)
    }

    @Test
    fun aBreakCountsAsBreakNotLocked() {
        val days = LockdownLog.credit(emptyMap(), at(16, 12), at(16, 12, 5), State(phone = true, mac = false, onBreak = true))
        assertEquals(0L, days[date(16)]!!.phoneMs)
        assertEquals(300_000L, days[date(16)]!!.breakMs)
    }

    @Test
    fun spansThatTouchJoin() {
        var days = LockdownLog.credit(emptyMap(), at(16, 10), at(16, 10, 30), State(true, false, false))
        days = LockdownLog.credit(days, at(16, 10, 30), at(16, 11), State(true, false, false))
        assertEquals(listOf(600 until 660), days[date(16)]!!.spans)
        assertEquals(days, LockdownLog.decode(LockdownLog.encode(days)))
    }

    @Test
    fun theTvsTimeIsCountedOnItsOwn() {
        val days = LockdownLog.credit(emptyMap(), at(16, 10), at(16, 11), State(phone = false, mac = false, onBreak = false, tv = true))
        val day = days.values.single()
        assertEquals(60 * 60_000L, day.tvMs)
        assertEquals(0L, day.phoneMs)
        // Kept and read back
        assertEquals(day, LockdownLog.decode(LockdownLog.encode(days)).values.single())
    }
}
