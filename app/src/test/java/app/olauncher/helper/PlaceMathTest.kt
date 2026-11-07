package app.olauncher.helper

import app.olauncher.helper.PlaceMath.Reading
import app.olauncher.helper.PlaceMath.Spot
import app.olauncher.helper.PlaceMath.Stay
import app.olauncher.helper.PlaceMath.Visit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class PlaceMathTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    // A gym and a café about 1km apart
    private val gym = 51.5000 to -0.1000
    private val cafe = 51.5090 to -0.1000

    private fun reading(at: Long, where: Pair<Double, Double>, accuracy: Float = 40f, nets: Set<String> = emptySet()) =
        Reading(at, where.first, where.second, accuracy, nets)

    /** Readings every 15 minutes at each spot in turn, with how long at each */
    private fun stays(vararg legs: Pair<Pair<Double, Double>, Long>, from: Long = 0L): Pair<Stay?, List<Visit>> {
        var stay: Stay? = null
        val visits = mutableListOf<Visit>()
        var at = from
        legs.forEach { (where, ms) ->
            val end = at + ms
            while (at < end) {
                val (next, visit) = PlaceMath.step(stay, reading(at, where))
                visit?.let { visits += it }
                stay = next
                at += 15 * minute
            }
        }
        return stay to visits
    }

    @Test
    fun distanceIsAboutRight() {
        val metres = PlaceMath.distance(gym.first, gym.second, cafe.first, cafe.second)
        assertTrue(metres in 990.0..1010.0)
    }

    @Test
    fun anHourAtOnePlaceThenMovingOnIsAVisit() {
        val (stay, visits) = stays(gym to hour, cafe to 15 * minute)
        assertEquals(1, visits.size)
        assertEquals(45 * minute, visits[0].ms)
        assertTrue(PlaceMath.distance(visits[0].lat, visits[0].lon, gym.first, gym.second) < 5)
        assertNotNull(stay)
    }

    @Test
    fun aQuickStopIsNotAVisit() {
        val (_, visits) = stays(gym to 15 * minute, cafe to 15 * minute)
        assertTrue(visits.isEmpty())
    }

    @Test
    fun aLongGapAtTheSamePlaceCarriesTheStayOn() {
        // The phone left still for hours: two readings far apart at the same spot are one stay
        val (first, _) = PlaceMath.step(null, reading(0, gym))
        val (second, visit) = PlaceMath.step(first, reading(5 * hour, gym))
        assertNull(visit)
        assertEquals(0L, second.start)
        assertEquals(5 * hour, second.ms)
    }

    @Test
    fun sharedNetworksAreTheSamePlaceEvenWithAPoorFix() {
        val nets = setOf("a", "b", "c")
        val (first, _) = PlaceMath.step(null, reading(0, gym, nets = nets))
        // A rough reading 2km off, but the same networks around
        val (second, visit) = PlaceMath.step(first, reading(30 * minute, 51.518 to -0.1, accuracy = 900f, nets = setOf("b", "c")))
        assertNull(visit)
        assertEquals(0L, second.start)
        // and it barely moves the middle
        assertTrue(PlaceMath.distance(second.lat, second.lon, gym.first, gym.second) < 20)
    }

    @Test
    fun suggestsAPlaceVisitedOnThreeDays() {
        val visits = (0 until 3).map { d -> Visit(d * day + 9 * hour, d * day + 10 * hour, gym.first, gym.second, emptySet()) } +
            Visit(10 * hour, 11 * hour, cafe.first, cafe.second, emptySet())
        val suggested = PlaceMath.suggestions(visits, emptyList(), emptyList(), ZoneOffset.UTC)
        assertEquals(1, suggested.size)
        assertEquals(3, suggested[0].visits.size)
        assertEquals(3 * hour, suggested[0].ms)
    }

    @Test
    fun threeVisitsOnOneDayAreNotEnough() {
        val visits = (0 until 3).map { i -> Visit(i * 2 * hour, i * 2 * hour + hour, gym.first, gym.second, emptySet()) }
        assertTrue(PlaceMath.suggestions(visits, emptyList(), emptyList(), ZoneOffset.UTC).isEmpty())
    }

    @Test
    fun knownAndDismissedPlacesAreNotSuggested() {
        val visits = (0 until 3).map { d -> Visit(d * day, d * day + hour, gym.first, gym.second, emptySet()) }
        val home = Spot(gym.first + 0.0005, gym.second, emptySet())
        assertTrue(PlaceMath.suggestions(visits, listOf(home), emptyList(), ZoneOffset.UTC).isEmpty())
        assertTrue(PlaceMath.suggestions(visits, emptyList(), listOf(home), ZoneOffset.UTC).isEmpty())
        assertFalse(PlaceMath.suggestions(visits, listOf(Spot(cafe.first, cafe.second, emptySet())), emptyList(), ZoneOffset.UTC).isEmpty())
    }

    @Test
    fun paintingMarksOnlyTheTimeOut() {
        val changes = listOf(0L to 0, 10L to Social.OUT, 50L to 1)
        // A visit from 20 to 40, inside the time out
        assertEquals(listOf(0L to 0, 10L to Social.OUT, 20L to 12, 40L to Social.OUT, 50L to 1), PlaceMath.paint(changes, 20, 40, Social.OUT, 12))
        // A visit running past getting home stops at home
        assertEquals(listOf(0L to 0, 10L to Social.OUT, 30L to 12, 50L to 1), PlaceMath.paint(changes, 30, 70, Social.OUT, 12))
        // Still there now
        assertEquals(listOf(0L to 0, 10L to Social.OUT, 20L to 12), PlaceMath.paint(listOf(0L to 0, 10L to Social.OUT), 20, Long.MAX_VALUE, Social.OUT, 12))
    }

    @Test
    fun paintingBackUndoesIt() {
        val changes = listOf(0L to 0, 10L to Social.OUT, 20L to 12, 40L to Social.OUT, 50L to 1)
        assertEquals(listOf(0L to 0, 10L to Social.OUT, 50L to 1), PlaceMath.paint(changes, 0, Long.MAX_VALUE, 12, Social.OUT))
    }
}
