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
 * One line per stat over points [first] to [last] (weeks or days), stretched to fill the width, with
 * values from 0 to [max]. Tapping picks a point, marked with a line, and reports it to [onPointSelected].
 */
class StatChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Points missing from [values] count as 0. */
    class Series(val color: Int, val values: Map<Int, Int>)

    private var series = emptyList<Series>()
    private var first = 1
    private var last = 1
    private var max = 35
    private var gridStep = 7
    private var label: (Int) -> String = { it.toString() }

    var selected: Int? = null
        private set
    var onPointSelected: ((point: Int?) -> Unit)? = null

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

    /**
     * Grid lines every [gridStep], [label] names points along the bottom. The picked point is kept
     * unless [clearSelection] or the range changed.
     */
    fun setData(
        series: List<Series>,
        first: Int,
        last: Int,
        max: Int,
        gridStep: Int,
        label: (Int) -> String,
        clearSelection: Boolean = false,
    ) {
        if (clearSelection || first != this.first || last != this.last) selected = null
        this.series = series
        this.first = first
        this.last = last
        this.max = max
        this.gridStep = gridStep
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

    private fun y(value: Int): Float = top + (1 - value.toFloat() / max) * (bottom - top)

    override fun onDraw(canvas: Canvas) {
        textPaint.textAlign = Paint.Align.RIGHT
        for (value in 0..max step gridStep) {
            canvas.drawLine(left, y(value), right, y(value), if (value == 0) axisPaint else gridPaint)
            canvas.drawText(value.toString(), left - 5.dpToPx(), y(value) + textPaint.textSize / 3, textPaint)
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

        val dotRadius = 3.dpToPx().toFloat()
        series.forEach { s ->
            linePaint.color = s.color
            dotPaint.color = s.color
            path.reset()
            for (point in first..last) {
                val value = s.values[point] ?: 0
                if (point == first) path.moveTo(x(point), y(value)) else path.lineTo(x(point), y(value))
            }
            if (span == 0) canvas.drawCircle(x(first), y(s.values[first] ?: 0), dotRadius, dotPaint)
            else canvas.drawPath(path, linePaint)
            selected?.let { canvas.drawCircle(x(it), y(s.values[it] ?: 0), dotRadius, dotPaint) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val point = if (last == first) first
                else (first + (event.x - left) / (right - left) * (last - first)).roundToInt().coerceIn(first, last)
                if (point != selected) {
                    selected = point
                    onPointSelected?.invoke(point)
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
}
