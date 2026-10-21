package app.olauncher.helper

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.olauncher.data.Prefs

/**
 * Wellness: chosen apps go through a screen before they open, with a breathing pause, a choice of
 * how long to use the app for, or both.
 *
 * Known addictive apps are added automatically, both when a feature is turned on and the first
 * time one installed later is opened. Each is only added once, so removing it from the list sticks.
 *
 * Getting through the screen gives the app a pass: until the home screen next shows, or until the
 * chosen time runs out, when the app is closed. Passes are only touched on the main thread.
 */
object AppPause {

    private const val UNTIL_HOME = Long.MAX_VALUE
    private val passes = HashMap<String, Long>()
    private val timers = HashMap<String, Runnable>()
    private val handler = Handler(Looper.getMainLooper())

    private val ADDICTIVE_PACKAGES = setOf(
        // Reddit and Reddit clients
        "com.reddit.frontpage",
        "com.rubenmayayo.reddit",           // Boost
        "com.laurencedawson.reddit_sync",   // Sync
        "ml.docilealligator.infinityforreddit",
        "org.quantumbadger.redreader",
        "free.reddit.news",                 // Relay
        "o.o.joey",
        // Social media
        "com.instagram.android",
        "com.instagram.barcelona",          // Threads
        "com.facebook.katana",
        "com.facebook.lite",
        "com.twitter.android",              // X
        "com.snapchat.android",
        "com.zhiliaoapp.musically",         // TikTok
        "com.ss.android.ugc.trill",         // TikTok (some regions)
        "com.zhiliaoapp.musically.go",      // TikTok Lite
        "com.pinterest",
        "com.tumblr",
        "com.linkedin.android",
        "xyz.blueskyweb.app",
        "org.joinmastodon.android",
        "com.bereal.ft",
        "com.ninegag.android.app",
        "com.vkontakte.android",
        "com.sina.weibo",
        "com.ss.android.ugc.aweme",         // Douyin
        "com.smile.gifmaker",               // Kuaishou
        // Video and streaming
        "com.google.android.youtube",
        "app.revanced.android.youtube",
        "tv.twitch.android.app",
        // Dating
        "com.tinder",
        "com.bumble.app",
        "co.hinge.app",
    )

    /** "Use for" needs the accessibility service to close the app when the time is up. */
    fun useForActive(prefs: Prefs) = prefs.useForOn && MyAccessibilityService.isRunning

    /** Whether opening [packageName] should show the wellness screen. May add a newly installed addictive app. */
    fun needsGate(prefs: Prefs, packageName: String): Boolean {
        if (!prefs.pauseAppsOn && !useForActive(prefs)) return false
        if (hasPass(packageName)) return false
        if (packageName in prefs.pausedApps) return true
        if (packageName in ADDICTIVE_PACKAGES && packageName !in prefs.pauseAppsReviewed) {
            prefs.pausedApps = prefs.pausedApps + packageName
            prefs.pauseAppsReviewed = prefs.pauseAppsReviewed + packageName
            return true
        }
        return false
    }

    /** Adds the installed addictive apps that haven't been added before. */
    fun addAddictiveApps(context: Context, prefs: Prefs) {
        val toAdd = ADDICTIVE_PACKAGES
            .filter { it !in prefs.pauseAppsReviewed && context.isPackageInstalled(it) }
        if (toAdd.isEmpty()) return
        prefs.pausedApps = prefs.pausedApps + toAdd
        prefs.pauseAppsReviewed = prefs.pauseAppsReviewed + toAdd
    }

    fun setPaused(prefs: Prefs, packageName: String, paused: Boolean) {
        prefs.pausedApps = if (paused) prefs.pausedApps + packageName else prefs.pausedApps - packageName
        // Counts as reviewed either way, so a removed addictive app isn't added back
        if (packageName in ADDICTIVE_PACKAGES) prefs.pauseAppsReviewed = prefs.pauseAppsReviewed + packageName
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
