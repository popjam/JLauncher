package app.olauncher.ui

import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
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
import app.olauncher.helper.statAmount
import app.olauncher.helper.Vibecoding
import app.olauncher.helper.allStats
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import java.time.LocalDate
import java.util.Locale

/**
 * The stats' front page, opened from the face on the home screen: Overview (every stat together) at
 * the top, then every stat in its order with its icon and today's value, and a way to add one. Tapping
 * a stat opens its page; holding one and dragging moves it, holding and letting go offers Edit, Remove
 * and Select. Pulling down closes it.
 */
class StatListFragment : Fragment(R.layout.fragment_stat_list) {

    private lateinit var rows: LinearLayout
    private lateinit var scroll: ScrollView
    private var stats = emptyList<StatInfo>()

    /** The stats and Supplements: moved by holding and dragging, and removed one or several at a time */
    private val statList = HoldList<String>(
        onReorder = { ids -> if (view != null) MoodStore(requireContext()).setOrder(ids) },
        onRemove = { ids -> deleteStats(ids) },
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rows = view.findViewById(R.id.statRows)
        scroll = view.findViewById(R.id.statScroll)
        (view as? PullDismissLayout)?.onDismiss = { if (isAdded) findNavController().popBackStack() }
    }

    override fun onResume() {
        super.onResume()
        Tracking.restartIfStopped(requireContext())
        // Stats can change on their pages (renamed, recoloured, deleted) or today's values move on
        build()
    }

    override fun onPause() {
        statList.end()
        super.onPause()
    }

    /** Edits a stat in its form */
    private fun edit(id: String) = StatEditor.show(requireContext(), MoodStore(requireContext()), id) { if (view != null) build() }

    /** Deletes the stats [ids] and all their history, after asking once */
    private fun deleteStats(ids: Set<String>) {
        val context = requireContext()
        val store = MoodStore(context)
        val data = store.load()
        val doomed = ids.mapNotNull { data.stat(it) }
        if (doomed.isEmpty()) return
        val title = if (doomed.size == 1) getString(R.string.delete_what, doomed.first().name)
        else resources.getQuantityString(R.plurals.stats_delete_n, doomed.size, doomed.size)
        AppDialog.confirmDelete(context, title, getString(if (doomed.size == 1) R.string.stat_delete_detail else R.string.stats_delete_n_detail)) {
            doomed.forEach { store.deleteStat(it.id) }
            if (view != null) build()
        }
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
            setCompoundDrawablesRelativeWithIntrinsicBounds(StatIcons.drawable(StatIcons.WARNING, Style.WARNING, 16.dpToPx()), null, null, null)
            compoundDrawablePadding = 10.dpToPx()
            gravity = Gravity.CENTER_VERTICAL
            textSize = Style.TEXT_SMALL
            setTextColor(context.getColorFromAttr(R.attr.primaryColor))
            setLineSpacing(0f, 1.2f)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = Style.radiusSmall
                setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(Style.WARNING, 48))
            }
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
            setOnClickListener { findNavController().navigate(R.id.action_global_settingsFragment, bundleOf(SettingsFragment.OPEN to SettingsFragment.TRACKING)) }
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
        rows.addView(row(getString(R.string.battery), StatIcons.BATTERY, context.getColorFromAttr(R.attr.primaryColor),
            app.olauncher.helper.Battery.samples(context).lastOrNull()?.let { "${it.level}%" }) {
            findNavController().navigate(R.id.batteryFragment)
        })
        val values = todayValues(data)
        statList.attach(rows, scroll)
        fun open(id: String) = findNavController().navigate(R.id.action_statListFragment_to_statFragment, bundleOf(StatFragment.STAT_ID to id))
        // In the stat pages' order: Supplements sits among the stats where it was dragged to, at the top until then
        StatFragment.pageIds(context).forEach { id ->
            if (id == MoodStore.SUPPLEMENTS) {
                val row = row(getString(R.string.supplements), StatIcons.TABLET, MoodStore.SUPPLEMENTS_COLOR,
                    data.supplements.takeIf { it.isNotEmpty() }?.let { getString(R.string.supplements_today, app.olauncher.data.Supplements.takenCount(it, LocalDate.now())) }) {}
                rows.addView(row)
                statList.item(row, id, label = getString(R.string.supplements), selectable = false, onTap = { open(id) })
                return@forEach
            }
            val stat = stats.firstOrNull { it.id == id } ?: return@forEach
            val row = row(stat.name, StatIcons.forStat(stat), stat.color, values[stat.id]) {}
            rows.addView(row)
            // Mood and Screen time can be edited but not removed; stats filled in by the app neither
            val builtIn = id == MoodStore.MOOD || id == MoodStore.USAGE
            val own = data.stat(id) != null
            statList.item(row, id, label = stat.name, selectable = own, onTap = { open(id) }, actions = listOfNotNull(
                if (builtIn || own) HoldList.Action(getString(R.string.edit)) { edit(id) } else null,
                if (own) HoldList.Action(getString(R.string.remove)) { deleteStats(setOf(id)) } else null,
            ))
        }
        rows.addView(row(getString(R.string.stats_settings), StatIcons.GEAR, context.getColorFromAttr(R.attr.primaryColor),
            problems.size.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.tracking_problems, it, it) }, faded = true) {
            findNavController().navigate(R.id.action_global_settingsFragment,
                bundleOf(SettingsFragment.OPEN to if (problems.isNotEmpty()) SettingsFragment.TRACKING else SettingsFragment.STATS))
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
                textSize = if (home) Style.TEXT_TITLE else Style.TEXT_TITLE
                setTextColor(ink)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (value != null) addView(TextView(context, null, 0, R.style.TextSmall).apply {
                text = value
                textSize = Style.TEXT_BODY
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
            val text = context.statAmount(stat, today, day)
                ?: day?.face(stat.id)?.let { faceName(stat, it) }
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
