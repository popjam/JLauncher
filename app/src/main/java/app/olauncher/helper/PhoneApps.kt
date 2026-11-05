package app.olauncher.helper

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate

/**
 * The phone's time in each app, a day at a time (by app name, its [KEEP_APPS] most used), so Screen
 * time can list them with the Mac's and TV's. Saved with each day's screen time.
 */
object PhoneApps {

    fun days(context: Context): Map<LocalDate, Map<String, Long>> = synchronized(LOCK) {
        val json = prefs(context).getString(KEY, null) ?: return emptyMap()
        val days = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        return days.keys().asSequence().mapNotNull { key ->
            val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@mapNotNull null
            val apps = days.getJSONObject(key)
            date to apps.keys().asSequence().associateWith { apps.getLong(it) }
        }.toMap()
    }

    fun save(context: Context, date: LocalDate, apps: Map<String, Long>) = synchronized(LOCK) {
        val all = days(context).toMutableMap()
        all[date] = apps.entries.filter { it.value >= MIN_MS }.sortedByDescending { it.value }.take(KEEP_APPS).associate { it.key to it.value }
        val cutoff = LocalDate.now().minusDays(KEEP_DAYS)
        val json = JSONObject()
        all.filterKeys { it >= cutoff }.forEach { (day, map) ->
            json.put(day.toString(), JSONObject().apply { map.forEach { (app, ms) -> put(app, ms) } })
        }
        prefs(context).edit().putString(KEY, json.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val LOCK = Any()
    private const val PREFS = "phone_apps"
    private const val KEY = "days"
    private const val KEEP_APPS = 20
    private const val KEEP_DAYS = 60L
    private const val MIN_MS = 60_000L
}
