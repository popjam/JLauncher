package app.olauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import app.olauncher.R
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr
import kotlin.math.roundToInt

/**
 * One line per stat: weekly totals (0 to [MAX]) for weeks [firstWeek] to [lastWeek], stretched to fill
 * the width. Tapping picks a week, marked with a line, and reports it to [onWeekSelected].
 */
class WeeklyChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** [totals] holds every week from first to last. */
    class Series(val color: Int, val totals: Map<Int, Int>)

    private var series = emptyList<Series>()
    private var firstWeek = 1
    private var lastWeek = 1

    var selectedWeek: Int? = null
        private set
    var onWeekSelected: ((week: Int?) -> Unit)? = null

    private val ink = context.getColorFromAttr(R.attr.primaryColor)
    private val gridPaint = Paint().apply { strokeWidth = 1f; color = ColorUtils.setAlphaComponent(ink, 30) }
    private val axisPaint = Paint().apply { strokeWidth = 1f; color = ColorUtils.setAlphaComponent(ink, 90) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(ink, 140)
        textSize = 10f * resources.displayMetrics.scaledDensity
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectPaint = Paint().apply { strokeWidth = 1f * resources.displayMetrics.density; color = ColorUtils.setAlphaComponent(ink, 160) }
    private val path = Path()

    private val left get() = 26.dpToPx().toFloat()
    private val right get() = width - 10.dpToPx().toFloat()
    private val top get() = 8.dpToPx().toFloat()
    private val bottom get() = height - 20.dpToPx().toFloat()

    /** Keeps the picked week unless [clearSelection] or the range changed. */
    fun setData(series: List<Series>, firstWeek: Int, lastWeek: Int, clearSelection: Boolean = false) {
        if (clearSelection || firstWeek != this.firstWeek || lastWeek != this.lastWeek) selectedWeek = null
        this.series = series
        this.firstWeek = firstWeek
        this.lastWeek = lastWeek
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(300.dpToPx(), widthMeasureSpec),
            resolveSize(200.dpToPx(), heightMeasureSpec)
        )
    }

    private fun x(week: Int): Float =
        if (lastWeek == firstWeek) (left + right) / 2
        else left + (week - firstWeek) * (right - left) / (lastWeek - firstWeek)

    private fun y(total: Int): Float = top + (1 - total.toFloat() / MAX) * (bottom - top)

    override fun onDraw(canvas: Canvas) {
        // Grid, a line per week's worth of a single face
        textPaint.textAlign = Paint.Align.RIGHT
        for (total in 0..MAX step 7) {
            canvas.drawLine(left, y(total), right, y(total), if (total == 0) axisPaint else gridPaint)
            canvas.drawText(total.toString(), left - 5.dpToPx(), y(total) + textPaint.textSize / 3, textPaint)
        }

        // Up to about five week labels, always including the first and last
        val span = lastWeek - firstWeek
        val step = maxOf(1, (span + 4) / 5)
        val labels = (firstWeek..lastWeek step step).toMutableList()
        if (labels.last() != lastWeek) {
            if (lastWeek - labels.last() < step / 2.0 && labels.size > 1) labels.removeAt(labels.lastIndex)
            labels.add(lastWeek)
        }
        val labelY = height - 5.dpToPx().toFloat()
        labels.forEach { week ->
            textPaint.textAlign = when {
                span == 0 -> Paint.Align.CENTER
                week == firstWeek -> Paint.Align.LEFT
                week == lastWeek -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            canvas.drawText(context.getString(R.string.week_short, week), x(week), labelY, textPaint)
        }

        selectedWeek?.let { canvas.drawLine(x(it), top, x(it), bottom, selectPaint) }

        val dotRadius = 3.dpToPx().toFloat()
        series.forEach { s ->
            linePaint.color = s.color
            dotPaint.color = s.color
            path.reset()
            for (week in firstWeek..lastWeek) {
                val total = s.totals[week] ?: 0
                if (week == firstWeek) path.moveTo(x(week), y(total)) else path.lineTo(x(week), y(total))
            }
            if (span == 0) canvas.drawCircle(x(firstWeek), y(s.totals[firstWeek] ?: 0), dotRadius, dotPaint)
            else canvas.drawPath(path, linePaint)
            selectedWeek?.let { canvas.drawCircle(x(it), y(s.totals[it] ?: 0), dotRadius, dotPaint) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val week = if (lastWeek == firstWeek) firstWeek
                else (firstWeek + (event.x - left) / (right - left) * (lastWeek - firstWeek)).roundToInt().coerceIn(firstWeek, lastWeek)
                if (week != selectedWeek) {
                    selectedWeek = week
                    onWeekSelected?.invoke(week)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    companion object {
        /** Seven happy days */
        const val MAX = 35
    }
}
