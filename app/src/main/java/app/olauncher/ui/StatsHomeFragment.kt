package app.olauncher.ui

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.helper.StatInfo
import app.olauncher.helper.allStats
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.setStatDot
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

/**
 * Every stat together, at the top of the stat list. The day (its mood and thoughts, editable) under the
 * last days' grid, the weekly and monthly lines, then what the single pages can't show: a note from
 * before, how this week compares, what goes with a good mood, and any two stats set against each other.
 * Which stats the grid and lines show is picked in a bubble floating at the bottom.
 */
class StatsHomeFragment : CardPageFragment() {

    private lateinit var cardList: LinearLayout
    private lateinit var scrollView: ScrollView

    /** The Compare card, and how to redraw it, so an insight can open its pair there */
    private var compareRoot: View? = null
    private var compareRedraw: (() -> Unit)? = null
    override val cards: LinearLayout get() = cardList

    private lateinit var store: MoodStore
    private var data = MoodStore.Data()
    private var stats = emptyList<StatInfo>()

    /** Each stat's score (1 to 5, higher better) on each day it has a face */
    private var scores = emptyMap<String, Map<LocalDate, Int>>()

    private var selectedDate: LocalDate = LocalDate.now()
    private var stretchesBack = 0
    private var weeklyYear = LocalDate.now().get(WEEKS.weekBasedYear())
    private var monthlyYear = LocalDate.now().year
    private var compareX: String? = null
    private var compareY: String = MoodStore.MOOD
    /** 0 compares the same day, 1 the second stat the day after */
    private var compareLag = 0

    /** The date the thoughts box holds, so redrawing doesn't overwrite typing */
    private var noteDate: LocalDate? = null
    private var noteInput: EditText? = null

    private lateinit var bubble: LinearLayout
    private lateinit var scrim: View
    private var bubbleOpen = false

    /** The cards that follow the picked stats, redrawn when the pick changes */
    private val redraws = mutableListOf<() -> Unit>()

    override fun colorOfLabel(label: String): Int? = stats.firstOrNull { it.name == label }?.color

    override fun iconOfLabel(label: String): String? = stats.firstOrNull { it.name == label }?.let { StatIcons.forStat(it) }

    private val shown: List<StatInfo> get() = stats.filter { it.id !in data.hiddenInGraph }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        cardList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            updatePadding(top = 48.dpToPx(), bottom = 120.dpToPx())
        }
        val scroll = ScrollView(context).also { scrollView = it }.apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            addView(cardList)
        }
        bubble = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        // Over the cards while the bubble's open: touching anywhere outside it closes it
        scrim = View(context).apply {
            isVisible = false
            setBackgroundColor(0x33000000)
            setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) closeBubble()
                true
            }
        }
        val page = FrameLayout(context).apply {
            setBackgroundColor(context.getColorFromAttr(R.attr.primaryShadeDarkColor))
            isClickable = true
            addView(scroll)
            addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(bubble, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                setMargins(16.dpToPx(), 0, 16.dpToPx(), 28.dpToPx())
            })
        }
        return PullDismissLayout(context).apply {
            addView(page)
            onDismiss = { if (isAdded) findNavController().popBackStack() }
            sideDismiss = arguments?.getInt(app.olauncher.helper.SwipeTargets.SWIPE_BACK) ?: 0
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = MoodStore(requireContext())
    }

    override fun onResume() {
        super.onResume()
        noteDate = null
        render()
    }

    override fun onPause() {
        saveNote()
        super.onPause()
    }

    private fun load() {
        data = store.load()
        stats = requireContext().allStats(data)
        // Every stat at once makes the lines a tangle: the first time, start with the first few
        val prefs = requireContext().getSharedPreferences(PREFS, 0)
        if (!prefs.getBoolean(PICKED, false)) {
            prefs.edit().putBoolean(PICKED, true).apply()
            if (data.hiddenInGraph.isEmpty() && stats.size > FIRST_PICK) {
                store.setHiddenInGraph(stats.drop(FIRST_PICK).map { it.id }.toSet())
                data = store.load()
            }
        }
        scores = stats.associate { stat ->
            stat.id to data.days.entries.mapNotNull { (key, day) ->
                val face = day.face(stat.id) ?: return@mapNotNull null
                val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@mapNotNull null
                date to MoodStore.score(face, stat.inverted)
            }.toMap()
        }
    }

    private fun render() {
        load()
        cardList.removeAllViews()
        redraws.clear()
        header()
        dailyCard()
        lineCard(weekly = true)
        lineCard(weekly = false)
        rememberCard()
        comparables = buildComparables()
        insightsCard()
        thisWeekCard()
        compareCard()
        streaksCard()
        renderBubble()
    }

    private fun redrawPicked() {
        load()
        redraws.forEach { it() }
        renderBubble()
    }

    private fun header() {
        val context = requireContext()
        cardList.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dpToPx(), 0, 12.dpToPx(), 4.dpToPx())
            addView(TextView(context, null, 0, R.style.TextLarge).apply {
                text = "‹"
                setTextColor(ink)
                alpha = 0.7f
                contentDescription = getString(R.string.back)
                setPadding(12.dpToPx(), 4.dpToPx(), 12.dpToPx(), 4.dpToPx())
                setOnClickListener { findNavController().popBackStack() }
            })
            addView(View(context).apply { background = StatIcons.drawable(StatIcons.HOME, ink, 22.dpToPx()) },
                LinearLayout.LayoutParams(22.dpToPx(), 22.dpToPx()).apply { marginEnd = 12.dpToPx() })
            addView(TextView(context, null, 0, R.style.TextLarge).apply {
                text = getString(R.string.stats_home)
                textSize = 24f
                setTextColor(ink)
            })
        })
    }

    // --- The days: a grid of the last days, and the picked day's mood and thoughts ---

    private fun dailyCard() {
        val context = requireContext()
        val card = Card(getString(R.string.stat_daily))
        val heatmap = StatHeatmapView(context)
        val day = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        card.addBody(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(heatmap)
            addView(day, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 16.dpToPx()
            })
        })
        heatmap.onPointHeld = { point ->
            saveNote()
            openDay(LocalDate.ofEpochDay(point.toLong()))
        }
        heatmap.onPointSelected = { point ->
            point?.let {
                saveNote()
                selectedDate = LocalDate.ofEpochDay(it.toLong())
                renderDay(day)
            }
            card.showDetails(point?.let { dayDetails(LocalDate.ofEpochDay(it.toLong())) })
        }
        fun draw(clear: Boolean) {
            val today = LocalDate.now()
            val first = dates().firstOrNull() ?: today
            val furthest = (ChronoUnit.DAYS.between(first, today) / GRID_DAYS).toInt().coerceAtLeast(0)
            stretchesBack = stretchesBack.coerceIn(0, furthest)
            val end = today.minusDays(GRID_DAYS.toLong() * stretchesBack)
            val start = end.minusDays(GRID_DAYS - 1L)
            val format = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
            card.stepper(if (stretchesBack == 0) getString(R.string.last_days, GRID_DAYS) else getString(R.string.graph_month_range, start.format(format), end.format(format)),
                (stretchesBack < furthest), (stretchesBack > 0), GRID_DAYS) { by ->
                stretchesBack -= by
                draw(true)
            }
            heatmap.setData(
                shown.map { stat ->
                    val cells = (0 until GRID_DAYS).mapNotNull { i ->
                        val date = start.plusDays(i.toLong())
                        val face = data.days[date.toString()]?.face(stat.id)?.takeIf { !stat.isBlank(it) } ?: return@mapNotNull null
                        date.toEpochDay().toInt() to stat.faceColor(context, face)
                    }.toMap()
                    StatHeatmapView.Row(stat.name, stat.color, cells, StatIcons.forStat(stat))
                },
                start.toEpochDay().toInt(), end.toEpochDay().toInt(),
                label = { LocalDate.ofEpochDay(it.toLong()).dayOfMonth.toString() },
                clearSelection = clear,
            )
            if (clear) card.showDetails(null)
        }
        draw(false)
        renderDay(day)
        redraws.add { draw(false) }
    }

    /** The picked day: its date, Mood's faces to tap and its thoughts to write. */
    private fun renderDay(container: LinearLayout) {
        val context = requireContext()
        container.removeAllViews()
        val mood = stats.firstOrNull { it.id == MoodStore.MOOD } ?: return
        val today = LocalDate.now()
        val day = data.days[selectedDate.toString()]
        container.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context, null, 0, R.style.TextSmall).apply {
                text = if (selectedDate == today) getString(R.string.today) else dateText(selectedDate)
                textSize = 15f
                setTextColor(ink)
                setTypeface(null, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (selectedDate != today) addView(TextView(context, null, 0, R.style.TextSmall).apply {
                text = getString(R.string.today)
                textSize = 13f
                setTextColor(ink)
                setBackgroundResource(R.drawable.bg_stat_chip)
                setPadding(12.dpToPx(), 5.dpToPx(), 12.dpToPx(), 5.dpToPx())
                setOnClickListener {
                    saveNote()
                    selectedDate = today
                    renderDay(container)
                }
            })
        })
        val chosen = day?.face(MoodStore.MOOD)
        container.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            updatePadding(top = 8.dpToPx())
            val names = resources.getStringArray(R.array.mood_names)
            mood.faces.forEach { face ->
                addView(PixelFaceView(context).apply {
                    this.mood = face
                    color = mood.faceColor(context, face)
                    alpha = if (face == chosen) 1f else 0.3f
                    contentDescription = names[face]
                    val pad = 6.dpToPx()
                    setPadding(pad, pad, pad, pad)
                    setOnClickListener {
                        store.setFace(selectedDate, MoodStore.MOOD, if (chosen == face) null else face)
                        saveNote()
                        load()
                        renderDay(container)
                        redraws.forEach { it() }
                    }
                }, LinearLayout.LayoutParams(0, 46.dpToPx(), 1f))
            }
        })
        val input = EditText(context, null, 0, R.style.TextSmall).apply {
            setText(day?.note.orEmpty())
            hint = getString(R.string.thoughts_hint)
            textSize = 15f
            setTextColor(ink)
            setHintTextColor(faint(110))
            background = GradientDrawable().apply {
                cornerRadius = 16.dpToPx().toFloat()
                setColor(faint(14))
            }
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            setPadding(14.dpToPx(), 12.dpToPx(), 14.dpToPx(), 12.dpToPx())
            setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveNote() }
        }
        noteInput = input
        noteDate = selectedDate
        container.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 10.dpToPx()
        })
    }

    private fun saveNote() {
        val date = noteDate ?: return
        val input = noteInput ?: return
        val note = MoodStore.sanitizeNote(input.text?.toString())
        if (note != store.load().days[date.toString()]?.note) store.setNote(date, note)
    }

    /** Each picked stat's face on [date], by name */
    private fun dayDetails(date: LocalDate): String {
        val day = data.days[date.toString()]
        val parts = mutableListOf(dateText(date))
        shown.forEach { stat ->
            val face = day?.face(stat.id) ?: return@forEach
            parts.add(getString(R.string.stat_label, stat.name, faceName(stat, face)))
        }
        if (parts.size == 1) parts.add(getString(R.string.no_data))
        return parts.joinToString("\n")
    }

    private fun faceName(stat: StatInfo, face: Int): String {
        val place = stat.faces.indexOf(face)
        stat.labels.getOrNull(place)?.takeIf { it.isNotEmpty() }?.let { return it }
        return when {
            stat.id == MoodStore.MOOD -> resources.getStringArray(R.array.mood_names)[face]
            stat.id == MoodStore.USAGE -> resources.getStringArray(R.array.usage_faces)[face]
            stat.isBlank(face) -> getString(R.string.none_taken)
            stat.medicine && stat.faces.size == 2 -> getString(R.string.taken)
            stat.medicine -> getString(R.string.dose_number, place)
            else -> MoodStore.score(face, stat.inverted).toString()
        }
    }

    private fun dates(): List<LocalDate> = data.days.keys.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.sorted()

    // --- Weekly and monthly lines ---

    private fun lineCard(weekly: Boolean) {
        val context = requireContext()
        val card = Card(getString(if (weekly) R.string.graph_weekly else R.string.graph_monthly))
        val chart = StatChartView(context)
        card.addBody(chart)
        fun pointOf(date: LocalDate, year: Int): Int? =
            if (weekly) date.get(WEEKS.weekOfWeekBasedYear()).takeIf { date.get(WEEKS.weekBasedYear()) == year }
            else date.monthValue.takeIf { date.year == year }
        fun values(year: Int): Map<String, Map<Int, Float>> = shown.associate { stat ->
            stat.id to scores[stat.id].orEmpty().entries.mapNotNull { (date, score) -> pointOf(date, year)?.let { it to score } }
                .groupBy({ it.first }, { it.second }).mapValues { (_, list) -> list.average().toFloat() }
        }
        fun draw(clear: Boolean) {
            val today = LocalDate.now()
            val thisYear = if (weekly) today.get(WEEKS.weekBasedYear()) else today.year
            val firstDate = dates().firstOrNull() ?: today
            val firstYear = minOf(if (weekly) firstDate.get(WEEKS.weekBasedYear()) else firstDate.year, thisYear)
            val year = (if (weekly) weeklyYear else monthlyYear).coerceIn(firstYear, thisYear)
            card.stepper(year.toString(), (year > firstYear), (year < thisYear), 1) { by ->
                if (weekly) weeklyYear = year + by else monthlyYear = year + by
                draw(true)
            }
            val all = values(year)
            val points = all.values.flatMap { it.keys }
            val first = points.minOrNull()
            val last = pointOf(today, year) ?: points.maxOrNull()
            chart.isVisible = first != null && last != null
            if (first == null || last == null) {
                card.note.isVisible = true
                card.note.text = getString(R.string.no_data)
                return
            }
            card.note.isVisible = false
            chart.setData(
                shown.map { StatChartView.Series(it.color, all[it.id].orEmpty(), true) },
                first, maxOf(first, last), min = 1, max = 5,
                partial = pointOf(today, year),
                label = if (weekly) { w -> getString(R.string.week_short, w) } else { m -> Month.of(m).getDisplayName(TextStyle.SHORT, Locale.getDefault()) },
                clearSelection = clear,
            )
            chart.onPointSelected = { point ->
                card.showDetails(point?.let { p ->
                    val title = if (weekly) getString(R.string.week_number, p)
                    else YearMonth.of(year, p).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
                    (listOf(if (p == pointOf(today, year)) getString(R.string.graph_so_far, title) else title) +
                        shown.mapNotNull { s -> all[s.id]?.get(p)?.let { getString(R.string.stat_label, s.name, decimal(it)) } }).joinToString("\n")
                })
            }
            if (clear) card.showDetails(null)
        }
        draw(false)
        redraws.add { draw(false) }
    }

    // --- What the stat pages can't show ---

    /** A note written more than a week ago, a different one each day */
    private fun rememberCard() {
        val today = LocalDate.now()
        val old = data.days.entries.mapNotNull { (key, day) ->
            val note = day.note ?: return@mapNotNull null
            val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@mapNotNull null
            (date to note).takeIf { date < today.minusDays(REMEMBER_AFTER_DAYS) }
        }.sortedBy { it.first }
        if (old.isEmpty()) return
        val (date, note) = old[Random(today.toEpochDay()).nextInt(old.size)]
        val card = Card(getString(R.string.remember))
        card.subtitle(date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())))
        val context = requireContext()
        card.addBody(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            data.days[date.toString()]?.face(MoodStore.MOOD)?.let { face ->
                val mood = stats.first { it.id == MoodStore.MOOD }
                addView(PixelFaceView(context).apply {
                    this.mood = face
                    color = mood.faceColor(context, face)
                }, LinearLayout.LayoutParams(34.dpToPx(), 34.dpToPx()).apply { marginEnd = 14.dpToPx() })
            }
            addView(card.textView(15f, 235).apply {
                text = getString(R.string.quoted, note)
                setLineSpacing(3.dpToPx().toFloat(), 1f)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
    }

    /** Each picked stat's average this week against last week's, better or worse */
    private fun thisWeekCard() {
        val card = Card(getString(R.string.this_week))
        card.subtitle(getString(R.string.vs_last_week))
        val good = requireContext().getColor(R.color.moodHappy)
        val bad = requireContext().getColor(R.color.moodSad)
        fun draw() {
            val today = LocalDate.now()
            val monday = today.with(DayOfWeek.MONDAY)
            val rows = shown.filter { !it.isMedicine && it.kind != app.olauncher.helper.StatKind.WORK }.mapNotNull { stat ->
                val byDate = scores[stat.id].orEmpty()
                val now = byDate.filterKeys { it >= monday && it <= today }.values.takeIf { it.isNotEmpty() }?.average() ?: return@mapNotNull null
                val before = byDate.filterKeys { it >= monday.minusDays(7) && it < monday }.values.takeIf { it.isNotEmpty() }?.average()
                Triple(stat, now.toFloat(), before?.toFloat())
            }
                // Biggest rise first, down to the biggest fall; new ones (no last week) after
                .sortedWith(compareBy<Triple<StatInfo, Float, Float?>> { it.third == null }.thenByDescending { (_, now, before) -> before?.let { now - it } ?: 0f })
            if (rows.isEmpty()) return card.showNote(getString(R.string.no_data))
            card.describe = { key ->
                val (stat, now, before) = rows[key as Int]
                listOf(stat.name, getString(R.string.stat_label, getString(R.string.this_week), decimal(now)),
                    getString(R.string.stat_label, getString(R.string.last_week), before?.let(::decimal) ?: "–")).joinToString("\n")
            }
            card.draw { s ->
                val rowHeight = s.dp(30f)
                val nameSize = s.sp(13.5f)
                rows.forEachIndexed { i, (stat, now, before) ->
                    val y = i * rowHeight
                    val mid = y + rowHeight / 2
                    s.icon(StatIcons.forStat(stat), 0f, mid - s.dp(7f), s.dp(14f), stat.color)
                    s.text(stat.name, s.dp(24f), mid + nameSize * 0.35f, faint(220), nameSize, Paint.Align.LEFT)
                    // A track from 1 to 5 with this week's spot, and an arrow for the change
                    val left = s.width * 0.48f
                    val right = s.width - s.dp(52f)
                    fun x(v: Float) = left + (v - 1f) / 4f * (right - left)
                    s.line(left, mid, right, mid, faint(30), s.dp(3f))
                    before?.let { s.ring(x(it), mid, s.dp(5.5f), faint(150), s.dp(1.5f)) }
                    s.circle(x(now), mid, s.dp(5.5f), stat.color)
                    val change = before?.let { now - it }
                    val text = when {
                        change == null -> "new"
                        abs(change) < 0.15f -> "="
                        change > 0 -> "▲ ${decimal(change)}"
                        else -> "▼ ${decimal(-change)}"
                    }
                    val color = when {
                        change == null || abs(change) < 0.15f || stat.medicine -> faint(140)
                        change > 0 -> good
                        else -> bad
                    }
                    s.text(text, s.width, mid + s.sp(4f), color, s.sp(12f), Paint.Align.RIGHT, bold = true)
                    s.target(i, RectF(0f, y, s.width, y + rowHeight))
                }
                s.height = rows.size * rowHeight
            }
        }
        draw()
        redraws.add { draw() }
    }

    /** What a stat's day holds, which decides how it's compared */
    private enum class ValueKind { SCORE, AMOUNT, YES_NO }

    /**
     * A stat as something to compare: its value each day ([ValueKind.YES_NO] as 0 or 1), how to write
     * one, and names for its groups.
     */
    private class Measured(
        val key: String,
        val name: String,
        val color: Int,
        val icon: String,
        val kind: ValueKind,
        val values: Map<LocalDate, Float>,
        val format: (Float) -> String,
        val yesNo: Pair<String, String> = "no" to "yes",
        val scoreLabel: (Int) -> String = { it.toString() },
    )

    private var comparables: List<Measured> = emptyList()

    private fun hoursText(v: Float) = String.format(Locale.getDefault(), "%.1fh", v)

    private fun numberText(v: Float) = if (abs(v - Math.round(v)) < 0.05f) Math.round(v).toString() else String.format(Locale.getDefault(), "%.1f", v)

    /**
     * Every stat as a [Measured]: faces as scores; Screen time, Sleep, Music, Vibecoding and Social in
     * hours, Money in dollars; things counted from none by their dose (read from labels like "50mg", else
     * their place) and, for medicines and two-face stats, taken or not; Work worked or not; and Steps.
     */
    private fun buildComparables(): List<Measured> {
        val context = requireContext()
        val list = mutableListOf<Measured>()
        fun parse(key: String) = runCatching { LocalDate.parse(key) }.getOrNull()
        stats.forEach { stat ->
            val icon = StatIcons.forStat(stat)
            val faces = data.days.entries.mapNotNull { (key, day) -> day.face(stat.id)?.let { f -> parse(key)?.let { it to f } } }.toMap()
            fun amount(values: Map<LocalDate, Float>, format: (Float) -> String) =
                list.add(Measured(stat.id, stat.name, stat.color, icon, ValueKind.AMOUNT, values, format))
            when (stat.kind) {
                app.olauncher.helper.StatKind.SCREEN -> amount(data.days.entries.mapNotNull { (k, d) -> d.totalUsageMs?.let { ms -> parse(k)?.let { it to ms / 3_600_000f } } }.toMap(), ::hoursText)
                app.olauncher.helper.StatKind.SLEEP -> app.olauncher.helper.Sleep.nights(context).takeIf { it.isNotEmpty() }
                    ?.let { n -> amount(n.mapValues { it.value.asleepMs / 3_600_000f }, ::hoursText) }
                    ?: list.add(scoreComparable(stat, icon))
                app.olauncher.helper.StatKind.MUSIC -> {
                    val played = app.olauncher.helper.Music.days(context).mapValues { it.value.ms / 3_600_000f }
                    amount(played + faces.keys.filter { it !in played }.associateWith { 0f }, ::hoursText)
                }
                app.olauncher.helper.StatKind.VIBECODING -> amount(app.olauncher.helper.Vibecoding.days(context).mapValues { it.value.minutes / 60f }, ::hoursText)
                app.olauncher.helper.StatKind.MONEY -> {
                    val since = app.olauncher.helper.Spending.trackedSince(context)
                    if (since == null) list.add(scoreComparable(stat, icon)) else {
                        val spent = app.olauncher.helper.Spending.dailyCents(context)
                        val today = LocalDate.now()
                        amount((0..ChronoUnit.DAYS.between(since, today)).associate { since.plusDays(it) to (spent[since.plusDays(it)] ?: 0L) / 100f }) { "$" + Math.round(it) }
                    }
                }
                app.olauncher.helper.StatKind.SOCIAL -> {
                    val today = LocalDate.now()
                    amount(app.olauncher.helper.Social.days(context).filter { (d, day) -> d == today || day.trackedMs >= 12 * 3_600_000L }
                        .mapValues { it.value.outMs / 3_600_000f }, ::hoursText)
                }
                app.olauncher.helper.StatKind.WORK -> list.add(Measured(stat.id, stat.name, stat.color, icon, ValueKind.YES_NO,
                    faces.mapValues { if (stat.isBlank(it.value)) 0f else 1f }, ::percentText, getString(R.string.compare_off) to getString(R.string.compare_worked)))
                else -> if (stat.medicine) {
                    // The number in each label ("50mg", "2 Cups"), or each face's place when they haven't all got one
                    val numbers = stat.faces.indices.map { place -> stat.labels.getOrNull(place)?.let { Regex("""\d+(\.\d+)?""").find(it)?.value?.toFloatOrNull() } }
                    val numeric = numbers.all { it != null }
                    val unit = stat.labels.firstOrNull { Regex("""\d""").containsMatchIn(it) }?.replace(Regex("""[\d.\s]+"""), "")?.trim().orEmpty()
                    val doseText: (Float) -> String = { v ->
                        if (!numeric) numberText(v) else if (unit.length <= 2) numberText(v) + unit else numberText(v) + " " + unit.lowercase(Locale.getDefault())
                    }
                    val many = stat.faces.size > 2
                    if (many) list.add(Measured("${stat.id}:dose", if (stat.isMedicine) getString(R.string.compare_dose, stat.name) else stat.name, stat.color, icon,
                        ValueKind.AMOUNT, faces.mapValues { (_, f) -> stat.faces.indexOf(f).let { place -> if (numeric) numbers[place]!! else place.toFloat() } }, doseText))
                    if (stat.isMedicine || !many) list.add(Measured(stat.id, if (many) getString(R.string.compare_taken, stat.name) else stat.name, stat.color, icon,
                        ValueKind.YES_NO, faces.mapValues { if (stat.isBlank(it.value)) 0f else 1f }, ::percentText,
                        if (stat.isMedicine) getString(R.string.compare_not_taken) to getString(R.string.compare_taken_short) else getString(R.string.compare_none) to getString(R.string.compare_some)))
                } else list.add(scoreComparable(stat, icon))
            }
        }
        val steps = app.olauncher.helper.Steps.days(context)
        if (steps.isNotEmpty()) list.add(Measured(STEPS_KEY, getString(R.string.steps_title), 0xFF4DB6AC.toInt(), StatIcons.STEPS, ValueKind.AMOUNT,
            steps.mapValues { it.value.toFloat() }, { v -> if (v >= 1000) String.format(Locale.getDefault(), "%.1fk", v / 1000) else Math.round(v).toString() }))
        return list
    }

    private fun percentText(v: Float) = "${Math.round(v * 100)}%"

    private fun scoreComparable(stat: StatInfo, icon: String) = Measured(stat.id, stat.name, stat.color, icon, ValueKind.SCORE,
        scores[stat.id].orEmpty().mapValues { it.value.toFloat() }, ::decimal, scoreLabel = { score ->
            stat.faces.firstOrNull { MoodStore.score(it, stat.inverted) == score }?.let { faceName(stat, it) } ?: score.toString()
        })

    private class Group(val label: String, val values: List<Float>) {
        val average: Float? get() = values.takeIf { it.size >= MIN_GROUP }?.average()?.toFloat()
    }

    /**
     * [y]'s values grouped by [x]'s on the same day (or the day before, [lag] 1): yes and no; each score;
     * each amount when there are only a few, else its own lowest, middle and highest thirds.
     */
    private fun groups(x: Measured, y: Measured, lag: Int): List<Group> {
        val pairs = x.values.mapNotNull { (date, xv) -> y.values[date.plusDays(lag.toLong())]?.let { xv to it } }
        return when (x.kind) {
            ValueKind.YES_NO -> listOf(
                Group(x.yesNo.first, pairs.filter { it.first < 0.5f }.map { it.second }),
                Group(x.yesNo.second, pairs.filter { it.first >= 0.5f }.map { it.second }),
            )
            ValueKind.SCORE -> x.values.values.map { Math.round(it) }.distinct().sorted().map { score ->
                Group(x.scoreLabel(score), pairs.filter { Math.round(it.first) == score }.map { it.second })
            }
            ValueKind.AMOUNT -> CompareGroups.amountBuckets(pairs.map { it.first }).map { bucket ->
                val label = when {
                    bucket.exact != null -> x.format(bucket.exact)
                    bucket.from == null -> getString(R.string.compare_up_to, x.format(bucket.to!!))
                    bucket.to == null -> getString(R.string.compare_over, x.format(bucket.from))
                    else -> x.format(bucket.from) + "–" + x.format(bucket.to)
                }
                Group(label, pairs.filter { bucket.contains(it.first) }.map { it.second })
            }
        }
    }

    /** What [x]'s groups come to for [y]: its first group of enough days against its last, in a sentence */
    private fun sentence(x: Measured, y: Measured, lag: Int, valid: List<Group>): String {
        val yName = if (lag == 1) getString(R.string.compare_next_day_of, y.name) else y.name
        val first = valid.first()
        val last = valid.last()
        val diff = last.average!! - first.average!!
        val small = when (y.kind) {
            ValueKind.SCORE -> abs(diff) < 0.15f
            ValueKind.YES_NO -> abs(diff) < 0.05f
            ValueKind.AMOUNT -> abs(diff) < 0.05f * maxOf(abs(first.average!!), abs(last.average!!), 0.01f)
        }
        val amount = when (y.kind) {
            ValueKind.SCORE -> decimal(abs(diff))
            ValueKind.YES_NO -> getString(R.string.compare_points, Math.round(abs(diff) * 100))
            ValueKind.AMOUNT -> y.format(abs(diff))
        }
        val way = getString(if (diff > 0) R.string.compare_higher else R.string.compare_lower)
        return when {
            small -> getString(R.string.compare_barely, yName, x.name)
            x.kind == ValueKind.YES_NO -> getString(R.string.compare_yes_no, yName, amount, way, last.label, first.label, last.values.size, first.values.size)
            else -> getString(R.string.compare_range, yName, amount, way, x.name, last.label, first.label)
        }
    }

    private fun kindOf(m: Measured) = stats.firstOrNull { it.id == m.key.substringBefore(':') }?.kind

    private class Insight(val x: Measured, val y: Measured, val lag: Int, val strength: Float, val text: String)

    /**
     * Every stat against every other, the same day and the next, keeping the clearest differences: each
     * side needs [INSIGHT_DAYS] days, and the difference has to be a good part of how much the second
     * stat usually varies. One per pair of stats, the strongest first.
     */
    private fun findInsights(): List<Insight> {
        val found = mutableListOf<Insight>()
        fun base(m: Measured) = m.key.substringBefore(':')
        for (x in comparables) for (y in comparables) {
            // Outcomes are scores and amounts; a stat isn't set against itself, or one made from it
            if (y.kind == ValueKind.YES_NO || base(x) == base(y) || setOf(kindOf(x), kindOf(y)) in MADE_FROM) continue
            for (lag in 0..1) {
                val groups = groups(x, y, lag)
                val valid = groups.filter { it.values.size >= INSIGHT_DAYS }
                if (valid.size < 2) continue
                val all = groups.flatMap { it.values }
                val mean = all.average().toFloat()
                val spread = Math.sqrt(all.sumOf { ((it - mean) * (it - mean)).toDouble() } / all.size).toFloat()
                if (spread <= 0f) continue
                val diff = valid.last().values.average().toFloat() - valid.first().values.average().toFloat()
                val strength = abs(diff) / spread
                if (strength < INSIGHT_STRENGTH) continue
                found += Insight(x, y, lag, strength, sentence(x, y, lag, valid.map { Group(it.label, it.values) }))
            }
        }
        return found.groupBy { setOf(base(it.x), base(it.y)) }.values.map { same -> same.maxBy { it.strength } }
            .sortedByDescending { it.strength }.take(INSIGHTS_SHOWN)
    }

    /** The clearest links in the data, in plain words; tapping one opens it in Compare */
    private fun insightsCard() {
        val card = Card(getString(R.string.insights))
        card.subtitle(getString(R.string.insights_note))
        val insights = runCatching { findInsights() }.getOrDefault(emptyList())
        if (insights.isEmpty()) return card.showNote(getString(R.string.insights_none))
        card.scene.isVisible = false
        val context = requireContext()
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        insights.forEach { insight ->
            list.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 9.dpToPx(), 0, 9.dpToPx())
                setBackgroundResource(android.R.drawable.list_selector_background)
                addView(View(context).apply { background = StatIcons.drawable(insight.x.icon, insight.x.color, 16.dpToPx()) },
                    LinearLayout.LayoutParams(16.dpToPx(), 16.dpToPx()).apply { marginEnd = 6.dpToPx() })
                addView(View(context).apply { background = StatIcons.drawable(insight.y.icon, insight.y.color, 16.dpToPx()) },
                    LinearLayout.LayoutParams(16.dpToPx(), 16.dpToPx()).apply { marginEnd = 14.dpToPx() })
                addView(card.textView(14f, 235).apply { text = insight.text },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                setOnClickListener {
                    compareX = insight.x.key
                    compareY = insight.y.key
                    compareLag = insight.lag
                    compareRedraw?.invoke()
                    compareRoot?.let { root -> scrollView.post { scrollView.smoothScrollTo(0, root.top - 16.dpToPx()) } }
                }
            })
        }
        card.root.addView(list, card.root.indexOfChild(card.scene))
    }

    /**
     * Any stat against any other, whatever each holds: the second's average for each group of the first
     * (yes or no, each face, or low, middle and high amounts), on the same day or the next, with a line
     * saying what it comes to.
     */
    private fun compareCard() {
        if (comparables.size < 2) return
        val card = Card(getString(R.string.compare_two))
        compareRoot = card.root
        fun pick(onPick: (String) -> Unit) {
            val options = comparables
            AlertDialog.Builder(requireContext())
                .setItems(options.map { it.name }.toTypedArray()) { _, i -> onPick(options[i].key) }
                .show()
        }
        val pickers = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val summary = card.textView(14f, 230).apply { updatePadding(bottom = 12.dpToPx()) }
        card.root.addView(pickers, card.root.indexOfChild(card.scene), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 14.dpToPx()
        })
        card.root.addView(summary, card.root.indexOfChild(card.scene))
        fun draw() {
            val x = comparables.firstOrNull { it.key == compareX }
                ?: comparables.firstOrNull { it.name == getString(R.string.work) }
                ?: comparables.firstOrNull { it.key == MoodStore.USAGE } ?: comparables.first { it.key != compareY }
            val y = comparables.firstOrNull { it.key == compareY } ?: comparables.first { it.key != x.key }
            card.segment(listOf(R.string.compare_same_day, R.string.compare_next_day), compareLag) {
                compareLag = it
                draw()
            }
            pickers.removeAllViews()
            fun chip(c: Measured, onTap: () -> Unit) = card.textView(14f, 255).apply {
                text = c.name
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                setBackgroundResource(R.drawable.bg_stat_chip)
                setPadding(12.dpToPx(), 7.dpToPx(), 14.dpToPx(), 7.dpToPx())
                setCompoundDrawablesRelativeWithIntrinsicBounds(StatIcons.drawable(c.icon, c.color, 14.dpToPx()), null, null, null)
                compoundDrawablePadding = 8.dpToPx()
                setOnClickListener { onTap() }
            }
            pickers.addView(chip(x) { pick { compareX = it; draw() } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            pickers.addView(card.textView(13f, 150).apply {
                text = getString(R.string.compare_against)
                setPadding(8.dpToPx(), 0, 8.dpToPx(), 0)
            })
            pickers.addView(chip(y) { pick { compareY = it; draw() } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val groups = groups(x, y, compareLag)
            val valid = groups.filter { it.average != null }
            if (valid.size < 2) {
                summary.isVisible = false
                return card.showNote(getString(R.string.needs_more_days))
            }
            summary.isVisible = true
            val yName = if (compareLag == 1) getString(R.string.compare_next_day_of, y.name) else y.name
            summary.text = sentence(x, y, compareLag, valid)
            card.describe = { key ->
                val group = groups[key as Int]
                listOf(getString(R.string.stat_label, x.name, group.label),
                    getString(R.string.stat_label, yName, group.average?.let(y.format) ?: "–"),
                    resources.getQuantityString(R.plurals.day_count, group.values.size, group.values.size)).joinToString("\n")
            }
            val (floor, ceiling) = when (y.kind) {
                ValueKind.SCORE -> 1f to 5f
                ValueKind.YES_NO -> 0f to 1f
                ValueKind.AMOUNT -> 0f to (groups.mapNotNull { it.average }.maxOrNull() ?: 1f).let { if (it <= 0f) 1f else it * 1.15f }
            }
            card.draw { s ->
                val top = s.dp(6f)
                val bottom = top + s.dp(120f)
                val axis = s.dp(30f)
                fun yOf(v: Float) = bottom - (v - floor) / (ceiling - floor) * (bottom - top)
                listOf(floor, (floor + ceiling) / 2, ceiling).forEach { g ->
                    s.line(axis, yOf(g), s.width, yOf(g), faint(28), s.dp(1f))
                    val label = if (y.kind == ValueKind.SCORE) numberText(g) else y.format(g)
                    s.text(label, 0f, yOf(g) + s.sp(4f), faint(130), s.sp(11f), Paint.Align.LEFT)
                }
                val column = (s.width - axis) / groups.size
                val bar = minOf(column * 0.5f, s.dp(34f))
                groups.forEachIndexed { i, group ->
                    val center = axis + i * column + column / 2
                    val average = group.average
                    if (average == null) s.rect(center - bar / 2, bottom - s.dp(2f), center + bar / 2, bottom, faint(45), s.dp(1f))
                    else {
                        val color = if (y.kind == ValueKind.SCORE) stats.firstOrNull { it.id == y.key }?.let { st ->
                            st.faces.minByOrNull { abs(MoodStore.score(it, st.inverted) - average) }?.let { st.faceColor(requireContext(), it) }
                        } ?: y.color else y.color
                        s.rect(center - bar / 2, minOf(yOf(average), bottom - s.dp(3f)), center + bar / 2, bottom, color, s.dp(6f))
                    }
                    val label = group.label
                    s.text(if (label.length > 12) label.take(11) + "…" else label, center, bottom + s.dp(18f), faint(160), s.sp(11.5f))
                    s.target(i, RectF(axis + i * column, 0f, axis + (i + 1) * column, bottom + s.dp(26f)))
                }
                s.height = bottom + s.dp(26f)
            }
        }
        compareRedraw = ::draw
        draw()
    }

    /** Days in a row with Mood logged, the best run, and the notes written */
    private fun streaksCard() {
        val mood = scores[MoodStore.MOOD].orEmpty().keys
        if (mood.isEmpty()) return
        val today = LocalDate.now()
        var current = 0
        var date = if (today in mood) today else today.minusDays(1)
        while (date in mood) {
            current++
            date = date.minusDays(1)
        }
        var best = 0
        var run = 0
        var previous: LocalDate? = null
        mood.sorted().forEach { d ->
            run = if (previous?.plusDays(1) == d) run + 1 else 1
            best = maxOf(best, run)
            previous = d
        }
        val notes = data.days.values.count { it.note != null }
        val card = Card(getString(R.string.logging))
        tiles(card, null, listOf(
            current.toString() to getString(R.string.days_in_a_row),
            best.toString() to getString(R.string.best_run),
            notes.toString() to getString(R.string.notes_written),
        ))
    }

    // --- The floating bubble picking which stats show ---

    private fun renderBubble() {
        val context = requireContext()
        bubble.removeAllViews()
        val dark = ColorUtils.calculateLuminance(ink) > 0.5
        bubble.background = GradientDrawable().apply {
            cornerRadius = 26.dpToPx().toFloat()
            setColor(if (dark) 0xFF202024.toInt() else 0xFFF4F4F6.toInt())
            setStroke(1.dpToPx(), faint(40))
        }
        bubble.elevation = 8.dpToPx().toFloat()
        scrim.isVisible = bubbleOpen
        val picked = shown
        if (!bubbleOpen) {
            (bubble.layoutParams as? FrameLayout.LayoutParams)?.apply {
                width = ViewGroup.LayoutParams.WRAP_CONTENT
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            bubble.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16.dpToPx(), 10.dpToPx(), 16.dpToPx(), 10.dpToPx())
                picked.take(BUBBLE_ICONS).forEach { stat ->
                    addView(View(context).apply { background = StatIcons.drawable(StatIcons.forStat(stat), stat.color, 14.dpToPx()) },
                        LinearLayout.LayoutParams(14.dpToPx(), 14.dpToPx()).apply { marginEnd = 6.dpToPx() })
                }
                addView(TextView(context, null, 0, R.style.TextSmall).apply {
                    text = resources.getQuantityString(R.plurals.stats_shown, picked.size, picked.size) + "  ▴"
                    textSize = 13f
                    setTextColor(ink)
                    setPadding(4.dpToPx(), 0, 0, 0)
                })
                setOnClickListener {
                    bubbleOpen = true
                    renderBubble()
                }
            })
            bubble.requestLayout()
            return
        }
        (bubble.layoutParams as? FrameLayout.LayoutParams)?.apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            gravity = Gravity.BOTTOM
        }
        bubble.setPadding(14.dpToPx(), 12.dpToPx(), 14.dpToPx(), 12.dpToPx())
        bubble.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context, null, 0, R.style.TextSmall).apply {
                text = getString(R.string.show)
                textSize = 14f
                setTextColor(ink)
                setTypeface(null, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            listOf(
                R.string.bubble_default to { setHidden(stats.map { it.id }.toSet() - defaultPicks()) },
                R.string.bubble_all to { setHidden(emptySet()) },
                R.string.bubble_none to { setHidden(stats.map { it.id }.toSet() - MoodStore.MOOD) },
            ).forEach { (label, action) ->
                addView(TextView(context, null, 0, R.style.TextSmall).apply {
                    setText(label)
                    textSize = 13f
                    setTextColor(ink)
                    alpha = 0.75f
                    setPadding(8.dpToPx(), 6.dpToPx(), 8.dpToPx(), 6.dpToPx())
                    setOnClickListener { action() }
                })
            }
            addView(TextView(context, null, 0, R.style.TextSmall).apply {
                text = "▾"
                textSize = 16f
                setTextColor(ink)
                setPadding(12.dpToPx(), 2.dpToPx(), 6.dpToPx(), 2.dpToPx())
                contentDescription = getString(R.string.close)
                setOnClickListener { closeBubble() }
            })
        })
        bubble.addView(FlowLayout(context).apply {
            stats.forEach { stat ->
                val on = stat.id !in data.hiddenInGraph
                addView(TextView(context, null, 0, R.style.TextSmall).apply {
                    text = stat.name
                    textSize = 13f
                    setTextColor(ink)
                    setBackgroundResource(R.drawable.bg_stat_chip)
                    setPadding(10.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
                    setCompoundDrawablesRelativeWithIntrinsicBounds(StatIcons.drawable(StatIcons.forStat(stat), stat.color, 13.dpToPx()), null, null, null)
                    compoundDrawablePadding = 6.dpToPx()
                    alpha = if (on) 1f else 0.35f
                    setOnClickListener { setHidden(if (on) data.hiddenInGraph + stat.id else data.hiddenInGraph - stat.id) }
                    setOnLongClickListener {
                        findNavController().navigate(R.id.action_statsHomeFragment_to_statFragment, bundleOf(StatFragment.STAT_ID to stat.id))
                        true
                    }
                })
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 8.dpToPx() })
        bubble.requestLayout()
    }

    private fun closeBubble() {
        if (!bubbleOpen) return
        bubbleOpen = false
        bubble.setPadding(0, 0, 0, 0)
        renderBubble()
    }

    /** Mood, Screen time, Energy, Libido and Anxiety: the ones shown by Default */
    private fun defaultPicks(): Set<String> =
        stats.filter { it.id == MoodStore.MOOD || it.id == MoodStore.USAGE || it.name.lowercase() in DEFAULT_NAMES }.map { it.id }.toSet()

    private fun setHidden(ids: Set<String>) {
        requireContext().getSharedPreferences(PREFS, 0).edit().putBoolean(PICKED, true).apply()
        store.setHiddenInGraph(ids)
        redrawPicked()
    }

    override fun onDestroyView() {
        compareRoot = null
        compareRedraw = null
        noteInput?.hideKeyboard()
        noteInput = null
        redraws.clear()
        super.onDestroyView()
    }

    companion object {
        private val WEEKS = WeekFields.ISO
        private const val GRID_DAYS = 18
        private val DEFAULT_NAMES = setOf("energy", "libido", "anxiety")
        private const val MIN_GROUP = 3
        private const val INSIGHT_DAYS = 5

        /** Pairs where one is counted from the other (time at work is time out), so a link is no news */
        private val MADE_FROM = setOf(setOf(app.olauncher.helper.StatKind.WORK, app.olauncher.helper.StatKind.SOCIAL))
        private const val INSIGHT_STRENGTH = 0.4f
        private const val INSIGHTS_SHOWN = 5
        private const val STEPS_KEY = "steps"
        private const val REMEMBER_AFTER_DAYS = 7L
        private const val MIN_LINK_DAYS = 4
        private const val LINKS_SHOWN = 6
        private const val BUBBLE_ICONS = 6
        private const val FIRST_PICK = 5
        private const val PREFS = "stats_home"
        private const val PICKED = "picked"
    }
}
