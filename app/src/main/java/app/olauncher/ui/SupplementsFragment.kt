package app.olauncher.ui

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.core.view.updatePadding
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.data.Supplement
import app.olauncher.data.Supplements
import app.olauncher.helper.StatInfo
import app.olauncher.helper.allStats
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * Every supplement on one page: what's being taken and on what schedule, and for the one picked from the
 * list, its days (tap one to mark it taken or missed), its changes, and how the other stats go with it:
 * on days taken against not, since each change against before, and by amount.
 */
class SupplementsFragment : CardPageFragment() {

    private lateinit var cardList: LinearLayout
    override val cards: LinearLayout get() = cardList

    private lateinit var store: MoodStore
    private var data = MoodStore.Data()
    private var stats = emptyList<StatInfo>()

    /** The supplement the cards below the list show */
    private var selected: String? = null

    /** The month the Days card shows */
    private var month: YearMonth = YearMonth.now()

    /** Which change Before vs since shows, counted from the newest */
    private var changeIndex = 0

    /** The stat By amount shows */
    private var amountStat: String = MoodStore.MOOD

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        store = MoodStore(context)
        selected = savedInstanceState?.getString(SELECTED) ?: arguments?.getString(SELECTED)
        savedInstanceState?.getString(MONTH)?.let { runCatching { month = YearMonth.parse(it) } }
        cardList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            updatePadding(bottom = 64.dpToPx())
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            updatePadding(top = 48.dpToPx())
            addView(pageHeader(context, StatIcons.TABLET, getString(R.string.supplements)) { findNavController().popBackStack() })
            addView(cardList)
        }
        val page = FrameLayout(context).apply {
            setBackgroundColor(context.getColorFromAttr(R.attr.primaryShadeDarkColor))
            isClickable = true
            addView(ScrollView(context).apply {
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                addView(content)
            })
        }
        return PullDismissLayout(context).apply {
            addView(page)
            onDismiss = { if (isAdded) findNavController().popBackStack() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(SELECTED, selected)
        outState.putString(MONTH, month.toString())
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private val today get() = LocalDate.now()

    private fun render() {
        if (view == null) return
        data = store.load()
        stats = requireContext().allStats(data)
        cardList.removeAllViews()
        val list = ordered()
        if (list.none { it.id == selected }) {
            selected = list.firstOrNull()?.id
            changeIndex = 0
        }
        takingCard(list)
        val supplement = list.firstOrNull { it.id == selected } ?: return
        daysCard(supplement)
        changesCard(supplement)
        takenVsNotCard(supplement)
        beforeSinceCard(supplement)
        byAmountCard(supplement)
    }

    /** Those being taken first, in the order added, then the stopped ones */
    private fun ordered(): List<Supplement> = data.supplements.sortedBy { if (it.isStopped(today)) 1 else 0 }

    private fun select(id: String) {
        if (id == selected) return
        selected = id
        changeIndex = 0
        month = YearMonth.now()
        render()
    }

    private fun edit(supplement: Supplement?) = SupplementDialog.show(requireContext(), store, supplement) { render() }

    private val ripple by lazy {
        TypedValue().also { requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }.resourceId
    }

    private fun dot(color: Int, size: Int = 10) = View(requireContext()).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        layoutParams = LinearLayout.LayoutParams(size.dpToPx(), size.dpToPx()).apply { marginEnd = 14.dpToPx() }
    }

    // --- Taking ---

    /** The supplements, each with its amount, schedule and today; tapping one shows it below, ✎ changes it */
    private fun takingCard(list: List<Supplement>) {
        val context = requireContext()
        val card = Card(getString(R.string.supplement_taking))
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        list.forEach { s ->
            val stopped = s.isStopped(today)
            val change = s.changeOn(today) ?: s.changes.lastOrNull() ?: return@forEach
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(10.dpToPx(), 9.dpToPx(), 4.dpToPx(), 9.dpToPx())
                if (s.id == selected) background = GradientDrawable().apply {
                    cornerRadius = Style.radiusSmall
                    setColor(faint(22))
                } else setBackgroundResource(ripple)
                alpha = if (stopped) 0.5f else 1f
                setOnClickListener { select(s.id) }
            }
            row.addView(dot(if (stopped) faint(90) else s.color))
            row.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(card.textView(Style.TEXT_BODY, 255).apply {
                    text = s.name
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(card.textView(Style.TEXT_SMALL, 150).apply {
                    text = if (stopped) getString(R.string.supplement_stopped_on, shortDate(stoppedOn(s)))
                    else getString(R.string.supplement_line, SupplementDialog.amountText(s, change.amount), SupplementDialog.scheduleText(context, change.schedule))
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                    updatePadding(top = 2.dpToPx())
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (!stopped) row.addView(card.textView(Style.TEXT_SMALL, 160).apply {
                text = todayText(s)
                setPadding(8.dpToPx(), 0, 0, 0)
            })
            row.addView(card.textView(Style.TEXT_TITLE, 160).apply {
                text = "✎"
                contentDescription = getString(R.string.edit)
                setPadding(14.dpToPx(), 6.dpToPx(), 8.dpToPx(), 6.dpToPx())
                setOnClickListener { edit(s) }
            })
            rows.addView(row)
        }
        rows.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dpToPx(), 11.dpToPx(), 4.dpToPx(), 11.dpToPx())
            setBackgroundResource(ripple)
            addView(card.textView(Style.TEXT_BODY, 150).apply {
                text = "+"
                setPadding(0, 0, 14.dpToPx(), 0)
            })
            addView(card.textView(Style.TEXT_BODY, 170).apply { setText(R.string.supplement_add) })
            setOnClickListener { edit(null) }
        })
        card.addBody(rows)
    }

    /** "Today", "Off today", "Week 3 of 8" or "Off · week 2 of 4" */
    private fun todayText(s: Supplement): String {
        s.weekOf(today)?.let { (week, of, on) ->
            // A day marked by hand says so plainly
            if (s.fixes[today] == null) return getString(if (on) R.string.supplement_week_of else R.string.supplement_off_week_of, week, of)
        }
        return getString(if (s.isTaken(today)) R.string.supplement_on_today else R.string.supplement_off_today)
    }

    /** When it stopped: the first of the stops in a row it's on now (starting again and stopping the same day leaves two) */
    private fun stoppedOn(s: Supplement): LocalDate {
        val past = s.changes.filter { it.from <= today }
        val lastTaken = past.indexOfLast { !it.stopped }
        return past.getOrNull(lastTaken + 1)?.takeIf { it.stopped }?.from ?: today
    }

    private fun shortDate(date: LocalDate): String =
        date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "d MMM" else "d MMM yyyy", Locale.getDefault()))

    // --- Days ---

    /** A month of days: taken in its colour, off faint, missed dashed, the plan ahead faded; tap a day to mark it */
    private fun daysCard(s: Supplement) {
        val context = requireContext()
        val card = Card(getString(R.string.supplement_days))
        val first = YearMonth.from(s.firstStart ?: today)
        val last = YearMonth.now().plusMonths(1)
        month = month.coerceIn(minOf(first, last), last)
        card.stepper(month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + if (month.year != today.year) " ${month.year}" else "",
            month > first, month < last, 30) { by ->
            month = month.plusMonths(by.toLong())
            render()
        }
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        grid.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            java.time.DayOfWeek.values().forEach { d ->
                addView(card.textView(Style.TEXT_SMALL, 130).apply {
                    text = d.getDisplayName(TextStyle.NARROW, Locale.getDefault())
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 6.dpToPx() })
        val start = s.firstStart
        val firstDay = month.atDay(1)
        val blanks = firstDay.dayOfWeek.value - 1
        val cells = blanks + month.lengthOfMonth()
        var row: LinearLayout? = null
        for (i in 0 until ((cells + 6) / 7) * 7) {
            if (i % 7 == 0) row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }.also {
                grid.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 38.dpToPx()).apply { bottomMargin = 5.dpToPx() })
            }
            val date = firstDay.plusDays((i - blanks).toLong()).takeIf { i >= blanks && i < cells }
            val cell = TextView(context, null, 0, R.style.TextSmall).apply {
                gravity = Gravity.CENTER
                textSize = Style.TEXT_SMALL
            }
            if (date != null) {
                cell.text = date.dayOfMonth.toString()
                val started = start != null && date >= start
                val future = date > today
                val taken = started && s.isTaken(date)
                val missed = started && s.isMissed(date)
                val corner = 8.dpToPx().toFloat()
                cell.background = when {
                    !started -> null
                    missed -> GradientDrawable().apply {
                        cornerRadius = corner
                        setStroke(2.dpToPx(), s.color, 4.dpToPx().toFloat(), 3.dpToPx().toFloat())
                    }
                    taken -> GradientDrawable().apply {
                        cornerRadius = corner
                        setColor(s.color)
                    }
                    else -> GradientDrawable().apply {
                        cornerRadius = corner
                        setColor(faint(16))
                    }
                }
                cell.setTextColor(if (taken && !missed) Style.INK_ON_LIGHT else faint(if (started) 170 else 70))
                if (future) cell.alpha = 0.35f
                if (date == today) cell.setTypeface(null, android.graphics.Typeface.BOLD)
                cell.contentDescription = listOf(dateText(date), getString(when {
                    missed -> R.string.supplement_missed
                    taken -> R.string.supplement_taken
                    else -> R.string.supplement_off
                })).joinToString(" · ")
                // Today and days gone by while it's being taken can be marked
                if (started && !future && s.isActive(date)) cell.setOnClickListener {
                    store.editSupplement(s.id) { Supplements.flip(it, date) }
                    render()
                }
            }
            row!!.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = 2.dpToPx()
                marginEnd = 2.dpToPx()
            })
        }
        card.addBody(grid)
        card.keyItem(s.color, getString(R.string.supplement_taken))
        card.keyItem(faint(40), getString(R.string.supplement_off))
        card.keyItem(s.color, getString(R.string.supplement_missed), ring = true)
    }

    // --- Changes ---

    /** Each change, newest first: started, a new amount or schedule, stopped, started again */
    private fun changesCard(s: Supplement) {
        val context = requireContext()
        val card = Card(getString(R.string.supplement_changes))
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        s.changes.indices.reversed().forEach { i ->
            val change = s.changes[i]
            val before = s.changes.getOrNull(i - 1)
            // A stop after a stop (started again and stopped the same day) adds nothing to say
            if (change.stopped && before?.stopped == true) return@forEach
            val amount = SupplementDialog.amountText(s, change.amount)
            val schedule = SupplementDialog.scheduleText(context, change.schedule)
            val text = when {
                change.stopped -> getString(R.string.supplement_stopped)
                before == null -> getString(R.string.supplement_started, amount, schedule)
                before.stopped -> getString(R.string.supplement_started_again, amount, schedule)
                else -> listOfNotNull(
                    if (before.amount != change.amount) getString(R.string.supplement_was, amount, SupplementDialog.amountText(s, before.amount)) else null,
                    if (before.schedule != change.schedule) getString(R.string.supplement_was, schedule, SupplementDialog.scheduleText(context, before.schedule)) else null,
                ).joinToString(" · ").ifEmpty { getString(R.string.supplement_line, amount, schedule) }
            }
            rows.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 6.dpToPx(), 0, 6.dpToPx())
                addView(card.textView(Style.TEXT_SMALL, 140).apply { this.text = shortDate(change.from) },
                    LinearLayout.LayoutParams(78.dpToPx(), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(card.textView(Style.TEXT_BODY, 235).apply { this.text = text }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
        card.addBody(rows)
    }

    // --- How the other stats go with it ---

    /** The stats set against it: everything rated with faces, Mood first; not the medicines or counted ones */
    private fun compared(): List<StatInfo> = stats.filter { !it.medicine }.sortedBy { if (it.id == MoodStore.MOOD) 0 else 1 }

    /** [stat]'s score (1 to 5, 5 best) on [date], if it has one */
    private fun score(stat: StatInfo, date: LocalDate): Float? =
        data.days[date.toString()]?.face(stat.id)?.let { MoodStore.score(it, stat.inverted).toFloat() }

    private class Row(val name: String, val color: Int, val now: Float, val nowDays: Int, val was: Float, val wasDays: Int)

    /** Each stat's average on [now] days against [was] days, where both have enough */
    private fun rows(now: List<LocalDate>, was: List<LocalDate>): List<Row> = compared().mapNotNull { stat ->
        val a = now.mapNotNull { score(stat, it) }
        val b = was.mapNotNull { score(stat, it) }
        if (a.size < MIN_DAYS || b.size < MIN_DAYS) null
        else Row(stat.name, stat.color, a.average().toFloat(), a.size, b.average().toFloat(), b.size)
    }.sortedWith(compareBy<Row> { it.name != moodName }.thenByDescending { abs(it.now - it.was) })

    private val moodName by lazy { getString(R.string.mood) }

    private fun signed(value: Float) = (if (value >= 0) "+" else "−") + String.format(Locale.getDefault(), "%.1f", abs(value))

    /**
     * A row per stat: a ring for the [was] side and a dot in [color] for the [now] side, on a line from
     * 1 to 5, with the difference after it.
     */
    private fun dotRows(card: Card, rows: List<Row>, color: Int, detail: Int) {
        card.describe = { key ->
            val row = rows[key as Int]
            getString(detail, row.name, decimal(row.now), row.nowDays, decimal(row.was), row.wasDays)
        }
        card.draw { s ->
            val nameSize = s.sp(13.5f)
            val textPaint = TextPaint().apply { textSize = nameSize }
            val left = minOf(rows.maxOf { s.textWidth(it.name, nameSize) } + s.dp(34f), s.width / 3)
            val diffWidth = s.dp(40f)
            val right = s.width - diffWidth - s.dp(10f)
            fun x(value: Float) = left + (value - 1f) / 4f * (right - left)
            val rowHeight = s.dp(30f)
            val bottom = rows.size * rowHeight
            (1..5).forEach { tick ->
                s.line(x(tick.toFloat()), 0f, x(tick.toFloat()), bottom, faint(35), s.dp(1.5f))
                s.text(tick.toString(), x(tick.toFloat()), bottom + s.dp(18f), faint(140), s.sp(12.5f))
            }
            rows.forEachIndexed { i, row ->
                val y = s.dp(14f) + i * rowHeight
                s.circle(s.dp(6f), y, s.dp(5.5f), row.color)
                val name = TextUtils.ellipsize(row.name, textPaint, left - s.dp(30f), TextUtils.TruncateAt.END).toString()
                s.text(name, s.dp(20f), y + nameSize * 0.35f, faint(220), nameSize, Paint.Align.LEFT)
                s.line(x(row.was), y, x(row.now), y, faint(110), s.dp(3.5f))
                s.ring(x(row.was), y, s.dp(7f), faint(170), s.dp(2.5f))
                s.circle(x(row.now), y, s.dp(8f), color)
                s.text(signed(row.now - row.was), s.width, y + s.sp(12.5f) * 0.35f, faint(170), s.sp(12.5f), Paint.Align.RIGHT)
                val area = RectF(0f, y - rowHeight / 2, s.width, y + rowHeight / 2)
                s.target(i, area, area, s.dp(10f))
            }
            s.height = bottom + s.dp(24f)
        }
    }

    /** Each stat on days it was taken against days it wasn't, since it was first started */
    private fun takenVsNotCard(s: Supplement) {
        val card = Card(getString(R.string.supplement_taken_vs_not))
        val (taken, not) = Supplements.takenAndNot(s, today)
        card.subtitle(getString(R.string.supplement_taken_count, taken.size, not.size))
        val rows = rows(taken, not)
        if (rows.isEmpty()) return card.showNote(getString(R.string.supplement_not_enough))
        card.keyItem(faint(170), getString(R.string.supplement_not_taken), ring = true)
        card.keyItem(s.color, getString(R.string.supplement_taken))
        dotRows(card, rows, s.color, R.string.supplement_vs_detail)
    }

    /** Each stat since a change against the two weeks before it, for the start and the last few changes */
    private fun beforeSinceCard(s: Supplement) {
        val card = Card(getString(R.string.supplement_before_since))
        val points = s.changes.filter { !it.stopped && it.from <= today }.takeLast(MAX_CHANGES)
        if (points.isEmpty()) return card.showNote(getString(R.string.supplement_not_enough))
        // Counted back from the newest, which is picked to start with
        changeIndex = changeIndex.coerceIn(0, points.lastIndex)
        val picked = points.lastIndex - changeIndex
        val context = requireContext()
        val labels = points.map { change ->
            val index = s.changes.indexOf(change)
            val before = s.changes.getOrNull(index - 1)
            val date = shortDate(change.from)
            when {
                before == null || before.stopped -> getString(R.string.supplement_started_seg, date)
                before.amount != change.amount -> getString(R.string.supplement_change_seg, SupplementDialog.amountText(s, change.amount), date)
                else -> getString(R.string.supplement_change_seg, SupplementDialog.scheduleText(context, change.schedule), date)
            }
        }
        if (points.size > 1) {
            card.chipRow(labels.map { it to s.color }, picked) {
                changeIndex = points.lastIndex - it
                render()
            }
            // The chips go above the drawing
            card.root.removeView(card.chips)
            card.root.addView(card.chips, card.root.indexOfChild(card.scene), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 12.dpToPx()
            })
        } else card.subtitle(labels.first())
        val (before, since) = Supplements.beforeAndSince(s, points[picked], today)
        val rows = rows(since, before)
        if (rows.isEmpty()) {
            card.note.visibility = View.VISIBLE
            card.note.text = getString(R.string.supplement_not_enough)
            card.scene.visibility = View.GONE
            return
        }
        card.keyItem(faint(170), getString(R.string.supplement_two_weeks_before), ring = true)
        card.keyItem(s.color, getString(R.string.supplement_since))
        dotRows(card, rows, s.color, R.string.supplement_since_detail)
    }

    /** One stat's average on each amount taken, and on days not taken; once there's been more than one amount */
    private fun byAmountCard(s: Supplement) {
        val (taken, not) = Supplements.takenAndNot(s, today)
        val byAmount = taken.groupBy { s.amountOn(it)!! }
        if (byAmount.size < 2) return
        val choices = compared()
        if (choices.isEmpty()) return
        val stat = choices.firstOrNull { it.id == amountStat } ?: choices.first()
        val card = Card(getString(R.string.supplement_by_amount))
        card.headerEnd.addView(card.textView(Style.TEXT_SMALL, 170).apply {
            text = stat.name + " ▾"
            setPadding(8.dpToPx(), 4.dpToPx(), 0, 4.dpToPx())
            setOnClickListener {
                AppDialog(requireContext())
                    .setItems(choices.map { it.name }.toTypedArray()) { _, i ->
                        amountStat = choices[i].id
                        render()
                    }
                    .show()
            }
        })
        class Bar(val label: String, val average: Float?, val days: Int, val color: Int)
        fun bar(label: String, days: List<LocalDate>, color: Int): Bar {
            val values = days.mapNotNull { score(stat, it) }
            return Bar(label, values.takeIf { it.isNotEmpty() }?.average()?.toFloat(), values.size, color)
        }
        val bars = byAmount.keys.sorted().map { bar(SupplementDialog.amountText(s, it), byAmount[it]!!, s.color) } +
            bar(getString(R.string.supplement_none), not, faint(110))
        if (bars.count { it.average != null } < 2) return card.showNote(getString(R.string.supplement_not_enough))
        card.describe = { key ->
            val b = bars[key as Int]
            getString(R.string.supplement_amount_detail, b.label, b.average?.let { decimal(it) } ?: "–", b.days)
        }
        card.draw { sc ->
            val nameSize = sc.sp(13.5f)
            val left = minOf(bars.maxOf { sc.textWidth(it.label, nameSize) } + sc.dp(14f), sc.width / 3)
            val right = sc.width - sc.dp(36f)
            val rowHeight = sc.dp(30f)
            bars.forEachIndexed { i, b ->
                val y = sc.dp(14f) + i * rowHeight
                sc.text(b.label, 0f, y + nameSize * 0.35f, faint(220), nameSize, Paint.Align.LEFT)
                sc.rect(left, y - sc.dp(5f), right, y + sc.dp(5f), faint(22), sc.dp(5f))
                if (b.average != null) {
                    sc.rect(left, y - sc.dp(5f), left + (right - left) * (b.average / 5f), y + sc.dp(5f), b.color, sc.dp(5f))
                    sc.text(decimal(b.average), sc.width, y + sc.sp(12.5f) * 0.35f, faint(170), sc.sp(12.5f), Paint.Align.RIGHT)
                } else sc.text("–", sc.width, y + sc.sp(12.5f) * 0.35f, faint(120), sc.sp(12.5f), Paint.Align.RIGHT)
                val area = RectF(0f, y - rowHeight / 2, sc.width, y + rowHeight / 2)
                sc.target(i, area, area, sc.dp(10f))
            }
            sc.height = bars.size * rowHeight
        }
    }

    companion object {
        /** The supplement to show first, by id */
        const val SELECTED = "selected"
        private const val MONTH = "month"
        private const val MIN_DAYS = 3
        private const val MAX_CHANGES = 3
    }
}
