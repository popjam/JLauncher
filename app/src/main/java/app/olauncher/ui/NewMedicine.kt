package app.olauncher.ui

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.helper.dpToPx

/**
 * Adding a stat: first whether it's something to rate or a medicine. A medicine only needs its name and,
 * if it comes in doses, what they are; it's then asked about in the check-in, taken or not (and how much).
 * Anything finer is in the full editor, holding the stat.
 */
object NewMedicine {

    fun pick(context: Context, store: MoodStore, onDone: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(R.string.new_stat)
            .setItems(arrayOf(context.getString(R.string.new_kind_stat), context.getString(R.string.new_kind_medicine))) { _, which ->
                if (which == 0) StatEditor.show(context, store, null) { onDone() } else show(context, store, onDone)
            }
            .show()
    }

    private fun show(context: Context, store: MoodStore, onDone: () -> Unit) {
        val name = EditText(context).apply {
            hint = context.getString(R.string.medicine_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
        }
        val doses = EditText(context).apply {
            hint = context.getString(R.string.medicine_doses_hint)
            setSingleLine()
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dpToPx(), 8.dpToPx(), 24.dpToPx(), 0)
            addView(name)
            addView(doses)
            addView(TextView(context).apply {
                setText(R.string.medicine_doses_note)
                textSize = 13f
                alpha = 0.6f
                setPadding(4.dpToPx(), 6.dpToPx(), 4.dpToPx(), 0)
            })
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.new_kind_medicine)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.add) { _, _ ->
                val title = name.text.toString().trim()
                if (title.isEmpty()) return@setPositiveButton
                val list = doses.text.toString().split(',', '/', ';').map { it.trim() }.filter { it.isNotEmpty() }.take(4)
                add(context, store, title, list)
                Toast.makeText(context, context.getString(R.string.medicine_added, title), Toast.LENGTH_SHORT).show()
                onDone()
            }
            .show()
    }

    /**
     * A medicine stat: taken or skipped, or skipped and each dose. Scales come in 2, 3 or 5, so three
     * doses get a fourth for more than that.
     */
    fun add(context: Context, store: MoodStore, name: String, doses: List<String>) {
        val skipped = context.getString(R.string.medicine_skipped)
        val labels = when (doses.size) {
            0 -> listOf(skipped, context.getString(R.string.medicine_taken))
            1 -> listOf(skipped, doses[0])
            2 -> listOf(skipped) + doses
            3 -> listOf(skipped) + doses + context.getString(R.string.medicine_more)
            else -> listOf(skipped) + doses.take(4)
        }
        val used = store.load().stats.map { it.color }
        val color = COLORS.firstOrNull { it !in used } ?: COLORS[used.size % COLORS.size]
        store.addStat(name, color, daily = true, scale = labels.size, question = context.getString(R.string.medicine_question, name),
            labels = labels, inverted = false, phrases = List(labels.size) { i ->
                when {
                    i == 0 -> context.getString(R.string.medicine_phrase_skipped, name)
                    doses.isEmpty() -> context.getString(R.string.medicine_phrase_taken, name)
                    else -> context.getString(R.string.medicine_phrase, labels[i], name)
                }
            },
            medicine = true, nextDay = false)
    }

    private val COLORS = listOf(0xFF9575CD, 0xFF4DB6AC, 0xFF64B5F6, 0xFFF06292, 0xFFFFB74D, 0xFFAED581).map { it.toInt() }
}
