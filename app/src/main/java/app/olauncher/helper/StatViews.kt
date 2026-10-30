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
) {
    val isBuiltIn get() = id == MoodStore.MOOD || id == MoodStore.USAGE

    /** The faces left to right: sad to happy, or happy to sad when [inverted]. */
    val faces get() = MoodStore.facesFor(scale).let { if (inverted) it.reversed() else it }
}

/**
 * Every stat in the order picked by dragging the tabs ([MoodStore.Data.order]). Stats not placed yet
 * follow: Mood, Screen time, then custom stats in the order they were added.
 */
fun Context.allStats(data: MoodStore.Data): List<StatInfo> {
    val stats = listOf(
        StatInfo(MoodStore.MOOD, getString(R.string.mood), data.moodColor, 5, data.moodQuestion, inverted = data.moodInverted),
        StatInfo(MoodStore.USAGE, getString(R.string.screen_time), data.usageColor, 5, inverted = data.usageInverted),
    ) + data.stats.map { StatInfo(it.id, it.name, it.color, it.scale, it.question, it.labels, it.inverted, it.phrases) }
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
