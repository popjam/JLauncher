package app.olauncher.helper

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import app.olauncher.data.MoodStore
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Sleep worked out from when the screen was on: each night's longest stretch with the phone left alone,
 * starting between [BED_FROM] and [BED_UNTIL] and at least [MIN_NIGHT_MS] long. Quick checks in the
 * night (up to [MAX_CHECK_MS] on, between long stretches off) don't end it but are taken off the time
 * asleep; the screen lighting up for under [GLANCE_MS] is ignored. Kept on the phone by the date woken
 * up, and filled into the Sleep stat. Android keeps a week or so of screen events, so the last few
 * nights are read again on each refresh.
 */
object Sleep {

    /**
     * [lateMs] is the phone's screen time from [LATE_HOUR] until falling asleep, and [morningMs] its time
     * in the first [MORNING_MS] after waking.
     */
    class Night(val start: Long, val end: Long, val asleepMs: Long, val checks: Int, val lateMs: Long = 0, val morningMs: Long = 0) {
        val bedtime: LocalDateTime get() = time(start)
        val wake: LocalDateTime get() = time(end)

        private fun time(ms: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
    }

    /** By the date woken up */
    fun nights(context: Context): Map<LocalDate, Night> = synchronized(LOCK) {
        val json = prefs(context).getString(KEY, null) ?: return emptyMap()
        val nights = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        return nights.keys().asSequence().mapNotNull { key ->
            val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@mapNotNull null
            val night = nights.getJSONObject(key)
            date to Night(night.getLong("start"), night.getLong("end"), night.getLong("asleep"), night.optInt("checks"),
                night.optLong("late"), night.optLong("morning"))
        }.toMap()
    }

    /**
     * Reads the last [NIGHTS_BACK] nights, adding the Sleep stat (in place of an old one) the first time.
     * At most every [REFRESH_MS]; on a background thread.
     */
    fun refresh(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(LAST_KEY, 0) < REFRESH_MS) return
        prefs.edit().putLong(LAST_KEY, now).apply()

        val usageStats = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val today = LocalDate.now()
        val found = (0..NIGHTS_BACK).mapNotNull { daysAgo ->
            val date = today.minusDays(daysAgo.toLong())
            runCatching { findNight(usageStats, date, now) }.getOrNull()?.let { date to it }
        }
        if (found.isEmpty()) return

        val id = AutoStats.ensure(context, AutoStats.SLEEP, NAME, replaces = { it.name.contains(NAME, ignoreCase = true) }) { store ->
            store.addStat(NAME, COLOR, daily = false, scale = 5, question = "", labels = LABELS, inverted = false,
                phrases = emptyList(), medicine = false, nextDay = false)
        } ?: return
        val store = MoodStore(context)
        val saved = synchronized(LOCK) {
            val nights = nights(context).toMutableMap()
            found.forEach { (date, night) -> nights[date] = night }
            val cutoff = today.minusDays(KEEP_DAYS)
            nights.filterKeys { it >= cutoff }.also { save(context, it) }
        }
        found.forEach { (date, night) ->
            if (saved.containsKey(date)) store.setFace(date, id, faceFor(night.asleepMs))
        }
    }

    /** Under 5 hours is the saddest face, then a face an hour up to 8 hours and more. */
    fun faceFor(asleepMs: Long): Int {
        val hours = asleepMs / HOUR_MS.toDouble()
        return when {
            hours < 5 -> MoodStore.SAD
            hours < 6 -> MoodStore.MILDLY_SAD
            hours < 7 -> MoodStore.NEUTRAL
            hours < 8 -> MoodStore.MILDLY_HAPPY
            else -> MoodStore.HAPPY
        }
    }

    /** The night before the morning of [date], if it's over: there's been a screen-on after it. */
    private fun findNight(usageStats: UsageStatsManager, date: LocalDate, now: Long): Night? {
        val zone = ZoneId.systemDefault()
        val windowStart = date.minusDays(1).atTime(WINDOW_START_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
        val windowEnd = minOf(now, date.atTime(WINDOW_END_HOUR, 0).atZone(zone).toInstant().toEpochMilli())
        if (windowEnd <= windowStart) return null
        val bedFrom = date.minusDays(1).atTime(BED_FROM, 0).atZone(zone).toInstant().toEpochMilli()
        val bedUntil = date.atTime(BED_UNTIL, 0).atZone(zone).toInstant().toEpochMilli()

        // Screen-on stretches, as start and end
        val sessions = mutableListOf<LongArray>()
        val events = usageStats.queryEvents(windowStart - LOOK_BACK_MS, windowEnd)
        val event = UsageEvents.Event()
        var onSince: Long? = null
        var sawAny = false
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> {
                    if (onSince == null) onSince = event.timeStamp
                    sawAny = true
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    // On since before the events start
                    val from = onSince ?: if (!sawAny) windowStart - LOOK_BACK_MS else null
                    if (from != null) sessions.add(longArrayOf(from, event.timeStamp))
                    onSince = null
                    sawAny = true
                }
            }
        }
        onSince?.let { sessions.add(longArrayOf(it, windowEnd)) }
        // Only what's in the window, leaving out the screen just lighting up
        val real = sessions.filter { it[1] - it[0] >= GLANCE_MS && it[1] > windowStart && it[0] < windowEnd }
        if (real.size < 2) return null

        // The longest stretch off between two stretches on, starting at a bedtime
        var best = -1
        for (i in 0 until real.lastIndex) {
            val gapStart = real[i][1]
            val gap = real[i + 1][0] - gapStart
            if (gapStart !in bedFrom..bedUntil || gap < MIN_NIGHT_MS) continue
            if (best < 0 || gap > real[best + 1][0] - real[best][1]) best = i
        }
        if (best < 0) return null

        // Carried on through quick checks, on either side
        var first = best
        var last = best + 1
        var checks = 0
        var awake = 0L
        while (last < real.lastIndex) {
            val check = real[last]
            val after = real[last + 1][0] - check[1]
            if (check[1] - check[0] > MAX_CHECK_MS || after < MIN_REST_MS) break
            awake += check[1] - check[0]
            checks++
            last++
        }
        while (first > 0) {
            val check = real[first]
            val before = check[0] - real[first - 1][1]
            if (check[1] - check[0] > MAX_CHECK_MS || before < MIN_REST_BEFORE_MS || real[first - 1][1] < bedFrom) break
            awake += check[1] - check[0]
            checks++
            first--
        }
        val start = real[first][1]
        val end = real[last][0]
        fun onBetween(from: Long, to: Long) = real.sumOf { maxOf(0L, minOf(it[1], to) - maxOf(it[0], from)) }
        val lateFrom = date.minusDays(1).atTime(LATE_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
        return Night(start, end, end - start - awake, checks, onBetween(lateFrom, start), onBetween(end, end + MORNING_MS))
    }

    private fun save(context: Context, nights: Map<LocalDate, Night>) {
        val json = JSONObject()
        nights.forEach { (date, night) ->
            json.put(date.toString(), JSONObject().put("start", night.start).put("end", night.end)
                .put("asleep", night.asleepMs).put("checks", night.checks).put("late", night.lateMs).put("morning", night.morningMs))
        }
        prefs(context).edit().putString(KEY, json.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    const val NAME = "Sleep"
    private val COLOR = 0xFF7986CB.toInt()
    private val LABELS = listOf("Under 5h", "5–6h", "6–7h", "7–8h", "8h+")

    private val LOCK = Any()
    private const val PREFS = "sleep"
    private const val KEY = "nights"
    private const val LAST_KEY = "refreshed"
    private const val HOUR_MS = 60 * 60 * 1000L
    private const val REFRESH_MS = 15 * 60 * 1000L
    private const val NIGHTS_BACK = 6
    private const val KEEP_DAYS = 400L
    private const val WINDOW_START_HOUR = 18
    private const val WINDOW_END_HOUR = 15
    private const val BED_FROM = 19
    private const val BED_UNTIL = 7
    private const val LOOK_BACK_MS = 6 * HOUR_MS
    private const val MIN_NIGHT_MS = 3 * HOUR_MS
    private const val GLANCE_MS = 20 * 1000L
    private const val MAX_CHECK_MS = 15 * 60 * 1000L
    private const val MIN_REST_MS = 30 * 60 * 1000L
    private const val MIN_REST_BEFORE_MS = 90 * 60 * 1000L
    const val LATE_HOUR = 23
    const val MORNING_MS = 60 * 60 * 1000L
}
