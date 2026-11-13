package app.olauncher.helper

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.olauncher.R
import java.time.LocalDate

/**
 * Holding the voice note key (picked in settings, caught by the accessibility service) records a voice
 * note for today, with a small timer at the top of the screen; letting go keeps it and writes its words
 * onto the end of today's note.
 */
object VoiceKey {

    private var recorder: VoiceNotes.Recorder? = null
    private var date: LocalDate? = null
    private var overlay: View? = null
    private val main = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null

    val recording get() = recorder != null

    /** Key down: starts recording, from the accessibility service's [context] */
    fun start(context: Context) {
        if (recorder != null) return
        val app = context.applicationContext
        if (!VoiceNotes.canRecord(app)) return Toast.makeText(app, R.string.voice_allow_mic, Toast.LENGTH_LONG).show()
        val today = LocalDate.now()
        val r = VoiceNotes.Recorder(app, today)
        if (!r.start()) return Toast.makeText(app, R.string.voice_cant_record, Toast.LENGTH_SHORT).show()
        recorder = r
        date = today
        showOverlay(context, r)
    }

    /** Key up: keeps the recording and writes it into today's note */
    fun finish(context: Context) {
        val r = recorder ?: return
        val day = date ?: LocalDate.now()
        recorder = null
        hideOverlay(context)
        val app = context.applicationContext
        Thread {
            val file = r.stop()
            main.post {
                if (file == null) return@post Toast.makeText(app, R.string.voice_too_short, Toast.LENGTH_SHORT).show()
                VoiceNotes.writeOut(app, day, file) { text ->
                    if (text != null) Toast.makeText(app, R.string.voice_added, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** The service going away mid-recording throws it away */
    fun cancel(context: Context) {
        val r = recorder ?: return
        recorder = null
        hideOverlay(context)
        Thread { r.cancel() }.start()
    }

    private fun showOverlay(context: Context, r: VoiceNotes.Recorder) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFEF5350.toInt())
            }
        }
        val time = TextView(context).apply {
            text = "0:00"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
        }
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0xE6111111.toInt())
                setStroke(dp(1), 0x40FFFFFF)
            }
            setPadding(dp(16), dp(10), dp(18), dp(10))
            addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) })
            addView(time)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(48)
        }
        runCatching { (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(pill, params) }
            .onSuccess { overlay = pill }
        val t = object : Runnable {
            override fun run() {
                if (recorder !== r) return
                val ms = r.recordedMs
                time.text = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)
                dot.alpha = if (System.currentTimeMillis() / 600 % 2 == 0L) 1f else 0.25f
                main.postDelayed(this, 100)
            }
        }
        tick = t
        main.post(t)
    }

    private fun hideOverlay(context: Context) {
        tick?.let { main.removeCallbacks(it) }
        tick = null
        val view = overlay ?: return
        overlay = null
        runCatching { (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view) }
    }
}
