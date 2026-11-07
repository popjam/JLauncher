package app.olauncher.ui

import android.Manifest
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import androidx.core.graphics.ColorUtils
import app.olauncher.R
import app.olauncher.helper.DeviceTime
import app.olauncher.helper.PhoneApps
import app.olauncher.helper.PhoneHabits
import app.olauncher.helper.Steps
import app.olauncher.ui.CardPageFragment.Card
import app.olauncher.ui.StatPageFragment.Companion.APPS_SHOWN
import app.olauncher.ui.StatPageFragment.Companion.HABIT_DAYS
import app.olauncher.ui.StatPageFragment.Companion.HOUR_MS
import app.olauncher.ui.StatPageFragment.Companion.NOT_APPS
import app.olauncher.ui.StatPageFragment.Companion.PHONE
import app.olauncher.ui.StatPageFragment.Companion.PORN_GRID_WEEKS
import app.olauncher.ui.StatPageFragment.Companion.PORN_MIN_MINUTES
import app.olauncher.ui.StatPageFragment.Companion.PORN_WEEKS
import app.olauncher.ui.StatPageFragment.Companion.appsSpan
import app.olauncher.ui.StatPageFragment.Source
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

// Screen time across the phone, the Mac and the TV, pickups and Steps, and Libido's VLC time, as cards on [StatPageFragment]

// --- Screen time's pickups and doomscrolling, and Steps ---

/**
 * A bar a day for the last [HABIT_DAYS] days, today on the right, in [color], with a dashed-looking
 * [goal] line if given. [label] is a bar's figure in words, for tapping.
 */
internal fun StatPageFragment.dayBars(card: Card, values: Map<LocalDate, Float>, color: Int, goal: Float? = null, label: (LocalDate, Float) -> String) {
    val today = LocalDate.now()
    val days = (HABIT_DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }
    val max = maxOf(values.filterKeys { it in days }.values.maxOrNull() ?: 0f, goal ?: 0f, 1f)
    card.describe = { key -> (key as LocalDate).let { d -> listOf(dateText(d), values[d]?.let { label(d, it) } ?: "–").joinToString(" · ") } }
    card.draw { s ->
        val top = s.dp(6f)
        val bottom = top + s.dp(110f)
        val column = s.width / days.size
        fun y(value: Float) = bottom - value / max * (bottom - top)
        s.line(0f, bottom, s.width, bottom, faint(30), s.dp(1f))
        goal?.let { g -> s.line(0f, y(g), s.width, y(g), faint(110), s.dp(1.5f)) }
        val gap = minOf(s.dp(2f), column * 0.15f)
        days.forEachIndexed { i, date ->
            val x = i * column
            val value = values[date]
            if (value == null || value <= 0f) s.rect(x + gap, bottom - s.dp(2f), x + column - gap, bottom, faint(45), s.dp(1f))
            else s.rect(x + gap, y(value), x + column - gap, bottom, if (date == today) color else ColorUtils.setAlphaComponent(color, 190), minOf(s.dp(4f), column / 4))
            s.target(date, RectF(x, 0f, x + column, bottom + s.dp(24f)), RectF(x, top - s.dp(2f), x + column, bottom + s.dp(2f)))
        }
        labelIndices(days.size, 2).forEach { i ->
            s.text(days[i].dayOfMonth.toString(), i * column + column / 2, bottom + s.dp(18f), faint(140), s.sp(12f))
        }
        s.height = bottom + s.dp(24f)
    }
}

/** Unlocks each day, today's against the usual. */
internal fun StatPageFragment.pickupsCard(days: Map<LocalDate, PhoneHabits.Day>) {
    if (days.isEmpty()) return
    val card = Card(getString(R.string.phone_pickups), icon = StatIcons.PHONE, iconColor = faint(200))
    val today = LocalDate.now()
    val usual = days.filterKeys { it < today && it >= today.minusDays(HABIT_DAYS.toLong()) }.values.map { it.pickups }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
    card.headline((days[today]?.pickups ?: 0).toString(),
        listOfNotNull(getString(R.string.pickups_today), usual?.let { getString(R.string.usually_n, it) }).joinToString(" · "))
    dayBars(card, days.mapValues { it.value.pickups.toFloat() }, stat.color) { _, value ->
        resources.getQuantityString(R.plurals.pickup_count, value.toInt(), value.toInt())
    }
}

/** Today's steps against the usual, a bar a day with the 10,000 line, and the last [HABIT_DAYS] days' figures. */
internal fun StatPageFragment.stepsCard() {
    val context = requireContext()
    val card = Card(getString(R.string.steps_title), icon = StatIcons.STEPS, iconColor = stat.themeColor)
    if (!Steps.hasPermission(context)) {
        card.showNote(getString(R.string.steps_need_permission))
        card.chipRow(listOf(getString(R.string.steps_allow) to ink), null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) askActivity.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        return
    }
    val days = Steps.days(context)
    val today = LocalDate.now()
    val past = days.filterKeys { it < today && it >= today.minusDays(HABIT_DAYS.toLong()) }.values
    val format = java.text.NumberFormat.getIntegerInstance(Locale.getDefault())
    card.headline(format.format(days[today] ?: 0), listOfNotNull(getString(R.string.steps_so_far),
        past.takeIf { it.isNotEmpty() }?.average()?.roundToInt()?.let { getString(R.string.usually_text, format.format(it)) }).joinToString(" · "))
    if (days.isEmpty()) {
        card.showNote(getString(R.string.steps_none))
        return
    }
    card.keyItem(faint(110), getString(R.string.steps_goal, format.format(Steps.goal(requireContext()))))
    dayBars(card, days.mapValues { it.value.toFloat() }, stat.themeColor, Steps.goal(requireContext()).toFloat()) { _, value ->
        getString(R.string.steps_count, format.format(value.toInt()))
    }
    if (past.size >= 2) {
        val tilesCard = Card(getString(R.string.steps_usual_heading))
        tiles(tilesCard, getString(R.string.social_usual, past.size), listOf(
            format.format(past.average().roundToInt()) to getString(R.string.steps_tile_average),
            format.format(past.max()) to getString(R.string.steps_tile_best),
            past.count { it >= Steps.goal(requireContext()) }.toString() to getString(R.string.steps_tile_goal),
        ))
    }
}

/** Reads the Mac's and TV's latest files once on opening Screen time, and redraws if anything came in. */
internal fun StatPageFragment.readDevicesOnce() {
    if (devicesRead) return
    devicesRead = true
    val context = requireContext().applicationContext
    Thread {
        val before = DeviceTime.Device.values().map { DeviceTime.updated(context, it) }
        DeviceTime.refresh(context, force = true)
        val after = DeviceTime.Device.values().map { DeviceTime.updated(context, it) }
        if (before != after) _binding?.root?.post { if (_binding != null) render() }
    }.start()
}

internal fun StatPageFragment.sourceMs(date: LocalDate, key: String): Long =
    dayOf(date)?.let { if (key == PHONE) it.usageMs ?: 0L else it.deviceMs[key] ?: 0L } ?: 0L

internal fun StatPageFragment.screenDayText(date: LocalDate) = (listOf(dateText(date), getString(R.string.stat_label, getString(R.string.total), duration(sources.sumOf { sourceMs(date, it.key) })))
    + sources.filter { sourceMs(date, it.key) > 0 }.map { getString(R.string.stat_label, it.name, duration(sourceMs(date, it.key))) }).joinToString("\n")

/**
 * A bar a day of all screen time, stacked phone, Mac and TV in shades of one colour, against an hours
 * axis. Tapping one gives the day's total and how it split.
 */
internal fun StatPageFragment.screenDailyCard() {
    val card = Card(getString(R.string.stat_daily))
    sources.forEach { card.keyItem(it.color, it.name, icon = it.icon) }
    fun draw() {
        val days = dailyDates(card, redraw = ::draw)
        val totals = days.map { date -> sources.sumOf { sourceMs(date, it.key) } / HOUR_MS }
        val max = axisMax(totals.maxOrNull())
        card.describe = { key -> screenDayText(key as LocalDate) }
        card.draw { s ->
            // The hours in a column of their own on the right, clear of the bars
            val labelSize = s.sp(11f)
            val width = s.width - s.textWidth("88h", labelSize) - s.dp(8f)
            val column = width / days.size
            val top = s.dp(8f)
            val bottom = top + s.dp(130f)
            fun y(value: Float) = bottom - minOf(value, max) / max * (bottom - top)
            axisTicks(max).drop(1).forEach { (v, label) ->
                s.line(0f, y(v), width, y(v), faint(30), s.dp(1f))
                s.text(label, s.width, y(v) + labelSize * 0.35f, faint(130), labelSize, Paint.Align.RIGHT)
            }
            val gap = minOf(s.dp(1.5f), column * 0.15f)
            val corner = minOf(s.dp(4f), column / 4)
            days.forEachIndexed { i, date ->
                val x = i * column
                var base = bottom
                // Phone at the bottom; only the top piece gets the rounded corners
                val pieces = sources.map { it to sourceMs(date, it.key) / HOUR_MS }.filter { it.second > 0f }
                if (pieces.isEmpty()) s.rect(x + gap, bottom - s.dp(2f), x + column - gap, bottom, faint(45), s.dp(1f))
                pieces.forEachIndexed { k, (source, hours) ->
                    val h = minOf(hours, max) / max * (bottom - top)
                    s.rect(x + gap, base - h, x + column - gap, base, source.color, if (k == pieces.lastIndex) corner else 0f)
                    if (k < pieces.lastIndex) s.rect(x + gap, base - h, x + column - gap, base - h + corner, source.color)
                    base -= h
                }
            }
            labelIndices(days.size, 3).forEach { i ->
                s.text(days[i].dayOfMonth.toString(), i * column + column / 2, bottom + s.dp(18f), faint(140), s.sp(12f))
            }
            s.height = bottom + s.dp(24f)
            days.forEachIndexed { i, date ->
                val x = i * column
                s.target(date, RectF(x, 0f, x + column, s.height), RectF(x, 0f, x + column, bottom + s.dp(2f)), corner)
            }
        }
    }
    draw()
}

/** Today's screen time in big text, and a ring split between the devices, each with its icon. */
internal fun StatPageFragment.devicePieCard() {
    val today = LocalDate.now()
    val split = sources.map { it to sourceMs(today, it.key) }
    val total = split.sumOf { it.second }
    val card = Card(getString(R.string.today))
    card.headline(duration(total), getString(R.string.across_devices))
    val used = split.filter { it.second > 0 }
    if (used.isEmpty()) return
    card.describe = { key ->
        val (source, ms) = used[key as Int]
        listOf(source.name, getString(R.string.stat_label, getString(R.string.time), duration(ms)),
            getString(R.string.stat_label, getString(R.string.share_of), "${(ms * 100f / total).roundToInt()}%")).joinToString("\n")
    }
    card.draw { s ->
        val size = minOf(s.width * 0.42f, s.dp(132f))
        val radius = size / 2
        val cx = radius
        val cy = radius
        val thickness = s.dp(20f)
        var angle = -90f
        used.forEachIndexed { i, (source, ms) ->
            val sweep = ms * 360f / total
            // A hair's gap between pieces
            s.arc(cx, cy, radius - thickness / 2, angle + 1f, maxOf(sweep - 2f, 1f), source.color, thickness)
            angle += sweep
        }
        s.icon(StatIcons.SCREEN, cx - s.dp(12f), cy - s.dp(12f), s.dp(24f), faint(120))
        // The key on the right: icon, name, time and share
        val left = size + s.dp(22f)
        val rowHeight = s.dp(38f)
        val top = cy - rowHeight * used.size / 2
        used.forEachIndexed { i, (source, ms) ->
            val y = top + i * rowHeight
            s.icon(source.icon, left, y + s.dp(5f), s.dp(18f), source.color)
            s.text(source.name, left + s.dp(28f), y + s.dp(14f), faint(220), s.sp(13.5f), Paint.Align.LEFT)
            s.text(duration(ms) + " · " + (ms * 100f / total).roundToInt() + "%", left + s.dp(28f), y + s.dp(30f), faint(150), s.sp(12f), Paint.Align.LEFT)
            s.target(i, RectF(left - s.dp(6f), y, s.width, y + rowHeight))
        }
        s.height = maxOf(size, top + used.size * rowHeight)
    }
}

/** The most used apps on every device together, today or over the last week, each with its device's icon. */
internal fun StatPageFragment.appsCard() {
    val context = requireContext()
    val card = Card(getString(R.string.apps))
    val phone = PhoneApps.days(context)
    val others = DeviceTime.Device.values().associateWith { DeviceTime.days(context, it) }
    fun draw() {
        card.segment(listOf(R.string.today, R.string.last_7_days), appsSpan) {
            appsSpan = it
            draw()
        }
        val today = LocalDate.now()
        val days = (0 until if (appsSpan == 0) 1 else 7).map { today.minusDays(it.toLong()) }
        val sourceOf = sources.associateBy { it.key }
        val rows = mutableListOf<Triple<Source, String, Long>>()
        days.flatMap { d -> phone[d].orEmpty().entries }.groupBy({ it.key }, { it.value }).forEach { (app, times) ->
            rows.add(Triple(sourceOf.getValue(PHONE), app, times.sum()))
        }
        others.forEach { (device, byDay) ->
            days.flatMap { d -> byDay[d]?.apps.orEmpty().entries }.groupBy({ it.key }, { it.value }).forEach { (app, minutes) ->
                rows.add(Triple(sourceOf.getValue(device.key), app, minutes.sum() * 60_000L))
            }
        }
        // Launchers and system screens aren't apps anyone chose to use
        val top = rows.filter { it.second !in NOT_APPS }.sortedByDescending { it.third }.take(APPS_SHOWN)
        if (top.isEmpty()) return card.showNote(getString(R.string.apps_none))
        card.describe = { key ->
            val (source, app, ms) = top[key as Int]
            listOf(app, getString(R.string.stat_label, getString(R.string.device), source.name),
                getString(R.string.stat_label, getString(R.string.time), duration(ms))).joinToString("\n")
        }
        horizontalBars(card, top.map { (source, app, ms) -> Triple(app, (ms / 60_000L).toInt(), source.color) }, top.map { it.first.icon }) {
            duration(it * 60_000L)
        }
    }
    draw()
}

// --- Libido: porn, from the Mac's VLC time ---

/**
 * The Mac's time in VLC each week (the last [PORN_WEEKS]), and the last [PORN_DAYS] days as dots,
 * filled on days with [PORN_MIN_MINUTES] or more.
 */
internal fun StatPageFragment.pornCard() {
    val days = DeviceTime.days(requireContext(), DeviceTime.Device.MAC)
    val minutes = days.mapValues { (_, day) -> day.apps.entries.filter { it.key.contains("VLC", ignoreCase = true) }.sumOf { it.value } }
    val card = Card(getString(R.string.porn_usage))
    if (days.isEmpty()) return card.showNote(getString(R.string.porn_needs_mac))
    val today = LocalDate.now()
    val watched = { date: LocalDate -> (minutes[date] ?: 0) >= PORN_MIN_MINUTES }
    val tracked = { date: LocalDate -> date >= (days.keys.minOrNull() ?: today) }
    val week = (0..6).map { app.olauncher.helper.Week.start(today).plusDays(it.toLong()) }.filter { it <= today }
    val color = stat.themeColor
    val monday = app.olauncher.helper.Week.start(today)
    val weeks = (PORN_WEEKS - 1 downTo 0).map { monday.minusWeeks(it.toLong()) }
    val weekMinutes = weeks.map { start -> (0..6).sumOf { minutes[start.plusDays(it.toLong())] ?: 0 } }
    card.headline(duration(week.sumOf { minutes[it] ?: 0 } * 60_000L), getString(R.string.porn_time_this_week))
    val weekDays = weeks.map { start -> (0..6).count { watched(start.plusDays(it.toLong())) } }
    card.keyItem(color, getString(R.string.porn_watched))
    card.keyItem(faint(170), getString(R.string.porn_not), ring = true)
    card.describe = { key ->
        when (key) {
            is LocalDate -> listOf(dateText(key), if (!tracked(key)) getString(R.string.no_data)
                else getString(R.string.stat_label, getString(R.string.time), duration((minutes[key] ?: 0) * 60_000L))).joinToString("\n")
            else -> {
                val i = key as Int
                listOf(getString(R.string.week_of, weeks[i].format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))),
                    getString(R.string.stat_label, getString(R.string.time), duration(weekMinutes[i] * 60_000L)),
                    getString(R.string.stat_label, getString(R.string.days), getString(R.string.n_of_days, weekDays[i], 7))).joinToString("\n")
            }
        }
    }
    val most = maxOf(weekMinutes.maxOrNull() ?: 0, 30).toFloat()
    card.draw { s ->
        // A bar a week
        val top = s.dp(4f)
        val bottom = top + s.dp(80f)
        val column = s.width / weeks.size
        s.line(0f, bottom, s.width, bottom, faint(30), s.dp(1f))
        weeks.forEachIndexed { i, start ->
            val x = i * column
            val h = weekMinutes[i] / most * (bottom - top)
            if (weekMinutes[i] == 0) s.rect(x + s.dp(3f), bottom - s.dp(2f), x + column - s.dp(3f), bottom, faint(45), s.dp(1f))
            else s.rect(x + s.dp(3f), bottom - h, x + column - s.dp(3f), bottom, if (i == weeks.lastIndex) color else ColorUtils.setAlphaComponent(color, 170), s.dp(4f))
            s.target(i, RectF(x, 0f, x + column, bottom + s.dp(20f)), RectF(x + s.dp(1f), top - s.dp(2f), x + column - s.dp(1f), bottom + s.dp(2f)))
        }
        labelIndices(weeks.size, 3).forEach { i ->
            s.text(weeks[i].format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())), i * column + column / 2, bottom + s.dp(16f), faint(140), s.sp(11f))
        }
        // The days, a row a week, Monday first
        val gridTop = bottom + s.dp(46f)
        val cell = minOf(s.width / 7, s.dp(30f))
        val left = (s.width - cell * 7) / 2
        val radius = cell * 0.28f
        var day = app.olauncher.helper.Week.first
        repeat(7) { i ->
            s.text(day.getDisplayName(TextStyle.NARROW, Locale.getDefault()), left + i * cell + cell / 2, gridTop, faint(140), s.sp(11f))
            day = day.plus(1)
        }
        val first = monday.minusWeeks(PORN_GRID_WEEKS - 1L)
        (0 until PORN_GRID_WEEKS * 7).forEach { n ->
            val date = first.plusDays(n.toLong())
            val cx = left + n % 7 * cell + cell / 2
            val cy = gridTop + s.dp(12f) + n / 7 * cell + cell / 2
            when {
                date > today || !tracked(date) -> s.circle(cx, cy, s.dp(2f), faint(60))
                watched(date) -> s.circle(cx, cy, radius, color)
                else -> s.ring(cx, cy, radius, faint(150), s.dp(1.5f))
            }
            if (date <= today) s.target(date, RectF(cx - cell / 2, cy - cell / 2, cx + cell / 2, cy + cell / 2),
                RectF(cx - radius - s.dp(3f), cy - radius - s.dp(3f), cx + radius + s.dp(3f), cy + radius + s.dp(3f)), radius + s.dp(3f))
        }
        s.height = gridTop + s.dp(12f) + PORN_GRID_WEEKS * cell
    }
}

/** On Screen time, until a device's file has been found: where to look for it. */
internal fun StatPageFragment.macSetupCard() {
    val card = Card(getString(R.string.mac_screen_time))
    card.showNote(getString(R.string.mac_setup))
    card.chipRow(listOf(getString(R.string.mac_choose_folder) to ink), null) {
        androidx.lifecycle.ViewModelProvider(requireActivity())[app.olauncher.MainViewModel::class.java].isPickingFile = true
        pickDeviceFolder.launch(null)
    }
}
