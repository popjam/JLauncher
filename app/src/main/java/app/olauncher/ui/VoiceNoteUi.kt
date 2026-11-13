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
import android.media.MediaPlayer
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
import app.olauncher.R
import app.olauncher.helper.VoiceNotes
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import java.io.File
import java.time.LocalDate

// Voice notes: holding a note box records one, written out as text into the note; the day page plays them back

/** What's playing now, so starting another stops it */
private var player: MediaPlayer? = null
private var playing: File? = null

fun stopVoicePlayback() {
    runCatching { player?.release() }
    player = null
    playing = null
}

/**
 * Records a voice note for [date] until Done, then writes it out as text and hands that to [onText]
 * (to add to the note). [onSaved] runs once the recording's kept, text or not.
 */
fun Fragment.recordVoiceNote(date: LocalDate, onText: (String) -> Unit, onSaved: () -> Unit = {}) {
    val context = requireContext().applicationContext
    if (!VoiceNotes.canRecord(context)) {
        @Suppress("DEPRECATION")
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 41)
        Toast.makeText(context, R.string.voice_allow_mic, Toast.LENGTH_LONG).show()
        return
    }
    stopVoicePlayback()
    val recorder = VoiceNotes.Recorder(context, date)
    if (!recorder.start()) return run { Toast.makeText(context, R.string.voice_cant_record, Toast.LENGTH_SHORT).show() }
    val ui = requireContext()
    val ink = ui.getColorFromAttr(R.attr.primaryColor)
    val red = 0xFFEF5350.toInt()
    fun faint(alpha: Int) = ColorUtils.setAlphaComponent(ink, alpha)

    val dot = ImageView(ui)
    val status = TextView(ui, null, 0, R.style.TextSmall).apply {
        textSize = 13f
        setTextColor(ink)
        setTypeface(null, Typeface.BOLD)
        letterSpacing = 0.08f
    }
    val timer = TextView(ui, null, 0, R.style.TextLarge).apply {
        text = "0:00"
        textSize = 52f
        setTextColor(ink)
        gravity = Gravity.CENTER
        fontFeatureSettings = "tnum"
    }
    val bars = LevelBars(ui, ink)
    val cancel = roundButton(ui, StatIcons.CROSS, ui.getString(R.string.cancel), ink, filled = false, sizeDp = 54)
    val pause = roundButton(ui, StatIcons.PAUSE, ui.getString(R.string.voice_pause), ink, filled = true, sizeDp = 72)
    val done = roundButton(ui, StatIcons.CHECK, ui.getString(R.string.voice_done), ink, filled = false, sizeDp = 54)

    val card = LinearLayout(ui).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = GradientDrawable().apply {
            cornerRadius = 28.dpToPx().toFloat()
            setColor(ColorUtils.setAlphaComponent(ui.getColorFromAttr(R.attr.primaryInverseColor), 245))
            setStroke((0.5f * ui.resources.displayMetrics.density).toInt().coerceAtLeast(1), faint(90))
        }
        setPadding(24.dpToPx(), 22.dpToPx(), 24.dpToPx(), 20.dpToPx())
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

    val dialog = Dialog(ui).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(FrameLayout(ui).apply {
            setPadding(20.dpToPx(), 0, 20.dpToPx(), 0)
            addView(card)
        })
        setCancelable(false)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.7f)
            // Thinking for a while shouldn't let the screen go off mid-note
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    fun showState() {
        val paused = recorder.paused
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
    val lifecycle = viewLifecycleOwner.lifecycle
    var finished = false
    lateinit var tick: Runnable
    lateinit var keeper: androidx.lifecycle.DefaultLifecycleObserver

    fun close() {
        finished = true
        main.removeCallbacks(tick)
        lifecycle.removeObserver(keeper)
        runCatching { dialog.dismiss() }
    }

    fun save() {
        if (finished) return
        close()
        Thread {
            val file = recorder.stop()
            main.post {
                if (file == null) return@post Toast.makeText(context, R.string.voice_too_short, Toast.LENGTH_SHORT).show()
                onSaved()
                if (!VoiceNotes.canTranscribe(context)) return@post Toast.makeText(context, R.string.voice_no_text, Toast.LENGTH_LONG).show()
                Toast.makeText(context, R.string.voice_writing, Toast.LENGTH_SHORT).show()
                VoiceNotes.transcribe(context, file) { text ->
                    if (text != null) onText(text)
                    else Toast.makeText(context, R.string.voice_no_text, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    fun discard() {
        if (finished) return
        close()
        Thread { recorder.cancel() }.start()
    }

    tick = object : Runnable {
        override fun run() {
            if (finished) return
            timer.text = clockOf(recorder.recordedMs)
            bars.push(recorder.level)
            // The red dot blinks while it's listening
            dot.alpha = when {
                recorder.paused -> 1f
                System.currentTimeMillis() / 600 % 2 == 0L -> 1f
                else -> 0.25f
            }
            // At the most it can hold, it's kept as if Done were tapped
            if (recorder.full) return save()
            main.postDelayed(this, 80)
        }
    }

    // Leaving the page (home pressed, screen off) pauses it, so the mic isn't left on; it's still there on coming back.
    // The page going altogether throws it away.
    keeper = object : androidx.lifecycle.DefaultLifecycleObserver {
        override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
            recorder.paused = true
            showState()
        }

        override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) = discard()
    }
    lifecycle.addObserver(keeper)

    cancel.setOnClickListener {
        // A long recording is worth a second thought before throwing it away
        if (recorder.recordedMs < 10_000) return@setOnClickListener discard()
        recorder.paused = true
        showState()
        AlertDialog.Builder(ui)
            .setMessage(R.string.voice_discard_ask)
            .setNegativeButton(R.string.voice_keep_going, null)
            .setPositiveButton(R.string.voice_discard) { _, _ -> discard() }
            .show()
    }
    pause.setOnClickListener {
        recorder.paused = !recorder.paused
        showState()
    }
    done.setOnClickListener { save() }

    dialog.show()
    main.post(tick)
}

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
        textSize = 12f
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

/**
 * A row for each of [date]'s recordings: tap to play or stop; hold to write it out again (its words to
 * [onText]) or delete it ([onChange] after).
 */
internal fun Fragment.voiceRows(card: CardPageFragment.Card, date: LocalDate, onText: (String) -> Unit, onChange: () -> Unit): LinearLayout? {
    val context = requireContext()
    val files = VoiceNotes.files(context, date)
    if (files.isEmpty()) return null
    val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    files.forEach { file ->
        val label = card.textView(14f, 220)
        fun show() {
            label.text = getString(if (playing == file) R.string.voice_stop else R.string.voice_play, clockOf(VoiceNotes.lengthMs(file)))
        }
        show()
        label.setPadding(4.dpToPx(), 10.dpToPx(), 4.dpToPx(), 10.dpToPx())
        label.setBackgroundResource(android.R.drawable.list_selector_background)
        label.setOnClickListener {
            if (playing == file) {
                stopVoicePlayback()
                show()
                return@setOnClickListener
            }
            stopVoicePlayback()
            player = runCatching {
                MediaPlayer().apply {
                    setDataSource(file.path)
                    setOnCompletionListener {
                        stopVoicePlayback()
                        show()
                    }
                    prepare()
                    start()
                }
            }.getOrNull()
            playing = if (player != null) file else null
            show()
        }
        label.setOnLongClickListener {
            AlertDialog.Builder(context)
                .setItems(arrayOf(getString(R.string.voice_write_again), getString(R.string.voice_delete))) { _, which ->
                    if (which == 0) {
                        if (!VoiceNotes.canTranscribe(context)) return@setItems Toast.makeText(context, R.string.voice_no_text, Toast.LENGTH_LONG).show()
                        Toast.makeText(context, R.string.voice_writing, Toast.LENGTH_SHORT).show()
                        VoiceNotes.transcribe(context.applicationContext, file) { text ->
                            if (text != null) onText(text) else Toast.makeText(context, R.string.voice_no_text, Toast.LENGTH_LONG).show()
                        }
                    } else AlertDialog.Builder(context)
                        .setMessage(R.string.voice_delete_ask)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.voice_delete) { _, _ ->
                            if (playing == file) stopVoicePlayback()
                            VoiceNotes.delete(file)
                            onChange()
                        }
                        .show()
                }
                .show()
            true
        }
        list.addView(label)
    }
    return list
}

/** A small "Voice note" button with a mic, for under a note box, doing [onTap] */
internal fun Fragment.voiceButton(ink: Int, onTap: () -> Unit): TextView = TextView(requireContext(), null, 0, R.style.TextSmall).apply {
    setText(R.string.voice_note_button)
    textSize = 13f
    setTextColor(ink)
    gravity = Gravity.CENTER_VERTICAL
    setCompoundDrawablesRelativeWithIntrinsicBounds(StatIcons.drawable(StatIcons.MIC, ink, 14.dpToPx()), null, null, null)
    compoundDrawablePadding = 6.dpToPx()
    background = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = 14.dpToPx().toFloat()
        setStroke(1.dpToPx(), androidx.core.graphics.ColorUtils.setAlphaComponent(ink, 70))
    }
    setPadding(12.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
    setOnClickListener { onTap() }
}

/** [button] on the right, in its own row, for under a note box */
internal fun Fragment.endRow(button: android.view.View): LinearLayout = LinearLayout(requireContext()).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.END
    addView(button)
}
