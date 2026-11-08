package app.olauncher.helper

import android.content.Context
import app.olauncher.data.BackupManager

/**
 * How the pages are arranged, picked in Settings or by holding and dragging things into place: what
 * shows by the clock on Home, in which order and row, and where the clock is; which Stats Home cards
 * show and in which order, and where its bubble sits; each kind of stat page's cards. Anything new
 * shows, near where it would have been, until moved or hidden.
 */
object Arrangement {

    // --- By the clock ---

    const val WEATHER = "weather"
    const val SCREEN_TIME = "screenTime"
    const val MONEY = "money"
    val HOME_ITEMS = listOf(WEATHER, SCREEN_TIME, MONEY)

    /** Which of [HOME_ITEMS] sit in the row under the one beside the clock; money does at first */
    fun underClock(context: Context): Set<String> =
        prefs(context).getString("$HOME.under", null)?.split(',')?.filter { it.isNotEmpty() }?.toSet() ?: setOf(MONEY)

    fun setUnderClock(context: Context, keys: Set<String>) {
        prefs(context).edit().putString("$HOME.under", keys.joinToString(",")).apply()
        BackupManager.requestSync(context)
    }

    /** The clock at the bottom of the home screen rather than the top */
    fun clockAtBottom(context: Context) = prefs(context).getBoolean("$HOME.clockBottom", false)

    fun setClockAtBottom(context: Context, bottom: Boolean) {
        prefs(context).edit().putBoolean("$HOME.clockBottom", bottom).apply()
        BackupManager.requestSync(context)
    }

    // --- Stats Home ---

    const val DAILY = "daily"
    const val TRENDS = "trends"
    const val REMEMBER = "remember"
    const val INSIGHTS = "insights"
    const val THIS_WEEK = "thisWeek"
    const val COMPARE = "compare"
    val STATS_HOME_CARDS = listOf(DAILY, TRENDS, REMEMBER, INSIGHTS, THIS_WEEK, COMPARE)

    /** Where Stats Home's bubble sits: bottom (0) or top (1), and start, centre or end */
    class Spot(val top: Boolean, val side: Int)

    val BUBBLE_SPOTS = listOf(false, true).flatMap { top -> (0..2).map { Spot(top, it) } }

    fun bubbleSpot(context: Context): Int = prefs(context).getInt("$STATS_HOME.bubble", 1).coerceIn(0, BUBBLE_SPOTS.lastIndex)

    fun setBubbleSpot(context: Context, spot: Int) {
        prefs(context).edit().putInt("$STATS_HOME.bubble", spot).apply()
        BackupManager.requestSync(context)
    }

    // --- Any list ---

    /** [all]'s keys in the order picked for [name], with any not placed yet near where they'd be in [all] */
    fun order(context: Context, name: String, all: List<String>): List<String> =
        merge(all, prefs(context).getString("$name.order", null)?.split(',').orEmpty())

    fun hidden(context: Context, name: String): Set<String> =
        prefs(context).getString("$name.hidden", null)?.split(',')?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    /** [all] in order without the hidden ones */
    fun shown(context: Context, name: String, all: List<String>) = hidden(context, name).let { h -> order(context, name, all).filter { it !in h } }

    fun save(context: Context, name: String, order: List<String>, hidden: Set<String>) {
        prefs(context).edit().putString("$name.order", order.joinToString(",")).putString("$name.hidden", hidden.joinToString(",")).apply()
        BackupManager.requestSync(context)
    }

    /**
     * Keeps the [saved] order of [natural]'s keys, dropping ones that are gone; one not in it yet goes
     * just after the key it follows in [natural] (or first), so new things turn up where they'd be.
     */
    fun merge(natural: List<String>, saved: List<String>): List<String> {
        val result = saved.filter { it in natural }.distinct().toMutableList()
        natural.forEachIndexed { i, key ->
            if (key in result) return@forEachIndexed
            val previous = natural.subList(0, i).lastOrNull { it in result }
            result.add(if (previous == null) 0 else result.indexOf(previous) + 1, key)
        }
        return result
    }

    /** Moves [key] in [order] to just before [before], or to the end for null */
    fun moved(order: List<String>, key: String, before: String?): List<String> {
        val rest = order.filter { it != key }.toMutableList()
        val at = before?.let { rest.indexOf(it) }?.takeIf { it >= 0 } ?: rest.size
        rest.add(at, key)
        return rest
    }

    /** Puts every arrangement back as it first was */
    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
        BackupManager.requestSync(context)
    }

    const val HOME = "home"
    const val STATS_HOME = "statsHome"

    /** A kind of stat page's cards, by their titles */
    fun statPage(kind: String) = "statPage.$kind"

    const val PREFS = "arrangement"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
