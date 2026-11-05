package app.olauncher.helper

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pickups (unlocks) and doomscrolling (stretches of [DOOM_MS] or more in one feed app: social and
 * video apps, and [FEEDS]) for each day, read from the same usage events as Screen time. Android
 * keeps about a week of them, so each day's counts are kept on the phone.
 */
object PhoneHabits {

    class Day(val pickups: Int, val doomMs: Long, val doomSessions: Int, val doomApp: String?)

    fun days(context: Context): Map<LocalDate, Day> = synchronized(LOCK) {
        val json = prefs(context).getString(KEY, null) ?: return emptyMap()
        val days = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        return days.keys().asSequence().mapNotNull { key ->
            val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@mapNotNull null
            val day = days.getJSONObject(key)
            date to Day(day.getInt("pickups"), day.getLong("doomMs"), day.optInt("doomSessions"), day.optString("doomApp").ifEmpty { null })
        }.toMap()
    }

    /** Reads today and the last [DAYS_BACK] days, at most every [REFRESH_MS]; on a background thread. */
    fun refresh(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(LAST_KEY, 0) < REFRESH_MS) return
        prefs.edit().putLong(LAST_KEY, now).apply()
        val usageStats = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val today = LocalDate.now()
        val read = (0..DAYS_BACK).mapNotNull { daysAgo ->
            val date = today.minusDays(daysAgo.toLong())
            runCatching { readDay(context, usageStats, date, now) }.getOrNull()?.let { date to it }
        }
        synchronized(LOCK) {
            val all = days(context).toMutableMap()
            // A day read again after Android let go of its events would come back empty
            read.forEach { (date, day) -> if (day.pickups > 0 || all[date] == null) all[date] = day }
            val cutoff = today.minusDays(KEEP_DAYS)
            save(context, all.filterKeys { it >= cutoff })
        }
    }

    private fun readDay(context: Context, usageStats: UsageStatsManager, date: LocalDate, now: Long): Day? {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = minOf(now, date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        if (end <= start) return null
        val events = usageStats.queryEvents(start, end)
        val event = UsageEvents.Event()
        var pickups = 0
        var screenOns = 0
        var sawAny = false
        // The feed app in front and since when, and the last stretch so a quick switch away carries on
        var app: String? = null
        var since = 0L
        var lastApp: String? = null
        var lastStart = 0L
        var lastEnd = 0L
        val stretches = mutableListOf<Triple<String, Long, Long>>()
        fun close(at: Long) {
            val current = app ?: return
            if (current == lastApp && since - lastEnd < GAP_MS) {
                // Joins the last stretch
                stretches.removeLastOrNull()
                stretches.add(Triple(current, lastStart, at))
            } else {
                stretches.add(Triple(current, since, at))
                lastStart = since
            }
            lastApp = current
            lastEnd = at
            app = null
        }
        val feeds = mutableMapOf<String, Boolean>()
        fun isFeed(pkg: String) = feeds.getOrPut(pkg) { isFeedApp(context, pkg) }
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            sawAny = true
            when (event.eventType) {
                UsageEvents.Event.KEYGUARD_HIDDEN -> pickups++
                UsageEvents.Event.SCREEN_INTERACTIVE -> screenOns++
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (event.packageName != app) {
                        close(event.timeStamp)
                        if (isFeed(event.packageName)) {
                            app = event.packageName
                            since = event.timeStamp
                        }
                    }
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> if (event.packageName == app) close(event.timeStamp)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> close(event.timeStamp)
            }
        }
        if (!sawAny) return null
        close(end)
        val doom = stretches.filter { it.third - it.second >= DOOM_MS }
        val doomApp = doom.groupBy { it.first }.maxByOrNull { (_, s) -> s.sumOf { it.third - it.second } }?.key?.let { label(context, it) }
        // Without a lock screen there are no unlocks, so the screen coming on counts instead
        return Day(if (pickups > 0) pickups else screenOns, doom.sumOf { it.third - it.second }, doom.size, doomApp)
    }

    private fun isFeedApp(context: Context, pkg: String): Boolean {
        if (pkg in FEEDS) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val category = runCatching { context.packageManager.getApplicationInfo(pkg, 0).category }.getOrNull()
        return category == ApplicationInfo.CATEGORY_SOCIAL || category == ApplicationInfo.CATEGORY_VIDEO
    }

    private fun label(context: Context, pkg: String) = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun save(context: Context, days: Map<LocalDate, Day>) {
        val json = JSONObject()
        days.forEach { (date, day) ->
            json.put(date.toString(), JSONObject().put("pickups", day.pickups).put("doomMs", day.doomMs)
                .put("doomSessions", day.doomSessions).put("doomApp", day.doomApp ?: ""))
        }
        prefs(context).edit().putString(KEY, json.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    const val DOOM_MINUTES = 20
    private const val DOOM_MS = DOOM_MINUTES * 60 * 1000L

    /** Away for less than this and back in the same app carries the stretch on */
    private const val GAP_MS = 60 * 1000L

    /** Feed apps that don't all say they're social or video */
    private val FEEDS = setOf(
        "com.google.android.youtube", "app.revanced.android.youtube", "com.instagram.android", "com.instagram.barcelona",
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.reddit.frontpage", "com.facebook.katana",
        "com.twitter.android", "com.snapchat.android", "tv.twitch.android.app", "com.tumblr", "com.pinterest",
        "com.linkedin.android", "com.netflix.mediaclient", "com.discord",
    )

    private val LOCK = Any()
    private const val PREFS = "phone_habits"
    private const val KEY = "days"
    private const val LAST_KEY = "refreshed"
    private const val REFRESH_MS = 15 * 60 * 1000L
    private const val DAYS_BACK = 6
    private const val KEEP_DAYS = 400L
}
