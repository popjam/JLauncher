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

    class Purchase(val time: Long, val cents: Long, val source: String, val text: String, val key: String) {
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

        /** Groceries and health: spent on needs, so left out of Money spent's total */
        val essential: Boolean get() {
            val words = listOfNotNull(category, merchant).joinToString(" ").lowercase()
            return ESSENTIAL.any { it in words }
        }
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
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return List(array.length()) { array.getJSONObject(it) }.map {
            Purchase(it.getLong("time"), it.getLong("cents"), it.getString("source"), it.optString("text"), it.optString("key"))
        }.sortedByDescending { it.time }
    }

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
        val cents = purchaseCents(sbn.packageName, text) ?: return
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
    private fun purchaseCents(source: String, text: String): Long? {
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
            array.put(JSONObject().put("time", it.time).put("cents", it.cents).put("source", it.source).put("text", it.text).put("key", it.key))
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val LOCK = Any()
    private const val PREFS = "spending"
    private const val KEY = "purchases"
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

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching { Spending.record(applicationContext, sbn) }
    }

    // Catches purchases notified while it wasn't connected, if they're still showing
    override fun onListenerConnected() {
        runCatching { activeNotifications?.forEach { Spending.record(applicationContext, it) } }
        music?.stop()
        music = MusicTracker(applicationContext, ComponentName(this, SpendingListener::class.java)).also { it.start() }
        wifi?.stop()
        wifi = WifiWatcher(applicationContext).also { it.start() }
        steps?.stop()
        steps = StepWatcher(applicationContext).also { it.start() }
    }

    override fun onListenerDisconnected() {
        music?.stop()
        music = null
        wifi?.stop()
        wifi = null
        steps?.stop()
        steps = null
    }

    override fun onDestroy() {
        steps?.stop()
        steps = null
        music?.stop()
        music = null
        wifi?.stop()
        wifi = null
        super.onDestroy()
    }
}
