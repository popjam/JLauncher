package app.olauncher.ui

import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.databinding.FragmentStatPageBinding
import app.olauncher.helper.AutoStats
import app.olauncher.helper.Music
import app.olauncher.helper.DeviceTime
import app.olauncher.helper.PhoneHabits
import app.olauncher.helper.Sleep
import app.olauncher.helper.Steps
import app.olauncher.helper.Vibecoding
import app.olauncher.helper.Social
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
import kotlin.math.roundToInt
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
        if (renderedWith != choices || (spendingShownOn != null && spendingShownOn != Spending.isEnabled(requireContext())) ||
            (socialShownState != null && socialShownState != socialState())) render()
    }

    private val choices get() = trendPeriod to overTimeSpan
    private var renderedWith: Pair<Int, Int>? = null

    /** Whether Screen time's page has read the Mac's and TV's files since it opened */
    private var devicesRead = false

    /** Whether notification access was on when the Money spent card was drawn; null without the card */
    private var spendingShownOn: Boolean? = null

    /** Location access and homes when the Social cards were drawn; null without them */
    private var socialShownState: String? = null

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
        socialShownState = null
        if (Spending.moneyStat(data)?.id == stat.id) spendingCard()
        if (AutoStats.id(requireContext(), AutoStats.SLEEP, data) == stat.id) {
            val nights = Sleep.nights(requireContext())
            sleepCard(nights)
            sleepTimesCard(nights)
            sleepMoodCard(nights)
        }
        if (AutoStats.id(requireContext(), AutoStats.MUSIC, data) == stat.id) musicCard()
        if (AutoStats.id(requireContext(), AutoStats.STEPS, data) == stat.id) stepsCard()
        if (AutoStats.id(requireContext(), AutoStats.VIBECODING, data) == stat.id) {
            val days = Vibecoding.days(requireContext())
            vibeTodayCard(days)
            vibeProjectsCard(days)
            vibeHoursCard(days)
            vibeYouCard(days)
            vibeCostCard(days)
        }
        if (AutoStats.id(requireContext(), AutoStats.SOCIAL, data) == stat.id) {
            val days = Social.days(requireContext())
            socialTodayCard(days)
            socialHomesCard(days)
            socialTimelineCard(days)
            socialStayInCard(days)
            socialMoodCard(days)
        }
        if (stat.medicine) {
            if (stat.name.contains(COFFEE, ignoreCase = true)) cupsCard() else takenCard()
            if (stat.name.contains(SERTRALINE, ignoreCase = true)) holidayCard()
            trendCard()
            overTimeCard()
            doseCard()
        } else {
            dailyCard()
            if (stat.id == MoodStore.USAGE) {
                deviceSplitCard()
                readDevicesOnce()
                val habits = PhoneHabits.days(requireContext())
                pickupsCard(habits)
                doomCard(habits)
                val withData = DeviceTime.Device.values().filter { DeviceTime.days(requireContext(), it).isNotEmpty() }
                withData.forEach { deviceCard(it) }
                // Until a device's file has been found
                if (withData.isEmpty()) macSetupCard()
            }
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
        if (s.id == MoodStore.USAGE) return day.totalUsageMs?.let { it / HOUR_MS }
        return day.face(s.id)?.let { MoodStore.score(it, s.inverted).toFloat() }
    }

    /** The face [value] is nearest, or for a medicine [taken] at all, its nearest dose. */
    private fun faceFor(s: StatInfo, value: Float, taken: Boolean = false): Int {
        if (s.id == MoodStore.USAGE) return MoodStore.moodForTotalUsage((value * HOUR_MS).toLong())
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
        if (s.id == MoodStore.USAGE) return day.totalUsageMs?.let { requireContext().formattedTimeSpent(it) }
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

    // --- Sleep and Music, filled in by themselves ---

    private fun clock(time: java.time.LocalTime) = time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())).lowercase(Locale.getDefault())

    /** Minutes from midnight on the morning woken up: before it (going to bed) is negative. */
    private fun offset(time: java.time.LocalDateTime, date: LocalDate) = ChronoUnit.MINUTES.between(date.atStartOfDay(), time).toInt()

    private fun offsetClock(minutes: Int) = clock(java.time.LocalTime.MIDNIGHT.plusMinutes(Math.floorMod(minutes, 1440).toLong()))

    private fun duration(ms: Long) = requireContext().formattedTimeSpent(ms)

    private fun nightText(date: LocalDate, night: Sleep.Night) = listOfNotNull(
        dateText(date),
        getString(R.string.sleep_span, clock(night.bedtime.toLocalTime()), clock(night.wake.toLocalTime())),
        getString(R.string.sleep_asleep, duration(night.asleepMs)),
        night.checks.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.sleep_checks, it, it) },
    ).joinToString(" · ")

    /** Last night: how long, from when to when, and the usual of the last [SLEEP_DAYS] nights under it. */
    private fun sleepCard(nights: Map<LocalDate, Sleep.Night>) {
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
    private fun sleepTimesCard(nights: Map<LocalDate, Sleep.Night>) {
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
    private fun sleepMoodCard(nights: Map<LocalDate, Sleep.Night>) {
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

    /** Today's listening, and the artists of the last [MUSIC_DAYS] days with Mood on the days they played. */
    private fun musicCard() {
        val context = requireContext()
        val card = Card(getString(R.string.music_listening))
        if (!Spending.isEnabled(context)) {
            spendingShownOn = false
            card.showNote(getString(R.string.music_off))
            card.chipRow(listOf(getString(R.string.spending_turn_on) to ink), null) { Spending.openSettings(context) }
            return
        }
        spendingShownOn = true
        val days = Music.days(context)
        val today = LocalDate.now()
        val todays = days[today]
        card.subtitle(getString(R.string.last_days, MUSIC_DAYS))
        card.headline(duration(todays?.ms ?: 0), todays?.artists?.maxByOrNull { it.value }?.key
            ?.let { getString(R.string.music_today_top, it) } ?: getString(R.string.music_today))
        val recent = days.filterKeys { it > today.minusDays(MUSIC_DAYS.toLong()) }
        val artists = recent.values.flatMap { it.artists.entries }.groupBy({ it.key }, { it.value })
            .mapValues { (_, times) -> times.sum() }.entries.sortedByDescending { it.value }.take(MUSIC_ARTISTS)
        if (artists.isEmpty()) {
            card.showNote(getString(R.string.music_none))
            return
        }
        card.scene.isVisible = false
        val mood = stats.firstOrNull { it.id == MoodStore.MOOD }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        artists.forEach { (artist, ms) ->
            val playedOn = recent.filterValues { it.artists.containsKey(artist) }.keys
            val moodText = mood?.let { m -> playedOn.mapNotNull { valueOf(m, it) }.takeIf { it.size >= 2 }?.average()?.toFloat() }
                ?.let { getString(R.string.music_mood, decimal(it)) }
            list.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                updatePadding(top = 6.dpToPx(), bottom = 6.dpToPx())
                addView(card.textView(14f, 255).apply {
                    text = artist
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(card.textView(13f, 150).apply {
                    text = listOfNotNull(duration(ms), moodText).joinToString(" · ")
                    updatePadding(left = 10.dpToPx())
                })
            })
        }
        card.root.addView(list, card.root.indexOfChild(card.scene))
    }

    // --- Social: time out of the house, from home Wi-Fi ---

    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (_binding != null) render() }

    /** What the Social cards were drawn with, so they're redrawn on coming back from a permission */
    private fun socialState(): String {
        val context = requireContext()
        return listOf(Social.hasLocation(context), Social.hasBackgroundLocation(context), Social.homes(context).map { it?.ssid }).toString()
    }

    private val homeColors by lazy { listOf(0xFF81C784.toInt(), 0xFFFFB74D.toInt()) }

    private fun homeName(slot: Int) = Social.homes(requireContext()).getOrNull(slot)?.name ?: getString(R.string.social_home_n, slot + 1)

    private fun socialDayText(date: LocalDate, day: Social.Day) = listOfNotNull(
        dateText(date),
        getString(R.string.social_out, duration(day.outMs)),
        day.outings.takeIf { it.isNotEmpty() }?.let { o -> getString(R.string.social_left_at, o.joinToString(", ") { clock(it.startTime.toLocalTime()) }) },
        day.mainHome?.let { getString(R.string.social_at, homeName(it)) },
    ).joinToString(" · ")

    /** Three figures side by side under [caption] */
    private fun tiles(card: Card, caption: String, tiles: List<Pair<String, String>>) {
        card.draw { s ->
            s.text(caption, 0f, s.sp(12f), faint(140), s.sp(12f), Paint.Align.LEFT)
            val top = s.dp(22f)
            val gap = s.dp(8f)
            val width = (s.width - gap * (tiles.size - 1)) / tiles.size
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
     * The two homes' Wi-Fi, set from the Wi-Fi you're on, with where you are now and how many days of
     * the last [SOCIAL_DAYS] were mostly at each. Until location is allowed, a way to allow it.
     */
    private fun socialHomesCard(days: Map<LocalDate, Social.Day>) {
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
        val recent = days.filterKeys { it > today.minusDays(SOCIAL_DAYS.toLong()) }
        val mood = stats.firstOrNull { it.id == MoodStore.MOOD }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        homes.forEachIndexed { slot, home ->
            val dates = recent.filterValues { it.mainHome == slot }.keys
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
        card.root.addView(list, card.root.indexOfChild(card.scene))
        if (homes.any { it != null }) {
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

    /** Sets a home to the Wi-Fi you're on now, with a name, or takes it away. */
    private fun editHome(slot: Int, home: Social.Home?) {
        val context = requireContext()
        val ssid = Social.currentSsid(context)
        if (ssid == null && home == null) {
            AlertDialog.Builder(context).setMessage(R.string.social_connect_first).setPositiveButton(R.string.okay, null).show()
            return
        }
        val name = EditText(context).apply {
            setText(home?.name ?: ssid)
            setSingleLine()
            setSelection(text.length)
        }
        val box = android.widget.FrameLayout(context).apply {
            setPadding(24.dpToPx(), 8.dpToPx(), 24.dpToPx(), 0)
            addView(name)
        }
        AlertDialog.Builder(context)
            .setTitle(getString(R.string.social_home_n, slot + 1))
            .setMessage(if (ssid != null) getString(R.string.social_uses_wifi, ssid) else getString(R.string.social_keeps_wifi, home!!.ssid))
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val wifi = ssid ?: home!!.ssid
                Social.setHome(context, slot, Social.Home(wifi, name.text.toString().trim().ifEmpty { wifi }))
                if (_binding != null) render()
            }
            .apply {
                if (home != null) setNeutralButton(R.string.social_remove) { _, _ ->
                    Social.setHome(context, slot, null)
                    if (_binding != null) render()
                }
            }
            .show()
    }

    /** Today's time out, when you left, and the usual of the last [SOCIAL_DAYS] days under it. */
    private fun socialTodayCard(days: Map<LocalDate, Social.Day>) {
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
    private fun socialTimelineCard(days: Map<LocalDate, Social.Day>) {
        val today = LocalDate.now()
        val dates = (SOCIAL_DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }.filter { days.containsKey(it) }
        if (dates.isEmpty()) return
        val card = Card(getString(R.string.social_when_out))
        card.subtitle(getString(R.string.last_days, SOCIAL_DAYS))
        card.keyItem(stat.color, getString(R.string.social_key_out))
        Social.homes(requireContext()).forEachIndexed { slot, home -> if (home != null) card.keyItem(homeColors[slot], home.name) }
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
    private fun socialStayInCard(days: Map<LocalDate, Social.Day>) {
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
    private fun socialMoodCard(days: Map<LocalDate, Social.Day>) {
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

    // --- Screen time's pickups and doomscrolling, and Steps ---

    /**
     * A bar a day for the last [HABIT_DAYS] days, today on the right, in [color], with a dashed-looking
     * [goal] line if given. [label] is a bar's figure in words, for tapping.
     */
    private fun dayBars(card: Card, values: Map<LocalDate, Float>, color: Int, goal: Float? = null, label: (LocalDate, Float) -> String) {
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
    private fun pickupsCard(days: Map<LocalDate, PhoneHabits.Day>) {
        if (days.isEmpty()) return
        val card = Card(getString(R.string.pickups))
        val today = LocalDate.now()
        val usual = days.filterKeys { it < today && it >= today.minusDays(HABIT_DAYS.toLong()) }.values.map { it.pickups }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
        card.headline((days[today]?.pickups ?: 0).toString(),
            listOfNotNull(getString(R.string.pickups_today), usual?.let { getString(R.string.usually_n, it) }).joinToString(" · "))
        dayBars(card, days.mapValues { it.value.pickups.toFloat() }, stat.color) { _, value ->
            resources.getQuantityString(R.plurals.pickup_count, value.toInt(), value.toInt())
        }
    }

    /** Time in stretches of [PhoneHabits.DOOM_MINUTES]+ minutes in one feed app, each day. */
    private fun doomCard(days: Map<LocalDate, PhoneHabits.Day>) {
        if (days.isEmpty()) return
        val card = Card(getString(R.string.doomscrolling))
        card.subtitle(getString(R.string.doom_rule, PhoneHabits.DOOM_MINUTES))
        val today = LocalDate.now()
        val day = days[today]
        card.headline(duration(day?.doomMs ?: 0), listOfNotNull(
            getString(R.string.doom_today),
            day?.doomSessions?.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.doom_sessions, it, it) },
            day?.doomApp?.let { getString(R.string.doom_mostly, it) },
        ).joinToString(" · "))
        // Nothing to chart until there's been some
        if (days.values.none { it.doomMs > 0 }) return
        dayBars(card, days.mapValues { it.value.doomMs / HOUR_MS }, ContextCompat.getColor(requireContext(), R.color.holiday)) { date, _ ->
            val d = days.getValue(date)
            listOfNotNull(duration(d.doomMs), d.doomSessions.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.doom_sessions, it, it) },
                d.doomApp?.let { getString(R.string.doom_mostly, it) }).joinToString(" · ")
        }
    }

    private val askActivity = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) Steps.refresh(requireContext().applicationContext)
        if (_binding != null) render()
    }

    /** Today's steps against the usual, a bar a day with the 10,000 line, and the last [HABIT_DAYS] days' figures. */
    private fun stepsCard() {
        val context = requireContext()
        val card = Card(getString(R.string.steps_today))
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
        card.keyItem(faint(110), getString(R.string.steps_goal, format.format(Steps.GOAL)))
        dayBars(card, days.mapValues { it.value.toFloat() }, stat.color, Steps.GOAL.toFloat()) { _, value ->
            getString(R.string.steps_count, format.format(value.toInt()))
        }
        if (past.size >= 2) {
            val tilesCard = Card(getString(R.string.steps_usual_title))
            tiles(tilesCard, getString(R.string.social_usual, past.size), listOf(
                format.format(past.average().roundToInt()) to getString(R.string.steps_tile_average),
                format.format(past.max()) to getString(R.string.steps_tile_best),
                past.count { it >= Steps.GOAL }.toString() to getString(R.string.steps_tile_goal),
            ))
        }
    }

    /** Reads the Mac's and TV's latest files once on opening Screen time, and redraws if anything came in. */
    private fun readDevicesOnce() {
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

    /**
     * Screen time split between the phone, the Mac and the TV: today's, a stacked bar a day for the
     * last [HABIT_DAYS] days, and each device's share of them.
     */
    private fun deviceSplitCard() {
        val context = requireContext()
        val today = LocalDate.now()
        val days = (HABIT_DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }
        val devices = listOf(Triple("phone", getString(R.string.device_phone), stat.color)) +
            DeviceTime.Device.values().map { Triple(it.key, getString(it.label), it.color) }
        fun ms(date: LocalDate, key: String): Long = dayOf(date)?.let { if (key == "phone") it.usageMs ?: 0L else it.deviceMs[key] ?: 0L } ?: 0L
        // Only the devices with any time in these days
        val shown = devices.filter { (key, _, _) -> days.any { ms(it, key) > 0 } }
        if (shown.size < 2) return
        val card = Card(getString(R.string.device_split))
        val totals = shown.map { (key, _, _) -> days.sumOf { ms(it, key) } }
        val all = totals.sum().coerceAtLeast(1)
        shown.forEachIndexed { i, (_, name, color) -> card.keyItem(color, getString(R.string.device_share, name, (totals[i] * 100f / all).roundToInt())) }
        card.headline(duration(shown.sumOf { (key, _, _) -> ms(today, key) }),
            shown.filter { (key, _, _) -> ms(today, key) > 0 }.joinToString(" · ") { (key, name, _) -> "$name ${duration(ms(today, key))}" }
                .ifEmpty { getString(R.string.mac_not_today) })
        card.describe = { key ->
            val date = key as LocalDate
            (listOf(dateText(date)) + shown.map { (k, name, _) -> "$name ${duration(ms(date, k))}" }).joinToString(" · ")
        }
        val max = days.maxOf { date -> shown.sumOf { (key, _, _) -> ms(date, key) } }.coerceAtLeast(1).toFloat()
        card.draw { s ->
            val top = s.dp(6f)
            val bottom = top + s.dp(120f)
            val column = s.width / days.size
            s.line(0f, bottom, s.width, bottom, faint(30), s.dp(1f))
            val gap = minOf(s.dp(2f), column * 0.15f)
            days.forEachIndexed { i, date ->
                val x = i * column
                var y = bottom
                shown.forEach { (key, _, color) ->
                    val h = ms(date, key) / max * (bottom - top)
                    if (h > 0f) {
                        s.rect(x + gap, y - h, x + column - gap, y, color)
                        y -= h
                    }
                }
                if (y == bottom) s.rect(x + gap, bottom - s.dp(2f), x + column - gap, bottom, faint(45), s.dp(1f))
                s.target(date, RectF(x, 0f, x + column, bottom + s.dp(24f)), RectF(x, top - s.dp(2f), x + column, bottom + s.dp(2f)))
            }
            labelIndices(days.size, 2).forEach { i ->
                s.text(days[i].dayOfMonth.toString(), i * column + column / 2, bottom + s.dp(18f), faint(140), s.sp(12f))
            }
            s.height = bottom + s.dp(24f)
        }
    }

    // --- The Mac's and TV's screen time, from the files they write to Google Drive ---

    private val pickDeviceFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        val context = requireContext().applicationContext
        runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        DeviceTime.setFolder(context, uri)
        Thread {
            DeviceTime.refresh(context, force = true)
            Vibecoding.refresh(context, force = true)
            binding.root.post { if (_binding != null) render() }
        }.start()
    }

    /** On Screen time, until a device's file has been found: where to look for it. */
    private fun macSetupCard() {
        val card = Card(getString(R.string.mac_screen_time))
        card.showNote(getString(R.string.mac_setup))
        card.chipRow(listOf(getString(R.string.mac_choose_folder) to ink), null) { pickDeviceFolder.launch(null) }
    }

    /** Today on the Mac or TV, when it was first and last used, and its apps over the last [HABIT_DAYS] days. */
    private fun deviceCard(device: DeviceTime.Device) {
        val context = requireContext()
        val days = DeviceTime.days(context, device)
        val card = Card(getString(device.label))
        val updated = DeviceTime.updated(context, device)
        if (updated > 0) card.subtitle(getString(R.string.mac_updated, android.text.format.DateUtils.getRelativeTimeSpanString(updated, System.currentTimeMillis(), 60_000L)))
        val today = LocalDate.now()
        val day = days[today]
        card.headline(duration((day?.minutes ?: 0) * 60_000L), listOfNotNull(
            if (day?.first != null && day.last != null) getString(R.string.sleep_span, clock(java.time.LocalTime.parse(day.first)), clock(java.time.LocalTime.parse(day.last))) else getString(R.string.mac_not_today),
            day?.apps?.maxByOrNull { it.value }?.key?.let { getString(R.string.doom_mostly, it) },
        ).joinToString(" · "))
        val apps = days.filterKeys { it > today.minusDays(HABIT_DAYS.toLong()) }.values.flatMap { it.apps.entries }
            .groupBy({ it.key }, { it.value }).mapValues { (_, m) -> m.sum() }.entries.sortedByDescending { it.value }.take(MAC_APPS)
        // Its apps, under today's figure in the same card
        val appsCard = card
        appsCard.scene.isVisible = false
        if (apps.isEmpty()) return
        val most = apps.first().value.toFloat()
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        apps.forEach { (app, minutes) ->
            list.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                updatePadding(top = 6.dpToPx(), bottom = 6.dpToPx())
                addView(appsCard.textView(14f, 255).apply {
                    text = app
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                // A bar as long as its share of the most-used app's time
                addView(View(context).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = 3.dpToPx().toFloat()
                        setColor(device.color)
                    }
                }, LinearLayout.LayoutParams((60.dpToPx() * minutes / most).toInt().coerceAtLeast(3.dpToPx()), 6.dpToPx()).apply {
                    marginStart = 10.dpToPx()
                })
                addView(appsCard.textView(13f, 150).apply {
                    text = duration(minutes * 60_000L)
                    gravity = Gravity.END
                }, LinearLayout.LayoutParams(64.dpToPx(), ViewGroup.LayoutParams.WRAP_CONTENT))
            })
        }
        appsCard.root.addView(list, appsCard.root.indexOfChild(appsCard.scene))
    }

    // --- Vibecoding, from the files the PC and Mac write to Google Drive ---

    private fun vibeDayText(date: LocalDate, d: Vibecoding.Day) = listOfNotNull(
        dateText(date), duration(d.minutes * 60_000L),
        resources.getQuantityString(R.plurals.vibe_prompts, d.prompts, d.prompts),
        if (d.first != null && d.last != null) getString(R.string.sleep_span, clock(java.time.LocalTime.parse(d.first)), clock(java.time.LocalTime.parse(d.last))) else null,
        d.projects.maxByOrNull { it.value.minutes }?.key?.let { getString(R.string.doom_mostly, it) },
    ).joinToString(" · ")

    private fun money(dollars: Double) = String.format(Locale.getDefault(), "$%.2f", dollars)

    /** Today's time and prompts, a bar a day, and the usual of the last [HABIT_DAYS] days. */
    private fun vibeTodayCard(days: Map<LocalDate, Vibecoding.Day>) {
        val context = requireContext()
        val card = Card(getString(R.string.mac_today))
        val updated = Vibecoding.updated(context)
        if (updated > 0) card.subtitle(getString(R.string.mac_updated, android.text.format.DateUtils.getRelativeTimeSpanString(updated, System.currentTimeMillis(), 60_000L)))
        val today = LocalDate.now()
        val day = days[today]
        card.headline(duration((day?.minutes ?: 0) * 60_000L), listOfNotNull(
            getString(R.string.vibe_your_time),
            day?.prompts?.let { resources.getQuantityString(R.plurals.vibe_prompts, it, it) },
            day?.projects?.maxByOrNull { it.value.minutes }?.key?.let { getString(R.string.doom_mostly, it) },
        ).joinToString(" · "))
        dayBars(card, days.mapValues { it.value.minutes / 60f }, stat.color, 3f) { date, _ -> vibeDayText(date, days.getValue(date)) }
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

    /** Time and prompts per project over the last [VIBE_DAYS] days. */
    private fun vibeProjectsCard(days: Map<LocalDate, Vibecoding.Day>) {
        val context = requireContext()
        val today = LocalDate.now()
        val recent = days.filterKeys { it > today.minusDays(VIBE_DAYS.toLong()) }.values
        val projects = recent.flatMap { it.projects.entries }.groupBy({ it.key }, { it.value })
            .mapValues { (_, p) -> Vibecoding.Project(p.sumOf { it.minutes }, p.sumOf { it.prompts }) }
            .entries.sortedByDescending { it.value.minutes }.take(VIBE_PROJECTS)
        if (projects.isEmpty()) return
        val card = Card(getString(R.string.vibe_projects))
        card.subtitle(getString(R.string.last_days, VIBE_DAYS))
        card.scene.isVisible = false
        val most = projects.first().value.minutes.coerceAtLeast(1).toFloat()
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        projects.forEach { (name, project) ->
            list.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                updatePadding(top = 6.dpToPx(), bottom = 6.dpToPx())
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(card.textView(14f, 255).apply {
                        text = name
                        isSingleLine = true
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(card.textView(12f, 140).apply {
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
                addView(card.textView(13f, 150).apply {
                    text = duration(project.minutes * 60_000L)
                    gravity = Gravity.END
                }, LinearLayout.LayoutParams(64.dpToPx(), ViewGroup.LayoutParams.WRAP_CONTENT))
            })
        }
        card.root.addView(list, card.root.indexOfChild(card.scene))
    }

    /** Time by hour of the day over the last [VIBE_DAYS] days, late at night standing out. */
    private fun vibeHoursCard(days: Map<LocalDate, Vibecoding.Day>) {
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
    private fun vibeCostCard(days: Map<LocalDate, Vibecoding.Day>) {
        val today = LocalDate.now()
        val recent = days.filterKeys { it > today.minusDays(VIBE_DAYS.toLong()) }
        if (recent.values.sumOf { it.cost } <= 0.0) return
        val card = Card(getString(R.string.vibe_cost))
        card.subtitle(getString(R.string.vibe_cost_note))
        val tokens = recent.values.sumOf { it.tokens }
        card.headline(money(recent.values.sumOf { it.cost }), getString(R.string.vibe_cost_caption, VIBE_DAYS,
            java.text.NumberFormat.getIntegerInstance(Locale.getDefault()).format(tokens / 1_000_000)))
        dayBars(card, days.mapValues { it.value.cost.toFloat() }, stat.color) { date, _ ->
            val d = days.getValue(date)
            getString(R.string.vibe_cost_day, money(d.cost), java.text.NumberFormat.getIntegerInstance(Locale.getDefault()).format(d.tokens / 1_000_000))
        }
    }

    /** Mood by how long you vibecoded that day, and Sleep after nights you were still going late. */
    private fun vibeYouCard(days: Map<LocalDate, Vibecoding.Day>) {
        val mood = stats.firstOrNull { it.id == MoodStore.MOOD } ?: return
        val today = LocalDate.now()
        val past = days.filterKeys { it < today }
        val all = dates.filter { it < today && it >= (days.keys.minOrNull() ?: today) }
        if (all.count { valueOf(mood, it) != null } < SLEEP_MIN_NIGHTS) return
        val card = Card(getString(R.string.vibe_and_you))
        card.keyItem(mood.color, mood.name)
        val names = listOf(R.string.vibe_none, R.string.social_under_1, R.string.social_1_3, R.string.vibe_3_plus).map { getString(it) }
        fun bucket(date: LocalDate): Int {
            val minutes = past[date]?.minutes ?: 0
            return when {
                minutes == 0 -> 0
                minutes < 60 -> 1
                minutes < 180 -> 2
                else -> 3
            }
        }
        val averages = (0 until 4).map { b ->
            all.filter { bucket(it) == b }.mapNotNull { valueOf(mood, it) }
                .let { values -> values.takeIf { it.isNotEmpty() }?.average()?.toFloat() to values.size }
        }
        card.describe = { key ->
            val (average, count) = averages[key as Int]
            listOf(names[key], getString(R.string.stat_total, mood.name, average?.let(::decimal) ?: "–"),
                resources.getQuantityString(R.plurals.day_count, count, count)).joinToString(" · ")
        }
        // Still prompting after 11 pm (or past midnight), and the night's sleep that followed
        val nights = Sleep.nights(requireContext())
        val lateDates = past.filter { (_, d) -> d.last?.let { it >= "23:00" } == true || d.hours.take(5).sum() > 0 }.keys
        val lateSleep = lateDates.mapNotNull { nights[it.plusDays(1)]?.asleepMs }
        val otherSleep = nights.filterKeys { it.minusDays(1) !in lateDates && it.minusDays(1) >= (days.keys.minOrNull() ?: today) }.values.map { it.asleepMs }
        if (lateSleep.size >= 2 && otherSleep.size >= 2) {
            card.note.isVisible = true
            card.note.text = getString(R.string.vibe_late_sleep, lateSleep.size, duration(lateSleep.average().toLong()), duration(otherSleep.average().toLong()))
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
        // A holiday's days are the ones the missed doses affect, which for a next-day medicine are the days
        // after; it's named by the days the doses were missed, as on the calendar above
        val shift = if (stat.nextDay) 1L else 0L
        fun rangeText(h: Holiday) = if (h.length == 1) h.start.minusDays(shift).format(rangeFormat)
        else getString(R.string.graph_month_range, h.start.minusDays(shift).format(rangeFormat), h.end.minusDays(shift).format(rangeFormat))

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
            if (count - 1 - indices.last() <= step / 2 && indices.size > 1) indices.removeAt(indices.lastIndex)
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

        /** How many nights Sleep's cards cover, and how many with a Mood before comparing */
        private const val SLEEP_DAYS = 14
        private const val SLEEP_MIN_NIGHTS = 3

        /** How far back Music's artists go, and how many are listed */
        private const val MUSIC_DAYS = 30
        private const val MUSIC_ARTISTS = 6

        /** How many days pickups, doomscrolling and Steps show */
        private const val HABIT_DAYS = 14

        /** How far back Vibecoding's projects, hours and spend go, and how many projects are listed */
        private const val VIBE_DAYS = 30
        private const val VIBE_PROJECTS = 8

        /** How many apps Mac screen time lists */
        private const val MAC_APPS = 8

        /** How many days Social's cards cover, and its staying-in dots */
        private const val SOCIAL_DAYS = 14
        private const val SOCIAL_LONG_DAYS = 30

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
