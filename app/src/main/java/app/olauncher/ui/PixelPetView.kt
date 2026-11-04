package app.olauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import app.olauncher.helper.dpToPx

/**
 * The stats Home tab's pixel pet: a little creature whose body, eyes, mouth and props show how the
 * last few days went. Part of the Home tab ([StatsHome]); see [StatsHome.petLook] for what sets what.
 */
class PixelPetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Eyes { NORMAL, HAPPY, HEARTS, SLEEPY, WORRIED, STARS }

    /** Mood 1 (awful) to 5 (great) */
    enum class Mouth { WOBBLY, FROWN, FLAT, SMILE, GRIN }

    enum class Prop { HALO, HEADBAND, SWEAT, ZZZ, HEARTS, SPARKLES, MUG, PHONE, RAIN_CLOUD, SUN, TEAR, BLUSH, SAD_BROWS }

    class Look(val body: Int, val eyes: Eyes, val mouth: Mouth, val props: Set<Prop>)

    var look: Look = Look(Color.GRAY, Eyes.NORMAL, Mouth.FLAT, emptySet())
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(200.dpToPx(), widthMeasureSpec)
        setMeasuredDimension(width, resolveSize(minOf(width, 170.dpToPx()) * ROWS / COLUMNS, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val cell = minOf(width / COLUMNS, height / ROWS).toFloat()
        val left = (width - cell * COLUMNS) / 2
        val top = (height - cell * ROWS) / 2
        fun dot(x: Int, y: Int, color: Int) {
            paint.color = color
            canvas.drawRect(left + x * cell, top + y * cell, left + (x + 1) * cell, top + (y + 1) * cell, paint)
        }
        /** [rows] with a colour per letter in [colors]; anything else is left clear */
        fun sprite(x: Int, y: Int, rows: List<String>, colors: Map<Char, Int>, mirror: Boolean = false) {
            rows.forEachIndexed { r, row ->
                val line = if (mirror) row.reversed() else row
                line.forEachIndexed { c, ch -> colors[ch]?.let { dot(x + c, y + r, it) } }
            }
        }

        val look = look
        val props = look.props
        val body = look.body
        val shade = ColorUtils.blendARGB(body, Color.BLACK, 0.25f)
        val belly = ColorUtils.blendARGB(body, Color.WHITE, 0.3f)
        val colors = mapOf('X' to INK, 'W' to Color.WHITE, 'R' to HEART, 'Y' to GOLD, 'B' to WATER, 'P' to BLUSH_PINK,
            'G' to CLOUD, 'C' to MUG_BROWN, 'M' to CREAM, 'K' to PHONE_BODY, 'S' to SCREEN, 'D' to shade, 'L' to belly, 'O' to body,
            'Z' to Color.LTGRAY, 'H' to HEADBAND_RED)

        // Behind the body
        if (Prop.RAIN_CLOUD in props) {
            sprite(6, 0, listOf("...GGGG.....", ".GGGGGGGGG..", "GGGGGGGGGGGG", ".GGGGGGGGGG."), colors)
            listOf(8, 11, 14).forEach { dot(it, 4, WATER) }
        } else if (Prop.SUN in props) {
            sprite(0, 0, listOf("Y.Y.Y", ".YYY.", "YYYYY", ".YYY.", "Y.Y.Y"), colors)
        }
        if (Prop.HALO in props && Prop.RAIN_CLOUD !in props) sprite(7, 2, listOf(".YYYYYYYY.", "Y........Y", ".YYYYYYYY."), colors)

        // The body, shaded underneath, with a lighter belly
        val ox = 2
        val oy = 5
        sprite(ox, oy, BODY, mapOf('X' to body, 'D' to shade))
        sprite(ox + 6, oy + 10, listOf(".LLLLLL.", "LLLLLLLL", ".LLLLLL."), colors)

        // Eyes, the right one mirrored
        val eyes = when (look.eyes) {
            Eyes.NORMAL -> listOf(".WX", ".XX", ".XX")
            Eyes.HAPPY -> listOf("...", ".X.", "X.X")
            Eyes.HEARTS -> listOf("R.R", "RRR", ".R.")
            Eyes.SLEEPY -> listOf("...", "...", "XXX")
            Eyes.WORRIED -> listOf("XXX", "XWX", "XXX")
            Eyes.STARS -> listOf(".Y.", "YYY", ".Y.")
        }
        sprite(ox + 4, oy + 4, eyes, colors)
        sprite(ox + 13, oy + 4, eyes, colors, mirror = true)
        if (Prop.SAD_BROWS in props) {
            dot(ox + 4, oy + 3, INK); dot(ox + 5, oy + 2, INK)
            dot(ox + 15, oy + 3, INK); dot(ox + 14, oy + 2, INK)
        }
        if (Prop.BLUSH in props) {
            sprite(ox + 2, oy + 7, listOf("PP"), colors)
            sprite(ox + 16, oy + 7, listOf("PP"), colors)
        }
        if (Prop.TEAR in props) sprite(ox + 5, oy + 7, listOf("B", "B", "B"), colors)

        val mouth = when (look.mouth) {
            Mouth.GRIN -> listOf("XXXXXX", ".XRRX.")
            Mouth.SMILE -> listOf("X....X", ".XXXX.")
            Mouth.FLAT -> listOf("......", ".XXXX.")
            Mouth.FROWN -> listOf(".XXXX.", "X....X")
            Mouth.WOBBLY -> listOf(".X..X.", "X.XX.X")
        }
        sprite(ox + 7, oy + 8, mouth, colors)

        // In front
        if (Prop.HEADBAND in props) {
            for (x in ox until ox + 20) dot(x, oy + 2, HEADBAND_RED)
            sprite(ox + 20, oy + 2, listOf("H.", ".H"), colors)
        }
        if (Prop.SWEAT in props) sprite(22, 6, listOf(".B", "BB", "BB"), colors)
        if (Prop.ZZZ in props) sprite(19, 0, listOf("ZZZZ", "..Z.", ".Z..", "ZZZZ"), colors)
        if (Prop.HEARTS in props) {
            sprite(1, 1, listOf("R.R", "RRR", ".R."), colors)
            sprite(19, 3, listOf("R.R", "RRR", ".R."), colors)
        }
        if (Prop.SPARKLES in props) {
            sprite(0, 9, listOf(".Y.", "YYY", ".Y."), colors)
            sprite(21, 15, listOf(".Y.", "YYY", ".Y."), colors)
        }
        if (Prop.MUG in props) sprite(0, 15, listOf(".M..", "MMM.", "CCCC", "CCC.", "CCC."), colors)
        if (Prop.PHONE in props) sprite(21, 14, listOf("KKK", "KSK", "KSK", "KSK", "KKK"), colors)
    }

    companion object {
        private const val COLUMNS = 24
        private const val ROWS = 21

        private val BODY = listOf(
            ".XX..............XX.",
            ".XXX............XXX.",
            ".XXXXXXXXXXXXXXXXXX.",
            "XXXXXXXXXXXXXXXXXXXX",
            "XXXXXXXXXXXXXXXXXXXX",
            "XXXXXXXXXXXXXXXXXXXX",
            "XXXXXXXXXXXXXXXXXXXX",
            "XXXXXXXXXXXXXXXXXXXX",
            "XXXXXXXXXXXXXXXXXXXX",
            "XXXXXXXXXXXXXXXXXXXX",
            "XXXXXXXXXXXXXXXXXXXX",
            "DXXXXXXXXXXXXXXXXXXD",
            ".DXXXXXXXXXXXXXXXXD.",
            "..DDDDDDDDDDDDDDDD..",
            "..DDDD........DDDD..",
            "..DDD..........DDD..",
        )

        private const val INK = 0xFF151515.toInt()
        private const val HEART = 0xFFFF4D6D.toInt()
        private const val GOLD = 0xFFFFD54F.toInt()
        private const val WATER = 0xFF4FC3F7.toInt()
        private const val BLUSH_PINK = 0xFFF48FB1.toInt()
        private const val CLOUD = 0xFF8A8F98.toInt()
        private const val MUG_BROWN = 0xFFA0693A.toInt()
        private const val CREAM = 0xFFF5E6CC.toInt()
        private const val PHONE_BODY = 0xFF2B2B2B.toInt()
        private const val SCREEN = 0xFF80DEEA.toInt()
        private const val HEADBAND_RED = 0xFFEF5350.toInt()
    }
}
