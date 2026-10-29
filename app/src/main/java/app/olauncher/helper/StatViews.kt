package app.olauncher.helper

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import app.olauncher.R
import app.olauncher.data.MoodStore

/**
 * A stat to show: one of the built-ins or a custom stat, with the name and colour to use. [question] is
 * the daily check's title for it; empty for the default.
 */
data class StatInfo(val id: String, val name: String, val color: Int, val scale: Int, val question: String = "") {
    val isBuiltIn get() = id == MoodStore.MOOD || id == MoodStore.USAGE
}

/**
 * Every stat in the order picked by dragging the tabs ([MoodStore.Data.order]). Stats not placed yet
 * follow: Mood, Screen time, then custom stats in the order they were added.
 */
fun Context.allStats(data: MoodStore.Data): List<StatInfo> {
    val stats = listOf(
        StatInfo(MoodStore.MOOD, getString(R.string.mood), data.moodColor, 5, data.moodQuestion),
        StatInfo(MoodStore.USAGE, getString(R.string.screen_time), data.usageColor, 5),
    ) + data.stats.map { StatInfo(it.id, it.name, it.color, it.scale, it.question) }
    return stats.sortedBy { stat -> data.order.indexOf(stat.id).takeIf { it >= 0 } ?: Int.MAX_VALUE }
}

/** The mood and the custom stats asked every day, in [allStats] order. */
fun Context.dailyStats(data: MoodStore.Data): List<StatInfo> =
    allStats(data).filter { it.id == MoodStore.MOOD || data.stat(it.id)?.daily == true }

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
