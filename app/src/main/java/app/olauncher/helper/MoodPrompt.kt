package app.olauncher.helper

import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.ui.PixelFaceView

object MoodPrompt {

    private const val ADVANCE_DELAY_MS = 250L

    /**
     * Fills a view_mood_prompt layout with the mood and each daily stat, one at a time. Tapping a face
     * only selects it, then moves on to the next unanswered stat, so the thoughts box isn't skipped by
     * accident. Confirm (enabled once every stat has a face) passes the faces by stat id and the note to
     * [onResult]. Closing the prompt passes null for both. Answers already in [today] are filled in.
     * The title is the shown stat's display text, if it has one.
     */
    fun bind(
        root: View,
        data: MoodStore.Data,
        today: MoodStore.Day?,
        yesterday: Boolean = false,
        onResult: (faces: Map<String, Int>?, note: String?) -> Unit,
    ) {
        val context = root.context
        val names = context.resources.getStringArray(R.array.mood_names)
        val row = root.findViewById<LinearLayout>(R.id.moodPromptFaces)
        val labelRow = root.findViewById<LinearLayout>(R.id.moodPromptLabels)
        val note = root.findViewById<EditText>(R.id.moodPromptNote)
        val confirm = root.findViewById<View>(R.id.moodPromptConfirm)
        val nav = root.findViewById<View>(R.id.moodPromptNav)
        val label = root.findViewById<TextView>(R.id.moodPromptStat)
        val prev = root.findViewById<View>(R.id.moodPromptPrev)
        val next = root.findViewById<View>(R.id.moodPromptNext)

        val stats = context.dailyStats(data)
        val answers = mutableMapOf<String, Int>()
        stats.forEach { stat -> today?.face(stat.id)?.let { answers[stat.id] = it } }
        var index = stats.indexOfFirst { it.id !in answers }.coerceAtLeast(0)

        val multiple = stats.size > 1
        val anyLabels = stats.any { it.labels.isNotEmpty() }
        labelRow.isVisible = anyLabels
        nav.isVisible = multiple
        val title = root.findViewById<TextView>(R.id.moodPromptTitle)
        val defaultTitle = context.getString(
            when {
                yesterday -> R.string.how_was_yesterday
                multiple -> R.string.how_was_today
                else -> R.string.how_are_you_feeling
            }
        )
        row.updateLayoutParams<LinearLayout.LayoutParams> { topMargin = (if (multiple) 8 else 24).dpToPx() }

        lateinit var onFace: (statId: String, face: Int) -> Unit
        var advance: Runnable? = null

        fun nextUnanswered(): Int? = (1..stats.size).map { (index + it) % stats.size }.firstOrNull { stats[it].id !in answers }

        fun render() {
            val stat = stats[index]
            val selected = answers[stat.id]
            title.text = stat.question.ifEmpty { defaultTitle }
            label.text = context.getString(R.string.stat_step, stat.name, index + 1, stats.size)
            label.setStatDot(stat.color)

            val faces = stat.faces
            if (row.childCount != faces.size || row.tag != stat.id) {
                row.removeAllViews()
                row.tag = stat.id
                faces.forEach { face ->
                    row.addView(PixelFaceView(context).apply {
                        mood = face
                        contentDescription = names[face]
                        setPadding(6.dpToPx())
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { onFace(stat.id, face) }
                    }, LinearLayout.LayoutParams(0, 52.dpToPx(), 1f))
                }
            }
            val alphas = faces.map { face -> if (selected == null || selected == face) 1f else 0.3f }
            alphas.forEachIndexed { i, alpha -> row.getChildAt(i).alpha = alpha }
            if (anyLabels) bindFaceLabels(labelRow, stat.faceLabels.ifEmpty { List(faces.size) { "" } }, alphas)
            listOf(prev, next).forEach {
                it.isEnabled = multiple
                it.alpha = 0.6f
            }
            val done = stats.all { it.id in answers }
            confirm.isEnabled = done
            confirm.alpha = if (done) 1f else 0.3f
        }

        fun finish(faces: Map<String, Int>?, text: String?) {
            advance?.let { row.removeCallbacks(it) }
            note.clearFocus()
            note.hideKeyboard()
            onResult(faces, text)
        }

        onFace = { statId, face ->
            answers[statId] = face
            render()
            nextUnanswered()?.let { target ->
                advance?.let { row.removeCallbacks(it) }
                advance = Runnable {
                    // Skip if another stat was picked in the meantime
                    if (stats[index].id == statId) {
                        index = target
                        render()
                    }
                }.also { row.postDelayed(it, ADVANCE_DELAY_MS) }
            }
        }
        prev.setOnClickListener { index = (index - 1 + stats.size) % stats.size; render() }
        next.setOnClickListener { index = (index + 1) % stats.size; render() }

        note.setText("")
        confirm.setOnClickListener { if (stats.all { it.id in answers }) finish(answers.toMap(), note.text?.toString()) }
        root.findViewById<View>(R.id.moodPromptClose).setOnClickListener { finish(null, null) }
        render()
    }

    private fun View.setPadding(px: Int) = setPadding(px, px, px, px)
}
