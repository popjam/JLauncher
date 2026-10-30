package app.olauncher.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.DragEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.databinding.FragmentMoodBinding
import app.olauncher.helper.StatInfo
import app.olauncher.helper.Weather
import app.olauncher.helper.allStats
import app.olauncher.helper.bindFaceLabels
import app.olauncher.helper.dpToPx
import app.olauncher.helper.formattedTimeSpent
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.setStatDot
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

class MoodFragment : Fragment() {

    private var _binding: FragmentMoodBinding? = null
    private val binding get() = _binding!!

    private lateinit var moodStore: MoodStore
    private var data = MoodStore.Data()
    private var stats = emptyList<StatInfo>()

    /** [MoodStore.ALL] for the graph, otherwise the stat whose days are shown */
    private var selectedStat = MoodStore.MOOD
    private var selectedDate: LocalDate = LocalDate.now()
    private var shownMonth: YearMonth = YearMonth.now()

    /** The All graph shows each day of [graphMonth], or weekly totals or monthly averages over [graphYear] */
    private var graphMode = GraphMode.WEEKLY
    private var graphYear = LocalDate.now().get(WEEKS.weekBasedYear())
    private var graphMonth: YearMonth = YearMonth.now()

    private val dayCells = mutableListOf<DayCell>()
    private val weekRows = mutableListOf<LinearLayout>()

    /** What the tabs were built from, so they're only rebuilt (losing their scroll) when it changes */
    private var tabsKey: List<StatInfo>? = null

    /** The date whose note is in the input, so re-renders don't overwrite unsaved typing. */
    private var noteDate: LocalDate? = null

    /** The tab being dragged to a new place, and where the finger is in [FragmentMoodBinding.tabScroll] */
    private var draggedTab: View? = null
    private var dragX = 0f

    private enum class GraphMode { DAILY, WEEKLY, MONTHLY }

    private class DayCell(val box: FrameLayout, val number: TextView, val face: PixelFaceView, val noteDot: View)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMoodBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        moodStore = MoodStore(requireContext())
        // The graph shown last time
        graphMode = GraphMode.entries.firstOrNull { it.name == moodStore.load().graphMode } ?: GraphMode.WEEKLY
        buildCalendar()

        binding.addStat.setOnClickListener { editStat(null) }
        binding.prevMonth.setOnClickListener {
            shownMonth = shownMonth.minusMonths(1)
            render()
        }
        binding.nextMonth.setOnClickListener {
            if (shownMonth < YearMonth.now()) shownMonth = shownMonth.plusMonths(1)
            render()
        }
        binding.prevGraph.setOnClickListener { moveGraph(-1) }
        binding.nextGraph.setOnClickListener { moveGraph(1) }
        binding.graphTitle.setOnClickListener { toggleGraphMode() }
        binding.selectAllStats.setOnClickListener { setHidden(emptySet()) }
        binding.clearStats.setOnClickListener { setHidden(stats.map { it.id }.toSet()) }
        binding.statChart.onPointSelected = { renderPointDetails(it) }
        binding.statHeatmap.onPointSelected = { renderPointDetails(it) }
        // A day's details switch between each stat's value and a written summary, remembered for every day
        binding.pointDetails.setOnClickListener {
            if (graphMode != GraphMode.DAILY) return@setOnClickListener
            moodStore.update(sync = false) { it.daySummary = !it.daySummary }
            reload()
            renderPointDetails(graphSelected)
        }
        binding.tabScroll.setOnDragListener { _, event -> onTabDrag(event) }
        binding.noteInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveNote() }
    }

    override fun onResume() {
        super.onResume()
        // Data can change while away, e.g. today's usage was refreshed or the prompt added a note
        reload()
        noteDate = null
        render()
    }

    override fun onPause() {
        saveNote()
        super.onPause()
    }

    private fun reload() {
        data = moodStore.load()
        stats = requireContext().allStats(data)
        if (selectedStat != MoodStore.ALL && stats.none { it.id == selectedStat }) selectedStat = MoodStore.MOOD
    }

    private val selected: StatInfo? get() = stats.firstOrNull { it.id == selectedStat }

    private fun saveNote() {
        val date = noteDate ?: return
        val note = MoodStore.sanitizeNote(binding.noteInput.text?.toString())
        if (note != data.days[date.toString()]?.note) {
            moodStore.setNote(date, note)
            reload()
            renderCalendar()
        }
        // Show the cleaned-up text, unless still typing
        if (!binding.noteInput.hasFocus() && binding.noteInput.text?.toString() != note.orEmpty())
            binding.noteInput.setText(note.orEmpty())
    }

    private fun leaveNote() {
        saveNote()
        binding.noteInput.clearFocus()
        binding.noteInput.hideKeyboard()
    }

    private fun selectStat(id: String) {
        if (id == selectedStat) return
        leaveNote()
        selectedStat = id
        render()
    }

    /** [statId] null adds a new stat. */
    private fun editStat(statId: String?) {
        StatEditor.show(requireContext(), moodStore, statId) { id ->
            if (_binding == null) return@show
            if (id != null) selectedStat = id
            reload()
            render()
            if (id != null) scrollToTab(id)
        }
    }

    private fun setHidden(ids: Set<String>) {
        moodStore.setHiddenInGraph(ids)
        reload()
        renderGraph()
    }

    // --- Building ---

    private fun buildCalendar() {
        val context = requireContext()
        var day = DayOfWeek.MONDAY
        repeat(7) {
            binding.weekdayHeader.addView(TextView(context).apply {
                text = day.getDisplayName(TextStyle.NARROW, Locale.getDefault())
                gravity = Gravity.CENTER
                textSize = 12f
                alpha = 0.5f
                setTextColor(context.getColorFromAttr(R.attr.primaryColor))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            day = day.plus(1)
        }

        repeat(6) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(7) {
                val box = FrameLayout(context)
                val number = TextView(context).apply {
                    gravity = Gravity.CENTER
                    textSize = 13f
                    setTextColor(context.getColorFromAttr(R.attr.primaryColor))
                }
                val face = PixelFaceView(context)
                // Marks a day with a note, at the bottom right of the face
                val noteDot = View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL } }
                box.addView(number, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                box.addView(face, FrameLayout.LayoutParams(26.dpToPx(), 26.dpToPx(), Gravity.CENTER))
                box.addView(noteDot, FrameLayout.LayoutParams(5.dpToPx(), 5.dpToPx(), Gravity.BOTTOM or Gravity.END).apply {
                    setMargins(0, 0, 6.dpToPx(), 6.dpToPx())
                })

                val cell = FrameLayout(context)
                cell.addView(box, FrameLayout.LayoutParams(40.dpToPx(), 40.dpToPx(), Gravity.CENTER))
                row.addView(cell, LinearLayout.LayoutParams(0, 44.dpToPx(), 1f))
                dayCells.add(DayCell(box, number, face, noteDot))
            }
            weekRows.add(row)
            binding.calendarGrid.addView(row)
        }
    }

    /**
     * "All", then a tab per stat with a dot in its colour. Tapping the open stat's tab edits it; holding
     * a stat's tab picks it up to drag somewhere else.
     */
    private fun buildTabs() {
        if (tabsKey == stats) return
        tabsKey = stats
        val context = requireContext()
        binding.tabRow.removeAllViews()
        val tabs = listOf<StatInfo?>(null) + stats
        tabs.forEach { stat ->
            val id = stat?.id ?: MoodStore.ALL
            binding.tabRow.addView(TextView(context, null, 0, R.style.TextSmall).apply {
                tag = id
                text = stat?.name ?: getString(R.string.all)
                setTextColor(context.getColorFromAttr(R.attr.primaryColor))
                setPadding(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 8.dpToPx())
                gravity = Gravity.CENTER_VERTICAL
                setStatDot(stat?.color)
                setOnClickListener {
                    if (stat != null && id == selectedStat) {
                        leaveNote()
                        editStat(id)
                    } else selectStat(id)
                }
                if (stat != null) setOnLongClickListener { startTabDrag(it) }
            })
        }
    }

    private fun startTabDrag(tab: View): Boolean {
        leaveNote()
        // Set first, in case the drag's start reaches the listener straight away
        draggedTab = tab
        if (!tab.startDragAndDrop(null, View.DragShadowBuilder(tab), tab, 0)) {
            draggedTab = null
            return false
        }
        tab.alpha = 0.15f
        return true
    }

    private fun onTabDrag(event: DragEvent): Boolean {
        val tab = draggedTab ?: return false
        when (event.action) {
            DragEvent.ACTION_DRAG_LOCATION -> {
                dragX = event.x
                moveDraggedTab(tab)
                // Scrolls while the finger is held near either end
                val edge = 48.dpToPx()
                val wasScrolling = tabScrollSpeed != 0
                tabScrollSpeed = when {
                    dragX < edge -> -6.dpToPx()
                    dragX > binding.tabScroll.width - edge -> 6.dpToPx()
                    else -> 0
                }
                if (tabScrollSpeed != 0 && !wasScrolling) binding.tabScroll.postOnAnimation(autoScrollTabs)
            }
            DragEvent.ACTION_DRAG_EXITED -> tabScrollSpeed = 0
            DragEvent.ACTION_DRAG_ENDED -> finishTabDrag()
        }
        return true
    }

    private var tabScrollSpeed = 0
    private val autoScrollTabs = object : Runnable {
        override fun run() {
            val tab = draggedTab ?: return
            if (_binding == null || tabScrollSpeed == 0) return
            binding.tabScroll.scrollBy(tabScrollSpeed, 0)
            moveDraggedTab(tab)
            binding.tabScroll.postOnAnimation(this)
        }
    }

    /** Puts the dragged tab after every other stat tab whose middle is left of the finger. */
    private fun moveDraggedTab(tab: View) {
        val row = binding.tabRow
        val x = dragX + binding.tabScroll.scrollX
        var target = 1 // After All
        for (i in 1 until row.childCount) {
            val other = row.getChildAt(i)
            if (other != tab && (other.left + other.right) / 2 < x) target++
        }
        if (row.indexOfChild(tab) != target) {
            row.removeView(tab)
            row.addView(tab, target)
        }
    }

    /** Saves the new order, which the daily check and the All graph follow too. */
    private fun finishTabDrag() {
        draggedTab ?: return
        draggedTab = null
        tabScrollSpeed = 0
        if (_binding == null) return
        val ids = (1 until binding.tabRow.childCount).map { binding.tabRow.getChildAt(it).tag as String }
        if (ids != stats.map { it.id }) {
            moodStore.setOrder(ids)
            reload()
            // The tabs are already in this order
            tabsKey = stats
        }
        render()
    }

    private fun scrollToTab(id: String) {
        binding.tabScroll.post {
            val tab = binding.tabRow.findViewWithTag<View>(id) ?: return@post
            binding.tabScroll.smoothScrollTo(maxOf(0, tab.left - 24.dpToPx()), 0)
        }
    }

    private fun onFaceTapped(face: Int) {
        val stat = selected ?: return
        if (stat.id == MoodStore.USAGE) return
        val current = data.days[selectedDate.toString()]?.face(stat.id)
        // Tapping the chosen face again clears it
        moodStore.setFace(selectedDate, stat.id, if (current == face) null else face)
        reload()
        render()
    }

    // --- Rendering ---

    private fun render() {
        renderTabs()
        val all = selectedStat == MoodStore.ALL
        binding.dayCard.isVisible = !all
        binding.calendarCard.isVisible = !all
        binding.graphCard.isVisible = all
        binding.graphToggles.isVisible = all
        if (all) {
            renderGraph()
        } else {
            renderSelectedDay()
            renderCalendar()
        }
    }

    private fun renderTabs() {
        buildTabs()
        for (i in 0 until binding.tabRow.childCount) {
            val tab = binding.tabRow.getChildAt(i) as TextView
            val isSelected = tab.tag == selectedStat
            tab.alpha = if (isSelected) 1f else 0.5f
            tab.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
            tab.paintFlags = if (isSelected) tab.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            else tab.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        }
    }

    private fun renderSelectedDay() {
        val stat = selected ?: return
        val day = data.days[selectedDate.toString()]
        val showUsage = stat.id == MoodStore.USAGE

        binding.dayLabel.text = if (selectedDate == LocalDate.now()) getString(R.string.today)
        else selectedDate.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))

        if (noteDate != selectedDate) {
            noteDate = selectedDate
            binding.noteInput.setText(day?.note.orEmpty())
        }

        binding.moodFaces.isVisible = !showUsage
        binding.faceLabels.isVisible = !showUsage && stat.labels.isNotEmpty()
        binding.usageRow.isVisible = showUsage
        if (showUsage) {
            val usage = day?.usageMs
            binding.usageFace.isVisible = usage != null
            usage?.let { binding.usageFace.mood = MoodStore.moodForUsage(it) }
            binding.usageTime.text = usage?.let { requireContext().formattedTimeSpent(it) } ?: getString(R.string.no_data)
        } else {
            val faces = stat.faces
            if (binding.moodFaces.tag != faces) {
                binding.moodFaces.tag = faces
                binding.moodFaces.removeAllViews()
                val names = resources.getStringArray(R.array.mood_names)
                faces.forEach { face ->
                    binding.moodFaces.addView(PixelFaceView(requireContext()).apply {
                        mood = face
                        contentDescription = names[face]
                        val padding = 6.dpToPx()
                        setPadding(padding, padding, padding, padding)
                        setOnClickListener { onFaceTapped(face) }
                    }, LinearLayout.LayoutParams(0, 48.dpToPx(), 1f))
                }
            }
            val chosen = day?.face(stat.id)
            val alphas = faces.map { face -> if (face == chosen) 1f else 0.3f }
            alphas.forEachIndexed { i, alpha -> binding.moodFaces.getChildAt(i).alpha = alpha }
            if (stat.labels.isNotEmpty()) bindFaceLabels(binding.faceLabels, stat.labels, alphas)
        }
    }

    private fun renderCalendar() {
        val stat = selected ?: return
        val today = LocalDate.now()
        binding.monthTitle.text = shownMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
        val canGoForward = shownMonth < YearMonth.now()
        binding.nextMonth.alpha = if (canGoForward) 1f else 0.3f
        binding.nextMonth.isEnabled = canGoForward

        val first = shownMonth.atDay(1)
        val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
        dayCells.forEachIndexed { index, cell ->
            val date = start.plusDays(index.toLong())
            val inMonth = date.month == shownMonth.month && date.year == shownMonth.year
            cell.box.isVisible = inMonth
            if (!inMonth) return@forEachIndexed

            val day = data.days[date.toString()]
            val value = day?.face(stat.id)
            val isFuture = date > today

            cell.face.isVisible = value != null && !isFuture
            value?.let { cell.face.mood = it }
            cell.number.isVisible = !cell.face.isVisible
            cell.number.text = date.dayOfMonth.toString()
            cell.number.alpha = if (isFuture) 0.3f else 0.6f

            cell.noteDot.isVisible = day?.note != null && !isFuture
            (cell.noteDot.background as GradientDrawable).setColor(
                if (cell.face.isVisible) PixelFaceView.moodColor(requireContext(), cell.face.mood)
                else requireContext().getColorFromAttr(R.attr.primaryColor)
            )

            cell.box.setBackgroundResource(
                when (date) {
                    selectedDate -> R.drawable.bg_day_selected
                    today -> R.drawable.bg_day_today
                    else -> 0
                }
            )
            cell.box.setOnClickListener {
                leaveNote()
                selectedDate = date
                render()
            }
            // After setOnClickListener, which always makes the view clickable
            cell.box.isClickable = !isFuture
        }
        // Hide the last week row when the month fits in five
        weekRows.last().isVisible = dayCells.takeLast(7).any { it.box.isVisible }
    }

    /** Where a date falls on the graph shown (its day, week or month), or null when it's off it. */
    private fun graphPoint(date: LocalDate): Int? = when (graphMode) {
        GraphMode.DAILY -> date.dayOfMonth.takeIf { YearMonth.from(date) == graphMonth }
        GraphMode.WEEKLY -> date.get(WEEKS.weekOfWeekBasedYear()).takeIf { date.get(WEEKS.weekBasedYear()) == graphYear }
        GraphMode.MONTHLY -> date.monthValue.takeIf { date.year == graphYear }
    }

    /**
     * Each stat's value at each point on the graph: a day scores 5 (happy) to 1 (sad). Weekly points add
     * up their days, with a day with no entry counting 0; monthly points average the days with entries.
     */
    private fun graphValues(): Map<String, Map<Int, Float>> {
        val totals = mutableMapOf<String, MutableMap<Int, Int>>()
        val counts = mutableMapOf<String, MutableMap<Int, Int>>()
        data.days.forEach { (key, day) ->
            val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@forEach
            val point = graphPoint(date) ?: return@forEach
            stats.forEach { stat ->
                day.face(stat.id)?.let { face ->
                    totals.getOrPut(stat.id) { mutableMapOf() }.merge(point, MoodStore.score(face, stat.inverted), Int::plus)
                    counts.getOrPut(stat.id) { mutableMapOf() }.merge(point, 1, Int::plus)
                }
            }
        }
        return totals.mapValues { (id, points) ->
            points.mapValues { (point, total) ->
                if (graphMode == GraphMode.MONTHLY) total.toFloat() / counts.getValue(id).getValue(point) else total.toFloat()
            }
        }
    }

    private val graphSelected: Int?
        get() = if (graphMode == GraphMode.DAILY) binding.statHeatmap.selected else binding.statChart.selected

    /** Steps the month (daily) or year (weekly, monthly) shown. */
    private fun moveGraph(by: Int) {
        if (graphMode == GraphMode.DAILY) graphMonth = graphMonth.plusMonths(by.toLong()) else graphYear += by
        renderGraph(rangeChanged = true)
    }

    /** Daily, then weekly, then monthly and round again, keeping to the period that was being looked at. */
    private fun toggleGraphMode() {
        val picked = graphSelected
        val today = LocalDate.now()
        graphMode = when (graphMode) {
            GraphMode.DAILY -> {
                val date = picked?.let { graphMonth.atDay(it) } ?: graphMonth.atDay(minOf(15, graphMonth.lengthOfMonth()))
                graphYear = date.get(WEEKS.weekBasedYear())
                GraphMode.WEEKLY
            }
            // Week-based years start within a few days of calendar years
            GraphMode.WEEKLY -> GraphMode.MONTHLY
            GraphMode.MONTHLY -> {
                graphMonth = when {
                    picked != null -> YearMonth.of(graphYear, picked)
                    graphYear == today.year -> YearMonth.from(today)
                    else -> YearMonth.of(graphYear, 12)
                }
                GraphMode.DAILY
            }
        }
        moodStore.update(sync = false) { it.graphMode = graphMode.name }
        renderGraph(rangeChanged = true)
    }

    private fun renderGraph(rangeChanged: Boolean = false) {
        val today = LocalDate.now()
        val firstDate = data.days.keys.firstOrNull()?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today
        val canGoBack: Boolean
        val canGoForward: Boolean
        if (graphMode == GraphMode.DAILY) {
            val thisMonth = YearMonth.from(today)
            val firstMonth = minOf(YearMonth.from(firstDate), thisMonth)
            graphMonth = graphMonth.coerceIn(firstMonth, thisMonth)
            binding.graphTitle.text = getString(
                R.string.graph_daily_title, graphMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
            )
            canGoBack = graphMonth > firstMonth
            canGoForward = graphMonth < thisMonth
            binding.prevGraph.contentDescription = getString(R.string.previous_month)
            binding.nextGraph.contentDescription = getString(R.string.next_month)
        } else {
            val weekly = graphMode == GraphMode.WEEKLY
            val thisYear = if (weekly) today.get(WEEKS.weekBasedYear()) else today.year
            val firstYear = minOf(if (weekly) firstDate.get(WEEKS.weekBasedYear()) else firstDate.year, thisYear)
            graphYear = graphYear.coerceIn(firstYear, thisYear)
            binding.graphTitle.text = getString(if (weekly) R.string.graph_weekly_title else R.string.graph_monthly_title, graphYear)
            canGoBack = graphYear > firstYear
            canGoForward = graphYear < thisYear
            binding.prevGraph.contentDescription = getString(R.string.previous_year)
            binding.nextGraph.contentDescription = getString(R.string.next_year)
        }
        listOf(binding.prevGraph to canGoBack, binding.nextGraph to canGoForward).forEach { (arrow, enabled) ->
            arrow.isEnabled = enabled
            arrow.alpha = if (enabled) 1f else 0.3f
        }

        val values = graphValues()
        // From the first point with anything in it to today, or to the last point with data in the past
        val pointsWithData = values.values.flatMap { it.keys }
        val first = pointsWithData.minOrNull()
        val last = graphPoint(today) ?: pointsWithData.maxOrNull()
        val hasData = first != null && last != null
        val daily = graphMode == GraphMode.DAILY
        binding.statChart.isVisible = hasData && !daily
        binding.statHeatmap.isVisible = hasData && daily
        binding.graphEmpty.isVisible = !hasData
        if (hasData) {
            val visible = stats.filter { it.id !in data.hiddenInGraph }
            if (daily) {
                // Coloured by the day's face, whichever way round the stat scores
                binding.statHeatmap.setData(
                    visible.map { stat ->
                        StatHeatmapView.Row(stat.name, stat.color, values[stat.id].orEmpty().mapValues { (_, score) -> MoodStore.faceForScore(score.toInt(), stat.inverted) })
                    },
                    first!!,
                    maxOf(first, last!!),
                    label = { day -> day.toString() },
                    clearSelection = rangeChanged,
                )
            } else {
                val weekly = graphMode == GraphMode.WEEKLY
                binding.statChart.setData(
                    visible.map { StatChartView.Series(it.color, values[it.id].orEmpty()) },
                    first!!,
                    maxOf(first, last!!),
                    max = if (weekly) 35 else 5,
                    gridStep = if (weekly) 7 else 1,
                    label = if (weekly) { week -> getString(R.string.week_short, week) }
                    else { month -> Month.of(month).getDisplayName(TextStyle.SHORT, Locale.getDefault()) },
                    clearSelection = rangeChanged,
                )
            }
        }
        renderPointDetails(if (hasData) graphSelected else null)
        renderChips()
    }

    /** The tapped day's, week's or month's values, each with its stat's dot. */
    private fun renderPointDetails(point: Int?) {
        binding.pointDetails.isVisible = point != null
        if (point == null) return
        val values = graphValues()
        val title = when (graphMode) {
            GraphMode.DAILY -> graphMonth.atDay(point).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
            GraphMode.WEEKLY -> getString(R.string.week_number, point)
            GraphMode.MONTHLY -> YearMonth.of(graphYear, point).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
        }
        val text = SpannableStringBuilder(title)
        val summary = graphMode == GraphMode.DAILY && data.daySummary
        if (summary) {
            text.append("\n").append(daySummary(graphMonth.atDay(point), stats.filter { it.id !in data.hiddenInGraph }))
            binding.pointDetails.text = text
            return
        }
        stats.filter { it.id !in data.hiddenInGraph }.forEachIndexed { i, stat ->
            text.append(if (i % 3 == 0) "\n" else "     ")
            val dotStart = text.length
            text.append("● ")
            text.setSpan(ForegroundColorSpan(stat.color), dotStart, dotStart + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val value = values[stat.id]?.get(point)
            // A day's face label, when the stat has one for it, says more than its score
            val label = value?.takeIf { graphMode == GraphMode.DAILY }?.let { score ->
                val face = MoodStore.faceForScore(score.toInt(), stat.inverted)
                stat.labels.getOrNull(stat.faces.indexOf(face))?.takeIf { it.isNotEmpty() }
            }
            text.append(
                when {
                    label != null -> getString(R.string.stat_label, stat.name, label)
                    value == null -> getString(R.string.stat_total, stat.name, "–")
                    graphMode == GraphMode.MONTHLY ->
                        getString(R.string.stat_total, stat.name, String.format(Locale.getDefault(), "%.1f", value))
                    else -> getString(R.string.stat_total, stat.name, value.toInt().toString())
                }
            )
        }
        binding.pointDetails.text = text
    }

    /**
     * A sentence per stat with a value that day, in tab order: the stat's own sentence for the face if
     * it has one, with {} replaced by the face's label, else a plain one ("Energy was low"). The day's
     * weather follows the mood (or leads, without it), and the day's note ends it.
     */
    private fun daySummary(date: LocalDate, stats: List<StatInfo>): String {
        val day = data.days[date.toString()] ?: return getString(R.string.summary_nothing)
        val words = resources.getStringArray(R.array.summary_faces)
        val sentences = stats.mapNotNull { stat ->
            val face = day.face(stat.id) ?: return@mapNotNull null
            val index = stat.faces.indexOf(face)
            val label = stat.labels.getOrNull(index).orEmpty()
            val phrase = stat.phrases.getOrNull(index).orEmpty()
            when {
                phrase.isNotEmpty() -> phrase.replace("{}", label)
                stat.id == MoodStore.MOOD -> getString(R.string.summary_mood, words[face])
                stat.id == MoodStore.USAGE -> {
                    val time = requireContext().formattedTimeSpent(day.usageMs ?: 0)
                    // Only worth naming an app that took up a good part of the day
                    if (day.topApp != null && (day.topAppMs ?: 0) >= TOP_APP_MIN_MS) getString(R.string.summary_usage_app, time, day.topApp)
                    else getString(R.string.summary_usage, time)
                }
                label.isNotEmpty() -> getString(R.string.stat_label, stat.name, label)
                else -> getString(R.string.summary_stat, stat.name, words[face])
            }
        }
            .toMutableList()
        day.weatherCode?.let { code ->
            val moodIndex = stats.filter { day.face(it.id) != null }.indexOfFirst { it.id == MoodStore.MOOD }
            sentences.add(moodIndex + 1, Weather.pastSentence(requireContext(), code))
        }
        if (sentences.isEmpty() && day.note == null) return getString(R.string.summary_nothing)
        // Each a sentence: capitalised, with a full stop unless it ends in its own
        val text = sentences.joinToString(" ") { sentence ->
            val text = sentence.trim().replaceFirstChar { it.uppercase() }
            if (text.last() in ".!?") text else "$text."
        }
        // A full stop after the quote, unless the note already ends its own sentence
        val note = day.note?.replace(Regex("\\s*\\n+\\s*"), " ")?.let { note ->
            getString(R.string.summary_note, note) + if (note.last() in ".!?") "" else "."
        }
        return listOfNotNull(text.ifEmpty { null }, note).joinToString(" ")
    }

    /** Tap a stat to show or hide its line. */
    private fun renderChips() {
        val context = requireContext()
        binding.graphChips.removeAllViews()
        stats.forEach { stat ->
            val shown = stat.id !in data.hiddenInGraph
            binding.graphChips.addView(TextView(context, null, 0, R.style.TextSmall).apply {
                text = stat.name
                textSize = 13f
                setTextColor(context.getColorFromAttr(R.attr.primaryColor))
                setBackgroundResource(R.drawable.bg_stat_chip)
                setPadding(10.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
                setStatDot(stat.color)
                alpha = if (shown) 1f else 0.35f
                setOnClickListener {
                    setHidden(if (shown) data.hiddenInGraph + stat.id else data.hiddenInGraph - stat.id)
                }
            })
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        dayCells.clear()
        weekRows.clear()
        tabsKey = null
        draggedTab = null
        tabScrollSpeed = 0
        _binding = null
    }

    companion object {
        private val WEEKS = WeekFields.ISO
        private const val TOP_APP_MIN_MS = 30 * 60 * 1000L
    }
}
