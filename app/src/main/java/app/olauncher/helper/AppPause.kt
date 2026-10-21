package app.olauncher.helper

import android.content.Context
import app.olauncher.data.Prefs

/**
 * Wellness "pause before opening": chosen apps show a breathing pause before they open.
 *
 * Known addictive apps are added automatically, both when the feature is turned on and the first
 * time one installed later is opened. Each is only added once, so removing it from the list sticks.
 */
object AppPause {

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

    /** Whether opening [packageName] should show the pause. May add a newly installed addictive app. */
    fun shouldPause(prefs: Prefs, packageName: String): Boolean {
        if (!prefs.pauseAppsOn) return false
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
}
