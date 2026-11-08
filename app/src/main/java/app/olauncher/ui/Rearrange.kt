package app.olauncher.ui

import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.drawToBitmap
import app.olauncher.R
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import kotlin.math.hypot

// Moving things around by holding and dragging them, anywhere in the app. Holding something lifts it;
// dragging then carries a copy of it under the finger to a place it snaps to, and letting go without
// dragging does what holding it did before.

/**
 * A view's touch listener for holding and dragging it. Holding lifts it; moving then starts a drag
 * with [start] (null if it can't be moved now). Letting go without moving does [onHold] instead.
 * Until it's lifted, touches go on to [then] (the view's own touch listener, if it has one) and then
 * the view itself, so taps, swipes and scrolling work as before.
 */
class HoldToDrag(
    private val then: View.OnTouchListener? = null,
    private val onHold: (View) -> Unit = { it.performLongClick() },
    private val start: (view: View, x: Float, y: Float) -> DragSession?,
) : View.OnTouchListener {

    private var lifted = false
    private var session: DragSession? = null
    private var downX = 0f
    private var downY = 0f
    private var view: View? = null
    private val lift = Runnable { view?.let { liftUp(it) } }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        val slop = ViewConfiguration.get(v.context).scaledTouchSlop
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view = v
                downX = event.rawX
                downY = event.rawY
                lifted = false
                session = null
                // Just before the view's own long press, so that's cancelled rather than done
                v.postDelayed(lift, (ViewConfiguration.getLongPressTimeout() - 60L).coerceAtLeast(250L))
            }
            MotionEvent.ACTION_MOVE -> {
                val moved = hypot(event.rawX - downX, event.rawY - downY) > slop
                if (!lifted) {
                    if (moved) v.removeCallbacks(lift)
                } else {
                    if (session == null && moved) session = start(v, downX, downY)
                    session?.move(event.rawX, event.rawY)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.removeCallbacks(lift)
                if (lifted) {
                    lifted = false
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    val dragged = session
                    session = null
                    when {
                        dragged == null -> if (event.actionMasked == MotionEvent.ACTION_UP) onHold(v)
                        event.actionMasked == MotionEvent.ACTION_UP -> dragged.drop(event.rawX, event.rawY)
                        else -> dragged.cancel()
                    }
                    return true
                }
            }
        }
        if (lifted) return true
        val used = then?.onTouch(v, event) ?: false
        if (!used) v.onTouchEvent(event)
        return true
    }

    private fun liftUp(v: View) {
        if (!v.isAttachedToWindow) return
        lifted = true
        v.cancelLongPress()
        // Lets go of whatever the view or its listener had started: a press, a swipe
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        then?.onTouch(v, cancel)
        v.onTouchEvent(cancel)
        cancel.recycle()
        v.isPressed = false
        v.parent?.requestDisallowInterceptTouchEvent(true)
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        v.animate().scaleX(1.04f).scaleY(1.04f).setDuration(120).start()
    }
}

/**
 * One drag: a copy of [source] following the finger over everything, and where it can go.
 *
 * - [zones]: labelled places at the screen's edges (Hide, another page), dropped on by letting go over one.
 * - [slots]: places on screen it snaps to, outlined; the nearest to the finger is picked.
 * - [onMove]: the finger's every move, for a list that makes room as it goes ([ListDrag]).
 *
 * [onDrop] gets the key of the zone or slot it was dropped on, or null for neither; [onEnd] runs
 * first, however it ends.
 */
class DragSession(
    private val source: View,
    startX: Float,
    startY: Float,
    private val zones: List<Zone> = emptyList(),
    private val slots: List<Slot> = emptyList(),
    private val onMove: ((x: Float, y: Float) -> Unit)? = null,
    private val onEnd: () -> Unit = {},
    private val onDrop: (key: Any?) -> Unit,
) {
    /** A labelled place by an edge of the screen, at [gravity] */
    class Zone(val key: Any, val label: String, val gravity: Int)

    /** A place to snap to, on screen */
    class Slot(val key: Any, val rect: RectF)

    private val context = source.context
    private val ink = context.getColorFromAttr(R.attr.primaryColor)
    private val back = context.getColorFromAttr(R.attr.primaryShadeDarkColor)
    private val decor = (source.rootView as? ViewGroup)
    private val overlay = FrameLayout(context)
    private val ghost = ImageView(context)
    private val zoneViews = mutableListOf<Pair<Zone, TextView>>()
    private val slotViews = mutableListOf<Pair<Slot, View>>()
    private val origin = IntArray(2)
    private val startLeft: Float
    private val startTop: Float
    private val grabX: Float
    private val grabY: Float
    private var lastX = startX
    private var lastY = startY
    private var over: Any? = null
    private var ended = false

    init {
        val at = IntArray(2)
        source.getLocationOnScreen(at)
        decor?.getLocationOnScreen(origin)
        // Where the view sat before it was lifted (it's scaled up a little by then)
        startLeft = at[0] - origin[0] + source.width * (source.scaleX - 1) / 2
        startTop = at[1] - origin[1] + source.height * (source.scaleY - 1) / 2
        grabX = startX - origin[0] - startLeft
        grabY = startY - origin[1] - startTop
        slots.forEach { slot ->
            val view = View(context)
            overlay.addView(view, FrameLayout.LayoutParams(slot.rect.width().toInt(), slot.rect.height().toInt()))
            view.translationX = slot.rect.left - origin[0]
            view.translationY = slot.rect.top - origin[1]
            slotViews.add(slot to view)
        }
        zones.forEach { zone ->
            val label = TextView(context, null, 0, R.style.TextSmall).apply {
                text = zone.label
                textSize = 14f
                setTextColor(ink)
                gravity = Gravity.CENTER
                setPadding(20.dpToPx(), 12.dpToPx(), 20.dpToPx(), 12.dpToPx())
                elevation = 6.dpToPx().toFloat()
            }
            val vertical = zone.gravity and Gravity.VERTICAL_GRAVITY_MASK
            overlay.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, zone.gravity).apply {
                setMargins(12.dpToPx(), if (vertical == Gravity.TOP) 44.dpToPx() else 12.dpToPx(), 12.dpToPx(), if (vertical == Gravity.BOTTOM) 40.dpToPx() else 12.dpToPx())
            })
            zoneViews.add(zone to label)
        }
        ghost.setImageBitmap(runCatching { source.drawToBitmap() }.getOrNull())
        ghost.alpha = 0.92f
        ghost.elevation = 12.dpToPx().toFloat()
        ghost.scaleX = 1.04f
        ghost.scaleY = 1.04f
        overlay.addView(ghost, FrameLayout.LayoutParams(source.width, source.height))
        ghost.translationX = startLeft
        ghost.translationY = startTop
        decor?.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        source.alpha = 0.25f
        highlight()
    }

    fun move(x: Float, y: Float) {
        if (ended) return
        lastX = x
        lastY = y
        ghost.translationX = x - origin[0] - grabX
        ghost.translationY = y - origin[1] - grabY
        onMove?.invoke(x, y)
        val now = pick(x, y)
        if (now != over) {
            over = now
            if (now != null) source.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            highlight()
        }
    }

    fun drop(x: Float, y: Float) {
        if (ended) return
        val key = pick(x, y)
        finish()
        onDrop(key)
    }

    fun cancel() {
        if (ended) return
        finish()
    }

    private fun finish() {
        ended = true
        decor?.removeView(overlay)
        source.alpha = 1f
        onEnd()
    }

    /** The zone under the finger, or else the nearest slot within reach */
    private fun pick(x: Float, y: Float): Any? {
        val reach = 24.dpToPx()
        val rect = Rect()
        zoneViews.forEach { (zone, view) ->
            view.getGlobalVisibleRect(rect)
            rect.inset(-reach, -reach)
            if (rect.contains(x.toInt(), y.toInt())) return zone.key
        }
        val far = 140.dpToPx().toFloat()
        return slots.map { it to hypot(it.rect.centerX() - x, it.rect.centerY() - y) }
            .filter { it.second < far + maxOf(it.first.rect.width(), it.first.rect.height()) / 2 }
            .minByOrNull { it.second }?.first?.key
    }

    private fun highlight() {
        zoneViews.forEach { (zone, view) ->
            val on = zone.key == over
            view.background = GradientDrawable().apply {
                cornerRadius = 24.dpToPx().toFloat()
                setColor(ColorUtils.blendARGB(back, ink, if (on) 0.3f else 0.08f))
                setStroke(1.dpToPx(), ColorUtils.setAlphaComponent(ink, if (on) 200 else 80))
            }
            view.alpha = if (on) 1f else 0.85f
        }
        slotViews.forEach { (slot, view) ->
            val on = slot.key == over
            view.background = GradientDrawable().apply {
                cornerRadius = 14.dpToPx().toFloat()
                setColor(ColorUtils.setAlphaComponent(ink, if (on) 46 else 10))
                setStroke(if (on) 2.dpToPx() else 1.dpToPx(), ColorUtils.setAlphaComponent(ink, if (on) 220 else 90), 6.dpToPx().toFloat(), if (on) 0f else 4.dpToPx().toFloat())
            }
        }
    }
}

/**
 * Makes room in a vertical [list] as a child is dragged over it: the others move up or down around it
 * (it stays put in the list, so its touches keep coming), and [scroll] scrolls near its edges. Only
 * children [movable] says yes to change places.
 */
class ListDrag(private val list: LinearLayout, private val scroll: ScrollView?, private val movable: (View) -> Boolean) {

    private var dragged: View? = null
    private var lastY = 0f
    private var speed = 0

    fun start(view: View) {
        dragged = view
    }

    fun move(y: Float) {
        val view = dragged ?: return
        lastY = y
        place(view)
        val scroll = scroll ?: return
        val at = IntArray(2)
        scroll.getLocationOnScreen(at)
        val edge = 72.dpToPx()
        val was = speed
        speed = when {
            y < at[1] + edge -> -10.dpToPx()
            y > at[1] + scroll.height - edge -> 10.dpToPx()
            else -> 0
        }
        if (speed != 0 && was == 0) scroll.postOnAnimation(autoScroll)
    }

    fun stop() {
        dragged = null
        speed = 0
    }

    private val autoScroll = object : Runnable {
        override fun run() {
            val view = dragged ?: return
            val scroll = scroll ?: return
            if (speed == 0) return
            scroll.scrollBy(0, speed)
            place(view)
            scroll.postOnAnimation(this)
        }
    }

    /** Moves the other children so [view] sits after every movable one whose middle is above the finger */
    private fun place(view: View) {
        val at = IntArray(2)
        list.getLocationOnScreen(at)
        val y = lastY - at[1]
        val others = (0 until list.childCount).map { list.getChildAt(it) }.filter { it != view && it.visibility == View.VISIBLE && movable(it) }
        if (others.isEmpty()) return
        val above = others.count { (it.top + it.bottom) / 2 < y }
        val current = list.indexOfChild(view)
        if (above < others.size) {
            // Just before others[above]
            val before = others[above]
            val target = list.indexOfChild(before)
            if (target == current + 1) return
            if (target > current) {
                // The ones between move up past it
                repeat(target - current - 1) {
                    val next = list.getChildAt(list.indexOfChild(view) + 1)
                    list.removeView(next)
                    list.addView(next, list.indexOfChild(view))
                }
            } else {
                // Those from before up to it move down past it
                repeat(current - target) {
                    val previous = list.getChildAt(list.indexOfChild(view) - 1)
                    list.removeView(previous)
                    list.addView(previous, list.indexOfChild(view) + 1)
                }
            }
        } else {
            // After the last movable one
            val last = others.last()
            val target = list.indexOfChild(last)
            if (target < current) return
            repeat(target - current) {
                val next = list.getChildAt(list.indexOfChild(view) + 1)
                list.removeView(next)
                list.addView(next, list.indexOfChild(view))
            }
        }
    }
}
