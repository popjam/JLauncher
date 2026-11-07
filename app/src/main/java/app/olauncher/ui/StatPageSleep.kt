package app.olauncher.ui

import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.ColorUtils
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.helper.Music
import app.olauncher.helper.Sleep
import app.olauncher.ui.CardPageFragment.Card
import app.olauncher.ui.StatPageFragment.Companion.PHONE
import app.olauncher.ui.StatPageFragment.Companion.SLEEP_DAYS
import app.olauncher.ui.StatPageFragment.Companion.SLEEP_LATE_MS
import app.olauncher.ui.StatPageFragment.Companion.SLEEP_MIN_NIGHTS
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

// Sleep: last night, the usual times, mood, and the phone late at night and first thing, as cards on [StatPageFragment]

// --- Sleep and Music, filled in by themselves ---

/** Minutes from midnight on the morning woken up: before it (going to bed) is negative. */
internal fun StatPageFragment.offset(time: java.time.LocalDateTime, date: LocalDate) = ChronoUnit.MINUTES.between(date.atStartOfDay(), time).toInt()

internal fun StatPageFragment.offsetClock(minutes: Int) = clock(java.time.LocalTime.MIDNIGHT.plusMinutes(Math.floorMod(minutes, 1440).toLong()))

internal fun StatPageFragment.nightText(date: LocalDate, night: Sleep.Night) = listOfNotNull(
    dateText(date),
    getString(R.string.sleep_span, clock(night.bedtime.toLocalTime()), clock(night.wake.toLocalTime())),
    getString(R.string.sleep_asleep, duration(night.asleepMs)),
    night.checks.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.sleep_checks, it, it) },
).joinToString(" · ")

/** Last night: how long, from when to when, and the usual of the last [SLEEP_DAYS] nights under it. */
internal fun StatPageFragment.sleepCard(nights: Map<LocalDate, Sleep.Night>) {
    val card = Card(getString(R.string.sleep_last_night))
    val latest = nights.maxByOrNull { it.key }
    if (latest == null) {
        card.showNote(getString(R.string.sleep_none))
        return
    }
    val (date, night) = latest
    card.subtitle(dateText(date))
    card.headline(duration(night.asleepMs), listOfNotNull(
        getString(R.string.sleep_span, clock(night.bedtime.toLocalTime()), clock(night.wake.toLocalTime())),
        night.checks.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.sleep_checks, it, it) },
    ).joinToString(" · "))
    val recent = nights.filterKeys { it > LocalDate.now().minusDays(SLEEP_DAYS.toLong()) }
    if (recent.size < 2) return
    val asleep = recent.values.map { it.asleepMs }.average().toLong()
    val bed = recent.map { (d, n) -> offset(n.bedtime, d) }.average().roundToInt()
    val wake = recent.map { (d, n) -> offset(n.wake, d) }.average().roundToInt()
    val tiles = listOf(
        duration(asleep) to getString(R.string.sleep_usual_asleep),
        offsetClock(bed) to getString(R.string.sleep_usual_bedtime),
        offsetClock(wake) to getString(R.string.sleep_usual_wake),
    )
    card.draw { s ->
        s.text(getString(R.string.sleep_usual, recent.size), 0f, s.sp(12f), faint(140), s.sp(12f), Paint.Align.LEFT)
        val top = s.dp(22f)
        val gap = s.dp(8f)
        val width = (s.width - gap * 2) / 3
        tiles.forEachIndexed { i, (value, label) ->
            val x = i * (width + gap)
            s.rect(x, top, x + width, top + s.dp(64f), faint(18), s.dp(14f))
            s.text(value, x + width / 2, top + s.dp(30f), ink, s.sp(17f), bold = true)
            s.text(label, x + width / 2, top + s.dp(50f), faint(150), s.sp(12f))
        }
        s.height = top + s.dp(64f)
    }
}

/**
 * The last [SLEEP_DAYS] nights as bars from going to bed to waking up, in the night's face colour,
 * with the usual bedtime and wake-up behind them.
 */
internal fun StatPageFragment.sleepTimesCard(nights: Map<LocalDate, Sleep.Night>) {
    val today = LocalDate.now()
    val days = (SLEEP_DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }
    val shown = days.mapNotNull { date -> nights[date]?.let { date to it } }
    if (shown.size < 2) return
    val card = Card(getString(R.string.sleep_times))
    card.subtitle(getString(R.string.last_days, SLEEP_DAYS))
    card.keyItem(faint(160), getString(R.string.sleep_usual_times))
    card.describe = { key -> nightText(key as LocalDate, nights.getValue(key)) }
    val beds = shown.map { (d, n) -> offset(n.bedtime, d) }
    val wakes = shown.map { (d, n) -> offset(n.wake, d) }
    // Whole hours either side of the earliest bedtime and latest wake-up
    val from = Math.floorDiv(beds.min(), 60) * 60
    val to = -Math.floorDiv(-wakes.max(), 60) * 60
    val usualBed = beds.average().toFloat()
    val usualWake = wakes.average().toFloat()
    card.draw { s ->
        val labelSize = s.sp(11f)
        val left = s.textWidth("12:00 am", labelSize) + s.dp(8f)
        val top = s.dp(6f)
        val bottom = top + s.dp(170f)
        val column = (s.width - left) / days.size
        fun y(minutes: Float) = top + (minutes - from) / (to - from) * (bottom - top)
        val step = if (to - from > 10 * 60) 180 else 120
        var hour = from
        while (hour <= to) {
            s.line(left, y(hour.toFloat()), s.width, y(hour.toFloat()), faint(28), s.dp(1f))
            s.text(offsetClock(hour).replace(":00", ""), left - s.dp(8f), y(hour.toFloat()) + labelSize * 0.35f, faint(130), labelSize, Paint.Align.RIGHT)
            hour += step
        }
        listOf(usualBed, usualWake).forEach { s.line(left, y(it), s.width, y(it), faint(150), s.dp(1.5f)) }
        val bar = minOf(column * 0.55f, s.dp(14f))
        days.forEachIndexed { i, date ->
            val x = left + i * column + column / 2
            nights[date]?.let { night ->
                val face = Sleep.faceFor(night.asleepMs)
                s.rect(x - bar / 2, y(offset(night.bedtime, date).toFloat()), x + bar / 2, y(offset(night.wake, date).toFloat()),
                    stat.faceColor(requireContext(), face), bar / 2)
                s.target(date, RectF(left + i * column, 0f, left + (i + 1) * column, bottom + s.dp(24f)),
                    RectF(x - bar / 2 - s.dp(3f), y(offset(night.bedtime, date).toFloat()) - s.dp(3f), x + bar / 2 + s.dp(3f), y(offset(night.wake, date).toFloat()) + s.dp(3f)), bar / 2 + s.dp(3f))
            }
        }
        labelIndices(days.size, 2).forEach { i ->
            s.text(days[i].dayOfMonth.toString(), left + i * column + column / 2, bottom + s.dp(18f), faint(140), s.sp(12f))
        }
        s.height = bottom + s.dp(24f)
    }
}

/**
 * Mood (and Energy, if there's a stat for it) on the day after each night, by when you went to bed
 * or how long you slept.
 */
internal fun StatPageFragment.sleepMoodCard(nights: Map<LocalDate, Sleep.Night>) {
    val mood = stats.firstOrNull { it.id == MoodStore.MOOD } ?: return
    val energy = stats.firstOrNull { it.id != MoodStore.MOOD && it.name.contains("energy", ignoreCase = true) }
    if (nights.count { (date, _) -> valueOf(mood, date) != null } < SLEEP_MIN_NIGHTS) return
    val card = Card(getString(R.string.sleep_and_day))
    card.keyItem(mood.color, mood.name)
    energy?.let { card.keyItem(it.color, it.name) }
    val bedBuckets = listOf(R.string.sleep_before_11, R.string.sleep_11_12, R.string.sleep_12_1, R.string.sleep_after_1)
    val hourBuckets = listOf(R.string.sleep_under_6, R.string.sleep_6_7, R.string.sleep_7_8, R.string.sleep_8_plus)
    var byHours = false
    fun bucket(date: LocalDate, night: Sleep.Night): Int = if (byHours) {
        val hours = night.asleepMs / 3_600_000.0
        when {
            hours < 6 -> 0
            hours < 7 -> 1
            hours < 8 -> 2
            else -> 3
        }
    } else {
        val bed = offset(night.bedtime, date)
        when {
            bed < -60 -> 0
            bed < 0 -> 1
            bed < 60 -> 2
            else -> 3
        }
    }
    fun draw() {
        card.segment(listOf(R.string.sleep_by_bedtime, R.string.sleep_by_hours), if (byHours) 1 else 0) {
            byHours = it == 1
            draw()
        }
        val names = (if (byHours) hourBuckets else bedBuckets).map { getString(it) }
        val measured = listOfNotNull(mood, energy)
        // Per bucket, per stat: the average and how many days
        val averages = (0 until 4).map { b ->
            measured.map { s ->
                nights.filter { (date, night) -> bucket(date, night) == b }.keys.mapNotNull { valueOf(s, it) }
                    .let { values -> values.takeIf { it.isNotEmpty() }?.average()?.toFloat() to values.size }
            }
        }
        card.describe = { key ->
            val b = key as Int
            (listOf(names[b]) + measured.mapIndexed { i, s ->
                val (average, count) = averages[b][i]
                getString(R.string.stat_total, s.name, average?.let(::decimal) ?: "–") + " · " + resources.getQuantityString(R.plurals.day_count, count, count)
            }).joinToString("\n")
        }
        card.draw { s ->
            val top = s.dp(6f)
            val bottom = top + s.dp(120f)
            val column = s.width / 4
            fun y(value: Float) = bottom - (value - 1f) / 4f * (bottom - top) - s.dp(2f)
            listOf(1, 3, 5).forEach { g -> s.line(0f, y(g.toFloat()), s.width, y(g.toFloat()), faint(28), s.dp(1f)) }
            val bar = minOf(column / (measured.size + 1), s.dp(22f))
            (0 until 4).forEach { b ->
                val center = b * column + column / 2
                val start = center - bar * measured.size / 2
                measured.forEachIndexed { i, st ->
                    val (average, _) = averages[b][i]
                    val x = start + i * bar
                    if (average == null) s.rect(x + s.dp(2f), bottom - s.dp(2f), x + bar - s.dp(2f), bottom, faint(45), s.dp(1f))
                    else s.rect(x + s.dp(2f), y(average), x + bar - s.dp(2f), bottom, st.color, s.dp(5f))
                }
                s.text(names[b], center, bottom + s.dp(18f), faint(160), s.sp(12f))
                s.target(b, RectF(b * column, 0f, (b + 1) * column, bottom + s.dp(26f)))
            }
            s.height = bottom + s.dp(26f)
        }
    }
    draw()
}

// --- Sleep: the phone late at night and first thing ---

/**
 * The phone's time from 11 pm until falling asleep, and in the first hour after waking, the last
 * [SLEEP_DAYS] nights: the usual of each, and a dot a night sized by the minutes.
 */
internal fun StatPageFragment.sleepPhoneCard(nights: Map<LocalDate, Sleep.Night>) {
    val today = LocalDate.now()
    val days = (SLEEP_DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }
    val shown = days.mapNotNull { nights[it] }
    if (shown.size < 2) return
    val card = Card(getString(R.string.sleep_phone), icon = StatIcons.PHONE, iconColor = stat.themeColor)
    card.subtitle(getString(R.string.last_days, SLEEP_DAYS))
    val late = shown.map { it.lateMs }
    val morning = shown.map { it.morningMs }
    val tiles = listOf(
        duration(late.average().toLong()) to getString(R.string.sleep_late_avg),
        late.count { it >= SLEEP_LATE_MS }.toString() + "/" + shown.size to getString(R.string.sleep_late_nights),
        duration(morning.average().toLong()) to getString(R.string.sleep_morning_avg),
    )
    val color = stat.themeColor
    card.describe = { key ->
        val date = key as LocalDate
        val night = nights.getValue(date)
        listOf(dateText(date), getString(R.string.stat_label, getString(R.string.sleep_late_row), duration(night.lateMs)),
            getString(R.string.stat_label, getString(R.string.sleep_morning_row), duration(night.morningMs))).joinToString("\n")
    }
    card.draw { s ->
        // The usual of each, then a dot a night, as big as its minutes
        val gap = s.dp(8f)
        val tileWidth = (s.width - gap * 2) / 3
        tiles.forEachIndexed { i, (value, label) ->
            val x = i * (tileWidth + gap)
            s.rect(x, 0f, x + tileWidth, s.dp(64f), faint(18), s.dp(14f))
            s.text(value, x + tileWidth / 2, s.dp(30f), ink, s.sp(17f), bold = true)
            s.text(label, x + tileWidth / 2, s.dp(50f), faint(150), s.sp(12f))
        }
        val top = s.dp(84f)
        val labelSize = s.sp(12f)
        val left = maxOf(s.textWidth(getString(R.string.sleep_late_row), labelSize), s.textWidth(getString(R.string.sleep_morning_row), labelSize)) + s.dp(12f)
        val column = (s.width - left) / days.size
        val row = s.dp(30f)
        val most = minOf(s.dp(10f), column * 0.45f)
        listOf(getString(R.string.sleep_late_row) to { n: Sleep.Night -> n.lateMs }, getString(R.string.sleep_morning_row) to { n: Sleep.Night -> n.morningMs })
            .forEachIndexed { r, (label, ms) ->
                val cy = top + r * row + row / 2
                s.text(label, 0f, cy + labelSize * 0.35f, faint(180), labelSize, Paint.Align.LEFT)
                days.forEachIndexed { i, date ->
                    val cx = left + i * column + column / 2
                    val night = nights[date]
                    if (night == null) s.circle(cx, cy, s.dp(1.5f), faint(60))
                    else {
                        val minutes = ms(night) / 60_000f
                        // Up to an hour, then as big as it gets
                        val radius = s.dp(2.5f) + (minutes / 60f).coerceIn(0f, 1f) * (most - s.dp(2.5f))
                        s.circle(cx, cy, radius, if (minutes >= 30) color else ColorUtils.setAlphaComponent(color, 140))
                    }
                }
            }
        labelIndices(days.size, 3).forEach { i ->
            s.text(days[i].dayOfMonth.toString(), left + i * column + column / 2, top + row * 2 + s.dp(16f), faint(140), s.sp(11.5f))
        }
        s.height = top + row * 2 + s.dp(22f)
        days.forEachIndexed { i, date ->
            if (nights.containsKey(date)) s.target(date, RectF(left + i * column, top, left + (i + 1) * column, s.height))
        }
    }
}
