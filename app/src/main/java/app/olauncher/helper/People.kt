package app.olauncher.helper

import android.Manifest
import android.app.Notification
import android.app.Person
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import app.olauncher.data.MoodStore
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * How many people you were in touch with each day: calls from the call log (answered or made), and
 * messages from chat apps' notifications (WhatsApp, Messages, Signal and the like), as the
 * notification listener sees them. Only counts are kept, and people as short codes made from their
 * names: never what was said. Fills in the People stat, from none to five or more.
 */
object People {

    class Day(val calls: Int, val talkMs: Long, val missed: Int, val received: Int, val sent: Int, val people: Int) {
        val messages get() = received + sent
    }

    fun hasCallLog(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    /** When counting began: days before it aren't counted, as messages from then weren't seen */
    fun since(context: Context): LocalDate? = prefs(context).getString(SINCE, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun begin(context: Context) {
        if (!prefs(context).contains(SINCE)) prefs(context).edit().putString(SINCE, LocalDate.now().toString()).apply()
    }

    // --- Messages, from notifications ---

    /** A notification from a chat app: each new message in it counted once, by who sent it */
    fun record(context: Context, sbn: StatusBarNotification) {
        if (sbn.packageName !in APPS) return
        synchronized(LOCK) { recordLocked(context, sbn) }
    }

    private fun recordLocked(context: Context, sbn: StatusBarNotification) {
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_MISSED_CALL) return
        val extras = n.extras
        val self = extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)?.toString()
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) personName(extras.get(Notification.EXTRA_MESSAGING_PERSON)) else null
        // time, sender (null for you), the text's code
        val messages = mutableListOf<Triple<Long, String?, String>>()
        @Suppress("DEPRECATION")
        val bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (!bundles.isNullOrEmpty()) {
            bundles.forEach { b ->
                val bundle = b as? Bundle ?: return@forEach
                val sender = bundle.getCharSequence("sender")?.toString()
                    ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) personName(bundle.get("sender_person")) else null
                val mine = sender == null || sender == self
                messages += Triple(bundle.getLong("time"), if (mine) null else sender, code(bundle.getCharSequence("text")?.toString().orEmpty()))
            }
        } else {
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
            messages += Triple(n.`when`.takeIf { it > 0 } ?: sbn.postTime, title, code(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()))
        }
        if (messages.isEmpty()) return
        begin(context)
        val prefs = prefs(context)
        val seen = prefs.getString(SEEN, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val counts = prefs.getString(MESSAGES, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val now = System.currentTimeMillis()
        var changed = false
        messages.forEach { (time, sender, text) ->
            if (now - time > SEEN_MS) return@forEach
            val key = code(sbn.packageName + "|" + sender + "|" + time + "|" + text)
            if (seen.has(key)) return@forEach
            seen.put(key, time)
            val date = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate().toString()
            val day = counts.optJSONObject(date) ?: JSONObject().also { counts.put(date, it) }
            if (sender == null) day.put("out", day.optInt("out") + 1)
            else {
                day.put("in", day.optInt("in") + 1)
                val who = day.optJSONArray("who") ?: JSONArray().also { day.put("who", it) }
                val person = code(sender.trim().lowercase())
                if ((0 until who.length()).none { who.optString(it) == person }) who.put(person)
            }
            changed = true
        }
        if (!changed) return
        // Forgets messages seen long enough ago that they won't show again
        seen.keys().asSequence().toList().filter { now - seen.optLong(it) > SEEN_MS }.forEach { seen.remove(it) }
        val cutoff = LocalDate.now().minusDays(KEEP_DAYS).toString()
        counts.keys().asSequence().toList().filter { it < cutoff }.forEach { counts.remove(it) }
        prefs.edit().putString(SEEN, seen.toString()).putString(MESSAGES, counts.toString()).apply()
    }

    private fun personName(person: Any?): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) (person as? Person)?.name?.toString() else null

    /** A short code for [text], so names and messages aren't kept */
    private fun code(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(("jl:" + text).toByteArray())
        return digest.take(5).joinToString("") { "%02x".format(it) }
    }

    // --- Calls, from the call log ---

    private class Calls(val count: Int, val ms: Long, val missed: Int, val who: Set<String>)

    private fun calls(context: Context, from: LocalDate): Map<LocalDate, Calls> {
        if (!hasCallLog(context)) return emptyMap()
        val start = from.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val rows = mutableListOf<Triple<LocalDate, Pair<Int, Long>, String>>()
        runCatching {
            context.contentResolver.query(CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER),
                "${CallLog.Calls.DATE} >= ?", arrayOf(start.toString()), null)?.use { c ->
                while (c.moveToNext()) {
                    val date = Instant.ofEpochMilli(c.getLong(0)).atZone(ZoneId.systemDefault()).toLocalDate()
                    val who = (c.getString(3)?.takeIf { it.isNotBlank() } ?: c.getString(4).orEmpty()).trim().lowercase()
                    rows += Triple(date, c.getInt(2) to c.getLong(1) * 1000, code(who))
                }
            }
        }
        return rows.groupBy { it.first }.mapValues { (_, list) ->
            val talked = list.filter { it.second.second > 0 && it.second.first in setOf(CallLog.Calls.INCOMING_TYPE, CallLog.Calls.OUTGOING_TYPE) }
            Calls(talked.size, talked.sumOf { it.second.second }, list.count { it.second.first == CallLog.Calls.MISSED_TYPE },
                talked.map { it.third }.toSet())
        }
    }

    // --- Days ---

    /** Each day since counting began: calls, time talking, messages, and how many people */
    fun days(context: Context): Map<LocalDate, Day> {
        val since = since(context) ?: return emptyMap()
        val counts = prefs(context).getString(MESSAGES, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val calls = calls(context, since)
        val today = LocalDate.now()
        return generateSequence(since) { it.plusDays(1) }.takeWhile { it <= today }.associateWith { date ->
            val m = counts.optJSONObject(date.toString())
            val c = calls[date]
            val who = (m?.optJSONArray("who")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()) + c?.who.orEmpty()
            Day(c?.count ?: 0, c?.ms ?: 0L, c?.missed ?: 0, m?.optInt("in") ?: 0, m?.optInt("out") ?: 0, who.toSet().size)
        }
    }

    /** Adds the People stat once there's something to count, and fills in the last [DAYS_BACK] days */
    fun refresh(context: Context, force: Boolean = false) {
        // The call log needn't be read every time home shows
        val now = System.currentTimeMillis()
        if (!force && now - prefs(context).getLong(REFRESHED, 0L) < REFRESH_MS) return
        prefs(context).edit().putLong(REFRESHED, now).apply()
        Workload.track(context, Workload.PEOPLE) { refreshNow(context) }
    }

    private fun refreshNow(context: Context) {
        if (hasCallLog(context)) begin(context)
        if (since(context) == null) return
        val id = AutoStats.ensure(context, AutoStats.PEOPLE, NAME) { store ->
            store.addStat(NAME, COLOR, daily = false, scale = 5, question = "", labels = LABELS, inverted = false,
                phrases = emptyList(), medicine = false, nextDay = false)
        } ?: return
        val today = LocalDate.now()
        MoodStore(context).setFaces(id, days(context).filterKeys { it > today.minusDays(DAYS_BACK) }.mapValues { faceFor(it.value.people) })
    }

    /** No one is the saddest face, then one, two, three or four, and five or more */
    fun faceFor(people: Int) = when {
        people <= 0 -> MoodStore.SAD
        people == 1 -> MoodStore.MILDLY_SAD
        people == 2 -> MoodStore.NEUTRAL
        people <= 4 -> MoodStore.MILDLY_HAPPY
        else -> MoodStore.HAPPY
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    const val NAME = "People"
    private val COLOR = 0xFFFF8A65.toInt()
    private val LABELS = listOf("No one", "1 person", "2 people", "3–4 people", "5+ people")
    private val APPS = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "org.thoughtcrime.securesms", "com.facebook.orca", "org.telegram.messenger", "com.discord",
    )
    private val LOCK = Any()
    private const val PREFS = "people"
    private const val SINCE = "since"
    private const val REFRESHED = "refreshed"
    private const val REFRESH_MS = 10 * 60_000L
    private const val SEEN = "seen"
    private const val MESSAGES = "messages"
    private const val SEEN_MS = 3 * 24 * 3_600_000L
    private const val KEEP_DAYS = 400L
    private const val DAYS_BACK = 30L
}
