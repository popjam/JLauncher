package app.olauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import app.olauncher.R
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr

/**
 * One stat day by day from [first] to [last]: a bar per day as tall as its score (1 to 5) in its face's
 * colour, with a line along its rolling average. Under it, a row of dots per [Strip] (the days a medicine
 * was taken, say) lines other stats up against it. Tapping picks a day, outlined and reported to
 * [onPointSelected].
 */
class StatFocusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    class Bar(val score: Float, val color: Int)

    /** [dots] maps days to their dot's colour; days without one are left empty. */
    class Strip(val name: String, val dots: Map<Int, Int>)

    private var bars = emptyMap<Int, Bar>()
    private var average = emptyMap<Int, Float>()
    private var strips = emptyList<Strip>()
    private var first = 1
    private var last = 1
    private var label: (Int) -> String = { it.toString() }

    var selected: Int? = null
        private set
    var onPointSelected: ((point: Int?) -> Unit)? = null

    /** Whether lifting the finger lets go of the picked point, having touched down on it */
    private var tapClears = false

    private val ink = context.getColorFromAttr(R.attr.primaryColor)
    private val density = resources.displayMetrics.density
    private val gridPaint = Paint().apply { strokeWidth = 1f; color = ColorUtils.setAlphaComponent(ink, 30) }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(ink, 40) }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val averagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = ink
    }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(ink, 120)
        textSize = 9.5f * resources.displayMetrics.scaledDensity
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(ink, 140)
        textSize = 10f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = ink
    }
    private val rect = RectF()
    private val path = Path()

    private val chartHeight = 130.dpToPx()
    private val stripHeight = 24.dpToPx()
    private val top = 4.dpToPx().toFloat()
    private val labelBand = 20.dpToPx()
    private val left = 2.dpToPx().toFloat()
    private val right get() = width - 2.dpToPx().toFloat()
    private val columnWidth get() = (right - left) / (last - first + 1)
    private val chartBottom get() = top + chartHeight

    /** [average] is drawn as a line through the days it has. The picked day is kept unless [clearSelection] or the range changed. */
    fun setData(
        bars: Map<Int, Bar>,
        average: Map<Int, Float>,
        strips: List<Strip>,
        first: Int,
        last: Int,
        label: (Int) -> String,
        clearSelection: Boolean = false,
    ) {
        if (clearSelection || first != this.first || last != this.last) selected = null
        val resize = strips.size != this.strips.size
        this.bars = bars
        this.average = average
        this.strips = strips
        this.first = first
        this.last = last
        this.label = label
        if (resize) requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(300.dpToPx(), widthMeasureSpec),
            resolveSize((top + chartHeight + 6.dpToPx() + strips.size * stripHeight).toInt() + labelBand, heightMeasureSpec)
        )
    }

    private fun columnLeft(point: Int) = left + (point - first) * columnWidth

    private fun y(score: Float) = chartBottom - score / 5 * chartHeight

    override fun onDraw(canvas: Canvas) {
        val cellWidth = columnWidth
        val gap = minOf(cellWidth * 0.25f, 3.dpToPx().toFloat())
        val corner = minOf(cellWidth / 4, 3.dpToPx().toFloat())

        for (score in 1..5) canvas.drawLine(left, y(score.toFloat()), right, y(score.toFloat()), gridPaint)

        for (point in first..last) {
            val x = columnLeft(point)
            val bar = bars[point]
            if (bar == null) {
                rect.set(x + gap / 2, chartBottom - 2 * density, x + cellWidth - gap / 2, chartBottom)
                canvas.drawRoundRect(rect, corner, corner, emptyPaint)
            } else {
                rect.set(x + gap / 2, y(bar.score), x + cellWidth - gap / 2, chartBottom)
                canvas.drawRoundRect(rect, corner, corner, barPaint.apply { color = bar.color })
            }
        }

        path.reset()
        var drawing = false
        for (point in first..last) {
            val value = average[point]
            if (value == null) {
                drawing = false
                continue
            }
            val x = columnLeft(point) + cellWidth / 2
            if (drawing) path.lineTo(x, y(value)) else path.moveTo(x, y(value))
            drawing = true
        }
        canvas.drawPath(path, averagePaint)

        // Each strip: its name, then its dots below
        val dotRadius = minOf(cellWidth / 2 - 1.dpToPx(), 4.dpToPx().toFloat())
        var stripTop = chartBottom + 6.dpToPx()
        strips.forEach { strip ->
            canvas.drawText(strip.name, left, stripTop + namePaint.textSize, namePaint)
            val middle = stripTop + stripHeight - dotRadius - 2.dpToPx()
            strip.dots.forEach { (point, color) ->
                if (point in first..last) canvas.drawCircle(columnLeft(point) + cellWidth / 2, middle, dotRadius, barPaint.apply { this.color = color })
            }
            stripTop += stripHeight
        }

        selected?.let {
            val x = columnLeft(it)
            val half = selectPaint.strokeWidth / 2
            rect.set(x + half, top - half, x + cellWidth - half, stripTop + half)
            canvas.drawRoundRect(rect, corner, corner, selectPaint)
        }

        // Up to about six labels, always including the first and last
        val span = last - first
        val step = maxOf(1, (span + 5) / 6)
        val labels = (first..last step step).toMutableList()
        if (labels.last() != last) {
            if (last - labels.last() < step / 2.0 && labels.size > 1) labels.removeAt(labels.lastIndex)
            labels.add(last)
        }
        val labelY = height - 5.dpToPx().toFloat()
        labels.forEach { point -> canvas.drawText(label(point), columnLeft(point) + cellWidth / 2, labelY, textPaint) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val point = (first + ((event.x - left) / columnWidth).toInt()).coerceIn(first, last)
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
