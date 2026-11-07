package app.olauncher.ui

import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.databinding.FragmentStatPageBinding
import app.olauncher.helper.Music
import app.olauncher.helper.DeviceTime
import app.olauncher.helper.PhoneHabits
import app.olauncher.helper.Sleep
import app.olauncher.helper.Steps
import app.olauncher.helper.Vibecoding
import app.olauncher.helper.Social
import app.olauncher.helper.Spending
import app.olauncher.helper.Tracking
import app.olauncher.helper.StatInfo
import app.olauncher.helper.StatKind
import app.olauncher.helper.allStats
import app.olauncher.helper.dpToPx
import app.olauncher.helper.formattedTimeSpent
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

    internal var _binding: FragmentStatPageBinding? = null
    internal val binding get() = _binding!!

    internal lateinit var moodStore: MoodStore
    internal var data = MoodStore.Data()
    internal var stats = emptyList<StatInfo>()
    internal lateinit var stat: StatInfo

    /** Every day with something saved, oldest first */
    internal var dates = emptyList<LocalDate>()

    /** How many stretches of its days back from today the Daily, Taken or Cups card is */
    internal var stretchesBack = 0

    /** The face Over time is showing only the days of, picked from the chips under it */
    internal var overTimeFilter: Int? = null

    override val cards: LinearLayout get() = binding.cards

    override fun colorOfLabel(label: String): Int? = stats.firstOrNull { it.name == label }?.color

    override fun iconOfLabel(label: String): String? = stats.firstOrNull { it.name == label }?.let { StatIcons.forStat(it) }

    /** The page's stat counted in something real (hours, dollars) rather than faces, by day; null for faces */
    internal var real: Map<LocalDate, Float>? = null
    internal var unit: Measure? = null

    internal enum class Measure { HOURS, DOLLARS }

    internal enum class Period { WEEKS, MONTHS, YEARS }
    internal enum class Span { MONTHS_3, YEAR, ALL }

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

    internal val choices get() = trendPeriod to overTimeSpan
    internal var renderedWith: Pair<Int, Int>? = null

    /** Whether Screen time's page has read the Mac's and TV's files since it opened */
    internal var devicesRead = false

    /** Whether notification access was on when the Money spent card was drawn; null without the card */
    internal var spendingShownOn: Boolean? = null

    /** Location access and homes when the Social cards were drawn; null without them */
    internal var socialShownState: String? = null

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    internal fun render() {
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
        trackingNotice()
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
                workoutsCard(R.string.workouts, StatIcons.DUMBBELL)
                stepsCard()
            }
            StatKind.MEDITATION -> {
                streakCard()
                dailyCard()
            }
            StatKind.MUSIC -> if (musicCard()) {
                musicDaysCard()
                musicHoursCard()
                musicArtistsCard()
                musicTracksCard()
                musicNewCard()
                musicMoodCard()
                musicAppsCard()
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
                socialPlacesCard(days)
                socialSpotsCard(days)
                socialTimelineCard(days)
                socialStayInCard(days)
                socialMoodCard(days)
                dailyCard()
            }
            StatKind.WEATHER -> {
                val days = app.olauncher.helper.WeatherHistory.days(context)
                weatherTodayCard(days)
                weatherMoodCard(days)
                daylightMoodCard(days)
                dailyCard()
            }
            StatKind.PEOPLE -> {
                val days = app.olauncher.helper.People.days(context)
                peopleTodayCard(days)
                peopleMoodCard(days)
                dailyCard()
            }
            StatKind.LIBIDO -> {
                pornCard()
                dailyCard()
            }
            StatKind.MEDICINE -> takenCard()
            StatKind.WORK -> {
                workCard()
                workoutsCard(R.string.work_days, StatIcons.BRIEFCASE)
            }
            else -> dailyCard()
        }
        // The cards every stat has, over time, follow once the page is showing, so it opens straight away
        val generation = ++renderGeneration
        binding.cards.post {
            if (_binding == null || generation != renderGeneration) return@post
            trendCard()
            overTimeCard()
            when {
                stat.kind == StatKind.MONEY || stat.kind == StatKind.MUSIC || stat.kind == StatKind.WORK || unit != null -> {}
                stat.medicine -> doseCard()
                else -> howOftenCard()
            }
            if (!stat.medicine || unit != null || stat.kind == StatKind.EXERCISE) weekdayCard()
            // Set against the medicines, or on a medicine's page every stat set against it; not for things counted like Coffee
            if (stat.isMedicine || !stat.medicine) takenVsNotCard()
        }
    }

    /** Each render's number, so the later cards of an older one aren't added */
    internal var renderGeneration = 0

    /** At the top, when something that fills this stat in has stopped: what, since when, and a fix */
    internal fun trackingNotice() {
        val context = requireContext()
        val problems = Tracking.problemsFor(context, stat.kind)
        if (problems.isEmpty()) return
        val card = Card(getString(R.string.tracking_paused), icon = StatIcons.WARNING, iconColor = 0xFFFFB300.toInt())
        card.showNote(problems.joinToString("\n") { problem ->
            problem.title + ": " + problem.detail + (problem.since?.let { " · " + getString(R.string.tracking_since, dateText(java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate())) } ?: "")
        })
        card.note.gravity = android.view.Gravity.START
        val fixable = problems.firstOrNull { it.fix != null }
        if (fixable != null) card.chipRow(listOf(getString(R.string.tracking_fix) to 0xFFFFB300.toInt()), null) { fixable.fix?.invoke(context) }
    }

    /** The stat's name and icon on a band of its colour, with back and Edit on it */
    internal fun renderHeader() {
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
    internal fun realValues(): Map<LocalDate, Float>? {
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

    internal val usage get() = stat.id == MoodStore.USAGE

    internal fun dayOf(date: LocalDate) = data.days[date.toString()]

    /** [s]'s value on [date]: in real terms for the page's own stat if it has them ([real]), else [scoreOf]. */
    internal fun valueOf(s: StatInfo, date: LocalDate): Float? {
        if (s.id == stat.id) real?.let { return it[date] }
        return scoreOf(s, date)
    }

    /** [s]'s face's score on [date], 1 to 5 (hours for Screen time) */
    internal fun scoreOf(s: StatInfo, date: LocalDate): Float? {
        val day = dayOf(date) ?: return null
        if (s.id == MoodStore.USAGE) return day.totalUsageMs?.let { it / HOUR_MS }
        return day.face(s.id)?.let { MoodStore.score(it, s.inverted).toFloat() }
    }

    /** What [value] says in the page's unit: a time, an amount or a score */
    internal fun valueText(value: Float): String = when (unit) {
        Measure.HOURS -> duration((value * HOUR_MS).toLong())
        Measure.DOLLARS -> moneyShort((value * 100).roundToInt().toLong())
        null -> decimal(value)
    }

    /** The top of an axis for values up to [highest]: whole hours (at least [minHours]), or a round amount of money */
    internal fun axisMax(highest: Float?, minHours: Float = if (usage) 8f else 2f): Float = when (unit) {
        Measure.HOURS -> hoursMax(highest, minHours)
        Measure.DOLLARS -> listOf(10f, 20f, 50f, 100f, 200f, 300f, 500f, 1000f, 2000f, 5000f, 10_000f).firstOrNull { it >= (highest ?: 0f) } ?: (ceil((highest ?: 0f) / 1000f) * 1000f)
        null -> 5f
    }

    /** Lines across an axis up to [max], each with its label */
    internal fun axisTicks(max: Float): List<Pair<Float, String>> = when (unit) {
        Measure.HOURS -> (0..max.toInt() step if (max <= 4f) 1 else 2).map { it.toFloat() to getString(R.string.hours_short, it) }
        Measure.DOLLARS -> (0..4).map { max * it / 4 }.map { it to "$" + it.roundToInt() }
        null -> listOf(1f, 3f, 5f).map { it to it.toInt().toString() }
    }

    /** The face [value] is nearest, or for a medicine [taken] at all, its nearest dose. */
    internal fun faceFor(s: StatInfo, value: Float, taken: Boolean = false): Int {
        if (s.id == MoodStore.USAGE) return MoodStore.moodForTotalUsage((value * HOUR_MS).toLong())
        val faces = if (taken) s.faces.filter { !s.isBlank(it) } else s.faces
        return faces.minBy { abs(MoodStore.score(it, s.inverted) - value) }
    }

    /** Null for a medicine's blank face, left empty like on the calendar. */
    internal fun colorFor(s: StatInfo, value: Float, taken: Boolean = false, faces: Boolean = false): Int? {
        // Real values other than Screen time's hours have no faces to match; they're the stat's own colour
        if (!faces && s.id == stat.id && unit != null && !usage) return if (value > 0f) s.themeColor else null
        return faceFor(s, value, taken).let { face -> if (s.isBlank(face)) null else s.faceColor(requireContext(), face) }
    }

    /** The medicine's dose counted towards [date]: the day before's when it affects the next day. */
    internal fun doseFor(med: StatInfo, date: LocalDate): Int? =
        dayOf(if (med.nextDay) date.minusDays(1) else date)?.face(med.id)

    internal fun takenOn(med: StatInfo, date: LocalDate): Boolean? = doseFor(med, date)?.let { !med.isBlank(it) }

    /** A face's label, or failing that a name for it: Mood's word, a medicine's dose, or a plain score, 1 to 5. */
    internal fun faceName(s: StatInfo, face: Int): String {
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
    internal fun dayText(s: StatInfo, date: LocalDate): String? {
        if (s.id == stat.id && unit != null && !usage) real?.get(date)?.let { return valueText(it) }
        val day = dayOf(date) ?: return null
        if (s.id == MoodStore.USAGE) return day.totalUsageMs?.let { requireContext().formattedTimeSpent(it) }
        return day.face(s.id)?.let { faceName(s, it) }
    }

    internal fun averageText(s: StatInfo, average: Float): String = when {
        s.id == MoodStore.USAGE -> getString(R.string.a_day, requireContext().formattedTimeSpent((average * HOUR_MS).toLong()))
        s.id == stat.id && unit != null -> getString(R.string.a_day, valueText(average))
        else -> String.format(Locale.getDefault(), "%.1f", average)
    }

    /** [s]'s average over the days [keyOf] puts in each group, leaving out days it gives null. */
    internal fun averages(s: StatInfo, keyOf: (LocalDate) -> Int?, value: (StatInfo, LocalDate) -> Float? = ::valueOf): Map<Int, Float> {
        val values = mutableMapOf<Int, MutableList<Float>>()
        dates.forEach { date ->
            val key = keyOf(date) ?: return@forEach
            val value = value(s, date) ?: return@forEach
            values.getOrPut(key) { mutableListOf() }.add(value)
        }
        return values.mapValues { (_, list) -> list.average().toFloat() }
    }

    /** For a medicine, the days it was taken and the days with an entry at all, in each group. */
    internal fun takenCounts(keyOf: (LocalDate) -> Int?): Map<Int, Pair<Int, Int>> {
        val counts = mutableMapOf<Int, Pair<Int, Int>>()
        dates.forEach { date ->
            val key = keyOf(date) ?: return@forEach
            val face = dayOf(date)?.face(stat.id) ?: return@forEach
            val (taken, all) = counts[key] ?: (0 to 0)
            counts[key] = (taken + if (stat.isBlank(face)) 0 else 1) to all + 1
        }
        return counts
    }

    internal val firstDate: LocalDate?
        get() = listOfNotNull(real?.filterValues { it > 0f }?.keys?.minOrNull(), dates.firstOrNull { valueOf(stat, it) != null }).minOrNull()

    /** An hours axis: at least [minHours], rounded up to an even number to fit [highest]. */
    internal fun hoursMax(highest: Float?, minHours: Float = 8f) = maxOf(minHours, ceil((highest ?: 0f) / 2) * 2)

    internal val mood get() = stats.first { it.id == MoodStore.MOOD }

    /** A medicine's line on the trend: between its light and dark purples. */
    internal val medicineColor
        get() = ColorUtils.blendARGB(
            ContextCompat.getColor(requireContext(), R.color.medicineLight),
            ContextCompat.getColor(requireContext(), R.color.medicineDark), 0.4f
        )

    // --- Cards ---

    /** The [count] days the Daily, Taken or Cups card shows, stepping back to the stat's first day. */
    internal fun dailyDates(card: Card, count: Int = DAILY_DAYS, redraw: () -> Unit): List<LocalDate> {
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
    internal fun dailyCard() {
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
    internal fun takenCard() {
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

    internal val moneyGold get() = StatKind.MONEY.shades!!.let { ColorUtils.blendARGB(it.first, it.second, 0.3f) }

    // --- Social: time out of the house, from home Wi-Fi ---

    internal val askLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (_binding != null) render() }
    internal val askCallLog = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        Thread {
            runCatching { app.olauncher.helper.People.refresh(requireContext().applicationContext, force = true) }
            view?.post { if (_binding != null) render() }
        }.start()
    }

    /** The homes' colours, then work's */
    internal val homeColors by lazy { listOf(0xFF81C784.toInt(), 0xFFFFB74D.toInt(), 0xFF7986CB.toInt()) }

    /** Which of week, month and year the places pie shows */
    internal var placesPeriod = 0

    internal val askActivity = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) Steps.refresh(requireContext().applicationContext)
        if (_binding != null) render()
    }

    // --- Screen time across the phone, the Mac and the TV ---

    /** A device screen time comes from: its key in [MoodStore.Day.deviceMs] ("phone" for the phone's own), name and icon */
    internal class Source(val key: String, val name: String, val icon: String, val color: Int)

    /** The phone, then the Mac and the TV, in shades of Screen time's colour so they read as one */
    internal val sources: List<Source>
        get() = listOf(
            Source(PHONE, getString(R.string.device_phone), StatIcons.PHONE, stat.color),
            Source(DeviceTime.Device.MAC.key, getString(R.string.device_mac), StatIcons.LAPTOP, ColorUtils.blendARGB(stat.color, 0xFFFFFFFF.toInt(), 0.45f)),
            Source(DeviceTime.Device.TV.key, getString(R.string.device_tv), StatIcons.TV, ColorUtils.blendARGB(stat.color, 0xFF000000.toInt(), 0.4f)),
        )

    // --- The Mac's and TV's screen time, from the files they write to Google Drive ---

    internal val pickDeviceFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
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

    internal val coffeeCup get() = StatKind.COFFEE.shades!!.let { ColorUtils.blendARGB(it.first, it.second, 0.35f) }
    internal val hotCup get() = StatKind.COFFEE.shades!!.second

    internal fun trendCard() {
        if (stat.kind == StatKind.WORK) return workTrendCard()
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
    internal fun overTimeCard() {
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
    internal fun howOftenCard() {
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
    internal fun doseCard() {
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

    internal fun recentDays(): List<LocalDate> = LocalDate.now().let { today -> (0L until RECENT_DAYS).map { today.minusDays(it) } }

    /** The average of every Monday, every Tuesday and so on. */
    internal fun weekdayCard() {
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
    internal fun takenVsNotCard() {
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
    internal fun periodOf(period: Period, date: LocalDate): Int = when (period) {
        Period.WEEKS -> (date.with(DayOfWeek.MONDAY).toEpochDay() / 7).toInt()
        Period.MONTHS -> date.year * 12 + date.monthValue - 1
        Period.YEARS -> date.year
    }

    internal fun periodStart(period: Period, point: Int): LocalDate = when (period) {
        // Mondays' epoch days are 4 more than a multiple of 7
        Period.WEEKS -> LocalDate.ofEpochDay(point * 7L + 4)
        Period.MONTHS -> LocalDate.of(point / 12, point % 12 + 1, 1)
        Period.YEARS -> LocalDate.of(point, 1, 1)
    }

    internal fun periodLabel(period: Period, point: Int): String = when (period) {
        Period.WEEKS -> getString(R.string.week_short, periodStart(period, point).get(WeekFields.ISO.weekOfWeekBasedYear()))
        Period.MONTHS -> Month.of(point % 12 + 1).getDisplayName(TextStyle.SHORT, Locale.getDefault())
        Period.YEARS -> point.toString()
    }

    companion object {
        internal const val DAILY_DAYS = 18
        internal const val AVERAGE_DAYS = 7L
        internal const val RECENT_DAYS = 30
        internal const val MIN_TAKEN_DAYS = 3
        internal const val MIN_WEEKDAY_DAYS = 14
        internal const val HOUR_MS = 3_600_000f

        /** How many nights Sleep's cards cover, and how many with a Mood before comparing */
        internal const val SLEEP_DAYS = 14
        internal const val SLEEP_MIN_NIGHTS = 3

        /** How far back Music's artists go, and how many are listed */
        internal const val MUSIC_DAYS = 30
        internal const val MUSIC_ARTISTS = 6
        internal const val MUSIC_TRACKS = 5

        /** Names for players that may since have been uninstalled */
        internal val KNOWN_PLAYERS = mapOf(
            "com.spotify.music" to "Spotify",
            "com.google.android.youtube" to "YouTube",
            "com.google.android.apps.youtube.music" to "YouTube Music",
            "au.com.shiftyjelly.pocketcasts" to "Pocket Casts",
            "com.apple.android.music" to "Apple Music",
            "com.soundcloud.android" to "SoundCloud",
            "com.aspiro.tidal" to "Tidal",
            "deezer.android.app" to "Deezer",
            "com.amazon.mp3" to "Amazon Music",
            "com.audible.application" to "Audible",
        )
        internal const val MUSIC_NEW_AFTER = 14L

        /** How many days pickups, doomscrolling and Steps show */
        internal const val HABIT_DAYS = 14

        /** How far back Vibecoding's projects, hours and spend go, and how many projects are listed */
        internal const val VIBE_DAYS = 30
        internal const val VIBE_PROJECTS = 8

        /** How many apps Screen time lists */
        internal const val APPS_SHOWN = 10
        internal val NOT_APPS = setOf("Unknown", "Home screen", "Pixel Launcher", "JLauncher", "Permission controller", "System UI",
            "Android System", "Settings", "Quickstep", "One UI Home", "Finder", "loginwindow", "Dock")

        /** How many days Social's cards cover, and its staying-in dots */
        internal const val SOCIAL_DAYS = 14
        internal const val SOCIAL_LONG_DAYS = 30

        /** How many of the latest purchases Money spent lists */
        internal const val SPENDING_SHOWN = 3

        /** How many days Meditation's streak dots cover */
        internal const val STREAK_DAYS = 30

        /** Minutes in each block of Music's level meters */
        internal const val MUSIC_BLOCK_MINUTES = 10L
        internal const val CUP_DAYS = 10

        /** How many cups before the rest stand out */
        internal const val CUPS_HOT = 3
        /** Porn: how many weeks of bars and of days, and how long in VLC counts */
        internal const val PORN_WEEKS = 10
        internal const val LIMIT_WEEKS = 10
        internal const val WORK_TREND_WEEKS = 12
        internal const val LIMIT_OVER_COLOR = 0xFFE5736F.toInt()
        internal const val PORN_GRID_WEEKS = 5
        internal const val PORN_MIN_MINUTES = 2

        /** Late-night phone use worth counting */
        internal const val SLEEP_LATE_MS = 30 * 60_000L

        internal const val PHONE = "phone"

        /** The Trend and Over time cards' choices, kept while the app runs */
        internal var appsSpan = 0
        internal var trendPeriod = 0
        internal var overTimeSpan = 1
    }
}
