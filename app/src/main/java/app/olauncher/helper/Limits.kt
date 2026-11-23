package app.olauncher.helper

import android.content.Context
import android.os.SystemClock
import app.olauncher.data.Prefs
import app.olauncher.helper.usageStats.EventLogWrapper
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlin.concurrent.thread

/**
 * Daily limits: how much each app may be used a day (time and opens), each set for every day of the
 * week or day by day. They reset at midnight. A limit is strict (the app is blocked once it's reached)
 * or gentle (a screen says it's reached, and offers one more minute or ignoring it for the day).
 *
 * The rules are kept with the settings (backed up); today's opens, warnings and pauses are kept on their
 * own (only for this phone and day). Today's usage is read from the phone's usage data now and then,
 * off the main thread, and the last reading is used for checks.
 */
object Limits {

    /**
     * An app's daily limits: minutes of use and opens for each day of the week (Monday first), 0 for
     * none; [gentle] when reaching one shows a notice instead of blocking.
     */
    data class Rule(val minutes: List<Int> = ZEROS, val opens: List<Int> = ZEROS, val gentle: Boolean = false) {
        init {
            require(minutes.size == 7 && opens.size == 7)
        }

        fun minutesOn(day: DayOfWeek) = minutes[day.value - 1]
        fun opensOn(day: DayOfWeek) = opens[day.value - 1]

        val isEmpty get() = minutes.all { it <= 0 } && opens.all { it <= 0 }
        val hasMinutes get() = minutes.any { it > 0 }
        val hasOpens get() = opens.any { it > 0 }

        /** Whether every day has the same limits */
        val sameEveryDay get() = minutes.distinct().size == 1 && opens.distinct().size == 1

        fun withMinutes(day: DayOfWeek?, value: Int) = copy(minutes = setDay(minutes, day, value))
        fun withOpens(day: DayOfWeek?, value: Int) = copy(opens = setDay(opens, day, value))

        companion object {
            /** The same limits every day */
            fun every(minutes: Int = 0, opens: Int = 0, gentle: Boolean = false) = Rule(List(7) { minutes }, List(7) { opens }, gentle)

            /** [days] with [day]'s value (every day's, when null) set to [value] */
            private fun setDay(days: List<Int>, day: DayOfWeek?, value: Int) = days.mapIndexed { i, old -> if (day == null || i == day.value - 1) value else old }
        }
    }

    val ZEROS = List(7) { 0 }

    val MINUTE_CHOICES = listOf(0, 5, 15, 30, 60, 120, 180)
    val OPEN_CHOICES = listOf(0, 1, 3, 5, 10, 20)

    /** When the time left gets this low, it's said once */
    const val WARN_MS = 5 * 60_000L

    private fun today() = LocalDate.now().dayOfWeek

    // --- Rules, separated by ";" ---
    // "package:minutes:opens" with the same limits every day (how they were always written), or
    // "package:m1,…,m7:o1,…,o7" day by day from Monday; either with ":g" on the end when gentle.

    /** Anything unreadable, and strict rules with no limits, are skipped; a gentle one with none is kept, so the choice stays for the first limit */
    fun parseRules(text: String): Map<String, Rule> = text.split(';').mapNotNull { part ->
        val bits = part.trim().split(':')
        if (bits.size !in 3..4 || bits[0].isBlank()) return@mapNotNull null
        val minutes = days(bits[1]) ?: return@mapNotNull null
        val opens = days(bits[2]) ?: return@mapNotNull null
        val gentle = when (bits.getOrNull(3)) {
            null, "s" -> false
            "g" -> true
            else -> return@mapNotNull null
        }
        val rule = Rule(minutes, opens, gentle)
        if (rule.isEmpty && !rule.gentle) null else bits[0] to rule
    }.toMap()

    /** One number for every day, or seven */
    private fun days(text: String): List<Int>? {
        val values = text.split(',').map { it.trim().toIntOrNull()?.coerceAtLeast(0) ?: return null }
        return when (values.size) {
            1 -> List(7) { values[0] }
            7 -> values
            else -> null
        }
    }

    fun formatRules(rules: Map<String, Rule>): String =
        rules.filterValues { !it.isEmpty || it.gentle }.entries.joinToString(";") { (pkg, rule) ->
            val days = if (rule.sameEveryDay) "${rule.minutes[0]}:${rule.opens[0]}"
                else rule.minutes.joinToString(",") + ":" + rule.opens.joinToString(",")
            "$pkg:$days" + if (rule.gentle) ":g" else ""
        }

    fun rules(prefs: Prefs) = parseRules(prefs.appLimits)

    fun rule(prefs: Prefs, pkg: String) = rules(prefs)[pkg] ?: Rule()

    /** How many installed apps have a limit, a pause or a choice of time */
    fun appsWithRules(context: android.content.Context, prefs: Prefs): Int {
        AppPause.migrateIfNeeded(prefs)
        return (rules(prefs).filterValues { !it.isEmpty }.keys + prefs.pausedApps + prefs.useForApps).count { context.isPackageInstalled(it) }
    }

    fun setRule(prefs: Prefs, pkg: String, rule: Rule) {
        prefs.appLimits = formatRules(rules(prefs) + (pkg to rule))
    }

    /** Whether going from [old] to [new] loosens a limit: raised, or taken off */
    fun loosens(old: Int, new: Int) = old > 0 && (new <= 0 || new > old)

    /** Whether going from [old] to [new] loosens an app's limits: any day's raised or taken off, or strict made gentle */
    fun loosens(old: Rule, new: Rule) = (0 until 7).any { loosens(old.minutes[it], new.minutes[it]) || loosens(old.opens[it], new.opens[it]) } ||
        (new.gentle && !old.gentle && !old.isEmpty)

    // --- Why an app is over its limit ---

    enum class Over { TIME, OPENS }

    /**
     * Which limit [rule] stops on [day], after [usedMs] of use and [opens] opens today; a new open is
     * about to count when [opening]
     */
    fun over(rule: Rule, usedMs: Long, opens: Int, opening: Boolean, day: DayOfWeek = today()): Over? {
        val minutes = rule.minutesOn(day)
        val maxOpens = rule.opensOn(day)
        return when {
            minutes > 0 && usedMs >= minutes * 60_000L -> Over.TIME
            maxOpens > 0 && (if (opening) opens >= maxOpens else opens > maxOpens) -> Over.OPENS
            else -> null
        }
    }

    /** Whether to say only a little time is left on [day]: under [WARN_MS], not yet out, and not said today */
    fun shouldWarn(rule: Rule, usedMs: Long, warned: Boolean, day: DayOfWeek = today()): Boolean {
        val minutes = rule.minutesOn(day)
        if (minutes <= 0 || warned) return false
        val left = minutes * 60_000L - usedMs
        return left in 1..WARN_MS
    }

    // --- Gentle limits ---

    /** How long "One more minute" lets a gentle limit's app be used */
    const val ONE_MORE_MS = 60_000L

    /** Whether a gentle limit that's been reached lets its app be used at [now]: ignored for the day, or in a minute more until [moreUntil] */
    fun gentleAllows(ignored: Boolean, moreUntil: Long, now: Long) = ignored || moreUntil > now

    /** Whether [pkg]'s gentle limit lets it be used now */
    fun gentleAllowed(context: Context, pkg: String): Boolean {
        val today = today(context)
        return gentleAllows(today.getBoolean("ignored:$pkg", false), today.getLong("more:$pkg", 0L), System.currentTimeMillis())
    }

    /** "One more minute": [pkg] can be used for a minute before the notice comes back */
    fun oneMoreMinute(context: Context, pkg: String) =
        today(context).edit().putLong("more:$pkg", System.currentTimeMillis() + ONE_MORE_MS).apply()

    /** "Ignore for today": [pkg]'s gentle limit says nothing more until midnight */
    fun ignoreToday(context: Context, pkg: String) = today(context).edit().putBoolean("ignored:$pkg", true).apply()

    // --- Websites ---

    /** A site's host from what an address bar shows: "https://www.reddit.com/r/x" is "reddit.com" */
    fun host(address: String): String? {
        var text = address.trim().lowercase()
        if (text.isEmpty() || ' ' in text) return null
        text = text.substringAfter("://")
        text = text.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        // A bar read aloud with its hint ("reddit.com. Search or enter address") leaves a full stop on the end
        text = text.trimEnd('.')
        text = text.removePrefix("www.").removePrefix("m.")
        return text.takeIf { '.' in it && it.length > 3 }
    }

    /**
     * Whether [host] is [site], loosely, the same way for every site: any of its subdomains
     * (old.reddit.com), the same name under another ending (reddit.co.uk), and names that start with
     * it (redditmedia.com). Names under 4 letters (x.com) only match exactly, so they don't catch others.
     */
    fun matches(host: String, site: String): Boolean {
        val s = site.trim().lowercase().removePrefix("www.").trimEnd('.')
        val h = host.trim().lowercase().trimEnd('.')
        if (s.isEmpty()) return false
        if (h == s || h.endsWith(".$s")) return true
        val name = siteName(s) ?: return false
        val hostName = siteName(h) ?: return false
        return hostName == name || (name.length >= 4 && hostName.startsWith(name))
    }

    /** The name a domain is registered under: "reddit" for old.reddit.com and reddit.co.uk */
    fun siteName(domain: String): String? {
        val labels = domain.split('.').filter { it.isNotEmpty() }
        if (labels.size < 2) return null
        // Endings like .co.uk and .com.au take two labels
        val twoPart = labels.size >= 3 && labels.last().length == 2 && labels[labels.size - 2] in setOf("co", "com", "org", "net", "ac", "gov", "edu")
        return labels[labels.size - if (twoPart) 3 else 2]
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

    /** How many times [pkg]'s pause has been shown today */
    fun pausesToday(context: Context, pkg: String) = today(context).getInt("pauses:$pkg", 0)

    fun countPause(context: Context, pkg: String) {
        val prefs = today(context)
        prefs.edit().putInt("pauses:$pkg", prefs.getInt("pauses:$pkg", 0) + 1).apply()
    }

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
