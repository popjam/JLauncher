package app.olauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.widget.ScrollView

/**
 * A box that grows with what's in it up to [maxHeightPx], then scrolls, inside a page that scrolls
 * too: while what's in it can scroll, a drag scrolls the box rather than the page.
 */
class CappedScrollView(context: Context, private val maxHeightPx: Int) : ScrollView(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST))
    }

    private val scrolls get() = canScrollVertically(1) || canScrollVertically(-1)

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && scrolls) parent?.requestDisallowInterceptTouchEvent(true)
        return super.onInterceptTouchEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && scrolls) parent?.requestDisallowInterceptTouchEvent(true)
        return super.onTouchEvent(event)
    }
}
