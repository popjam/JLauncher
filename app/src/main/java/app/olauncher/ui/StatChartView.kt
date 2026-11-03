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
 * One line per stat over points [first] to [last] (weeks or months), stretched to fill the width, with
 * values from [min] to [max]. Highlighted lines are drawn in their colour over the rest, which are grey.
 * Tapping picks a point, marked with a line, and reports it to [onPointSelected].
 */
class StatChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Points missing from [values] are gaps in the line. */
    class Series(val color: Int, val values: Map<Int, Float>, val highlighted: Boolean)

    private var series = emptyList<Series>()
    private var first = 1
    private var last = 1
    private var min = 1
    private var max = 5
    private var partial: Int? = null
    private var label: (Int) -> String = { it.toString() }

    var selected: Int? = null
        private set
    var onPointSelected: ((point: Int?) -> Unit)? = null

    /** Whether lifting the finger lets go of the picked point, having touched down on it */
    private var tapClears = false

    private val ink = context.getColorFromAttr(R.attr.primaryColor)
    private val grey = ColorUtils.setAlphaComponent(ink, 60)
    private val gridPaint = Paint().apply { strokeWidth = 1f; color = ColorUtils.setAlphaComponent(ink, 30) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(ink, 140)
        textSize = 10f * resources.displayMetrics.scaledDensity
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val selectPaint = Paint().apply { strokeWidth = 1f * resources.displayMetrics.density; color = ColorUtils.setAlphaComponent(ink, 160) }
    private val path = Path()

    private val left get() = 26.dpToPx().toFloat()
    private val right get() = width - 12.dpToPx().toFloat()
    private val top get() = 8.dpToPx().toFloat()
    private val bottom get() = height - 20.dpToPx().toFloat()

    /**
     * Grid lines at each whole value, [label] names points along the bottom. [partial] is a point still
     * in progress, drawn hollow. The picked point is kept unless [clearSelection] or the range changed.
     */
    fun setData(
        series: List<Series>,
        first: Int,
        last: Int,
        min: Int,
        max: Int,
        partial: Int?,
        label: (Int) -> String,
        clearSelection: Boolean = false,
    ) {
        if (clearSelection || first != this.first || last != this.last) selected = null
        this.series = series
        this.first = first
        this.last = last
        this.min = min
        this.max = max
        this.partial = partial
        this.label = label
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(300.dpToPx(), widthMeasureSpec),
            resolveSize(200.dpToPx(), heightMeasureSpec)
        )
    }

    private fun x(point: Int): Float =
        if (last == first) (left + right) / 2
        else left + (point - first) * (right - left) / (last - first)

    private fun y(value: Float): Float = top + (1 - (value - min) / (max - min)) * (bottom - top)

    override fun onDraw(canvas: Canvas) {
        textPaint.textAlign = Paint.Align.RIGHT
        for (value in min..max) {
            val y = y(value.toFloat())
            canvas.drawLine(left, y, right, y, gridPaint)
            canvas.drawText(value.toString(), left - 8.dpToPx(), y + textPaint.textSize / 3, textPaint)
        }

        // Up to about five labels, always including the first and last
        val span = last - first
        val step = maxOf(1, (span + 4) / 5)
        val labels = (first..last step step).toMutableList()
        if (labels.last() != last) {
            if (last - labels.last() < step / 2.0 && labels.size > 1) labels.removeAt(labels.lastIndex)
            labels.add(last)
        }
        val labelY = height - 5.dpToPx().toFloat()
        labels.forEach { point ->
            textPaint.textAlign = when {
                span == 0 -> Paint.Align.CENTER
                point == first -> Paint.Align.LEFT
                point == last -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            canvas.drawText(label(point), x(point), labelY, textPaint)
        }

        selected?.let { canvas.drawLine(x(it), top, x(it), bottom, selectPaint) }

        // Grey lines first, so the highlighted ones sit on top
        series.filter { !it.highlighted }.forEach { drawSeries(canvas, it, grey, 1.2f) }
        series.filter { it.highlighted }.forEach { drawSeries(canvas, it, it.color, 2.5f) }
    }

    private fun drawSeries(canvas: Canvas, s: Series, color: Int, widthDp: Float) {
        val density = resources.displayMetrics.density
        linePaint.color = color
        linePaint.strokeWidth = widthDp * density
        dotPaint.color = color
        path.reset()
        var drawing = false
        for (point in first..last) {
            val value = s.values[point]
            if (value == null) {
                drawing = false
                continue
            }
            if (drawing) path.lineTo(x(point), y(value)) else path.moveTo(x(point), y(value))
            drawing = true
        }
        canvas.drawPath(path, linePaint)

        val radius = (if (s.highlighted) 3.5f else 2f) * density
        // Every point while there are few enough to tell apart, otherwise the lone ones a line can't show
        val allDots = s.highlighted && last - first <= 12
        for (point in first..last) {
            val value = s.values[point] ?: continue
            val lone = s.values[point - 1] == null && s.values[point + 1] == null
            val isPartial = point == partial && s.highlighted
            if (!allDots && !lone && !isPartial && point != selected) continue
            if (point == selected && !s.highlighted) continue
            // A ring while it's still in progress
            if (isPartial) canvas.drawCircle(x(point), y(value), radius + 1f * density, ringPaint.apply { this.color = color })
            else canvas.drawCircle(x(point), y(value), radius, dotPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val point = if (last == first) first
                else (first + (event.x - left) / (right - left) * (last - first)).roundToInt().coerceIn(first, last)
                // Touching the picked point again lets go of it, unless the finger moves on to another
                if (event.actionMasked == MotionEvent.ACTION_DOWN) tapClears = point == selected
                if (point != selected) {
                    tapClears = false
                    selected = point
                    onPointSelected?.invoke(point)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (tapClears) {
                    tapClears = false
                    selected = null
                    onPointSelected?.invoke(null)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                tapClears = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
