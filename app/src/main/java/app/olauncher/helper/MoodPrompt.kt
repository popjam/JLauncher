package app.olauncher.helper

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.data.Supplements
import app.olauncher.ui.PixelFaceView
import app.olauncher.ui.SupplementDialog
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import java.time.LocalDate

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
        date: LocalDate? = null,
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

        val day = date ?: if (yesterday) LocalDate.now().minusDays(1) else LocalDate.now()
        val store = MoodStore(context)
        val list = root.findViewById<LinearLayout>(R.id.moodPromptList)
        val answers = mutableMapOf<String, Int>()
        // The stats with faces, then supplements when any are being taken that day: whether anything changed
        val faceStats = context.dailyStats(data) + listOfNotNull(supplementStep(context).takeIf {
            Supplements.asked(data.supplements, day) || today?.supplementsChanged != null
        })
        val changesStep = StatInfo(MoodStore.SUPPLEMENT_CHANGES, context.getString(R.string.supplements), SUPPLEMENT_COLOR, 2,
            context.getString(R.string.supplement_what_changed))
        // "Changed" adds a step after it, listing them to change
        fun steps() = if (answers[MoodStore.SUPPLEMENTS] == MoodStore.HAPPY) faceStats + changesStep else faceStats
        fun answered(step: StatInfo) = step.id == MoodStore.SUPPLEMENT_CHANGES || step.id in answers
        faceStats.forEach { stat -> today?.face(stat.id)?.let { answers[stat.id] = it } }
        today?.supplementsChanged?.let { answers[MoodStore.SUPPLEMENTS] = if (it) MoodStore.HAPPY else MoodStore.SAD }
        var index = faceStats.indexOfFirst { it.id !in answers }.coerceAtLeast(0)

        val anyLabels = faceStats.any { it.labels.isNotEmpty() }
        val title = root.findViewById<TextView>(R.id.moodPromptTitle)
        fun multiple() = steps().size > 1
        val defaultTitle = context.getString(
            when {
                yesterday -> R.string.how_was_yesterday
                faceStats.size > 1 -> R.string.how_was_today
                else -> R.string.how_are_you_feeling
            }
        )
        row.updateLayoutParams<LinearLayout.LayoutParams> { topMargin = (if (faceStats.size > 1) 8 else 24).dpToPx() }

        lateinit var onFace: (statId: String, face: Int) -> Unit
        var advance: Runnable? = null
        lateinit var render: () -> Unit

        fun nextUnanswered(): Int? {
            val steps = steps()
            return (1..steps.size).map { (index + it) % steps.size }.firstOrNull { !answered(steps[it]) }
        }

        /** The supplements taken that day (and any stopped from it, to undo): change the amount, stop, or add one */
        fun fillList() {
            list.removeAllViews()
            val ink = note.currentTextColor
            fun text(size: Float, alpha: Float, value: String) = TextView(context, null, 0, R.style.TextSmall).apply {
                textSize = size
                setTextColor(ink)
                this.alpha = alpha
                text = value
            }
            val supplements = store.load().supplements.filter { s -> s.isActive(day) || s.changes.any { it.from == day && it.stopped } }
            supplements.forEach { s ->
                val stopped = !s.isActive(day)
                val line = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 6.dpToPx(), 0, 6.dpToPx())
                }
                line.addView(View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(s.color)
                    }
                    alpha = if (stopped) 0.4f else 1f
                }, LinearLayout.LayoutParams(10.dpToPx(), 10.dpToPx()).apply { marginEnd = 12.dpToPx() })
                line.addView(text(15.5f, if (stopped) 0.5f else 1f, s.name).apply {
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (stopped) {
                    line.addView(text(13.5f, 0.6f, context.getString(R.string.supplement_stopped) + " · "))
                    line.addView(text(13.5f, 1f, context.getString(R.string.supplement_undo)).apply {
                        setPadding(4.dpToPx(), 6.dpToPx(), 4.dpToPx(), 6.dpToPx())
                        setOnClickListener {
                            store.editSupplement(s.id) { Supplements.undoStop(it, day) }
                            render()
                        }
                    })
                } else {
                    line.addView(text(13.5f, 1f, SupplementDialog.amountText(s, s.changeOn(day)!!.amount)).apply {
                        setBackgroundResource(R.drawable.bg_stat_chip)
                        setPadding(12.dpToPx(), 5.dpToPx(), 12.dpToPx(), 5.dpToPx())
                        setOnClickListener { SupplementDialog.askAmount(context, store, s, day) { render() } }
                    })
                    line.addView(text(13.5f, 0.6f, context.getString(R.string.supplement_stop)).apply {
                        setPadding(16.dpToPx(), 6.dpToPx(), 4.dpToPx(), 6.dpToPx())
                        setOnClickListener {
                            store.editSupplement(s.id) { Supplements.stop(it, day) }
                            render()
                        }
                    })
                }
                list.addView(line)
            }
            list.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
                addView(text(16f, 0.6f, "+").apply { setPadding(0, 0, 12.dpToPx(), 0) })
                addView(text(15.5f, 0.7f, context.getString(R.string.supplement_add)))
                setOnClickListener { SupplementDialog.show(context, store, null, day) { render() } }
            })
        }

        render = render@{
            val steps = steps()
            index = index.coerceIn(0, steps.lastIndex)
            val stat = steps[index]
            val selected = answers[stat.id]
            nav.isVisible = multiple()
            title.text = stat.question.ifEmpty { defaultTitle }
            label.text = context.getString(R.string.stat_step, stat.name, index + 1, steps.size)
            label.setStatDot(stat.color)
            listOf(prev, next).forEach {
                it.isEnabled = multiple()
                it.alpha = 0.6f
            }
            val done = faceStats.all { it.id in answers }
            confirm.isEnabled = done
            confirm.alpha = if (done) 1f else 0.3f

            val isList = stat.id == MoodStore.SUPPLEMENT_CHANGES
            row.isVisible = !isList
            labelRow.isVisible = anyLabels && !isList
            list.isVisible = isList
            if (isList) {
                row.tag = null
                fillList()
                return@render
            }
            val faces = stat.faces
            if (row.childCount != faces.size || row.tag != stat.id) {
                row.removeAllViews()
                row.tag = stat.id
                faces.forEachIndexed { place, face ->
                    row.addView(PixelFaceView(context).apply {
                        // Supplements' "Same as usual" is a plain face rather than a sad one
                        mood = if (stat.id == MoodStore.SUPPLEMENTS && face == MoodStore.SAD) MoodStore.NEUTRAL else face
                        color = stat.faceColor(context, face)
                        contentDescription = stat.labels.getOrNull(place)?.takeIf { it.isNotEmpty() } ?: names[face]
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
            // "Changed" goes on to the list of them; anything else to the next one not answered
            val target = if (statId == MoodStore.SUPPLEMENTS && face == MoodStore.HAPPY) steps().indexOfFirst { it.id == MoodStore.SUPPLEMENT_CHANGES }
            else nextUnanswered()
            target?.takeIf { it >= 0 }?.let { to ->
                advance?.let { row.removeCallbacks(it) }
                advance = Runnable {
                    // Skip if another stat was picked in the meantime
                    if (steps().getOrNull(index)?.id == statId) {
                        index = to
                        render()
                    }
                }.also { row.postDelayed(it, ADVANCE_DELAY_MS) }
            }
        }
        prev.setOnClickListener { val n = steps().size; index = (index - 1 + n) % n; render() }
        next.setOnClickListener { val n = steps().size; index = (index + 1) % n; render() }

        // The day's note so far, so answering keeps it rather than replacing it
        note.setText(today?.note.orEmpty())
        val voice = root.findViewById<TextView>(R.id.moodPromptVoice)
        voice.isVisible = onVoice != null
        val ink = note.currentTextColor
        voice.setCompoundDrawablesRelativeWithIntrinsicBounds(app.olauncher.ui.StatIcons.drawable(app.olauncher.ui.StatIcons.MIC, ink, 14.dpToPx()), null, null, null)
        voice.setOnClickListener {
            onVoice?.invoke { text ->
                val now = note.text?.toString().orEmpty().trim()
                note.setText(if (now.isEmpty()) text else now + "\n\n" + text)
                note.setSelection(note.text.length)
            }
        }
        liftAboveKeyboard(root, note)
        confirm.setOnClickListener { if (faceStats.all { it.id in answers }) finish(answers.toMap(), note.text?.toString()) }
        root.findViewById<View>(R.id.moodPromptClose).setOnClickListener { finish(null, null) }
        render()
    }

    private fun View.setPadding(px: Int) = setPadding(px, px, px, px)

    /** Supplements' purple, like a medicine's */
    private const val SUPPLEMENT_COLOR = 0xFF9575CD.toInt()

    /** The check-in's supplements question: two faces, the same as usual or changed */
    private fun supplementStep(context: Context) = StatInfo(
        MoodStore.SUPPLEMENTS, context.getString(R.string.supplements), SUPPLEMENT_COLOR, 2,
        context.getString(R.string.supplement_checkin_question),
        listOf(context.getString(R.string.supplement_checkin_same), context.getString(R.string.supplement_checkin_changed)),
        medicine = true, kind = StatKind.MEDICINE,
    )

    /**
     * While the keyboard is up for the thoughts box, the prompt slides up so the box (and Confirm, if
     * there's room) sits just above it; on a small screen the title goes off the top rather than the box
     * hiding behind the keyboard. Only the prompt moves, by translation, and it goes back as soon as
     * the keyboard does, so nothing about its place is left changed. The window itself is told not to
     * shift or resize, since the launcher's window can't resize and panning moved it unpredictably.
     */
    private fun liftAboveKeyboard(root: View, note: EditText) {
        val box = (root as? FrameLayout)?.getChildAt(0) ?: return
        val confirm = root.findViewById<View>(R.id.moodPromptConfirm)
        val activity = root.context.activity()
        val defaultMode = activity?.window?.attributes?.softInputMode
        val frame = Rect()
        val place = IntArray(2)
        val gap = 12.dpToPx()

        fun keyboardTop(): Int? {
            val rootView = root.rootView
            val insets = ViewCompat.getRootWindowInsets(rootView)
            val ime = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            if (insets != null && insets.isVisible(WindowInsetsCompat.Type.ime()) && ime > 0) return rootView.height - ime
            // Older phones without keyboard insets: the part of the window left showing
            rootView.getWindowVisibleDisplayFrame(frame)
            rootView.getLocationOnScreen(place)
            val covered = place[1] + rootView.height - frame.bottom
            return if (covered > rootView.height / 5) frame.bottom - place[1] else null
        }

        fun place() {
            if (!root.isShown) return
            val top = if (note.hasFocus()) keyboardTop() else null
            val target = if (top == null) 0f else {
                box.getLocationInWindow(place)
                val boxTop = place[1] - box.translationY
                fun bottomOf(view: View): Float {
                    view.getLocationInWindow(place)
                    return place[1] - box.translationY + view.height
                }
                val statusBar = ViewCompat.getRootWindowInsets(root)?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
                // All of it, down to Confirm, if that fits under the status bar; else just the box
                val withConfirm = top - gap - bottomOf(confirm)
                val shift = if (boxTop + withConfirm >= statusBar) withConfirm else top - gap - bottomOf(note)
                shift.coerceAtMost(0f)
            }
            if (box.translationY != target) box.animate().translationY(target).setDuration(150).start()
        }

        note.setOnFocusChangeListener { _, focused ->
            activity?.window?.let { window ->
                if (focused) window.setSoftInputMode((defaultMode ?: 0) and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE.inv() and
                    WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv() or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED)
                else if (defaultMode != null) window.setSoftInputMode(defaultMode)
            }
            root.post { place() }
        }
        // Once per prompt view, however many times it's bound
        if (root.getTag(R.id.moodPromptNote) == null) {
            root.setTag(R.id.moodPromptNote, true)
            root.viewTreeObserver.addOnGlobalLayoutListener { place() }
            ViewCompat.setWindowInsetsAnimationCallback(root, object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onProgress(insets: WindowInsetsCompat, running: List<WindowInsetsAnimationCompat>) = insets
                override fun onEnd(animation: WindowInsetsAnimationCompat) = place()
            })
            ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                view.post { place() }
                insets
            }
        }
        box.translationY = 0f
        place()
    }

    private tailrec fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }
}
