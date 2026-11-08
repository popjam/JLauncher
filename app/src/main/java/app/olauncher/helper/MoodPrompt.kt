package app.olauncher.helper

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
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
     * The title is the shown stat's display text, if it has one. [onVoice], if given, records a voice
     * note and hands its words to the function it's given, to add to the thoughts.
     */
    fun bind(
        root: View,
        data: MoodStore.Data,
        today: MoodStore.Day?,
        yesterday: Boolean = false,
        onVoice: ((addText: (String) -> Unit) -> Unit)? = null,
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
                        color = stat.faceColor(context, face)
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
            if (anyLabels) bindFaceLabels(labelRow, stat.labels.ifEmpty { List(faces.size) { "" } }, alphas)
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

        // The day's note so far, so answering keeps it rather than replacing it
        note.setText(today?.note.orEmpty())
        val voice = root.findViewById<TextView>(R.id.moodPromptVoice)
        voice.isVisible = onVoice != null
        val ink = note.currentTextColor
        voice.setCompoundDrawablesRelativeWithIntrinsicBounds(app.olauncher.ui.StatIcons.drawable(app.olauncher.ui.StatIcons.MIC, ink, 14.dpToPx()), null, null, null)
        voice.setOnClickListener {
            onVoice?.invoke { text ->
                val now = note.text?.toString().orEmpty().trim()
                note.setText(if (now.isEmpty()) text else now + "\n" + text)
                note.setSelection(note.text.length)
            }
        }
        liftAboveKeyboard(root, note)
        confirm.setOnClickListener { if (stats.all { it.id in answers }) finish(answers.toMap(), note.text?.toString()) }
        root.findViewById<View>(R.id.moodPromptClose).setOnClickListener { finish(null, null) }
        render()
    }

    private fun View.setPadding(px: Int) = setPadding(px, px, px, px)

    /**
     * While the thoughts box is being typed in, the prompt sits just above the keyboard rather than in
     * the middle of the screen behind it, so what's written can be seen. The window resizes for the
     * keyboard where Android does that, and otherwise the space the keyboard covers is padded out.
     */
    private fun liftAboveKeyboard(root: View, note: EditText) {
        val box = (root as? FrameLayout)?.getChildAt(0) ?: return
        val pad = 32.dpToPx()
        val activity = root.context.activity()
        val defaultMode = activity?.window?.attributes?.softInputMode
        val frame = Rect()
        val place = IntArray(2)
        fun layout() {
            if (!root.isShown) return
            val typing = note.hasFocus()
            root.getWindowVisibleDisplayFrame(frame)
            root.getLocationOnScreen(place)
            val covered = (place[1] + root.height - frame.bottom).coerceAtLeast(0)
            val bottom = if (typing) covered + 12.dpToPx() else pad
            val top = if (typing) 12.dpToPx() else pad
            val gravity = if (typing) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
            val params = box.layoutParams as FrameLayout.LayoutParams
            if (root.paddingBottom != bottom || root.paddingTop != top || params.gravity != gravity) {
                root.setPadding(pad, top, pad, bottom)
                params.gravity = gravity
                box.layoutParams = params
            }
        }
        note.setOnFocusChangeListener { _, focused ->
            activity?.window?.let { window ->
                if (focused) window.setSoftInputMode((defaultMode ?: 0) and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE.inv() and
                    WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv() or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED)
                else if (defaultMode != null) window.setSoftInputMode(defaultMode)
            }
            root.post { layout() }
        }
        // Once per prompt view, however many times it's bound
        if (root.getTag(R.id.moodPromptNote) == null) {
            root.setTag(R.id.moodPromptNote, true)
            root.viewTreeObserver.addOnGlobalLayoutListener { layout() }
        }
        layout()
    }

    private tailrec fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }
}
