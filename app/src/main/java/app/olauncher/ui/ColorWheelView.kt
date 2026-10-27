package app.olauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import app.olauncher.helper.dpToPx
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Hue around the edge, saturation from the centre out. Brightness is picked separately. */
class ColorWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var hue = 0f
        private set
    var saturation = 1f
        private set

    /** Called while dragging, with the new hue (0-360) and saturation (0-1). */
    var onChange: ((hue: Float, saturation: Float) -> Unit)? = null

    private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f.dpToPxF()
        color = Color.WHITE
    }
    private val knobShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f.dpToPxF()
        color = Color.BLACK
    }

    /** The knob's fill, so it shows the full colour including brightness */
    var knobColor = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    fun setColor(hue: Float, saturation: Float) {
        this.hue = hue
        this.saturation = saturation
        invalidate()
    }

    private val radius get() = min(width, height) / 2f - KNOB_RADIUS.dpToPx()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = resolveSize(140.dpToPx(), widthMeasureSpec).coerceAtMost(resolveSize(140.dpToPx(), heightMeasureSpec))
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val cx = w / 2f
        val cy = h / 2f
        val hues = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        // Sweep gradients start at 3 o'clock and go clockwise, like the angle in onTouchEvent
        val sweep = SweepGradient(cx, cy, hues, null)
        val toWhite = RadialGradient(cx, cy, radius.coerceAtLeast(1f), Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        wheelPaint.shader = ComposeShader(sweep, toWhite, PorterDuff.Mode.SRC_OVER)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, radius, wheelPaint)

        val angle = Math.toRadians(hue.toDouble())
        val x = cx + (cos(angle) * saturation * radius).toFloat()
        val y = cy + (sin(angle) * saturation * radius).toFloat()
        val knob = KNOB_RADIUS.dpToPx().toFloat()
        knobFill.color = knobColor
        canvas.drawCircle(x, y, knob, knobFill)
        canvas.drawCircle(x, y, knob + knobRing.strokeWidth / 2, knobShadow)
        canvas.drawCircle(x, y, knob, knobRing)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                // Keep the dialog from scrolling while dragging
                parent?.requestDisallowInterceptTouchEvent(true)
                val dx = event.x - width / 2f
                val dy = event.y - height / 2f
                hue = ((Math.toDegrees(atan2(dy, dx).toDouble()) + 360) % 360).toFloat()
                saturation = (hypot(dx, dy) / radius).coerceIn(0f, 1f)
                onChange?.invoke(hue, saturation)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun Float.dpToPxF() = this * resources.displayMetrics.density

    companion object {
        private const val KNOB_RADIUS = 9
    }
}
