package app.olauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import app.olauncher.helper.dpToPx

/** A short figure like "$21" in one-colour pixel digits, as tall as [PixelFaceView]'s face sits. */
class PixelMoneyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint().apply { isAntiAlias = false }

    var text: String = ""
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    var color: Int
        get() = paint.color
        set(value) {
            paint.color = value
            invalidate()
        }

    private val cell get() = 3.dpToPx()

    private fun columns() = (text.sumOf { (GLYPHS[it]?.first()?.length ?: 0) + 1 } - 1).coerceAtLeast(1)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(columns() * cell, widthMeasureSpec),
            resolveSize(32.dpToPx(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cell = cell
        var x = (width - columns() * cell) / 2f
        val top = (height - ROWS * cell) / 2f
        text.forEach { char ->
            val glyph = GLYPHS[char] ?: return@forEach
            glyph.forEachIndexed { row, line ->
                line.forEachIndexed { col, on ->
                    if (on == '1') canvas.drawRect(x + col * cell, top + row * cell, x + (col + 1) * cell, top + (row + 1) * cell, paint)
                }
            }
            x += (glyph.first().length + 1) * cell
        }
    }

    companion object {
        private const val ROWS = 7

        private val GLYPHS = mapOf(
            '0' to listOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
            '1' to listOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
            '2' to listOf("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
            '3' to listOf("11110", "00001", "00001", "01110", "00001", "00001", "11110"),
            '4' to listOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
            '5' to listOf("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
            '6' to listOf("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
            '7' to listOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
            '8' to listOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
            '9' to listOf("01110", "10001", "10001", "01111", "00001", "00010", "01100"),
            '$' to listOf("00100", "01111", "10100", "01110", "00101", "11110", "00100"),
            '-' to listOf("000", "000", "000", "111", "000", "000", "000"),
            'k' to listOf("1000", "1000", "1001", "1010", "1100", "1010", "1001"),
        )

        /** "$21", "-$20", "$12k" for [cents] (whole dollars, rounded down) */
        fun figure(cents: Long): String {
            val dollars = kotlin.math.abs(cents) / 100
            val number = if (dollars >= 10_000) "${dollars / 1000}k" else dollars.toString()
            return (if (cents < 0 && dollars > 0) "-$" else "$") + number
        }

        /** Green with all of it left, through yellow, to red at $0 and below */
        fun colorFor(context: Context, left: Long, allowance: Long): Int {
            val share = if (allowance <= 0) 0f else (left.toFloat() / allowance).coerceIn(0f, 1f)
            val place = (1 - share) * 4
            val from = place.toInt().coerceIn(0, 4)
            val to = (from + 1).coerceAtMost(4)
            return ColorUtils.blendARGB(PixelFaceView.moodColor(context, from), PixelFaceView.moodColor(context, to), place - from)
        }
    }
}
