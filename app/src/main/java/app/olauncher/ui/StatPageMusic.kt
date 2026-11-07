package app.olauncher.ui

import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.helper.Music
import app.olauncher.helper.Spending
import app.olauncher.helper.StatKind
import app.olauncher.helper.dpToPx
import app.olauncher.ui.CardPageFragment.Card
import app.olauncher.ui.StatPageFragment.Companion.HABIT_DAYS
import app.olauncher.ui.StatPageFragment.Companion.KNOWN_PLAYERS
import app.olauncher.ui.StatPageFragment.Companion.MIN_TAKEN_DAYS
import app.olauncher.ui.StatPageFragment.Companion.MUSIC_ARTISTS
import app.olauncher.ui.StatPageFragment.Companion.MUSIC_BLOCK_MINUTES
import app.olauncher.ui.StatPageFragment.Companion.MUSIC_DAYS
import app.olauncher.ui.StatPageFragment.Companion.MUSIC_NEW_AFTER
import app.olauncher.ui.StatPageFragment.Companion.MUSIC_TRACKS
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// Music: listening each day, when, which artists and tracks, new finds, mood and apps, as cards on [StatPageFragment]

/**
 * Each day's listening as a level meter, a block per [MUSIC_BLOCK_MINUTES] minutes, lighter at the
 * bottom and deeper up, the last [HABIT_DAYS] days.
 */
internal fun StatPageFragment.musicDaysCard() {
    val days = Music.days(requireContext())
    if (days.isEmpty()) return
    val today = LocalDate.now()
    val shown = (HABIT_DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }
    val card = Card(getString(R.string.music_each_day))
    val shades = StatKind.MUSIC.shades!!
    val blocks = shown.map { date -> ((days[date]?.ms ?: 0L) / 60_000L / MUSIC_BLOCK_MINUTES).toInt() }
    val most = maxOf(blocks.maxOrNull() ?: 0, 6)
    card.describe = { key ->
        val date = key as LocalDate
        val day = days[date]
        (listOf(dateText(date), getString(R.string.stat_label, getString(R.string.time), duration(day?.ms ?: 0L))) +
            day?.artists?.entries?.sortedByDescending { it.value }?.take(3)?.map { getString(R.string.stat_label, it.key, duration(it.value)) }.orEmpty()).joinToString("\n")
    }
    card.draw { s ->
        val column = s.width / shown.size
        val gap = s.dp(2f)
        val block = minOf(s.dp(7f), s.dp(130f) / most - gap)
        val bottom = most * (block + gap)
        shown.forEachIndexed { i, date ->
            val x = i * column + column * 0.18f
            val w = column * 0.64f
            val count = blocks[i]
            if (count == 0) s.rect(x, bottom - s.dp(2f), x + w, bottom, faint(45), s.dp(1f))
            repeat(minOf(count, most)) { k ->
                val y = bottom - (k + 1) * (block + gap) + gap
                s.rect(x, y, x + w, y + block, ColorUtils.blendARGB(shades.first, shades.second, k / most.toFloat()), s.dp(2f))
            }
            s.target(date, RectF(i * column, 0f, (i + 1) * column, bottom + s.dp(24f)), RectF(i * column, 0f, (i + 1) * column, bottom + s.dp(2f)))
        }
        labelIndices(shown.size, 3).forEach { i ->
            s.text(shown[i].dayOfMonth.toString(), i * column + column / 2, bottom + s.dp(18f), faint(140), s.sp(12f))
        }
        s.height = bottom + s.dp(24f)
    }
    card.keyItem(ColorUtils.blendARGB(shades.first, shades.second, 0.5f), getString(R.string.music_block, MUSIC_BLOCK_MINUTES))
}

/** The last [MUSIC_DAYS] days of listening, days without any included as none */
internal fun StatPageFragment.recentMusic(): Map<LocalDate, Music.Day> {
    val days = Music.days(requireContext())
    val today = LocalDate.now()
    return days.filterKeys { it > today.minusDays(MUSIC_DAYS.toLong()) }
}

internal fun StatPageFragment.musicShade(place: Float): Int = StatKind.MUSIC.shades!!.let { ColorUtils.blendARGB(it.second, it.first, place.coerceIn(0f, 1f)) }

/**
 * Today's listening, then this week's, the usual day and the days in a row with music. False when
 * there's nothing to show (no access, or nothing played yet), leaving the other music cards out.
 */
internal fun StatPageFragment.musicCard(): Boolean {
    val context = requireContext()
    val card = Card(getString(R.string.music_listening))
    if (!Spending.isEnabled(context)) {
        spendingShownOn = false
        card.showNote(getString(R.string.music_off))
        card.chipRow(listOf(getString(R.string.spending_turn_on) to ink), null) { Spending.openSettings(context) }
        return false
    }
    spendingShownOn = true
    val days = Music.days(context)
    val today = LocalDate.now()
    val todays = days[today]
    card.headline(duration(todays?.ms ?: 0), todays?.artists?.maxByOrNull { it.value }?.key
        ?.let { getString(R.string.music_today_top, it) } ?: getString(R.string.music_today))
    if (days.isEmpty()) {
        card.showNote(getString(R.string.music_none))
        return false
    }
    val first = days.keys.min()
    val monday = app.olauncher.helper.Week.start(today)
    val week = days.filterKeys { it >= monday }.values.sumOf { it.ms }
    val span = minOf(MUSIC_DAYS.toLong(), ChronoUnit.DAYS.between(first, today) + 1)
    val usual = (0 until span).sumOf { days[today.minusDays(it)]?.ms ?: 0L } / span
    // Days in a row with a minute or more, counting today only once it's had some
    var streak = 0
    var day = if ((todays?.ms ?: 0) >= 60_000) today else today.minusDays(1)
    while ((days[day]?.ms ?: 0) >= 60_000) {
        streak++
        day = day.minusDays(1)
    }
    tiles(card, null, listOf(
        duration(week) to getString(R.string.this_week).lowercase(Locale.getDefault()),
        duration(usual) to getString(R.string.a_usual_day),
        streak.toString() to getString(R.string.music_streak),
    ))
    return true
}

/** The hours of the day listened in over the last [MUSIC_DAYS] days, round a clock, and when most of it is */
internal fun StatPageFragment.musicHoursCard() {
    val recent = recentMusic().values.filter { it.hours.size == 24 }
    val hours = (0 until 24).map { h -> recent.sumOf { it.hours[h] } }
    val total = hours.sum()
    if (total < 10 * 60_000L) return
    val card = Card(getString(R.string.music_when))
    card.subtitle(getString(R.string.last_days, MUSIC_DAYS))
    // The three hours together with the most in them
    val peak = (0 until 24).maxBy { h -> (0..2).sumOf { hours[(h + it) % 24] } }
    card.headline(getString(R.string.music_peak, hourText(peak), hourText((peak + 3) % 24)), getString(R.string.music_peak_note,
        ((0..2).sumOf { hours[(peak + it) % 24] } * 100 / total).toInt()))
    val most = hours.max().toFloat()
    card.describe = { key ->
        val h = key as Int
        listOf(getString(R.string.music_hour, hourText(h), hourText((h + 1) % 24)),
            getString(R.string.stat_label, getString(R.string.time), duration(hours[h])),
            getString(R.string.stat_label, getString(R.string.share), "${(hours[h] * 100 / total)}%")).joinToString("\n")
    }
    card.draw { s ->
        val size = minOf(s.width, s.dp(240f))
        val cx = s.width / 2
        val cy = size / 2
        val inner = size * 0.2f
        val outer = size / 2 - s.dp(18f)
        s.ring(cx, cy, inner - s.dp(4f), faint(30), s.dp(1f))
        (0 until 24).forEach { h ->
            // Midnight at the top, going round clockwise
            val angle = Math.toRadians(h * 15.0 + 7.5 - 90)
            val share = hours[h] / most
            val length = (outer - inner) * share
            val cos = Math.cos(angle).toFloat()
            val sin = Math.sin(angle).toFloat()
            s.line(cx + cos * inner, cy + sin * inner, cx + cos * (inner + (outer - inner)), cy + sin * (inner + (outer - inner)), faint(18), s.dp(7f))
            if (hours[h] > 0) s.line(cx + cos * inner, cy + sin * inner, cx + cos * (inner + maxOf(length, s.dp(2f))), cy + sin * (inner + maxOf(length, s.dp(2f))),
                musicShade(1f - share * 0.85f), s.dp(7f))
            val mid = (inner + outer) / 2
            val x = cx + cos * mid
            val y = cy + sin * mid
            s.target(h, RectF(x - s.dp(12f), y - s.dp(12f), x + s.dp(12f), y + s.dp(12f)))
        }
        listOf(0 to "12am", 6 to "6am", 12 to "12pm", 18 to "6pm").forEach { (h, label) ->
            val angle = Math.toRadians(h * 15.0 - 90)
            val r = outer + s.dp(11f)
            s.text(label, cx + Math.cos(angle).toFloat() * r, cy + Math.sin(angle).toFloat() * r + s.sp(4f), faint(140), s.sp(11f))
        }
        s.icon(StatIcons.MUSIC, cx - s.dp(9f), cy - s.dp(9f), s.dp(18f), musicShade(0.2f))
        s.height = size
    }
}

internal fun StatPageFragment.hourText(hour: Int) = when {
    hour == 0 -> "12am"
    hour < 12 -> "${hour}am"
    hour == 12 -> "12pm"
    else -> "${hour - 12}pm"
}

/** The artists listened to most over the last [MUSIC_DAYS] days, with the mood on the days they played */
internal fun StatPageFragment.musicArtistsCard() {
    val recent = recentMusic()
    val totals = recent.values.flatMap { it.artists.entries }.groupBy({ it.key }, { it.value })
        .mapValues { (_, times) -> times.sum() }.entries.sortedByDescending { it.value }
    if (totals.isEmpty()) return
    val all = totals.sumOf { it.value }
    val top = totals.take(MUSIC_ARTISTS)
    val card = Card(getString(R.string.music_top_artists))
    card.subtitle(getString(R.string.last_days, MUSIC_DAYS))
    val mood = stats.firstOrNull { it.id == MoodStore.MOOD }
    card.describe = { key ->
        val (artist, ms) = top[key as Int]
        val playedOn = recent.filterValues { it.artists.containsKey(artist) }.keys
        val moodOn = mood?.let { m -> playedOn.mapNotNull { valueOf(m, it) }.takeIf { it.size >= 2 }?.average()?.toFloat() }
        listOfNotNull(artist, getString(R.string.stat_label, getString(R.string.time), duration(ms)),
            getString(R.string.stat_label, getString(R.string.share), "${ms * 100 / all}%"),
            getString(R.string.stat_label, getString(R.string.days), playedOn.size.toString()),
            moodOn?.let { getString(R.string.stat_label, getString(R.string.mood), decimal(it)) }).joinToString("\n")
    }
    horizontalBars(card, top.mapIndexed { i, (artist, ms) ->
        Triple(artist, (ms / 60_000).toInt().coerceAtLeast(1), musicShade(i / MUSIC_ARTISTS.toFloat()))
    }) { duration(it * 60_000L) }
}

/** The tracks played most over the last [MUSIC_DAYS] days, by plays */
internal fun StatPageFragment.musicTracksCard() {
    val recent = recentMusic().values
    val plays = recent.flatMap { it.plays.entries }.groupBy({ it.key }, { it.value }).mapValues { (_, n) -> n.sum() }
    val times = recent.flatMap { it.tracks.entries }.groupBy({ it.key }, { it.value }).mapValues { (_, t) -> t.sum() }
    val top = plays.entries.filter { it.value >= 2 }.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { times[it.key] ?: 0 })
        .take(MUSIC_TRACKS)
    if (top.isEmpty()) return
    val context = requireContext()
    val card = Card(getString(R.string.music_on_repeat))
    card.subtitle(getString(R.string.last_days, MUSIC_DAYS))
    card.scene.isVisible = false
    val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    top.forEachIndexed { i, (key, count) ->
        val (title, artist) = Music.splitTrack(key)
        list.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            updatePadding(top = 7.dpToPx(), bottom = 7.dpToPx())
            addView(card.textView(20f, 255).apply {
                text = (i + 1).toString()
                setTypeface(null, Typeface.BOLD)
                setTextColor(musicShade(i / MUSIC_TRACKS.toFloat()))
            }, LinearLayout.LayoutParams(34.dpToPx(), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(card.textView(14f, 240).apply {
                    text = title
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                })
                if (artist.isNotEmpty()) addView(card.textView(12f, 140).apply {
                    text = artist
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(card.textView(13f, 170).apply {
                text = resources.getQuantityString(R.plurals.music_plays, count, count)
                updatePadding(left = 10.dpToPx())
            })
        })
    }
    card.root.addView(list, card.root.indexOfChild(card.scene))
}

/** Artists first heard in the last [MUSIC_DAYS] days, once there's been a while of listening before them */
internal fun StatPageFragment.musicNewCard() {
    val days = Music.days(requireContext())
    if (days.isEmpty()) return
    val today = LocalDate.now()
    val firstHeard = mutableMapOf<String, LocalDate>()
    days.entries.sortedBy { it.key }.forEach { (date, day) -> day.artists.keys.forEach { firstHeard.putIfAbsent(it, date) } }
    val since = maxOf(days.keys.min().plusDays(MUSIC_NEW_AFTER), today.minusDays(MUSIC_DAYS.toLong()))
    val recent = recentMusic()
    val found = firstHeard.filterValues { it > since }.keys
        .associateWith { artist -> recent.values.sumOf { it.artists[artist] ?: 0L } }
        .entries.sortedByDescending { it.value }.take(5)
    if (found.isEmpty()) return
    val card = Card(getString(R.string.music_new))
    card.subtitle(resources.getQuantityString(R.plurals.music_new_count, found.size, found.size))
    card.describe = { key ->
        val (artist, ms) = found[key as Int]
        listOf(artist, getString(R.string.stat_label, getString(R.string.music_first_heard), dateText(firstHeard.getValue(artist))),
            getString(R.string.stat_label, getString(R.string.time), duration(ms))).joinToString("\n")
    }
    horizontalBars(card, found.map { (artist, ms) -> Triple(artist, (ms / 60_000).toInt().coerceAtLeast(1), musicShade(0.25f)) }) { duration(it * 60_000L) }
}

/** Mood on days with no music, some and a lot, so it's clear whether listening goes with a better day */
internal fun StatPageFragment.musicMoodCard() {
    val mood = stats.firstOrNull { it.id == MoodStore.MOOD } ?: return
    val days = Music.days(requireContext())
    if (days.isEmpty()) return
    val first = days.keys.min()
    val today = LocalDate.now()
    val groups = Array(3) { mutableListOf<Float>() }
    var date = first
    while (date <= today) {
        val m = valueOf(mood, date)
        if (m != null) {
            val ms = days[date]?.ms ?: 0L
            groups[when {
                ms < 60_000 -> 0
                ms < 3_600_000 -> 1
                else -> 2
            }].add(m)
        }
        date = date.plusDays(1)
    }
    if (groups.count { it.size >= MIN_TAKEN_DAYS } < 2) return
    val card = Card(getString(R.string.music_and_mood))
    val averages = groups.map { g -> g.takeIf { it.size >= MIN_TAKEN_DAYS }?.average()?.toFloat() }
    val none = averages[0]
    val most = averages[2] ?: averages[1]
    if (none != null && most != null && abs(most - none) >= 0.1f)
        card.headline((if (most > none) "+" else "−") + decimal(abs(most - none)),
            getString(if (most > none) R.string.music_mood_better else R.string.music_mood_worse, getString(if (averages[2] != null) R.string.music_lots else R.string.music_some)))
    val labels = listOf(R.string.music_none_day, R.string.music_some, R.string.music_lots)
    tiles(card, getString(R.string.music_mood_caption), averages.indices.mapNotNull { i ->
        averages[i]?.let { decimal(it) to getString(labels[i]) }
    })
}

/** Which apps the listening came from over the last [MUSIC_DAYS] days */
internal fun StatPageFragment.musicAppsCard() {
    val apps = recentMusic().values.flatMap { it.apps.entries }.groupBy({ it.key }, { it.value })
        .mapValues { (_, t) -> t.sum() }.entries.sortedByDescending { it.value }
    if (apps.size < 2) return
    val context = requireContext()
    val total = apps.sumOf { it.value }.toFloat()
    val names = apps.map { (pkg, _) ->
        runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
            ?: KNOWN_PLAYERS[pkg]
            ?: pkg.split('.').let { parts -> parts.getOrNull(parts.size - 2) ?: parts.last() }.replaceFirstChar { it.uppercase() }
    }
    val card = Card(getString(R.string.music_apps))
    card.subtitle(getString(R.string.last_days, MUSIC_DAYS))
    val colors = apps.indices.map { musicShade(it / maxOf(apps.size - 1, 1).toFloat() * 0.8f) }
    apps.forEachIndexed { i, (_, ms) -> card.keyItem(colors[i], "${names[i]} · ${(ms * 100 / total).roundToInt()}%") }
    card.describe = { key ->
        val i = key as Int
        listOf(names[i], getString(R.string.stat_label, getString(R.string.time), duration(apps[i].value))).joinToString("\n")
    }
    card.draw { s ->
        val h = s.dp(18f)
        var x = 0f
        apps.forEachIndexed { i, (_, ms) ->
            val w = s.width * ms / total
            s.rect(x + if (i > 0) s.dp(1.5f) else 0f, 0f, x + w, h, colors[i], s.dp(5f))
            s.target(i, RectF(x, 0f, x + w, h + s.dp(8f)))
            x += w
        }
        s.height = h + s.dp(4f)
    }
}
