package app.olauncher.helper

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenSpansTest {

    @Test
    fun encodesAndDecodes() {
        val spans = listOf(480 until 510, 600 until 605)
        assertEquals("480-510,600-605", ScreenSpans.encode(spans))
        assertEquals(spans, ScreenSpans.decode("480-510,600-605"))
    }

    @Test
    fun decodeSkipsNonsense() {
        assertEquals(emptyList<IntRange>(), ScreenSpans.decode(null))
        assertEquals(emptyList<IntRange>(), ScreenSpans.decode(""))
        assertEquals(listOf(10 until 20), ScreenSpans.decode("x,10-20,30-30,40-35,7"))
        assertEquals(listOf(1430 until 1440), ScreenSpans.decode("1430-1500"))
    }

    @Test
    fun msBecomeWholeMinutesWithShortGapsJoined() {
        val minute = 60_000L
        val spans = ScreenSpans.fromMs(listOf(
            8 * 60 * minute + 30_000 to 8 * 60 * minute + 5 * minute,    // 8:00:30 to 8:05
            8 * 60 * minute + 6 * minute to 8 * 60 * minute + 7 * minute, // a minute later: joined
            9 * 60 * minute to 9 * 60 * minute + 10_000,                  // a glance: its minute
            10 * 60 * minute to 10 * 60 * minute,                         // nothing
        ))
        assertEquals(listOf(480 until 487, 540 until 541), spans)
    }

    @Test
    fun segmentsSplitWhereTheScreensInUseChange() {
        val segments = ScreenSpans.segments(mapOf(
            "phone" to listOf(60 until 120),
            "mac" to listOf(90 until 150),
            "tv" to listOf(100 until 110),
        ))
        assertEquals(listOf(
            ScreenSpans.Segment(60, 90, setOf("phone")),
            ScreenSpans.Segment(90, 100, setOf("phone", "mac")),
            ScreenSpans.Segment(100, 110, setOf("phone", "mac", "tv")),
            ScreenSpans.Segment(110, 120, setOf("phone", "mac")),
            ScreenSpans.Segment(120, 150, setOf("mac")),
        ), segments)
    }

    @Test
    fun noScreensNoSegments() {
        assertEquals(emptyList<ScreenSpans.Segment>(), ScreenSpans.segments(emptyMap()))
    }
}
