package app.olauncher.ui

import android.Manifest
import android.media.MediaPlayer
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import app.olauncher.R
import app.olauncher.helper.VoiceNotes
import app.olauncher.helper.dpToPx
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
    val timer = TextView(requireContext(), null, 0, R.style.TextLarge).apply {
        text = "0:00"
        textSize = 34f
        gravity = Gravity.CENTER
        updatePadding(top = 16.dpToPx(), bottom = 8.dpToPx())
    }
    val tick = object : Runnable {
        override fun run() {
            val ms = System.currentTimeMillis() - recorder.startedAt
            timer.text = clockOf(ms)
            if (ms < VoiceNotes.MAX_MS) timer.postDelayed(this, 250)
        }
    }
    timer.post(tick)
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    // Leaving the page (home pressed, screen off) throws the recording away rather than leave the mic on
    var dialog: AlertDialog? = null
    val stopper = object : androidx.lifecycle.DefaultLifecycleObserver {
        override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
            timer.removeCallbacks(tick)
            Thread { recorder.cancel() }.start()
            runCatching { dialog?.dismiss() }
            owner.lifecycle.removeObserver(this)
        }
    }
    viewLifecycleOwner.lifecycle.addObserver(stopper)
    val lifecycle = viewLifecycleOwner.lifecycle
    dialog = AlertDialog.Builder(requireContext())
        .setTitle(R.string.voice_recording)
        .setView(timer)
        .setCancelable(false)
        .setNegativeButton(R.string.cancel) { _, _ ->
            lifecycle.removeObserver(stopper)
            timer.removeCallbacks(tick)
            Thread { recorder.cancel() }.start()
        }
        .setPositiveButton(R.string.voice_done) { _, _ ->
            lifecycle.removeObserver(stopper)
            timer.removeCallbacks(tick)
            Thread {
                val file = recorder.stop()
                // The dialog (and the timer in it) is gone by now, so back to the main thread directly
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
        .show()
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
