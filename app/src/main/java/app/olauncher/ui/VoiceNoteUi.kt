package app.olauncher.ui

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import app.olauncher.helper.Formats
import app.olauncher.R
import app.olauncher.helper.Dictation
import app.olauncher.helper.VoiceNotes
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import java.io.File
import java.time.LocalDate

// Speaking a note: a popup records it, then its words go on the end of the day's note ([VoiceNotes.writeOut])

const val MIC_REQUEST = 41

/** The permissions a recording needs that aren't given yet: the mic, and (Android 13 on) its notification */
fun missingForRecording(context: Context): Array<String> = listOfNotNull(
    Manifest.permission.RECORD_AUDIO.takeIf { !VoiceNotes.canRecord(context) },
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && !app.olauncher.helper.DictationService.canNotify(context))
        Manifest.permission.POST_NOTIFICATIONS else null,
).toTypedArray()

/** Records a note for [date] from a page; [onText] gets its words once they're in the note */
fun Fragment.recordVoiceNote(date: LocalDate, onText: ((String) -> Unit)? = null) {
    val missing = missingForRecording(requireContext())
    if (missing.isNotEmpty()) {
        @Suppress("DEPRECATION")
        requestPermissions(missing, MIC_REQUEST)
        // Without the mic there's nothing to do; without the notification it still records
        if (!VoiceNotes.canRecord(requireContext())) return Toast.makeText(requireContext(), R.string.voice_allow_mic, Toast.LENGTH_LONG).show()
    }
    showRecorder(requireContext(), viewLifecycleOwner.lifecycle, date, onText)
}

/**
 * The recording popup, over [ui] (an activity's context): the recording going on ([Dictation]), or a
 * new one for [date]. Done writes it out ([onText] gets the words); Cancel throws it away. [onClosed]
 * runs once the popup's gone.
 *
 * The recording carries on with the screen off or the page left (its notification can pause or
 * finish it, and so can headphones); the popup closes with [lifecycle], and comes back from the
 * notification.
 */
fun showRecorder(ui: Context, lifecycle: Lifecycle, date: LocalDate, onText: ((String) -> Unit)? = null, onClosed: () -> Unit = {}) {
    val context = ui.applicationContext
    if (!Dictation.start(context, date, onText)) {
        Toast.makeText(context, R.string.voice_cant_record, Toast.LENGTH_SHORT).show()
        return onClosed()
    }
    val ink = ui.getColorFromAttr(R.attr.primaryColor)
    val red = Style.DANGER
    fun faint(alpha: Int) = ColorUtils.setAlphaComponent(ink, alpha)

    val dot = ImageView(ui)
    val status = TextView(ui, null, 0, R.style.TextSmall).apply {
        textSize = Style.TEXT_SMALL
        setTextColor(ink)
        setTypeface(null, Typeface.BOLD)
        letterSpacing = 0.08f
    }
    val timer = TextView(ui, null, 0, R.style.TextLarge).apply {
        text = "0:00"
        textSize = Style.TEXT_FIGURE
        setTextColor(ink)
        gravity = Gravity.CENTER
        fontFeatureSettings = "tnum"
    }
    val bars = LevelBars(ui, ink)
    val cancel = roundButton(ui, StatIcons.CROSS, ui.getString(R.string.cancel), ink, filled = false, sizeDp = 54)
    val pause = roundButton(ui, StatIcons.PAUSE, ui.getString(R.string.voice_pause), ink, filled = true, sizeDp = 72)
    val done = roundButton(ui, StatIcons.CHECK, ui.getString(R.string.voice_done), ink, filled = false, sizeDp = 54)

    val card = sheetCard(ui).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        addView(LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(dot, LinearLayout.LayoutParams(12.dpToPx(), 12.dpToPx()).apply { marginEnd = 8.dpToPx() })
            addView(status)
        })
        addView(timer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 10.dpToPx() })
        addView(bars, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 44.dpToPx()).apply { topMargin = 18.dpToPx() })
        addView(LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            listOf(cancel, pause, done).forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 14.dpToPx() })
    }

    val dialog = AppDialog.custom(ui, card, cancelable = false).apply {
    }

    fun showState() {
        val paused = Dictation.recorder?.paused == true
        status.setText(if (paused) R.string.voice_paused else R.string.voice_recording)
        status.setTextColor(if (paused) faint(170) else ink)
        dot.setImageDrawable(StatIcons.drawable(if (paused) StatIcons.PAUSE else StatIcons.RECORD, if (paused) faint(170) else red, 12.dpToPx()))
        timer.alpha = if (paused) 0.55f else 1f
        pause.icon.setImageDrawable(StatIcons.drawable(if (paused) StatIcons.RECORD else StatIcons.PAUSE, if (paused) red else ui.getColorFromAttr(R.attr.primaryInverseColor), 24.dpToPx()))
        pause.label.setText(if (paused) R.string.voice_resume else R.string.voice_pause)
        bars.paused = paused
    }
    showState()

    val main = android.os.Handler(android.os.Looper.getMainLooper())
    var closed = false
    lateinit var tick: Runnable
    lateinit var keeper: DefaultLifecycleObserver
    lateinit var changed: () -> Unit

    /** Just the popup: the recording carries on unless it was finished */
    fun close() {
        if (closed) return
        closed = true
        main.removeCallbacks(tick)
        lifecycle.removeObserver(keeper)
        Dictation.removeListener(changed)
        runCatching { dialog.dismiss() }
        onClosed()
    }

    // Finished or paused elsewhere (the notification, headphones): the popup keeps up
    changed = { if (Dictation.active) showState() else close() }
    Dictation.addListener(changed)

    tick = object : Runnable {
        override fun run() {
            val recorder = Dictation.recorder ?: return close()
            timer.text = clockOf(recorder.recordedMs)
            bars.push(recorder.level)
            // The red dot blinks while it's listening
            dot.alpha = when {
                recorder.paused -> 1f
                System.currentTimeMillis() / 600 % 2 == 0L -> 1f
                else -> 0.25f
            }
            main.postDelayed(this, 80)
        }
    }

    // The page going takes the popup with it, not the recording
    keeper = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) = close()
    }
    lifecycle.addObserver(keeper)

    cancel.setOnClickListener {
        val recorder = Dictation.recorder ?: return@setOnClickListener close()
        // A long recording is worth a second thought before throwing it away
        if (recorder.recordedMs < KEEP_WITHOUT_ASKING_MS) return@setOnClickListener Dictation.cancel(context)
        recorder.paused = true
        showState()
        AppDialog(ui)
            .setMessage(R.string.voice_discard_ask)
            .setNegativeButton(R.string.voice_keep_going, null)
            .setPositiveButton(R.string.voice_discard) { _, _ -> Dictation.cancel(context) }
            .show()
    }
    pause.setOnClickListener { Dictation.togglePause() }
    done.setOnClickListener { Dictation.done(context) }

    dialog.show()
    main.post(tick)
}

/** Recordings shorter than this are thrown away without asking */
private const val KEEP_WITHOUT_ASKING_MS = 10_000L

/** A round button with a pixel icon in it and its name under it */
private class RoundButton(context: Context) : LinearLayout(context) {
    val icon = ImageView(context)
    val label = TextView(context, null, 0, R.style.TextSmall)
}

private fun roundButton(context: Context, icon: String, name: String, ink: Int, filled: Boolean, sizeDp: Int) = RoundButton(context).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER_HORIZONTAL
    isClickable = true
    val inverse = context.getColorFromAttr(R.attr.primaryInverseColor)
    this.icon.apply {
        setImageDrawable(StatIcons.drawable(icon, if (filled) inverse else ink, (if (filled) 24 else 18).dpToPx()))
        scaleType = ImageView.ScaleType.CENTER
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(ColorUtils.setAlphaComponent(if (filled) inverse else ink, 60)),
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (filled) setColor(ink) else {
                    setColor(ColorUtils.setAlphaComponent(ink, 18))
                    setStroke(1.dpToPx(), ColorUtils.setAlphaComponent(ink, 70))
                }
            }, null)
        isDuplicateParentStateEnabled = true
    }
    addView(this.icon, LinearLayout.LayoutParams(sizeDp.dpToPx(), sizeDp.dpToPx()))
    label.apply {
        text = name
        textSize = Style.TEXT_SMALL
        setTextColor(ColorUtils.setAlphaComponent(ink, 170))
        gravity = Gravity.CENTER
    }
    addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 6.dpToPx() })
}

/** How loud the last few seconds were, as columns of pixel squares, newest on the right */
private class LevelBars(context: Context, private val ink: Int) : View(context) {
    private val levels = FloatArray(32)
    private val paint = Paint()
    var paused = false
        set(value) { field = value; invalidate() }

    fun push(level: Float) {
        System.arraycopy(levels, 1, levels, 0, levels.size - 1)
        // Eased down, so it falls back smoothly between words
        levels[levels.size - 1] = maxOf(level, levels[levels.size - 2] * 0.6f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val rows = 7
        val gap = 2.dpToPx().toFloat()
        val cell = minOf((width - gap * (levels.size - 1)) / levels.size, (height - gap * (rows - 1)) / rows)
        val left = (width - (cell * levels.size + gap * (levels.size - 1))) / 2
        val middle = height / 2f
        levels.forEachIndexed { i, level ->
            // Grows out from the middle line; always at least the middle square, faintly
            val lit = (level * rows).toInt().coerceIn(0, rows).let { if (it % 2 == 0) it + 1 else it }.coerceAtMost(rows)
            val x = left + i * (cell + gap)
            for (r in 0 until lit) {
                val offset = r - lit / 2
                val y = middle - cell / 2 + offset * (cell + gap)
                paint.color = ColorUtils.setAlphaComponent(ink, if (paused || level < 0.05f) 50 else 110 + (145 * i / levels.size))
                canvas.drawRect(x, y, x + cell, y + cell, paint)
            }
        }
    }
}

private fun clockOf(ms: Long) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

/** What the voice popup shows, inside the popups' frame ([AppDialog.custom]) */
private fun sheetCard(context: Context) = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(24.dpToPx(), 22.dpToPx(), 24.dpToPx(), 20.dpToPx())
}

/**
 * Recordings that couldn't be written out, one row each, into [into] (hidden when there are none):
 * each can be tried again or thrown away.
 */
internal fun Fragment.fillPending(into: LinearLayout) {
    val ui = requireContext()
    val ink = ui.getColorFromAttr(R.attr.primaryColor)
    fun faint(alpha: Int) = ColorUtils.setAlphaComponent(ink, alpha)
    into.removeAllViews()
    val files = VoiceNotes.pending(ui)
    val writing = VoiceNotes.writing > 0
    into.visibility = if (files.isEmpty() && !writing) View.GONE else View.VISIBLE
    // Being written out now
    if (writing) into.addView(TextView(ui, null, 0, R.style.TextSmall).apply {
        setText(R.string.voice_writing)
        textSize = Style.TEXT_SMALL
        setTextColor(ink)
        alpha = 0.6f
        setTypeface(null, Typeface.BOLD)
        setPadding(4.dpToPx(), 0, 0, if (files.isEmpty()) 0 else 10.dpToPx())
    })
    if (files.isEmpty()) return
    into.addView(TextView(ui, null, 0, R.style.TextSmall).apply {
        text = resources.getQuantityString(R.plurals.voice_not_written, files.size, files.size)
        textSize = Style.TEXT_SMALL
        setTextColor(ink)
        alpha = 0.6f
        setTypeface(null, Typeface.BOLD)
        setPadding(4.dpToPx(), 0, 0, 2.dpToPx())
    })
    files.forEach { file -> into.addView(pendingRow(ui, ink, file, ::faint)) }
}

private fun Fragment.pendingRow(ui: Context, ink: Int, file: File, faint: (Int) -> Int): View {
    val date = VoiceNotes.dateOf(file) ?: LocalDate.now()
    val day = date.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.getDefault()))
    val time = app.olauncher.helper.Formats.time(ui, VoiceNotes.startedAt(file))
    return LinearLayout(ui).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(4.dpToPx(), 6.dpToPx(), 0, 6.dpToPx())
        addView(TextView(ui, null, 0, R.style.TextSmall).apply {
            text = "$day, $time · ${clockOf(VoiceNotes.wavMs(file))}"
            textSize = Style.TEXT_BODY
            setTextColor(ink)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(pillButton(ui, ink, ui.getString(R.string.voice_try_again), null) {
            VoiceNotes.writeOut(ui, date, file)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = 4.dpToPx() })
        addView(ImageView(ui).apply {
            setImageDrawable(StatIcons.drawable(StatIcons.CROSS, faint(170), 12.dpToPx()))
            scaleType = ImageView.ScaleType.CENTER
            setBackgroundResource(android.R.drawable.list_selector_background)
            contentDescription = ui.getString(R.string.voice_discard)
            setOnClickListener {
                AppDialog(ui)
                    .setMessage(R.string.voice_discard_ask)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.voice_discard) { _, _ -> VoiceNotes.delete(file) }
                    .show()
            }
        }, LinearLayout.LayoutParams(40.dpToPx(), 40.dpToPx()))
    }
}

/** A small outlined button with an optional pixel icon, doing [onTap] */
internal fun pillButton(context: Context, ink: Int, text: String, icon: String?, onTap: () -> Unit): TextView = TextView(context, null, 0, R.style.TextSmall).apply {
    this.text = text
    textSize = Style.TEXT_SMALL
    setTextColor(ink)
    gravity = Gravity.CENTER_VERTICAL
    if (icon != null) {
        setCompoundDrawablesRelativeWithIntrinsicBounds(StatIcons.drawable(icon, ink, 14.dpToPx()), null, null, null)
        compoundDrawablePadding = 6.dpToPx()
    }
    background = GradientDrawable().apply {
        cornerRadius = Style.radiusSmall
        setStroke(1.dpToPx(), ColorUtils.setAlphaComponent(ink, 70))
    }
    setPadding(12.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
    setOnClickListener { onTap() }
}

/** A small "Record" button with a mic, for under a note box, doing [onTap] */
internal fun Fragment.voiceButton(ink: Int, onTap: () -> Unit): TextView =
    pillButton(requireContext(), ink, getString(R.string.voice_note_button), StatIcons.MIC, onTap)

/** A small "Journal" button opening [date]'s note on its own page, after [before] (saving the box, say) */
internal fun Fragment.journalButton(ink: Int, date: LocalDate, before: () -> Unit): TextView =
    pillButton(requireContext(), ink, getString(R.string.journal), StatIcons.BOOK) {
        before()
        openNote(date)
    }

/** [buttons] on the right, in their own row, for under a note box */
internal fun Fragment.endRow(vararg buttons: View?): LinearLayout = LinearLayout(requireContext()).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.END
    buttons.filterNotNull().forEachIndexed { i, button ->
        addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (i > 0) marginStart = 8.dpToPx()
        })
    }
}
