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
 * Records a voice note for [date] until Done, then writes it out as text onto the end of that day's
 * note ([VoiceNotes.writeOut]), handing the words to [onText] too. [onSaved] runs once the recording's
 * kept, text or not.
 */
fun Fragment.recordVoiceNote(date: LocalDate, onText: ((String) -> Unit)? = null, onSaved: () -> Unit = {}) {
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

    val dialog = sheetDialog(ui, card, cancelable = false).apply {
        // Thinking for a while shouldn't let the screen go off mid-note
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
                VoiceNotes.writeOut(context, date, file) { text -> if (text != null) onText?.invoke(text) }
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

/** The rounded card the voice note popups are drawn on */
private fun sheetCard(context: Context) = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    val ink = context.getColorFromAttr(R.attr.primaryColor)
    background = GradientDrawable().apply {
        cornerRadius = 28.dpToPx().toFloat()
        setColor(ColorUtils.setAlphaComponent(context.getColorFromAttr(R.attr.primaryInverseColor), 245))
        setStroke((0.5f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(ink, 90))
    }
    setPadding(24.dpToPx(), 22.dpToPx(), 24.dpToPx(), 20.dpToPx())
}

/** A dialog showing [card] across the screen, the page dimmed behind it */
private fun sheetDialog(context: Context, card: View, cancelable: Boolean) = Dialog(context).apply {
    requestWindowFeature(Window.FEATURE_NO_TITLE)
    setContentView(FrameLayout(context).apply {
        setPadding(20.dpToPx(), 0, 20.dpToPx(), 0)
        addView(card)
    })
    setCancelable(cancelable)
    setCanceledOnTouchOutside(cancelable)
    window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setDimAmount(0.7f)
    }
}

/**
 * [date]'s recordings in a popup: each can be played, written out again if it couldn't be before, or
 * deleted ([onChange] after a delete).
 */
internal fun Fragment.showVoiceNotes(date: LocalDate, onChange: () -> Unit) {
    val ui = requireContext()
    val ink = ui.getColorFromAttr(R.attr.primaryColor)
    fun faint(alpha: Int) = ColorUtils.setAlphaComponent(ink, alpha)
    val list = LinearLayout(ui).apply { orientation = LinearLayout.VERTICAL }
    val card = sheetCard(ui).apply {
        addView(TextView(ui, null, 0, R.style.TextLarge).apply {
            setText(R.string.voice_notes)
            textSize = 22f
            setTextColor(ink)
        })
        addView(TextView(ui, null, 0, R.style.TextSmall).apply {
            text = date.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.getDefault()))
            textSize = 13f
            setTextColor(faint(150))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 2.dpToPx() })
        addView(android.widget.ScrollView(ui).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 12.dpToPx() })
    }
    val dialog = sheetDialog(ui, card, cancelable = true)
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    lateinit var fill: () -> Unit
    var noNote = false

    fun row(file: java.io.File): View {
        val playButton = ImageView(ui).apply {
            scaleType = ImageView.ScaleType.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(faint(22))
                setStroke(1.dpToPx(), faint(70))
            }
        }
        val time = TextView(ui, null, 0, R.style.TextSmall).apply {
            text = android.text.format.DateFormat.getTimeFormat(ui).format(java.util.Date(file.name.substringAfter(' ').substringBefore('.').toLongOrNull() ?: file.lastModified()))
            textSize = 15f
            setTextColor(ink)
        }
        val length = TextView(ui, null, 0, R.style.TextSmall).apply {
            textSize = 13f
            setTextColor(faint(150))
        }
        // Couldn't be written out, or (from before that was remembered) the day has no note at all.
        // Compressed ones were written out.
        val failed = file.name.endsWith(".wav") && (VoiceNotes.failed(ui, file) || noNote)
        fun show() {
            val on = playing == file
            playButton.setImageDrawable(StatIcons.drawable(if (on) StatIcons.STOP else StatIcons.PLAY, ink, 14.dpToPx()))
            val total = clockOf(VoiceNotes.lengthMs(ui, file))
            val at = if (on) player?.let { runCatching { clockOf(it.currentPosition.toLong()) + " / " }.getOrNull() }.orEmpty() else ""
            length.text = at + total + if (failed) " · " + ui.getString(R.string.voice_not_written) else ""
        }
        show()
        val progress = object : Runnable {
            override fun run() {
                if (playing != file || !dialog.isShowing) return
                show()
                main.postDelayed(this, 250)
            }
        }
        playButton.setOnClickListener {
            if (playing == file) {
                stopVoicePlayback()
                return@setOnClickListener fill()
            }
            stopVoicePlayback()
            player = runCatching {
                MediaPlayer().apply {
                    setDataSource(file.path)
                    setOnCompletionListener {
                        stopVoicePlayback()
                        if (dialog.isShowing) fill()
                    }
                    prepare()
                    start()
                }
            }.getOrNull()
            playing = if (player != null) file else null
            // Only the one playing shows Stop
            fill()
            main.post(progress)
        }
        return LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
            addView(playButton, LinearLayout.LayoutParams(40.dpToPx(), 40.dpToPx()).apply { marginEnd = 14.dpToPx() })
            addView(LinearLayout(ui).apply {
                orientation = LinearLayout.VERTICAL
                addView(time)
                addView(length)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (failed) addView(pillButton(ui, ink, ui.getString(R.string.voice_write_out), null) {
                VoiceNotes.writeOut(ui, date, file) { if (dialog.isShowing) fill() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = 8.dpToPx() })
            addView(ImageView(ui).apply {
                setImageDrawable(StatIcons.drawable(StatIcons.CROSS, faint(170), 12.dpToPx()))
                scaleType = ImageView.ScaleType.CENTER
                setBackgroundResource(android.R.drawable.list_selector_background)
                contentDescription = ui.getString(R.string.voice_delete)
                setOnClickListener {
                    AlertDialog.Builder(ui)
                        .setMessage(R.string.voice_delete_ask)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.voice_delete) { _, _ ->
                            if (playing == file) stopVoicePlayback()
                            VoiceNotes.delete(ui, file)
                            onChange()
                            if (VoiceNotes.files(ui, date).isEmpty()) dialog.dismiss() else fill()
                        }
                        .show()
                }
            }, LinearLayout.LayoutParams(36.dpToPx(), 36.dpToPx()))
        }
    }

    fill = {
        noNote = app.olauncher.data.MoodStore(ui).load().days[date.toString()]?.note.isNullOrBlank()
        list.removeAllViews()
        VoiceNotes.files(ui, date).forEach { list.addView(row(it)) }
    }
    fill()
    dialog.setOnDismissListener { stopVoicePlayback() }
    dialog.show()
}

/** A small outlined button with an optional pixel icon, doing [onTap] */
private fun pillButton(context: Context, ink: Int, text: String, icon: String?, onTap: () -> Unit): TextView = TextView(context, null, 0, R.style.TextSmall).apply {
    this.text = text
    textSize = 13f
    setTextColor(ink)
    gravity = Gravity.CENTER_VERTICAL
    if (icon != null) {
        setCompoundDrawablesRelativeWithIntrinsicBounds(StatIcons.drawable(icon, ink, 14.dpToPx()), null, null, null)
        compoundDrawablePadding = 6.dpToPx()
    }
    background = GradientDrawable().apply {
        cornerRadius = 14.dpToPx().toFloat()
        setStroke(1.dpToPx(), ColorUtils.setAlphaComponent(ink, 70))
    }
    setPadding(12.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
    setOnClickListener { onTap() }
}

/** A small "Record" button with a mic, for under a note box, doing [onTap] */
internal fun Fragment.voiceButton(ink: Int, onTap: () -> Unit): TextView =
    pillButton(requireContext(), ink, getString(R.string.voice_note_button), StatIcons.MIC, onTap)

/** A button opening [date]'s recordings ([showVoiceNotes]), or null if there are none */
internal fun Fragment.voiceListButton(ink: Int, date: LocalDate, onChange: () -> Unit): TextView? {
    val count = VoiceNotes.files(requireContext(), date).size
    if (count == 0) return null
    return pillButton(requireContext(), ink, resources.getQuantityString(R.plurals.voice_note_count, count, count), StatIcons.PLAY) {
        showVoiceNotes(date, onChange)
    }
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
