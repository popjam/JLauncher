package app.olauncher.helper

import app.olauncher.helper.Spending.Purchase
import app.olauncher.helper.Subscriptions.Period
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SubscriptionsTest {

    private val today = LocalDate.of(2026, 11, 8)

    private fun buy(date: LocalDate, cents: Long, shop: String) = Purchase(
        date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), cents, Spending.COMMBANK,
        "$${cents / 100}.${"%02d".format(cents % 100)} spent at $shop on Entertainment.", "k$shop$date$cents")

    private fun find(purchases: List<Purchase>, marks: Map<String, Subscriptions.Mark> = emptyMap(), dismissed: Set<String> = emptySet()) =
        Subscriptions.find(purchases, marks, dismissed, today)

    @Test
    fun monthlyChargesAreFound() {
        val subs = find(listOf(buy(today.minusMonths(2), 1599, "Netflix"), buy(today.minusMonths(1), 1599, "Netflix"), buy(today.minusDays(3), 50, "Cafe")))
        assertEquals(1, subs.size)
        assertEquals("Netflix", subs[0].merchant)
        assertEquals(Period.MONTHLY, subs[0].period)
        assertEquals(1599L, subs[0].monthlyCents)
    }

    @Test
    fun weeklyNeedsFourInARowAndCostsMoreAMonth() {
        val three = (1..3).map { buy(today.minusWeeks(it.toLong()), 1000, "Gym") }
        assertTrue(find(three).isEmpty())
        val four = three + buy(today.minusWeeks(4), 1000, "Gym")
        val sub = find(four).single()
        assertEquals(Period.WEEKLY, sub.period)
        assertEquals(1000L * 52 / 12, sub.monthlyCents)
    }

    @Test
    fun differentAmountsOrUnevenGapsAreNot() {
        assertTrue(find(listOf(buy(today.minusDays(40), 1200, "Shop"), buy(today.minusDays(10), 4500, "Shop"))).isEmpty())
        assertTrue(find(listOf(buy(today.minusDays(60), 1599, "Shop"), buy(today.minusDays(10), 1599, "Shop"))).isEmpty())
    }

    @Test
    fun stoppedOnesDropOff() {
        val old = listOf(buy(today.minusMonths(4), 1599, "Netflix"), buy(today.minusMonths(3), 1599, "Netflix"))
        assertTrue(find(old).isEmpty())
    }

    @Test
    fun removedOnesStayRemovedAndMarkedOnesShowAtOnce() {
        val netflix = listOf(buy(today.minusMonths(2), 1599, "Netflix"), buy(today.minusMonths(1), 1599, "Netflix"))
        assertTrue(find(netflix, dismissed = setOf("Netflix")).isEmpty())
        val marked = find(listOf(buy(today.minusDays(5), 1099, "Spotify")), marks = mapOf("Spotify" to Subscriptions.Mark(Period.MONTHLY, 1099)))
        assertEquals(1, marked.size)
        assertTrue(marked[0].byHand)
        assertEquals(today.minusDays(5).plusMonths(1), marked[0].next)
    }
}
