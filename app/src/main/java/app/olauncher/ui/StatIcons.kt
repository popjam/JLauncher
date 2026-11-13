package app.olauncher.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import app.olauncher.helper.StatInfo
import app.olauncher.helper.StatKind

/**
 * Small pixel-art icons, like the pixel faces: a stat's (picked by what it is or its name), the
 * devices screen time comes from, and Home. Each is an 11 by 11 grid, drawn square by square.
 */
object StatIcons {

    const val SIZE = 11

    /** The icon for [stat], or a plain diamond for one it doesn't know */
    fun forStat(stat: StatInfo): String {
        val name = stat.name.lowercase()
        return when {
            stat.kind == StatKind.MOOD -> MOOD
            stat.kind == StatKind.SCREEN -> SCREEN
            stat.kind == StatKind.SLEEP -> SLEEP
            stat.kind == StatKind.MUSIC -> MUSIC
            stat.kind == StatKind.SOCIAL -> SOCIAL
            stat.kind == StatKind.VIBECODING -> CODE
            stat.kind == StatKind.COFFEE -> COFFEE
            stat.kind == StatKind.MONEY -> MONEY
            stat.kind == StatKind.EXERCISE -> DUMBBELL
            stat.kind == StatKind.MEDITATION -> LOTUS
            stat.kind == StatKind.LIBIDO -> HEART
            stat.kind == StatKind.WORK -> BRIEFCASE
            stat.kind == StatKind.WEATHER -> SUN
            stat.kind == StatKind.PEOPLE -> CHAT
            "energy" in name -> BOLT
            "anxi" in name || "stress" in name -> ANXIETY
            "ocd" in name || "compuls" in name -> LOOP
            "depress" in name || "sad" in name -> RAIN
            "anger" in name || "angry" in name || "irrit" in name -> FLAME
            "creativ" in name || "idea" in name -> BULB
            "future" in name || "hope" in name || "motivat" in name -> SUNRISE
            "step" in name || "walk" in name -> STEPS
            stat.kind == StatKind.MEDICINE -> if ("sertraline" in name) CAPSULE else TABLET
            else -> DIAMOND
        }
    }

    fun drawable(icon: String, color: Int, sizePx: Int): Drawable = PixelIconDrawable(icon, color, sizePx)

    /**
     * Draws [icon] [size] wide with its top left at [left], [top], each pixel a square of the grid,
     * through [paint] in [color].
     */
    fun draw(canvas: Canvas, icon: String, left: Float, top: Float, size: Float, color: Int, paint: Paint) {
        val cell = size / SIZE
        paint.style = Paint.Style.FILL
        // A stat as dark as near-black would vanish on the dark background, so it's lifted a little
        paint.color = if (androidx.core.graphics.ColorUtils.calculateLuminance(color) < 0.05)
            androidx.core.graphics.ColorUtils.blendARGB(color, 0xFFFFFFFF.toInt(), 0.35f) else color
        val rows = icon.lines()
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, c ->
                // A hair over a cell, so neighbouring squares meet without seams
                if (c == 'X') canvas.drawRect(left + x * cell, top + y * cell, left + (x + 1) * cell + 0.5f, top + (y + 1) * cell + 0.5f, paint)
            }
        }
    }

    private class PixelIconDrawable(private val icon: String, private val color: Int, private val sizePx: Int) : Drawable() {
        private val paint = Paint()
        override fun draw(canvas: Canvas) {
            val b = bounds
            val size = minOf(b.width(), b.height()).toFloat()
            draw(canvas, icon, b.left + (b.width() - size) / 2, b.top + (b.height() - size) / 2, size, color, paint)
        }

        override fun getIntrinsicWidth() = sizePx
        override fun getIntrinsicHeight() = sizePx
        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private fun icon(vararg rows: String) = rows.joinToString("\n")

    val GEAR = icon(
        ".....X.....",
        "..X.XXX.X..",
        ".XXXXXXXXX.",
        "..XXX.XXX..",
        ".XXX...XXX.",
        "XXX.....XXX",
        ".XXX...XXX.",
        "..XXX.XXX..",
        ".XXXXXXXXX.",
        "..X.XXX.X..",
        ".....X.....",
    )
    val WARNING = icon(
        ".....X.....",
        "....XXX....",
        "....X.X....",
        "...XX.XX...",
        "...XX.XX...",
        "..XXX.XXX..",
        "..XXX.XXX..",
        ".XXXXXXXXX.",
        ".XXXX.XXXX.",
        "XXXXXXXXXXX",
        "...........",
    )
    val BOOK = icon(
        "...........",
        ".XXXX.XXXX.",
        "X....X....X",
        "X.XX.X.XX.X",
        "X....X....X",
        "X.XX.X.XX.X",
        "X....X....X",
        "X.XX.X.XX.X",
        "X....X....X",
        ".XXXXXXXXX.",
        "...........",
    )
    val BRIEFCASE = icon(
        "...........",
        "....XXX....",
        "...X...X...",
        "XXXXXXXXXXX",
        "XXXXXXXXXXX",
        "XXXXXXXXXXX",
        "XXXX...XXXX",
        "XXXXXXXXXXX",
        "XXXXXXXXXXX",
        "XXXXXXXXXXX",
        "...........",
    )
    val HOME = icon(
        ".....X.....",
        "....XXX....",
        "...XXXXX...",
        "..XXXXXXX..",
        ".XXXXXXXXX.",
        "XXXXXXXXXXX",
        ".XXXXXXXXX.",
        ".XXX...XXX.",
        ".XXX...XXX.",
        ".XXX...XXX.",
        ".XXX...XXX.",
    )
    val MOOD = icon(
        "...XXXXX...",
        ".XX.....XX.",
        ".X.......X.",
        "X..X...X..X",
        "X..X...X..X",
        "X.........X",
        "X.X.....X.X",
        "X..X...X..X",
        ".X..XXX..X.",
        ".XX.....XX.",
        "...XXXXX...",
    )
    val SCREEN = icon(
        "XXXXXXXXXXX",
        "X.........X",
        "X.........X",
        "X.........X",
        "X.........X",
        "X.........X",
        "XXXXXXXXXXX",
        "....XXX....",
        "....XXX....",
        "..XXXXXXX..",
        "...........",
    )
    val PHONE = icon(
        "..XXXXXXX..",
        "..X.....X..",
        "..X.....X..",
        "..X.....X..",
        "..X.....X..",
        "..X.....X..",
        "..X.....X..",
        "..X.....X..",
        "..XXXXXXX..",
        "..XXX.XXX..",
        "..XXXXXXX..",
    )
    val LAPTOP = icon(
        "...........",
        ".XXXXXXXXX.",
        ".X.......X.",
        ".X.......X.",
        ".X.......X.",
        ".X.......X.",
        ".XXXXXXXXX.",
        "XXXXXXXXXXX",
        "XXXXXXXXXXX",
        "...........",
        "...........",
    )
    val TV = icon(
        "...X...X...",
        "....X.X....",
        ".....X.....",
        "XXXXXXXXXXX",
        "X.........X",
        "X.........X",
        "X.........X",
        "X.........X",
        "XXXXXXXXXXX",
        "..XX...XX..",
        "...........",
    )
    val BOLT = icon(
        ".....XXXX..",
        "....XXXX...",
        "...XXXX....",
        "..XXXX.....",
        ".XXXXXXXX..",
        "....XXXX...",
        "...XXXX....",
        "..XXX......",
        "..XX.......",
        ".X.........",
        "...........",
    )
    val HEART = icon(
        "...........",
        ".XXX...XXX.",
        "XXXXX.XXXXX",
        "XXXXXXXXXXX",
        "XXXXXXXXXXX",
        ".XXXXXXXXX.",
        "..XXXXXXX..",
        "...XXXXX...",
        "....XXX....",
        ".....X.....",
        "...........",
    )
    val ANXIETY = icon(
        "...........",
        "X.....X....",
        ".X...X.X...",
        "..X.X...X.X",
        "...X.....X.",
        "...........",
        "X.....X....",
        ".X...X.X...",
        "..X.X...X.X",
        "...X.....X.",
        "...........",
    )
    val LOOP = icon(
        "...XXXXX...",
        "..X.....X.X",
        ".X.......XX",
        ".X......XXX",
        ".X.........",
        "...........",
        ".........X.",
        "XXX......X.",
        "XX.......X.",
        "X.X.....X..",
        "...XXXXX...",
    )
    val RAIN = icon(
        "....XXX....",
        "..XXXXXXX..",
        ".XXXXXXXXX.",
        "XXXXXXXXXXX",
        "XXXXXXXXXXX",
        "...........",
        "..X..X..X..",
        ".X..X..X...",
        "...........",
        "..X..X..X..",
        ".X..X..X...",
    )
    val FLAME = icon(
        ".....X.....",
        ".....XX....",
        "....XXX....",
        "...XXXX.X..",
        "...XXXXXX..",
        "..XXXXXXXX.",
        ".XXXX.XXXX.",
        ".XXX...XXX.",
        ".XXX...XXX.",
        "..XXX.XXX..",
        "...XXXXX...",
    )
    val BULB = icon(
        "...XXXXX...",
        "..X.....X..",
        ".X.......X.",
        ".X.......X.",
        ".X.......X.",
        "..X.....X..",
        "...X...X...",
        "...XXXXX...",
        "...........",
        "...XXXXX...",
        "....XXX....",
    )
    val SUNRISE = icon(
        ".....X.....",
        ".X...X...X.",
        "..X.....X..",
        "....XXX....",
        "...XXXXX...",
        "X.XXXXXXX.X",
        "..XXXXXXX..",
        "XXXXXXXXXXX",
        "...........",
        "..XXXXXXX..",
        "....XXX....",
    )
    val SLEEP = icon(
        "....XXX....",
        "..XXXX.....",
        ".XXXX......",
        ".XXX.......",
        "XXXX.......",
        "XXXX.......",
        "XXXX......X",
        ".XXXX....XX",
        ".XXXXXXXXX.",
        "..XXXXXXX..",
        "....XXX....",
    )
    val COFFEE = icon(
        "..X..X.....",
        ".X..X......",
        "..X..X.....",
        "...........",
        "XXXXXXXX...",
        "XXXXXXXXXX.",
        "XXXXXXXX..X",
        "XXXXXXXX..X",
        "XXXXXXXXXX.",
        ".XXXXXX....",
        "..XXXX.....",
    )
    val MONEY = icon(
        ".....X.....",
        "...XXXXX...",
        "..XX.X.XX..",
        "..XX.X.....",
        "...XXXX....",
        ".....XXX...",
        ".....X.XX..",
        "..XX.X.XX..",
        "...XXXXX...",
        ".....X.....",
        "...........",
    )
    val DUMBBELL = icon(
        "...........",
        "...........",
        ".XX.....XX.",
        ".XX.....XX.",
        "XXX.....XXX",
        "XXXXXXXXXXX",
        "XXX.....XXX",
        ".XX.....XX.",
        ".XX.....XX.",
        "...........",
        "...........",
    )
    val LOTUS = icon(
        ".....X.....",
        "....XXX....",
        "....XXX....",
        ".X..XXX..X.",
        ".XX.XXX.XX.",
        ".XXXXXXXXX.",
        "..XXXXXXX..",
        "...XXXXX...",
        "...........",
        "XXXXXXXXXXX",
        "...........",
    )
    val CAPSULE = icon(
        "...........",
        "...........",
        "..XXXXXXX..",
        ".XXXXX...X.",
        "XXXXXX....X",
        "XXXXXX....X",
        "XXXXXX....X",
        ".XXXXX...X.",
        "..XXXXXXX..",
        "...........",
        "...........",
    )
    val TABLET = icon(
        "...XXXXX...",
        ".XXXXXXXXX.",
        ".XXXXXXXXX.",
        "XXXXXXXXXXX",
        "XXXXXXXXXXX",
        "...........",
        "XXXXXXXXXXX",
        "XXXXXXXXXXX",
        ".XXXXXXXXX.",
        ".XXXXXXXXX.",
        "...XXXXX...",
    )
    val SOCIAL = icon(
        "..XX....XX.",
        ".XXXX..XXXX",
        ".XXXX..XXXX",
        "..XX....XX.",
        "...........",
        ".XXXX..XXXX",
        "XXXXXXXXXXX",
        "XXXXXXXXXXX",
        "XXXXXXXXXXX",
        "...........",
        "...........",
    )
    val STEPS = icon(
        ".XX........",
        "XXXX.......",
        "XXXX...XX..",
        "XXXX..XXXX.",
        ".XX...XXXX.",
        "......XXXX.",
        ".XX....XX..",
        ".XX........",
        ".......XX..",
        ".......XX..",
        "...........",
    )
    val CODE = icon(
        "...........",
        ".......X...",
        "..X...X.X..",
        ".X....X..X.",
        "X....X....X",
        ".X...X...X.",
        "..X.X...X..",
        "....X......",
        "...X.......",
        "...........",
        "...........",
    )
    val MUSIC = icon(
        "....XXXXXXX",
        "....XXXXXXX",
        "....X.....X",
        "....X.....X",
        "....X.....X",
        "....X.....X",
        "..XXX...XXX",
        ".XXXX..XXXX",
        ".XXXX..XXXX",
        "..XX....XX.",
        "...........",
    )
    val BATTERY = icon(
        "...........",
        "....XXX....",
        "..XXXXXXX..",
        "..X.....X..",
        "..X.....X..",
        "..X.....X..",
        "..XXXXXXX..",
        "..XXXXXXX..",
        "..XXXXXXX..",
        "..XXXXXXX..",
        "...........",
    )
    val SUN = icon(
        ".....X.....",
        ".X...X...X.",
        "..X.....X..",
        "....XXX....",
        "...XXXXX...",
        "XX.XXXXX.XX",
        "...XXXXX...",
        "....XXX....",
        "..X.....X..",
        ".X...X...X.",
        ".....X.....",
    )
    val CHAT = icon(
        "...........",
        ".XXXXXXXXX.",
        "XXXXXXXXXXX",
        "XX.......XX",
        "XX.X.X.X.XX",
        "XX.......XX",
        "XXXXXXXXXXX",
        ".XXXXXXXXX.",
        "..XX.......",
        ".XX........",
        "...........",
    )
    val PAUSE = icon(
        "...........",
        "...........",
        "..XXX.XXX..",
        "..XXX.XXX..",
        "..XXX.XXX..",
        "..XXX.XXX..",
        "..XXX.XXX..",
        "..XXX.XXX..",
        "..XXX.XXX..",
        "...........",
        "...........",
    )
    val RECORD = icon(
        "...........",
        "...........",
        "....XXX....",
        "...XXXXX...",
        "..XXXXXXX..",
        "..XXXXXXX..",
        "..XXXXXXX..",
        "...XXXXX...",
        "....XXX....",
        "...........",
        "...........",
    )
    val PLAY = icon(
        "...........",
        "..XX.......",
        "..XXXX.....",
        "..XXXXXX...",
        "..XXXXXXXX.",
        "..XXXXXXXXX",
        "..XXXXXXXX.",
        "..XXXXXX...",
        "..XXXX.....",
        "..XX.......",
        "...........",
    )
    val STOP = icon(
        "...........",
        "...........",
        "..XXXXXXX..",
        "..XXXXXXX..",
        "..XXXXXXX..",
        "..XXXXXXX..",
        "..XXXXXXX..",
        "..XXXXXXX..",
        "..XXXXXXX..",
        "...........",
        "...........",
    )
    val CHECK = icon(
        "...........",
        "...........",
        ".........XX",
        "........XX.",
        ".......XX..",
        "XX....XX...",
        ".XX..XX....",
        "..XXXX.....",
        "...XX......",
        "...........",
        "...........",
    )
    val CROSS = icon(
        "...........",
        "...........",
        "..XX...XX..",
        "...XX.XX...",
        "....XXX....",
        "....XXX....",
        "...XX.XX...",
        "..XX...XX..",
        "...........",
        "...........",
        "...........",
    )
    val MIC = icon(
        "....XXX....",
        "...XXXXX...",
        "...XXXXX...",
        "...XXXXX...",
        ".X.XXXXX.X.",
        ".X.XXXXX.X.",
        ".X..XXX..X.",
        "..X.....X..",
        "...XXXXX...",
        ".....X.....",
        "...XXXXX...",
    )
    val DIAMOND = icon(
        "...........",
        "...........",
        "....XXX....",
        "...XXXXX...",
        "..XXXXXXX..",
        "..XXXXXXX..",
        "..XXXXXXX..",
        "...XXXXX...",
        "....XXX....",
        "...........",
        "...........",
    )
}
