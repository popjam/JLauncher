package app.olauncher.ui

import android.Manifest
import android.graphics.RectF
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.helper.People
import app.olauncher.helper.Spending
import app.olauncher.helper.StatInfo
import app.olauncher.helper.WeatherHistory
import app.olauncher.ui.CardPageFragment.Card
import app.olauncher.ui.StatPageFragment.Companion.HABIT_DAYS
import app.olauncher.ui.StatPageFragment.Companion.SLEEP_MIN_NIGHTS
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

// Weather and People: each day's sunshine and rain, and the people you were in touch with, as cards on [StatPageFragment]

/** One way of putting days in groups for [moodByCard]: the groups' [names] and a day's group, or null for none */
internal class MoodGroups(val names: List<String>, val groupOf: (LocalDate) -> Int?)

/**
 * Mood vs something (the weather, messages, time out, sleep, music, vibecoding): Mood's average on
 * each kind of day, side by side as bars in Mood's colour for that average. [dates] are the days that
 * can count; [groupings] the ways of grouping them, picked by [choices] in the header when there's
 * more than one. [measured] are the stats averaged (Mood, and Energy on Sleep's), each bar marked
 * with its stat's icon when there's more than one. A group counts with [minDays] days or more, and
 * the card is only made when two groups count. [note] goes under the bars.
 */
internal fun StatPageFragment.moodByCard(title: String, dates: Collection<LocalDate>, groupings: List<MoodGroups>, choices: List<Int> = emptyList(),
                                         measured: List<StatInfo>? = null, minDays: Int = 1, note: String? = null) {
    val mood = stats.firstOrNull { it.id == MoodStore.MOOD } ?: return
    val shown = measured ?: listOf(mood)
    val today = LocalDate.now()
    val past = dates.filter { it < today && valueOf(mood, it) != null }
    if (past.size < SLEEP_MIN_NIGHTS) return
    // Per grouping, per group, per stat: the average (null with too few days) and how many days
    val all = groupings.map { grouping ->
        grouping.names.indices.map { g ->
            val days = past.filter { grouping.groupOf(it) == g }
            shown.map { s -> days.mapNotNull { valueOf(s, it) }.let { v -> v.takeIf { it.isNotEmpty() && it.size >= minDays }?.average()?.toFloat() to v.size } }
        }
    }
    fun enough(i: Int) = all[i].count { group -> group.first().first != null } >= 2
    // Nothing to set side by side yet: no card
    if (groupings.indices.none(::enough)) return
    val card = Card(title)
    val several = shown.size > 1
    shown.forEach { card.keyItem(it.color, it.name, icon = if (several) StatIcons.forStat(it) else null) }
    var picked = groupings.indices.first(::enough)
    fun draw() {
        if (choices.size > 1) card.segment(choices, picked) {
            picked = it
            draw()
        }
        if (!enough(picked)) return card.showNote(getString(R.string.needs_more_days))
        val names = groupings[picked].names
        val averages = all[picked]
        card.describe = { key ->
            val g = key as Int
            (listOf(names[g]) + shown.mapIndexed { i, s ->
                val (average, count) = averages[g][i]
                getString(R.string.stat_total, s.name, average?.let(::decimal) ?: "–") + " · " + resources.getQuantityString(R.plurals.day_count, count, count)
            }).joinToString("\n")
        }
        card.draw { s ->
            val top = s.dp(if (several) 20f else 6f)
            val bottom = top + s.dp(120f)
            val column = s.width / names.size
            fun y(value: Float) = bottom - (value - 1f) / 4f * (bottom - top) - s.dp(2f)
            listOf(1, 3, 5).forEach { g -> s.line(0f, y(g.toFloat()), s.width, y(g.toFloat()), faint(28), s.dp(1f)) }
            val bar = if (several) minOf(column / (shown.size + 1), s.dp(22f)) else minOf(column * 0.5f, s.dp(30f))
            val inset = if (several) s.dp(2f) else 0f
            names.indices.forEach { g ->
                val center = g * column + column / 2
                val start = center - bar * shown.size / 2
                shown.forEachIndexed { i, st ->
                    val (average, _) = averages[g][i]
                    val x = start + i * bar
                    if (average == null) s.rect(x + inset, bottom - s.dp(2f), x + bar - inset, bottom, faint(45), s.dp(1f))
                    else {
                        s.rect(x + inset, y(average), x + bar - inset, bottom, st.faceColor(requireContext(), faceFor(st, average)), s.dp(if (several) 5f else 6f))
                        if (several) {
                            val icon = minOf(bar - inset * 2, s.dp(12f))
                            s.icon(StatIcons.forStat(st), x + (bar - icon) / 2, y(average) - icon - s.dp(3f), icon, faint(170))
                        }
                    }
                }
                s.text(names[g], center, bottom + s.dp(18f), faint(160), s.sp(12f))
                s.target(g, RectF(g * column, 0f, (g + 1) * column, bottom + s.dp(26f)))
            }
            s.height = bottom + s.dp(26f)
        }
        // After drawing, which hides the note
        if (note != null) card.note.apply {
            isVisible = true
            text = note
        }
    }
    draw()
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

private fun hours(h: Float) = app.olauncher.helper.Formats.duration((h * 3_600_000L).toLong())

/** Today's sunshine, high and low, rain and daylight, and the last days' sunshine as bars */
internal fun StatPageFragment.weatherTodayCard(days: Map<LocalDate, WeatherHistory.Day>) {
    val today = LocalDate.now()
    val day = days[today] ?: days.maxByOrNull { it.key }?.value ?: return
    val card = Card(getString(R.string.weather_today))
    card.headline(getString(R.string.weather_sun_hours, hours(day.sunHours)), listOf(
        "${temp(day.max)} / ${temp(day.min)}",
        getString(R.string.weather_rain_mm, String.format(Locale.getDefault(), "%.1f", day.rain)),
        getString(R.string.weather_daylight, hours(day.daylightHours)),
    ).joinToString(" · "))
    dayBars(card, days.mapValues { it.value.sunHours }) { date, _ -> weatherDayText(date, days.getValue(date)) }
}

/** Mood on sunny, mixed, grey and rainy days */
internal fun StatPageFragment.weatherMoodCard(days: Map<LocalDate, WeatherHistory.Day>) {
    moodByCard(getString(R.string.weather_and_mood), days.keys, listOf(MoodGroups(
        listOf(R.string.weather_group_sunny, R.string.weather_group_mixed, R.string.weather_group_grey, R.string.weather_group_rainy).map { getString(it) }) { date ->
        val day = days[date] ?: return@MoodGroups null
        when {
            day.rainy -> 3
            day.sunHours >= 6f -> 0
            day.sunHours >= 2f -> 1
            else -> 2
        }
    }))
}

/** Mood by how much daylight there was: short, middling and long days */
internal fun StatPageFragment.daylightMoodCard(days: Map<LocalDate, WeatherHistory.Day>) {
    val lengths = days.values.map { it.daylightHours }.sorted()
    if (lengths.size < 30 || lengths.last() - lengths.first() < 1.5f) return
    val low = lengths[lengths.size / 3]
    val high = lengths[lengths.size * 2 / 3]
    moodByCard(getString(R.string.weather_daylight_and_mood), days.keys, listOf(MoodGroups(
        listOf(getString(R.string.weather_daylight_short, hours(low)), getString(R.string.weather_daylight_middle), getString(R.string.weather_daylight_long, hours(high)))) { date ->
        val l = days[date]?.daylightHours ?: return@MoodGroups null
        when {
            l < low -> 0
            l < high -> 1
            else -> 2
        }
    }))
}

// --- People ---

private fun StatPageFragment.peopleDayText(date: LocalDate, day: People.Day) = listOfNotNull(
    dateText(date),
    resources.getQuantityString(R.plurals.people_messages, day.messages, day.messages),
    resources.getQuantityString(R.plurals.people_count, day.people, day.people),
    resources.getQuantityString(R.plurals.people_calls, day.calls, day.calls).takeIf { day.calls > 0 },
    duration(day.talkMs).takeIf { day.talkMs > 0 }?.let { getString(R.string.people_talking, it) },
).joinToString(" · ")

/** Today's messages, with the people, calls and time talking under them; the last days as bars; and the usual */
internal fun StatPageFragment.peopleTodayCard(days: Map<LocalDate, People.Day>) {
    val context = requireContext()
    val messagesOn = Spending.isEnabled(context)
    // Nothing yet, and nothing missing: no card
    if (days.isEmpty() && messagesOn) return
    val card = Card(getString(R.string.people_today))
    val today = LocalDate.now()
    val day = days[today] ?: People.Day(0, 0L, 0, 0, 0, 0)
    if (days.isNotEmpty()) {
        card.headline(resources.getQuantityString(R.plurals.people_messages, day.messages, day.messages), listOfNotNull(
            resources.getQuantityString(R.plurals.people_count, day.people, day.people),
            resources.getQuantityString(R.plurals.people_calls, day.calls, day.calls),
            duration(day.talkMs).takeIf { day.talkMs > 0 }?.let { getString(R.string.people_talking, it) },
        ).joinToString(" · "))
        dayBars(card, days.mapValues { it.value.messages.toFloat() }) { date, _ -> peopleDayText(date, days.getValue(date)) }
    }
    // Messages come from notifications; calls say what they need on their own card
    if (!messagesOn) {
        if (days.isEmpty()) card.needs(getString(R.string.people_need_notifications)) { Spending.openSettings(context) }
        else {
            card.chipRow(listOf(getString(R.string.tracking_fix) to ink), null) { Spending.openSettings(context) }
            card.note.apply {
                isVisible = true
                text = getString(R.string.people_need_notifications)
            }
        }
    }
    val past = days.filterKeys { it < today && it >= today.minusDays(HABIT_DAYS.toLong()) }
    if (past.size < 2) return
    tiles(Card(getString(R.string.people_usual_title)), getString(R.string.social_usual, past.size), listOf(
        past.values.map { it.messages }.average().roundToInt().toString() to getString(R.string.people_tile_messages),
        String.format(Locale.getDefault(), "%.1f", past.values.map { it.people }.average()) to getString(R.string.people_tile_people),
        duration(past.values.sumOf { it.talkMs } / past.size) to getString(R.string.people_tile_talking),
    ))
}

/** The past week, month, year or everything, by [StatPageFragment.peopleSpan] */
private fun StatPageFragment.peopleDays(days: Map<LocalDate, People.Day>): Collection<People.Day> {
    val back = SPAN_DAYS.getOrNull(StatPageFragment.peopleSpan) ?: return days.values
    return days.filterKeys { it > LocalDate.now().minusDays(back) }.values
}

private fun StatPageFragment.peopleSpanPicker(card: Card, redraw: () -> Unit) =
    card.segment(SPAN_NAMES, StatPageFragment.peopleSpan) {
        StatPageFragment.peopleSpan = it
        redraw()
    }

/** Who sent the most messages over the past week, month, year or ever */
internal fun StatPageFragment.peopleMessagesCard(days: Map<LocalDate, People.Day>) {
    // No names yet: no card
    if (days.values.none { it.from.isNotEmpty() }) return
    val card = Card(getString(R.string.people_who_messages))
    fun draw() {
        peopleSpanPicker(card, ::draw)
        val counts = peopleDays(days).flatMap { it.from.entries }.groupBy({ it.key }, { it.value }).mapValues { (_, n) -> n.sum() }
        val all = counts.entries.sortedByDescending { it.value }
        if (all.isEmpty()) return card.showNote(getString(R.string.no_data))
        card.topList(all.size) { n ->
            val top = all.take(n)
            card.describe = { key ->
                val (name, count) = top[key as Int]
                listOf(name, resources.getQuantityString(R.plurals.people_messages, count, count),
                    getString(R.string.stat_label, getString(R.string.share_of), "${(count * 100f / counts.values.sum()).roundToInt()}%")).joinToString("\n")
            }
            horizontalBars(card, top.map { Triple(it.key, it.value, stat.themeColor) }) { it.toString() }
        }
    }
    draw()
}

/** Time on calls over the past week, month, year or ever, and who with; until the call log is allowed, a way to allow it */
internal fun StatPageFragment.peopleCallsCard(days: Map<LocalDate, People.Day>) {
    val card = Card(getString(R.string.people_calls_title))
    if (!People.hasCallLog(requireContext())) return card.needs(getString(R.string.people_need_calls)) { askCallLog.launch(Manifest.permission.READ_CALL_LOG) }
    // The span's total, between the title and the bars
    val total = card.textView(Style.TEXT_BODY, 220).apply { setPadding(0, 0, 0, (10 * resources.displayMetrics.density).toInt()) }
    card.root.addView(total, card.root.indexOfChild(card.scene))
    fun draw() {
        peopleSpanPicker(card, ::draw)
        val span = peopleDays(days)
        val calls = span.sumOf { it.calls }
        total.text = getString(R.string.people_calls_total, duration(span.sumOf { it.talkMs }), resources.getQuantityString(R.plurals.people_calls, calls, calls))
        val byName = span.flatMap { it.talkedWith.entries }.groupBy({ it.key }, { it.value }).mapValues { (_, ms) -> ms.sum() }
        val all = byName.entries.sortedByDescending { it.value }
        if (all.isEmpty()) return card.showNote(getString(R.string.people_calls_none))
        card.topList(all.size) { n ->
            val top = all.take(n)
            card.describe = { key ->
                val (name, ms) = top[key as Int]
                listOf(name, getString(R.string.stat_label, getString(R.string.time), duration(ms))).joinToString("\n")
            }
            // In whole minutes, at least one for a call that happened
            horizontalBars(card, top.map { Triple(it.key, ((it.value + 59_999) / 60_000).toInt(), stat.themeColor) }) { duration(it * 60_000L) }
        }
    }
    draw()
}

/** A range's choices, Week / Month / Year / All, and the days back each covers (All: everything) */
internal val SPAN_NAMES = listOf(R.string.period_week, R.string.period_month, R.string.period_year, R.string.over_all)
internal val SPAN_DAYS = listOf(7L, 30L, 365L)

/** Mood by how many messages there were */
internal fun StatPageFragment.peopleMoodCard(days: Map<LocalDate, People.Day>) {
    moodByCard(getString(R.string.people_and_mood), days.keys, listOf(MoodGroups(
        listOf(R.string.people_group_none, R.string.people_group_few, R.string.people_group_some, R.string.people_group_many).map { getString(it) }) { date ->
        val m = days[date]?.messages ?: return@MoodGroups null
        when {
            m == 0 -> 0
            m < People.FEW_MESSAGES -> 1
            m < People.SOME_MESSAGES -> 2
            else -> 3
        }
    }))
}
