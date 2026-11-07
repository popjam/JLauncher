package app.olauncher.ui

import android.Manifest
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.helper.Social
import app.olauncher.helper.dpToPx
import app.olauncher.helper.setStatDot
import app.olauncher.ui.CardPageFragment.Card
import app.olauncher.ui.StatPageFragment.Companion.SLEEP_MIN_NIGHTS
import app.olauncher.ui.StatPageFragment.Companion.SOCIAL_DAYS
import app.olauncher.ui.StatPageFragment.Companion.SOCIAL_LONG_DAYS
import app.olauncher.ui.StatPageFragment.Companion.WORK_TREND_WEEKS
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

// Social and Work: time out, the homes and work from their Wi-Fi, and where the time went, as cards on [StatPageFragment]

/** What the Social cards were drawn with, so they're redrawn on coming back from a permission */
internal fun StatPageFragment.socialState(): String {
    val context = requireContext()
    return listOf(Social.hasLocation(context), Social.hasBackgroundLocation(context), Social.homes(context).map { it?.ssid }, Social.work(context)?.ssid).toString()
}

internal fun StatPageFragment.homeName(slot: Int) =
    if (slot == Social.WORK) Social.work(requireContext())?.name ?: getString(R.string.work)
    else Social.homes(requireContext()).getOrNull(slot)?.name ?: getString(R.string.social_home_n, slot + 1)

internal fun StatPageFragment.socialDayText(date: LocalDate, day: Social.Day) = listOfNotNull(
    dateText(date),
    getString(R.string.social_out, duration(day.outMs)),
    day.outings.takeIf { it.isNotEmpty() }?.let { o -> getString(R.string.social_left_at, o.joinToString(", ") { clock(it.startTime.toLocalTime()) }) },
    day.mainHome?.let { getString(R.string.social_at, homeName(it)) },
).joinToString(" · ")

/**
 * Where each of the last [SOCIAL_LONG_DAYS] days was spent: a calendar with a house in the colour of
 * the home most of the day was at. Under it the two homes' Wi-Fi, set from the Wi-Fi you're on.
 * Until location is allowed, a way to allow it.
 */
internal fun StatPageFragment.socialHomesCard(days: Map<LocalDate, Social.Day>) {
    val context = requireContext()
    val card = Card(getString(R.string.social_homes))
    socialShownState = socialState()
    if (!Social.hasLocation(context)) {
        card.showNote(getString(R.string.social_need_location))
        card.chipRow(listOf(getString(R.string.social_allow_location) to ink), null) { askLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
        return
    }
    card.scene.isVisible = false
    val homes = Social.homes(context)
    val today = LocalDate.now()
    val recent = days.filterKeys { it > today.minusDays(SOCIAL_LONG_DAYS.toLong()) }
    // Each day goes to the home most of it was spent at; days at neither are left plain
    val dates = (SOCIAL_LONG_DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }
    val stayed = dates.associateWith { days[it]?.mainHome }
    val nights = homes.indices.associateWith { slot -> stayed.values.count { it == slot } }
    val stayedWork = dates.count { (days[it]?.workMs ?: 0L) >= 60 * 60_000L }
    if (stayed.values.any { it != null }) {
        card.describe = { key ->
            val date = key as LocalDate
            val day = days[date]
            listOfNotNull(dateText(date), stayed[date]?.let { getString(R.string.social_at, homeName(it)) } ?: getString(R.string.social_neither),
                day?.let { d -> stayed[date]?.let { getString(R.string.stat_label, getString(R.string.time), duration(d.homeMs(it))) } }).joinToString("\n")
        }
        card.draw { sc ->
            val cell = minOf(sc.width / 7.4f, sc.dp(48f))
            val left = (sc.width - cell * 7) / 2
            val gap = sc.dp(3f)
            var weekday = DayOfWeek.MONDAY
            repeat(7) { i ->
                sc.text(weekday.getDisplayName(TextStyle.NARROW, Locale.getDefault()), left + i * cell + cell / 2, sc.sp(12f), faint(140), sc.sp(12f))
                weekday = weekday.plus(1)
            }
            val gridTop = sc.dp(24f)
            val lead = dates.first().dayOfWeek.value - 1
            dates.forEachIndexed { i, date ->
                val place = i + lead
                val x = left + place % 7 * cell + gap
                val y = gridTop + place / 7 * cell + gap
                val size = cell - gap * 2
                val slot = stayed[date]
                if (slot != null) {
                    val icon = size * 0.6f
                    sc.icon(StatIcons.HOME, x + (size - icon) / 2, y + (size - icon) / 2 - sc.dp(3f), icon, homeColors[slot])
                    sc.text(date.dayOfMonth.toString(), x + size / 2, y + size - sc.dp(2f), faint(150), sc.sp(9.5f))
                } else {
                    sc.text(date.dayOfMonth.toString(), x + size / 2, y + size / 2 + sc.sp(4.5f), faint(70), sc.sp(12.5f))
                }
                sc.target(date, RectF(x - gap, y - gap, x + size + gap, y + size + gap), RectF(x - gap / 2, y - gap / 2, x + size + gap / 2, y + size + gap / 2), sc.dp(9f))
            }
            sc.height = gridTop + (dates.size + lead + 6) / 7 * cell + sc.dp(2f)
        }
    }
    val mood = stats.firstOrNull { it.id == MoodStore.MOOD }
    val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    // The homes in order of time spent at them, ones not set last
    homes.indices.sortedByDescending { if (homes[it] == null) -1 else nights.getValue(it) }.forEach { slot ->
        val home = homes[slot]
        val dates = stayed.filterValues { it == slot }.keys
        val moodText = mood?.let { m -> dates.mapNotNull { valueOf(m, it) }.takeIf { it.size >= 2 }?.average()?.toFloat() }
            ?.let { getString(R.string.music_mood, decimal(it)) }
        list.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            updatePadding(top = 8.dpToPx(), bottom = 8.dpToPx())
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(card.textView(15f, 255).apply {
                    text = home?.name ?: getString(R.string.social_home_n, slot + 1)
                    setStatDot(homeColors[slot])
                })
                addView(card.textView(12f, 140).apply {
                    text = if (home == null) getString(R.string.social_not_set)
                    else listOfNotNull(home.ssid, resources.getQuantityString(R.plurals.day_count, dates.size, dates.size), moodText).joinToString(" · ")
                    updatePadding(top = 2.dpToPx())
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(card.textView(13f, 255).apply {
                setText(if (home == null) R.string.social_set else R.string.edit)
                setBackgroundResource(R.drawable.bg_stat_chip)
                setPadding(14.dpToPx(), 6.dpToPx(), 14.dpToPx(), 6.dpToPx())
                setOnClickListener { editHome(slot, home) }
            })
        })
    }
    // Work, set the same way; it fills in the Work stat
    val work = Social.work(context)
    list.addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        updatePadding(top = 8.dpToPx(), bottom = 8.dpToPx())
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(card.textView(15f, 255).apply {
                text = work?.name ?: getString(R.string.work)
                setCompoundDrawablesRelativeWithIntrinsicBounds(StatIcons.drawable(StatIcons.BRIEFCASE, homeColors[Social.WORK], 13.dpToPx()), null, null, null)
                compoundDrawablePadding = 8.dpToPx()
            })
            addView(card.textView(12f, 140).apply {
                text = if (work == null) getString(R.string.social_work_not_set) else listOf(work.ssid,
                    resources.getQuantityString(R.plurals.day_count, stayedWork, stayedWork)).joinToString(" · ")
                updatePadding(top = 2.dpToPx())
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(card.textView(13f, 255).apply {
            setText(if (work == null) R.string.social_set else R.string.edit)
            setBackgroundResource(R.drawable.bg_stat_chip)
            setPadding(14.dpToPx(), 6.dpToPx(), 14.dpToPx(), 6.dpToPx())
            setOnClickListener { editHome(Social.WORK, work) }
        })
    })
    card.root.addView(list, card.root.indexOfChild(card.scene) + 1)
    if (homes.any { it != null } || work != null) {
        val now = days[today]?.stretches?.lastOrNull()
        card.subtitle(when {
            now == null || now.place == Social.UNKNOWN -> getString(R.string.social_now_unknown)
            now.place == Social.OUT -> getString(R.string.social_now_out, clock(now.startTime.toLocalTime()))
            else -> getString(R.string.social_now_at, homeName(now.place))
        })
    }
    if (!Social.hasBackgroundLocation(context)) {
        card.chipRow(listOf(getString(R.string.social_allow_always) to ink), null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) askLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        card.note.isVisible = true
        card.note.text = getString(R.string.social_need_always)
    }
}

/** Where the time went, home by home, at work, out and unknown, over the last week, month or year */
internal fun StatPageFragment.socialPlacesCard(days: Map<LocalDate, Social.Day>) {
    val context = requireContext()
    val first = Social.firstChange(context) ?: return
    val card = Card(getString(R.string.social_places))
    val names = listOf(homeName(0), homeName(1), homeName(Social.WORK), getString(R.string.social_out_place), getString(R.string.social_unknown_place))
    val places = listOf(0, 1, Social.WORK, Social.OUT, Social.UNKNOWN)
    val colors = listOf(homeColors[0], homeColors[1], homeColors[Social.WORK], stat.color, faint(70))
    fun draw() {
        val spanDays = listOf(7L, 30L, 365L)[placesPeriod]
        val now = System.currentTimeMillis()
        val wanted = now - spanDays * 24 * 3_600_000L
        val start = maxOf(wanted, first)
        val totals = LongArray(places.size)
        days.values.forEach { day ->
            day.stretches.forEach { stretch ->
                val ms = minOf(stretch.end, now) - maxOf(stretch.start, start)
                if (ms > 0) places.indexOf(stretch.place).takeIf { it >= 0 }?.let { totals[it] += ms }
            }
        }
        // Untracked time counts as unknown
        val known = totals.sum() - totals[4]
        totals[4] = maxOf(0L, now - start - known)
        val all = totals.sum().toFloat()
        card.segment(listOf(R.string.period_week, R.string.period_month, R.string.period_year), placesPeriod) {
            placesPeriod = it
            draw()
        }
        card.key.removeAllViews()
        val shown = places.indices.filter { totals[it] > 0 }.sortedByDescending { totals[it] }
        fun share(i: Int) = (totals[i] * 100 / all).let { if (it < 1f) "<1%" else "${it.roundToInt()}%" }
        shown.forEach { i -> card.keyItem(colors[i], "${names[i]} · ${share(i)}") }
        card.describe = { key ->
            val i = key as Int
            listOf(names[i], getString(R.string.stat_label, getString(R.string.time), duration(totals[i])),
                getString(R.string.stat_label, getString(R.string.share), share(i))).joinToString("\n")
        }
        val top = shown.firstOrNull()
        card.draw { sc ->
            val size = minOf(sc.width, sc.dp(200f))
            val cx = sc.width / 2
            val cy = size / 2
            val width = sc.dp(26f)
            val radius = size / 2 - width / 2 - sc.dp(4f)
            var angle = -90f
            shown.forEach { i ->
                val sweep = totals[i] / all * 360f
                sc.arc(cx, cy, radius, angle + 0.8f, maxOf(sweep - 1.6f, 0.5f), colors[i], width)
                // A tap target on the middle of the slice
                val mid = Math.toRadians((angle + sweep / 2).toDouble())
                val tx = cx + Math.cos(mid).toFloat() * radius
                val ty = cy + Math.sin(mid).toFloat() * radius
                sc.target(i, RectF(tx - width, ty - width, tx + width, ty + width))
                angle += sweep
            }
            if (top != null) {
                sc.text("${(totals[top] * 100 / all).roundToInt()}%", cx, cy + sc.sp(4f), ink, sc.sp(26f), bold = true)
                sc.text(names[top], cx, cy + sc.sp(24f), faint(160), sc.sp(12f))
            }
            sc.height = size
        }
        // After drawing, which hides the note
        if (start > wanted) card.note.apply {
            isVisible = true
            text = getString(R.string.social_tracked_since, dateText(Instant.ofEpochMilli(first).atZone(ZoneId.systemDefault()).toLocalDate()))
        }
    }
    draw()
}

/** This week at work: days, hours, and when you usually get there and leave, from work's Wi-Fi */
internal fun StatPageFragment.workCard() {
    val context = requireContext()
    val card = Card(getString(R.string.this_week))
    val days = Social.days(context)
    val today = LocalDate.now()
    val monday = today.with(DayOfWeek.MONDAY)
    val week = days.filterKeys { it >= monday }
    val worked = week.values.count { it.workMs >= 60 * 60_000L }
    card.headline(resources.getQuantityString(R.plurals.work_days_count, worked, worked), getString(R.string.work_hours_week, duration(week.values.sumOf { it.workMs })))
    val recent = days.filterKeys { it > today.minusDays(30) }.values.filter { it.workMs >= 60 * 60_000L }
    if (recent.isEmpty()) {
        if (Social.work(context) == null) card.showNote(getString(R.string.work_set_wifi))
        return
    }
    val arrive = recent.mapNotNull { d -> d.stretches.firstOrNull { it.place == Social.WORK }?.startTime?.toLocalTime()?.toSecondOfDay() }.average()
    val leave = recent.mapNotNull { d -> d.stretches.lastOrNull { it.place == Social.WORK }?.endTime?.toLocalTime()?.toSecondOfDay() }.average()
    tiles(card, getString(R.string.work_usual), listOf(
        duration(recent.sumOf { it.workMs } / recent.size) to getString(R.string.work_a_day),
        clock(java.time.LocalTime.ofSecondOfDay(arrive.toLong())) to getString(R.string.work_arrive),
        clock(java.time.LocalTime.ofSecondOfDay(leave.toLong())) to getString(R.string.work_leave),
    ))
}

/** Sets a home to the Wi-Fi you're on now, with a name, or takes it away. */
internal fun StatPageFragment.editHome(slot: Int, home: Social.Home?) {
    val context = requireContext()
    val ssid = Social.currentSsid(context)
    if (ssid == null && home == null) {
        AlertDialog.Builder(context).setMessage(R.string.social_connect_first).setPositiveButton(R.string.okay, null).show()
        return
    }
    val name = EditText(context).apply {
        setText(home?.name ?: if (slot == Social.WORK) getString(R.string.work) else ssid)
        setSingleLine()
        setSelection(text.length)
    }
    val box = android.widget.FrameLayout(context).apply {
        setPadding(24.dpToPx(), 8.dpToPx(), 24.dpToPx(), 0)
        addView(name)
    }
    AlertDialog.Builder(context)
        .setTitle(if (slot == Social.WORK) getString(R.string.work) else getString(R.string.social_home_n, slot + 1))
        .setMessage(if (ssid != null) getString(R.string.social_uses_wifi, ssid) else getString(R.string.social_keeps_wifi, home!!.ssid))
        .setView(box)
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.save) { _, _ ->
            val wifi = ssid ?: home!!.ssid
            val place = Social.Home(wifi, name.text.toString().trim().ifEmpty { wifi })
            if (slot == Social.WORK) Social.setWork(context, place) else Social.setHome(context, slot, place)
            if (slot == Social.WORK) runCatching { Social.refresh(context) }
            if (_binding != null) render()
        }
        .apply {
            if (home != null) setNeutralButton(R.string.social_remove) { _, _ ->
                if (slot == Social.WORK) Social.setWork(context, null) else Social.setHome(context, slot, null)
                if (_binding != null) render()
            }
        }
        .show()
}

/** Today's time out, when you left, and the usual of the last [SOCIAL_DAYS] days under it. */
internal fun StatPageFragment.socialTodayCard(days: Map<LocalDate, Social.Day>) {
    val today = LocalDate.now()
    val day = days[today] ?: return
    val card = Card(getString(R.string.social_today))
    card.headline(duration(day.outMs), listOfNotNull(
        getString(R.string.social_out_today),
        day.outings.firstOrNull()?.let { getString(R.string.social_left_at, clock(it.startTime.toLocalTime())) },
        day.mainHome?.let { getString(R.string.social_at, homeName(it)) },
    ).joinToString(" · "))
    val recent = days.filterKeys { it > today.minusDays(SOCIAL_DAYS.toLong()) && it < today }
    if (recent.size < 2) return
    val firstOut = recent.values.mapNotNull { d -> d.outings.firstOrNull()?.let { offset(it.startTime, it.startTime.toLocalDate()) } }
    tiles(card, getString(R.string.social_usual, recent.size), listOf(
        duration(recent.values.map { it.outMs }.average().toLong()) to getString(R.string.social_tile_out),
        recent.values.count { it.stayedIn }.toString() to getString(R.string.social_tile_stayed_in),
        (firstOut.takeIf { it.isNotEmpty() }?.average()?.roundToInt()?.let(::offsetClock) ?: "–") to getString(R.string.social_tile_first_out),
    ))
}

/** The last [SOCIAL_DAYS] days as a line each through the day: out, at each home, or not known. */
internal fun StatPageFragment.socialTimelineCard(days: Map<LocalDate, Social.Day>) {
    val today = LocalDate.now()
    val dates = (SOCIAL_DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }.filter { days.containsKey(it) }
    if (dates.isEmpty()) return
    val card = Card(getString(R.string.social_when_out))
    card.subtitle(getString(R.string.last_days, SOCIAL_DAYS))
    card.keyItem(stat.color, getString(R.string.social_key_out))
    Social.homes(requireContext()).forEachIndexed { slot, home -> if (home != null) card.keyItem(homeColors[slot], home.name) }
    Social.work(requireContext())?.let { card.keyItem(homeColors[Social.WORK], it.name) }
    card.describe = { key -> socialDayText(key as LocalDate, days.getValue(key)) }
    val zone = java.time.ZoneId.systemDefault()
    card.draw { s ->
        val labelSize = s.sp(12f)
        val left = s.textWidth("Wed 30", labelSize) + s.dp(10f)
        val row = s.dp(26f)
        val bar = s.dp(12f)
        var top = s.dp(16f)
        fun x(date: LocalDate, ms: Long) = left + (ms - date.atStartOfDay(zone).toInstant().toEpochMilli()) / 86_400_000f * (s.width - left)
        listOf(0, 6, 12, 18, 24).forEach { h ->
            val hx = left + h / 24f * (s.width - left)
            s.text(if (h == 24) "" else offsetClock(h * 60).replace(":00", ""), hx, s.sp(11f), faint(130), s.sp(11f),
                if (h == 0) Paint.Align.LEFT else Paint.Align.CENTER)
        }
        dates.reversed().forEach { date ->
            val mid = top + row / 2
            val dayLabel = date.format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault()))
            s.text(dayLabel, 0f, mid + labelSize * 0.35f, faint(170), labelSize, Paint.Align.LEFT)
            s.rect(left, mid - bar / 2, s.width, mid + bar / 2, faint(14), bar / 2)
            days.getValue(date).stretches.forEach { stretch ->
                val color = when (stretch.place) {
                    Social.UNKNOWN -> null
                    Social.OUT -> stat.color
                    else -> ColorUtils.setAlphaComponent(homeColors[stretch.place], 120)
                } ?: return@forEach
                s.rect(x(date, stretch.start), mid - bar / 2, maxOf(x(date, stretch.end), x(date, stretch.start) + s.dp(2f)), mid + bar / 2, color, s.dp(3f))
            }
            s.target(date, RectF(0f, top, s.width, top + row))
            top += row
        }
        s.height = top
    }
}

/** Days under an hour out in the last [SOCIAL_DAYS], as dots, with runs of staying in and going out. */
internal fun StatPageFragment.socialStayInCard(days: Map<LocalDate, Social.Day>) {
    val today = LocalDate.now()
    val dates = (SOCIAL_LONG_DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }
    val tracked = dates.filter { days.containsKey(it) && it < today }
    if (tracked.size < 3) return
    val card = Card(getString(R.string.social_staying_in))
    val stayed = tracked.count { days.getValue(it).stayedIn }
    card.headline(stayed.toString(), getString(R.string.social_stayed_in_of, tracked.size))
    card.keyItem(stat.color, getString(R.string.social_went_out))
    card.keyItem(faint(200), getString(R.string.social_stayed_in), ring = true)
    card.describe = { key -> socialDayText(key as LocalDate, days.getValue(key)) }
    fun longestRun(stayedIn: Boolean): Int {
        var best = 0
        var run = 0
        tracked.forEach { date ->
            run = if (days.getValue(date).stayedIn == stayedIn) run + 1 else 0
            best = maxOf(best, run)
        }
        return best
    }
    card.draw { s ->
        val perRow = 10
        val cell = s.width / perRow
        val radius = minOf(cell * 0.32f, s.dp(10f))
        dates.forEachIndexed { i, date ->
            val cx = i % perRow * cell + cell / 2
            val cy = i / perRow * cell + cell / 2
            val day = days[date]
            when {
                day == null || date == today -> s.circle(cx, cy, s.dp(2f), faint(70))
                day.stayedIn -> s.ring(cx, cy, radius, faint(200), s.dp(2f))
                else -> s.circle(cx, cy, radius, stat.color)
            }
            if (day != null) s.target(date, RectF(cx - cell / 2, cy - cell / 2, cx + cell / 2, cy + cell / 2),
                RectF(cx - radius - s.dp(3f), cy - radius - s.dp(3f), cx + radius + s.dp(3f), cy + radius + s.dp(3f)), radius + s.dp(3f))
        }
        val rows = (dates.size + perRow - 1) / perRow
        var top = rows * cell + s.dp(14f)
        val runs = listOf(
            longestRun(true).toString() to getString(R.string.social_longest_in),
            longestRun(false).toString() to getString(R.string.social_longest_out),
        )
        val gap = s.dp(8f)
        val width = (s.width - gap) / 2
        runs.forEachIndexed { i, (value, label) ->
            val x = i * (width + gap)
            s.rect(x, top, x + width, top + s.dp(56f), faint(18), s.dp(14f))
            s.text(value, x + width / 2, top + s.dp(26f), ink, s.sp(17f), bold = true)
            s.text(label, x + width / 2, top + s.dp(45f), faint(150), s.sp(12f))
        }
        top += s.dp(56f)
        s.height = top
    }
}

/** Mood by how long you were out that day. */
internal fun StatPageFragment.socialMoodCard(days: Map<LocalDate, Social.Day>) {
    val mood = stats.firstOrNull { it.id == MoodStore.MOOD } ?: return
    val today = LocalDate.now()
    val past = days.filterKeys { it < today }
    if (past.count { (date, _) -> valueOf(mood, date) != null } < SLEEP_MIN_NIGHTS) return
    val card = Card(getString(R.string.social_and_mood))
    card.keyItem(mood.color, mood.name)
    val names = listOf(R.string.social_under_1, R.string.social_1_3, R.string.social_3_6, R.string.social_6_plus).map { getString(it) }
    fun bucket(day: Social.Day): Int {
        val hours = day.outMs / 3_600_000.0
        return when {
            hours < 1 -> 0
            hours < 3 -> 1
            hours < 6 -> 2
            else -> 3
        }
    }
    val averages = (0 until 4).map { b ->
        past.filter { bucket(it.value) == b }.keys.mapNotNull { valueOf(mood, it) }
            .let { values -> values.takeIf { it.isNotEmpty() }?.average()?.toFloat() to values.size }
    }
    card.describe = { key ->
        val (average, count) = averages[key as Int]
        listOf(names[key], getString(R.string.stat_total, mood.name, average?.let(::decimal) ?: "–"),
            resources.getQuantityString(R.plurals.day_count, count, count)).joinToString(" · ")
    }
    card.draw { s ->
        val top = s.dp(6f)
        val bottom = top + s.dp(120f)
        val column = s.width / 4
        fun y(value: Float) = bottom - (value - 1f) / 4f * (bottom - top) - s.dp(2f)
        listOf(1, 3, 5).forEach { g -> s.line(0f, y(g.toFloat()), s.width, y(g.toFloat()), faint(28), s.dp(1f)) }
        val bar = minOf(column * 0.5f, s.dp(30f))
        averages.forEachIndexed { b, (average, _) ->
            val center = b * column + column / 2
            if (average == null) s.rect(center - bar / 2, bottom - s.dp(2f), center + bar / 2, bottom, faint(45), s.dp(1f))
            else s.rect(center - bar / 2, y(average), center + bar / 2, bottom, mood.faceColor(requireContext(), faceFor(mood, average)), s.dp(6f))
            s.text(names[b], center, bottom + s.dp(18f), faint(160), s.sp(12f))
            s.target(b, RectF(b * column, 0f, (b + 1) * column, bottom + s.dp(26f)))
        }
        s.height = bottom + s.dp(26f)
    }
}

/**
 * The average by week (the last 13), month (the last 12) or year, from the stat's first: real values
 * (Money's as each period's total) where it has them. The point still going is a ring.
 */
/** Work's trend: days worked each week, as bars, with the hours there on tapping one */
internal fun StatPageFragment.workTrendCard() {
    val card = Card(getString(R.string.stat_trend))
    val today = LocalDate.now()
    val monday = today.with(DayOfWeek.MONDAY)
    val first = firstDate ?: return card.showNote(getString(R.string.no_data))
    val count = minOf(WORK_TREND_WEEKS.toLong(), ChronoUnit.WEEKS.between(first.with(DayOfWeek.MONDAY), monday) + 1).toInt()
    val weeks = (count - 1 downTo 0).map { monday.minusWeeks(it.toLong()) }
    val social = Social.days(requireContext())
    fun daysIn(start: LocalDate) = (0..6L).map { start.plusDays(it) }.filter { it <= today }
    val worked = weeks.map { start -> daysIn(start).count { date -> dayOf(date)?.face(stat.id)?.let { !stat.isBlank(it) } == true } }
    val hours = weeks.map { start -> daysIn(start).sumOf { social[it]?.workMs ?: 0L } }
    card.subtitle(getString(R.string.work_days_a_week))
    card.describe = { key ->
        val i = key as Int
        listOf(getString(R.string.week_of, weeks[i].format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))),
            getString(R.string.stat_label, getString(R.string.days), worked[i].toString()),
            getString(R.string.stat_label, getString(R.string.time), duration(hours[i]))).joinToString("\n")
    }
    val color = stat.themeColor
    card.draw { s ->
        val top = s.dp(18f)
        val bottom = top + s.dp(100f)
        val axis = s.dp(18f)
        val column = (s.width - axis) / weeks.size
        fun yOf(days: Int) = bottom - days / 7f * (bottom - top)
        listOf(0, 5, 7).forEach { d ->
            s.line(axis, yOf(d), s.width, yOf(d), faint(if (d == 0) 40 else 22), s.dp(1f))
            s.text(d.toString(), 0f, yOf(d) + s.sp(4f), faint(130), s.sp(11f), Paint.Align.LEFT)
        }
        weeks.forEachIndexed { i, _ ->
            val x = axis + i * column
            val bar = minOf(column * 0.6f, s.dp(26f))
            val cx = x + column / 2
            if (worked[i] == 0) s.rect(cx - bar / 2, bottom - s.dp(2f), cx + bar / 2, bottom, faint(45), s.dp(1f))
            else {
                s.rect(cx - bar / 2, yOf(worked[i]), cx + bar / 2, bottom, if (i == weeks.lastIndex) color else ColorUtils.setAlphaComponent(color, 170), s.dp(5f))
                s.text(worked[i].toString(), cx, yOf(worked[i]) - s.dp(5f), faint(170), s.sp(11f))
            }
            s.target(i, RectF(x, 0f, x + column, bottom + s.dp(20f)))
        }
        labelIndices(weeks.size, 3).forEach { i ->
            s.text(weeks[i].format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())), axis + i * column + column / 2, bottom + s.dp(16f), faint(140), s.sp(11f))
        }
        s.height = bottom + s.dp(22f)
    }
}
