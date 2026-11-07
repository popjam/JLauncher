package app.olauncher.ui

import android.graphics.RectF
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.helper.Music
import app.olauncher.ui.CardPageFragment.Card
import app.olauncher.ui.StatPageFragment.Companion.STREAK_DAYS
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

// Exercise and Meditation: workouts (and work days) as calendars, and streaks, as cards on [StatPageFragment]

// --- Exercise, Meditation and Music ---

/** Exercise's days as a calendar of dumbbells, deeper the harder the day; a faint outline for a rest day */
internal fun StatPageFragment.workoutsCard(title: Int, iconName: String) {
    val card = Card(getString(title))
    fun draw() {
        val days = dailyDates(card, redraw = ::draw)
        card.describe = { key -> listOf(dateText(key as LocalDate), dayText(stat, key) ?: "–").joinToString(" · ") }
        card.draw { s ->
            val cell = minOf(s.width / 7.4f, s.dp(48f))
            val left = (s.width - cell * 7) / 2
            val gap = s.dp(3f)
            var day = DayOfWeek.MONDAY
            repeat(7) { i ->
                s.text(day.getDisplayName(TextStyle.NARROW, Locale.getDefault()), left + i * cell + cell / 2, s.sp(12f), faint(140), s.sp(12f))
                day = day.plus(1)
            }
            val gridTop = s.dp(24f)
            val lead = days.first().dayOfWeek.value - 1
            days.forEachIndexed { i, date ->
                val place = i + lead
                val x = left + place % 7 * cell + gap
                val y = gridTop + place / 7 * cell + gap
                val size = cell - gap * 2
                val face = dayOf(date)?.face(stat.id)
                val done = face != null && !isRest(face)
                if (done) {
                    val icon = size * 0.62f
                    s.icon(iconName, x + (size - icon) / 2, y + (size - icon) / 2 - s.dp(3f), icon, stat.faceColor(requireContext(), face!!))
                    s.text(date.dayOfMonth.toString(), x + size / 2, y + size - s.dp(2f), faint(150), s.sp(9.5f))
                } else {
                    if (face != null) s.outline(x, y, x + size, y + size, faint(60), s.dp(9f), s.dp(1.5f))
                    s.text(date.dayOfMonth.toString(), x + size / 2, y + size / 2 + s.sp(4.5f), faint(if (face != null) 130 else 70), s.sp(12.5f))
                }
                s.target(date, RectF(x - gap, y - gap, x + size + gap, y + size + gap), RectF(x - gap / 2, y - gap / 2, x + size + gap / 2, y + size + gap / 2), s.dp(9f))
            }
            val rows = (days.size + lead + 6) / 7
            s.height = gridTop + rows * cell + s.dp(2f)
        }
    }
    draw()
}

/** A rest day: none, for something counted from none, otherwise the lowest score */
internal fun StatPageFragment.isRest(face: Int) = if (stat.medicine) stat.isBlank(face) else MoodStore.score(face, stat.inverted) <= 1

/** Days in a row with some, the best run, and the last [STREAK_DAYS] days as dots */
internal fun StatPageFragment.streakCard() {
    val today = LocalDate.now()
    val done = dates.filter { date -> dayOf(date)?.face(stat.id)?.let { !isRest(it) } == true }.toSet()
    val card = Card(getString(R.string.streak))
    if (dates.none { dayOf(it)?.face(stat.id) != null }) return card.showNote(getString(R.string.no_data))
    var current = 0
    var date = if (today in done) today else today.minusDays(1)
    while (date in done) {
        current++
        date = date.minusDays(1)
    }
    var best = 0
    var run = 0
    var previous: LocalDate? = null
    done.sorted().forEach { d ->
        run = if (previous?.plusDays(1) == d) run + 1 else 1
        best = maxOf(best, run)
        previous = d
    }
    card.headline(resources.getQuantityString(R.plurals.day_count, current, current), getString(R.string.streak_caption, best))
    val color = stat.themeColor
    card.keyItem(color, getString(R.string.streak_done))
    card.keyItem(faint(170), getString(R.string.streak_missed), ring = true)
    val days = (STREAK_DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }
    card.describe = { key -> listOf(dateText(key as LocalDate), dayText(stat, key) ?: "–").joinToString(" · ") }
    card.draw { s ->
        val perRow = 10
        val cell = s.width / perRow
        val radius = minOf(cell * 0.32f, s.dp(10f))
        days.forEachIndexed { i, d ->
            val cx = i % perRow * cell + cell / 2
            val cy = i / perRow * cell + cell / 2
            val face = dayOf(d)?.face(stat.id)
            when {
                d in done -> s.circle(cx, cy, radius, color)
                face != null -> s.ring(cx, cy, radius, faint(170), s.dp(2f))
                else -> s.circle(cx, cy, s.dp(2f), faint(70))
            }
            s.target(d, RectF(cx - cell / 2, cy - cell / 2, cx + cell / 2, cy + cell / 2),
                RectF(cx - radius - s.dp(3f), cy - radius - s.dp(3f), cx + radius + s.dp(3f), cy + radius + s.dp(3f)), radius + s.dp(3f))
        }
        s.height = ((days.size + perRow - 1) / perRow) * cell
    }
}
