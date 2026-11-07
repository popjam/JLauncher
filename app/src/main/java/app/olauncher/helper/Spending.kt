package app.olauncher.helper

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import app.olauncher.data.MoodStore
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * Card purchases read from CommBank's and Google Wallet's notifications, kept on the phone, and totalled
 * into each day's Money spent stat (the stat with "money" or "spen" in its name). Only notifications with
 * a dollar amount count, and ones about money coming in, declines or offers are skipped. A purchase both
 * apps notify about is only counted once.
 */
object Spending {

    const val COMMBANK = "com.commbank.netbank"
    const val WALLET = "com.google.android.apps.walletnfcrel"

    /**
     * A purchase. [mark] is it set by hand as essential or not, and [groupRule] the same for everything in
     * its [group]; without either, it's essential when its category or shop reads like a need.
     */
    class Purchase(
        val time: Long,
        val cents: Long,
        val source: String,
        val text: String,
        val key: String,
        val mark: Boolean? = null,
        val groupRule: Boolean? = null,
    ) {
        val date: LocalDate get() = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate()

        /** Where it was spent: "Google Australia · Entertainment" from CommBank's "$0.29 spent at Google Australia on Entertainment." */
        val shop: String get() {
            SPENT_AT.find(text)?.let { match ->
                return listOf(match.groupValues[1], match.groupValues[2]).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ")
            }
            return text.split(" · ").filterNot { it.equals("CommBank", ignoreCase = true) }.joinToString(" · ").ifEmpty { text }
        }

        /** The shop alone: "Google Australia" */
        val merchant: String get() = SPENT_AT.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: shop

        /** CommBank's category for it, like "Entertainment", if it gave one */
        val category: String? get() = SPENT_AT.find(text)?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() }

        /** What a set of rules goes by: its category, or the shop when there isn't one */
        val group: String get() = category ?: merchant

        /** Whether it reads like a need (groceries, health) by its words alone */
        val usuallyEssential: Boolean get() {
            val words = listOfNotNull(category, merchant).joinToString(" ").lowercase()
            return ESSENTIAL.any { it in words }
        }

        /** Spent on needs, so left out of Money spent's total: as marked, else as its group is, else by its words */
        val essential: Boolean get() = mark ?: groupRule ?: usuallyEssential
    }

    fun moneyStat(data: MoodStore.Data): MoodStore.Stat? =
        data.stats.firstOrNull { stat -> NAMES.any { stat.name.contains(it, ignoreCase = true) } }

    fun isEnabled(context: Context) = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Android's notification access list, where JLauncher can be switched on. */
    fun openSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Newest first, from the last [KEEP_DAYS] days. */
    fun purchases(context: Context): List<Purchase> = synchronized(LOCK) {
        val json = prefs(context).getString(KEY, null) ?: return emptyList()
        val rulesJson = prefs(context).getString(GROUP_RULES, null)
        // Read once for each time they're saved: the same strings come back until they change
        if (json === parsedJson && rulesJson === parsedRules) return parsedPurchases
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val rules = groupRules(rulesJson)
        parsedPurchases = List(array.length()) { array.getJSONObject(it) }.map {
            val base = Purchase(it.getLong("time"), it.getLong("cents"), it.getString("source"), it.optString("text"), it.optString("key"),
                if (it.has("essential")) it.getBoolean("essential") else null)
            Purchase(base.time, base.cents, base.source, base.text, base.key, base.mark, rules[base.group])
        }.sortedByDescending { it.time }
        parsedJson = json
        parsedRules = rulesJson
        return parsedPurchases
    }

    private var parsedJson: String? = null
    private var parsedRules: String? = null
    private var parsedPurchases: List<Purchase> = emptyList()

    private fun groupRules(json: String?): Map<String, Boolean> {
        val rules = json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
        return rules.keys().asSequence().associateWith { rules.getBoolean(it) }
    }

    /** Marks one purchase as essential or not, or (null) back to how its group goes; its day's total follows */
    fun setEssential(context: Context, purchase: Purchase, essential: Boolean?) {
        synchronized(LOCK) {
            save(context, purchases(context).map {
                if (it.key == purchase.key && it.time == purchase.time) Purchase(it.time, it.cents, it.source, it.text, it.key, essential, it.groupRule) else it
            })
        }
        apply(context, purchase.date)
        app.olauncher.data.BackupManager.requestSync(context)
    }

    /** Everything in [group] counts as essential or not from now on, or (null) as its words say; their days' totals follow */
    fun setGroupEssential(context: Context, group: String, essential: Boolean?) {
        synchronized(LOCK) {
            val rules = groupRules(prefs(context).getString(GROUP_RULES, null)).toMutableMap()
            if (essential == null) rules.remove(group) else rules[group] = essential
            prefs(context).edit().putString(GROUP_RULES, JSONObject(rules as Map<*, *>).toString()).commit()
        }
        purchases(context).filter { it.group == group }.map { it.date }.distinct().forEach { apply(context, it) }
        app.olauncher.data.BackupManager.requestSync(context)
    }

    /** Every category (or shop) set by hand as essential (true) or not */
    fun groupRules(context: Context): Map<String, Boolean> = groupRules(prefs(context).getString(GROUP_RULES, null))

    private fun noteUnread(context: Context, time: Long) = synchronized(LOCK) {
        val prefs = prefs(context)
        val times = prefs.getString(UNREAD, null)?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()
        if (time in times) return
        val kept = (times + time).filter { it > System.currentTimeMillis() - 14 * DAY_MS }.takeLast(30)
        prefs.edit().putString(UNREAD, kept.joinToString(",")).apply()
    }

    /** How many CommBank notifications with amounts couldn't be read as purchases since [time] */
    fun unreadSince(context: Context, time: Long) =
        prefs(context).getString(UNREAD, null)?.split(',')?.mapNotNull { it.toLongOrNull() }?.count { it >= time } ?: 0

    /** How [group] is set to count, if it's been set */
    fun groupRule(context: Context, group: String): Boolean? = groupRules(prefs(context).getString(GROUP_RULES, null))[group]

    /** What [date] went on things that aren't essentials, which is what Money spent counts */
    fun totalCents(context: Context, date: LocalDate) = purchases(context).filter { it.date == date && !it.essential }.sumOf { it.cents }

    /** Each day's non-essential spending, from the purchases kept */
    fun dailyCents(context: Context): Map<LocalDate, Long> =
        purchases(context).filter { !it.essential }.groupBy { it.date }.mapValues { (_, list) -> list.sumOf { it.cents } }

    /** Since when purchases have been read: the oldest one kept, or null before any */
    fun trackedSince(context: Context): LocalDate? = purchases(context).minOfOrNull { it.date }

    /** Records [sbn] if it's a purchase, and updates its day's Money spent. */
    fun record(context: Context, sbn: StatusBarNotification) {
        if (sbn.packageName !in SOURCES) return
        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val text = listOf(title, body).filter { it.isNotBlank() }.joinToString(" · ")
        val cents = purchaseCents(sbn.packageName, text) ?: run {
            // An amount, and not money coming in: likely a purchase worded in a way not understood
            val lower = text.lowercase()
            if (sbn.packageName == COMMBANK && AMOUNT.containsMatchIn(text) && SKIP.none { it in lower }) noteUnread(context, sbn.postTime)
            return
        }
        val purchase = Purchase(sbn.postTime, cents, sbn.packageName, text.take(MAX_TEXT), sbn.key)

        val date = synchronized(LOCK) {
            val all = purchases(context)
            // The same notification again (updated, or seen on reconnecting), or the other app's for the same purchase
            if (all.any { it.key == purchase.key && it.cents == cents }) return
            if (all.any { it.source != purchase.source && it.cents == cents && abs(it.time - purchase.time) < SAME_PURCHASE_MS }) return
            val cutoff = System.currentTimeMillis() - KEEP_DAYS * DAY_MS
            save(context, (all + purchase).filter { it.time >= cutoff })
            purchase.date
        }
        apply(context, date)
    }

    /** Sets [date]'s Money spent to the face for its total, if there's a stat for it and anything was bought. */
    fun apply(context: Context, date: LocalDate) {
        val store = MoodStore(context)
        val stat = moneyStat(store.load()) ?: return
        // A day with only essentials bought still counts as $0 spent
        if (purchases(context).none { it.date == date }) return
        store.setFace(date, stat.id, faceFor(stat, totalCents(context, date)))
    }

    /**
     * The face for spending [cents]. With a number in every label ("$0", "<$20", "$20–50", "$100+"), the
     * label whose range it falls in: "<" (or "under") labels end there, the rest start there. Without,
     * $0 is the happiest face and more steps down at [DEFAULT_STEPS] (a medicine's run left to right).
     */
    fun faceFor(stat: MoodStore.Stat, cents: Long): Int {
        val amount = cents / 100.0
        val row = MoodStore.facesFor(stat.scale).let { if (stat.inverted) it.reversed() else it }
        val numbers = stat.labels.map { NUMBER.find(it.replace(",", ""))?.value?.toDoubleOrNull() }
        if (stat.labels.size == row.size && numbers.all { it != null }) {
            val places = row.indices.sortedBy { numbers[it]!! }
            var chosen = places.first()
            places.forEachIndexed { rank, place ->
                val label = stat.labels[place].lowercase()
                val matches = if (UP_TO.any { it in label }) {
                    if (rank == 0) true
                    else {
                        val previous = places[rank - 1]
                        val before = numbers[previous]!!
                        // After an exact amount like "$0" it starts just above it, otherwise at it
                        if (isExact(stat.labels[previous])) amount > before else amount >= before
                    }
                } else amount >= numbers[place]!!
                if (matches) chosen = place
            }
            return row[chosen]
        }
        val leastFirst = if (stat.medicine) row else row.sorted()
        val steps = DEFAULT_STEPS.getValue(row.size)
        return leastFirst[steps.indexOfLast { amount >= it }.coerceAtLeast(0)]
    }

    private fun isExact(label: String): Boolean {
        val lower = label.lowercase()
        return UP_TO.none { it in lower } && '+' !in lower && NUMBER.findAll(label.replace(",", "")).count() == 1 && "-" !in lower && "–" !in lower
    }

    /** The dollar amount, if [text] reads like money going out. */
    internal fun purchaseCents(source: String, text: String): Long? {
        val lower = text.lowercase()
        if (SKIP.any { it in lower }) return null
        if (source == COMMBANK && SPEND.none { it in lower }) return null
        val match = AMOUNT.find(text) ?: return null
        val dollars = match.groupValues[1].replace(",", "").toLongOrNull() ?: return null
        val cents = match.groupValues[2].takeIf { it.isNotEmpty() }?.toLongOrNull() ?: 0
        return (dollars * 100 + cents).takeIf { it > 0 }
    }

    private fun save(context: Context, purchases: List<Purchase>) {
        val array = JSONArray()
        purchases.forEach {
            val entry = JSONObject().put("time", it.time).put("cents", it.cents).put("source", it.source).put("text", it.text).put("key", it.key)
            it.mark?.let { mark -> entry.put("essential", mark) }
            array.put(entry)
        }
        // Written straight away, so the totals worked out next read it
        prefs(context).edit().putString(KEY, array.toString()).commit()
    }

    /** The most to spend a week (from the day weeks start on, leaving out essentials), or 0 for none */
    fun weekLimitCents(context: Context) = prefs(context).getLong(WEEK_LIMIT, 0L)

    fun setWeekLimitCents(context: Context, cents: Long) = prefs(context).edit().putLong(WEEK_LIMIT, cents.coerceAtLeast(0)).apply()

    /** Whether a week's leftover (or overspend) is added to (or taken from) the next week's limit */
    fun carryOver(context: Context) = prefs(context).getBoolean(CARRY, false)

    /** Turning it on starts counting from this week, so weeks before don't bring a surplus or debt with them */
    fun setCarryOver(context: Context, on: Boolean) {
        if (on == carryOver(context)) return
        prefs(context).edit().putBoolean(CARRY, on)
            .putString(CARRY_SINCE, app.olauncher.helper.Week.start(LocalDate.now()).toString()).apply()
    }

    /** Counts the carry-over from this week, after the week's start day has changed */
    fun restartCarry(context: Context) {
        if (carryOver(context)) prefs(context).edit().putString(CARRY_SINCE, Week.start(LocalDate.now()).toString()).apply()
    }

    /** Whether what's left this week shows on the home screen, under the mood */
    fun showOnHome(context: Context) = prefs(context).getBoolean(ON_HOME, true)

    fun setShowOnHome(context: Context, on: Boolean) = prefs(context).edit().putBoolean(ON_HOME, on).apply()

    /** The non-essential spending in the week from [monday] */
    fun weekSpentCents(daily: Map<LocalDate, Long>, monday: LocalDate) = (0..6L).sumOf { daily[monday.plusDays(it)] ?: 0L }

    /**
     * What the week from [monday] can spend: the limit, and with [carryOver] plus what each week before
     * left unspent, or less what it went over by.
     */
    fun allowanceCents(context: Context, monday: LocalDate, daily: Map<LocalDate, Long> = dailyCents(context)): Long {
        val since = if (carryOver(context)) prefs(context).getString(CARRY_SINCE, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } else null
        return allowanceCents(weekLimitCents(context), since, monday, daily)
    }

    /**
     * The week from [monday]'s allowance: [limit], and when carrying over since the week of [carrySince],
     * plus each week's leftover before it (or less what it went over by).
     */
    fun allowanceCents(limit: Long, carrySince: LocalDate?, monday: LocalDate, daily: Map<LocalDate, Long>): Long {
        if (carrySince == null) return limit
        var allowance = limit
        var week: LocalDate = carrySince
        while (week < monday) {
            allowance = limit + allowance - weekSpentCents(daily, week)
            week = week.plusWeeks(1)
        }
        return allowance
    }

    /** This week's allowance and what's left of it (below 0 when over), or null without a limit */
    fun thisWeek(context: Context): Pair<Long, Long>? {
        if (weekLimitCents(context) <= 0) return null
        val daily = dailyCents(context)
        val monday = app.olauncher.helper.Week.start(LocalDate.now())
        val allowance = allowanceCents(context, monday, daily)
        return allowance to allowance - weekSpentCents(daily, monday)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val LOCK = Any()
    private const val PREFS = "spending"
    private const val KEY = "purchases"
    private const val WEEK_LIMIT = "week_limit"
    private const val GROUP_RULES = "group_rules"
    private const val UNREAD = "unread"
    private const val CARRY = "carry_over"
    private const val CARRY_SINCE = "carry_since"
    private const val ON_HOME = "on_home"
    private const val KEEP_DAYS = 400L
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val SAME_PURCHASE_MS = 15 * 60 * 1000L
    private const val MAX_TEXT = 140
    private val SOURCES = setOf(COMMBANK, WALLET)
    private val NAMES = listOf("money", "spen")
    private val ESSENTIAL = listOf("grocer", "supermarket", "health", "medical", "pharmac", "chemist", "doctor", "dental", "woolworths", "coles", "aldi", "iga")
    private val AMOUNT = Regex("""\$\s?(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{2}))?""")
    private val SPENT_AT = Regex("""spent at (.+?)(?: on (.+?))?\.?$""", RegexOption.IGNORE_CASE)
    private val NUMBER = Regex("""\d+(?:\.\d+)?""")
    private val UP_TO = listOf("<", "under", "less", "up to")

    /** Words in a CommBank notification that say money went out */
    private val SPEND = listOf("spent", "spend", "purchase", "paid", "payment", "transaction", "debit", "charged", "withdraw")

    /** Money coming in, failed payments, and promotions */
    private val SKIP = listOf(
        "refund", "received", "receive", "deposit", "credited", "credit of", "declined", "unsuccessful", "failed",
        "reversed", "reversal", "cashback", "cash back", "offer", "reward", "bonus", "interest", "salary",
        "sent you", "paid you", "into your", "save ", "saving", "win ", "chance",
    )

    /** Where each step up in spending starts, in dollars, for 2, 3 and 5 faces */
    private val DEFAULT_STEPS = mapOf(
        2 to listOf(0.0, 0.01),
        3 to listOf(0.0, 0.01, 50.0),
        5 to listOf(0.0, 0.01, 20.0, 50.0, 100.0),
    )
}

/**
 * Hands CommBank's and Google Wallet's notifications to [Spending], and follows what's playing for
 * [Music]. Android runs it once notification access is on.
 */
class SpendingListener : NotificationListenerService() {

    private var music: MusicTracker? = null
    private var wifi: WifiWatcher? = null
    private var steps: StepWatcher? = null
    private var places: PlaceWatcher? = null
    private var battery: BatteryWatcher? = null

    /** One thread for reading notifications, in the order they come */
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val context = applicationContext
        worker.execute {
            Workload.track(context, Workload.NOTIFICATIONS) {
                runCatching { Spending.record(context, sbn) }
                runCatching { People.record(context, sbn) }
            }
        }
    }

    // Catches purchases notified while it wasn't connected, if they're still showing
    override fun onListenerConnected() {
        Week.load(applicationContext)
        Tracking.listenerConnected(applicationContext)
        runCatching { activeNotifications?.forEach { Spending.record(applicationContext, it) } }
        music?.stop()
        music = MusicTracker(applicationContext, ComponentName(this, SpendingListener::class.java)).also { it.start() }
        wifi?.stop()
        wifi = WifiWatcher(applicationContext).also { it.start() }
        steps?.stop()
        steps = StepWatcher(applicationContext).also { it.start() }
        places?.stop()
        places = PlaceWatcher(applicationContext).also { it.start() }
        battery?.stop()
        battery = BatteryWatcher(applicationContext).also { it.start() }
    }

    override fun onListenerDisconnected() {
        Tracking.listenerDisconnected(applicationContext)
        music?.stop()
        music = null
        wifi?.stop()
        wifi = null
        steps?.stop()
        steps = null
        places?.stop()
        places = null
        battery?.stop()
        battery = null
    }

    override fun onDestroy() {
        battery?.stop()
        battery = null
        places?.stop()
        places = null
        steps?.stop()
        steps = null
        music?.stop()
        music = null
        wifi?.stop()
        wifi = null
        super.onDestroy()
    }
}
