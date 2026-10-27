package app.olauncher.helper

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import app.olauncher.R
import app.olauncher.data.MoodStore

/** A stat to show: one of the built-ins or a custom stat, with the name and colour to use. */
data class StatInfo(val id: String, val name: String, val color: Int, val scale: Int) {
    val isBuiltIn get() = id == MoodStore.MOOD || id == MoodStore.USAGE
}

/** Mood, Screen time, then custom stats in the order they were added. */
fun Context.allStats(data: MoodStore.Data): List<StatInfo> =
    listOf(moodStat(data), StatInfo(MoodStore.USAGE, getString(R.string.screen_time), data.usageColor, 5)) +
        data.stats.map { it.info() }

/** Mood, then the custom stats asked every day. */
fun Context.dailyStats(data: MoodStore.Data): List<StatInfo> = listOf(moodStat(data)) + data.dailyStats.map { it.info() }

private fun Context.moodStat(data: MoodStore.Data) = StatInfo(MoodStore.MOOD, getString(R.string.mood), data.moodColor, 5)

private fun MoodStore.Stat.info() = StatInfo(id, name, color, scale)

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
