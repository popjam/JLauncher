package app.olauncher.ui

import android.app.DatePickerDialog
import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.data.Supplement
import app.olauncher.data.Supplements
import app.olauncher.helper.dpToPx
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Adding a supplement (its name, amount and unit, schedule and the day it starts) or changing one: a
 * change of amount or schedule applies from the day picked, earlier days keeping what they had. A
 * stopped one can be started again; any can be deleted.
 */
object SupplementDialog {

    /** [supplement] null adds one starting [day] (today, or the check-in's day); [onDone] follows any change */
    fun show(context: Context, store: MoodStore, supplement: Supplement?, day: LocalDate = LocalDate.now(), onDone: () -> Unit) {
        val today = LocalDate.now()
        val editing = supplement != null
        val latest = supplement?.changes?.lastOrNull()
        val current = latest ?: Supplement.Change(day, 0f, Supplement.Schedule(Supplement.Kind.DAILY))
        val stopped = latest?.stopped == true
        // Changes go from a day no earlier than the last one, and never later than today
        val earliest = latest?.from?.takeIf { editing }
        var from = if (editing) maxOf(today, earliest ?: today).let { minOf(it, today) } else minOf(day, today)
        var kind = current.schedule.kind
        var weekdays = current.schedule.weekdays.ifEmpty { WORKDAYS }

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dpToPx(), 8.dpToPx(), 24.dpToPx(), 0)
        }
        fun label(text: Int) = TextView(context).apply {
            setText(text)
            textSize = Style.TEXT_SMALL
            alpha = 0.6f
            setPadding(4.dpToPx(), 10.dpToPx(), 4.dpToPx(), 0)
        }
        fun field(type: Int, value: String) = EditText(context).apply {
            inputType = type
            setSingleLine()
            setText(value)
        }
        fun pair(first: View, second: View) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 16.dpToPx() })
            addView(second, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        fun column(labelText: Int, view: View) = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(labelText))
            addView(view)
        }

        val name = field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, supplement?.name.orEmpty())
        val amount = field(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            current.amount.takeIf { it > 0f }?.let { Supplements.amountText(it) }.orEmpty())
        val unit = field(InputType.TYPE_CLASS_TEXT, supplement?.unit.orEmpty())
        val cycleOn = field(InputType.TYPE_CLASS_NUMBER, "")
        val cycleOff = field(InputType.TYPE_CLASS_NUMBER, "")
        val onLabel = label(R.string.supplement_days_on)
        val offLabel = label(R.string.supplement_days_off)
        val cycle = pair(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(onLabel)
            addView(cycleOn)
        }, LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(offLabel)
            addView(cycleOff)
        })
        // Each cycle keeps its own numbers while the other is picked
        val cycles = mutableMapOf(Supplement.Kind.DAYS to (5 to 2), Supplement.Kind.WEEKS to (1 to 1))
        if (current.schedule.kind == Supplement.Kind.DAYS || current.schedule.kind == Supplement.Kind.WEEKS) {
            cycles[current.schedule.kind] = current.schedule.on to current.schedule.off
        }
        val kinds = FlowLayout(context)
        val days = FlowLayout(context)
        val dateText = TextView(context).apply {
            textSize = Style.TEXT_BODY
        }.also { AppDialog.box(it) }

        box.addView(column(R.string.supplement_name, name))
        box.addView(pair(column(R.string.supplement_amount, amount), column(R.string.supplement_unit, unit)))
        box.addView(label(R.string.supplement_schedule))
        box.addView(kinds, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 6.dpToPx() })
        box.addView(cycle)
        box.addView(days, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 8.dpToPx() })
        box.addView(label(if (editing) R.string.supplement_changes_from else R.string.supplement_starts))
        box.addView(dateText)

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16.dpToPx(), 0, 12.dpToPx())
        }
        box.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val dialog = AppDialog(context)
            .setTitle(supplement?.name ?: context.getString(R.string.supplement_new))
            .setView(ScrollView(context).apply { addView(box) })
            .create()

        fun currentCycle() = if (kind == Supplement.Kind.DAYS || kind == Supplement.Kind.WEEKS)
            (cycleOn.text.toString().toIntOrNull() ?: 0) to (cycleOff.text.toString().toIntOrNull() ?: -1) else 1 to 0
        fun schedule(): Supplement.Schedule {
            val (on, off) = currentCycle()
            return when (kind) {
                Supplement.Kind.DAILY -> Supplement.Schedule(Supplement.Kind.DAILY)
                Supplement.Kind.DAYS, Supplement.Kind.WEEKS -> Supplement.Schedule(kind, on, off)
                Supplement.Kind.WEEKDAYS -> Supplement.Schedule(Supplement.Kind.WEEKDAYS, weekdays = weekdays)
            }
        }
        fun amountValue() = amount.text.toString().replace(',', '.').toFloatOrNull()?.takeIf { it > 0f && it.isFinite() && it < 1e7f }

        lateinit var save: TextView
        lateinit var startAgain: TextView
        fun refresh() {
            val ok = name.text.isNotBlank() && amountValue() != null && schedule().isValid
            // A stopped one's Save keeps only a new name or unit: a new amount or schedule is Start again
            val saves = ok && (!stopped || (amountValue() == current.amount && schedule() == current.schedule))
            save.isEnabled = saves
            save.alpha = if (saves) 1f else 0.35f
            if (stopped) {
                startAgain.isEnabled = ok
                startAgain.alpha = if (ok) 1f else 0.35f
            }
        }

        fun chip(text: String, on: Boolean, onTap: () -> Unit) = TextView(context).apply {
            this.text = text
            textSize = Style.TEXT_SMALL
            setBackgroundResource(R.drawable.bg_stat_chip)
            val side = if (text.length == 1) 10.dpToPx() else 12.dpToPx()
            setPadding(side, 6.dpToPx(), side, 6.dpToPx())
            alpha = if (on) 1f else 0.45f
            if (on) setTypeface(null, Typeface.BOLD)
            setOnClickListener { onTap() }
        }
        lateinit var drawSchedule: () -> Unit
        drawSchedule = {
            kinds.removeAllViews()
            listOf(
                Supplement.Kind.DAILY to R.string.supplement_every_day, Supplement.Kind.DAYS to R.string.supplement_days_on_off,
                Supplement.Kind.WEEKS to R.string.supplement_weeks_on_off, Supplement.Kind.WEEKDAYS to R.string.supplement_weekdays,
            ).forEach { (k, text) ->
                kinds.addView(chip(context.getString(text), k == kind) {
                    if (k != kind) {
                        // Keep what was typed for the cycle being left
                        if (kind == Supplement.Kind.DAYS || kind == Supplement.Kind.WEEKS) cycles[kind] = currentCycle()
                        kind = k
                        drawSchedule()
                    }
                })
            }
            val isCycle = kind == Supplement.Kind.DAYS || kind == Supplement.Kind.WEEKS
            cycle.isVisible = isCycle
            if (isCycle) {
                val (on, off) = cycles[kind]!!
                cycleOn.setText(on.takeIf { it > 0 }?.toString().orEmpty())
                cycleOff.setText(off.takeIf { it >= 0 }?.toString().orEmpty())
                onLabel.setText(if (kind == Supplement.Kind.DAYS) R.string.supplement_days_on else R.string.supplement_weeks_on)
                offLabel.setText(if (kind == Supplement.Kind.DAYS) R.string.supplement_days_off else R.string.supplement_weeks_off)
            }
            days.isVisible = kind == Supplement.Kind.WEEKDAYS
            days.removeAllViews()
            DayOfWeek.values().forEach { d ->
                days.addView(chip(d.getDisplayName(TextStyle.NARROW, Locale.getDefault()), d in weekdays) {
                    weekdays = if (d in weekdays) weekdays - d else weekdays + d
                    drawSchedule()
                })
            }
            refresh()
        }
        fun drawDate() {
            val short = from.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
            dateText.text = if (from == today) context.getString(R.string.supplement_today_date, short)
            else from.format(DateTimeFormatter.ofPattern(if (from.year == today.year) "EEE d MMM" else "EEE d MMM yyyy", Locale.getDefault()))
        }
        dateText.setOnClickListener {
            DatePickerDialog(context, { _, y, m, d -> from = LocalDate.of(y, m + 1, d); drawDate() }, from.year, from.monthValue - 1, from.dayOfMonth).apply {
                fun millis(date: LocalDate) = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                datePicker.maxDate = millis(today) + 1
                earliest?.let { datePicker.minDate = millis(minOf(it, today)) }
            }.show()
        }

        // The popups' own buttons, laid out here since there are up to four
        fun button(text: Int, color: Int? = null, boxed: Boolean = false, onTap: () -> Unit) = AppDialog.button(context, text, boxed, color, onTap)
        fun finish() {
            dialog.dismiss()
            onDone()
        }
        if (supplement != null) {
            startAgain = button(if (stopped) R.string.supplement_start_again else R.string.supplement_stop) {
                if (stopped) {
                    val value = amountValue() ?: return@button
                    store.editSupplement(supplement.id) {
                        Supplements.change(it, from, value, schedule()).copy(name = cleanName(name, it.name), unit = MoodStore.sanitizeUnit(unit.text.toString()))
                    }
                    store.markSupplementsChanged(from)
                } else store.editSupplement(supplement.id) { Supplements.stop(it, from) }
                finish()
            }
            buttons.addView(startAgain)
            buttons.addView(button(R.string.action_delete, Style.DANGER_SOFT) {
                AppDialog.confirmDelete(context, context.getString(R.string.delete_what, supplement.name), context.getString(R.string.supplement_delete_detail)) {
                    store.deleteSupplement(supplement.id)
                    finish()
                }
            })
        }
        buttons.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        buttons.addView(button(R.string.cancel) { dialog.dismiss() })
        save = button(R.string.save, boxed = true) {
            val value = amountValue() ?: return@button
            if (supplement == null) {
                store.addSupplement(name.text.toString(), unit.text.toString(), value, schedule(), from)
                store.markSupplementsChanged(from)
            } else store.editSupplement(supplement.id) {
                val renamed = it.copy(name = cleanName(name, it.name), unit = MoodStore.sanitizeUnit(unit.text.toString()))
                if (stopped) renamed else Supplements.change(renamed, from, value, schedule())
            }
            finish()
        }
        buttons.addView(save)

        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refresh()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        drawSchedule()
        drawDate()
        listOf(name, amount, cycleOn, cycleOff).forEach { it.addTextChangedListener(watcher) }
        refresh()
        dialog.show()
    }

    /** A new amount for [supplement] from [day], asked on its own (from the check-in) */
    fun askAmount(context: Context, store: MoodStore, supplement: Supplement, day: LocalDate, onDone: () -> Unit) {
        val change = supplement.changeOn(day) ?: return
        val field = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine()
            setText(Supplements.amountText(change.amount))
            setSelection(text.length)
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24.dpToPx(), 8.dpToPx(), 24.dpToPx(), 0)
            addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (supplement.unit.isNotEmpty()) addView(TextView(context).apply {
                text = supplement.unit
                textSize = Style.TEXT_BODY
                setPadding(12.dpToPx(), 0, 0, 0)
            })
        }
        fun value() = field.text.toString().replace(',', '.').toFloatOrNull()?.takeIf { it > 0f && it.isFinite() && it < 1e7f }
        val dialog = AppDialog(context)
            .setTitle(context.getString(R.string.supplement_amount_title, supplement.name))
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val value = value() ?: return@setPositiveButton
                store.editSupplement(supplement.id) { Supplements.change(it, day, value, it.changeOn(day)?.schedule ?: change.schedule) }
                onDone()
            }
            .show()
        // Save only with a real amount
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        field.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { save.isEnabled = value() != null }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun cleanName(field: EditText, old: String) = MoodStore.sanitizeName(field.text.toString()).ifEmpty { old }

    private val WORKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    // --- Words for supplements, shared by the page and the check-in ---

    /** "300 mg", or just "300" without a unit */
    fun amountText(s: Supplement, amount: Float) = Supplements.amountText(amount).let { if (s.unit.isEmpty()) it else "$it ${s.unit}" }

    /** "Every day", "5 days on, 2 off", "8 weeks on, 4 off", "Weekdays", "Weekends", or "Mon, Wed, Fri" */
    fun scheduleText(context: Context, schedule: Supplement.Schedule): String = when (schedule.kind) {
        Supplement.Kind.DAILY -> context.getString(R.string.supplement_every_day)
        Supplement.Kind.DAYS -> context.resources.getQuantityString(R.plurals.supplement_days_cycle, schedule.on, schedule.on, schedule.off)
        Supplement.Kind.WEEKS -> context.resources.getQuantityString(R.plurals.supplement_weeks_cycle, schedule.on, schedule.on, schedule.off)
        Supplement.Kind.WEEKDAYS -> when (schedule.weekdays) {
            DayOfWeek.values().toSet() -> context.getString(R.string.supplement_every_day)
            WORKDAYS -> context.getString(R.string.supplement_weekdays)
            setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> context.getString(R.string.supplement_weekends)
            else -> schedule.weekdays.sorted().joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
        }
    }
}
