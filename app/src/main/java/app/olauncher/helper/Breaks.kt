package app.olauncher.helper

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Short breaks from lockdown: for a few minutes, nothing lockdown blocks is blocked. How long they
 * are, how often and when they can be taken, and what has to be done to take one, are all set here.
 * Breaks are shared between the devices (one break, counted once, lifts them all) or each
 * device has its own.
 *
 * The rules are plain functions of the settings, the log of breaks and the time, so they're the same
 * everywhere and can be tested.
 */
object Breaks {

    const val PHONE = "phone"
    const val MAC = "mac"
    const val TV = "tv"

    data class Config(
        /** How long a break is; 0 is no breaks */
        val minutes: Int = 0,
        /** Minutes after a break ends before another can start */
        val cooldown: Int = 0,
        /** A break taken during a break adds to it; otherwise one has to end first */
        val stack: Boolean = false,
        /** A break's time is added to the end of lockdown, so it ends that much later */
        val makeUp: Boolean = false,
        /** At most this many breaks in an hour, a day, a week (Monday to Sunday); 0 is no limit */
        val perHour: Int = 0,
        val perDay: Int = 0,
        val perWeek: Int = 0,
        /** When breaks can't be taken */
        val off: List<Lockdown.Schedule> = emptyList(),
        /** One break for both devices, counted once; otherwise each device has its own */
        val shared: Boolean = true,
        /** What has to be done to take one */
        val challenge: Challenge = Challenge(Challenge.Type.NONE),
    ) {
        val on get() = minutes > 0

        fun encode(): String = JSONObject()
            .put("minutes", minutes).put("cooldown", cooldown).put("stack", stack).put("makeUp", makeUp)
            .put("perHour", perHour).put("perDay", perDay).put("perWeek", perWeek)
            .put("off", Lockdown.formatSchedules(off)).put("shared", shared).put("challenge", challenge.encode())
            .toString()

        /**
         * Whether going from this to [new] makes breaks easier to get, so it needs lockdown's unlock:
         * longer, sooner, more of them, fewer times off, kept apart from the other device, or easier to ask for.
         */
        fun loosensTo(new: Config, phraseLength: Int = 40): Boolean {
            fun limitLoosens(old: Int, value: Int) = old > 0 && (value <= 0 || value > old)
            return (new.minutes > minutes) ||
                new.cooldown < cooldown ||
                (new.stack && !stack) ||
                (!new.makeUp && makeUp) ||
                limitLoosens(perHour, new.perHour) || limitLoosens(perDay, new.perDay) || limitLoosens(perWeek, new.perWeek) ||
                off.any { it !in new.off } ||
                (!new.shared && shared) ||
                Challenge.loosens(challenge, new.challenge, phraseLength)
        }

        companion object {
            fun decode(text: String?): Config = runCatching {
                val o = JSONObject(text ?: return Config())
                Config(
                    minutes = o.optInt("minutes", 0).coerceAtLeast(0),
                    cooldown = o.optInt("cooldown", 0).coerceAtLeast(0),
                    stack = o.optBoolean("stack", false),
                    makeUp = o.optBoolean("makeUp", false),
                    perHour = o.optInt("perHour", 0).coerceAtLeast(0),
                    perDay = o.optInt("perDay", 0).coerceAtLeast(0),
                    perWeek = o.optInt("perWeek", 0).coerceAtLeast(0),
                    off = Lockdown.parseSchedules(o.optString("off")),
                    shared = o.optBoolean("shared", true),
                    challenge = Challenge.decode(o.optString("challenge")) ?: Challenge(Challenge.Type.NONE),
                )
            }.getOrDefault(Config())
        }
    }

    /** One break: when it started and ended (wall clock; the end moves if it's ended early), and on which device */
    data class Entry(val id: String, val start: Long, val end: Long, val device: String)

    fun encodeLog(log: List<Entry>): String = JSONArray().apply {
        log.forEach { put(JSONObject().put("id", it.id).put("start", it.start).put("end", it.end).put("device", it.device)) }
    }.toString()

    fun decodeLog(text: String?): List<Entry> = runCatching {
        val a = JSONArray(text ?: return emptyList())
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Entry(it.optString("id"), it.getLong("start"), it.getLong("end"), it.optString("device", PHONE))
        }
    }.getOrDefault(emptyList())

    /** The log without breaks older than [keepMs], and one entry per id (the latest end) */
    fun trim(log: List<Entry>, now: Long, keepMs: Long = 9 * 24 * 60 * 60_000L): List<Entry> =
        log.filter { it.start > now - keepMs }.groupBy { it.id.ifEmpty { "${it.start}:${it.device}" } }
            .map { (_, same) -> same.maxByOrNull { it.end }!! }.sortedBy { it.start }

    /** Whether a break can be taken now, or why not and until when */
    sealed class Can {
        object Yes : Can()
        /** Breaks are turned off */
        object Off : Can()
        /** Lockdown isn't on, so there's nothing to take a break from */
        object NotLocked : Can()
        data class OnBreak(val until: Long) : Can()
        data class Cooldown(val until: Long) : Can()
        data class OffNow(val until: Long) : Can()
        /** [period]: "hour", "day" or "week" */
        data class Limit(val period: String, val count: Int, val until: Long) : Can()
    }

    /** The breaks that count against [device]'s limits: all of them when shared */
    fun counted(config: Config, log: List<Entry>, device: String) = if (config.shared) log else log.filter { it.device == device }

    /**
     * Whether [device] can take a break at [now] (wall clock, in [zone]), with lockdown on there
     * ([locked]) and its break, if any, running until [breakUntil].
     */
    fun check(config: Config, log: List<Entry>, now: Long, device: String, locked: Boolean, breakUntil: Long,
              zone: ZoneId = ZoneId.systemDefault()): Can {
        if (!config.on) return Can.Off
        if (!locked) return Can.NotLocked
        if (breakUntil > now && !config.stack) return Can.OnBreak(breakUntil)
        val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        config.off.firstOrNull { it.covers(local) }?.let { return Can.OffNow(epoch(it.endAfter(local), zone)) }
        val mine = counted(config, log, device).filter { it.start <= now }
        // The time between breaks runs from the end of the last one, once it's over
        if (config.cooldown > 0 && breakUntil <= now) {
            mine.maxOfOrNull { it.end }?.let { last ->
                val until = last + config.cooldown * 60_000L
                if (until > now) return Can.Cooldown(until)
            }
        }
        if (config.perHour > 0) {
            val starts = mine.map { it.start }.filter { it > now - HOUR }.sorted()
            if (starts.size >= config.perHour) return Can.Limit("hour", config.perHour, starts[starts.size - config.perHour] + HOUR)
        }
        if (config.perDay > 0) {
            val midnight = epoch(local.toLocalDate().atStartOfDay(), zone)
            if (mine.count { it.start >= midnight } >= config.perDay)
                return Can.Limit("day", config.perDay, epoch(local.toLocalDate().plusDays(1).atStartOfDay(), zone))
        }
        if (config.perWeek > 0) {
            val monday = local.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            if (mine.count { it.start >= epoch(monday.atStartOfDay(), zone) } >= config.perWeek)
                return Can.Limit("week", config.perWeek, epoch(monday.plusWeeks(1).atStartOfDay(), zone))
        }
        return Can.Yes
    }

    /** How many breaks [device] has had today, this week */
    fun countSince(config: Config, log: List<Entry>, device: String, since: Long) = counted(config, log, device).count { it.start >= since }

    /** When a break taken at [now] ends: added to the running one when they stack */
    fun newEnd(config: Config, now: Long, breakUntil: Long): Long =
        (if (config.stack && breakUntil > now) breakUntil else now) + config.minutes * 60_000L

    /** [log] with a new break, or the running one ended at [now] when it's ended early */
    fun started(log: List<Entry>, id: String, now: Long, end: Long, device: String) = log + Entry(id, now, end, device)

    fun ended(log: List<Entry>, now: Long, devices: Set<String>) = log.map { if (it.device in devices && it.end > now && it.start <= now) it.copy(end = now) else it }

    private const val HOUR = 60 * 60_000L

    private fun epoch(time: LocalDateTime, zone: ZoneId) = time.atZone(zone).toInstant().toEpochMilli()
}
