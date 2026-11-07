package app.olauncher.helper

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The working out behind [Places], with nothing from Android in it so it can be tested: readings turned
 * into stays, stays grouped into places, which places are worth suggesting, and a stretch of the Social
 * history marked as one of them.
 */
object PlaceMath {

    /** One reading: when, roughly where (with how sure, in metres), and the nearby Wi-Fi networks seen */
    class Reading(val time: Long, val lat: Double, val lon: Double, val accuracy: Float, val nets: Set<String>)

    /** A spot: its middle and the networks seen there */
    open class Spot(val lat: Double, val lon: Double, val nets: Set<String>)

    /** Time spent at one spot, [start] to [end] */
    class Visit(val start: Long, val end: Long, lat: Double, lon: Double, nets: Set<String>) : Spot(lat, lon, nets) {
        val ms get() = end - start
    }

    /**
     * The stay going on now: since [start], last seen at [last], its middle worked out from every reading so
     * far ([weight] is how much they count together), and the networks seen.
     */
    class Stay(val start: Long, val last: Long, lat: Double, lon: Double, val weight: Double, nets: Set<String>) : Spot(lat, lon, nets) {
        val ms get() = last - start
    }

    /** A group of visits to the same place */
    class Group(val visits: List<Visit>, lat: Double, lon: Double, nets: Set<String>) : Spot(lat, lon, nets) {
        val ms get() = visits.sumOf { it.ms }
        fun days(zone: ZoneId) = visits.map { Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() }.toSet()
    }

    /** Metres between two points */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /**
     * Whether [a] and [b] are the same place: [SHARED_NETS] networks in common, or within [NEAR_M] (more
     * when the reading's unsure, up to [MAX_ACCURACY_M]).
     */
    fun same(a: Spot, b: Spot, accuracy: Float = 0f): Boolean {
        if ((a.nets intersect b.nets).size >= SHARED_NETS) return true
        return distance(a.lat, a.lon, b.lat, b.lon) <= maxOf(NEAR_M, minOf(accuracy.toDouble(), MAX_ACCURACY_M))
    }

    /** Whether a reading is worth anything: close enough to place, or with networks to go by */
    fun usable(reading: Reading) = reading.accuracy <= MAX_ACCURACY_M || reading.nets.isNotEmpty()

    /**
     * The stay after [reading]: the same one carried on if it's the same place, else a new one starting
     * there, with the one before as a finished [Visit] if it lasted [MIN_VISIT_MS] or more.
     */
    fun step(stay: Stay?, reading: Reading): Pair<Stay, Visit?> {
        val spot = Spot(reading.lat, reading.lon, reading.nets)
        // Sure readings count for more in the middle; unsure ones barely move it
        val w = 1.0 / maxOf(reading.accuracy.toDouble(), 10.0).let { it * it }
        if (stay != null && same(stay, spot, reading.accuracy)) {
            val total = stay.weight + w
            return Stay(stay.start, reading.time, (stay.lat * stay.weight + reading.lat * w) / total,
                (stay.lon * stay.weight + reading.lon * w) / total, total, (stay.nets + reading.nets).take(MAX_NETS).toSet()) to null
        }
        val visit = stay?.takeIf { it.ms >= MIN_VISIT_MS }?.let { Visit(it.start, it.last, it.lat, it.lon, it.nets) }
        return Stay(reading.time, reading.time, reading.lat, reading.lon, w, reading.nets) to visit
    }

    /** Visits grouped by place, each group's middle the average of its visits' */
    fun group(visits: List<Visit>): List<Group> {
        val groups = mutableListOf<MutableList<Visit>>()
        val spots = mutableListOf<Spot>()
        visits.sortedBy { it.start }.forEach { visit ->
            val i = spots.indexOfFirst { same(it, visit) }
            if (i < 0) {
                groups += mutableListOf(visit)
                spots += Spot(visit.lat, visit.lon, visit.nets)
            } else {
                groups[i] += visit
                val g = groups[i]
                spots[i] = Spot(g.map { it.lat }.average(), g.map { it.lon }.average(), (spots[i].nets + visit.nets).take(MAX_NETS * 3).toSet())
            }
        }
        return groups.indices.map { Group(groups[it], spots[it].lat, spots[it].lon, spots[it].nets) }
    }

    /**
     * Places worth suggesting: [MIN_DAYS] different days or more there, [MIN_TOTAL_MS] in all, and not
     * already [known] (homes, work, named places) or [dismissed]. Most time first.
     */
    fun suggestions(visits: List<Visit>, known: List<Spot>, dismissed: List<Spot>, zone: ZoneId): List<Group> =
        group(visits.filter { v -> known.none { same(it, v) } && dismissed.none { same(it, v) } })
            .filter { it.days(zone).size >= MIN_DAYS && it.ms >= MIN_TOTAL_MS }
            .sortedByDescending { it.ms }

    /**
     * [changes] (from each time, the place) with the time from [start] to [end] that was [only] made
     * [place] instead; the rest as it was.
     */
    fun paint(changes: List<Pair<Long, Int>>, start: Long, end: Long, only: Int, place: Int): List<Pair<Long, Int>> {
        if (changes.isEmpty() || end <= start) return changes
        fun at(time: Long): Int? = changes.lastOrNull { it.first <= time }?.second
        val times = (changes.map { it.first } + listOf(start, end).filter { it > changes.first().first && it != Long.MAX_VALUE }).distinct().sorted()
        val painted = times.mapNotNull { time ->
            val was = at(time) ?: return@mapNotNull null
            time to if (time in start until end && was == only) place else was
        }
        // Each change only when the place does change
        return painted.filterIndexed { i, (_, p) -> i == 0 || painted[i - 1].second != p }
    }

    /** Days a place counted as gone to, for a day's time there */
    fun wentTo(ms: Long) = ms >= WENT_MS

    fun dayOf(time: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()

    const val NEAR_M = 150.0
    const val MAX_ACCURACY_M = 500.0
    const val SHARED_NETS = 2
    const val MAX_NETS = 12
    const val MIN_VISIT_MS = 20 * 60_000L
    const val MIN_DAYS = 3
    const val MIN_TOTAL_MS = 60 * 60_000L
    const val WENT_MS = 30 * 60_000L
}
