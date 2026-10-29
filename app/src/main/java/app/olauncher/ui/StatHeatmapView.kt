package app.olauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import app.olauncher.R
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getColorFromAttr

/**
 * A row per stat and a column per day from [first] to [last], each cell coloured like the face picked
 * that day (happy green to sad red), so good and bad days line up across stats. Tapping picks a day,
 * outlined and reported to [onPointSelected].
 */
class StatHeatmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** [faces] maps days to faces; missing days are left blank. */
    class Row(val name: String, val color: Int, val faces: Map<Int, Int>)

    private var rows = emptyList<Row>()
    private var first = 1
    private var last = 1
    private var label: (Int) -> String = { it.toString() }

    var selected: Int? = null
        private set
    var onPointSelected: ((point: Int?) -> Unit)? = null

    private val ink = context.getColorFromAttr(R.attr.primaryColor)
    private val faceColors = IntArray(5) { PixelFaceView.moodColor(context, it) }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(ink, 22) }
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(ink, 200)
        textSize = 11f * resources.displayMetrics.scaledDensity
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(ink, 140)
        textSize = 10f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = ink
    }
    private val rect = RectF()

    private val rowHeight = 20.dpToPx()
    private val top = 4.dpToPx().toFloat()
    private val labelBand = 20.dpToPx()

    /** Where the cells start: room for the names, up to a third of the width */
    private var left = 0f
    private val right get() = width - 4.dpToPx().toFloat()
    private val columnWidth get() = (right - left) / (last - first + 1)

    /** The picked day is kept unless [clearSelection] or the range changed. */
    fun setData(rows: List<Row>, first: Int, last: Int, label: (Int) -> String, clearSelection: Boolean = false) {
        if (clearSelection || first != this.first || last != this.last) selected = null
        val resize = rows.size != this.rows.size
        this.rows = rows
        this.first = first
        this.last = last
        this.label = label
        updateLeft()
        if (resize) requestLayout()
        invalidate()
    }

    private fun updateLeft() {
        left = minOf((rows.maxOfOrNull { namePaint.measureText(it.name) } ?: 0f) + 22.dpToPx(), width / 3f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = updateLeft()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(300.dpToPx(), widthMeasureSpec),
            resolveSize((top + rows.size * rowHeight).toInt() + labelBand, heightMeasureSpec)
        )
    }

    private fun columnLeft(point: Int) = left + (point - first) * columnWidth

    override fun onDraw(canvas: Canvas) {
        val cellWidth = columnWidth
        val gap = minOf(cellWidth * 0.2f, 2.dpToPx().toFloat())
        val corner = minOf(cellWidth / 4, 3.dpToPx().toFloat())
        val dotRadius = 3.5f * resources.displayMetrics.density

        rows.forEachIndexed { i, row ->
            val rowTop = top + i * rowHeight
            val middle = rowTop + rowHeight / 2f

            dotPaint.color = row.color
            canvas.drawCircle(4.dpToPx() + dotRadius, middle, dotRadius, dotPaint)
            val name = TextUtils.ellipsize(row.name, namePaint, left - 18.dpToPx(), TextUtils.TruncateAt.END)
            canvas.drawText(name, 0, name.length, 14.dpToPx().toFloat(), middle - (namePaint.ascent() + namePaint.descent()) / 2, namePaint)

            for (point in first..last) {
                val x = columnLeft(point)
                rect.set(x + gap / 2, rowTop + gap / 2, x + cellWidth - gap / 2, rowTop + rowHeight - gap / 2)
                val face = row.faces[point]
                val paint = if (face == null) emptyPaint else cellPaint.apply { color = faceColors[face.coerceIn(0, 4)] }
                canvas.drawRoundRect(rect, corner, corner, paint)
            }
        }

        selected?.let {
            val x = columnLeft(it)
            val half = selectPaint.strokeWidth / 2
            rect.set(x + half, top - half, x + cellWidth - half, top + rows.size * rowHeight + half)
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
