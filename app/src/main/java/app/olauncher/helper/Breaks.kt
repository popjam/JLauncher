package app.olauncher.helper

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Breaks from lockdown: for a while, nothing lockdown blocks is blocked. Break time is earned into a
 * pool: [Config.minutes] for every [Config.every] minutes of lockdown, not counting breaks or the times
 * breaks can't be taken. A break uses the whole pool, and ending it early puts what's left back, so
 * another can be taken straight away. The pool can be capped and emptied at midnight. Breaks are
 * shared between the devices: one break lifts them all. Automatic breaks start by themselves at set
 * times during any lockdown and don't use the pool.
 *
 * The rules are plain functions of the settings, the pool and the time, so they're the same
 * everywhere and can be tested.
 */
object Breaks {

    const val PHONE = "phone"
    const val MAC = "mac"
    const val TV = "tv"

    /** The least the pool needs for a break */
    const val MIN_BREAK_MS = 60_000L

    data class Config(
        /** Minutes of break earned each [every] minutes of lockdown; 0 is no breaks */
        val minutes: Int = 0,
        /** Minutes of lockdown that earn [minutes] */
        val every: Int = 60,
        /** The most the pool holds, in minutes; 0 is no cap */
        val cap: Int = 0,
        /** The pool empties at midnight */
        val reset: Boolean = false,
        /** The time a break is used is added to the end of lockdown */
        val makeUp: Boolean = false,
        /** When breaks can't be taken, and the pool doesn't fill */
        val off: List<Lockdown.Schedule> = emptyList(),
        /** What has to be done to take one */
        val challenge: Challenge = Challenge(Challenge.Type.NONE),
        /** Automatic breaks: [autoMinutes] long, starting every [autoEvery] minutes of lockdown; 0 for none */
        val autoMinutes: Int = 0,
        val autoEvery: Int = 0,
    ) {
        val on get() = minutes > 0

        /** Whether automatic breaks are set; they don't need manual breaks to be on */
        val autoOn get() = autoMinutes > 0 && autoEvery > autoMinutes

        // "shared" stays in what's written, always on, for the Mac app, which reads it
        fun encode(): String = JSONObject()
            .put("minutes", minutes).put("every", every).put("cap", cap).put("reset", reset).put("makeUp", makeUp)
            .put("off", Lockdown.formatSchedules(off)).put("shared", true).put("challenge", challenge.encode())
            .put("autoMinutes", autoMinutes).put("autoEvery", autoEvery)
            .toString()

        /**
         * Whether going from this to [new] makes breaks easier to get, so it needs lockdown's unlock:
         * more earned or more often, a higher cap or none, no emptying at midnight, no making up, fewer
         * times off, easier to ask for, or automatic ones longer or more often.
         */
        fun loosensTo(new: Config, phraseLength: Int = 40): Boolean {
            val autoLoosens = new.autoOn && (!autoOn || new.autoMinutes > autoMinutes || new.autoEvery < autoEvery)
            val capLoosens = cap > 0 && (new.cap <= 0 || new.cap > cap)
            return (new.minutes > minutes) ||
                (new.on && new.every < every) ||
                capLoosens ||
                (!new.reset && reset) ||
                (!new.makeUp && makeUp) ||
                Lockdown.coverageLost(off, new.off) ||
                Challenge.loosens(challenge, new.challenge, phraseLength) ||
                autoLoosens
        }

        companion object {
            fun decode(text: String?): Config = runCatching {
                val o = JSONObject(text ?: return Config())
                Config(
                    minutes = o.optInt("minutes", 0).coerceAtLeast(0),
                    every = o.optInt("every", 60).coerceAtLeast(1),
                    cap = o.optInt("cap", 0).coerceAtLeast(0),
                    reset = o.optBoolean("reset", false),
                    makeUp = o.optBoolean("makeUp", false),
                    off = Lockdown.parseSchedules(o.optString("off")),
                    challenge = Challenge.decode(o.optString("challenge")) ?: Challenge(Challenge.Type.NONE),
                    autoMinutes = o.optInt("autoMinutes", 0).coerceAtLeast(0),
                    autoEvery = o.optInt("autoEvery", 0).coerceAtLeast(0),
                )
            }.getOrDefault(Config())
        }
    }

    // --- The pool ---

    /**
     * The break time saved up ([banked], ms), the lockdown counted towards the next lot ([progress], ms),
     * and when it was last brought up to date ([at], wall clock) and whether it was earning then
     */
    data class Pool(val banked: Long = 0L, val progress: Long = 0L, val at: Long = 0L, val earning: Boolean = false) {
        fun encode(): String = JSONObject().put("banked", banked).put("progress", progress).put("at", at).put("earning", earning).toString()

        companion object {
            fun decode(text: String?): Pool = runCatching {
                val o = JSONObject(text ?: return Pool())
                Pool(o.optLong("banked").coerceAtLeast(0L), o.optLong("progress").coerceAtLeast(0L), o.optLong("at"), o.optBoolean("earning"))
            }.getOrDefault(Pool())
        }
    }

    /** Past this long between looks, time only counts when it was earning at both ends */
    const val POOL_GAP_MS = 10 * 60_000L

    /**
     * [pool] brought up to [now]: the time since it was last brought up to date earns when it was
     * earning ([earningNow]: lockdown on and not on a break), less the times breaks can't be taken and
     * the automatic breaks of a lockdown that started at [runStart]. With [Config.reset] it empties at
     * each midnight passed.
     */
    fun accrue(config: Config, pool: Pool, now: Long, earningNow: Boolean, runStart: Long = 0L, zone: ZoneId = ZoneId.systemDefault()): Pool {
        val from = pool.at
        if (from <= 0L || now <= from) return pool.copy(at = now, earning = earningNow)
        var p = pool
        var start = from
        if (config.reset) {
            val midnight = epoch(LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone).toLocalDate().atStartOfDay(), zone)
            if (from < midnight) {
                p = p.copy(banked = 0L, progress = 0L)
                start = midnight
            }
        }
        val counts = if (now - from <= POOL_GAP_MS) earningNow else earningNow && pool.earning
        if (counts && config.on && now > start) {
            val skipped = offWindows(config, start, now, zone) + autoWindows(config, runStart, start, now, zone)
            p = credit(config, p, now - start - covered(skipped, start, now))
        }
        return p.copy(at = now, earning = earningNow)
    }

    /** [pool] with [ms] more lockdown counted: each [Config.every] adds [Config.minutes], up to the cap */
    fun credit(config: Config, pool: Pool, ms: Long): Pool {
        if (!config.on || ms <= 0L) return pool
        val cap = config.cap * 60_000L
        if (cap > 0L && pool.banked >= cap) return pool
        val every = config.every * 60_000L
        val total = pool.progress + ms
        val banked = pool.banked + (total / every) * config.minutes * 60_000L
        return if (cap > 0L && banked >= cap) pool.copy(banked = cap, progress = 0L)
            else pool.copy(banked = banked, progress = total % every)
    }

    /** When the pool next gets more, if it keeps earning from [now], or null when it's full or breaks are off */
    fun nextEarn(config: Config, pool: Pool, now: Long): Long? {
        if (!config.on) return null
        if (config.cap > 0 && pool.banked >= config.cap * 60_000L) return null
        return now + (config.every * 60_000L - pool.progress).coerceAtLeast(0L)
    }

    /**
     * How long a break taken at [now] with [banked] in the pool runs: the whole pool, but no further
     * than when lockdown ends ([lockdownEnd], if known), breaks' next time off, or midnight when the pool
     * empties then. What it doesn't use stays in the pool.
     */
    fun breakLength(config: Config, banked: Long, now: Long, lockdownEnd: Long? = null, zone: ZoneId = ZoneId.systemDefault()): Long {
        val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val limits = listOfNotNull(
            now + banked,
            lockdownEnd?.takeIf { it > now },
            config.off.mapNotNull { it.nextStart(local) }.minOrNull()?.let { epoch(it, zone) },
            if (config.reset) epoch(local.toLocalDate().plusDays(1).atStartOfDay(), zone) else null,
        )
        return (limits.minOrNull()!! - now).coerceAtLeast(0L)
    }

    /** Breaks' times off that overlap [from] to [to], as start to end */
    private fun offWindows(config: Config, from: Long, to: Long, zone: ZoneId): List<Pair<Long, Long>> {
        if (config.off.isEmpty()) return emptyList()
        val first = LocalDateTime.ofInstant(Instant.ofEpochMilli(from), zone).toLocalDate()
        val last = LocalDateTime.ofInstant(Instant.ofEpochMilli(to), zone).toLocalDate()
        return config.off.flatMap { it.windows(first, last) }.map { epoch(it.start, zone) to epoch(it.end, zone) }
    }

    /** How much of [from] to [to] the [windows] cover, overlaps counted once */
    fun covered(windows: List<Pair<Long, Long>>, from: Long, to: Long): Long {
        var total = 0L
        var reached = from
        windows.map { maxOf(it.first, from) to minOf(it.second, to) }.filter { it.second > it.first }.sortedBy { it.first }.forEach { (s, e) ->
            val begin = maxOf(s, reached)
            if (e > begin) {
                total += e - begin
                reached = e
            }
        }
        return total
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
        data class OffNow(val until: Long) : Can()
        /** Under a minute in the pool; [next] is when it next gets more, if known */
        data class Empty(val next: Long?) : Can()
    }

    /**
     * Whether a break can be taken at [now] (wall clock, in [zone]), with lockdown on ([locked]), [pool]
     * saved up, and a break, if any, running until [breakUntil]
     */
    fun check(config: Config, pool: Pool, now: Long, locked: Boolean, breakUntil: Long,
              zone: ZoneId = ZoneId.systemDefault()): Can {
        if (!config.on) return Can.Off
        if (!locked) return Can.NotLocked
        if (breakUntil > now) return Can.OnBreak(breakUntil)
        val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        config.off.firstOrNull { it.covers(local) }?.let { return Can.OffNow(epoch(it.endAfter(local), zone)) }
        if (pool.banked < MIN_BREAK_MS) return Can.Empty(nextEarn(config, pool, now))
        return Can.Yes
    }

    // --- Automatic breaks ---

    /**
     * The automatic break running at [now] in a lockdown that started at [runStart] (wall clock), as
     * start to end, or null: one starts every [Config.autoEvery] minutes after the lockdown started and
     * lasts [Config.autoMinutes]. One that would start during "No breaks during" ([Config.off]) is skipped.
     */
    fun autoWindow(config: Config, runStart: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long>? {
        if (!config.autoOn || runStart <= 0L || now < runStart) return null
        val every = config.autoEvery * 60_000L
        val n = (now - runStart) / every
        if (n < 1) return null
        val start = runStart + n * every
        val end = start + config.autoMinutes * 60_000L
        return if (now < end && !offAt(config, start, zone)) start to end else null
    }

    /** When the next automatic break starts after [now], in a lockdown that started at [runStart], or null; skips ones during [Config.off] */
    fun nextAuto(config: Config, runStart: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (!config.autoOn || runStart <= 0L) return null
        val every = config.autoEvery * 60_000L
        val first = ((now - runStart).coerceAtLeast(0) / every) + 1
        // A week of them at most: times off can't cover every start for longer than that
        val limit = first + 7 * 24 * 60 / config.autoEvery + 1
        return (first..limit).asSequence().map { runStart + it * every }.firstOrNull { !offAt(config, it, zone) }
    }

    /**
     * The automatic breaks in a lockdown that started at [runStart] that overlap [from] to [to], as start
     * to end, the ones during [Config.off] left out
     */
    fun autoWindows(config: Config, runStart: Long, from: Long, to: Long, zone: ZoneId = ZoneId.systemDefault()): List<Pair<Long, Long>> {
        if (!config.autoOn || runStart <= 0L || to <= from) return emptyList()
        val every = config.autoEvery * 60_000L
        val length = config.autoMinutes * 60_000L
        val first = maxOf(1L, (from - runStart - length) / every)
        return generateSequence(first) { it + 1 }.map { runStart + it * every }.takeWhile { it < to }
            .filter { it + length > from && !offAt(config, it, zone) }.map { it to it + length }.toList()
    }

    /** Whether breaks can't be taken at [time]: an automatic one starting then is skipped */
    private fun offAt(config: Config, time: Long, zone: ZoneId) =
        config.off.isNotEmpty() && LocalDateTime.ofInstant(Instant.ofEpochMilli(time), zone).let { local -> config.off.any { it.covers(local) } }

    /** [log] with a new break, or the running one ended at [now] when it's ended early */
    fun started(log: List<Entry>, id: String, now: Long, end: Long, device: String) = log + Entry(id, now, end, device)

    fun ended(log: List<Entry>, now: Long, devices: Set<String>) = log.map { if (it.device in devices && it.end > now && it.start <= now) it.copy(end = now) else it }

    private fun epoch(time: LocalDateTime, zone: ZoneId) = time.atZone(zone).toInstant().toEpochMilli()
}
