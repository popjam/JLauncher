package app.olauncher.helper

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.abs

/**
 * Subscriptions: the same amount charged by the same shop on a steady beat (weekly, fortnightly, monthly
 * or yearly), found in the purchases kept, plus any marked by hand. One wrongly found can be removed,
 * and isn't found again.
 */
object Subscriptions {

    /** How often it charges: [min]–[max] days apart, seen [minCharges] times in a row before it's assumed */
    enum class Period(val min: Int, val max: Int, val minCharges: Int) {
        WEEKLY(6, 8, 4),
        FORTNIGHTLY(13, 15, 3),
        MONTHLY(27, 33, 2),
        YEARLY(355, 375, 2);

        /** What [cents] a charge comes to a month */
        fun monthlyCents(cents: Long) = when (this) {
            WEEKLY -> cents * 52 / 12
            FORTNIGHTLY -> cents * 26 / 12
            MONTHLY -> cents
            YEARLY -> cents / 12
        }

        fun next(date: LocalDate): LocalDate = when (this) {
            WEEKLY -> date.plusWeeks(1)
            FORTNIGHTLY -> date.plusWeeks(2)
            MONTHLY -> date.plusMonths(1)
            YEARLY -> date.plusYears(1)
        }

        /** Days late before it's taken as stopped */
        val grace: Long get() = maxOf(3, max / 3).toLong()
    }

    class Subscription(val merchant: String, val period: Period, val cents: Long, val last: LocalDate, val charges: Int, val byHand: Boolean) {
        val monthlyCents: Long get() = period.monthlyCents(cents)
        val next: LocalDate get() = period.next(last)
    }

    /** A shop marked by hand as a subscription, charging about [cents] each [period] */
    class Mark(val period: Period, val cents: Long)

    /** Those still running, the most a month first */
    fun all(context: Context): List<Subscription> =
        find(Spending.purchases(context), marks(context), dismissed(context), LocalDate.now())

    /** The subscription [purchase] is a charge of, if any */
    fun of(context: Context, purchase: Spending.Purchase): Subscription? =
        all(context).firstOrNull { it.merchant == purchase.merchant && similar(it.cents, purchase.cents, HAND_TOLERANCE) }

    fun find(purchases: List<Spending.Purchase>, marks: Map<String, Mark>, dismissed: Set<String>, today: LocalDate): List<Subscription> {
        val found = mutableListOf<Subscription>()
        purchases.groupBy { it.merchant }.forEach { (merchant, list) ->
            val sorted = list.sortedBy { it.time }
            val mark = marks[merchant]
            if (mark != null) {
                // Marked by hand: from the charges near the amount marked, however few
                val same = sorted.filter { similar(it.cents, mark.cents, HAND_TOLERANCE) }
                val last = same.lastOrNull() ?: return@forEach
                found += Subscription(merchant, mark.period, last.cents, last.date, same.map { it.date }.distinct().size, true)
                return@forEach
            }
            if (merchant in dismissed) return@forEach
            clusters(sorted).forEach { charges ->
                val dates = charges.map { it.date }.distinct()
                val period = Period.values().firstOrNull { run(dates, it) >= it.minCharges } ?: return@forEach
                found += Subscription(merchant, period, charges.last().cents, dates.last(), run(dates, period), false)
            }
        }
        return found.filter { !today.isAfter(it.next.plusDays(it.period.grace)) }.sortedByDescending { it.monthlyCents }
    }

    /** How many charges in a row, back from the latest, are [period] apart */
    private fun run(dates: List<LocalDate>, period: Period): Int {
        var count = 1
        for (i in dates.lastIndex downTo 1) {
            val gap = dates[i].toEpochDay() - dates[i - 1].toEpochDay()
            if (gap < period.min || gap > period.max) break
            count++
        }
        return count
    }

    /** A shop's charges split by amount, so two subscriptions at one shop (or a subscription among other buys) stay apart */
    private fun clusters(sorted: List<Spending.Purchase>): List<List<Spending.Purchase>> {
        val groups = mutableListOf<MutableList<Spending.Purchase>>()
        sorted.forEach { p -> (groups.firstOrNull { similar(it.last().cents, p.cents, FOUND_TOLERANCE) } ?: mutableListOf<Spending.Purchase>().also { groups += it }) += p }
        return groups
    }

    /** Within [share] of each other, or 50c */
    private fun similar(a: Long, b: Long, share: Double) = abs(a - b) <= maxOf(50L, (maxOf(a, b) * share).toLong())

    // --- Marks ---

    fun marks(context: Context): Map<String, Mark> {
        val json = runCatching { JSONObject(prefs(context).getString(MARKS, null) ?: "{}") }.getOrNull() ?: return emptyMap()
        return json.keys().asSequence().mapNotNull { merchant ->
            val o = json.optJSONObject(merchant) ?: return@mapNotNull null
            val period = runCatching { Period.valueOf(o.getString("period")) }.getOrNull() ?: return@mapNotNull null
            merchant to Mark(period, o.getLong("cents"))
        }.toMap()
    }

    fun dismissed(context: Context): Set<String> =
        runCatching { org.json.JSONArray(prefs(context).getString(DISMISSED, null) ?: "[]") }.getOrNull()
            ?.let { a -> List(a.length()) { a.getString(it) }.toSet() }.orEmpty()

    /** [merchant] charges about [cents] every [period] */
    fun mark(context: Context, merchant: String, cents: Long, period: Period) {
        val marks = JSONObject(prefs(context).getString(MARKS, null) ?: "{}")
        marks.put(merchant, JSONObject().put("period", period.name).put("cents", cents))
        prefs(context).edit().putString(MARKS, marks.toString())
            .putString(DISMISSED, org.json.JSONArray(dismissed(context) - merchant).toString()).apply()
        app.olauncher.data.BackupManager.requestSync(context)
    }

    /** [merchant] isn't a subscription: unmarked, and not found again */
    fun remove(context: Context, merchant: String) {
        val marks = JSONObject(prefs(context).getString(MARKS, null) ?: "{}")
        marks.remove(merchant)
        prefs(context).edit().putString(MARKS, marks.toString())
            .putString(DISMISSED, org.json.JSONArray(dismissed(context) + merchant).toString()).apply()
        app.olauncher.data.BackupManager.requestSync(context)
    }

    // In Spending's settings, so they're backed up with the purchases
    private fun prefs(context: Context) = context.getSharedPreferences("spending", Context.MODE_PRIVATE)

    private const val MARKS = "subscription_marks"
    private const val DISMISSED = "subscription_dismissed"
    private const val FOUND_TOLERANCE = 0.03
    private const val HAND_TOLERANCE = 0.25
}
