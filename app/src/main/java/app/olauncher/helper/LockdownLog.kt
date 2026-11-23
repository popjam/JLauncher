package app.olauncher.helper

import android.content.Context
import android.os.SystemClock
import app.olauncher.data.Prefs
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * How long each device spent in lockdown each day, how long the phone was on a break, and when in
 * the day the phone was locked. Kept by looking at lockdown every half minute or so while the phone
 * runs (the accessibility service's checks), adding the time since the last look while it's on.
 */
object LockdownLog {

    /** One day: ms in lockdown on the phone, the Mac and the TV, ms on a break, and the phone's locked minutes ([ScreenSpans]) */
    data class Day(val phoneMs: Long = 0, val macMs: Long = 0, val breakMs: Long = 0, val spans: List<IntRange> = emptyList(), val tvMs: Long = 0) {
        fun json(): JSONObject = JSONObject().put("p", phoneMs).put("m", macMs).put("b", breakMs).put("s", ScreenSpans.encode(spans)).put("t", tvMs)
    }

    fun decode(text: String?): Map<LocalDate, Day> = runCatching {
        val o = JSONObject(text ?: return emptyMap())
        o.keys().asSequence().mapNotNull { key ->
            val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@mapNotNull null
            val d = o.getJSONObject(key)
            date to Day(d.optLong("p"), d.optLong("m"), d.optLong("b"), ScreenSpans.decode(d.optString("s")), d.optLong("t"))
        }.toMap()
    }.getOrDefault(emptyMap())

    fun encode(days: Map<LocalDate, Day>): String = JSONObject().apply { days.forEach { (date, day) -> put(date.toString(), day.json()) } }.toString()

    /** What was on over a stretch of time */
    data class State(val phone: Boolean, val mac: Boolean, val onBreak: Boolean, val tv: Boolean = false)

    /**
     * [days] with the time from [from] to [to] added to what [state] says was on, split at midnight.
     * The phone's time counts while it's on and not on a break; a break counts as break time.
     */
    fun credit(days: Map<LocalDate, Day>, from: LocalDateTime, to: LocalDateTime, state: State): Map<LocalDate, Day> {
        if (!to.isAfter(from)) return days
        val result = days.toMutableMap()
        var start = from
        while (start.isBefore(to)) {
            val midnight = start.toLocalDate().plusDays(1).atStartOfDay()
            val end = if (to.isAfter(midnight)) midnight else to
            val ms = java.time.Duration.between(start, end).toMillis()
            val date = start.toLocalDate()
            val day = result[date] ?: Day()
            val phoneLocked = state.phone && !state.onBreak
            val startMinute = start.hour * 60 + start.minute
            val endMinute = if (end == midnight) ScreenSpans.DAY else end.hour * 60 + end.minute + (if (end.second > 0) 1 else 0)
            result[date] = day.copy(
                phoneMs = day.phoneMs + if (phoneLocked) ms else 0,
                macMs = day.macMs + if (state.mac) ms else 0,
                tvMs = day.tvMs + if (state.tv) ms else 0,
                breakMs = day.breakMs + if (state.phone && state.onBreak) ms else 0,
                spans = if (phoneLocked && endMinute > startMinute) join(day.spans, startMinute until endMinute) else day.spans,
            )
            start = end
        }
        return result
    }

    /** [spans] with [span] added, joined to any it touches */
    fun join(spans: List<IntRange>, span: IntRange): List<IntRange> {
        val all = (spans + listOf(span)).sortedBy { it.first }
        val out = mutableListOf<IntRange>()
        all.forEach { r ->
            val last = out.lastOrNull()
            if (last != null && r.first <= last.last + 1) out[out.lastIndex] = last.first..maxOf(last.last, r.last) else out.add(r)
        }
        return out
    }

    /** A gap between looks longer than this (the phone asleep) only counts if lockdown was on at both ends */
    private const val GAP_MS = 10 * 60_000L
    private const val EVERY_MS = 30_000L

    private var lastLook = 0L

    /** Looks at lockdown now, at most every half minute, adding the time since the last look */
    fun tick(context: Context, prefs: Prefs = Prefs(context)) {
        val elapsed = SystemClock.elapsedRealtime()
        if (lastLook != 0L && elapsed - lastLook < EVERY_MS) return
        lastLook = elapsed
        val store = context.getSharedPreferences("lockdown_log", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val state = State(Lockdown.isOn(context, prefs, Lockdown.Device.PHONE),
            Lockdown.macPaired(context) && Lockdown.isOn(context, prefs, Lockdown.Device.MAC), Lockdown.onBreak(prefs),
            Lockdown.tvPaired(context) && Lockdown.isOn(context, prefs, Lockdown.Device.TV))
        val code = (if (state.phone) 1 else 0) + (if (state.mac) 2 else 0) + (if (state.onBreak) 4 else 0) + (if (state.tv) 8 else 0)
        val lastAt = store.getLong("at", 0L)
        val lastCode = store.getInt("state", 0)
        store.edit().putLong("at", now).putInt("state", code).apply()
        if (lastAt <= 0L || now <= lastAt || code == 0 && lastCode == 0) return
        // After a long gap, only what was on at both ends is counted for all of it
        val gap = now - lastAt
        val counted = if (gap <= GAP_MS) state else State(state.phone && lastCode and 1 != 0, state.mac && lastCode and 2 != 0, state.onBreak && lastCode and 4 != 0, state.tv && lastCode and 8 != 0)
        val zone = ZoneId.systemDefault()
        val days = credit(decode(prefs.lockdownLog), Lockdown.localTime(lastAt, zone), Lockdown.localTime(now, zone), counted)
        val cutoff = LocalDate.now().minusDays(400)
        val kept = days.filterKeys { it > cutoff }
        prefs.lockdownLog = encode(kept)
        LockdownStats.update(context, kept)
    }

    fun days(prefs: Prefs) = decode(prefs.lockdownLog)
}
