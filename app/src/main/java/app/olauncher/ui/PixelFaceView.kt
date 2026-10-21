package app.olauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.helper.dpToPx

/** One-colour 16x16 pixel face. [mood] is 0 (happy) to 4 (sad); the mouth and colour change with it. */
class PixelFaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint().apply { isAntiAlias = false }

    var mood: Int = MoodStore.NEUTRAL
        set(value) {
            field = value.coerceIn(0, 4)
            paint.color = moodColor(context, field)
            invalidate()
        }

    init {
        mood = MoodStore.NEUTRAL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val default = 32.dpToPx()
        setMeasuredDimension(
            resolveSize(default, widthMeasureSpec),
            resolveSize(default, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom)
        // Whole-pixel cells keep the edges crisp
        val cell = (size / GRID).coerceAtLeast(1)
        val left = paddingLeft + (width - paddingLeft - paddingRight - cell * GRID) / 2f
        val top = paddingTop + (height - paddingTop - paddingBottom - cell * GRID) / 2f

        fun dot(x: Int, y: Int) = canvas.drawRect(
            left + x * cell, top + y * cell, left + (x + 1) * cell, top + (y + 1) * cell, paint
        )

        OUTLINE.forEachIndexed { y, row ->
            row.forEachIndexed { x, c -> if (c == '#') dot(x, y) }
        }
        MOUTHS[mood].forEach { (y, xs) -> xs.forEach { x -> dot(x, y) } }
    }

    companion object {
        private const val GRID = 16

        private val OUTLINE = listOf(
            ".....######.....",
            "...##......##...",
            "..#..........#..",
            ".#............#.",
            ".#............#.",
            "#..............#",
            "#....#....#....#",
            "#....#....#....#",
            "#..............#",
            "#..............#",
            "#..............#",
            ".#............#.",
            ".#............#.",
            "..#..........#..",
            "...##......##...",
            ".....######.....",
        )

        // Row -> columns, per mood: smile, small smile, straight, small frown, frown
        private val MOUTHS = listOf(
            mapOf(9 to listOf(4, 11), 10 to listOf(5, 10), 11 to (6..9).toList()),
            mapOf(10 to listOf(4, 11), 11 to (5..10).toList()),
            mapOf(10 to (5..10).toList()),
            mapOf(10 to (5..10).toList(), 11 to listOf(4, 11)),
            mapOf(9 to (6..9).toList(), 10 to listOf(5, 10), 11 to listOf(4, 11)),
        )

        private val COLORS = intArrayOf(
            R.color.moodHappy, R.color.moodMildlyHappy, R.color.moodNeutral, R.color.moodMildlySad, R.color.moodSad
        )

        fun moodColor(context: Context, mood: Int) = ContextCompat.getColor(context, COLORS[mood.coerceIn(0, 4)])
    }
}
