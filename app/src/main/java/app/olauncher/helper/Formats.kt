package app.olauncher.helper

import android.content.Context
import android.text.format.DateFormat
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * How times and durations are written everywhere (except the Home screen's lockdown widget, which
 * keeps its own).
 *
 * - Times of day follow the phone's 24-hour setting: "21:30", "09:00"; otherwise "9:30 pm", with
 *   whole hours as "9 pm", and midnight and noon as "12 am" and "12 pm".
 * - Ranges join two times with an en dash: "11 pm–5 am", "23:00–05:00".
 * - Durations are "1h 5m", "1h", "45m", "<1m" (under a minute) and "0m" (none). They're always
 *   rounded down to the minute, so totals never add up to more than was used.
 */
object Formats {

    // --- Times of day ---

    /** [time] as "9:30 pm" / "9 pm", or "21:30" when [is24] */
    fun time(time: LocalTime, is24: Boolean): String {
        if (is24) return "%02d:%02d".format(time.hour, time.minute)
        val hour = if (time.hour % 12 == 0) 12 else time.hour % 12
        val suffix = if (time.hour < 12) "am" else "pm"
        return if (time.minute == 0) "$hour $suffix" else "%d:%02d %s".format(hour, time.minute, suffix)
    }

    /** "11 pm–5 am", or "23:00–05:00" when [is24] */
    fun timeRange(start: LocalTime, end: LocalTime, is24: Boolean) = time(start, is24) + "–" + time(end, is24)

    /** An hour on a chart's axis: "6 am", or "06" when [is24] */
    fun hour(hour: Int, is24: Boolean): String =
        if (is24) "%02d".format(Math.floorMod(hour, 24)) else time(LocalTime.of(Math.floorMod(hour, 24), 0), false)

    /** A time [minutes] after midnight */
    fun minutesOfDay(minutes: Int): LocalTime = LocalTime.of(Math.floorMod(minutes, 1440) / 60, Math.floorMod(minutes, 1440) % 60)

    fun is24(context: Context) = DateFormat.is24HourFormat(context)
    fun time(context: Context, time: LocalTime) = time(time, is24(context))
    fun time(context: Context, epochMs: Long) = time(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalTime(), is24(context))
    fun timeRange(context: Context, start: LocalTime, end: LocalTime) = timeRange(start, end, is24(context))
    fun hour(context: Context, hour: Int) = hour(hour, is24(context))

    // --- Durations ---

    /** "1h 5m", "1h", "45m", "<1m", or "0m" for none; rounded down to the minute */
    fun duration(ms: Long): String {
        if (ms <= 0L) return "0m"
        val minutes = ms / 60_000L
        if (minutes == 0L) return "<1m"
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0L -> "${rest}m"
            rest == 0L -> "${hours}h"
            else -> "${hours}h ${rest}m"
        }
    }

    /** [minutes] as a duration: "1h 30m" */
    fun minutes(minutes: Int) = duration(minutes * 60_000L)
}
