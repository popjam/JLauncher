package app.olauncher.helper

import android.content.Context
import android.net.Uri
import app.olauncher.data.MoodStore
import org.json.JSONObject
import java.time.LocalDate

/**
 * Your time prompting coding agents, written to Google Drive by tools/vibecoding on the PC and the Mac
 * (one file each), read from the same folder as Mac screen time and added together. Kept on the
 * phone and filled into the Vibecoding stat, which runs from good (a little) to bad (a lot).
 */
object Vibecoding {

    class Project(val minutes: Int, val prompts: Int)

    class Day(
        val minutes: Int, val prompts: Int, val tokens: Long, val cost: Double,
        val first: String?, val last: String?, val hours: IntArray, val projects: Map<String, Project>,
    )

    /** Both machines' days, added together */
    fun days(context: Context): Map<LocalDate, Day> {
        val prefs = prefs(context)
        val perDevice = DEVICES.map { device -> prefs.getString(device, null)?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }.orEmpty() }
        val dates = perDevice.flatMap { it.keys }.toSet()
        return dates.associateWith { date -> perDevice.mapNotNull { it[date] }.reduce(::add) }
    }

    fun updated(context: Context) = prefs(context).getLong(UPDATED_KEY, 0)

    /** Reads both machines' files, at most every [REFRESH_MS] unless [force]; on a background thread. */
    fun refresh(context: Context, force: Boolean = false) {
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(LAST_KEY, 0) < REFRESH_MS) return
        prefs.edit().putLong(LAST_KEY, now).apply()
        val folder = DeviceTime.folder(context).takeIf { it.isNotEmpty() } ?: return
        var found = false
        DEVICES.forEach { device ->
            val text = DeviceTime.readFile(context, Uri.parse(folder), "JLauncher Vibecoding $device.json") ?: return@forEach
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return@forEach
            val read = json.optJSONObject("days") ?: return@forEach
            found = true
            // Each machine keeps 60 days; older days stay as last read
            val kept = prefs.getString(device, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
            read.keys().forEach { kept.put(it, read.get(it)) }
            val cutoff = LocalDate.now().minusDays(KEEP_DAYS).toString()
            kept.keys().asSequence().toList().filter { it < cutoff }.forEach { kept.remove(it) }
            prefs.edit().putString(device, kept.toString())
                .putLong(UPDATED_KEY, maxOf(prefs.getLong(UPDATED_KEY, 0), json.optLong("updated"))).apply()
        }
        if (!found) return
        val id = AutoStats.ensure(context, AutoStats.VIBECODING) { store ->
            store.addStat(NAME, COLOR, daily = false, scale = 5, question = "", labels = LABELS, inverted = true,
                phrases = emptyList(), medicine = false, nextDay = false)
        } ?: return
        val store = MoodStore(context)
        val today = LocalDate.now()
        days(context).filterKeys { it > today.minusDays(DAYS_BACK) }.forEach { (date, day) -> store.setFace(date, id, faceFor(day.minutes)) }
    }

    /** Under an hour is the happiest face, then 1–2, 2–3, 3–5, and 5 hours or more the saddest. */
    fun faceFor(minutes: Int) = when {
        minutes < 60 -> MoodStore.HAPPY
        minutes < 120 -> MoodStore.MILDLY_HAPPY
        minutes < 180 -> MoodStore.NEUTRAL
        minutes < 300 -> MoodStore.MILDLY_SAD
        else -> MoodStore.SAD
    }

    private fun add(a: Day, b: Day) = Day(
        a.minutes + b.minutes, a.prompts + b.prompts, a.tokens + b.tokens, a.cost + b.cost,
        listOfNotNull(a.first, b.first).minOrNull(), listOfNotNull(a.last, b.last).maxOrNull(),
        IntArray(24) { a.hours[it] + b.hours[it] },
        (a.projects.keys + b.projects.keys).associateWith { name ->
            val x = a.projects[name]
            val y = b.projects[name]
            Project((x?.minutes ?: 0) + (y?.minutes ?: 0), (x?.prompts ?: 0) + (y?.prompts ?: 0))
        },
    )

    private fun parse(days: JSONObject): Map<LocalDate, Day> = days.keys().asSequence().mapNotNull { key ->
        val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@mapNotNull null
        val day = days.getJSONObject(key)
        val hours = day.optJSONArray("hours")
        val projects = day.optJSONObject("projects")?.let { p ->
            p.keys().asSequence().associateWith { name ->
                p.getJSONObject(name).let { Project(it.optInt("minutes"), it.optInt("prompts")) }
            }
        }.orEmpty()
        date to Day(
            day.optInt("minutes"), day.optInt("prompts"), day.optLong("tokens"), day.optDouble("cost", 0.0),
            day.optString("first").ifEmpty { null }, day.optString("last").ifEmpty { null },
            IntArray(24) { hours?.optInt(it) ?: 0 }, projects,
        )
    }.toMap()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    const val NAME = "Vibecoding"
    private val DEVICES = listOf("PC", "Mac")
    private val COLOR = 0xFFBA68C8.toInt()

    /** Left to right on the inverted row, happy to sad */
    private val LABELS = listOf("Under 1h", "1–2h", "2–3h", "3–5h", "5h+")

    private const val PREFS = "vibecoding"
    private const val LAST_KEY = "refreshed"
    private const val UPDATED_KEY = "updated"
    private const val REFRESH_MS = 15 * 60 * 1000L
    private const val KEEP_DAYS = 400L
    private const val DAYS_BACK = 60L
}
