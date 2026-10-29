package app.olauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import app.olauncher.R
import app.olauncher.helper.dpToPx

/**
 * One-colour 16x16 pixel weather icon, drawn like [PixelFaceView]. [code] is a WMO weather code;
 * the colour follows the day's [uv] index, using the mood colours from low (happy) to very high (sad).
 */
class PixelWeatherView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint().apply { isAntiAlias = false }
    private var icon = CLOUDY

    var code: Int = 3
        set(value) {
            field = value
            icon = iconFor(value)
            invalidate()
        }

    var uv: Int = 0
        set(value) {
            field = value
            paint.color = ContextCompat.getColor(context, uvColor(value))
            invalidate()
        }

    init {
        uv = 0
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

        for (y in 0 until GRID) for (x in 0 until GRID) {
            if (icon[y][x]) canvas.drawRect(
                left + x * cell, top + y * cell, left + (x + 1) * cell, top + (y + 1) * cell, paint
            )
        }
    }

    companion object {
        private const val GRID = 16

        private fun uvColor(uv: Int) = when {
            uv <= 2 -> R.color.moodHappy
            uv <= 5 -> R.color.moodNeutral
            uv <= 7 -> R.color.moodMildlySad
            else -> R.color.moodSad
        }

        // '#' draws a pixel; 'o' marks the cloud's inside, which clears anything drawn behind it
        private val CLOUD = listOf(
            "......####......",
            ".....#oooo#.....",
            "..###oooooo##...",
            ".#ooooooooooo#..",
            "#oooooooooooooo#",
            "#oooooooooooooo#",
            "#oooooooooooooo#",
            ".##############.",
        )

        private val SUN = listOf(
            ".......##.......",
            ".##....##....##.",
            ".##..........##.",
            "......####......",
            "....##....##....",
            "....#......#....",
            "...#........#...",
            "##.#........#.##",
            "##.#........#.##",
            "...#........#...",
            "....#......#....",
            "....##....##....",
            "......####......",
            ".##..........##.",
            ".##....##....##.",
            ".......##.......",
        )

        private val SMALL_SUN = listOf(
            "...#....",
            "#..#..#.",
            ".#####..",
            ".#...#..",
            "##...###",
            ".#...#..",
            ".#####..",
            "#..#..#.",
        )

        private val FOG_LINES = listOf(
            "................",
            "................",
            "................",
            "..##########....",
            "................",
            "....###########.",
            "................",
            ".#######..###...",
            "................",
            "...##########...",
            "................",
            ".....########...",
        )

        private fun icon(build: Array<BooleanArray>.() -> Unit) =
            Array(GRID) { BooleanArray(GRID) }.apply(build)

        private fun Array<BooleanArray>.stamp(rows: List<String>, dx: Int = 0, dy: Int = 0) {
            rows.forEachIndexed { y, row ->
                row.forEachIndexed { x, c ->
                    val gx = x + dx
                    val gy = y + dy
                    if (gx in 0 until GRID && gy in 0 until GRID) when (c) {
                        '#' -> this[gy][gx] = true
                        'o' -> this[gy][gx] = false
                    }
                }
            }
        }

        /** Pairs of (row, column). */
        private fun Array<BooleanArray>.dots(vararg points: Pair<Int, Int>) =
            points.forEach { (y, x) -> this[y][x] = true }

        private val CLEAR = icon { stamp(SUN) }
        private val PARTLY_CLOUDY = icon { stamp(SMALL_SUN, 1, 1); stamp(CLOUD, 0, 7) }
        private val CLOUDY = icon { stamp(CLOUD, 0, 4) }
        private val RAIN = icon {
            stamp(CLOUD, 0, 1)
            dots(10 to 4, 11 to 3, 10 to 8, 11 to 7, 10 to 12, 11 to 11, 13 to 6, 14 to 5, 13 to 10, 14 to 9)
        }
        private val SHOWERS = icon {
            stamp(SMALL_SUN, 8, 0)
            stamp(CLOUD, 0, 3)
            dots(12 to 4, 13 to 3, 12 to 8, 13 to 7, 12 to 12, 13 to 11)
        }
        private val STORM = icon {
            stamp(CLOUD, 0, 1)
            dots(9 to 9, 10 to 8, 11 to 7, 12 to 6, 12 to 7, 12 to 8, 12 to 9, 13 to 8, 14 to 7, 15 to 6)
        }
        private val SNOW = icon {
            stamp(CLOUD, 0, 1)
            dots(11 to 4, 11 to 8, 11 to 12, 14 to 6, 14 to 10)
        }
        private val FOG = icon { stamp(FOG_LINES) }

        /** WMO weather interpretation codes, as used by Open-Meteo. */
        private fun iconFor(code: Int) = when (code) {
            0, 1 -> CLEAR
            2 -> PARTLY_CLOUDY
            45, 48 -> FOG
            in 51..67 -> RAIN
            in 71..77, 85, 86 -> SNOW
            in 80..82 -> SHOWERS
            in 95..99 -> STORM
            else -> CLOUDY
        }
    }
}
