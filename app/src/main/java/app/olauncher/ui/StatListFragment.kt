package app.olauncher.ui

import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.helper.Tracking
import app.olauncher.data.MoodStore
import app.olauncher.helper.AutoStats
import app.olauncher.helper.Music
import app.olauncher.helper.Sleep
import app.olauncher.helper.Spending
import app.olauncher.helper.StatInfo
import app.olauncher.helper.StatKind
import app.olauncher.helper.Vibecoding
import app.olauncher.helper.allStats
import app.olauncher.helper.dpToPx
import app.olauncher.helper.formattedTimeSpent
import app.olauncher.helper.getColorFromAttr
import java.time.LocalDate
import java.util.Locale

/**
 * The stats' front page, opened from the face on the home screen: Home (every stat together) at the
 * top, then every stat in its order with its icon and today's value, and a way to add one. Tapping a
 * stat opens its page; holding one picks it up to drag to a new place.
 */
class StatListFragment : Fragment(R.layout.fragment_stat_list) {

    private lateinit var rows: LinearLayout
    private lateinit var scroll: ScrollView
    private var stats = emptyList<StatInfo>()

    /** The row being dragged, and where the finger is in the scroll view */
    private var dragged: View? = null
    private var dragY = 0f
    private var scrollSpeed = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.back).setOnClickListener { findNavController().popBackStack() }
        rows = view.findViewById(R.id.statRows)
        scroll = view.findViewById(R.id.statScroll)
        scroll.setOnDragListener { _, event -> onDrag(event) }
        (view as? PullDismissLayout)?.apply {
            pullDown = false
            sideDismiss = arguments?.getInt(app.olauncher.helper.SwipeTargets.SWIPE_BACK) ?: 0
            onDismiss = { if (isAdded) findNavController().popBackStack() }
        }
    }

    override fun onResume() {
        super.onResume()
        Tracking.restartIfStopped(requireContext())
        // Stats can change on their pages (renamed, recoloured, deleted) or today's values move on
        build()
    }

    private fun build() {
        val context = requireContext()
        val store = MoodStore(context)
        val data = store.load()
        stats = context.allStats(data)
        rows.removeAllViews()
        // What's stopped filling stats in, if anything
        val problems = Tracking.problems(context)
        if (problems.isNotEmpty()) rows.addView(TextView(context, null, 0, R.style.TextSmall).apply {
            // One short line; Settings says what's wrong with each
            text = resources.getQuantityString(R.plurals.tracking_need_look, problems.size, problems.map { it.title }.let { names ->
                if (names.size < 2) names.joinToString() else getString(R.string.list_and, names.dropLast(1).joinToString(", "), names.last())
            })
            setCompoundDrawablesRelativeWithIntrinsicBounds(StatIcons.drawable(StatIcons.WARNING, 0xFFFFB300.toInt(), 16.dpToPx()), null, null, null)
            compoundDrawablePadding = 10.dpToPx()
            gravity = Gravity.CENTER_VERTICAL
            textSize = 13f
            setTextColor(context.getColorFromAttr(R.attr.primaryColor))
            setLineSpacing(0f, 1.2f)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16.dpToPx().toFloat()
                setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(0xFFFFB300.toInt(), 48))
            }
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
            setOnClickListener { findNavController().navigate(R.id.action_global_statsSettingsFragment, bundleOf(SettingsFragment.TO_TRACKING to true)) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(20.dpToPx(), 0, 20.dpToPx(), 12.dpToPx())
        })
        rows.addView(row(getString(R.string.stats_home), StatIcons.HOME, context.getColorFromAttr(R.attr.primaryColor), null, home = true) {
            findNavController().navigate(R.id.action_statListFragment_to_statsHomeFragment)
        })
        rows.addView(row(getString(R.string.journal), StatIcons.BOOK, context.getColorFromAttr(R.attr.primaryColor),
            data.days.values.count { !it.note.isNullOrBlank() }.takeIf { it > 0 }?.toString()) {
            findNavController().navigate(R.id.action_statListFragment_to_journalFragment)
        })
        val values = todayValues(data)
        stats.forEach { stat ->
            rows.addView(row(stat.name, StatIcons.forStat(stat), stat.color, values[stat.id]) {
                findNavController().navigate(R.id.action_statListFragment_to_statFragment, bundleOf(StatFragment.STAT_ID to stat.id))
            }.apply {
                tag = stat.id
                setOnLongClickListener { startDrag(it) }
            })
        }
        rows.addView(row(getString(R.string.stats_settings), StatIcons.GEAR, context.getColorFromAttr(R.attr.primaryColor),
            problems.size.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.tracking_problems, it, it) }, faded = true) {
            findNavController().navigate(R.id.action_global_statsSettingsFragment)
        })
        rows.addView(row(getString(R.string.new_stat), ADD, context.getColorFromAttr(R.attr.primaryColor), null, faded = true) {
            NewMedicine.pick(context, store) { if (view != null) build() }
        })
    }

    private val ripple by lazy {
        TypedValue().also { requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }.resourceId
    }

    /** A row: the icon in its colour, the name, and today's value on the right. */
    private fun row(name: String, icon: String, color: Int, value: String?, home: Boolean = false, faded: Boolean = false, onTap: () -> Unit): View {
        val context = requireContext()
        val ink = context.getColorFromAttr(R.attr.primaryColor)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24.dpToPx(), (if (home) 16 else 13).dpToPx(), 24.dpToPx(), (if (home) 16 else 13).dpToPx())
            setBackgroundResource(ripple)
            alpha = if (faded) 0.6f else 1f
            addView(View(context).apply { background = StatIcons.drawable(icon, color, 22.dpToPx()) },
                LinearLayout.LayoutParams(22.dpToPx(), 22.dpToPx()).apply { marginEnd = 18.dpToPx() })
            addView(TextView(context, null, 0, R.style.TextLarge).apply {
                text = name
                textSize = if (home) 20f else 18f
                setTextColor(ink)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (value != null) addView(TextView(context, null, 0, R.style.TextSmall).apply {
                text = value
                textSize = 14f
                setTextColor(ink)
                alpha = 0.55f
                isSingleLine = true
                setPadding(12.dpToPx(), 0, 0, 0)
            })
            setOnClickListener { onTap() }
        }
    }

    /** Each stat's today in a few words: a time or amount where it has one, else its face's name. */
    private fun todayValues(data: MoodStore.Data): Map<String, String> {
        val context = requireContext()
        val today = LocalDate.now()
        val day = data.days[today.toString()]
        val values = mutableMapOf<String, String>()
        stats.forEach { stat ->
            val text = when (stat.kind) {
                StatKind.SCREEN -> day?.totalUsageMs?.let { context.formattedTimeSpent(it) }
                StatKind.SLEEP -> Sleep.nights(context)[today]?.let { context.formattedTimeSpent(it.asleepMs) }
                StatKind.VIBECODING -> Vibecoding.days(context)[today]?.let { context.formattedTimeSpent(it.minutes * 60_000L) }
                StatKind.MUSIC -> Music.days(context)[today]?.let { context.formattedTimeSpent(it.ms) }
                StatKind.MONEY -> if (Spending.purchases(context).any { it.date == today })
                    Spending.totalCents(context, today).let { String.format(Locale.getDefault(), "$%.2f", it / 100.0) } else null
                else -> null
            } ?: day?.face(stat.id)?.let { faceName(stat, it) }
            if (text != null) values[stat.id] = text
        }
        return values
    }

    private fun faceName(stat: StatInfo, face: Int): String? {
        val place = stat.faces.indexOf(face)
        stat.labels.getOrNull(place)?.takeIf { it.isNotEmpty() }?.let { return it }
        return when {
            stat.id == MoodStore.MOOD -> resources.getStringArray(R.array.mood_names)[face]
            stat.isBlank(face) -> getString(R.string.none_taken)
            stat.medicine && stat.faces.size == 2 -> getString(R.string.taken)
            stat.medicine -> getString(R.string.dose_number, place)
            else -> MoodStore.score(face, stat.inverted).toString()
        }
    }

    // --- Dragging a stat to a new place ---

    private fun startDrag(row: View): Boolean {
        dragged = row
        if (!row.startDragAndDrop(null, View.DragShadowBuilder(row), row, 0)) {
            dragged = null
            return false
        }
        row.alpha = 0.15f
        return true
    }

    private fun onDrag(event: DragEvent): Boolean {
        val row = dragged ?: return false
        when (event.action) {
            DragEvent.ACTION_DRAG_LOCATION -> {
                dragY = event.y
                moveDragged(row)
                // Scrolls while the finger is held near the top or bottom
                val edge = 64.dpToPx()
                val wasScrolling = scrollSpeed != 0
                scrollSpeed = when {
                    dragY < edge -> -8.dpToPx()
                    dragY > scroll.height - edge -> 8.dpToPx()
                    else -> 0
                }
                if (scrollSpeed != 0 && !wasScrolling) scroll.postOnAnimation(autoScroll)
            }
            DragEvent.ACTION_DRAG_EXITED -> scrollSpeed = 0
            DragEvent.ACTION_DRAG_ENDED -> finishDrag()
        }
        return true
    }

    private val autoScroll = object : Runnable {
        override fun run() {
            val row = dragged ?: return
            if (view == null || scrollSpeed == 0) return
            scroll.scrollBy(0, scrollSpeed)
            moveDragged(row)
            scroll.postOnAnimation(this)
        }
    }

    /** Puts the dragged row after every other stat row whose middle is above the finger. */
    private fun moveDragged(row: View) {
        val y = dragY + scroll.scrollY - rows.top
        val first = 1
        val last = rows.childCount - 2
        var target = first
        for (i in first..last) {
            val other = rows.getChildAt(i)
            if (other != row && (other.top + other.bottom) / 2 < y) target++
        }
        target = target.coerceAtMost(last)
        if (rows.indexOfChild(row) != target) {
            rows.removeView(row)
            rows.addView(row, target)
        }
    }

    private fun finishDrag() {
        val row = dragged ?: return
        dragged = null
        scrollSpeed = 0
        row.alpha = 1f
        if (view == null) return
        val ids = (1 until rows.childCount - 1).mapNotNull { rows.getChildAt(it).tag as? String }
        if (ids != stats.map { it.id }) {
            MoodStore(requireContext()).setOrder(ids)
            stats = requireContext().allStats(MoodStore(requireContext()).load())
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        dragged = null
        scrollSpeed = 0
    }

    companion object {
        private val ADD = StatIcons.run {
            listOf(
                "...........",
                ".....X.....",
                ".....X.....",
                ".....X.....",
                ".....X.....",
                ".XXXXXXXXX.",
                ".....X.....",
                ".....X.....",
                ".....X.....",
                ".....X.....",
                "...........",
            ).joinToString("\n")
        }
    }
}
