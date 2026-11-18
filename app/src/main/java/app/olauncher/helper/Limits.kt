package app.olauncher.helper

import android.content.Context
import android.os.SystemClock
import app.olauncher.data.Prefs
import app.olauncher.helper.usageStats.EventLogWrapper
import java.time.LocalDate
import java.time.ZoneId
import kotlin.concurrent.thread

/**
 * Daily limits and screen time stages: how much each app may be used a day (time and opens), and how
 * lockdown tightens as the day's screen time grows. Limits apply every day; they reset at midnight.
 *
 * The rules are kept with the settings (backed up); today's opens and warnings are kept on their own
 * (only for this phone and day). Today's usage is read from the phone's usage data now and then, off
 * the main thread, and the last reading is used for checks.
 */
object Limits {

    /** An app's daily limits: minutes of use and opens, 0 for none */
    data class Rule(val minutes: Int = 0, val opens: Int = 0) {
        val isEmpty get() = minutes <= 0 && opens <= 0
    }

    val MINUTE_CHOICES = listOf(0, 5, 15, 30, 60, 120, 180)
    val OPEN_CHOICES = listOf(0, 1, 3, 5, 10, 20)

    /** When the time left gets this low, it's said once */
    const val WARN_MS = 5 * 60_000L

    // --- Rules, written as "package:minutes:opens" separated by ";" ---

    /** Anything unreadable, and rules with no limits, are skipped */
    fun parseRules(text: String): Map<String, Rule> = text.split(';').mapNotNull { part ->
        val bits = part.trim().split(':')
        if (bits.size != 3 || bits[0].isBlank()) return@mapNotNull null
        val minutes = bits[1].toIntOrNull() ?: return@mapNotNull null
        val opens = bits[2].toIntOrNull() ?: return@mapNotNull null
        val rule = Rule(minutes.coerceAtLeast(0), opens.coerceAtLeast(0))
        if (rule.isEmpty) null else bits[0] to rule
    }.toMap()

    fun formatRules(rules: Map<String, Rule>): String =
        rules.filterValues { !it.isEmpty }.entries.joinToString(";") { (pkg, rule) -> "$pkg:${rule.minutes}:${rule.opens}" }

    fun rules(prefs: Prefs) = parseRules(prefs.appLimits)

    fun rule(prefs: Prefs, pkg: String) = rules(prefs)[pkg] ?: Rule()

    /** How many installed apps have a limit, a pause or a choice of time */
    fun appsWithRules(context: android.content.Context, prefs: Prefs): Int {
        AppPause.migrateIfNeeded(prefs)
        return (rules(prefs).keys + prefs.pausedApps + prefs.useForApps).count { context.isPackageInstalled(it) }
    }

    fun setRule(prefs: Prefs, pkg: String, rule: Rule) {
        prefs.appLimits = formatRules(rules(prefs) + (pkg to rule))
    }

    /** Whether going from [old] to [new] loosens a limit: raised, or taken off */
    fun loosens(old: Int, new: Int) = old > 0 && (new <= 0 || new > old)

    // --- Why an app is over its limit ---

    enum class Over { TIME, OPENS }

    /** Which limit [rule] stops, after [usedMs] of use and [opens] opens today; a new open is about to count when [opening] */
    fun over(rule: Rule, usedMs: Long, opens: Int, opening: Boolean): Over? = when {
        rule.minutes > 0 && usedMs >= rule.minutes * 60_000L -> Over.TIME
        rule.opens > 0 && (if (opening) opens >= rule.opens else opens > rule.opens) -> Over.OPENS
        else -> null
    }

    /** Whether to say only a little time is left: under [WARN_MS], not yet out, and not said today */
    fun shouldWarn(rule: Rule, usedMs: Long, warned: Boolean): Boolean {
        if (rule.minutes <= 0 || warned) return false
        val left = rule.minutes * 60_000L - usedMs
        return left in 1..WARN_MS
    }

    // --- Screen time stages ---

    /** 0 for none, else the stage today's [usedMs] has reached, with [stages] in minutes (0 is off) */
    fun stage(stages: List<Int>, usedMs: Long): Int {
        var reached = 0
        stages.forEachIndexed { i, minutes -> if (minutes > 0 && usedMs >= minutes * 60_000L) reached = i + 1 }
        return reached
    }

    /** The next stage's number and minutes after [usedMs], or null */
    fun nextStage(stages: List<Int>, usedMs: Long): Pair<Int, Int>? = stages.withIndex()
        .firstOrNull { (_, minutes) -> minutes > 0 && usedMs < minutes * 60_000L }?.let { it.index + 1 to it.value }

    /** Whether [minutes] can be stage [index]'s time: after every earlier stage that's set and before every later one */
    fun fitsStage(stages: List<Int>, index: Int, minutes: Int): Boolean {
        if (minutes <= 0) return true
        return stages.withIndex().all { (i, other) ->
            other <= 0 || i == index || (i < index && other < minutes) || (i > index && other > minutes)
        }
    }

    fun parseStages(text: String): List<Int> = text.split(',').map { it.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0 }
        .let { (it + List(STAGES) { 0 }).take(STAGES) }

    fun formatStages(stages: List<Int>) = stages.joinToString(",")

    const val STAGES = 3
    val STAGE_DEFAULTS = listOf(120, 180, 240)
    val STAGE_CHOICES = listOf(0, 30, 60, 90, 120, 150, 180, 240, 300, 360, 480)

    fun stages(prefs: Prefs) = parseStages(prefs.lockdownStages)

    fun stageToday(context: Context, prefs: Prefs = Prefs(context)) = stage(stages(prefs), totalToday(context))

    // --- Websites ---

    /** A site's host from what an address bar shows: "https://www.reddit.com/r/x" is "reddit.com" */
    fun host(address: String): String? {
        var text = address.trim().lowercase()
        if (text.isEmpty() || ' ' in text) return null
        text = text.substringAfter("://")
        text = text.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        text = text.removePrefix("www.").removePrefix("m.")
        return text.takeIf { '.' in it && it.length > 3 }
    }

    /** Whether [host] is [site] or one of its subdomains */
    fun matches(host: String, site: String): Boolean {
        val s = site.trim().lowercase().removePrefix("www.")
        return host == s || host.endsWith(".$s")
    }

    /** What's typed as a site, made plain ("https://Reddit.com/" is "reddit.com"), or null if it isn't one */
    fun cleanSite(text: String) = host(text)

    /** The sites of well-known apps, so blocking the app blocks its site too */
    val APP_SITES = mapOf(
        "com.reddit.frontpage" to listOf("reddit.com", "redd.it"),
        "com.instagram.android" to listOf("instagram.com"),
        "com.instagram.barcelona" to listOf("threads.net", "threads.com"),
        "com.facebook.katana" to listOf("facebook.com", "fb.com"),
        "com.facebook.lite" to listOf("facebook.com", "fb.com"),
        "com.twitter.android" to listOf("twitter.com", "x.com"),
        "com.zhiliaoapp.musically" to listOf("tiktok.com"),
        "com.ss.android.ugc.trill" to listOf("tiktok.com"),
        "com.google.android.youtube" to listOf("youtube.com", "youtu.be"),
        "com.pinterest" to listOf("pinterest.com"),
        "com.tumblr" to listOf("tumblr.com"),
        "com.linkedin.android" to listOf("linkedin.com"),
        "com.snapchat.android" to listOf("snapchat.com"),
        "tv.twitch.android.app" to listOf("twitch.tv"),
        "xyz.blueskyweb.app" to listOf("bsky.app"),
        "com.ninegag.android.app" to listOf("9gag.com"),
        "com.netflix.mediaclient" to listOf("netflix.com"),
        "com.tinder" to listOf("tinder.com"),
    )

    // --- Today, on this phone ---

    private fun today(context: Context) = context.getSharedPreferences(TODAY_PREFS, Context.MODE_PRIVATE).also { prefs ->
        val date = LocalDate.now()
        if (isNewDay(prefs.getString("date", null), date)) prefs.edit().clear().putString("date", date.toString()).apply()
    }

    /** Whether today's opens and warnings, saved on [saved], are from before [today] and start again */
    fun isNewDay(saved: String?, today: LocalDate) = saved != today.toString()

    fun opensToday(context: Context, pkg: String) = today(context).getInt("opens:$pkg", 0)

    fun countOpen(context: Context, pkg: String) {
        val prefs = today(context)
        prefs.edit().putInt("opens:$pkg", prefs.getInt("opens:$pkg", 0) + 1).apply()
    }

    fun warnedToday(context: Context, pkg: String) = today(context).getBoolean("warned:$pkg", false)

    fun setWarned(context: Context, pkg: String) = today(context).edit().putBoolean("warned:$pkg", true).apply()

    // --- Today's usage ---

    @Volatile private var usage: Map<String, Long> = emptyMap()
    @Volatile private var usageDate: LocalDate? = null
    @Volatile private var readAt = 0L
    @Volatile private var reading = false

    /** Today's use of [pkg] at the last reading */
    fun usedToday(context: Context, pkg: String): Long {
        refresh(context)
        return if (usageDate == LocalDate.now()) usage[pkg] ?: 0L else 0L
    }

    /** Today's screen time at the last reading */
    fun totalToday(context: Context): Long {
        refresh(context)
        return if (usageDate == LocalDate.now()) usage.values.sum() else 0L
    }

    /** Callbacks waiting for the reading under way */
    private val waiting = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Reads today's usage again in the background, at most every [READ_MS] unless [force]; [then] runs once the reading is current */
    fun refresh(context: Context, force: Boolean = false, then: (() -> Unit)? = null) {
        val now = SystemClock.elapsedRealtime()
        if (reading) {
            then?.let { waiting += it }
            return
        }
        if (!force && usageDate == LocalDate.now() && now - readAt < READ_MS) return run { then?.invoke() }
        if (!context.appUsagePermissionGranted()) return
        reading = true
        then?.let { waiting += it }
        val app = context.applicationContext
        thread {
            try {
                val date = LocalDate.now()
                val start = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val events = EventLogWrapper(app)
                usage = events.aggregateForegroundStats(events.getForegroundStatsByTimestamps(start, System.currentTimeMillis()))
                    .associate { it.applicationId to it.timeUsed }
                usageDate = date
                readAt = SystemClock.elapsedRealtime()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                reading = false
                val done = waiting.toList()
                waiting.clear()
                done.forEach { runCatching { it() } }
            }
        }
    }

    private const val TODAY_PREFS = "limits_today"
    private const val READ_MS = 30_000L
}
