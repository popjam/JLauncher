package app.olauncher.ui

import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.databinding.FragmentStatPageBinding
import app.olauncher.helper.Spending
import app.olauncher.helper.StatInfo
import app.olauncher.helper.allStats
import app.olauncher.helper.dpToPx
import app.olauncher.helper.formattedTimeSpent
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.setStatDot
import app.olauncher.helper.statDot
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
import kotlin.math.ceil

/**
 * One stat up close, a page of [StatFragment]. A card per view of it: its last days,
 * its trend by week, month or year, its days over time, how its days spread, and how it differs on
 * days a medicine was taken. A medicine shows the days it was taken and its doses instead.
 *
 * A medicine set to affect the next day ([StatInfo.nextDay]) counts each dose towards the day after,
 * wherever it's set against another stat.
 */
class StatPageFragment : Fragment() {

    private var _binding: FragmentStatPageBinding? = null
    private val binding get() = _binding!!

    private lateinit var moodStore: MoodStore
    private var data = MoodStore.Data()
    private var stats = emptyList<StatInfo>()
    private lateinit var stat: StatInfo

    /** Every day with something saved, oldest first */
    private var dates = emptyList<LocalDate>()

    /** How many stretches of its days back from today the Daily, Taken or Cups card is */
    private var stretchesBack = 0

    /** The face Over time is showing only the days of, picked from the chips under it */
    private var overTimeFilter: Int? = null

    private val ink by lazy { requireContext().getColorFromAttr(R.attr.primaryColor) }
    private fun faint(alpha: Int) = ColorUtils.setAlphaComponent(ink, alpha)

    private enum class Period { WEEKS, MONTHS, YEARS }
    private enum class Span { MONTHS_3, YEAR, ALL }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStatPageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        moodStore = MoodStore(requireContext())
        binding.back.setOnClickListener { findNavController().popBackStack() }
        binding.editStat.setOnClickListener {
            StatEditor.show(requireContext(), moodStore, stat.id) { id ->
                if (_binding == null) return@show
                // Deleted
                if (id == null) findNavController().popBackStack() else render()
            }
        }
    }

    // Built on start, which the pages either side of the open one reach too, so they're ready before
    // they're swiped to. Only rebuilt on coming to the front if another page changed a card's choice.
    override fun onStart() {
        super.onStart()
        render()
    }

    override fun onResume() {
        super.onResume()
        // Also after coming back from turning on notification access for Money spent
        if (renderedWith != choices || (spendingShownOn != null && spendingShownOn != Spending.isEnabled(requireContext()))) render()
    }

    private val choices get() = trendPeriod to overTimeSpan
    private var renderedWith: Pair<Int, Int>? = null

    /** Whether notification access was on when the Money spent card was drawn; null without the card */
    private var spendingShownOn: Boolean? = null

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun render() {
        renderedWith = choices
        data = moodStore.load()
        stats = requireContext().allStats(data)
        val id = requireArguments().getString(StatFragment.STAT_ID)
        stat = stats.firstOrNull { it.id == id } ?: run {
            findNavController().popBackStack()
            return
        }
        dates = data.days.keys.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.sorted()

        binding.statName.text = stat.name
        binding.statName.setCompoundDrawablesRelativeWithIntrinsicBounds(statDot(stat.color, 10.dpToPx()), null, null, null)
        binding.statName.compoundDrawablePadding = 10.dpToPx()

        binding.cards.removeAllViews()
        spendingShownOn = null
        if (Spending.moneyStat(data)?.id == stat.id) spendingCard()
        if (stat.medicine) {
            if (stat.name.contains(COFFEE, ignoreCase = true)) cupsCard() else takenCard()
            if (stat.name.contains(SERTRALINE, ignoreCase = true)) holidayCard()
            trendCard()
            overTimeCard()
            doseCard()
        } else {
            dailyCard()
            trendCard()
            overTimeCard()
            if (stat.id == MoodStore.USAGE) topAppCard() else howOftenCard()
            weekdayCard()
        }
        takenVsNotCard()
    }

    // --- Values ---

    private val usage get() = stat.id == MoodStore.USAGE

    private fun dayOf(date: LocalDate) = data.days[date.toString()]

    /** [s]'s value on [date]: hours for Screen time, otherwise its face's score, 1 to 5. */
    private fun valueOf(s: StatInfo, date: LocalDate): Float? {
        val day = dayOf(date) ?: return null
        if (s.id == MoodStore.USAGE) return day.usageMs?.let { it / HOUR_MS }
        return day.face(s.id)?.let { MoodStore.score(it, s.inverted).toFloat() }
    }

    /** The face [value] is nearest, or for a medicine [taken] at all, its nearest dose. */
    private fun faceFor(s: StatInfo, value: Float, taken: Boolean = false): Int {
        if (s.id == MoodStore.USAGE) return MoodStore.moodForUsage((value * HOUR_MS).toLong())
        val faces = if (taken) s.faces.filter { !s.isBlank(it) } else s.faces
        return faces.minBy { abs(MoodStore.score(it, s.inverted) - value) }
    }

    /** Null for a medicine's blank face, left empty like on the calendar. */
    private fun colorFor(s: StatInfo, value: Float, taken: Boolean = false): Int? =
        faceFor(s, value, taken).let { face -> if (s.isBlank(face)) null else s.faceColor(requireContext(), face) }

    /** The medicine's dose counted towards [date]: the day before's when it affects the next day. */
    private fun doseFor(med: StatInfo, date: LocalDate): Int? =
        dayOf(if (med.nextDay) date.minusDays(1) else date)?.face(med.id)

    private fun takenOn(med: StatInfo, date: LocalDate): Boolean? = doseFor(med, date)?.let { !med.isBlank(it) }

    /** A face's label, or failing that a name for it: Mood's word, a medicine's dose, or a plain score, 1 to 5. */
    private fun faceName(s: StatInfo, face: Int): String {
        val place = s.faces.indexOf(face)
        s.labels.getOrNull(place)?.takeIf { it.isNotEmpty() }?.let { return it }
        return when {
            s.id == MoodStore.USAGE -> resources.getStringArray(R.array.usage_faces)[face]
            s.id == MoodStore.MOOD -> resources.getStringArray(R.array.mood_names)[face]
            !s.medicine -> MoodStore.score(face, s.inverted).toString()
            place <= 0 -> getString(R.string.none_taken)
            s.faces.size == 2 -> getString(R.string.taken)
            else -> getString(R.string.dose_number, place)
        }
    }

    /** [s]'s day in words: its face's name, or Screen time's time. */
    private fun dayText(s: StatInfo, date: LocalDate): String? {
        val day = dayOf(date) ?: return null
        if (s.id == MoodStore.USAGE) return day.usageMs?.let { requireContext().formattedTimeSpent(it) }
        return day.face(s.id)?.let { faceName(s, it) }
    }

    private fun averageText(s: StatInfo, average: Float): String =
        if (s.id == MoodStore.USAGE) getString(R.string.a_day, requireContext().formattedTimeSpent((average * HOUR_MS).toLong()))
        else String.format(Locale.getDefault(), "%.1f", average)

    /** [s]'s average over the days [keyOf] puts in each group, leaving out days it gives null. */
    private fun averages(s: StatInfo, keyOf: (LocalDate) -> Int?): Map<Int, Float> {
        val values = mutableMapOf<Int, MutableList<Float>>()
        dates.forEach { date ->
            val key = keyOf(date) ?: return@forEach
            val value = valueOf(s, date) ?: return@forEach
            values.getOrPut(key) { mutableListOf() }.add(value)
        }
        return values.mapValues { (_, list) -> list.average().toFloat() }
    }

    /** For a medicine, the days it was taken and the days with an entry at all, in each group. */
    private fun takenCounts(keyOf: (LocalDate) -> Int?): Map<Int, Pair<Int, Int>> {
        val counts = mutableMapOf<Int, Pair<Int, Int>>()
        dates.forEach { date ->
            val key = keyOf(date) ?: return@forEach
            val face = dayOf(date)?.face(stat.id) ?: return@forEach
            val (taken, all) = counts[key] ?: (0 to 0)
            counts[key] = (taken + if (stat.isBlank(face)) 0 else 1) to all + 1
        }
        return counts
    }

    private val firstDate: LocalDate? get() = dates.firstOrNull { valueOf(stat, it) != null }

    /** Screen time's axis: at least 8 hours, rounded up to an even number to fit [highest]. */
    private fun hoursMax(highest: Float?) = maxOf(8f, ceil((highest ?: 0f) / 2) * 2)

    private val mood get() = stats.first { it.id == MoodStore.MOOD }

    /** A medicine's line on the trend: between its light and dark purples. */
    private val medicineColor
        get() = ColorUtils.blendARGB(
            ContextCompat.getColor(requireContext(), R.color.medicineLight),
            ContextCompat.getColor(requireContext(), R.color.medicineDark), 0.4f
        )

    private fun dateText(date: LocalDate) = date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))

    // --- Cards ---

    /** A card with its title, anything on the right of it, its drawing, a key, and the tapped point's details. */
    private inner class Card(title: String) {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.rounded_rect_shade_color)
            setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 14.dpToPx())
        }
        val headerEnd = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val scene = StatSceneView(context)
        val note = textView(13f, 150).apply {
            gravity = Gravity.CENTER
            updatePadding(top = 20.dpToPx(), bottom = 20.dpToPx())
            isVisible = false
        }
        val chips = FlowLayout(context).apply { isVisible = false }
        val key = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isVisible = false
        }
        val details = textView(13f, 255).apply {
            gravity = Gravity.CENTER
            setLineSpacing(4.dpToPx().toFloat(), 1f)
            isVisible = false
        }

        /** What the tapped point's details say */
        var describe: (Any) -> String = { "" }

        init {
            root.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(textView(15f, 255).apply { this.text = title }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(headerEnd)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 14.dpToPx()
            })
            root.addView(scene)
            root.addView(note)
            root.addView(chips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 14.dpToPx()
            })
            root.addView(key, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 12.dpToPx()
            })
            root.addView(details, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 10.dpToPx()
            })
            scene.onPicked = { picked ->
                details.isVisible = picked != null
                details.text = picked?.let(describe)
            }
            binding.cards.addView(root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 0)
            })
        }

        fun textView(size: Float, alpha: Int) = TextView(context, null, 0, R.style.TextSmall).apply {
            textSize = size
            setTextColor(faint(alpha))
        }

        fun subtitle(text: String) {
            headerEnd.removeAllViews()
            headerEnd.addView(textView(13f, 150).apply { this.text = text })
        }

        /** A big figure with a line under it, between the title and the drawing */
        fun headline(figure: String, caption: String) {
            root.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(TextView(context, null, 0, R.style.TextLarge).apply {
                    text = figure
                    textSize = 40f
                    setTextColor(ink)
                    includeFontPadding = false
                })
                addView(textView(13f, 150).apply {
                    text = caption
                    updatePadding(top = 4.dpToPx())
                })
            }, root.indexOfChild(scene), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 16.dpToPx()
            })
        }

        /** Options on the right, the [selected] one bold. */
        fun segment(options: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
            headerEnd.removeAllViews()
            options.forEachIndexed { i, option ->
                headerEnd.addView(textView(13f, if (i == selected) 255 else 120).apply {
                    setText(option)
                    if (i == selected) setTypeface(null, Typeface.BOLD)
                    setPadding(8.dpToPx(), 4.dpToPx(), if (i == options.lastIndex) 0 else 2.dpToPx(), 4.dpToPx())
                    setOnClickListener { if (i != selected) onSelect(i) }
                })
            }
        }

        /** ‹ [text] ›, stepping with [onStep] (-1 back, 1 on) where [canGoBack] and [canGoForward]. */
        fun stepper(text: String, canGoBack: Boolean, canGoForward: Boolean, days: Int, onStep: (Int) -> Unit) {
            headerEnd.removeAllViews()
            fun arrow(symbol: String, enabled: Boolean, by: Int, description: Int) = TextView(context, null, 0, R.style.TextLarge).apply {
                this.text = symbol
                textSize = 18f
                setTextColor(ink)
                alpha = if (enabled) 0.8f else 0.2f
                isEnabled = enabled
                contentDescription = getString(description, days)
                setPadding(10.dpToPx(), 0, 10.dpToPx(), 0)
                setOnClickListener { onStep(by) }
            }
            headerEnd.addView(arrow("‹", canGoBack, -1, R.string.previous_days))
            headerEnd.addView(textView(13f, 150).apply { this.text = text })
            headerEnd.addView(arrow("›", canGoForward, 1, R.string.next_days))
        }

        /**
         * A chip per option, each with its colour's dot. The [selected] one stands out and the rest fade;
         * with none selected they're all as they are.
         */
        fun chipRow(options: List<Pair<String, Int>>, selected: Int?, onTap: (Int) -> Unit) {
            chips.isVisible = true
            chips.removeAllViews()
            options.forEachIndexed { i, (name, color) ->
                chips.addView(textView(13f, 255).apply {
                    text = name
                    setBackgroundResource(R.drawable.bg_stat_chip)
                    setPadding(10.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
                    setStatDot(color)
                    alpha = when (selected) {
                        null -> 0.8f
                        i -> 1f
                        else -> 0.35f
                    }
                    if (i == selected) setTypeface(null, Typeface.BOLD)
                    setOnClickListener { onTap(i) }
                })
            }
        }

        fun keyItem(color: Int, text: String, ring: Boolean = false) {
            key.isVisible = true
            key.addView(textView(13f, 150).apply {
                this.text = text
                gravity = Gravity.CENTER_VERTICAL
                val size = 11.dpToPx()
                val mark = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setSize(size, size)
                    if (ring) setStroke(2.dpToPx(), color) else setColor(color)
                }
                setCompoundDrawablesRelativeWithIntrinsicBounds(mark, null, null, null)
                compoundDrawablePadding = 6.dpToPx()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = 16.dpToPx()
            })
        }

        fun draw(build: (StatSceneView.Scene) -> Unit) {
            note.isVisible = false
            scene.isVisible = true
            details.isVisible = false
            scene.setScene(build = build)
        }

        /** Instead of a drawing, when there's too little to draw */
        fun showNote(text: String) {
            scene.isVisible = false
            chips.isVisible = false
            details.isVisible = false
            note.isVisible = true
            note.text = text
        }
    }

    /** The [count] days the Daily, Taken or Cups card shows, stepping back to the stat's first day. */
    private fun dailyDates(card: Card, count: Int = DAILY_DAYS, redraw: () -> Unit): List<LocalDate> {
        val today = LocalDate.now()
        val furthestBack = firstDate?.let { (ChronoUnit.DAYS.between(it, today) / count).toInt() } ?: 0
        stretchesBack = stretchesBack.coerceIn(0, furthestBack)
        val end = today.minusDays(count.toLong() * stretchesBack)
        val start = end.minusDays(count - 1L)
        val title = if (stretchesBack == 0) getString(R.string.last_days, count)
        else {
            val format = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
            getString(R.string.graph_month_range, start.format(format), end.format(format))
        }
        card.stepper(title, canGoBack = (stretchesBack < furthestBack), canGoForward = (stretchesBack > 0), days = count) { by ->
            stretchesBack -= by
            redraw()
        }
        return (0 until count).map { start.plusDays(it.toLong()) }
    }

    /**
     * A bar a day, as tall as its score (or Screen time's hours) in its face's colour, with a line along
     * its 7-day average. Under it, a row of dots per medicine on the days it counts towards.
     */
    private fun dailyCard() {
        val card = Card(getString(R.string.stat_daily))
        card.keyItem(ink, getString(R.string.seven_day_average))
        fun draw() {
            val days = dailyDates(card, redraw = ::draw)
            val today = LocalDate.now()
            val values = days.map { valueOf(stat, it) }
            val averages = days.map { date ->
                if (date > today) null
                else (0L until AVERAGE_DAYS).mapNotNull { valueOf(stat, date.minusDays(it)) }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
            }
            val max = if (usage) hoursMax((values + averages).filterNotNull().maxOrNull()) else 5f
            val meds = stats.filter { it.medicine && it.id != stat.id }
                .map { med -> med to days.map { date -> doseFor(med, date)?.takeIf { !med.isBlank(it) } } }
                .filter { (_, doses) -> doses.any { it != null } }
            card.describe = { key ->
                val date = key as LocalDate
                val i = days.indexOf(date)
                val head = listOfNotNull(dateText(date), dayText(stat, date) ?: "–",
                    averages[i]?.let { getString(R.string.stat_total, getString(R.string.seven_day_average), averageText(stat, it)) })
                    .joinToString(" · ")
                val taken = meds.mapNotNull { (med, doses) -> doses[i]?.let { getString(R.string.stat_label, med.name, faceName(med, it)) } }
                (listOf(head) + taken).joinToString("\n")
            }
            card.draw { s ->
                val width = s.width
                val column = width / days.size
                val top = s.dp(4f)
                val bottom = top + s.dp(120f)
                fun y(value: Float) = bottom - minOf(value, max) / max * (bottom - top)
                val grid = if (usage) (2 until max.toInt() step 2).toList() else listOf(1, 3, 5)
                grid.forEach { g ->
                    s.line(0f, y(g.toFloat()), width, y(g.toFloat()), faint(30), s.dp(1f))
                    if (usage) s.text(getString(R.string.hours_short, g), width, y(g.toFloat()) - s.dp(4f), faint(130), s.sp(11f), Paint.Align.RIGHT)
                }
                val gap = minOf(s.dp(1.5f), column * 0.15f)
                val corner = minOf(s.dp(4f), column / 4)
                values.forEachIndexed { i, value ->
                    val x = i * column
                    val color = value?.let { colorFor(stat, it) }
                    if (value == null || color == null) s.rect(x + gap, bottom - s.dp(2f), x + column - gap, bottom, faint(45), s.dp(1f))
                    else s.rect(x + gap, y(value), x + column - gap, bottom, color, corner)
                }
                s.polyline(averages.mapIndexed { i, average -> average?.let { PointF(i * column + column / 2, y(it)) } }, ink, s.dp(2.5f))

                var rowTop = bottom + s.dp(10f)
                val radius = minOf(s.dp(5.5f), column / 2 - s.dp(1f))
                meds.forEach { (med, doses) ->
                    s.text(med.name, 0f, rowTop + s.sp(12f), faint(170), s.sp(12f), Paint.Align.LEFT)
                    doses.forEachIndexed { i, face ->
                        face?.let { s.circle(i * column + column / 2, rowTop + s.dp(26f), radius, med.faceColor(requireContext(), it)) }
                    }
                    rowTop += s.dp(38f)
                }
                labelIndices(days.size, 3).forEach { i ->
                    s.text(days[i].dayOfMonth.toString(), i * column + column / 2, rowTop + s.dp(16f), faint(140), s.sp(12f))
                }
                s.height = rowTop + s.dp(22f)
                days.forEachIndexed { i, date ->
                    val x = i * column
                    s.target(date, RectF(x, 0f, x + column, s.height), RectF(x, 0f, x + column, rowTop + s.dp(2f)), corner)
                }
            }
        }
        draw()
    }

    /** A medicine's days as a calendar, each square in its dose's purple, or an outline when not taken. */
    private fun takenCard() {
        val card = Card(getString(R.string.stat_taken))
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
                val corner = s.dp(9f)
                days.forEachIndexed { i, date ->
                    val place = i + lead
                    val x = left + place % 7 * cell + gap
                    val y = gridTop + place / 7 * cell + gap
                    val size = cell - gap * 2
                    val face = dayOf(date)?.face(stat.id)
                    val textColor = when {
                        face == null -> faint(70)
                        stat.isBlank(face) -> {
                            s.outline(x, y, x + size, y + size, faint(70), corner, s.dp(1.5f))
                            faint(130)
                        }
                        else -> {
                            val fill = stat.faceColor(requireContext(), face)
                            s.rect(x, y, x + size, y + size, fill, corner)
                            if (ColorUtils.calculateLuminance(fill) > 0.4) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()
                        }
                    }
                    val textSize = s.sp(13.5f)
                    s.text(date.dayOfMonth.toString(), x + size / 2, y + size / 2 + textSize * 0.35f, textColor, textSize, bold = face != null && !stat.isBlank(face))
                    s.target(date, RectF(x - gap, y - gap, x + size + gap, y + size + gap), RectF(x - gap / 2, y - gap / 2, x + size + gap / 2, y + size + gap / 2), corner)
                }
                val rows = (days.size + lead + 6) / 7
                s.height = gridTop + rows * cell + s.dp(2f)
            }
        }
        draw()
    }

    /**
     * Coffee's days as stacks of little cups, one per cup: its place in the row, so the faces go 0 to 4
     * cups. Cups past the [CUPS_HOT]rd stand out. A dot is a day with none; a gap, a day not logged.
     */
    private fun cupsCard() {
        val card = Card(getString(R.string.stat_cups))
        card.keyItem(stat.color, getString(R.string.a_cup))
        card.keyItem(hotCup, getString(R.string.cup_past, CUPS_HOT + 1))
        fun cupsOn(date: LocalDate): Int? = dayOf(date)?.face(stat.id)?.let { stat.faces.indexOf(it).coerceAtLeast(0) }
        fun draw() {
            val days = dailyDates(card, CUP_DAYS, ::draw)
            val cups = days.map { cupsOn(it) }
            card.describe = { key ->
                val date = key as LocalDate
                val count = cupsOn(date)
                listOf(dateText(date), count?.let { resources.getQuantityString(R.plurals.cup_count, it, it) } ?: "–").joinToString(" · ")
            }
            card.draw { s ->
                val column = s.width / days.size
                val cup = minOf(column * 0.6f, s.dp(24f))
                val cupHeight = cup * 0.72f
                val gap = s.dp(4f)
                val most = maxOf(cups.maxOf { it ?: 0 }, stat.faces.size - 1)
                val base = s.dp(4f) + most * (cupHeight + gap)
                s.line(0f, base + s.dp(2f), s.width, base + s.dp(2f), faint(40), s.dp(1.5f))
                cups.forEachIndexed { i, count ->
                    val x = i * column + (column - cup) / 2 - cup * 0.06f
                    if (count == 0) s.circle(x + cup / 2, base - s.dp(4f), s.dp(2f), faint(110))
                    repeat(count ?: 0) { k ->
                        val y = base - (k + 1) * (cupHeight + gap) + gap
                        val color = if (k >= CUPS_HOT) hotCup else stat.color
                        s.path(Path().apply {
                            moveTo(x, y)
                            lineTo(x + cup, y)
                            lineTo(x + cup * 0.88f, y + cupHeight)
                            lineTo(x + cup * 0.12f, y + cupHeight)
                            close()
                        }, color)
                        s.path(Path().apply {
                            moveTo(x + cup * 0.94f, y + cupHeight * 0.18f)
                            quadTo(x + cup * 1.3f, y + cupHeight * 0.3f, x + cup * 0.94f, y + cupHeight * 0.78f)
                        }, color, cup * 0.12f)
                    }
                    s.text(days[i].dayOfMonth.toString(), i * column + column / 2, base + s.dp(22f), faint(140), s.sp(12f))
                }
                s.height = base + s.dp(28f)
                days.forEachIndexed { i, date ->
                    val x = i * column
                    s.target(date, RectF(x, 0f, x + column, s.height), RectF(x, 0f, x + column, base + s.dp(6f)))
                }
            }
        }
        draw()
    }

    /**
     * Purchases read from CommBank's and Google Wallet's notifications, which fill in Money spent: today's
     * total and the latest few. Until notification access is on, a way to turn it on.
     */
    private fun spendingCard() {
        val context = requireContext()
        val card = Card(getString(R.string.spending_card))
        val on = Spending.isEnabled(context)
        spendingShownOn = on
        if (!on) {
            card.showNote(getString(R.string.spending_off))
            card.chipRow(listOf(getString(R.string.spending_turn_on) to ink), null) { Spending.openSettings(context) }
            return
        }
        card.subtitle(getString(R.string.spending_sources))
        val purchases = Spending.purchases(context)
        val today = LocalDate.now()
        val todays = purchases.filter { it.date == today }
        card.headline(money(todays.sumOf { it.cents }), resources.getQuantityString(R.plurals.spending_today, todays.size, todays.size))
        if (purchases.isEmpty()) {
            card.showNote(getString(R.string.spending_none))
            return
        }
        card.scene.isVisible = false
        val timeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        purchases.take(SPENDING_SHOWN).forEach { purchase ->
            val time = java.time.Instant.ofEpochMilli(purchase.time).atZone(java.time.ZoneId.systemDefault())
            val `when` = if (purchase.date == today) time.format(timeFormat) else dateText(purchase.date)
            list.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                updatePadding(top = 6.dpToPx(), bottom = 6.dpToPx())
                addView(card.textView(15f, 255).apply {
                    text = money(purchase.cents)
                    setTypeface(null, Typeface.BOLD)
                }, LinearLayout.LayoutParams(76.dpToPx(), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(card.textView(13f, 170).apply {
                    text = purchase.shop
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(card.textView(12f, 130).apply {
                    text = `when`
                    updatePadding(left = 10.dpToPx())
                })
            })
        }
        card.root.addView(list, card.root.indexOfChild(card.scene))
    }

    private fun money(cents: Long) = String.format(Locale.getDefault(), "$%d.%02d", cents / 100, cents % 100)

    private val hotCup by lazy { ContextCompat.getColor(requireContext(), R.color.holiday) }

    /** A run of days a medicine wasn't taken, and the days just after it. */
    private class Holiday(val start: LocalDate, val end: LocalDate) {
        val length get() = ChronoUnit.DAYS.between(start, end).toInt() + 1
        val after get() = (1..HOLIDAY_AFTER_DAYS).map { end.plusDays(it.toLong()) }
        fun contains(date: LocalDate) = date in start..end
    }

    /**
     * Breaks of [HOLIDAY_DAYS] or more days not taken, oldest first. Days with nothing logged for the
     * medicine don't end a break, but it only counts from and to days logged as not taken.
     */
    private fun holidays(): List<Holiday> {
        val found = mutableListOf<Holiday>()
        var start: LocalDate? = null
        var lastOff: LocalDate? = null
        fun close() {
            val first = start ?: return
            val last = lastOff ?: return
            if (ChronoUnit.DAYS.between(first, last) + 1 >= HOLIDAY_DAYS) found.add(Holiday(first, last))
            start = null
            lastOff = null
        }
        val from = firstDate ?: return found
        var date = from
        val today = LocalDate.now()
        while (date <= today) {
            when (takenOn(stat, date)) {
                false -> {
                    if (start == null) start = date
                    lastOff = date
                }
                true -> close()
                null -> {}
            }
            date = date.plusDays(1)
        }
        close()
        return found
    }

    /**
     * How Mood and the stats a break tends to shift (by name: libido, anxiety, OCD, energy) went during
     * the medicine's breaks and the [HOLIDAY_AFTER_DAYS] days after, against the usual days it was taken.
     * A chip per break shows just that one; tapping it again goes back to all of them.
     */
    private fun holidayCard() {
        val card = Card(getString(R.string.drug_holidays))
        val holidays = holidays()
        if (holidays.isEmpty()) {
            card.showNote(getString(R.string.holiday_none, HOLIDAY_DAYS))
            return
        }
        val holidayColor = ContextCompat.getColor(requireContext(), R.color.holiday)
        val afterColor = ContextCompat.getColor(requireContext(), R.color.medicineLight)
        val rangeFormat = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
        fun rangeText(h: Holiday) = if (h.length == 1) h.start.format(rangeFormat)
        else getString(R.string.graph_month_range, h.start.format(rangeFormat), h.end.format(rangeFormat))

        val last = holidays.last()
        card.headline(holidays.size.toString(),
            resources.getQuantityString(R.plurals.holiday_count, holidays.size, holidays.size, rangeText(last), last.length))
        card.keyItem(faint(200), getString(R.string.holiday_usual), ring = true)
        card.keyItem(holidayColor, getString(R.string.holiday_during))
        card.keyItem(afterColor, getString(R.string.holiday_after, HOLIDAY_AFTER_DAYS))

        val measured = HOLIDAY_STATS.mapNotNull { word ->
            stats.firstOrNull { s -> if (word == MoodStore.MOOD) s.id == MoodStore.MOOD else s.id != MoodStore.MOOD && s.name.contains(word, ignoreCase = true) }
        }.distinct().filter { it.id != stat.id }
        // Usual: days it was taken, away from every break and the days after one
        val usualDays = dates.filter { date -> takenOn(stat, date) == true && holidays.none { it.contains(date) || date in it.after } }

        var picked: Holiday? = null
        fun average(s: StatInfo, days: List<LocalDate>) = days.mapNotNull { valueOf(s, it) }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        fun change(value: Float?, usual: Float?) = if (value == null || usual == null) "" else
            String.format(Locale.getDefault(), " (%+.1f)", value - usual)

        fun draw() {
            val chosen = picked?.let { listOf(it) } ?: holidays
            val during = chosen.flatMap { h -> (0 until h.length).map { h.start.plusDays(it.toLong()) } }
            val after = chosen.flatMap { it.after }.filter { date -> chosen.none { it.contains(date) } }
            val rows = measured.map { s -> Triple(s, average(s, usualDays), average(s, during) to average(s, after)) }
            val shown = holidays.takeLast(HOLIDAY_CHIPS).reversed()
            card.chipRow(shown.map { rangeText(it) to holidayColor }, shown.indexOf(picked).takeIf { it >= 0 }) { i ->
                picked = if (picked == shown[i]) null else shown[i]
                draw()
            }
            card.describe = { key ->
                val (s, usual, values) = rows[key as Int]
                listOf(
                    s.name,
                    getString(R.string.holiday_usual_value, usual?.let(::decimal) ?: "–"),
                    getString(R.string.holiday_during_value, (values.first?.let(::decimal) ?: "–") + change(values.first, usual)),
                    getString(R.string.holiday_after_value, (values.second?.let(::decimal) ?: "–") + change(values.second, usual)),
                ).joinToString(" · ")
            }
            if (rows.isEmpty()) {
                card.showNote(getString(R.string.holiday_no_stats))
                return
            }
            card.draw { s ->
                val labelSize = s.sp(13f)
                val left = rows.maxOf { s.textWidth(it.first.name, labelSize) } + s.dp(14f)
                val changeWidth = s.textWidth("+0.0", labelSize) + s.dp(10f)
                val right = s.width - changeWidth - s.dp(8f)
                fun x(value: Float) = left + (value - 1f) / 4f * (right - left)
                val rowHeight = s.dp(38f)
                var top = s.dp(18f)
                // 1 to 5 along the top
                (1..5).forEach { n -> s.text(n.toString(), x(n.toFloat()), s.sp(11f), faint(120), s.sp(11f)) }
                rows.forEachIndexed { i, (row, usual, values) ->
                    val mid = top + rowHeight / 2
                    s.text(row.name, 0f, mid + labelSize * 0.35f, faint(220), labelSize, Paint.Align.LEFT)
                    s.line(left, mid, right, mid, faint(35), s.dp(2f))
                    val (during, after) = values
                    val points = listOfNotNull(usual, during, after)
                    if (points.size > 1) s.line(x(points.min()), mid, x(points.max()), mid, faint(90), s.dp(2f))
                    after?.let { s.circle(x(it), mid, s.dp(6f), afterColor) }
                    during?.let { s.circle(x(it), mid, s.dp(6f), holidayColor) }
                    usual?.let { s.ring(x(it), mid, s.dp(7.5f), faint(220), s.dp(2f)) }
                    if (during != null && usual != null) {
                        val diff = during - usual
                        s.text(String.format(Locale.getDefault(), "%+.1f", diff), s.width, mid + labelSize * 0.35f,
                            if (abs(diff) >= 0.3f) holidayColor else faint(150), labelSize, Paint.Align.RIGHT, bold = abs(diff) >= 0.3f)
                    }
                    s.target(i, RectF(0f, top, s.width, top + rowHeight))
                    top += rowHeight
                }
                s.height = top + s.dp(2f)
            }
        }
        draw()
    }

    private fun decimal(value: Float) = String.format(Locale.getDefault(), "%.1f", value)

    /**
     * The average by week (the last 13), month (the last 12) or year, from the stat's first, with
     * Mood faint behind it. The point still going is a ring.
     */
    private fun trendCard() {
        val card = Card(getString(R.string.stat_trend))
        val color = if (stat.medicine) medicineColor else stat.color
        val moodColor = mood.color
        val withMood = stat.id != MoodStore.MOOD
        if (withMood) {
            card.keyItem(color, stat.name)
            card.keyItem(ColorUtils.setAlphaComponent(moodColor, 100), mood.name)
        }
        fun draw() {
            val period = Period.entries[trendPeriod]
            card.segment(listOf(R.string.trend_weeks, R.string.trend_months, R.string.trend_years), trendPeriod) {
                trendPeriod = it
                draw()
            }
            val start = firstDate ?: return card.showNote(getString(R.string.no_data))
            val today = LocalDate.now()
            val last = periodOf(period, today)
            val first = when (period) {
                Period.WEEKS -> maxOf(last - 12, periodOf(period, start))
                Period.MONTHS -> maxOf(last - 11, periodOf(period, start))
                Period.YEARS -> periodOf(period, start)
            }
            val keyOf = { date: LocalDate -> periodOf(period, date).takeIf { it in first..last } }
            val values = averages(stat, keyOf)
            val moodValues = if (withMood) averages(mood, keyOf) else emptyMap()
            val taken = if (stat.medicine) takenCounts(keyOf) else emptyMap()
            val max = if (usage) hoursMax(values.values.maxOrNull()) else 5f
            fun fraction(value: Float) = if (usage) value / max else (value - 1) / 4
            val ticks: List<Pair<Float, String>> = when {
                usage -> (0..max.toInt() step 2).map { it / max to getString(R.string.hours_short, it) }
                stat.labels.isNotEmpty() -> stat.faces.mapIndexedNotNull { i, face ->
                    stat.labels[i].takeIf { it.isNotEmpty() }?.let { fraction(MoodStore.score(face, stat.inverted).toFloat()) to it }
                }
                else -> listOf(1, 3, 5).map { fraction(it.toFloat()) to it.toString() }
            }
            card.describe = { key ->
                val point = key as Int
                val start = periodStart(period, point)
                val title = when (period) {
                    Period.WEEKS -> getString(R.string.week_number, start.get(WeekFields.ISO.weekOfWeekBasedYear()))
                    Period.MONTHS -> start.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
                    Period.YEARS -> point.toString()
                }
                val parts = mutableListOf(if (point == last) getString(R.string.graph_so_far, title) else title)
                if (stat.medicine) taken[point]?.let { (yes, all) -> parts.add(getString(R.string.taken_of_days, yes, all)) }
                else values[point]?.let { parts.add(getString(R.string.stat_total, stat.name, averageText(stat, it))) }
                moodValues[point]?.let { parts.add(getString(R.string.stat_total, mood.name, averageText(mood, it))) }
                parts.joinToString(" · ")
            }
            card.draw { s ->
                val labelSize = s.sp(11.5f)
                val left = maxOf(s.dp(18f), (ticks.maxOfOrNull { s.textWidth(it.second, labelSize) } ?: 0f) + s.dp(8f))
                val right = s.width - s.dp(10f)
                val top = s.dp(10f)
                val bottom = top + s.dp(120f)
                val count = last - first + 1
                fun x(point: Int) = if (count == 1) (left + right) / 2 else left + (point - first) * (right - left) / (count - 1)
                fun y(fraction: Float) = bottom - fraction.coerceIn(0f, 1f) * (bottom - top)
                ticks.forEach { (fraction, label) ->
                    s.line(left, y(fraction), right, y(fraction), faint(30), s.dp(1f))
                    s.text(label, left - s.dp(8f), y(fraction) + labelSize * 0.35f, faint(140), labelSize, Paint.Align.RIGHT)
                }
                fun series(points: Map<Int, Float>, color: Int, width: Float, radius: Float, toFraction: (Float) -> Float) {
                    s.polyline((first..last).map { p -> points[p]?.let { PointF(x(p), y(toFraction(it))) } }, color, width)
                    points.forEach { (p, value) ->
                        if (p == last) s.ring(x(p), y(toFraction(value)), radius + s.dp(1.5f), color, s.dp(2.5f))
                        else s.circle(x(p), y(toFraction(value)), radius, color)
                    }
                }
                if (withMood) series(moodValues, ColorUtils.setAlphaComponent(moodColor, 90), s.dp(2f), s.dp(3f)) { (it - 1) / 4 }
                series(values, color, s.dp(3f), s.dp(4f), ::fraction)
                val labelY = bottom + s.dp(20f)
                labelIndices(count, maxOf(1, (count + 3) / 4)).forEach { i ->
                    val align = when {
                        count == 1 -> Paint.Align.CENTER
                        i == 0 -> Paint.Align.LEFT
                        i == count - 1 -> Paint.Align.RIGHT
                        else -> Paint.Align.CENTER
                    }
                    s.text(periodLabel(period, first + i), x(first + i), labelY, faint(140), s.sp(12f), align)
                }
                s.height = labelY + s.dp(6f)
                val half = if (count == 1) right - left else (right - left) / (count - 1) / 2
                (first..last).forEach { p ->
                    s.target(p, RectF(x(p) - half, 0f, x(p) + half, s.height), RectF(x(p) - s.dp(11f), top - s.dp(8f), x(p) + s.dp(11f), bottom + s.dp(8f)), s.dp(11f))
                }
            }
        }
        draw()
    }

    /**
     * The 3 months, the year or every month so far, a square per day or a block per month in its face's
     * colour. A chip per face under it shows only that face's days, and a month's block how many it had.
     */
    private fun overTimeCard() {
        val card = Card(getString(R.string.stat_over_time))
        // Screen time's faces from least time to most, otherwise as they are left to right
        val faces = if (usage) MoodStore.facesFor(5).sorted() else stat.faces
        fun filterColor(face: Int) = if (stat.isBlank(face)) faint(140) else stat.faceColor(requireContext(), face)
        fun dayColor(date: LocalDate, today: LocalDate): Int {
            if (date > today) return faint(12)
            val filter = overTimeFilter ?: return valueOf(stat, date)?.let { colorFor(stat, it) } ?: faint(30)
            return if (dayOf(date)?.face(stat.id) == filter) filterColor(filter) else faint(30)
        }
        fun describeDay(date: LocalDate) = listOf(dateText(date), dayText(stat, date) ?: "–").joinToString(" · ")
        fun draw() {
            val span = Span.entries[overTimeSpan]
            card.segment(listOf(R.string.over_3_months, R.string.over_year, R.string.over_all), overTimeSpan) {
                overTimeSpan = it
                draw()
            }
            val start = firstDate ?: return card.showNote(getString(R.string.no_data))
            val today = LocalDate.now()
            // Tapping the picked chip again shows every day again
            card.chipRow(faces.map { faceName(stat, it) to filterColor(it) }, faces.indexOf(overTimeFilter).takeIf { it >= 0 }) { i ->
                overTimeFilter = faces[i].takeIf { it != overTimeFilter }
                draw()
            }
            when (span) {
                Span.YEAR -> {
                    card.describe = { describeDay(it as LocalDate) }
                    card.draw { s ->
                        val left = s.dp(20f)
                        val cell = (s.width - left) / 31
                        val size = cell - s.dp(2f)
                        val rowHeight = cell + s.dp(2f)
                        val corner = minOf(s.dp(2.5f), size / 3)
                        Month.values().forEachIndexed { row, month ->
                            val y = row * rowHeight
                            s.text(month.getDisplayName(TextStyle.NARROW, Locale.getDefault()), 0f, y + size - s.dp(1f), faint(140), s.sp(11f), Paint.Align.LEFT)
                            val ym = YearMonth.of(today.year, month)
                            for (d in 1..ym.lengthOfMonth()) {
                                val date = ym.atDay(d)
                                val x = left + (d - 1) * cell + s.dp(1f)
                                s.rect(x, y, x + size, y + size, dayColor(date, today), corner)
                                if (date <= today) s.target(date, RectF(x - s.dp(1f), y - s.dp(2f), x + size + s.dp(1f), y + size + s.dp(2f)), RectF(x - s.dp(2f), y - s.dp(2f), x + size + s.dp(2f), y + size + s.dp(2f)), corner)
                            }
                        }
                        s.height = 12 * rowHeight
                    }
                }
                Span.MONTHS_3 -> {
                    card.describe = { describeDay(it as LocalDate) }
                    card.draw { s ->
                        val gap = s.dp(10f)
                        val width = (s.width - gap * 2) / 3
                        val cell = width / 7
                        val size = cell - s.dp(2f)
                        val gridTop = s.dp(22f)
                        val corner = minOf(s.dp(2.5f), size / 3)
                        (2 downTo 0).forEachIndexed { i, back ->
                            val ym = YearMonth.from(today).minusMonths(back.toLong())
                            val left = i * (width + gap)
                            s.text(ym.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()), left + s.dp(1f), s.sp(12f), faint(170), s.sp(12f), Paint.Align.LEFT)
                            val lead = ym.atDay(1).dayOfWeek.value - 1
                            for (d in 1..ym.lengthOfMonth()) {
                                val date = ym.atDay(d)
                                val place = lead + d - 1
                                val x = left + place % 7 * cell + s.dp(1f)
                                val y = gridTop + place / 7 * cell
                                s.rect(x, y, x + size, y + size, dayColor(date, today), corner)
                                if (date <= today) s.target(date, RectF(x - s.dp(1f), y - s.dp(1f), x + size + s.dp(1f), y + size + s.dp(1f)), RectF(x - s.dp(2f), y - s.dp(2f), x + size + s.dp(2f), y + size + s.dp(2f)), corner)
                            }
                        }
                        s.height = gridTop + 6 * cell
                    }
                }
                Span.ALL -> {
                    val keyOf = { date: LocalDate -> date.year * 12 + date.monthValue - 1 }
                    val values = averages(stat, keyOf)
                    val taken = if (stat.medicine) takenCounts(keyOf) else emptyMap()
                    val filter = overTimeFilter
                    // With a face picked, each month's days with it and days with anything
                    val matching = mutableMapOf<Int, Pair<Int, Int>>()
                    if (filter != null) dates.forEach { date ->
                        val face = dayOf(date)?.face(stat.id) ?: return@forEach
                        val (yes, all) = matching[keyOf(date)] ?: (0 to 0)
                        matching[keyOf(date)] = (yes + if (face == filter) 1 else 0) to all + 1
                    }
                    card.describe = { key ->
                        val month = key as Int
                        val title = YearMonth.of(month / 12, month % 12 + 1).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
                        val value = when {
                            filter != null -> matching[month]?.let { (yes, all) -> getString(R.string.filter_days, faceName(stat, filter), yes, all) }
                            stat.medicine -> taken[month]?.let { (yes, all) -> getString(R.string.taken_of_days, yes, all) }
                            else -> values[month]?.let { averageText(stat, it) }
                        }
                        listOf(title, value ?: "–").joinToString(" · ")
                    }
                    val years = start.year..today.year
                    card.draw { s ->
                        val left = s.dp(40f)
                        val cell = (s.width - left) / 12
                        val block = s.dp(20f)
                        val rowHeight = s.dp(26f)
                        val thisMonth = today.year * 12 + today.monthValue - 1
                        years.forEachIndexed { row, year ->
                            val y = row * rowHeight
                            s.text(year.toString(), 0f, y + block / 2 + s.sp(4f), faint(170), s.sp(12f), Paint.Align.LEFT)
                            for (m in 0 until 12) {
                                val month = year * 12 + m
                                val x = left + m * cell + s.dp(1.5f)
                                val value = values[month]
                                val share = matching[month]?.let { (yes, all) -> yes.toFloat() / all }
                                val color = when {
                                    month > thisMonth -> faint(12)
                                    // Stronger the more of the month's days had the picked face
                                    filter != null -> if (share != null && share > 0)
                                        ColorUtils.setAlphaComponent(filterColor(filter), (70 + 185 * share).toInt()) else faint(30)
                                    value == null -> faint(30)
                                    stat.medicine -> if ((taken[month]?.first ?: 0) > 0) colorFor(stat, value, taken = true) ?: faint(30) else faint(30)
                                    else -> colorFor(stat, value) ?: faint(30)
                                }
                                s.rect(x, y, x + cell - s.dp(3f), y + block, color, s.dp(4f))
                                if (month <= thisMonth) s.target(month, RectF(x, y, x + cell - s.dp(3f), y + block), RectF(x - s.dp(1.5f), y - s.dp(1.5f), x + cell - s.dp(1.5f), y + block + s.dp(1.5f)), s.dp(5f))
                            }
                        }
                        val labelY = years.count() * rowHeight + s.dp(8f)
                        Month.values().forEachIndexed { m, month ->
                            s.text(month.getDisplayName(TextStyle.NARROW, Locale.getDefault()), left + m * cell + (cell - s.dp(3f)) / 2 + s.dp(1.5f), labelY, faint(140), s.sp(11f))
                        }
                        s.height = labelY + s.dp(4f)
                    }
                }
            }
        }
        draw()
    }

    /** How many of the last 30 days had each face, best first. */
    private fun howOftenCard() {
        val card = Card(getString(R.string.stat_how_often))
        card.subtitle(getString(R.string.last_days, RECENT_DAYS))
        val faces = stat.faces.sortedByDescending { MoodStore.score(it, stat.inverted) }
        val recent = recentDays()
        val rows = faces.map { face ->
            Triple(faceName(stat, face), recent.count { dayOf(it)?.face(stat.id) == face }, stat.faceColor(requireContext(), face))
        }
        horizontalBars(card, rows) { it.toString() }
    }

    /** How many of the last 30 days had each dose, biggest first. */
    private fun doseCard() {
        val card = Card(getString(R.string.stat_dose))
        card.subtitle(getString(R.string.last_days, RECENT_DAYS))
        val faces = stat.faces.sortedByDescending { MoodStore.score(it, stat.inverted) }
        val recent = recentDays()
        val rows = faces.map { face ->
            val color = if (stat.isBlank(face)) faint(70) else stat.faceColor(requireContext(), face)
            Triple(faceName(stat, face), recent.count { dayOf(it)?.face(stat.id) == face }, color)
        }
        horizontalBars(card, rows) { it.toString() }
    }

    /** The apps that were used most on the most days of the last 30. */
    private fun topAppCard() {
        val card = Card(getString(R.string.stat_top_app))
        card.subtitle(getString(R.string.days_it_was_top))
        val rows = recentDays().mapNotNull { dayOf(it)?.topApp }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(TOP_APPS)
            .map { (app, days) -> Triple(app, days, stat.color) }
        horizontalBars(card, rows) { resources.getQuantityString(R.plurals.day_count, it, it) }
    }

    private fun recentDays(): List<LocalDate> = LocalDate.now().let { today -> (0L until RECENT_DAYS).map { today.minusDays(it) } }

    /** A bar per row, as long as its count, with the count after it. */
    private fun horizontalBars(card: Card, rows: List<Triple<String, Int, Int>>, countText: (Int) -> String) {
        val most = rows.maxOfOrNull { it.second } ?: 0
        if (most == 0) return card.showNote(getString(R.string.no_data))
        card.draw { s ->
            val nameSize = s.sp(13f)
            val textPaint = TextPaint().apply { textSize = nameSize }
            val left = minOf(maxOf(s.dp(60f), rows.maxOf { s.textWidth(it.first, nameSize) } + s.dp(12f)), s.width / 3)
            val longest = s.width - left - s.dp(56f)
            val rowHeight = s.dp(28f)
            rows.forEachIndexed { i, (name, count, color) ->
                val y = i * rowHeight
                val shown = TextUtils.ellipsize(name, textPaint, left - s.dp(8f), TextUtils.TruncateAt.END).toString()
                s.text(shown, 0f, y + s.dp(16f), faint(220), nameSize, Paint.Align.LEFT)
                val length = maxOf(longest * count / most, s.dp(3f))
                s.rect(left, y + s.dp(4f), left + length, y + s.dp(20f), color, s.dp(5f))
                s.text(countText(count), left + length + s.dp(8f), y + s.dp(16.5f), faint(150), s.sp(12f), Paint.Align.LEFT)
            }
            s.height = rows.size * rowHeight
        }
    }

    /** The average of every Monday, every Tuesday and so on. */
    private fun weekdayCard() {
        val card = Card(getString(R.string.stat_by_weekday))
        card.subtitle(getString(if (usage) R.string.average_hours else R.string.averages))
        val byDay = DayOfWeek.values().associateWith { mutableListOf<Float>() }
        dates.forEach { date -> valueOf(stat, date)?.let { byDay.getValue(date.dayOfWeek).add(it) } }
        if (byDay.values.sumOf { it.size } < MIN_WEEKDAY_DAYS) return card.showNote(getString(R.string.needs_more_days))
        val averages = DayOfWeek.values().map { day -> byDay.getValue(day).takeIf { it.isNotEmpty() }?.average()?.toFloat() }
        val max = if (usage) hoursMax(averages.filterNotNull().maxOrNull()) else 5f
        card.draw { s ->
            val column = s.width / 7
            val top = s.dp(18f)
            val bottom = top + s.dp(80f)
            averages.forEachIndexed { i, average ->
                val x = i * column + s.dp(7f)
                val barWidth = column - s.dp(14f)
                if (average == null) s.rect(x, bottom - s.dp(2f), x + barWidth, bottom, faint(45), s.dp(1f))
                else {
                    val barTop = bottom - minOf(average, max) / max * (bottom - top)
                    s.rect(x, barTop, x + barWidth, bottom, colorFor(stat, average) ?: faint(70), s.dp(6f))
                    s.text(String.format(Locale.getDefault(), "%.1f", average), x + barWidth / 2, barTop - s.dp(6f), faint(220), s.sp(11.5f))
                }
                s.text(DayOfWeek.of(i + 1).getDisplayName(TextStyle.NARROW, Locale.getDefault()), x + barWidth / 2, bottom + s.dp(18f), faint(140), s.sp(12f))
            }
            s.height = bottom + s.dp(24f)
        }
    }

    /**
     * For each medicine (or on a medicine's page, each other stat), the stat's average on the days it
     * counts as taken, filled, and on the days it doesn't, a ring, joined by a line.
     */
    private fun takenVsNotCard() {
        class Row(val name: String, val color: Int, val taken: Float, val takenDays: Int, val notTaken: Float, val notDays: Int)

        // Screen time's hours don't share the other stats' axis
        val pairs = if (stat.medicine) stats.filter { !it.medicine && it.id != MoodStore.USAGE }.map { it to stat }
        else stats.filter { it.medicine && it.id != stat.id }.map { stat to it }
        if (pairs.isEmpty()) return
        val card = Card(getString(R.string.stat_taken_vs_not))
        if (usage) card.subtitle(getString(R.string.hours))
        card.keyItem(faint(170), getString(R.string.days_not_taken), ring = true)
        card.keyItem(faint(170), getString(R.string.days_taken))

        val rows = pairs.mapNotNull { (measured, med) ->
            val taken = mutableListOf<Float>()
            val notTaken = mutableListOf<Float>()
            dates.forEach { date ->
                val value = valueOf(measured, date) ?: return@forEach
                when (takenOn(med, date)) {
                    true -> taken.add(value)
                    false -> notTaken.add(value)
                    null -> Unit
                }
            }
            if (taken.size < MIN_TAKEN_DAYS || notTaken.size < MIN_TAKEN_DAYS) return@mapNotNull null
            val other = if (stat.medicine) measured else med
            Row(other.name, other.color, taken.average().toFloat(), taken.size, notTaken.average().toFloat(), notTaken.size)
        }.sortedByDescending { abs(it.taken - it.notTaken) }
        if (rows.isEmpty()) return card.showNote(getString(R.string.needs_taken_and_not))

        val measuredStat = if (stat.medicine) null else stat
        card.describe = { key ->
            val row = rows[key as Int]
            val text = { value: Float -> measuredStat?.let { averageText(it, value) } ?: String.format(Locale.getDefault(), "%.1f", value) }
            getString(R.string.taken_vs_not_detail, row.name, text(row.taken), row.takenDays, text(row.notTaken), row.notDays)
        }
        val low = if (usage) 0f else 1f
        val high = if (usage) hoursMax(rows.maxOf { maxOf(it.taken, it.notTaken) }) else 5f
        val ticks = if (usage) (0..high.toInt() step 2).toList() else (1..5).toList()
        card.draw { s ->
            val nameSize = s.sp(13.5f)
            val textPaint = TextPaint().apply { textSize = nameSize }
            val left = minOf(rows.maxOf { s.textWidth(it.name, nameSize) } + s.dp(34f), s.width / 3)
            val right = s.width - s.dp(12f)
            fun x(value: Float) = left + (value - low) / (high - low) * (right - left)
            val rowHeight = s.dp(30f)
            val bottom = rows.size * rowHeight
            ticks.forEach { tick ->
                s.line(x(tick.toFloat()), 0f, x(tick.toFloat()), bottom, faint(35), s.dp(1.5f))
                s.text(tick.toString(), x(tick.toFloat()), bottom + s.dp(18f), faint(140), s.sp(12.5f))
            }
            rows.forEachIndexed { i, row ->
                val y = s.dp(14f) + i * rowHeight
                s.circle(s.dp(6f), y, s.dp(5.5f), row.color)
                val name = TextUtils.ellipsize(row.name, textPaint, left - s.dp(30f), TextUtils.TruncateAt.END).toString()
                s.text(name, s.dp(20f), y + nameSize * 0.35f, faint(220), nameSize, Paint.Align.LEFT)
                s.line(x(row.notTaken), y, x(row.taken), y, faint(110), s.dp(3.5f))
                s.ring(x(row.notTaken), y, s.dp(7f), faint(170), s.dp(2.5f))
                s.circle(x(row.taken), y, s.dp(8f), row.color)
                val area = RectF(0f, y - rowHeight / 2, s.width, y + rowHeight / 2)
                s.target(i, area, area, s.dp(10f))
            }
            s.height = bottom + s.dp(24f)
        }
    }

    // --- Periods ---

    /** Weeks count from 1970's first Monday-starting week, months from year 0, and years are years. */
    private fun periodOf(period: Period, date: LocalDate): Int = when (period) {
        Period.WEEKS -> (date.with(DayOfWeek.MONDAY).toEpochDay() / 7).toInt()
        Period.MONTHS -> date.year * 12 + date.monthValue - 1
        Period.YEARS -> date.year
    }

    private fun periodStart(period: Period, point: Int): LocalDate = when (period) {
        // Mondays' epoch days are 4 more than a multiple of 7
        Period.WEEKS -> LocalDate.ofEpochDay(point * 7L + 4)
        Period.MONTHS -> LocalDate.of(point / 12, point % 12 + 1, 1)
        Period.YEARS -> LocalDate.of(point, 1, 1)
    }

    private fun periodLabel(period: Period, point: Int): String = when (period) {
        Period.WEEKS -> getString(R.string.week_short, periodStart(period, point).get(WeekFields.ISO.weekOfWeekBasedYear()))
        Period.MONTHS -> Month.of(point % 12 + 1).getDisplayName(TextStyle.SHORT, Locale.getDefault())
        Period.YEARS -> point.toString()
    }

    /** Every [step]th of [count] points, and the last, dropping one that would crowd it. */
    private fun labelIndices(count: Int, step: Int): List<Int> {
        val indices = (0 until count step step).toMutableList()
        if (indices.last() != count - 1) {
            if (count - 1 - indices.last() < (step + 1) / 2 && indices.size > 1) indices.removeAt(indices.lastIndex)
            indices.add(count - 1)
        }
        return indices
    }

    companion object {
        private const val DAILY_DAYS = 18
        private const val AVERAGE_DAYS = 7L
        private const val RECENT_DAYS = 30
        private const val TOP_APPS = 5
        private const val MIN_TAKEN_DAYS = 3
        private const val MIN_WEEKDAY_DAYS = 14
        private const val HOUR_MS = 3_600_000f

        /** Only Sertraline gets the level card for now, with its usual half-life */
        private const val SERTRALINE = "sertraline"

        /** Coffee gets cups instead of the Taken calendar, [CUP_DAYS] days at a time */
        private const val COFFEE = "coffee"

        /** How many of the latest purchases Money spent lists */
        private const val SPENDING_SHOWN = 8
        private const val CUP_DAYS = 10

        /** How many cups before the rest stand out */
        private const val CUPS_HOT = 3
        /** Sertraline's breaks: how long one has to be, how many days after it count, and how many get a chip */
        private const val HOLIDAY_DAYS = 2
        private const val HOLIDAY_AFTER_DAYS = 3
        private const val HOLIDAY_CHIPS = 8

        /** What a break is measured against, by name, Mood first */
        private val HOLIDAY_STATS = listOf(MoodStore.MOOD, "libido", "anxi", "ocd", "energy")

        /** The Trend and Over time cards' choices, kept while the app runs */
        private var trendPeriod = 0
        private var overTimeSpan = 1
    }
}
