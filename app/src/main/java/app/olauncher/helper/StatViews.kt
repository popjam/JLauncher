package app.olauncher.helper

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import app.olauncher.R
import app.olauncher.data.MoodStore
import app.olauncher.ui.PixelFaceView

/**
 * A stat to show: one of the built-ins or a custom stat, with the name and colour to use. [question] is
 * the daily check's title for it; empty for the default. [labels] go under its faces, if any, left to
 * right whichever way round the faces are.
 */
data class StatInfo(
    val id: String,
    val name: String,
    val color: Int,
    val scale: Int,
    val question: String = "",
    val labels: List<String> = emptyList(),
    val inverted: Boolean = false,
    val phrases: List<String> = emptyList(),
    /** Counted from nothing: its leftmost face is none (not taken, no cups, $0), the rest step up */
    val medicine: Boolean = false,
    val nextDay: Boolean = false,
    val kind: StatKind = StatKind.PLAIN,
) {
    val isBuiltIn get() = id == MoodStore.MOOD || id == MoodStore.USAGE

    /** A real medicine (Sertraline, say), as opposed to something else counted from none, like Coffee */
    val isMedicine get() = kind == StatKind.MEDICINE

    /** The faces left to right: sad to happy, or happy to sad when [inverted]. */
    val faces get() = MoodStore.facesFor(scale).let { if (inverted) it.reversed() else it }

    /** A face's colour: Mood's for most stats; counted stats in shades of their [kind]'s colour, more being darker; Sleep in blues. */
    fun faceColor(context: Context, face: Int): Int {
        val shades = kind.shades
        if (medicine && shades != null) {
            val place = faces.indexOf(face)
            if (place <= 0) return androidx.core.content.ContextCompat.getColor(context, R.color.medicineNone)
            val shade = if (faces.size > 2) (place - 1f) / (faces.size - 2) else 0.5f
            return androidx.core.graphics.ColorUtils.blendARGB(shades.first, shades.second, shade)
        }
        if (medicine) return PixelFaceView.faceColor(context, face, faces, true)
        if (kind == StatKind.SLEEP) return SLEEP_BLUES[face.coerceIn(0, 4)]
        return PixelFaceView.faceColor(context, face, faces, false)
    }

    /** The colour its own cards are drawn in: Mood's for Mood, a medicine's purple, the theme's middle shade, or its own colour. */
    val themeColor: Int
        get() = kind.shades?.takeIf { medicine }?.let { androidx.core.graphics.ColorUtils.blendARGB(it.first, it.second, 0.45f) }
            ?: if (kind == StatKind.SLEEP) SLEEP_BLUES[1] else color

    /** A medicine's leftmost face means it wasn't taken, so the calendar and daily graph leave it blank. */
    fun isBlank(face: Int) = medicine && face == faces.first()
}

/** What a stat is, for the cards and colours its page gets. Worked out from its name or what fills it in. */
enum class StatKind(val shades: Pair<Int, Int>? = null) {
    MOOD, SCREEN,
    MEDICINE(0xFFC4A3F5.toInt() to 0xFF4A148C.toInt()),
    COFFEE(0xFFD9B48F.toInt() to 0xFF6D4325.toInt()),
    MONEY(0xFFFFE08A.toInt() to 0xFFB8860B.toInt()),
    EXERCISE(0xFFA7F3EB.toInt() to 0xFF00897B.toInt()),
    MUSIC(0xFFA5ECF5.toInt() to 0xFF00838F.toInt()),
    WORK(0xFFC5CAE9.toInt() to 0xFF3949AB.toInt()),
    MEDITATION, SLEEP, VIBECODING, SOCIAL, LIBIDO, PLAIN;
}

/** Sleep's faces by id, happiest (most sleep) first: bright blue down to slate */
private val SLEEP_BLUES = intArrayOf(0xFF8FB8F7.toInt(), 0xFF6E9FE8.toInt(), 0xFF5577C4.toInt(), 0xFF495E92.toInt(), 0xFF4A5568.toInt())

/** [StatKind] for a stat, from what fills it in or its name. */
fun Context.statKind(id: String, name: String, medicine: Boolean, data: MoodStore.Data): StatKind {
    if (id == MoodStore.MOOD) return StatKind.MOOD
    if (id == MoodStore.USAGE) return StatKind.SCREEN
    when (AutoStats.keyOf(this, id)) {
        AutoStats.SLEEP -> return StatKind.SLEEP
        AutoStats.MUSIC -> return StatKind.MUSIC
        AutoStats.SOCIAL -> return StatKind.SOCIAL
        AutoStats.VIBECODING -> return StatKind.VIBECODING
        AutoStats.WORK -> return StatKind.WORK
    }
    val lower = name.lowercase()
    return when {
        Spending.moneyStat(data)?.id == id -> StatKind.MONEY
        "coffee" in lower || "caffeine" in lower -> StatKind.COFFEE
        listOf("exercise", "workout", "gym", "training").any { it in lower } -> StatKind.EXERCISE
        "meditat" in lower -> StatKind.MEDITATION
        "libido" in lower -> StatKind.LIBIDO
        "sleep" in lower -> StatKind.SLEEP
        // Filled in by themselves, but known by name too (after a restore, say)
        lower == "music" -> StatKind.MUSIC
        lower == "social" -> StatKind.SOCIAL
        "vibecod" in lower -> StatKind.VIBECODING
        lower == "work" && medicine -> StatKind.WORK
        medicine -> StatKind.MEDICINE
        else -> StatKind.PLAIN
    }
}

/**
 * Every stat in the order picked by dragging the tabs ([MoodStore.Data.order]). Stats not placed yet
 * follow: Mood, Screen time, then custom stats in the order they were added.
 */
fun Context.allStats(data: MoodStore.Data): List<StatInfo> {
    val stats = listOf(
        StatInfo(MoodStore.MOOD, getString(R.string.mood), data.moodColor, 5, data.moodQuestion, inverted = data.moodInverted, kind = StatKind.MOOD),
        StatInfo(MoodStore.USAGE, getString(R.string.screen_time), data.usageColor, 5, inverted = data.usageInverted, kind = StatKind.SCREEN),
    ) + data.stats.map {
        StatInfo(it.id, it.name, it.color, it.scale, it.question, it.labels, it.inverted, it.phrases, it.medicine, it.nextDay,
            statKind(it.id, it.name, it.medicine, data))
    }
    return stats.sortedBy { stat -> data.order.indexOf(stat.id).takeIf { it >= 0 } ?: Int.MAX_VALUE }
}

/** The mood and the custom stats asked every day, in [allStats] order. */
fun Context.dailyStats(data: MoodStore.Data): List<StatInfo> =
    allStats(data).filter { it.id == MoodStore.MOOD || data.stat(it.id)?.daily == true }

/**
 * Fills [row] (horizontal, under a row of faces) with a label per face, each as wide as its face.
 * [alphas] matches the faces' so the picked one stands out. Labels are one line, so the row's height
 * doesn't change with them.
 */
fun bindFaceLabels(row: LinearLayout, labels: List<String>, alphas: List<Float>) {
    val context = row.context
    if (row.childCount != labels.size) {
        row.removeAllViews()
        repeat(labels.size) {
            row.addView(TextView(context, null, 0, R.style.TextSmall).apply {
                gravity = Gravity.CENTER
                textSize = 12f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(context.getColorFromAttr(R.attr.primaryColor))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }
    labels.forEachIndexed { i, label ->
        (row.getChildAt(i) as TextView).apply {
            // A space keeps the line's height when this face has no label
            text = label.ifEmpty { " " }
            alpha = 0.8f * (alphas.getOrNull(i) ?: 1f)
        }
    }
}

/** A small filled circle in a stat's colour. */
fun statDot(color: Int, sizePx: Int = 7.dpToPx()) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
    setSize(sizePx, sizePx)
}

/** Puts a [statDot] before the text, or removes it for null. */
fun TextView.setStatDot(color: Int?) {
    setCompoundDrawablesRelativeWithIntrinsicBounds(color?.let { statDot(it) }, null, null, null)
    compoundDrawablePadding = 6.dpToPx()
}
