package app.olauncher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import app.olauncher.helper.dpToPx

/**
 * A newsletter issue as a small book: a black cover with a spine down its left, which issue it is at
 * the top, the day large in the middle like a masthead, and along the bottom how far it's been read.
 * One on the phone has a solid edge; one still on Drive a dashed edge, faded, with an arrow down.
 * While it downloads, the cover fills from the bottom and the bar along the foot runs with it (moving
 * back and forth until there's a size to measure against).
 */
class IssueCoverView(context: Context) : View(context) {

    /** "DAILY" or "WEEKLY" */
    var kind = ""
    /** The day of the month, its month and weekday */
    var day = ""
    var month = ""
    var weekday = ""
    /** The time it was made, small at the foot */
    var time = ""
    var downloaded = false
    /** How much has been read, 0 to 1, or null when it hasn't been opened */
    var read: Float? = null
    /** Read to the end */
    var finished = false
    /** Downloading: 0 to 1, or -1 before there's a size to measure; null when not downloading */
    var progress: Float? = null
        set(value) {
            field = value
            if (value != null && value < 0f) startSweep() else stopSweep()
            invalidate()
        }
    /** Always light: the cover is black whatever the page is */
    var ink = Style.PAPER_INK_DARK
    var accent = Style.accent(true)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var sweep = 0f
    private var animator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, (width * 1.42f).toInt())
    }

    private fun startSweep() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_100L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { sweep = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopSweep() {
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        stopSweep()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = 3.dpToPx().toFloat()
        val spine = 7.dpToPx().toFloat()
        val pad = 10.dpToPx().toFloat()
        val downloading = progress != null
        val faded = !downloaded && !downloading
        val text = if (faded) ColorUtils.setAlphaComponent(ink, 110) else ink

        // The cover
        paint.style = Paint.Style.FILL
        paint.pathEffect = null
        paint.color = 0xFF0B0B0B.toInt()
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, paint)
        // Filling from the bottom while it downloads
        val fill = progress
        if (fill != null && fill > 0f) {
            paint.color = ColorUtils.setAlphaComponent(accent, 38)
            canvas.save()
            canvas.clipRect(0f, h * (1f - fill), w, h)
            canvas.drawRoundRect(rect, radius, radius, paint)
            canvas.restore()
        }
        // The spine: a slightly lighter band with a crease beside it
        paint.color = 0xFF1A1A1A.toInt()
        canvas.save()
        canvas.clipRect(0f, 0f, spine, h)
        canvas.drawRoundRect(rect, radius, radius, paint)
        canvas.restore()
        paint.color = ColorUtils.setAlphaComponent(ink, if (faded) 25 else 45)
        canvas.drawRect(spine, 0f, spine + 1.dpToPx(), h, paint)
        // The edge: solid when it's on the phone, dashed when it's still on Drive
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.dpToPx().toFloat()
        paint.color = ColorUtils.setAlphaComponent(ink, when {
            downloading -> 150
            downloaded -> 120
            else -> 70
        })
        if (faded) paint.pathEffect = DashPathEffect(floatArrayOf(4.dpToPx().toFloat(), 3.dpToPx().toFloat()), 0f)
        val half = paint.strokeWidth / 2
        rect.set(half, half, w - half, h - half)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.pathEffect = null
        paint.style = Paint.Style.FILL

        val left = spine + pad * 0.8f
        val right = w - pad
        val middle = (left + right) / 2

        // Which issue, along the top, with a rule under it
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = 9.5f * resources.displayMetrics.scaledDensity
        paint.letterSpacing = 0.16f
        paint.textAlign = Paint.Align.CENTER
        paint.color = ColorUtils.setAlphaComponent(text, 200)
        val top = pad + paint.textSize
        canvas.drawText(kind, middle, top, paint)
        paint.color = ColorUtils.setAlphaComponent(text, 60)
        canvas.drawRect(left, top + 6.dpToPx(), right, top + 6.dpToPx() + 1, paint)
        paint.letterSpacing = 0f

        // The day, large, with the month above and the weekday below
        paint.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        paint.textSize = (w * 0.36f).coerceAtMost(46f * resources.displayMetrics.scaledDensity)
        paint.color = text
        val dayBase = h * 0.6f
        canvas.drawText(day, middle, dayBase, paint)
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.textSize = 11f * resources.displayMetrics.scaledDensity
        paint.letterSpacing = 0.12f
        paint.color = ColorUtils.setAlphaComponent(text, 190)
        canvas.drawText(month, middle, dayBase - w * 0.36f - 2.dpToPx(), paint)
        canvas.drawText(weekday, middle, dayBase + paint.textSize + 6.dpToPx(), paint)
        paint.letterSpacing = 0f

        // The foot: download progress, how far it's been read, or an arrow down for one still on Drive
        val barY = h - pad - 2.dpToPx()
        val barH = (if (downloading) 3 else 2).dpToPx().toFloat()
        when {
            downloading -> {
                paint.color = ColorUtils.setAlphaComponent(ink, 40)
                canvas.drawRect(left, barY, right, barY + barH, paint)
                paint.color = accent
                val p = progress ?: 0f
                if (p < 0f) {
                    val span = (right - left) * 0.3f
                    val start = left + (right - left - span) * sweep
                    canvas.drawRect(start, barY, start + span, barY + barH, paint)
                } else canvas.drawRect(left, barY, left + (right - left) * p.coerceIn(0f, 1f), barY + barH, paint)
                if (p >= 0f) {
                    paint.textSize = 10f * resources.displayMetrics.scaledDensity
                    paint.color = ColorUtils.setAlphaComponent(ink, 200)
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText("${(p * 100).toInt()}%", middle, barY - 6.dpToPx(), paint)
                }
            }
            downloaded -> {
                val r = read ?: if (finished) 1f else null
                if (r != null) {
                    paint.color = ColorUtils.setAlphaComponent(ink, 35)
                    canvas.drawRect(left, barY, right, barY + barH, paint)
                    paint.color = if (finished) ColorUtils.setAlphaComponent(ink, 150) else accent
                    canvas.drawRect(left, barY, left + (right - left) * (if (finished) 1f else r.coerceIn(0.03f, 1f)), barY + barH, paint)
                }
                paint.textSize = 9.5f * resources.displayMetrics.scaledDensity
                paint.color = ColorUtils.setAlphaComponent(text, 110)
                canvas.drawText(time, middle, barY - 6.dpToPx(), paint)
            }
            else -> {
                val size = 15.dpToPx()
                val icon = StatIcons.drawable(ARROW, ColorUtils.setAlphaComponent(ink, 150), size)
                val x = (middle - size / 2).toInt()
                val y = (barY - size + barH).toInt()
                icon.setBounds(x, y, x + size, y + size)
                icon.draw(canvas)
            }
        }
    }

    companion object {
        /** An arrow down to a line: still to download */
        private val ARROW = listOf(
            "...........",
            ".....X.....",
            ".....X.....",
            ".....X.....",
            "..X..X..X..",
            "...X.X.X...",
            "....XXX....",
            ".....X.....",
            "...........",
            ".XXXXXXXXX.",
            "...........",
        ).joinToString("\n")
    }
}
