package app.olauncher.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
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
import app.olauncher.helper.dpToPx
import app.olauncher.helper.formattedTimeSpent
import app.olauncher.helper.getColorFromAttr
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class MoodFragment : Fragment() {

    private var _binding: FragmentMoodBinding? = null
    private val binding get() = _binding!!

    private lateinit var moodStore: MoodStore
    private var data = MoodStore.Data()
    private var showUsage = false
    private var selectedDate: LocalDate = LocalDate.now()
    private var shownMonth: YearMonth = YearMonth.now()

    private val moodFaces = mutableListOf<PixelFaceView>()
    private val dayCells = mutableListOf<DayCell>()
    private val weekRows = mutableListOf<LinearLayout>()

    private class DayCell(val box: FrameLayout, val number: TextView, val face: PixelFaceView)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMoodBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        moodStore = MoodStore(requireContext())
        buildMoodFaces()
        buildCalendar()

        binding.tabMood.setOnClickListener { showUsage = false; render() }
        binding.tabUsage.setOnClickListener { showUsage = true; render() }
        binding.prevMonth.setOnClickListener {
            shownMonth = shownMonth.minusMonths(1)
            render()
        }
        binding.nextMonth.setOnClickListener {
            if (shownMonth < YearMonth.now()) shownMonth = shownMonth.plusMonths(1)
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        // Data can change while away, e.g. today's usage was refreshed
        data = moodStore.load()
        render()
    }

    private fun buildMoodFaces() {
        val names = resources.getStringArray(R.array.mood_names)
        for (mood in 0..4) {
            val face = PixelFaceView(requireContext()).apply {
                this.mood = mood
                contentDescription = names[mood]
                val padding = 6.dpToPx()
                setPadding(padding, padding, padding, padding)
                setOnClickListener { onMoodTapped(mood) }
            }
            moodFaces.add(face)
            binding.moodFaces.addView(face, LinearLayout.LayoutParams(0, 48.dpToPx(), 1f))
        }
    }

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
                box.addView(number, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                box.addView(face, FrameLayout.LayoutParams(26.dpToPx(), 26.dpToPx(), Gravity.CENTER))

                val cell = FrameLayout(context)
                cell.addView(box, FrameLayout.LayoutParams(40.dpToPx(), 40.dpToPx(), Gravity.CENTER))
                row.addView(cell, LinearLayout.LayoutParams(0, 44.dpToPx(), 1f))
                dayCells.add(DayCell(box, number, face))
            }
            weekRows.add(row)
            binding.calendarGrid.addView(row)
        }
    }

    private fun onMoodTapped(mood: Int) {
        if (showUsage) return
        val current = data.days[selectedDate.toString()]?.mood
        // Tapping the chosen face again clears it
        moodStore.setMood(selectedDate, if (current == mood) null else mood)
        data = moodStore.load()
        render()
    }

    private fun render() {
        renderTabs()
        renderSelectedDay()
        renderCalendar()
    }

    private fun renderTabs() {
        listOf(binding.tabMood to !showUsage, binding.tabUsage to showUsage).forEach { (tab, selected) ->
            tab.alpha = if (selected) 1f else 0.5f
            tab.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            tab.paintFlags = if (selected) tab.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            else tab.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        }
    }

    private fun renderSelectedDay() {
        val day = data.days[selectedDate.toString()]
        val mood = day?.mood
        val usage = day?.usageMs

        binding.dayLabel.text = if (selectedDate == LocalDate.now()) getString(R.string.today)
        else selectedDate.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))

        binding.moodFaces.isVisible = !showUsage
        binding.usageRow.isVisible = showUsage
        if (showUsage) {
            binding.usageFace.isVisible = usage != null
            usage?.let { binding.usageFace.mood = MoodStore.moodForUsage(it) }
            binding.usageTime.text = usage?.let { requireContext().formattedTimeSpent(it) } ?: getString(R.string.no_data)

            binding.compareRow.isVisible = mood != null
            binding.compareText.text = getString(R.string.mood)
            mood?.let { binding.compareFace.mood = it }
        } else {
            moodFaces.forEachIndexed { index, face -> face.alpha = if (index == mood) 1f else 0.3f }

            binding.compareRow.isVisible = usage != null
            usage?.let {
                binding.compareText.text = getString(R.string.usage_line, requireContext().formattedTimeSpent(it))
                binding.compareFace.mood = MoodStore.moodForUsage(it)
            }
        }
    }

    private fun renderCalendar() {
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
            val value = if (showUsage) day?.usageMs?.let { MoodStore.moodForUsage(it) } else day?.mood
            val isFuture = date > today

            cell.face.isVisible = value != null && !isFuture
            value?.let { cell.face.mood = it }
            cell.number.isVisible = !cell.face.isVisible
            cell.number.text = date.dayOfMonth.toString()
            cell.number.alpha = if (isFuture) 0.3f else 0.6f

            cell.box.setBackgroundResource(
                when (date) {
                    selectedDate -> R.drawable.bg_day_selected
                    today -> R.drawable.bg_day_today
                    else -> 0
                }
            )
            cell.box.setOnClickListener {
                selectedDate = date
                render()
            }
            // After setOnClickListener, which always makes the view clickable
            cell.box.isClickable = !isFuture
        }
        // Hide the last week row when the month fits in five
        weekRows.last().isVisible = dayCells.takeLast(7).any { it.box.isVisible }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        moodFaces.clear()
        dayCells.clear()
        weekRows.clear()
        _binding = null
    }
}
