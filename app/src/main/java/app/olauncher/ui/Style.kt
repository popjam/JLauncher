package app.olauncher.ui

import android.graphics.Color
import app.olauncher.helper.dpToPx

/**
 * The app's look in one place: its colours, text sizes and corner radii. Views are built in code, so
 * they take these instead of numbers of their own; the XML drawables use the same radii through
 * `@dimen/radius_small` and `@dimen/radius_large`.
 *
 * - Colours: the few the interface itself uses ([ACCENT_ON_DARK], [WARNING], [DANGER], …), then
 *   [Palette], the hues given to stats, places, supplements and devices, then a few fixed scales.
 *   Text and lines are otherwise the theme's ink (`R.attr.primaryColor`) at some alpha.
 * - Text sizes, in sp: [TEXT_SMALL] for notes, captions and secondary lines, [TEXT_BODY] for rows and
 *   ordinary text, [TEXT_TITLE] for headings, [TEXT_LARGE] for big status lines, [TEXT_FIGURE] for
 *   a page's headline number. Drawings ([StatSceneView] and the charts) size their own labels.
 * - Corners: [radiusSmall] for chips, buttons, rows' boxes and small panels; [radiusLarge] for cards,
 *   sections, popups and search boxes.
 */
object Style {

    // --- Interface colours ---

    /** The accent: section headings and anything marked as set, on a dark page and on a light one */
    const val ACCENT_ON_DARK = 0xFF7FD1C2.toInt()
    const val ACCENT_ON_LIGHT = 0xFF00796B.toInt()
    fun accent(darkPage: Boolean) = if (darkPage) ACCENT_ON_DARK else ACCENT_ON_LIGHT

    /** Lockdown's blue, and the ink on it when it's a fill on a dark page */
    const val LOCKDOWN_ON_DARK = 0xFF8FB3FF.toInt()
    const val LOCKDOWN_ON_LIGHT = 0xFF3D5AFE.toInt()
    const val LOCKDOWN_INK_ON_DARK = 0xFF0B1020.toInt()
    fun lockdown(darkPage: Boolean) = if (darkPage) LOCKDOWN_ON_DARK else LOCKDOWN_ON_LIGHT

    /** Something to look at: a problem to fix, tracking paused */
    const val WARNING = 0xFFFFB300.toInt()

    /** Something on that shouldn't be, or a limit gone over */
    const val DANGER = 0xFFEF5350.toInt()

    /** Danger, softer: Delete buttons, over a limit in charts */
    const val DANGER_SOFT = 0xFFE57373.toInt()

    /** All well */
    const val GOOD = 0xFF66BB6A.toInt()

    /** Text on a light fill (a light stat colour), and on a dark one */
    const val INK_ON_LIGHT = 0xFF111111.toInt()
    const val INK_ON_DARK = Color.WHITE
    fun inkOn(fillIsLight: Boolean) = if (fillIsLight) INK_ON_LIGHT else INK_ON_DARK

    /** The solid colour behind settings sections and search boxes */
    const val PANEL_DARK = 0xFF151716.toInt()
    const val PANEL_LIGHT = 0xFFF4F5F4.toInt()
    fun panel(darkPage: Boolean) = if (darkPage) PANEL_DARK else PANEL_LIGHT

    /** A panel raised over the others (Overview's bubble) on a dark page */
    const val PANEL_RAISED_DARK = 0xFF202024.toInt()

    /** Over the page while something's open on it */
    const val SCRIM = 0x33000000

    /** Over a blocked app: nearly black, so the app doesn't show through */
    const val SHADE_BLOCK = 0xF2000000.toInt()

    /** Over a settings page or an app with a notice: dimmed */
    const val SHADE_DIM = 0xB3000000.toInt()

    /** Marked words in a journal note */
    const val HIGHLIGHT = 0x6EFFD54F

    /** The newsletter reader's paper, its text, and its bars, light and dark */
    const val PAPER_LIGHT = 0xFFF6F4EF.toInt()
    const val PAPER_DARK = Color.BLACK
    const val PAPER_INK_DARK = 0xFFE6E6E6.toInt()
    const val PAPER_BAR_LIGHT = 0xFFE9E5DC.toInt()

    /** The printed report, which is always black on white */
    val PRINT_INK = Color.rgb(25, 25, 28)
    val PRINT_GREY = Color.rgb(120, 120, 128)
    val PRINT_LINE = Color.rgb(225, 225, 230)
    val PRINT_EMPTY = Color.rgb(240, 240, 243)
    val PRINT_BLANK = Color.rgb(214, 214, 220)

    /** The hues things are coloured by: stats, places, supplements, medicines, devices */
    object Palette {
        const val TEAL = 0xFF4DB6AC.toInt()
        const val TEAL_DEEP = 0xFF26A69A.toInt()
        const val MINT = 0xFF4FC3A1.toInt()
        const val CYAN = 0xFF4DD0E1.toInt()
        const val SKY = 0xFF4FC3F7.toInt()
        const val BLUE = 0xFF64B5F6.toInt()
        const val BLUE_DEEP = 0xFF5B8DEF.toInt()
        const val PERIWINKLE = LOCKDOWN_ON_DARK
        const val INDIGO = 0xFF7986CB.toInt()
        const val DEEP_PURPLE = 0xFF9575CD.toInt()
        const val LAVENDER = 0xFFB388FF.toInt()
        const val PURPLE = 0xFFBA68C8.toInt()
        const val MAGENTA = 0xFFE05FA8.toInt()
        const val PINK = 0xFFF06292.toInt()
        const val RED = DANGER_SOFT
        const val CORAL = 0xFFFF8A65.toInt()
        const val ORANGE = 0xFFFFB74D.toInt()
        const val GOLD = 0xFFF2B33D.toInt()
        const val AMBER = 0xFFFFCA28.toInt()
        const val YELLOW = 0xFFFFD54F.toInt()
        const val LIME = 0xFFAED581.toInt()
        const val GREEN = 0xFF81C784.toInt()
        const val BROWN = 0xFFA1887F.toInt()
        const val GREY = 0xFF90A4AE.toInt()
    }

    /** Light and dark shades of a kind of stat's icon, for its pictures */
    object Shades {
        val MEDICINE = 0xFFC4A3F5.toInt() to 0xFF4A148C.toInt()
        val COFFEE = 0xFFD9B48F.toInt() to 0xFF6D4325.toInt()
        val MONEY = 0xFFFFE08A.toInt() to 0xFFB8860B.toInt()
        val EXERCISE = 0xFFA7F3EB.toInt() to 0xFF00897B.toInt()
        val MUSIC = 0xFFA5ECF5.toInt() to 0xFF00838F.toInt()
        val WORK = 0xFFC5CAE9.toInt() to 0xFF3949AB.toInt()
    }

    /** Sleep's faces, most sleep first: bright blue down to slate */
    val SLEEP_BLUES = intArrayOf(0xFF8FB8F7.toInt(), 0xFF6E9FE8.toInt(), 0xFF5577C4.toInt(), 0xFF495E92.toInt(), 0xFF4A5568.toInt())

    /** Temperatures' colours from cold to hot, at temperatures in °C */
    val TEMPERATURE = listOf(
        0f to 0xFF2F4FC4.toInt(),  // dark blue
        10f to 0xFF4A9BE8.toInt(), // light blue
        17f to 0xFF56C7A8.toInt(), // teal
        22f to 0xFFF2C84B.toInt(), // yellow
        28f to 0xFFF2843A.toInt(), // orange
        34f to 0xFFE53935.toInt(), // red
    )

    // --- Text sizes (sp) ---

    const val TEXT_SMALL = 13f
    const val TEXT_BODY = 15f
    const val TEXT_TITLE = 20f
    const val TEXT_LARGE = 26f
    const val TEXT_FIGURE = 40f

    // --- Corners ---

    const val RADIUS_SMALL_DP = 14
    const val RADIUS_LARGE_DP = 24
    val radiusSmall get() = RADIUS_SMALL_DP.dpToPx().toFloat()
    val radiusLarge get() = RADIUS_LARGE_DP.dpToPx().toFloat()
}
