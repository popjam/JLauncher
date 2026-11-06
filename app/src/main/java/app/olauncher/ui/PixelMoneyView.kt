package app.olauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import app.olauncher.helper.dpToPx

/** A short figure like "$21" in one-colour pixel digits, drawn with the same size pixels as [PixelFaceView]'s face. */
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

    /** The face's pixel: 32dp over its 16 cells */
    private val cell get() = 2.dpToPx()

    private fun columns() = (text.sumOf { (GLYPHS[it]?.first()?.length ?: 0) + GAP } - GAP).coerceAtLeast(1)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(columns() * cell, widthMeasureSpec),
            resolveSize(ROWS * cell, heightMeasureSpec)
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
            x += (glyph.first().length + GAP) * cell
        }
    }

    companion object {
        /** Digits drawn with one-pixel lines, like the face, 11 pixels tall */
        private const val ROWS = 11
        private const val GAP = 2

        private val GLYPHS = mapOf(
            '0' to listOf("011110", "100001", "100001", "100001", "100001", "100001", "100001", "100001", "100001", "100001", "011110"),
            '1' to listOf("001100", "010100", "000100", "000100", "000100", "000100", "000100", "000100", "000100", "000100", "011111"),
            '2' to listOf("011110", "100001", "000001", "000001", "000010", "000100", "001000", "010000", "100000", "100000", "111111"),
            '3' to listOf("011110", "100001", "000001", "000001", "000001", "001110", "000001", "000001", "000001", "100001", "011110"),
            '4' to listOf("000010", "000110", "001010", "010010", "100010", "100010", "111111", "000010", "000010", "000010", "000010"),
            '5' to listOf("111111", "100000", "100000", "100000", "111110", "000001", "000001", "000001", "000001", "100001", "011110"),
            '6' to listOf("011110", "100000", "100000", "100000", "111110", "100001", "100001", "100001", "100001", "100001", "011110"),
            '7' to listOf("111111", "000001", "000001", "000010", "000010", "000100", "000100", "001000", "001000", "001000", "001000"),
            '8' to listOf("011110", "100001", "100001", "100001", "100001", "011110", "100001", "100001", "100001", "100001", "011110"),
            '9' to listOf("011110", "100001", "100001", "100001", "100001", "011111", "000001", "000001", "000001", "100001", "011110"),
            '$' to listOf("00100", "01111", "10100", "10100", "10100", "01110", "00101", "00101", "00101", "11110", "00100"),
            '-' to listOf("0000", "0000", "0000", "0000", "0000", "1111", "0000", "0000", "0000", "0000", "0000"),
            'k' to listOf("10000", "10000", "10000", "10001", "10010", "10100", "11000", "10100", "10010", "10001", "10001"),
        )

        /** "$21", "-$20", "$12k" for [cents] (whole dollars, rounded down) */
        fun figure(cents: Long): String {
            val dollars = kotlin.math.abs(cents) / 100
            val number = if (dollars >= 10_000) "${dollars / 1000}k" else dollars.toString()
            return (if (cents < 0 && dollars > 0) "-$" else "$") + number
        }

        /** The mood face's colours: its green with most of it left, through yellow, to its red at $0 and below */
        fun colorFor(context: Context, left: Long, allowance: Long): Int {
            val share = if (allowance <= 0 || left <= 0) 0f else (left.toFloat() / allowance).coerceIn(0f, 1f)
            val mood = when {
                left <= 0 -> 4
                share >= 0.6f -> 0
                share >= 0.4f -> 1
                share >= 0.2f -> 2
                else -> 3
            }
            return PixelFaceView.moodColor(context, mood)
        }
    }
}
