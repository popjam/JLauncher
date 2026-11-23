package app.olauncher.ui

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.helper.Sleep
import app.olauncher.helper.Vibecoding
import app.olauncher.helper.dpToPx
import app.olauncher.ui.CardPageFragment.Card
import app.olauncher.ui.StatPageFragment.Companion.HABIT_DAYS
import app.olauncher.ui.StatPageFragment.Companion.SLEEP_MIN_NIGHTS
import app.olauncher.ui.StatPageFragment.Companion.VIBE_DAYS
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

// Vibecoding, from the files the PC and Mac write to Google Drive, as cards on [StatPageFragment]

// --- Vibecoding, from the files the PC and Mac write to Google Drive ---

internal fun StatPageFragment.vibeDayText(date: LocalDate, d: Vibecoding.Day) = listOfNotNull(
    dateText(date), duration(d.minutes * 60_000L),
    resources.getQuantityString(R.plurals.vibe_prompts, d.prompts, d.prompts),
    if (d.first != null && d.last != null) getString(R.string.sleep_span, clock(java.time.LocalTime.parse(d.first)), clock(java.time.LocalTime.parse(d.last))) else null,
    d.projects.maxByOrNull { it.value.minutes }?.key?.let { getString(R.string.doom_mostly, it) },
).joinToString(" · ")

internal fun StatPageFragment.money(dollars: Double) = String.format(Locale.getDefault(), "$%.2f", dollars)

/** Today's time and prompts, a bar a day, and the usual of the last [HABIT_DAYS] days. */
internal fun StatPageFragment.vibeTodayCard(days: Map<LocalDate, Vibecoding.Day>) {
    val context = requireContext()
    // Until the Drive folder the files are written to is chosen, a way to choose it; then, until they come in, no card
    if (app.olauncher.helper.DeviceTime.folder(context).isEmpty()) return Card(getString(R.string.mac_today)).needs(getString(R.string.needs_drive_folder)) { chooseDeviceFolder() }
    if (days.isEmpty()) return
    val card = Card(getString(R.string.mac_today))
    val updated = Vibecoding.updated(context)
    val today = LocalDate.now()
    val day = days[today]
    // When the files were last read goes with the figures, as the header steps through the days
    card.headline(duration((day?.minutes ?: 0) * 60_000L), listOfNotNull(
        getString(R.string.vibe_your_time),
        day?.prompts?.let { resources.getQuantityString(R.plurals.vibe_prompts, it, it) },
        day?.projects?.maxByOrNull { it.value.minutes }?.key?.let { getString(R.string.doom_mostly, it) },
        updated.takeIf { it > 0 }?.let { getString(R.string.mac_updated, android.text.format.DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), 60_000L)) },
    ).joinToString(" · "))
    dayBars(card, days.mapValues { it.value.minutes / 60f }, goal = 3f) { date, _ -> vibeDayText(date, days.getValue(date)) }
    card.keyItem(faint(110), getString(R.string.vibe_three_hours))
    val past = days.filterKeys { it < today && it >= today.minusDays(HABIT_DAYS.toLong()) }
    if (past.size < 2) return
    val tilesCard = Card(getString(R.string.steps_usual_title))
    // Days without any count as none
    tiles(tilesCard, getString(R.string.social_usual, HABIT_DAYS), listOf(
        duration(past.values.sumOf { it.minutes } * 60_000L / HABIT_DAYS) to getString(R.string.steps_tile_average),
        (past.values.sumOf { it.prompts } / HABIT_DAYS).toString() to getString(R.string.vibe_tile_prompts),
        (HABIT_DAYS - past.size).toString() to getString(R.string.vibe_tile_off),
    ))
}

/** Time and prompts per project over the past week, month, year or ever. */
internal fun StatPageFragment.vibeProjectsCard(days: Map<LocalDate, Vibecoding.Day>) {
    if (days.values.none { it.projects.isNotEmpty() }) return
    val context = requireContext()
    val today = LocalDate.now()
    val card = Card(getString(R.string.vibe_projects))
    card.scene.isVisible = false
    val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    card.root.addView(list, card.root.indexOfChild(card.scene))
    fun draw() {
        card.segment(SPAN_NAMES, StatPageFragment.vibeProjectsSpan) {
            StatPageFragment.vibeProjectsSpan = it
            draw()
        }
        val back = SPAN_DAYS.getOrNull(StatPageFragment.vibeProjectsSpan)
        val recent = days.filterKeys { back == null || it > today.minusDays(back) }.values
        val all = recent.flatMap { it.projects.entries }.groupBy({ it.key }, { it.value })
            .mapValues { (_, p) -> Vibecoding.Project(p.sumOf { it.minutes }, p.sumOf { it.prompts }) }
            .entries.sortedByDescending { it.value.minutes }
        card.topList(all.size) { n -> fillProjects(card, list, all.take(n)) }
    }
    draw()
}

/** The projects' rows in [list]: name, prompts, a bar as long as the time and the time; "No data" for none */
private fun StatPageFragment.fillProjects(card: Card, list: LinearLayout, projects: List<Map.Entry<String, Vibecoding.Project>>) {
        val context = requireContext()
        list.removeAllViews()
        if (projects.isEmpty()) {
            list.addView(card.textView(Style.TEXT_SMALL, 150).apply {
                setText(R.string.no_data)
                gravity = Gravity.CENTER
                updatePadding(top = 16.dpToPx(), bottom = 16.dpToPx())
            })
            return
        }
        val most = projects.first().value.minutes.coerceAtLeast(1).toFloat()
        projects.forEach { (name, project) ->
            list.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                updatePadding(top = 6.dpToPx(), bottom = 6.dpToPx())
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(card.textView(Style.TEXT_BODY, 255).apply {
                        text = name
                        isSingleLine = true
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(card.textView(Style.TEXT_SMALL, 140).apply {
                        text = resources.getQuantityString(R.plurals.vibe_prompts, project.prompts, project.prompts)
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(View(context).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = 3.dpToPx().toFloat()
                        setColor(stat.color)
                    }
                }, LinearLayout.LayoutParams((80.dpToPx() * project.minutes / most).toInt().coerceAtLeast(3.dpToPx()), 6.dpToPx()).apply {
                    marginStart = 10.dpToPx()
                })
                addView(card.textView(Style.TEXT_SMALL, 150).apply {
                    text = duration(project.minutes * 60_000L)
                    gravity = Gravity.END
                }, LinearLayout.LayoutParams(64.dpToPx(), ViewGroup.LayoutParams.WRAP_CONTENT))
            })
        }
}

/** Time by hour of the day over the last [VIBE_DAYS] days, late at night standing out. */
internal fun StatPageFragment.vibeHoursCard(days: Map<LocalDate, Vibecoding.Day>) {
    val today = LocalDate.now()
    val recent = days.filterKeys { it > today.minusDays(VIBE_DAYS.toLong()) }.values
    val hours = IntArray(24) { h -> recent.sumOf { it.hours[h] } }
    if (hours.sum() == 0) return
    val card = Card(getString(R.string.vibe_when))
    card.subtitle(getString(R.string.last_days, VIBE_DAYS))
    val late = ContextCompat.getColor(requireContext(), R.color.holiday)
    fun isLate(h: Int) = h >= 23 || h < 5
    card.keyItem(stat.color, getString(R.string.vibe_daytime))
    card.keyItem(late, getString(R.string.vibe_late))
    val lateShare = (hours.indices.filter(::isLate).sumOf { hours[it] } * 100f / hours.sum()).roundToInt()
    card.headline("$lateShare%", getString(R.string.vibe_late_share))
    card.describe = { key ->
        val h = key as Int
        listOf(getString(R.string.sleep_span, offsetClock(h * 60), offsetClock((h + 1) * 60)), duration(hours[h] * 60_000L)).joinToString(" · ")
    }
    val most = hours.max().toFloat()
    card.draw { s ->
        val top = s.dp(6f)
        val bottom = top + s.dp(100f)
        val column = s.width / 24
        s.line(0f, bottom, s.width, bottom, faint(30), s.dp(1f))
        (0 until 24).forEach { h ->
            val x = h * column
            if (hours[h] > 0) s.rect(x + s.dp(1f), bottom - hours[h] / most * (bottom - top), x + column - s.dp(1f), bottom,
                if (isLate(h)) late else stat.color, s.dp(3f))
            s.target(h, RectF(x, 0f, x + column, bottom + s.dp(24f)), RectF(x, top - s.dp(2f), x + column, bottom + s.dp(2f)))
        }
        listOf(0, 6, 12, 18).forEach { h ->
            val text = offsetClock(h * 60).replace(":00", "")
            // Midnight sits at the left edge, where a centred label would be cut off
            if (h == 0) s.text(text, 0f, bottom + s.dp(18f), faint(140), s.sp(11f), Paint.Align.LEFT)
            else s.text(text, h * column + column / 2, bottom + s.dp(18f), faint(140), s.sp(11f))
        }
        s.height = bottom + s.dp(24f)
    }
}

/** Estimated spend a day and over the last [VIBE_DAYS] days, at the models' list prices. */
internal fun StatPageFragment.vibeCostCard(days: Map<LocalDate, Vibecoding.Day>) {
    val today = LocalDate.now()
    val recent = days.filterKeys { it > today.minusDays(VIBE_DAYS.toLong()) }
    if (recent.values.sumOf { it.cost } <= 0.0) return
    val card = Card(getString(R.string.vibe_cost))
    val tokens = recent.values.sumOf { it.tokens }
    card.headline(money(recent.values.sumOf { it.cost }), getString(R.string.vibe_cost_caption, VIBE_DAYS,
        java.text.NumberFormat.getIntegerInstance(Locale.getDefault()).format(tokens / 1_000_000)))
    dayBars(card, days.mapValues { it.value.cost.toFloat() }) { date, _ ->
        val d = days.getValue(date)
        getString(R.string.vibe_cost_day, money(d.cost), java.text.NumberFormat.getIntegerInstance(Locale.getDefault()).format(d.tokens / 1_000_000))
    }
}

/** Mood by how long you vibecoded that day, and Sleep after nights you were still going late. */
internal fun StatPageFragment.vibeYouCard(days: Map<LocalDate, Vibecoding.Day>) {
    val today = LocalDate.now()
    val past = days.filterKeys { it < today }
    val since = days.keys.minOrNull() ?: return
    // Still prompting after 11 pm (or past midnight), and the night's sleep that followed
    val nights = Sleep.nights(requireContext())
    val lateDates = past.filter { (_, d) -> d.last?.let { it >= "23:00" } == true || d.hours.take(5).sum() > 0 }.keys
    val lateSleep = lateDates.mapNotNull { nights[it.plusDays(1)]?.asleepMs }
    val otherSleep = nights.filterKeys { it.minusDays(1) !in lateDates && it.minusDays(1) >= since }.values.map { it.asleepMs }
    val note = if (lateSleep.size >= 2 && otherSleep.size >= 2)
        getString(R.string.vibe_late_sleep, lateSleep.size, duration(lateSleep.average().toLong()), duration(otherSleep.average().toLong())) else null
    // Every logged day since vibecoding was first seen, days without any counting as none
    moodByCard(getString(R.string.vibe_and_you), dates.filter { it >= since }, listOf(MoodGroups(
        listOf(R.string.vibe_none, R.string.social_under_1, R.string.social_1_3, R.string.vibe_3_plus).map { getString(it) }) { date ->
        val minutes = past[date]?.minutes ?: 0
        when {
            minutes == 0 -> 0
            minutes < 60 -> 1
            minutes < 180 -> 2
            else -> 3
        }
    }), note = note)
}
