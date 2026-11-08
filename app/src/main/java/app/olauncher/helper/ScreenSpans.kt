package app.olauncher.helper

/**
 * When in a day a screen was in use, as minutes from midnight, each span from its first minute up to
 * (not including) its end. Kept as text like "480-510,600-605": the phone's in [PhoneHabits], the
 * Mac's and TV's in their files ([DeviceTime]).
 */
object ScreenSpans {

    /** A stretch of the day with the same screens in use: from [start] up to [end], minutes from midnight */
    data class Segment(val start: Int, val end: Int, val devices: Set<String>)

    fun encode(spans: List<IntRange>): String = spans.joinToString(",") { "${it.first}-${it.last + 1}" }

    fun decode(text: String?): List<IntRange> = text.orEmpty().split(",").mapNotNull { part ->
        val bits = part.trim().split("-")
        val start = bits.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
        val end = bits.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
        if (end <= start) null else start.coerceIn(0, DAY) until end.coerceIn(0, DAY)
    }.filter { !it.isEmpty() }

    /** Spans from (start, end) times in ms since the day's start: whole minutes, and gaps of under [joinMinutes] joined. */
    fun fromMs(times: List<Pair<Long, Long>>, joinMinutes: Int = 1): List<IntRange> {
        val minutes = times.filter { it.second > it.first }.map { (start, end) ->
            (start / 60_000L).toInt().coerceIn(0, DAY) to ((end + 59_999L) / 60_000L).toInt().coerceIn(0, DAY)
        }.filter { it.second > it.first }.sortedBy { it.first }
        val joined = mutableListOf<Pair<Int, Int>>()
        minutes.forEach { (start, end) ->
            val last = joined.lastOrNull()
            if (last != null && start - last.second <= joinMinutes) joined[joined.lastIndex] = last.first to maxOf(last.second, end)
            else joined.add(start to end)
        }
        return joined.map { it.first until it.second }
    }

    /** The day cut where the set of screens in use changes, leaving out the times none were. */
    fun segments(byDevice: Map<String, List<IntRange>>): List<Segment> {
        val used = Array(DAY) { mutableSetOf<String>() }
        byDevice.forEach { (device, spans) -> spans.forEach { span -> for (m in span) if (m in 0 until DAY) used[m].add(device) } }
        val segments = mutableListOf<Segment>()
        var m = 0
        while (m < DAY) {
            val devices = used[m]
            if (devices.isEmpty()) {
                m++
                continue
            }
            var end = m + 1
            while (end < DAY && used[end] == devices) end++
            segments.add(Segment(m, end, devices.toSet()))
            m = end
        }
        return segments
    }

    const val DAY = 1440
}
