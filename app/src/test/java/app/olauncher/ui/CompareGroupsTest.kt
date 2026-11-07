package app.olauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompareGroupsTest {

    @Test
    fun fewValuesGetAGroupEach() {
        // Doses: 0, 50 and 100mg
        val buckets = CompareGroups.amountBuckets(listOf(0f, 50f, 50f, 100f, 0f, 50f))
        assertEquals(listOf(0f, 50f, 100f), buckets.map { it.exact })
    }

    @Test
    fun manyValuesSplitIntoThirds() {
        val xs = (1..30).map { it.toFloat() }
        val buckets = CompareGroups.amountBuckets(xs)
        assertEquals(3, buckets.size)
        // Every day lands in exactly one group, about a third in each
        xs.forEach { x -> assertEquals(1, buckets.count { it.contains(x) }) }
        buckets.forEach { b -> assertTrue(xs.count { b.contains(it) } in 9..12) }
    }

    @Test
    fun manyTiesSplitInTwo() {
        // Mostly zero, a few days of other amounts
        val xs = List(20) { 0f } + listOf(1f, 2f, 3f, 4f, 5f, 6f)
        val buckets = CompareGroups.amountBuckets(xs)
        assertEquals(2, buckets.size)
        assertEquals(20, xs.count { buckets[0].contains(it) })
        assertEquals(6, xs.count { buckets[1].contains(it) })
    }
}
