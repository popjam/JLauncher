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

    /** The date the thoughts box holds, so redrawing doesn't overwrite typing */
    private var noteDate: LocalDate? = null
    private var noteInput: EditText? = null

    private lateinit var bubble: LinearLayout
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
        val scroll = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            addView(cardList)
        }
        bubble = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val page = FrameLayout(context).apply {
            setBackgroundColor(context.getColorFromAttr(R.attr.primaryShadeDarkColor))
            isClickable = true
            addView(scroll)
            addView(bubble, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                setMargins(16.dpToPx(), 0, 16.dpToPx(), 28.dpToPx())
            })
        }
        return PullDismissLayout(context).apply {
            addView(page)
            onDismiss = { if (isAdded) findNavController().popBackStack() }
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
        thisWeekCard()
        moodLinksCard()
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
            val rows = shown.filter { !it.isMedicine }.mapNotNull { stat ->
                val byDate = scores[stat.id].orEmpty()
                val now = byDate.filterKeys { it >= monday && it <= today }.values.takeIf { it.isNotEmpty() }?.average() ?: return@mapNotNull null
                val before = byDate.filterKeys { it >= monday.minusDays(7) && it < monday }.values.takeIf { it.isNotEmpty() }?.average()
                Triple(stat, now.toFloat(), before?.toFloat())
            }
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

    /**
     * Mood on days a stat was high against days it was low: the stats it moves with most, a bar either
     * side of the middle (better on the right).
     */
    private fun moodLinksCard() {
        val mood = scores[MoodStore.MOOD].orEmpty()
        val card = Card(getString(R.string.mood_links))
        card.subtitle(getString(R.string.mood_links_note))
        val good = requireContext().getColor(R.color.moodHappy)
        val bad = requireContext().getColor(R.color.moodSad)
        fun draw() {
            // Every stat, whatever's picked to show: the point is finding the ones that matter
            val rows = stats.filter { it.id != MoodStore.MOOD && !it.isMedicine }.mapNotNull { stat ->
                val byDate = scores[stat.id].orEmpty()
                val high = byDate.filterValues { it >= 4 }.keys.mapNotNull { mood[it] }
                val low = byDate.filterValues { it <= 2 }.keys.mapNotNull { mood[it] }
                if (high.size < MIN_LINK_DAYS || low.size < MIN_LINK_DAYS) return@mapNotNull null
                Triple(stat, (high.average() - low.average()).toFloat(), high.size to low.size)
            }.sortedByDescending { abs(it.second) }.take(LINKS_SHOWN)
            if (rows.isEmpty()) return card.showNote(getString(R.string.needs_more_days))
            card.describe = { key ->
                val (stat, diff, counts) = rows[key as Int]
                listOf(stat.name, getString(R.string.links_detail_high, decimal(mood.filterKeys { scores[stat.id].orEmpty()[it]?.let { s -> s >= 4 } == true }.values.average().toFloat()), counts.first),
                    getString(R.string.links_detail_low, decimal(mood.filterKeys { scores[stat.id].orEmpty()[it]?.let { s -> s <= 2 } == true }.values.average().toFloat()), counts.second),
                    getString(R.string.stat_label, getString(R.string.difference), String.format(Locale.getDefault(), "%+.1f", diff))).joinToString("\n")
            }
            val most = rows.maxOf { abs(it.second) }.coerceAtLeast(0.5f)
            card.draw { s ->
                val rowHeight = s.dp(30f)
                val nameSize = s.sp(13.5f)
                val mid = s.width * 0.62f
                val half = minOf(s.width - mid - s.dp(40f), mid - s.width * 0.42f)
                rows.forEachIndexed { i, (stat, diff, _) ->
                    val y = i * rowHeight
                    val cy = y + rowHeight / 2
                    s.icon(StatIcons.forStat(stat), 0f, cy - s.dp(7f), s.dp(14f), stat.color)
                    s.text(stat.name, s.dp(24f), cy + nameSize * 0.35f, faint(220), nameSize, Paint.Align.LEFT)
                    val length = abs(diff) / most * half
                    if (diff >= 0) s.rect(mid, cy - s.dp(6f), mid + length, cy + s.dp(6f), good, s.dp(4f))
                    else s.rect(mid - length, cy - s.dp(6f), mid, cy + s.dp(6f), bad, s.dp(4f))
                    s.text(String.format(Locale.getDefault(), "%+.1f", diff), s.width, cy + s.sp(4f), faint(200), s.sp(12f), Paint.Align.RIGHT)
                    s.target(i, RectF(0f, y, s.width, y + rowHeight))
                }
                s.line(mid, 0f, mid, rows.size * rowHeight, faint(60), s.dp(1.5f))
                s.height = rows.size * rowHeight
            }
        }
        draw()
        redraws.add { draw() }
    }

    /** Any stat against any other: the second's average on days the first had each of its faces. */
    private fun compareCard() {
        val card = Card(getString(R.string.compare_two))
        fun pick(current: String?, onPick: (String) -> Unit) {
            val options = stats
            AlertDialog.Builder(requireContext())
                .setItems(options.map { it.name }.toTypedArray()) { _, i -> onPick(options[i].id) }
                .show()
        }
        val pickers = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        card.root.addView(pickers, card.root.indexOfChild(card.scene), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 14.dpToPx()
        })
        fun draw() {
            val x = stats.firstOrNull { it.id == compareX } ?: stats.firstOrNull { it.id != compareY && it.id != MoodStore.MOOD } ?: return
            val y = stats.firstOrNull { it.id == compareY } ?: stats.first()
            pickers.removeAllViews()
            fun chip(stat: StatInfo, onTap: () -> Unit) = card.textView(14f, 255).apply {
                text = stat.name
                setBackgroundResource(R.drawable.bg_stat_chip)
                setPadding(12.dpToPx(), 7.dpToPx(), 14.dpToPx(), 7.dpToPx())
                setCompoundDrawablesRelativeWithIntrinsicBounds(StatIcons.drawable(StatIcons.forStat(stat), stat.color, 14.dpToPx()), null, null, null)
                compoundDrawablePadding = 8.dpToPx()
                setOnClickListener { onTap() }
            }
            pickers.addView(chip(x) { pick(x.id) { compareX = it; draw() } })
            pickers.addView(card.textView(13f, 150).apply {
                text = getString(R.string.compare_against)
                setPadding(10.dpToPx(), 0, 10.dpToPx(), 0)
            })
            pickers.addView(chip(y) { pick(y.id) { compareY = it; draw() } })
            val xs = scores[x.id].orEmpty()
            val ys = scores[y.id].orEmpty()
            // By the first stat's faces, left to right as on its page
            val buckets = x.faces.map { face ->
                val score = MoodStore.score(face, x.inverted)
                val values = xs.filterValues { it == score }.keys.mapNotNull { ys[it] }
                Triple(face, values.takeIf { it.isNotEmpty() }?.average()?.toFloat(), values.size)
            }
            if (buckets.count { it.second != null } < 2) return card.showNote(getString(R.string.needs_more_days))
            card.describe = { key ->
                val (face, average, count) = buckets[key as Int]
                listOf(getString(R.string.stat_label, x.name, faceName(x, face)),
                    getString(R.string.stat_label, y.name, average?.let(::decimal) ?: "–"),
                    resources.getQuantityString(R.plurals.day_count, count, count)).joinToString("\n")
            }
            card.draw { s ->
                val top = s.dp(6f)
                val bottom = top + s.dp(120f)
                val column = s.width / buckets.size
                fun yOf(v: Float) = bottom - (v - 1f) / 4f * (bottom - top) - s.dp(2f)
                listOf(1, 3, 5).forEach { g ->
                    s.line(s.dp(16f), yOf(g.toFloat()), s.width, yOf(g.toFloat()), faint(28), s.dp(1f))
                    s.text(g.toString(), 0f, yOf(g.toFloat()) + s.sp(4f), faint(130), s.sp(11f), Paint.Align.LEFT)
                }
                val bar = minOf(column * 0.5f, s.dp(30f))
                buckets.forEachIndexed { i, (face, average, _) ->
                    val center = s.dp(16f) + i * (s.width - s.dp(16f)) / buckets.size + (s.width - s.dp(16f)) / buckets.size / 2
                    if (average == null) s.rect(center - bar / 2, bottom - s.dp(2f), center + bar / 2, bottom, faint(45), s.dp(1f))
                    else {
                        val color = y.faces.minByOrNull { abs(MoodStore.score(it, y.inverted) - average) }?.let { y.faceColor(requireContext(), it) } ?: y.color
                        s.rect(center - bar / 2, yOf(average), center + bar / 2, bottom, color, s.dp(6f))
                    }
                    val label = faceName(x, face)
                    s.text(if (label.length > 8) label.take(7) + "…" else label, center, bottom + s.dp(18f), faint(160), s.sp(11.5f))
                    s.target(i, RectF(i * column, 0f, (i + 1) * column, bottom + s.dp(26f)))
                }
                s.height = bottom + s.dp(26f)
            }
        }
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
            setColor(if (dark) 0xF2202024.toInt() else 0xF2F4F4F6.toInt())
            setStroke(1.dpToPx(), faint(40))
        }
        bubble.elevation = 8.dpToPx().toFloat()
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
            listOf(R.string.bubble_all to { setHidden(emptySet()) }, R.string.bubble_none to { setHidden(stats.map { it.id }.toSet() - MoodStore.MOOD) }).forEach { (label, action) ->
                addView(TextView(context, null, 0, R.style.TextSmall).apply {
                    setText(label)
                    textSize = 13f
                    setTextColor(ink)
                    alpha = 0.75f
                    setPadding(10.dpToPx(), 6.dpToPx(), 10.dpToPx(), 6.dpToPx())
                    setOnClickListener { action() }
                })
            }
            addView(TextView(context, null, 0, R.style.TextSmall).apply {
                text = "▾"
                textSize = 16f
                setTextColor(ink)
                setPadding(12.dpToPx(), 2.dpToPx(), 6.dpToPx(), 2.dpToPx())
                contentDescription = getString(R.string.close)
                setOnClickListener {
                    bubbleOpen = false
                    bubble.setPadding(0, 0, 0, 0)
                    renderBubble()
                }
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

    private fun setHidden(ids: Set<String>) {
        requireContext().getSharedPreferences(PREFS, 0).edit().putBoolean(PICKED, true).apply()
        store.setHiddenInGraph(ids)
        redrawPicked()
    }

    override fun onDestroyView() {
        noteInput?.hideKeyboard()
        noteInput = null
        redraws.clear()
        super.onDestroyView()
    }

    companion object {
        private val WEEKS = WeekFields.ISO
        private const val GRID_DAYS = 18
        private const val REMEMBER_AFTER_DAYS = 7L
        private const val MIN_LINK_DAYS = 4
        private const val LINKS_SHOWN = 6
        private const val BUBBLE_ICONS = 6
        private const val FIRST_PICK = 5
        private const val PREFS = "stats_home"
        private const val PICKED = "picked"
    }
}
