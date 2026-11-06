package app.olauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Pulling down on a page already scrolled to its top drags it down after the finger, fading; let go
 * far or fast enough and it slides away and [onDismiss] runs, otherwise it springs back. Only vertical
 * pulls are taken, so sideways swipes (between stat pages) and taps still reach what's inside.
 */
class PullDismissLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    var onDismiss: (() -> Unit)? = null

    /** Whether pulling down dismisses */
    var pullDown = true

    /** Dragging sideways dismisses too: 1 to the right, -1 to the left, 0 not at all */
    var sideDismiss = 0

    private var sideways = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4
    private val threshold = 110 * resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var atTop = false
    private var dragging = false
    private var velocity: VelocityTracker? = null
    private var dismissing = false

    private val content: View? get() = if (childCount > 0) getChildAt(0) else null

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (dismissing) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
                sideways = false
                atTop = !canScrollUpAt(this, event.x, event.y)
                velocity?.recycle()
                velocity = VelocityTracker.obtain().apply { addMovement(event) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                val dy = event.y - downY
                val dx = event.x - downX
                if (sideDismiss != 0 && dx * sideDismiss > touchSlop && abs(dx) > abs(dy) * 1.6f &&
                    !canScrollSidewaysAt(this, event.x, event.y, -sideDismiss)) {
                    dragging = true
                    sideways = true
                    downX = event.x
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                if (pullDown && atTop && dy > touchSlop && dy > abs(dx) * 1.6f) {
                    dragging = true
                    // Starts from where the pull was recognised, so the page doesn't jump
                    downY = event.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(event)
        velocity?.addMovement(event)
        val view = content ?: return true
        if (sideways) return sideTouch(event, view)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dy = (event.y - downY).coerceAtLeast(0f)
                // Some give, so it feels held back a little
                view.translationY = dy * 0.75f
                view.alpha = 1f - (view.translationY / (height * 0.9f)).coerceIn(0f, 0.7f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.computeCurrentVelocity(1000)
                val speed = velocity?.yVelocity ?: 0f
                dragging = false
                if (event.actionMasked == MotionEvent.ACTION_UP && (view.translationY > threshold || speed > minFling && view.translationY > touchSlop)) {
                    dismissing = true
                    view.animate().translationY(height.toFloat()).alpha(0f).setDuration(180)
                        .setInterpolator(DecelerateInterpolator()).withEndAction { onDismiss?.invoke() }.start()
                } else {
                    view.animate().translationY(0f).alpha(1f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
                }
                velocity?.recycle()
                velocity = null
            }
        }
        return true
    }

    /** Like a pull down, but sideways in [sideDismiss]'s direction, following the finger exactly */
    private fun sideTouch(event: MotionEvent, view: View): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = ((event.x - downX) * sideDismiss).coerceAtLeast(0f)
                view.translationX = dx * sideDismiss
                view.alpha = 1f - (dx / (width * 1.2f)).coerceIn(0f, 0.6f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.computeCurrentVelocity(1000)
                val speed = (velocity?.xVelocity ?: 0f) * sideDismiss
                val moved = view.translationX * sideDismiss
                dragging = false
                sideways = false
                if (event.actionMasked == MotionEvent.ACTION_UP && (moved > width / 3f || speed > minFling && moved > touchSlop)) {
                    dismissing = true
                    view.animate().translationX(width.toFloat() * sideDismiss).alpha(0f).setDuration(140)
                        .setInterpolator(DecelerateInterpolator()).withEndAction { onDismiss?.invoke() }.start()
                } else {
                    view.animate().translationX(0f).alpha(1f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
                }
                velocity?.recycle()
                velocity = null
            }
        }
        return true
    }

    /** Whether something under ([x], [y]) can still scroll sideways in [direction] (1 = towards the right). */
    private fun canScrollSidewaysAt(group: ViewGroup, x: Float, y: Float, direction: Int): Boolean {
        for (i in group.childCount - 1 downTo 0) {
            val child = group.getChildAt(i)
            if (child.visibility != VISIBLE) continue
            val cx = x - child.left + group.scrollX - child.translationX
            val cy = y - child.top + group.scrollY - child.translationY
            if (cx < 0 || cy < 0 || cx > child.width || cy > child.height) continue
            if (child.canScrollHorizontally(direction)) return true
            if (child is ViewGroup && canScrollSidewaysAt(child, cx, cy, direction)) return true
        }
        return false
    }

    /** Whether something scrollable under ([x], [y]) in [group]'s coordinates can still scroll up. */
    private fun canScrollUpAt(group: ViewGroup, x: Float, y: Float): Boolean {
        for (i in group.childCount - 1 downTo 0) {
            val child = group.getChildAt(i)
            if (child.visibility != VISIBLE) continue
            val cx = x - child.left + group.scrollX - child.translationX
            val cy = y - child.top + group.scrollY - child.translationY
            if (cx < 0 || cy < 0 || cx > child.width || cy > child.height) continue
            if (child.canScrollVertically(-1)) return true
            if (child is ViewGroup && canScrollUpAt(child, cx, cy)) return true
        }
        return false
    }
}
