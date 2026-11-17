package app.olauncher.helper

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import app.olauncher.R
import java.time.LocalDate

/**
 * The one recording going on, wherever it was started: kept going with the screen off by
 * [DictationService] (with a notification to pause or finish it), from Bluetooth headphones' mic when
 * some are connected, and finished by a press of their play/pause button. The recording popup shows
 * it and controls it while it's on screen. Main thread.
 */
object Dictation {

    var recorder: VoiceNotes.Recorder? = null
        private set
    private var date: LocalDate = LocalDate.now()
    private var onText: ((String) -> Unit)? = null

    val active get() = recorder != null

    /** Told when it's paused, resumed or finished, so the popup and notification keep up */
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    private fun changed() = listeners.toList().forEach { it() }

    private val main = Handler(Looper.getMainLooper())

    /** The app, while recording, for finishing it by itself */
    private var appContext: Context? = null

    /**
     * Starts recording for [date], from headphones' mic if there are any; [onText] gets the words once
     * they're in the note. False if it couldn't start. From a page on screen (Android only lets a
     * recording keep going with the screen off if it starts while the app is in front).
     */
    fun start(context: Context, date: LocalDate, onText: ((String) -> Unit)? = null): Boolean {
        if (recorder != null) return true
        val app = context.applicationContext
        val mic = HeadsetMic.use(app)
        val r = VoiceNotes.Recorder(app, date)
        if (!r.start(mic)) {
            HeadsetMic.release(app)
            return false
        }
        recorder = r
        appContext = app
        this.date = date
        this.onText = onText
        ContextCompat.startForegroundService(app, Intent(app, DictationService::class.java))
        main.post(fullCheck)
        changed()
        return true
    }

    /** Checks each second whether it's reached the most it can hold, and finishes it if so */
    private val fullCheck = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            if (r.full) return done(appContext ?: return)
            main.postDelayed(this, 1000)
        }
    }

    fun togglePause() {
        val r = recorder ?: return
        r.paused = !r.paused
        changed()
    }

    /** Keeps what was said and writes it out into the day's note */
    fun done(context: Context) {
        val r = recorder ?: return
        val app = context.applicationContext
        val day = date
        val callback = onText
        end(app)
        Thread {
            val file = r.stop()
            main.post {
                if (file == null) return@post Toast.makeText(app, R.string.voice_too_short, Toast.LENGTH_SHORT).show()
                VoiceNotes.writeOut(app, day, file) { text -> if (text != null) callback?.invoke(text) }
            }
        }.start()
    }

    /** Throws it away */
    fun cancel(context: Context) {
        val r = recorder ?: return
        end(context.applicationContext)
        Thread { r.cancel() }.start()
    }

    private fun end(app: Context) {
        recorder = null
        onText = null
        appContext = null
        main.removeCallbacks(fullCheck)
        HeadsetMic.release(app)
        app.stopService(Intent(app, DictationService::class.java))
        changed()
    }

    /**
     * Bluetooth headphones' mic: while recording, the phone talks to connected headphones as it would
     * on a call, so their mic is heard instead of the phone's. Android 12 on.
     */
    private object HeadsetMic {
        private var routed = false

        private val INPUTS = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 26 /* TYPE_BLE_HEADSET */)

        /** The headphones' mic to record from, having switched the phone to it; null for the phone's own */
        fun use(context: Context): AudioDeviceInfo? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
            return runCatching {
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val headset = audio.availableCommunicationDevices.firstOrNull { it.type in INPUTS } ?: return null
                if (!audio.setCommunicationDevice(headset)) return null
                routed = true
                audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == headset.type }
            }.getOrNull()
        }

        fun release(context: Context) {
            if (!routed || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            routed = false
            runCatching { (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).clearCommunicationDevice() }
        }
    }
}
