package app.olauncher.helper

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import app.olauncher.data.Prefs
import app.olauncher.ui.LockdownGate
import kotlin.math.abs

/**
 * The break's countdown, floating over every app while a break runs (when it's turned on): a small
 * black rectangle with the time left in white. It can be dragged anywhere and stays where it's left.
 * Shown by the accessibility service, whose overlays need no other permission.
 */
class BreakBubble(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val windows get() = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var shownSize = -1
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            var shown = false
            CrashLog.guard { shown = update() }
            // By the second while it counts down; otherwise a look now and then for a break starting
            if (running) main.postDelayed(this, if (shown) 1_000L else 5_000L)
        }
    }

    fun start() {
        if (running) return
        running = true
        main.post(tick)
    }

    fun stop() {
        running = false
        main.removeCallbacks(tick)
        hide()
    }

    /** Looks again straight away, after a break starts or ends or the setting changes */
    fun refresh() {
        if (!running) return
        main.removeCallbacks(tick)
        main.post(tick)
    }

    /** Shows, moves on or hides the bubble; whether it shows */
    private fun update(): Boolean {
        val prefs = Prefs(context)
        val left = if (prefs.lockdownBreakBubble && Lockdown.isOn(context, prefs) && Lockdown.onBreak(prefs))
            Lockdown.breakUntil(prefs) - System.currentTimeMillis() else 0L
        if (left <= 0L) {
            hide()
            return false
        }
        if (view != null && shownSize != prefs.lockdownBreakBubbleSize) hide()
        val bubble = view ?: show(prefs) ?: return false
        bubble.text = LockdownGate.clockText(left)
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show(prefs: Prefs): TextView? = runCatching {
        val size = prefs.lockdownBreakBubbleSize.coerceIn(0, SIZES.lastIndex)
        val (textSp, padH, padV) = SIZES[size]
        val bubble = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
            gravity = Gravity.CENTER
            setPadding(padH.dpToPx(), padV.dpToPx(), padH.dpToPx(), padV.dpToPx())
            background = GradientDrawable().apply {
                setColor(0xF2000000.toInt())
                setStroke(1.dpToPx(), 0x66FFFFFF)
                cornerRadius = 3.dpToPx().toFloat()
            }
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val metrics = context.resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Top right, under the status bar, until it's moved
            x = if (prefs.lockdownBreakBubbleX >= 0f) (prefs.lockdownBreakBubbleX * metrics.widthPixels).toInt() else metrics.widthPixels - 120.dpToPx()
            y = if (prefs.lockdownBreakBubbleY >= 0f) (prefs.lockdownBreakBubbleY * metrics.heightPixels).toInt() else 48.dpToPx()
        }
        bubble.setOnTouchListener(Drag(lp))
        windows.addView(bubble, lp)
        view = bubble
        params = lp
        shownSize = size
        bubble
    }.getOrNull()

    private fun hide() {
        val bubble = view ?: return
        view = null
        params = null
        runCatching { windows.removeView(bubble) }
    }

    /** Moves the bubble with a finger, and keeps where it's let go */
    private inner class Drag(private val lp: WindowManager.LayoutParams) : android.view.View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        override fun onTouch(v: android.view.View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = lp.x; startY = lp.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!moved && abs(dx) + abs(dy) > ViewConfiguration.get(context).scaledTouchSlop) moved = true
                    if (moved) {
                        val metrics = context.resources.displayMetrics
                        lp.x = (startX + dx).toInt().coerceIn(0, (metrics.widthPixels - v.width).coerceAtLeast(0))
                        lp.y = (startY + dy).toInt().coerceIn(0, (metrics.heightPixels - v.height).coerceAtLeast(0))
                        runCatching { windows.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (moved) {
                    val metrics = context.resources.displayMetrics
                    val prefs = Prefs(context)
                    prefs.lockdownBreakBubbleX = lp.x / metrics.widthPixels.toFloat()
                    prefs.lockdownBreakBubbleY = lp.y / metrics.heightPixels.toFloat()
                }
            }
            return true
        }
    }

    companion object {
        /** Text size (sp) and padding across and down (dp), small to large */
        private val SIZES = listOf(Triple(12f, 8, 3), Triple(15f, 10, 5), Triple(20f, 14, 7))
    }
}
