package app.olauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import app.olauncher.R
import app.olauncher.helper.getColorFromAttr
import kotlin.math.hypot

/**
 * Draws a [Scene] of simple shapes, laid out in code for the view's width by [setScene]'s builder, so
 * each card on a stat page can draw its own chart. Tapping one of the scene's targets outlines it and
 * reports its key to [onPicked]; tapping it again lets go. Taps only, so the page still scrolls.
 */
class StatSceneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Shapes are drawn in the order added. Sizes are in pixels; [dp] and [sp] convert. */
    class Scene(val context: Context, val width: Float) {
        var height = 0f
        internal val shapes = mutableListOf<(Canvas, Paint) -> Unit>()
        internal val targets = mutableListOf<Target>()

        private val metrics = context.resources.displayMetrics
        fun dp(value: Float) = value * metrics.density
        fun sp(value: Float) = value * metrics.scaledDensity

        fun rect(left: Float, top: Float, right: Float, bottom: Float, color: Int, corner: Float = 0f) {
            val rect = RectF(left, top, right, bottom)
            shapes.add { canvas, paint ->
                paint.style = Paint.Style.FILL
                paint.color = color
                canvas.drawRoundRect(rect, corner, corner, paint)
            }
        }

        fun outline(left: Float, top: Float, right: Float, bottom: Float, color: Int, corner: Float, width: Float) {
            val half = width / 2
            val rect = RectF(left + half, top + half, right - half, bottom - half)
            shapes.add { canvas, paint ->
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = width
                paint.color = color
                canvas.drawRoundRect(rect, corner, corner, paint)
            }
        }

        fun circle(x: Float, y: Float, radius: Float, color: Int) = shapes.add { canvas, paint ->
            paint.style = Paint.Style.FILL
            paint.color = color
            canvas.drawCircle(x, y, radius, paint)
        }

        fun ring(x: Float, y: Float, radius: Float, color: Int, width: Float) = shapes.add { canvas, paint ->
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = width
            paint.color = color
            canvas.drawCircle(x, y, radius - width / 2, paint)
        }

        fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float) = shapes.add { canvas, paint ->
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = width
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = color
            canvas.drawLine(x1, y1, x2, y2, paint)
        }

        /** A line through [points], broken wherever one is null. */
        fun polyline(points: List<PointF?>, color: Int, width: Float) {
            val path = Path()
            var drawing = false
            points.forEach { point ->
                if (point == null) drawing = false
                else {
                    if (drawing) path.lineTo(point.x, point.y) else path.moveTo(point.x, point.y)
                    drawing = true
                }
            }
            shapes.add { canvas, paint ->
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = width
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = color
                canvas.drawPath(path, paint)
            }
        }

        /** [y] is the baseline. */
        fun text(text: String, x: Float, y: Float, color: Int, size: Float, align: Paint.Align = Paint.Align.CENTER, bold: Boolean = false) =
            shapes.add { canvas, paint ->
                paint.style = Paint.Style.FILL
                paint.color = color
                paint.textSize = size
                paint.textAlign = align
                paint.isFakeBoldText = bold
                canvas.drawText(text, x, y, paint)
                paint.isFakeBoldText = false
            }

        fun textWidth(text: String, size: Float): Float = Paint().apply { textSize = size }.measureText(text)

        /** Tapping inside [area] picks [key], outlining [outline]. */
        fun target(key: Any, area: RectF, outline: RectF = area, corner: Float = dp(4f)) {
            targets.add(Target(key, area, outline, corner))
        }
    }

    class Target(val key: Any, val area: RectF, val outline: RectF, val corner: Float)

    private var builder: ((Scene) -> Unit)? = null
    private var scene: Scene? = null

    var picked: Any? = null
        private set
    var onPicked: ((key: Any?) -> Unit)? = null

    private val ink = context.getColorFromAttr(R.attr.primaryColor)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = ink
    }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    /** [build] lays the scene out for the width it's given and sets its height. Lets go of the picked target. */
    fun setScene(build: (Scene) -> Unit) {
        builder = build
        scene = null
        picked = null
        requestLayout()
        invalidate()
    }

    private fun sceneFor(width: Int): Scene? {
        val build = builder ?: return null
        scene?.takeIf { it.width == width.toFloat() }?.let { return it }
        return Scene(context, width.toFloat()).also {
            build(it)
            scene = it
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = sceneFor(width - paddingLeft - paddingRight)?.height ?: 0f
        setMeasuredDimension(width, resolveSize(height.toInt() + paddingTop + paddingBottom + 1, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val scene = sceneFor(width - paddingLeft - paddingRight) ?: return
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        scene.shapes.forEach { it(canvas, paint) }
        scene.targets.firstOrNull { it.key == picked }?.let {
            val half = pickPaint.strokeWidth / 2
            val o = it.outline
            canvas.drawRoundRect(o.left + half, o.top + half, o.right - half, o.bottom - half, it.corner, it.corner, pickPaint)
        }
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val targets = scene?.targets.orEmpty()
        if (targets.isEmpty()) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                if (hypot(event.x - downX, event.y - downY) > touchSlop) return true
                val x = event.x - paddingLeft
                val y = event.y - paddingTop
                val hit = targets.firstOrNull { it.area.contains(x, y) }?.key
                // Tapping the picked one again, or nothing, lets go
                picked = if (hit == picked) null else hit
                onPicked?.invoke(picked)
                invalidate()
            }
        }
        return true
    }
}
