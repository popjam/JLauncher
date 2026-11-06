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
import app.olauncher.helper.StatKind
import app.olauncher.helper.PhoneApps
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
class StatPageFragment : CardPageFragment() {

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

    override val cards: LinearLayout get() = binding.cards

    override fun colorOfLabel(label: String): Int? = stats.firstOrNull { it.name == label }?.color

    override fun iconOfLabel(label: String): String? = stats.firstOrNull { it.name == label }?.let { StatIcons.forStat(it) }

    /** The page's stat counted in something real (hours, dollars) rather than faces, by day; null for faces */
    private var real: Map<LocalDate, Float>? = null
    private var unit: Measure? = null

    private enum class Measure { HOURS, DOLLARS }

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

        renderHeader()
        real = realValues()
        unit = when (stat.kind) {
            StatKind.SCREEN, StatKind.VIBECODING, StatKind.MUSIC -> Measure.HOURS
            StatKind.MONEY -> Measure.DOLLARS.takeIf { real != null }
            else -> null
        }

        binding.cards.removeAllViews()
        spendingShownOn = null
        socialShownState = null
        val context = requireContext()
        // Each kind's own cards come first, then the ones every stat gets
        when (stat.kind) {
            StatKind.SCREEN -> {
                readDevicesOnce()
                devicePieCard()
                appsCard()
                pickupsCard(PhoneHabits.days(context))
                if (DeviceTime.Device.values().none { DeviceTime.days(context, it).isNotEmpty() }) macSetupCard()
                screenDailyCard()
            }
            StatKind.SLEEP -> {
                val nights = Sleep.nights(context)
                sleepCard(nights)
                sleepTimesCard(nights)
                sleepPhoneCard(nights)
                sleepMoodCard(nights)
                dailyCard()
            }
            StatKind.MONEY -> {
                spendingCard()
                weekLimitCard()
                moneyCalendarCard()
                moneyMonthCard()
                moneyCategoriesCard(essential = false)
                moneyShopsCard()
                moneyCategoriesCard(essential = true)
            }
            StatKind.COFFEE -> cupsCard()
            StatKind.EXERCISE -> {
                workoutsCard()
                stepsCard()
            }
            StatKind.MEDITATION -> {
                streakCard()
                dailyCard()
            }
            StatKind.MUSIC -> {
                musicCard()
                musicDaysCard()
            }
            StatKind.VIBECODING -> {
                val days = Vibecoding.days(context)
                vibeTodayCard(days)
                vibeProjectsCard(days)
                vibeHoursCard(days)
                vibeYouCard(days)
                vibeCostCard(days)
            }
            StatKind.SOCIAL -> {
                val days = Social.days(context)
                socialTodayCard(days)
                socialHomesCard(days)
                socialTimelineCard(days)
                socialStayInCard(days)
                socialMoodCard(days)
                dailyCard()
            }
            StatKind.LIBIDO -> {
                pornCard()
                dailyCard()
            }
            StatKind.MEDICINE -> takenCard()
            else -> dailyCard()
        }
        trendCard()
        overTimeCard()
        when {
            stat.kind == StatKind.MONEY || stat.kind == StatKind.MUSIC || unit != null -> {}
            stat.medicine -> doseCard()
            else -> howOftenCard()
        }
        if (!stat.medicine || unit != null || stat.kind == StatKind.EXERCISE) weekdayCard()
        // Set against the medicines, or on a medicine's page every stat set against it; not for things counted like Coffee
        if (stat.isMedicine || !stat.medicine) takenVsNotCard()
    }

    /** The stat's name and icon on a band of its colour, with back and Edit on it */
    private fun renderHeader() {
        val color = stat.themeColor
        val onColor = if (ColorUtils.calculateLuminance(color) > 0.45) 0xFF161616.toInt() else 0xFFFFFFFF.toInt()
        binding.header.background = GradientDrawable().apply {
            cornerRadius = 28.dpToPx().toFloat()
            setColor(color)
        }
        binding.statName.text = stat.name
        binding.statName.setTextColor(onColor)
        binding.statIcon.background = StatIcons.drawable(StatIcons.forStat(stat), onColor, 26.dpToPx())
        binding.back.setTextColor(onColor)
        binding.editStat.setTextColor(onColor)
        binding.editStat.background = GradientDrawable().apply {
            cornerRadius = 20.dpToPx().toFloat()
            setStroke(1.dpToPx(), ColorUtils.setAlphaComponent(onColor, 110))
        }
    }

    /**
     * The page's stat in real terms, where it has them: Screen time's hours on every device, Vibecoding's
     * and Music's hours, and Money's dollars since purchases have been read (days without any being $0).
     */
    private fun realValues(): Map<LocalDate, Float>? {
        val context = requireContext()
        return when (stat.kind) {
            StatKind.SCREEN -> data.days.entries.mapNotNull { (key, day) ->
                day.totalUsageMs?.let { ms -> runCatching { LocalDate.parse(key) }.getOrNull()?.let { it to ms / HOUR_MS } }
            }.toMap()
            StatKind.VIBECODING -> Vibecoding.days(context).mapValues { it.value.minutes / 60f }
            StatKind.MUSIC -> Music.days(context).mapValues { it.value.ms / HOUR_MS }.let { played ->
                // Days logged with nothing played count as none
                played + dates.filter { !played.containsKey(it) && dayOf(it)?.face(stat.id) != null }.associateWith { 0f }
            }
            StatKind.MONEY -> {
                val since = Spending.trackedSince(context) ?: return null
                val spent = Spending.dailyCents(context)
                val today = LocalDate.now()
                (0..ChronoUnit.DAYS.between(since, today)).associate { since.plusDays(it) to (spent[since.plusDays(it)] ?: 0L) / 100f }
            }
            else -> null
        }
    }

    // --- Values ---

    private val usage get() = stat.id == MoodStore.USAGE

    private fun dayOf(date: LocalDate) = data.days[date.toString()]

    /** [s]'s value on [date]: in real terms for the page's own stat if it has them ([real]), else [scoreOf]. */
    private fun valueOf(s: StatInfo, date: LocalDate): Float? {
        if (s.id == stat.id) real?.let { return it[date] }
        return scoreOf(s, date)
    }

    /** [s]'s face's score on [date], 1 to 5 (hours for Screen time) */
    private fun scoreOf(s: StatInfo, date: LocalDate): Float? {
        val day = dayOf(date) ?: return null
        if (s.id == MoodStore.USAGE) return day.totalUsageMs?.let { it / HOUR_MS }
        return day.face(s.id)?.let { MoodStore.score(it, s.inverted).toFloat() }
    }

    /** What [value] says in the page's unit: a time, an amount or a score */
    private fun valueText(value: Float): String = when (unit) {
        Measure.HOURS -> duration((value * HOUR_MS).toLong())
        Measure.DOLLARS -> moneyShort((value * 100).roundToInt().toLong())
        null -> decimal(value)
    }

    /** The top of an axis for values up to [highest]: whole hours (at least [minHours]), or a round amount of money */
    private fun axisMax(highest: Float?, minHours: Float = if (usage) 8f else 2f): Float = when (unit) {
        Measure.HOURS -> hoursMax(highest, minHours)
        Measure.DOLLARS -> listOf(10f, 20f, 50f, 100f, 200f, 300f, 500f, 1000f, 2000f, 5000f, 10_000f).firstOrNull { it >= (highest ?: 0f) } ?: (ceil((highest ?: 0f) / 1000f) * 1000f)
        null -> 5f
    }

    /** Lines across an axis up to [max], each with its label */
    private fun axisTicks(max: Float): List<Pair<Float, String>> = when (unit) {
        Measure.HOURS -> (0..max.toInt() step if (max <= 4f) 1 else 2).map { it.toFloat() to getString(R.string.hours_short, it) }
        Measure.DOLLARS -> (0..4).map { max * it / 4 }.map { it to "$" + it.roundToInt() }
        null -> listOf(1f, 3f, 5f).map { it to it.toInt().toString() }
    }

    /** The face [value] is nearest, or for a medicine [taken] at all, its nearest dose. */
    private fun faceFor(s: StatInfo, value: Float, taken: Boolean = false): Int {
        if (s.id == MoodStore.USAGE) return MoodStore.moodForTotalUsage((value * HOUR_MS).toLong())
        val faces = if (taken) s.faces.filter { !s.isBlank(it) } else s.faces
        return faces.minBy { abs(MoodStore.score(it, s.inverted) - value) }
    }

    /** Null for a medicine's blank face, left empty like on the calendar. */
    private fun colorFor(s: StatInfo, value: Float, taken: Boolean = false, faces: Boolean = false): Int? {
        // Real values other than Screen time's hours have no faces to match; they're the stat's own colour
        if (!faces && s.id == stat.id && unit != null && !usage) return if (value > 0f) s.themeColor else null
        return faceFor(s, value, taken).let { face -> if (s.isBlank(face)) null else s.faceColor(requireContext(), face) }
    }

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
        if (s.id == stat.id && unit != null && !usage) real?.get(date)?.let { return valueText(it) }
        val day = dayOf(date) ?: return null
        if (s.id == MoodStore.USAGE) return day.totalUsageMs?.let { requireContext().formattedTimeSpent(it) }
        return day.face(s.id)?.let { faceName(s, it) }
    }

    private fun averageText(s: StatInfo, average: Float): String = when {
        s.id == MoodStore.USAGE -> getString(R.string.a_day, requireContext().formattedTimeSpent((average * HOUR_MS).toLong()))
        s.id == stat.id && unit != null -> getString(R.string.a_day, valueText(average))
        else -> String.format(Locale.getDefault(), "%.1f", average)
    }

    /** [s]'s average over the days [keyOf] puts in each group, leaving out days it gives null. */
    private fun averages(s: StatInfo, keyOf: (LocalDate) -> Int?, value: (StatInfo, LocalDate) -> Float? = ::valueOf): Map<Int, Float> {
        val values = mutableMapOf<Int, MutableList<Float>>()
        dates.forEach { date ->
            val key = keyOf(date) ?: return@forEach
            val value = value(s, date) ?: return@forEach
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

    private val firstDate: LocalDate?
        get() = listOfNotNull(real?.filterValues { it > 0f }?.keys?.minOrNull(), dates.firstOrNull { valueOf(stat, it) != null }).minOrNull()

    /** An hours axis: at least [minHours], rounded up to an even number to fit [highest]. */
    private fun hoursMax(highest: Float?, minHours: Float = 8f) = maxOf(minHours, ceil((highest ?: 0f) / 2) * 2)

    private val mood get() = stats.first { it.id == MoodStore.MOOD }

    /** A medicine's line on the trend: between its light and dark purples. */
    private val medicineColor
        get() = ColorUtils.blendARGB(
            ContextCompat.getColor(requireContext(), R.color.medicineLight),
            ContextCompat.getColor(requireContext(), R.color.medicineDark), 0.4f
        )

    // --- Cards ---

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
            val meds = stats.filter { it.isMedicine && it.id != stat.id }
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
        card.keyItem(coffeeCup, getString(R.string.a_cup))
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
                        val color = if (k >= CUPS_HOT) hotCup else coffeeCup
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
     * total (leaving out essentials) and the latest [SPENDING_SHOWN]. Until notification access is on, a
     * way to turn it on.
     */
    private fun spendingCard() {
        val context = requireContext()
        val card = Card(getString(R.string.today))
        val on = Spending.isEnabled(context)
        spendingShownOn = on
        if (!on) {
            card.showNote(getString(R.string.spending_off))
            card.chipRow(listOf(getString(R.string.spending_turn_on) to ink), null) { Spending.openSettings(context) }
            return
        }
        val purchases = Spending.purchases(context)
        val today = LocalDate.now()
        val todays = purchases.filter { it.date == today }
        val spent = todays.filter { !it.essential }
        val essentials = todays.filter { it.essential }.sumOf { it.cents }
        card.headline(money(spent.sumOf { it.cents }), listOfNotNull(
            resources.getQuantityString(R.plurals.spending_today, spent.size, spent.size),
            essentials.takeIf { it > 0 }?.let { getString(R.string.plus_essentials, money(it)) },
        ).joinToString(" · "))
        if (purchases.isEmpty()) {
            card.showNote(getString(R.string.spending_none))
            return
        }
        val timeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        purchases.take(SPENDING_SHOWN).forEach { purchase ->
            val time = java.time.Instant.ofEpochMilli(purchase.time).atZone(java.time.ZoneId.systemDefault())
            val `when` = if (purchase.date == today) time.format(timeFormat).lowercase(Locale.getDefault()) else dateText(purchase.date)
            list.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                updatePadding(top = 7.dpToPx(), bottom = 7.dpToPx())
                alpha = if (purchase.essential) 0.6f else 1f
                addView(card.textView(15f, 255).apply {
                    text = money(purchase.cents)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(if (purchase.essential) faint(255) else moneyGold)
                }, LinearLayout.LayoutParams(84.dpToPx(), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(card.textView(14f, 235).apply {
                        text = purchase.merchant
                        isSingleLine = true
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(card.textView(12f, 140).apply {
                        text = listOfNotNull(purchase.category, getString(R.string.essential).takeIf { purchase.essential }).joinToString(" · ").ifEmpty { `when` }
                        isSingleLine = true
                        ellipsize = TextUtils.TruncateAt.END
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(card.textView(12f, 130).apply {
                    text = `when`
                    updatePadding(left = 10.dpToPx())
                })
            })
        }
        card.addBody(list)
    }

    /**
     * The weekly limit: what's left of it this week, and the weeks spent (leaving out essentials) as bars
     * against it, the ones over it in red. Without a limit, a way to set one.
     */
    private fun weekLimitCard() {
        val context = requireContext()
        val spent = real ?: return
        val card = Card(getString(R.string.week_limit))
        val limit = Spending.weekLimitCents(context)
        if (limit <= 0) {
            card.showNote(getString(R.string.week_limit_none))
            card.chipRow(listOf(getString(R.string.week_limit_set) to moneyGold), null) { askWeekLimit() }
            return
        }
        val today = LocalDate.now()
        val monday = today.with(DayOfWeek.MONDAY)
        val first = maxOf(spent.keys.minOrNull()?.with(DayOfWeek.MONDAY) ?: monday, monday.minusWeeks(LIMIT_WEEKS - 1L))
        val weeks = generateSequence(first) { it.plusWeeks(1) }.takeWhile { it <= monday }.toList()
        val daily = Spending.dailyCents(context)
        val weekCents = weeks.map { Spending.weekSpentCents(daily, it) }
        // With carry-over each week has its own allowance
        val allowances = weeks.map { Spending.allowanceCents(context, it, daily) }
        val carry = Spending.carryOver(context)
        val thisWeek = weekCents.last()
        val allowance = allowances.last()
        val over = LIMIT_OVER_COLOR
        fun dollars(cents: Long) = if (cents % 100 == 0L) "$" + String.format(Locale.getDefault(), "%,d", cents / 100) else money(cents)
        val carried = (allowance - limit).takeIf { carry && it != 0L }?.let {
            getString(if (it > 0) R.string.week_limit_carried else R.string.week_limit_debt, dollars(abs(it)))
        }
        if (thisWeek <= allowance) card.headline(money(allowance - thisWeek), listOfNotNull(getString(R.string.week_limit_left, dollars(allowance)), carried).joinToString(" · "))
        else card.headline(money(thisWeek - allowance), listOfNotNull(getString(R.string.week_limit_over, dollars(allowance)), carried).joinToString(" · "))
        val pastOver = weekCents.indices.count { it < weeks.lastIndex && weekCents[it] > allowances[it] }
        if (weeks.size > 1) card.subtitle(getString(R.string.week_limit_broken, pastOver, weeks.size - 1))
        card.keyItem(moneyGold, getString(R.string.week_limit_under))
        card.keyItem(over, getString(R.string.week_limit_over_key))
        card.chipRow(listOf(getString(R.string.week_limit_change, moneyShort(limit)) to moneyGold), null) { askWeekLimit() }
        card.describe = { key ->
            val i = key as Int
            val cents = weekCents[i]
            val allowed = allowances[i]
            listOfNotNull(getString(R.string.week_of, weeks[i].format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))),
                getString(R.string.stat_label, getString(R.string.spent), money(cents)),
                if (allowed != limit) getString(R.string.stat_label, getString(R.string.week_limit_allowed), money(allowed)) else null,
                if (cents > allowed) getString(R.string.stat_label, getString(R.string.week_limit_over_by), money(cents - allowed))
                else getString(R.string.stat_label, getString(R.string.week_limit_under_by), money(allowed - cents))).joinToString("\n")
        }
        val most = maxOf(weekCents.maxOrNull() ?: 0L, limit) * 1.1f
        card.draw { s ->
            val top = s.dp(14f)
            val bottom = top + s.dp(96f)
            val column = s.width / LIMIT_WEEKS
            val left = s.width - column * weeks.size
            s.line(0f, bottom, s.width, bottom, faint(30), s.dp(1f))
            weeks.forEachIndexed { i, start ->
                val x = left + i * column
                val cents = weekCents[i]
                val color = if (cents > allowances[i]) over else moneyGold
                val h = cents / most * (bottom - top)
                if (cents == 0L) s.rect(x + s.dp(3f), bottom - s.dp(2f), x + column - s.dp(3f), bottom, faint(45), s.dp(1f))
                else s.rect(x + s.dp(3f), bottom - h, x + column - s.dp(3f), bottom,
                    if (i == weeks.lastIndex) color else ColorUtils.setAlphaComponent(color, 170), s.dp(4f))
                s.target(i, RectF(x, 0f, x + column, bottom + s.dp(20f)), RectF(x + s.dp(1f), top - s.dp(2f), x + column - s.dp(1f), bottom + s.dp(2f)))
            }
            // The limit across them all
            val y = bottom - limit / most * (bottom - top)
            var x = 0f
            while (x < s.width) {
                s.line(x, y, minOf(x + s.dp(5f), s.width), y, faint(150), s.dp(1.5f))
                x += s.dp(9f)
            }
            s.text(moneyShort(limit), 0f, y - s.dp(5f), faint(170), s.sp(11f), Paint.Align.LEFT)
            labelIndices(weeks.size, 3).forEach { i ->
                s.text(weeks[i].format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())), left + i * column + column / 2, bottom + s.dp(16f), faint(140), s.sp(11f))
            }
            s.height = bottom + s.dp(22f)
        }
    }

    private fun askWeekLimit() {
        val context = requireContext()
        val current = Spending.weekLimitCents(context)
        val input = EditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "$"
            if (current > 0) setText(if (current % 100 == 0L) (current / 100).toString() else String.format(Locale.US, "%.2f", current / 100.0))
            setSelection(text.length)
        }
        val carry = android.widget.CheckBox(context).apply {
            setText(R.string.week_limit_carry)
            isChecked = Spending.carryOver(context)
        }
        val onHome = android.widget.CheckBox(context).apply {
            setText(R.string.week_limit_on_home)
            isChecked = Spending.showOnHome(context)
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dpToPx(), 8.dpToPx(), 24.dpToPx(), 0)
            addView(input)
            addView(carry)
            addView(onHome)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.week_limit_ask)
            .setView(box)
            .setPositiveButton(R.string.okay) { _, _ ->
                val dollars = input.text.toString().replace("$", "").replace(",", "").trim().toDoubleOrNull()
                if (dollars != null && dollars > 0) Spending.setWeekLimitCents(context, (dollars * 100).roundToInt().toLong())
                Spending.setCarryOver(context, carry.isChecked)
                Spending.setShowOnHome(context, onHome.isChecked)
                if (isAdded) render()
            }
            .apply { if (current > 0) setNeutralButton(R.string.week_limit_remove) { _, _ -> Spending.setWeekLimitCents(context, 0); if (isAdded) render() } }
            .setNegativeButton(R.string.close, null)
            .show()
            .window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        input.requestFocus()
    }

    private val moneyGold get() = StatKind.MONEY.shades!!.let { ColorUtils.blendARGB(it.first, it.second, 0.3f) }

    /** Dollars short enough for a day's square: "$4.50", "$45", "$1.2k" */
    private fun squareMoney(cents: Long): String = when {
        cents < 1000 -> String.format(Locale.getDefault(), "$%.2f", cents / 100.0)
        cents < 100_000 -> "$" + (cents + 50) / 100
        else -> String.format(Locale.getDefault(), "$%.1fk", cents / 100_000.0)
    }

    /**
     * The days as a calendar, each square gold as deep as what was spent (leaving out essentials), with
     * the amount on it. Days from before purchases were read show their face's colour and label instead.
     */
    private fun moneyCalendarCard() {
        val card = Card(getString(R.string.spent_each_day))
        val shades = StatKind.MONEY.shades!!
        fun draw() {
            val days = dailyDates(card, redraw = ::draw)
            val cents = days.associateWith { date -> real?.get(date)?.let { (it * 100).roundToInt().toLong() } }
            val most = cents.values.filterNotNull().maxOrNull()?.coerceAtLeast(1) ?: 1
            card.describe = { key ->
                val date = key as LocalDate
                val amount = cents[date]
                val purchases = Spending.purchases(requireContext()).filter { it.date == date }
                (listOf(dateText(date), amount?.let { getString(R.string.stat_label, getString(R.string.spent), money(it)) } ?: dayText(stat, date) ?: "–") +
                    purchases.take(4).map { getString(R.string.stat_label, it.merchant, money(it.cents)) }).joinToString("\n")
            }
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
                    val amount = cents[date]
                    val face = dayOf(date)?.face(stat.id)
                    val (fill, text) = when {
                        amount != null && amount > 0 -> ColorUtils.blendARGB(shades.first, shades.second, (amount.toFloat() / most).coerceIn(0f, 1f) * 0.85f) to squareMoney(amount)
                        amount != null -> null to "$0"
                        face != null && !stat.isBlank(face) -> stat.faceColor(requireContext(), face) to faceName(stat, face)
                        face != null -> null to "$0"
                        else -> null to null
                    }
                    if (fill != null) s.rect(x, y, x + size, y + size, fill, corner)
                    else if (text != null) s.outline(x, y, x + size, y + size, faint(80), corner, s.dp(1.5f))
                    val textColor = if (fill != null && ColorUtils.calculateLuminance(fill) > 0.4) 0xFF1A1A1A.toInt() else if (fill != null) 0xFFFFFFFF.toInt() else faint(150)
                    // A day with nothing logged is just its date, faint
                    if (text == null) s.text(date.dayOfMonth.toString(), x + size / 2, y + size / 2 + s.sp(4.5f), faint(70), s.sp(12.5f))
                    else s.text(date.dayOfMonth.toString(), x + s.dp(6f), y + s.sp(11f), ColorUtils.setAlphaComponent(textColor, 150), s.sp(9.5f), Paint.Align.LEFT)
                    text?.let {
                        val textSize = if (it.length > 5) s.sp(10f) else s.sp(12f)
                        s.text(it, x + size / 2, y + size * 0.68f, textColor, textSize, bold = fill != null)
                    }
                    s.target(date, RectF(x - gap, y - gap, x + size + gap, y + size + gap), RectF(x - gap / 2, y - gap / 2, x + size + gap / 2, y + size + gap / 2), corner)
                }
                val rows = (days.size + lead + 6) / 7
                s.height = gridTop + rows * cell + s.dp(2f)
            }
        }
        draw()
    }

    /** This month so far against last month by the same day, and the usual day */
    private fun moneyMonthCard() {
        val spent = real ?: return
        val today = LocalDate.now()
        val thisMonth = spent.filterKeys { YearMonth.from(it) == YearMonth.from(today) }
        val lastMonth = YearMonth.from(today).minusMonths(1)
        val lastBy = spent.filterKeys { YearMonth.from(it) == lastMonth && it.dayOfMonth <= today.dayOfMonth }
        val card = Card(getString(R.string.this_month))
        val tracked = lastBy.isNotEmpty()
        tiles(card, null, listOfNotNull(
            moneyShort((thisMonth.values.sum() * 100).roundToInt().toLong()) to getString(R.string.so_far),
            if (tracked) moneyShort((lastBy.values.sum() * 100).roundToInt().toLong()) to getString(R.string.last_month_by_now) else null,
            moneyShort((spent.filterKeys { it > today.minusDays(30) }.values.average() * 100).roundToInt().toLong()) to getString(R.string.a_usual_day),
        ))
    }

    /** What went on each category (or on essentials alone) over the last 30 days */
    private fun moneyCategoriesCard(essential: Boolean) {
        val context = requireContext()
        val since = LocalDate.now().minusDays(RECENT_DAYS.toLong())
        val purchases = Spending.purchases(context).filter { it.date > since && it.essential == essential }
        if (purchases.isEmpty()) return
        val card = Card(getString(if (essential) R.string.essentials else R.string.where_it_went))
        card.subtitle(getString(R.string.last_days, RECENT_DAYS))
        val groups = purchases.groupBy { it.category ?: getString(R.string.other) }.mapValues { (_, list) -> list.sumOf { it.cents } }
            .entries.sortedByDescending { it.value }.take(6)
        if (essential) card.headline(money(purchases.sumOf { it.cents }), getString(R.string.essentials_note))
        val color = if (essential) faint(150) else moneyGold
        card.describe = { key ->
            val (name, cents) = groups[key as Int]
            val count = purchases.count { (it.category ?: getString(R.string.other)) == name }
            listOf(name, getString(R.string.stat_label, getString(R.string.spent), money(cents)),
                getString(R.string.stat_label, getString(R.string.purchases), count.toString())).joinToString("\n")
        }
        horizontalBars(card, groups.map { (name, cents) -> Triple(name, (cents / 100).toInt().coerceAtLeast(1), color) }) { "$$it" }
    }

    /** The shops spent at most over the last 30 days, leaving out essentials */
    private fun moneyShopsCard() {
        val since = LocalDate.now().minusDays(RECENT_DAYS.toLong())
        val purchases = Spending.purchases(requireContext()).filter { it.date > since && !it.essential }
        if (purchases.size < 2) return
        val card = Card(getString(R.string.top_shops))
        card.subtitle(getString(R.string.last_days, RECENT_DAYS))
        val shops = purchases.groupBy { it.merchant }.entries.sortedByDescending { (_, list) -> list.sumOf { it.cents } }.take(5)
        card.describe = { key ->
            val (shop, list) = shops[key as Int]
            listOf(shop, getString(R.string.stat_label, getString(R.string.spent), money(list.sumOf { it.cents })),
                getString(R.string.stat_label, getString(R.string.purchases), list.size.toString()),
                getString(R.string.stat_label, getString(R.string.last_time), dateText(list.maxOf { it.date }))).joinToString("\n")
        }
        horizontalBars(card, shops.map { (shop, list) -> Triple(shop, (list.sumOf { it.cents } / 100).toInt().coerceAtLeast(1), moneyGold) }) { "$$it" }
    }

    // --- Exercise, Meditation and Music ---

    /** Exercise's days as a calendar of dumbbells, deeper the harder the day; a faint outline for a rest day */
    private fun workoutsCard() {
        val card = Card(getString(R.string.workouts))
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
                        s.icon(StatIcons.DUMBBELL, x + (size - icon) / 2, y + (size - icon) / 2 - s.dp(3f), icon, stat.faceColor(requireContext(), face!!))
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
    private fun isRest(face: Int) = if (stat.medicine) stat.isBlank(face) else MoodStore.score(face, stat.inverted) <= 1

    /** Days in a row with some, the best run, and the last [STREAK_DAYS] days as dots */
    private fun streakCard() {
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

    /**
     * Each day's listening as a level meter, a block per [MUSIC_BLOCK_MINUTES] minutes, lighter at the
     * bottom and deeper up, the last [HABIT_DAYS] days.
     */
    private fun musicDaysCard() {
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

    // --- Sleep and Music, filled in by themselves ---

    /** Minutes from midnight on the morning woken up: before it (going to bed) is negative. */
    private fun offset(time: java.time.LocalDateTime, date: LocalDate) = ChronoUnit.MINUTES.between(date.atStartOfDay(), time).toInt()

    private fun offsetClock(minutes: Int) = clock(java.time.LocalTime.MIDNIGHT.plusMinutes(Math.floorMod(minutes, 1440).toLong()))

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
        val card = Card(getString(R.string.phone_pickups), icon = StatIcons.PHONE, iconColor = faint(200))
        val today = LocalDate.now()
        val usual = days.filterKeys { it < today && it >= today.minusDays(HABIT_DAYS.toLong()) }.values.map { it.pickups }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
        card.headline((days[today]?.pickups ?: 0).toString(),
            listOfNotNull(getString(R.string.pickups_today), usual?.let { getString(R.string.usually_n, it) }).joinToString(" · "))
        dayBars(card, days.mapValues { it.value.pickups.toFloat() }, stat.color) { _, value ->
            resources.getQuantityString(R.plurals.pickup_count, value.toInt(), value.toInt())
        }
    }

    private val askActivity = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) Steps.refresh(requireContext().applicationContext)
        if (_binding != null) render()
    }

    /** Today's steps against the usual, a bar a day with the 10,000 line, and the last [HABIT_DAYS] days' figures. */
    private fun stepsCard() {
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
        card.keyItem(faint(110), getString(R.string.steps_goal, format.format(Steps.GOAL)))
        dayBars(card, days.mapValues { it.value.toFloat() }, stat.themeColor, Steps.GOAL.toFloat()) { _, value ->
            getString(R.string.steps_count, format.format(value.toInt()))
        }
        if (past.size >= 2) {
            val tilesCard = Card(getString(R.string.steps_usual_heading))
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

    // --- Screen time across the phone, the Mac and the TV ---

    /** A device screen time comes from: its key in [MoodStore.Day.deviceMs] ("phone" for the phone's own), name and icon */
    private class Source(val key: String, val name: String, val icon: String, val color: Int)

    /** The phone, then the Mac and the TV, in shades of Screen time's colour so they read as one */
    private val sources: List<Source>
        get() = listOf(
            Source(PHONE, getString(R.string.device_phone), StatIcons.PHONE, stat.color),
            Source(DeviceTime.Device.MAC.key, getString(R.string.device_mac), StatIcons.LAPTOP, ColorUtils.blendARGB(stat.color, 0xFFFFFFFF.toInt(), 0.45f)),
            Source(DeviceTime.Device.TV.key, getString(R.string.device_tv), StatIcons.TV, ColorUtils.blendARGB(stat.color, 0xFF000000.toInt(), 0.4f)),
        )

    private fun sourceMs(date: LocalDate, key: String): Long =
        dayOf(date)?.let { if (key == PHONE) it.usageMs ?: 0L else it.deviceMs[key] ?: 0L } ?: 0L

    private fun screenDayText(date: LocalDate) = (listOf(dateText(date), getString(R.string.stat_label, getString(R.string.total), duration(sources.sumOf { sourceMs(date, it.key) })))
        + sources.filter { sourceMs(date, it.key) > 0 }.map { getString(R.string.stat_label, it.name, duration(sourceMs(date, it.key))) }).joinToString("\n")

    /**
     * A bar a day of all screen time, stacked phone, Mac and TV in shades of one colour, against an hours
     * axis. Tapping one gives the day's total and how it split.
     */
    private fun screenDailyCard() {
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
    private fun devicePieCard() {
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
    private fun appsCard() {
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
    private fun pornCard() {
        val days = DeviceTime.days(requireContext(), DeviceTime.Device.MAC)
        val minutes = days.mapValues { (_, day) -> day.apps.entries.filter { it.key.contains("VLC", ignoreCase = true) }.sumOf { it.value } }
        val card = Card(getString(R.string.porn_usage))
        if (days.isEmpty()) return card.showNote(getString(R.string.porn_needs_mac))
        val today = LocalDate.now()
        val watched = { date: LocalDate -> (minutes[date] ?: 0) >= PORN_MIN_MINUTES }
        val tracked = { date: LocalDate -> date >= (days.keys.minOrNull() ?: today) }
        val week = (0..6).map { today.with(DayOfWeek.MONDAY).plusDays(it.toLong()) }.filter { it <= today }
        val color = stat.themeColor
        val monday = today.with(DayOfWeek.MONDAY)
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
            var day = DayOfWeek.MONDAY
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

    // --- Sleep: the phone late at night and first thing ---

    /**
     * The phone's time from 11 pm until falling asleep, and in the first hour after waking, the last
     * [SLEEP_DAYS] nights: the usual of each, and a dot a night sized by the minutes.
     */
    private fun sleepPhoneCard(nights: Map<LocalDate, Sleep.Night>) {
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

    private val coffeeCup get() = StatKind.COFFEE.shades!!.let { ColorUtils.blendARGB(it.first, it.second, 0.35f) }
    private val hotCup get() = StatKind.COFFEE.shades!!.second

    /**
     * The average by week (the last 13), month (the last 12) or year, from the stat's first: real values
     * (Money's as each period's total) where it has them. The point still going is a ring.
     */
    private fun trendCard() {
        val card = Card(getString(R.string.stat_trend))
        val color = if (stat.isMedicine) medicineColor else stat.themeColor
        val totals = stat.kind == StatKind.MONEY && unit == Measure.DOLLARS
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
            val grouped = real.orEmpty().entries.mapNotNull { (date, v) -> keyOf(date)?.let { it to v } }.groupBy({ it.first }, { it.second })
            val values: Map<Int, Float> = when {
                totals -> grouped.mapValues { (_, list) -> list.sum() }
                unit != null -> grouped.mapValues { (_, list) -> list.average().toFloat() }
                else -> averages(stat, keyOf)
            }
            val taken = if (stat.medicine) takenCounts(keyOf) else emptyMap()
            val max = if (unit != null) axisMax(values.values.maxOrNull()) else 5f
            val low = if (unit != null) 0f else 1f
            fun fraction(value: Float) = (value - low) / (max - low)
            val ticks: List<Pair<Float, String>> = when {
                unit != null -> axisTicks(max).map { (v, label) -> fraction(v) to label }
                stat.labels.isNotEmpty() -> stat.faces.mapIndexedNotNull { i, face ->
                    stat.labels[i].takeIf { it.isNotEmpty() }?.let { fraction(MoodStore.score(face, stat.inverted).toFloat()) to it }
                }
                else -> listOf(1, 3, 5).map { fraction(it.toFloat()) to it.toString() }
            }
            card.describe = { key ->
                val point = key as Int
                val periodStart = periodStart(period, point)
                val title = when (period) {
                    Period.WEEKS -> getString(R.string.week_number, periodStart.get(WeekFields.ISO.weekOfWeekBasedYear()))
                    Period.MONTHS -> periodStart.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
                    Period.YEARS -> point.toString()
                }
                val parts = mutableListOf(if (point == last) getString(R.string.graph_so_far, title) else title)
                when {
                    totals -> values[point]?.let { parts.add(getString(R.string.stat_label, getString(R.string.spent), valueText(it))) }
                    stat.medicine && unit == null -> taken[point]?.let { (yes, all) -> parts.add(getString(R.string.stat_label, getString(R.string.taken), getString(R.string.n_of_days, yes, all))) }
                    else -> values[point]?.let { parts.add(getString(R.string.stat_label, getString(R.string.average), averageText(stat, it))) }
                }
                parts.joinToString("\n")
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
                val points = (first..last).map { p -> values[p]?.let { PointF(x(p), y(fraction(it))) } }
                // A soft fill under the line, in the stat's colour
                points.filterNotNull().takeIf { it.size > 1 }?.let { s.area(it, bottom, ColorUtils.setAlphaComponent(color, 70)) }
                s.polyline(points, color, s.dp(3f))
                values.forEach { (p, value) ->
                    if (p == last) s.ring(x(p), y(fraction(value)), s.dp(5.5f), color, s.dp(2.5f))
                    else s.circle(x(p), y(fraction(value)), s.dp(4f), color)
                }
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
            val filter = overTimeFilter ?: return scoreOf(stat, date)?.let { colorFor(stat, it, faces = true) } ?: faint(30)
            return if (dayOf(date)?.face(stat.id) == filter) filterColor(filter) else faint(30)
        }
        fun describeDay(date: LocalDate) = listOf(dateText(date), dayText(stat, date) ?: "–").joinToString(" · ")
        fun draw() {
            val span = Span.entries[overTimeSpan]
            // Days with only real values (spending read before Money had a face that day, say) have no face to show
            val start = (dates.firstOrNull { scoreOf(stat, it) != null } ?: firstDate) ?: return card.showNote(getString(R.string.no_data))
            card.segment(listOf(R.string.over_3_months, R.string.over_year, R.string.over_all), overTimeSpan) {
                overTimeSpan = it
                draw()
            }
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
                    val values = averages(stat, keyOf, ::scoreOf)
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
                            else -> values[month]?.let { decimal(it) }
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
                                    stat.medicine -> if ((taken[month]?.first ?: 0) > 0) colorFor(stat, value, taken = true, faces = true) ?: faint(30) else faint(30)
                                    else -> colorFor(stat, value, faces = true) ?: faint(30)
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

    private fun recentDays(): List<LocalDate> = LocalDate.now().let { today -> (0L until RECENT_DAYS).map { today.minusDays(it) } }

    /** The average of every Monday, every Tuesday and so on. */
    private fun weekdayCard() {
        val card = Card(getString(R.string.stat_by_weekday))
        card.subtitle(getString(when (unit) {
            Measure.HOURS -> R.string.average_hours
            Measure.DOLLARS -> R.string.average_spent
            null -> R.string.averages
        }))
        val byDay = DayOfWeek.values().associateWith { mutableListOf<Float>() }
        dates.forEach { date -> valueOf(stat, date)?.let { byDay.getValue(date.dayOfWeek).add(it) } }
        if (byDay.values.sumOf { it.size } < MIN_WEEKDAY_DAYS) return card.showNote(getString(R.string.needs_more_days))
        val averages = DayOfWeek.values().map { day -> byDay.getValue(day).takeIf { it.isNotEmpty() }?.average()?.toFloat() }
        val max = axisMax(averages.filterNotNull().maxOrNull())
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
                    val label = when (unit) {
                        Measure.DOLLARS -> "$" + average.roundToInt()
                        Measure.HOURS -> String.format(Locale.getDefault(), "%.1fh", average)
                        null -> String.format(Locale.getDefault(), "%.1f", average)
                    }
                    s.text(label, x + barWidth / 2, barTop - s.dp(6f), faint(220), s.sp(11.5f))
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
        val pairs = if (stat.isMedicine) stats.filter { !it.medicine && it.id != MoodStore.USAGE }.map { it to stat }
        else stats.filter { it.isMedicine && it.id != stat.id }.map { stat to it }
        if (pairs.isEmpty()) return
        val card = Card(getString(R.string.stat_taken_vs_not))
        if (unit == Measure.HOURS) card.subtitle(getString(R.string.hours))
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
            val other = if (stat.isMedicine) measured else med
            Row(other.name, other.color, taken.average().toFloat(), taken.size, notTaken.average().toFloat(), notTaken.size)
        }.sortedByDescending { abs(it.taken - it.notTaken) }
        if (rows.isEmpty()) return card.showNote(getString(R.string.needs_taken_and_not))

        val measuredStat = if (stat.isMedicine) null else stat
        card.describe = { key ->
            val row = rows[key as Int]
            val text = { value: Float -> measuredStat?.let { averageText(it, value) } ?: String.format(Locale.getDefault(), "%.1f", value) }
            getString(R.string.taken_vs_not_detail, row.name, text(row.taken), row.takenDays, text(row.notTaken), row.notDays)
        }
        val low = if (unit != null) 0f else 1f
        val high = if (unit != null) axisMax(rows.maxOf { maxOf(it.taken, it.notTaken) }) else 5f
        val ticks = if (unit != null) axisTicks(high).map { it.first.roundToInt() } else (1..5).toList()
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

    companion object {
        private const val DAILY_DAYS = 18
        private const val AVERAGE_DAYS = 7L
        private const val RECENT_DAYS = 30
        private const val MIN_TAKEN_DAYS = 3
        private const val MIN_WEEKDAY_DAYS = 14
        private const val HOUR_MS = 3_600_000f

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

        /** How many apps Screen time lists */
        private const val APPS_SHOWN = 10
        private val NOT_APPS = setOf("Unknown", "Home screen", "Pixel Launcher", "JLauncher", "Permission controller", "System UI",
            "Android System", "Settings", "Quickstep", "One UI Home", "Finder", "loginwindow", "Dock")

        /** How many days Social's cards cover, and its staying-in dots */
        private const val SOCIAL_DAYS = 14
        private const val SOCIAL_LONG_DAYS = 30

        /** How many of the latest purchases Money spent lists */
        private const val SPENDING_SHOWN = 3

        /** How many days Meditation's streak dots cover */
        private const val STREAK_DAYS = 30

        /** Minutes in each block of Music's level meters */
        private const val MUSIC_BLOCK_MINUTES = 10L
        private const val CUP_DAYS = 10

        /** How many cups before the rest stand out */
        private const val CUPS_HOT = 3
        /** Porn: how many weeks of bars and of days, and how long in VLC counts */
        private const val PORN_WEEKS = 10
        private const val LIMIT_WEEKS = 10
        private const val LIMIT_OVER_COLOR = 0xFFE5736F.toInt()
        private const val PORN_GRID_WEEKS = 5
        private const val PORN_MIN_MINUTES = 2

        /** Late-night phone use worth counting */
        private const val SLEEP_LATE_MS = 30 * 60_000L

        private const val PHONE = "phone"

        /** The Trend and Over time cards' choices, kept while the app runs */
        private var appsSpan = 0
        private var trendPeriod = 0
        private var overTimeSpan = 1
    }
}
