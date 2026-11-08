package app.olauncher.helper

import org.junit.Assert.assertEquals
import org.junit.Test

class ArrangementTest {

    @Test
    fun nothingSavedKeepsTheNaturalOrder() {
        assertEquals(listOf("a", "b", "c"), Arrangement.merge(listOf("a", "b", "c"), emptyList()))
    }

    @Test
    fun savedOrderWins() {
        assertEquals(listOf("c", "a", "b"), Arrangement.merge(listOf("a", "b", "c"), listOf("c", "a", "b")))
    }

    @Test
    fun goneKeysAreDroppedAndRepeatsIgnored() {
        assertEquals(listOf("b", "a"), Arrangement.merge(listOf("a", "b"), listOf("x", "b", "b", "a")))
    }

    @Test
    fun aNewKeyGoesAfterTheOneItFollowsNaturally() {
        // "n" comes after "b" naturally, so it goes after "b" wherever "b" was moved
        assertEquals(listOf("c", "b", "n", "a"), Arrangement.merge(listOf("a", "b", "n", "c"), listOf("c", "b", "a")))
    }

    @Test
    fun aNewFirstKeyGoesFirst() {
        assertEquals(listOf("n", "c", "a"), Arrangement.merge(listOf("n", "a", "c"), listOf("c", "a")))
    }

    @Test
    fun movedPutsAKeyBeforeAnotherOrLast() {
        assertEquals(listOf("b", "a", "c"), Arrangement.moved(listOf("a", "b", "c"), "b", "a"))
        assertEquals(listOf("b", "c", "a"), Arrangement.moved(listOf("a", "b", "c"), "a", null))
        assertEquals(listOf("b", "c", "a"), Arrangement.moved(listOf("a", "b", "c"), "a", "gone"))
    }
}
