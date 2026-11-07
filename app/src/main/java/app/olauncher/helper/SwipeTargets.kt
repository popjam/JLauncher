package app.olauncher.helper

import android.content.Context

/** What swiping left or right on the home screen opens: the app picked for it, or one of the stats pages. */
object SwipeTargets {

    enum class Target { APP, STATS, STATS_HOME }

    /** The page opened by a swipe takes this argument: which way to drag it to go back (1 right, -1 left) */
    const val SWIPE_BACK = "swipeBack"

    fun get(context: Context, left: Boolean): Target =
        prefs(context).getString(key(left), null)?.let { runCatching { Target.valueOf(it) }.getOrNull() } ?: if (left) Target.STATS else Target.APP

    fun set(context: Context, left: Boolean, target: Target) = prefs(context).edit().putString(key(left), target.name).apply()

    private fun key(left: Boolean) = if (left) "left" else "right"

    private fun prefs(context: Context) = context.getSharedPreferences("swipe_targets", Context.MODE_PRIVATE)
}
