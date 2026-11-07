package app.olauncher.helper

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** The day weeks start on, picked in Settings (Monday unless changed): for the spending limit, weekly stats and calendars. */
object Week {

    @Volatile var first: DayOfWeek = DayOfWeek.MONDAY
        private set

    /** Reads the day picked; called as the app and the notification listener start */
    fun load(context: Context) {
        first = prefs(context).getString(KEY, null)?.let { runCatching { DayOfWeek.valueOf(it) }.getOrNull() } ?: DayOfWeek.MONDAY
    }

    fun set(context: Context, day: DayOfWeek) {
        prefs(context).edit().putString(KEY, day.name).apply()
        first = day
    }

    /** The first day of [date]'s week */
    fun start(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(first))

    /** [date]'s column in a calendar, 0 for the first day of the week */
    fun column(date: LocalDate) = (date.dayOfWeek.value - first.value + 7) % 7

    private fun prefs(context: Context) = context.getSharedPreferences("week", Context.MODE_PRIVATE)
    private const val KEY = "first"
}
