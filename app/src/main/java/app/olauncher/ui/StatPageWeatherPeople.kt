package app.olauncher.ui

import android.Manifest
import android.graphics.RectF
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.helper.People
import app.olauncher.helper.Spending
import app.olauncher.helper.WeatherHistory
import app.olauncher.ui.CardPageFragment.Card
import app.olauncher.ui.StatPageFragment.Companion.HABIT_DAYS
import app.olauncher.ui.StatPageFragment.Companion.SLEEP_MIN_NIGHTS
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

// Weather and People: each day's sunshine and rain, and the people you were in touch with, as cards on [StatPageFragment]

/** Mood on each kind of day, side by side as bars: [names] for the groups, [groupOf] a day's group (or none) */
internal fun StatPageFragment.moodByCard(title: String, names: List<String>, dates: Collection<LocalDate>, groupOf: (LocalDate) -> Int?) {
    val mood = stats.firstOrNull { it.id == MoodStore.MOOD } ?: return
    val today = LocalDate.now()
    val past = dates.filter { it < today && valueOf(mood, it) != null }
    if (past.size < SLEEP_MIN_NIGHTS) return
    val card = Card(title)
    card.keyItem(mood.color, mood.name)
    val averages = names.indices.map { g ->
        past.filter { groupOf(it) == g }.mapNotNull { valueOf(mood, it) }
            .let { values -> values.takeIf { it.isNotEmpty() }?.average()?.toFloat() to values.size }
    }
    // Nothing to set side by side yet
    if (averages.count { it.first != null } < 2) return run { card.root.isVisible = false }
    card.describe = { key ->
        val (average, count) = averages[key as Int]
        listOf(names[key], getString(R.string.stat_total, mood.name, average?.let(::decimal) ?: "–"),
            resources.getQuantityString(R.plurals.day_count, count, count)).joinToString(" · ")
    }
    card.draw { s ->
        val top = s.dp(6f)
        val bottom = top + s.dp(120f)
        val column = s.width / names.size
        fun y(value: Float) = bottom - (value - 1f) / 4f * (bottom - top) - s.dp(2f)
        listOf(1, 3, 5).forEach { g -> s.line(0f, y(g.toFloat()), s.width, y(g.toFloat()), faint(28), s.dp(1f)) }
        val bar = minOf(column * 0.5f, s.dp(30f))
        averages.forEachIndexed { g, (average, _) ->
            val center = g * column + column / 2
            if (average == null) s.rect(center - bar / 2, bottom - s.dp(2f), center + bar / 2, bottom, faint(45), s.dp(1f))
            else s.rect(center - bar / 2, y(average), center + bar / 2, bottom, mood.faceColor(requireContext(), faceFor(mood, average)), s.dp(6f))
            s.text(names[g], center, bottom + s.dp(18f), faint(160), s.sp(12f))
            s.target(g, RectF(g * column, 0f, (g + 1) * column, bottom + s.dp(26f)))
        }
        s.height = bottom + s.dp(26f)
    }
}

// --- Weather ---

/** In the units the forecast uses: °C or °F */
private fun StatPageFragment.temp(celsius: Float): String {
    val fahrenheit = app.olauncher.helper.Weather.cached(requireContext())?.fahrenheit == true
    return "${(if (fahrenheit) celsius * 9 / 5 + 32 else celsius).roundToInt()}°"
}

private fun StatPageFragment.weatherDayText(date: LocalDate, day: WeatherHistory.Day) = listOf(
    dateText(date),
    getString(R.string.weather_sun_hours, hours(day.sunHours)),
    "${temp(day.max)} / ${temp(day.min)}",
    getString(R.string.weather_rain_mm, String.format(Locale.getDefault(), "%.1f", day.rain)),
).joinToString(" · ")

private fun hours(h: Float) = String.format(Locale.getDefault(), "%.1fh", h)

/** Today's sunshine, high and low, rain and daylight, and the last days' sunshine as bars */
internal fun StatPageFragment.weatherTodayCard(days: Map<LocalDate, WeatherHistory.Day>) {
    val today = LocalDate.now()
    val day = days[today] ?: days.maxByOrNull { it.key }?.value ?: return
    val card = Card(getString(R.string.mac_today))
    card.headline(getString(R.string.weather_sun_hours, hours(day.sunHours)), listOf(
        "${temp(day.max)} / ${temp(day.min)}",
        getString(R.string.weather_rain_mm, String.format(Locale.getDefault(), "%.1f", day.rain)),
        getString(R.string.weather_daylight, hours(day.daylightHours)),
    ).joinToString(" · "))
    dayBars(card, days.mapValues { it.value.sunHours }, stat.color) { date, _ -> weatherDayText(date, days.getValue(date)) }
}

/** Mood on sunny, mixed, grey and rainy days */
internal fun StatPageFragment.weatherMoodCard(days: Map<LocalDate, WeatherHistory.Day>) {
    moodByCard(getString(R.string.weather_and_mood),
        listOf(R.string.weather_group_sunny, R.string.weather_group_mixed, R.string.weather_group_grey, R.string.weather_group_rainy).map { getString(it) },
        days.keys) { date ->
        val day = days[date] ?: return@moodByCard null
        when {
            day.rainy -> 3
            day.sunHours >= 6f -> 0
            day.sunHours >= 2f -> 1
            else -> 2
        }
    }
}

/** Mood by how much daylight there was: short, middling and long days */
internal fun StatPageFragment.daylightMoodCard(days: Map<LocalDate, WeatherHistory.Day>) {
    val lengths = days.values.map { it.daylightHours }.sorted()
    if (lengths.size < 30 || lengths.last() - lengths.first() < 1.5f) return
    val low = lengths[lengths.size / 3]
    val high = lengths[lengths.size * 2 / 3]
    moodByCard(getString(R.string.weather_daylight_and_mood),
        listOf(getString(R.string.weather_daylight_short, hours(low)), getString(R.string.weather_daylight_middle), getString(R.string.weather_daylight_long, hours(high))),
        days.keys) { date ->
        val l = days[date]?.daylightHours ?: return@moodByCard null
        when {
            l < low -> 0
            l < high -> 1
            else -> 2
        }
    }
}

// --- People ---

private fun StatPageFragment.peopleDayText(date: LocalDate, day: People.Day) = listOfNotNull(
    dateText(date),
    resources.getQuantityString(R.plurals.people_count, day.people, day.people),
    resources.getQuantityString(R.plurals.people_calls, day.calls, day.calls).takeIf { day.calls > 0 },
    duration(day.talkMs).takeIf { day.talkMs > 0 },
    resources.getQuantityString(R.plurals.people_messages, day.messages, day.messages).takeIf { day.messages > 0 },
).joinToString(" · ")

/** Today's people, calls, time talking and messages; the last days as bars; and the usual */
internal fun StatPageFragment.peopleTodayCard(days: Map<LocalDate, People.Day>) {
    val context = requireContext()
    val card = Card(getString(R.string.mac_today))
    val today = LocalDate.now()
    val day = days[today]
    if (day == null) {
        card.showNote(getString(R.string.people_waiting))
    } else {
        card.headline(resources.getQuantityString(R.plurals.people_count, day.people, day.people), listOfNotNull(
            resources.getQuantityString(R.plurals.people_calls, day.calls, day.calls),
            duration(day.talkMs).takeIf { day.talkMs > 0 }?.let { getString(R.string.people_talking, it) },
            resources.getQuantityString(R.plurals.people_messages, day.messages, day.messages),
        ).joinToString(" · "))
        dayBars(card, days.mapValues { it.value.people.toFloat() }, stat.color) { date, _ -> peopleDayText(date, days.getValue(date)) }
    }
    // What it counts from, and what's missing
    val missing = listOfNotNull(
        getString(R.string.people_need_calls).takeIf { !People.hasCallLog(context) },
        getString(R.string.people_need_notifications).takeIf { !Spending.isEnabled(context) },
    )
    if (missing.isNotEmpty()) {
        if (!People.hasCallLog(context)) card.chipRow(listOf(getString(R.string.people_count_calls) to ink), null) {
            askCallLog.launch(Manifest.permission.READ_CALL_LOG)
        }
        if (day != null) card.note.apply {
            isVisible = true
            text = missing.joinToString("\n")
        }
    }
    val past = days.filterKeys { it < today && it >= today.minusDays(HABIT_DAYS.toLong()) }
    if (past.size < 2) return
    tiles(Card(getString(R.string.steps_usual_title)), getString(R.string.social_usual, past.size), listOf(
        String.format(Locale.getDefault(), "%.1f", past.values.map { it.people }.average()) to getString(R.string.people_tile_people),
        String.format(Locale.getDefault(), "%.1f", past.values.map { it.calls }.average()) to getString(R.string.people_tile_calls),
        past.values.count { it.people == 0 }.toString() to getString(R.string.people_tile_no_one),
    ))
}

/** Mood by how many people you were in touch with */
internal fun StatPageFragment.peopleMoodCard(days: Map<LocalDate, People.Day>) {
    moodByCard(getString(R.string.people_and_mood),
        listOf(R.string.people_group_none, R.string.people_group_one, R.string.people_group_few, R.string.people_group_many).map { getString(it) },
        days.keys) { date ->
        val p = days[date]?.people ?: return@moodByCard null
        when {
            p == 0 -> 0
            p == 1 -> 1
            p <= 3 -> 2
            else -> 3
        }
    }
}
