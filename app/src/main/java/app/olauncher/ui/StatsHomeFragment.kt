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
import app.olauncher.helper.DeviceTime
import app.olauncher.helper.Sleep
import app.olauncher.helper.Social
import app.olauncher.helper.StatInfo
import app.olauncher.helper.StatKind
import app.olauncher.helper.allStats
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.setStatDot
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
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

    /** The trend card's view: [TREND_WEEKS], [TREND_MONTHS] or [TREND_YEARS] */
    private var trendMode = TREND_WEEKS
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
        // Coming back unchanged (same stats, same day, same cards) keeps what's drawn
        val shown = listOf(store.lastSaved(), LocalDate.now(), app.olauncher.helper.Arrangement.shown(requireContext(),
            app.olauncher.helper.Arrangement.STATS_HOME, app.olauncher.helper.Arrangement.STATS_HOME_CARDS))
        if (shown == renderedFor && cardList.childCount > 0) return
        renderedFor = shown
        render()
    }

    /** What the page was last drawn from */
    private var renderedFor: List<Any>? = null

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
        // Stats added since last time start hidden, unless set to show
        val known = prefs.getStringSet(KNOWN, null)
        val ids = stats.map { it.id }.toSet()
        if (known != null && !showsNewStats(requireContext())) {
            val added = ids - known
            if (added.isNotEmpty()) {
                store.setHiddenInGraph(data.hiddenInGraph + added)
                data = store.load()
            }
        }
        if (known != ids) prefs.edit().putStringSet(KNOWN, ids).apply()
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
        var insights: Card? = null
        var compare: Card? = null
        val arrangement = app.olauncher.helper.Arrangement
        arrangement.shown(requireContext(), arrangement.STATS_HOME, arrangement.STATS_HOME_CARDS).forEach { key ->
            when (key) {
                arrangement.DAILY -> dailyCard()
                arrangement.TRENDS -> trendCard()
                arrangement.REMEMBER -> rememberCard()
                // Insights and Compare need every stat side by side, worked out off the main thread
                arrangement.INSIGHTS -> insights = Card(getString(R.string.insights)).apply {
                    subtitle(getString(R.string.insights_note))
                    showNote(getString(R.string.insights_working))
                }
                arrangement.THIS_WEEK -> thisWeekCard()
                arrangement.COMPARE -> compare = Card(getString(R.string.compare_two)).apply { showNote(getString(R.string.insights_working)) }
            }
        }
        renderBubble()
        val generation = ++renderGeneration
        if (insights == null && compare == null) return
        Thread {
            val built = runCatching { buildComparables() }.getOrDefault(emptyList())
            val found = if (insights == null) emptyList() else runCatching { findInsights(built) }.getOrDefault(emptyList())
            view?.post {
                if (generation != renderGeneration || view == null) return@post
                comparables = built
                insights?.let { fillInsights(it, found) }
                compare?.let { fillCompare(it) }
            }
        }.start()
    }

    /** Each render's number, so work finished for an older one is dropped */
    private var renderGeneration = 0

    private fun redrawPicked() {
        load()
        redraws.forEach { it() }
        renderBubble()
    }

    private fun header() {
        cardList.addView(pageHeader(requireContext(), StatIcons.HOME, getString(R.string.stats_home)) { findNavController().popBackStack() })
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
        heatmap.onRowHeld = { row -> gridStats.getOrNull(row)?.let { openStat(it.id) } }
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
                shown.also { gridStats = it }.map { stat ->
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
            // Holding it while not typing records a voice note, written into the note
            setOnLongClickListener {
                if (hasFocus() && androidx.core.view.ViewCompat.getRootWindowInsets(this)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true) return@setOnLongClickListener false
                recordInto(selectedDate)
                true
            }
        }
        noteInput = input
        noteDate = selectedDate
        container.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 10.dpToPx()
        })
        container.addView(endRow(voiceButton(ink) { recordInto(selectedDate) }), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 8.dpToPx()
        })
    }

    /** Records a voice note for [date], its words added to that day's note */
    private fun recordInto(date: LocalDate) {
        saveNote()
        recordVoiceNote(date, onText = { text ->
            val now = store.load().days[date.toString()]?.note.orEmpty().trim()
            val note = if (now.isEmpty()) text else now + "\n" + text
            store.setNote(date, note)
            if (noteDate == date) noteInput?.setText(note)
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

    /**
     * Each picked stat's average by week, month or year, chosen at the top; weeks and months a year at a
     * time, stepped under the chart when there's more than one.
     */
    private fun trendCard() {
        val context = requireContext()
        val card = Card(getString(R.string.graph_trends))
        val chart = StatChartView(context)
        card.addBody(chart)
        val years = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        card.addBody(years)
        fun draw(clear: Boolean) {
            val mode = trendMode
            val weekly = mode == TREND_WEEKS
            val today = LocalDate.now()
            card.segment(listOf(R.string.graph_weeks, R.string.graph_months, R.string.graph_years), mode) {
                trendMode = it
                draw(true)
            }
            fun pointOf(date: LocalDate, year: Int): Int? = when (mode) {
                TREND_WEEKS -> date.get(WEEKS.weekOfWeekBasedYear()).takeIf { date.get(WEEKS.weekBasedYear()) == year }
                TREND_MONTHS -> date.monthValue.takeIf { date.year == year }
                else -> date.year
            }
            val thisYear = if (weekly) today.get(WEEKS.weekBasedYear()) else today.year
            val firstDate = dates().firstOrNull() ?: today
            val firstYear = minOf(if (weekly) firstDate.get(WEEKS.weekBasedYear()) else firstDate.year, thisYear)
            val year = (if (weekly) weeklyYear else monthlyYear).coerceIn(firstYear, thisYear)
            // ‹ 2026 › under the chart, for weeks and months, once there's more than one year
            years.removeAllViews()
            years.isVisible = mode != TREND_YEARS && firstYear < thisYear
            if (years.isVisible) {
                fun arrow(symbol: String, enabled: Boolean, by: Int, description: Int) = TextView(context, null, 0, R.style.TextLarge).apply {
                    text = symbol
                    textSize = 18f
                    setTextColor(card.titleView.currentTextColor)
                    alpha = if (enabled) 0.8f else 0.2f
                    isEnabled = enabled
                    contentDescription = getString(description, 365)
                    setPadding(14.dpToPx(), 6.dpToPx(), 14.dpToPx(), 0)
                    setOnClickListener {
                        if (weekly) weeklyYear = year + by else monthlyYear = year + by
                        draw(true)
                    }
                }
                years.addView(arrow("‹", year > firstYear, -1, R.string.previous_days))
                years.addView(card.textView(13f, 150).apply {
                    text = year.toString()
                    updatePadding(top = 6.dpToPx())
                })
                years.addView(arrow("›", year < thisYear, 1, R.string.next_days))
            }
            val all = shown.associate { stat ->
                stat.id to scores[stat.id].orEmpty().entries.mapNotNull { (date, score) -> pointOf(date, year)?.let { it to score } }
                    .groupBy({ it.first }, { it.second }).mapValues { (_, list) -> list.average().toFloat() }
            }
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
                label = when (mode) {
                    TREND_WEEKS -> { w -> getString(R.string.week_short, w) }
                    TREND_MONTHS -> { m -> Month.of(m).getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
                    else -> { y -> y.toString() }
                },
                clearSelection = clear,
            )
            chart.onPointSelected = { point ->
                card.showDetails(point?.let { p ->
                    val title = when (mode) {
                        TREND_WEEKS -> getString(R.string.week_number, p)
                        TREND_MONTHS -> YearMonth.of(year, p).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
                        else -> p.toString()
                    }
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
            val monday = app.olauncher.helper.Week.start(today)
            val rows = shown.filter { !it.isMedicine && it.kind != app.olauncher.helper.StatKind.WORK }.mapNotNull { stat ->
                val byDate = scores[stat.id].orEmpty()
                val now = byDate.filterKeys { it >= monday && it <= today }.values.takeIf { it.isNotEmpty() }?.average() ?: return@mapNotNull null
                val before = byDate.filterKeys { it >= monday.minusDays(7) && it < monday }.values.takeIf { it.isNotEmpty() }?.average()
                Triple(stat, now.toFloat(), before?.toFloat())
            }
                // Biggest rise first, down to the biggest fall; new ones (no last week) after
                .sortedWith(compareBy<Triple<StatInfo, Float, Float?>> { it.third == null }.thenByDescending { (_, now, before) -> before?.let { now - it } ?: 0f })
            if (rows.isEmpty()) return card.showNote(getString(R.string.no_data))
            card.statOf = { key -> rows.getOrNull(key as Int)?.first?.id }
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
    ) {
        /** Days in a group, as words: a group's [label], fully ("days with over 2h of music") or short after "than" ("with up to 1h") */
        var cond: (label: String, short: Boolean) -> String = { label, short -> if (short) label else label }

        /** The difference in it, as words: "you sleep 1.2h more" ([up] is more or better) */
        var outcome: (amount: String, up: Boolean) -> String = { amount, up -> "$name ${if (up) "+" else "−"}$amount" }

        /** Whether it comes before its day (last night's sleep), so it's only set against that same day */
        var before = false

        /** Worked out from the night's sleep, so never set against how long it was */
        var fromSleep = false

        /** Its high side ([Boolean] true) or low side as words, in place of the usual ones */
        var says: ((Boolean) -> String)? = null

        /** The stat page an insight about it opens, when it isn't one of Compare's */
        var opens: String? = null
    }

    private var comparables: List<Measured> = emptyList()

    /** The stats in the Daily grid's rows, top to bottom */
    private var gridStats: List<StatInfo> = emptyList()

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
                StatKind.WEATHER -> amount(app.olauncher.helper.WeatherHistory.days(context).mapValues { it.value.sunHours }, ::hoursText)
                StatKind.PEOPLE -> amount(app.olauncher.helper.People.days(context).mapValues { it.value.people.toFloat() }, ::numberText)
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
        list.forEach { phrase(it) }
        val steps = app.olauncher.helper.Steps.days(context)
        if (steps.isNotEmpty()) list.add(Measured(STEPS_KEY, getString(R.string.steps_title), 0xFF4DB6AC.toInt(), StatIcons.STEPS, ValueKind.AMOUNT,
            steps.mapValues { it.value.toFloat() }, { v -> if (v >= 1000) String.format(Locale.getDefault(), "%.1fk", v / 1000) else Math.round(v).toString() }).also {
            it.cond = { label, short -> if (short) getString(R.string.cond_short_with, label) else getString(R.string.cond_steps, label) }
            it.outcome = { amount, up -> getString(R.string.out_steps, amount, getString(if (up) R.string.word_more else R.string.word_fewer)) }
        })
        return list
    }

    private fun percentText(v: Float) = "${Math.round(v * 100)}%"

    /** How [m] reads in a sentence, by what it is: the days in its groups, and what a difference in it means */
    private fun phrase(m: Measured) {
        val stat = stats.firstOrNull { it.id == m.key.substringBefore(':') } ?: return
        val lower = stat.name.lowercase(Locale.getDefault())
        // Medicines keep their names as they are; everything else reads in lower case
        val name = if (stat.isMedicine) stat.name else lower
        fun more(up: Boolean) = getString(if (up) R.string.word_more else R.string.word_less)
        val dose = m.key.endsWith(":dose")
        when {
            m.kind == ValueKind.YES_NO -> {
                val (yes, no) = when (stat.kind) {
                    app.olauncher.helper.StatKind.WORK -> getString(R.string.cond_work_yes) to getString(R.string.cond_work_no)
                    app.olauncher.helper.StatKind.EXERCISE -> getString(R.string.cond_exercise_yes) to getString(R.string.cond_exercise_no)
                    app.olauncher.helper.StatKind.MEDITATION -> getString(R.string.cond_meditation_yes) to getString(R.string.cond_meditation_no)
                    app.olauncher.helper.StatKind.MEDICINE -> getString(R.string.cond_medicine_yes, name) to getString(R.string.cond_medicine_no, name)
                    else -> getString(R.string.cond_with, name) to getString(R.string.cond_without, name)
                }
                m.cond = { label, short -> (if (label == m.yesNo.second) yes else no).let { if (short) getString(R.string.cond_short_on, it) else it } }
            }
            m.kind == ValueKind.SCORE -> {
                m.cond = { label, short -> if (short) getString(R.string.cond_score_short, label.lowercase(Locale.getDefault())) else getString(R.string.cond_score, name, label.lowercase(Locale.getDefault())) }
                m.outcome = { amount, up -> getString(R.string.out_score, name, amount, getString(if (up) R.string.word_better else R.string.word_worse)) }
            }
            dose -> {
                m.cond = { label, short -> if (short) getString(R.string.cond_short_on, label) else getString(R.string.cond_dose, label, name) }
                m.outcome = { amount, up -> getString(R.string.out_dose, amount, more(up), name) }
            }
            else -> when (stat.kind) {
                app.olauncher.helper.StatKind.SCREEN -> {
                    m.cond = { label, short -> if (short) getString(R.string.cond_short_with, label) else getString(R.string.cond_amount, label, getString(R.string.noun_screen)) }
                    m.outcome = { amount, up -> getString(R.string.out_screen, amount, more(up)) }
                }
                app.olauncher.helper.StatKind.SLEEP -> {
                    m.before = true
                    m.cond = { label, short -> if (short) getString(R.string.cond_sleep_short, label) else getString(R.string.cond_sleep, label) }
                    m.outcome = { amount, up -> getString(R.string.out_sleep, amount, more(up)) }
                }
                app.olauncher.helper.StatKind.MUSIC -> {
                    m.cond = { label, short -> if (short) getString(R.string.cond_short_with, label) else getString(R.string.cond_amount, label, getString(R.string.noun_music)) }
                    m.outcome = { amount, up -> getString(R.string.out_music, amount, more(up)) }
                }
                app.olauncher.helper.StatKind.VIBECODING -> {
                    m.cond = { label, short -> if (short) getString(R.string.cond_short_with, label) else getString(R.string.cond_amount, label, getString(R.string.noun_vibecoding)) }
                    m.outcome = { amount, up -> getString(R.string.out_vibecoding, amount, more(up)) }
                }
                app.olauncher.helper.StatKind.MONEY -> {
                    m.cond = { label, short -> if (short) getString(R.string.cond_money_short, label) else getString(R.string.cond_money, label) }
                    m.outcome = { amount, up -> getString(R.string.out_money, amount, more(up)) }
                }
                app.olauncher.helper.StatKind.SOCIAL -> {
                    m.cond = { label, short -> if (short) getString(R.string.cond_social_short, label) else getString(R.string.cond_social, label) }
                    m.outcome = { amount, up -> getString(R.string.out_social, amount, more(up)) }
                }
                else -> {
                    m.cond = { label, short -> if (short) getString(R.string.cond_short_with, label.lowercase(Locale.getDefault())) else getString(R.string.cond_count, label.lowercase(Locale.getDefault()), name) }
                    m.outcome = { amount, up -> getString(R.string.out_count, amount, more(up), name) }
                }
            }
        }
    }

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
        val counts = " " + getString(R.string.insight_counts, last.values.size, first.values.size)
        // One day of a kind: "work days" → "a work day", "days with over 2h…" → "a day with over 2h…"
        fun one(days: String) = when {
            days.startsWith("days ") -> "a day " + days.removePrefix("days ")
            days.endsWith(" days") -> "a " + days.removeSuffix("s")
            else -> days
        }
        val hi = x.cond(last.label, false)
        if (small) {
            val text = if (x.kind == ValueKind.YES_NO) getString(R.string.insight_barely_yes_no, y.name, hi, x.cond(first.label, false))
            else getString(R.string.insight_barely, y.name, x.name.lowercase(Locale.getDefault()))
            return text + counts
        }
        val outcome = y.outcome(amount, diff > 0)
        val text = when {
            x.before -> getString(R.string.insight_after, hi, outcome, x.cond(first.label, true))
            lag == 1 -> getString(R.string.insight_next_day, one(hi), outcome,
                if (x.kind == ValueKind.YES_NO) one(x.cond(first.label, false)) else getString(R.string.insight_one, x.cond(first.label, true)))
            else -> getString(R.string.insight_same_day, hi, outcome, x.cond(first.label, true))
        }
        return text.replaceFirstChar { it.uppercase() } + counts
    }

    private fun kindOf(m: Measured) = stats.firstOrNull { it.id == m.key.substringBefore(':') }?.kind

    /**
     * One takeaway: its line and the numbers under it. Only the strongest of each [group] is shown, and
     * [headline]s (the last two weeks, a record) go first.
     */
    private class Insight(
        val icon: String,
        val color: Int,
        val strength: Float,
        val text: String,
        val detail: String,
        val group: String,
        val headline: Boolean = false,
        val onTap: () -> Unit,
        val onHold: () -> Unit = onTap,
    )

    /** What an insight can be about: how you rate things (mood, energy, anxiety…) and how long you sleep */
    private fun isOutcome(m: Measured) = m.kind == ValueKind.SCORE || (m.kind == ValueKind.AMOUNT && kindOf(m) == StatKind.SLEEP)

    private fun baseOf(m: Measured) = m.key.substringBefore(':')

    /** A stat's name as it reads mid-sentence: medicines as they are, the rest in lower case */
    private fun inSentence(m: Measured): String {
        val stat = stats.firstOrNull { it.id == baseOf(m) }
        return if (stat?.isMedicine == true) m.name else m.name.lowercase(Locale.getDefault())
    }

    /** "you're in a better mood", "you feel more anxious"…: [y] on its good side or its bad one */
    private fun feel(y: Measured, good: Boolean): String {
        val name = inSentence(y)
        return when {
            y.key == MoodStore.MOOD -> getString(if (good) R.string.ins_mood else R.string.ins_mood_worse)
            kindOf(y) == StatKind.SLEEP -> getString(if (good) R.string.ins_sleep else R.string.ins_sleep_worse)
            "energy" in name -> getString(if (good) R.string.ins_energy else R.string.ins_energy_worse)
            "anxi" in name || "stress" in name -> getString(if (good) R.string.ins_anxiety else R.string.ins_anxiety_worse)
            else -> getString(if (good) R.string.ins_score else R.string.ins_score_worse, name)
        }
    }

    /** The same, for a stretch gone by: "you've been in a better mood", "you've slept less" */
    private fun feelPast(y: Measured, good: Boolean): String {
        val name = inSentence(y)
        return when {
            y.key == MoodStore.MOOD -> getString(if (good) R.string.ins_past_mood else R.string.ins_past_mood_worse)
            kindOf(y) == StatKind.SLEEP -> getString(if (good) R.string.ins_past_sleep else R.string.ins_past_sleep_worse)
            "energy" in name -> getString(if (good) R.string.ins_past_energy else R.string.ins_past_energy_worse)
            "anxi" in name || "stress" in name -> getString(if (good) R.string.ins_past_anxiety else R.string.ins_past_anxiety_worse)
            else -> getString(if (good) R.string.ins_past_score else R.string.ins_past_score_worse, name)
        }
    }

    /** When [x] is on its high side ([high]) or low side, as words: "on work days", "on days with less screen time" */
    private fun whenX(x: Measured, high: Boolean): String {
        x.says?.let { return it(high) }
        val name = inSentence(x)
        val stat = stats.firstOrNull { it.id == baseOf(x) }
        return when (x.kind) {
            ValueKind.YES_NO -> getString(R.string.ins_on, x.cond(if (high) x.yesNo.second else x.yesNo.first, false))
            ValueKind.SCORE -> getString(if (high) R.string.ins_when_better else R.string.ins_when_worse, name)
            ValueKind.AMOUNT -> when {
                x.key == STEPS_KEY -> getString(if (high) R.string.ins_steps_more else R.string.ins_steps_less)
                else -> when (stat?.kind) {
                    StatKind.SCREEN -> getString(if (high) R.string.ins_screen_more else R.string.ins_screen_less)
                    StatKind.SLEEP -> getString(if (high) R.string.ins_sleep_more else R.string.ins_sleep_less)
                    StatKind.MUSIC -> getString(if (high) R.string.ins_music_more else R.string.ins_music_less)
                    StatKind.VIBECODING -> getString(if (high) R.string.ins_vibe_more else R.string.ins_vibe_less)
                    StatKind.MONEY -> getString(if (high) R.string.ins_money_more else R.string.ins_money_less)
                    StatKind.SOCIAL -> getString(if (high) R.string.ins_social_more else R.string.ins_social_less)
                    StatKind.WEATHER -> getString(if (high) R.string.ins_sun_more else R.string.ins_sun_less)
                    StatKind.PEOPLE -> getString(if (high) R.string.ins_people_more else R.string.ins_people_less)
                    else -> getString(if (high) R.string.ins_count_more else R.string.ins_count_less, name)
                }
            }
        }
    }

    private fun average(values: List<Float>) = values.average().toFloat()

    /** How far apart two sets of days are, in how much the values usually vary */
    private fun strengthOf(a: List<Float>, b: List<Float>, spreadFrom: List<Float> = a + b): Float {
        if (a.isEmpty() || b.isEmpty() || spreadFrom.size < 2) return 0f
        val mean = average(spreadFrom)
        val spread = Math.sqrt(spreadFrom.sumOf { ((it - mean) * (it - mean)).toDouble() } / spreadFrom.size).toFloat()
        return if (spread <= 0f) 0f else abs(average(a) - average(b)) / spread
    }

    private fun capital(text: String) = text.replaceFirstChar { it.uppercase() }

    private fun openIfStat(id: String?) {
        stats.firstOrNull { it.id == id }?.let { openStat(it.id) }
    }

    private fun showInCompare(x: String, y: String, lag: Int) {
        compareX = x
        compareY = y
        compareLag = lag
        compareRedraw?.invoke()
        compareRoot?.let { root -> scrollView.post { scrollView.smoothScrollTo(0, root.top - 16.dpToPx()) } }
    }

    /**
     * Plain takeaways, each one short line with its numbers under it: what goes with your mood, sleep
     * and ratings being better (bedtimes, which home, and the rest), what changed with a new dose, what
     * the day after a missed dose is like, what you do more of on low days, your best and worst weekdays,
     * the last two weeks against the two before, and last week if it was a record. Only clear differences
     * with [INSIGHT_DAYS] days on each side.
     */
    private fun findInsights(all: List<Measured>): List<Insight> {
        val context = requireContext()
        // Only days that are over, so they stay the same all day and move on each morning
        val comparables = all.map { finished(it) }
        val outcomes = comparables.filter { isOutcome(it) }
        val found = mutableListOf<Insight>()
        fun safely(block: () -> List<Insight>) { found += runCatching(block).getOrDefault(emptyList()) }
        safely { pairInsights(comparables, outcomes, extraMeasures(context).map { finished(it) }) }
        safely { doseChangeInsights(outcomes) }
        safely { missedDoseInsights(comparables, outcomes) }
        safely { lowDayInsights(context, comparables) }
        safely { weekdayInsights(outcomes) }
        safely { stretchInsights(outcomes) }
        safely { recordInsights(outcomes) }
        val best = found.groupBy { it.group }.values.map { same -> same.maxBy { it.strength } }
        val headlines = best.filter { it.headline }.sortedBy { it.group != STRETCH }
        return headlines + best.filter { !it.headline }.sortedByDescending { it.strength }.take(INSIGHTS_SHOWN - headlines.size)
    }

    /** The last day insights go up to: yesterday, the last one that's over */
    private fun lastDay() = LocalDate.now().minusDays(1)

    /** [m] without today, which isn't over yet */
    private fun finished(m: Measured): Measured {
        val last = lastDay()
        return Measured(m.key, m.name, m.color, m.icon, m.kind, m.values.filterKeys { it <= last }, m.format, m.yesNo, m.scoreLabel).also {
            it.cond = m.cond
            it.outcome = m.outcome
            it.before = m.before
            it.fromSleep = m.fromSleep
            it.says = m.says
            it.opens = m.opens
        }
    }

    /** For each thing you rate (and sleep), what goes with it being better, on the same day (sleep is the night after) */
    private fun pairInsights(comparables: List<Measured>, outcomes: List<Measured>, extras: List<Measured>): List<Insight> {
        val found = mutableListOf<Insight>()
        for (y in outcomes) for (x in comparables + extras) {
            if (baseOf(x) == baseOf(y) || setOf(kindOf(x), kindOf(y)) in MADE_FROM) continue
            if (x.key.endsWith(":dose") && kindOf(x) == StatKind.MEDICINE) continue
            if (x.fromSleep && kindOf(y) == StatKind.SLEEP) continue
            // Sleep is the night after a day (kept against the morning it ends), so it's set against the day before
            val lag = if (kindOf(y) == StatKind.SLEEP && !x.before) 1 else 0
            val groups = groups(x, y, lag)
            val valid = groups.filter { it.values.size >= INSIGHT_DAYS }
            if (valid.size < 2) continue
            val low = valid.first()
            val high = valid.last()
            val strength = strengthOf(high.values, low.values, groups.flatMap { it.values })
            if (strength < INSIGHT_STRENGTH) continue
            // Said from the better side: what goes with y being better
            val highBetter = average(high.values) > average(low.values)
            val (best, other) = if (highBetter) high to low else low to high
            val text = if (x.kind == ValueKind.SCORE && y.kind == ValueKind.SCORE) {
                if (highBetter) getString(R.string.ins_together, inSentence(y), inSentence(x))
                else getString(R.string.ins_opposite, inSentence(x), inSentence(y))
            } else getString(R.string.ins_sentence, feel(y, true), whenX(x, highBetter).let { w ->
                // "on days off" → "after days off" for the night after
                if (lag == 1 && w.startsWith(getString(R.string.ins_on_word) + " ")) getString(R.string.ins_after_word) + w.substring(getString(R.string.ins_on_word).length) else w
            })
            val detail = getString(R.string.ins_detail, y.name, y.format(average(best.values)), y.format(average(other.values)), best.values.size, other.values.size)
            val opens = x.opens
            val xKey = x.key
            val yKey = y.key
            found += Insight(x.icon, x.color, strength, capital(text), detail, "pair:" + listOf(baseOf(x), baseOf(y)).sorted().joinToString(),
                onTap = { if (opens != null) openIfStat(opens) else showInCompare(xKey, yKey, lag) },
                onHold = { openIfStat(opens ?: xKey.substringBefore(':')) })
        }
        return found
    }

    /**
     * More things to set against your days, only for insights: bedtime (against your usual one), checking
     * the phone in the night, phone time before bed, and which home you were at.
     */
    private fun extraMeasures(context: android.content.Context): List<Measured> {
        val list = mutableListOf<Measured>()
        val social = stats.firstOrNull { it.kind == StatKind.SOCIAL }
        val sleep = stats.firstOrNull { it.kind == StatKind.SLEEP }
        val nights = Sleep.nights(context)
        if (sleep != null && nights.size >= 2 * INSIGHT_DAYS) {
            val icon = StatIcons.forStat(sleep)
            fun night(key: String, values: Map<LocalDate, Float>, says: (Boolean) -> String) =
                list.add(Measured("night:$key", sleep.name, sleep.color, icon, ValueKind.YES_NO, values, ::percentText).also {
                    it.before = true
                    it.fromSleep = true
                    it.opens = sleep.id
                    it.says = says
                })
            // Asleep by about your usual time, to the half hour
            fun minutes(n: Sleep.Night) = n.bedtime.let { ((it.hour + 24 - BEDTIME_FROM) % 24) * 60 + it.minute }
            val usual = nights.values.map { minutes(it) }.sorted().let { it[it.size / 2] }
            val by = (usual + 15) / 30 * 30
            val clock = LocalTime.of(BEDTIME_FROM, 0).plusMinutes(by.toLong())
            val time = if (clock == LocalTime.MIDNIGHT) getString(R.string.ins_midnight)
            else clock.format(DateTimeFormatter.ofPattern(if (clock.minute == 0) "h a" else "h:mm a", Locale.getDefault())).lowercase(Locale.getDefault())
            night("bed", nights.mapValues { if (minutes(it.value) <= by) 1f else 0f }) { early ->
                getString(if (early) R.string.ins_asleep_by else R.string.ins_asleep_after, time)
            }
            // Checking the phone in the night: not at all, or less than usual
            val checks = nights.values.map { it.checks }.sorted()
            val none = checks.count { it == 0 } >= INSIGHT_DAYS && checks.count { it > 0 } >= INSIGHT_DAYS
            val most = if (none) 0 else checks[checks.size / 2]
            night("checks", nights.mapValues { if (it.value.checks <= most) 1f else 0f }) { few ->
                getString(when {
                    none && few -> R.string.ins_no_checks
                    none -> R.string.ins_checks
                    few -> R.string.ins_fewer_checks
                    else -> R.string.ins_more_checks
                })
            }
            // Phone time in the evening before bed
            val late = nights.values.map { it.lateMs }.sorted().let { it[it.size / 2] }
            night("late", nights.mapValues { if (it.value.lateMs <= late) 1f else 0f }) { little ->
                getString(if (little) R.string.ins_late_less else R.string.ins_late_more)
            }
        }
        // Each named place: gone to (half an hour or more there) or not, on days mostly tracked
        val socialDays = Social.days(context)
        val today = LocalDate.now()
        if (social != null) app.olauncher.helper.Places.places(context).forEach { place ->
            val went = socialDays.filter { (date, day) -> date == today || day.trackedMs >= 12 * 3_600_000L }.mapValues { (_, day) ->
                if (app.olauncher.helper.PlaceMath.wentTo(day.stretches.filter { it.place == place.id }.sumOf { it.ms })) 1f else 0f
            }
            list.add(Measured("place:" + place.id, place.name, place.color, StatIcons.forStat(social), ValueKind.YES_NO, went, ::percentText).also {
                it.opens = social.id
                it.says = { yes -> getString(if (yes) R.string.ins_at_place else R.string.ins_not_at_place, place.name) }
            })
        }
        // The weather's other sides: rain, daylight and warmth, set against your days like the stat
        val weather = stats.firstOrNull { it.kind == StatKind.WEATHER }
        val skies = app.olauncher.helper.WeatherHistory.days(context)
        if (weather != null && skies.isNotEmpty()) {
            val icon = StatIcons.forStat(weather)
            fun sky(key: String, kind: ValueKind, values: Map<LocalDate, Float>, format: (Float) -> String, says: (Boolean) -> String) =
                list.add(Measured(weather.id + ":" + key, weather.name, weather.color, icon, kind, values, format).also {
                    it.opens = weather.id
                    it.says = says
                })
            sky("rain", ValueKind.YES_NO, skies.mapValues { if (it.value.rainy) 1f else 0f }, ::percentText) { rainy ->
                getString(if (rainy) R.string.ins_rainy else R.string.ins_dry)
            }
            sky("daylight", ValueKind.AMOUNT, skies.mapValues { it.value.daylightHours }, ::hoursText) { more ->
                getString(if (more) R.string.ins_daylight_more else R.string.ins_daylight_less)
            }
            sky("warmth", ValueKind.AMOUNT, skies.mapValues { it.value.max }, { "${Math.round(it)}°" }) { warm ->
                getString(if (warm) R.string.ins_warmer else R.string.ins_cooler)
            }
        }
        // Which home: the one stayed at most that day
        val homes = Social.homes(context)
        val first = homes.getOrNull(0)
        val second = homes.getOrNull(1)
        if (social != null && first != null && second != null) {
            val days = Social.days(context).mapNotNull { (date, day) -> day.mainHome?.let { date to it.toFloat() } }.toMap()
            list.add(Measured("home", social.name, social.color, StatIcons.forStat(social), ValueKind.YES_NO, days, ::percentText).also {
                it.opens = social.id
                it.says = { atSecond -> getString(R.string.ins_at_home, if (atSecond) second.name else first.name) }
            })
        }
        return list
    }

    /** A medicine's last change of dose that's stuck: how you've been since, against before */
    private fun doseChangeInsights(outcomes: List<Measured>): List<Insight> {
        val found = mutableListOf<Insight>()
        val today = lastDay()
        val dateFormat = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
        stats.filter { it.isMedicine && it.faces.size > 2 }.forEach { med ->
            val taken = data.days.entries.mapNotNull { (key, day) ->
                val face = day.face(med.id)?.takeIf { !med.isBlank(it) } ?: return@mapNotNull null
                runCatching { LocalDate.parse(key) }.getOrNull()?.takeIf { it <= today }?.let { it to face }
            }.sortedBy { it.first }
            if (taken.isEmpty()) return@forEach
            class Run(val face: Int, val from: LocalDate, var to: LocalDate)
            val runs = mutableListOf<Run>()
            taken.forEach { (date, face) ->
                val last = runs.lastOrNull()
                if (last != null && last.face == face) last.to = date else runs += Run(face, date, date)
            }
            // Only stretches of a week or more count, so a day or two on another dose doesn't
            val settled = mutableListOf<Run>()
            runs.filter { ChronoUnit.DAYS.between(it.from, it.to) >= DOSE_SETTLED - 1 }.forEach { run ->
                val last = settled.lastOrNull()
                if (last != null && last.face == run.face) last.to = run.to else settled += Run(run.face, run.from, run.to)
            }
            if (settled.size < 2) return@forEach
            val before = settled[settled.size - 2]
            val after = settled.last()
            val end = minOf(if (runs.last().face == after.face) today else after.to, after.from.plusDays(DOSE_WINDOW - 1))
            val start = maxOf(before.from, after.from.minusDays(DOSE_WINDOW))
            fun amount(face: Int) = med.labels.getOrNull(med.faces.indexOf(face))?.let { Regex("""\d+(\.\d+)?""").find(it)?.value?.toFloatOrNull() }
                ?: med.faces.indexOf(face).toFloat()
            val dose = med.labels.getOrNull(med.faces.indexOf(after.face)).orEmpty()
            val up = amount(after.face) > amount(before.face)
            val icon = StatIcons.forStat(med)
            outcomes.forEach { y ->
                val was = y.values.filterKeys { it >= start && it < after.from }.values.toList()
                val now = y.values.filterKeys { it >= after.from && it <= end }.values.toList()
                if (was.size < INSIGHT_DAYS || now.size < INSIGHT_DAYS) return@forEach
                val strength = strengthOf(now, was)
                if (strength < INSIGHT_STRENGTH) return@forEach
                val how = feel(y, average(now) > average(was))
                val date = after.from.format(dateFormat)
                val text = if (dose.isBlank()) getString(R.string.ins_dose_changed, med.name, date, how)
                else getString(if (up) R.string.ins_dose_up else R.string.ins_dose_down, dose, med.name, date, how)
                val detail = getString(R.string.ins_detail_before, y.name, y.format(average(now)), y.format(average(was)), now.size, was.size)
                found += Insight(icon, med.color, strength, text, detail, "dose:" + med.id, onTap = { openIfStat(med.id) })
            }
        }
        return found
    }

    /** The day after a missed dose, when it's worse than usual */
    private fun missedDoseInsights(comparables: List<Measured>, outcomes: List<Measured>): List<Insight> {
        val found = mutableListOf<Insight>()
        comparables.filter { x -> x.kind == ValueKind.YES_NO && stats.firstOrNull { it.id == x.key }?.isMedicine == true }.forEach { x ->
            val med = stats.first { it.id == x.key }
            outcomes.filter { kindOf(it) != StatKind.SLEEP && baseOf(it) != x.key }.forEach { y ->
                val groups = groups(x, y, 1)
                val skipped = groups[0].values
                val taken = groups[1].values
                if (skipped.size < MISSED_DAYS || taken.size < INSIGHT_DAYS || average(skipped) >= average(taken)) return@forEach
                val strength = strengthOf(skipped, taken)
                if (strength < INSIGHT_STRENGTH) return@forEach
                val text = getString(R.string.ins_after_skip, med.name, feel(y, false))
                val detail = getString(R.string.ins_detail_skip, y.name, y.format(average(skipped)), y.format(average(taken)), skipped.size, taken.size)
                val xKey = x.key
                val yKey = y.key
                found += Insight(x.icon, x.color, strength, text, detail, "skip:" + x.key,
                    onTap = { showInCompare(xKey, yKey, 1) }, onHold = { openIfStat(xKey) })
            }
        }
        return found
    }

    /** What you do more of on low days, or good ones: spending, and porn from the Mac */
    private fun lowDayInsights(context: android.content.Context, comparables: List<Measured>): List<Insight> {
        val mood = comparables.firstOrNull { it.key == MoodStore.MOOD } ?: return emptyList()
        val things = mutableListOf<Pair<Measured, Int>>()
        comparables.firstOrNull { it.kind == ValueKind.AMOUNT && kindOf(it) == StatKind.MONEY }?.let { things += it to R.string.ins_spend_on }
        val libido = stats.firstOrNull { it.kind == StatKind.LIBIDO }
        val mac = DeviceTime.days(context, DeviceTime.Device.MAC)
        if (libido != null && mac.isNotEmpty() && DeviceTime.pornOn(context)) {
            val minutes = mac.filterKeys { it <= lastDay() }
                .mapValues { (_, day) -> day.apps.entries.filter { it.key.contains("VLC", ignoreCase = true) }.sumOf { it.value }.toFloat() }
            things += Measured(PORN_KEY, getString(R.string.ins_porn_name), libido.color, StatIcons.forStat(libido), ValueKind.AMOUNT, minutes,
                { getString(R.string.ins_minutes, Math.round(it)) }).also { it.opens = libido.id } to R.string.ins_porn_on
        }
        return things.mapNotNull { (y, line) ->
            val valid = groups(mood, y, 0).filter { it.values.size >= INSIGHT_DAYS }
            if (valid.size < 2) return@mapNotNull null
            val low = valid.first().values
            val good = valid.last().values
            val strength = strengthOf(low, good)
            if (strength < INSIGHT_STRENGTH) return@mapNotNull null
            val onLow = average(low) > average(good)
            val (more, less) = if (onLow) low to good else good to low
            val text = getString(line, getString(if (onLow) R.string.ins_low_days else R.string.ins_good_days))
            val detail = getString(R.string.ins_detail, y.name, y.format(average(more)), y.format(average(less)), more.size, less.size)
            val opens = y.opens
            val yKey = y.key
            Insight(y.icon, y.color, strength, text, detail, "low:" + y.key,
                onTap = { if (opens != null) openIfStat(opens) else showInCompare(MoodStore.MOOD, yKey, 0) },
                onHold = { openIfStat(opens ?: yKey) })
        }
    }

    /** Your mood's best and hardest day of the week, when they stand out */
    private fun weekdayInsights(outcomes: List<Measured>): List<Insight> {
        val mood = outcomes.firstOrNull { it.key == MoodStore.MOOD } ?: return emptyList()
        val byDay = mood.values.entries.groupBy({ it.key.dayOfWeek }, { it.value }).filterValues { it.size >= INSIGHT_DAYS }
        if (byDay.size < 7) return emptyList()
        val averages = byDay.mapValues { average(it.value) }
        return listOf(averages.minBy { it.value }.key to false, averages.maxBy { it.value }.key to true).mapNotNull { (day, best) ->
            val these = byDay.getValue(day)
            val rest = mood.values.filterKeys { it.dayOfWeek != day }.values.toList()
            val strength = strengthOf(these, rest)
            if (strength < INSIGHT_STRENGTH) return@mapNotNull null
            val name = day.getDisplayName(TextStyle.FULL, Locale.getDefault())
            val text = getString(if (best) R.string.ins_best_weekday else R.string.ins_low_weekday, name)
            val detail = getString(R.string.ins_detail_others, mood.name, mood.format(average(these)), mood.format(average(rest)), these.size, rest.size)
            Insight(mood.icon, mood.color, strength, text, detail, "weekday:$best", onTap = { openIfStat(MoodStore.MOOD) })
        }
    }

    /** The last two weeks against the two before, for whichever's changed most */
    private fun stretchInsights(outcomes: List<Measured>): List<Insight> {
        val today = lastDay()
        return outcomes.mapNotNull { y ->
            val recent = y.values.filterKeys { it > today.minusDays(14) && it <= today }.values.toList()
            val before = y.values.filterKeys { it > today.minusDays(28) && it <= today.minusDays(14) }.values.toList()
            if (recent.size < STRETCH_DAYS || before.size < STRETCH_DAYS) return@mapNotNull null
            val strength = strengthOf(recent, before, y.values.values.toList())
            if (strength < INSIGHT_STRENGTH) return@mapNotNull null
            val text = getString(R.string.ins_stretch, capital(feelPast(y, average(recent) > average(before))))
            val detail = getString(R.string.ins_detail_before, y.name, y.format(average(recent)), y.format(average(before)), recent.size, before.size)
            val id = baseOf(y)
            Insight(y.icon, y.color, strength, text, detail, STRETCH, headline = true, onTap = { openIfStat(id) })
        }
    }

    /** Last week, when it was your best for something in a good while */
    private fun recordInsights(outcomes: List<Measured>): List<Insight> {
        val today = LocalDate.now()
        val lastWeek = app.olauncher.helper.Week.start(today).minusWeeks(1)
        return outcomes.mapNotNull { y ->
            val weeks = y.values.entries.groupBy({ app.olauncher.helper.Week.start(it.key) }, { it.value })
                .filterValues { it.size >= RECORD_WEEK_DAYS }.mapValues { average(it.value) }
            val last = weeks[lastWeek] ?: return@mapNotNull null
            val earlier = weeks.filterKeys { it < lastWeek }
            if (earlier.size < RECORD_WEEKS) return@mapNotNull null
            val beaten = earlier.filterValues { it >= last }.keys.maxOrNull()
            val gap = beaten?.let { ChronoUnit.WEEKS.between(it, lastWeek) } ?: 1000L
            if (gap <= RECORD_WEEKS) return@mapNotNull null
            val name = inSentence(y)
            val text = if (beaten == null) getString(R.string.ins_record_yet, name) else {
                val month = beaten.plusDays(3)
                val words = month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + if (month.year != today.year) " " + month.year else ""
                getString(R.string.ins_record_since, name, words)
            }
            val detail = getString(R.string.ins_detail_record, y.name, y.format(last), y.format(average(earlier.values.toList())))
            val id = baseOf(y)
            Insight(y.icon, y.color, gap.toFloat(), text, detail, RECORD, headline = true, onTap = { openIfStat(id) })
        }
    }

    /** The clearest takeaways, in plain words; tapping one opens it in Compare or its stat */
    private fun fillInsights(card: Card, insights: List<Insight>) {
        if (insights.isEmpty()) return card.showNote(getString(R.string.insights_none))
        card.note.isVisible = false
        card.scene.isVisible = false
        val context = requireContext()
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        insights.forEach { insight ->
            list.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 9.dpToPx(), 0, 9.dpToPx())
                setBackgroundResource(android.R.drawable.list_selector_background)
                addView(View(context).apply { background = StatIcons.drawable(insight.icon, insight.color, 18.dpToPx()) },
                    LinearLayout.LayoutParams(18.dpToPx(), 18.dpToPx()).apply { marginEnd = 14.dpToPx() })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(card.textView(15f, 240).apply { text = insight.text })
                    addView(card.textView(12.5f, 140).apply {
                        text = insight.detail
                        updatePadding(top = 2.dpToPx())
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                setOnLongClickListener {
                    insight.onHold()
                    true
                }
                setOnClickListener { insight.onTap() }
            })
        }
        card.root.addView(list, card.root.indexOfChild(card.scene))
    }

    /**
     * Any stat against any other, whatever each holds: the second's average for each group of the first
     * (yes or no, each face, or low, middle and high amounts), on the same day or the next, with a line
     * saying what it comes to.
     */
    private fun fillCompare(card: Card) {
        if (comparables.size < 2) return card.showNote(getString(R.string.needs_more_days))
        card.note.isVisible = false
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
                setOnLongClickListener {
                    stats.firstOrNull { it.id == c.key.substringBefore(':') }?.let { openStat(it.id) }
                    true
                }
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
        private val WEEKS get() = app.olauncher.helper.Week.fields
        private const val GRID_DAYS = 18
        private val DEFAULT_NAMES = setOf("energy", "libido", "anxiety")
        private const val MIN_GROUP = 3
        private const val INSIGHT_DAYS = 5

        /** Pairs where one is counted from the other (time at work is time out), so a link is no news */
        private val MADE_FROM = setOf(setOf(app.olauncher.helper.StatKind.WORK, app.olauncher.helper.StatKind.SOCIAL))
        private const val INSIGHT_STRENGTH = 0.45f
        private const val INSIGHTS_SHOWN = 7
        private const val TREND_WEEKS = 0
        private const val TREND_MONTHS = 1
        private const val TREND_YEARS = 2

        /** Missed doses are fewer, so fewer days of them are enough */
        private const val MISSED_DAYS = 3

        /** A dose counts as changed once it's been kept this many days; before and after are looked at this far */
        private const val DOSE_SETTLED = 7
        private const val DOSE_WINDOW = 42L

        /** Days logged in each fortnight, and in a week for it to count towards a record, and weeks to beat */
        private const val STRETCH_DAYS = 7
        private const val RECORD_WEEK_DAYS = 4
        private const val RECORD_WEEKS = 6
        private const val BEDTIME_FROM = 18
        private const val STRETCH = "stretch"
        private const val RECORD = "record"
        private const val PORN_KEY = "porn"
        private const val STEPS_KEY = "steps"
        private const val REMEMBER_AFTER_DAYS = 7L
        private const val MIN_LINK_DAYS = 4
        private const val LINKS_SHOWN = 6
        private const val BUBBLE_ICONS = 6
        private const val FIRST_PICK = 5
        private const val PREFS = "stats_home"
        private const val PICKED = "picked"
        private const val KNOWN = "known"
        private const val NEW_ON_HOME = "new_on_home"

        /** Whether stats added later show here straight away (otherwise they start hidden) */
        fun showsNewStats(context: android.content.Context) = context.getSharedPreferences(PREFS, 0).getBoolean(NEW_ON_HOME, false)

        fun setShowsNewStats(context: android.content.Context, on: Boolean) =
            context.getSharedPreferences(PREFS, 0).edit().putBoolean(NEW_ON_HOME, on).apply()
    }
}
