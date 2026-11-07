package app.olauncher.helper

import android.content.Context

/**
 * How the pages are arranged, as picked in Settings: what shows beside the clock and in which order,
 * and which Stats Home cards show and in which order. Anything new shows, at the end, until hidden.
 */
object Arrangement {

    // --- Beside the clock ---

    const val WEATHER = "weather"
    const val SCREEN_TIME = "screenTime"
    val HOME_ITEMS = listOf(WEATHER, SCREEN_TIME)

    // --- Stats Home ---

    const val DAILY = "daily"
    const val TRENDS = "trends"
    const val REMEMBER = "remember"
    const val INSIGHTS = "insights"
    const val THIS_WEEK = "thisWeek"
    const val COMPARE = "compare"
    val STATS_HOME_CARDS = listOf(DAILY, TRENDS, REMEMBER, INSIGHTS, THIS_WEEK, COMPARE)

    /** [all]'s keys in the order picked for [name], with any not placed yet at the end */
    fun order(context: Context, name: String, all: List<String>): List<String> {
        val saved = prefs(context).getString("$name.order", null)?.split(',')?.filter { it in all }.orEmpty()
        return saved + all.filter { it !in saved }
    }

    fun hidden(context: Context, name: String): Set<String> =
        prefs(context).getString("$name.hidden", null)?.split(',')?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    /** [all] in order without the hidden ones */
    fun shown(context: Context, name: String, all: List<String>) = hidden(context, name).let { h -> order(context, name, all).filter { it !in h } }

    fun save(context: Context, name: String, order: List<String>, hidden: Set<String>) =
        prefs(context).edit().putString("$name.order", order.joinToString(",")).putString("$name.hidden", hidden.joinToString(",")).apply()

    const val HOME = "home"
    const val STATS_HOME = "statsHome"

    private fun prefs(context: Context) = context.getSharedPreferences("arrangement", Context.MODE_PRIVATE)
}
