package app.olauncher.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SpendingTest {

    private fun purchase(text: String, mark: Boolean? = null, rule: Boolean? = null) =
        Spending.Purchase(0L, 1000, Spending.COMMBANK, text, "k", mark, rule)

    @Test
    fun readsCommBankAmounts() {
        assertEquals(1250L, Spending.purchaseCents(Spending.COMMBANK, "\$12.50 spent at Woolworths on Groceries."))
        assertEquals(123456L, Spending.purchaseCents(Spending.COMMBANK, "\$1,234.56 spent at JB Hi-Fi on Shopping."))
        assertEquals(500L, Spending.purchaseCents(Spending.WALLET, "\$5 · Gong Cha"))
    }

    @Test
    fun skipsMoneyComingInAndDeclines() {
        assertNull(Spending.purchaseCents(Spending.COMMBANK, "You received \$50.00 from Alex"))
        assertNull(Spending.purchaseCents(Spending.COMMBANK, "Your payment of \$20.00 was declined"))
        assertNull(Spending.purchaseCents(Spending.COMMBANK, "Refund of \$9.99 from Steam"))
        // CommBank's that don't say money went out
        assertNull(Spending.purchaseCents(Spending.COMMBANK, "Your statement for \$0.00 is ready"))
    }

    @Test
    fun readsShopAndCategory() {
        val p = purchase("\$65.00 spent at Steam on Entertainment.")
        assertEquals("Steam", p.merchant)
        assertEquals("Entertainment", p.category)
        assertEquals("Entertainment", p.group)
        assertFalse(p.essential)
    }

    @Test
    fun essentialsByWordsRuleAndMark() {
        assertTrue(purchase("\$15.75 spent at Woolworths on Groceries.").essential)
        // A category set as essential
        assertTrue(purchase("\$65.00 spent at Steam on Entertainment.", rule = true).essential)
        // One marked by hand wins over its category
        assertFalse(purchase("\$15.75 spent at Woolworths on Groceries.", mark = false, rule = true).essential)
        assertTrue(purchase("\$65.00 spent at Steam on Entertainment.", mark = true, rule = false).essential)
    }

    @Test
    fun weeklyLimitWithoutCarryingOver() {
        val monday = LocalDate.of(2026, 11, 2)
        assertEquals(12000L, Spending.allowanceCents(12000, null, monday, mapOf(monday.minusDays(3) to 50000L)))
    }

    @Test
    fun leftoverCarriesIntoNextWeek() {
        val first = LocalDate.of(2026, 10, 26)
        // $50 unspent of $100 gives $150 next week
        val spent = mapOf(first to 3000L, first.plusDays(2) to 2000L)
        assertEquals(15000L, Spending.allowanceCents(10000, first, first.plusWeeks(1), spent))
    }

    @Test
    fun debtComesOffNextWeek() {
        val first = LocalDate.of(2026, 10, 26)
        // $20 over a $100 limit leaves $80 next week
        assertEquals(8000L, Spending.allowanceCents(10000, first, first.plusWeeks(1), mapOf(first.plusDays(4) to 12000L)))
    }

    @Test
    fun carryOverAddsUpOverWeeks() {
        val first = LocalDate.of(2026, 10, 19)
        // +$50, then -$30 against the $150: $100 + $150 - $180 = $70
        val spent = mapOf(first to 5000L, first.plusWeeks(1) to 18000L)
        assertEquals(7000L, Spending.allowanceCents(10000, first, first.plusWeeks(2), spent))
        // The first week itself starts at the limit
        assertEquals(10000L, Spending.allowanceCents(10000, first, first, spent))
    }
}
