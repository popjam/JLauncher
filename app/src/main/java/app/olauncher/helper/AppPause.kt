package app.olauncher.helper

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.olauncher.data.Prefs

/**
 * Wellness: chosen apps go through a screen before they open, with a breathing pause, a choice of
 * how long to use the app for, or both. Each app has its own choice (in its rules on the Lockdown
 * page); at screen time stage 1, lockdown's blocked apps get the pause too.
 *
 * Getting through the screen gives the app a pass: until the home screen next shows, or until the
 * chosen time runs out, when the app is closed. Passes are only touched on the main thread.
 */
object AppPause {

    private const val UNTIL_HOME = Long.MAX_VALUE
    private val passes = HashMap<String, Long>()
    private val timers = HashMap<String, Runnable>()
    private val handler = Handler(Looper.getMainLooper())

    /** The choices of how long to use an app for, in minutes */
    val USE_FOR_SETS = listOf(listOf(2, 5, 10, 30), listOf(5, 15, 30, 60), listOf(1, 3, 5, 10))

    fun useForLengths(context: Context) = USE_FOR_SETS.getOrElse(context.getSharedPreferences("pause", Context.MODE_PRIVATE).getInt("useForSet", 0)) { USE_FOR_SETS[0] }

    fun setUseForSet(context: Context, set: Int) = context.getSharedPreferences("pause", Context.MODE_PRIVATE).edit().putInt("useForSet", set).apply()

    /**
     * Before each app had its own choice, one list of apps got the pause while it was switched on,
     * and the choice of time while that was switched on. Returns the apps that pause and the apps
     * that ask for a time.
     */
    fun migrate(pauseOn: Boolean, useForOn: Boolean, apps: Set<String>): Pair<Set<String>, Set<String>> =
        (if (pauseOn) apps else emptySet()) to (if (useForOn) apps else emptySet())

    /** Moves the old switches to each app's own choice, once */
    fun migrateIfNeeded(prefs: Prefs) {
        if (prefs.appRulesMigrated) return
        val (pause, useFor) = migrate(prefs.pauseAppsOn, prefs.useForOn, prefs.pausedApps)
        prefs.pausedApps = pause
        prefs.useForApps = useFor
        prefs.appRulesMigrated = true
    }

    fun pauses(context: Context, prefs: Prefs, packageName: String): Boolean {
        migrateIfNeeded(prefs)
        return packageName in prefs.pausedApps ||
            (packageName in prefs.lockdownBlockedApps && Lockdown.stagePauses(Limits.stageToday(context, prefs), packageName, prefs.lockdownBlockedApps))
    }

    /** "Use for" needs the accessibility service to close the app when the time is up. */
    fun asksUseFor(prefs: Prefs, packageName: String): Boolean {
        migrateIfNeeded(prefs)
        return packageName in prefs.useForApps && MyAccessibilityService.isRunning
    }

    /** Whether opening [packageName] should show the wellness screen */
    fun needsGate(context: Context, prefs: Prefs, packageName: String): Boolean {
        if (hasPass(packageName)) return false
        return pauses(context, prefs, packageName) || asksUseFor(prefs, packageName)
    }

    private fun hasPass(packageName: String): Boolean {
        val until = passes[packageName] ?: return false
        return until > SystemClock.elapsedRealtime()
    }

    /** Lets [packageName] open without the screen, for [minutes] or, if null, until the home screen shows. */
    fun grantPass(packageName: String, minutes: Int?) {
        timers.remove(packageName)?.let { handler.removeCallbacks(it) }
        if (minutes == null) {
            passes[packageName] = UNTIL_HOME
            return
        }
        val ms = minutes * 60_000L
        passes[packageName] = SystemClock.elapsedRealtime() + ms
        val timeUp = Runnable {
            timers.remove(packageName)
            passes.remove(packageName)
            MyAccessibilityService.closeIfShowing(packageName)
        }
        timers[packageName] = timeUp
        handler.postDelayed(timeUp, ms)
    }

    /** The home screen ends passes that weren't given a time. */
    fun onHomeShown() {
        passes.values.removeAll { it == UNTIL_HOME }
    }
}
