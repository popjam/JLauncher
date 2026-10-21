package app.olauncher.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Mood + daily usage history, stored as a single JSON file in app storage.
 *
 * Kept out of SharedPreferences on purpose: the accessibility service runs in a separate process
 * and needs to read fresh data. [BackupManager] bundles this file with the launcher settings.
 */
class MoodStore(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(File(appContext.filesDir, DIR), FILE_NAME)

    data class Day(val mood: Int?, val usageMs: Long?)

    class Data(
        val days: MutableMap<String, Day> = sortedMapOf(),
        var promptEnabled: Boolean = false,
        var promptMinutes: Int = DEFAULT_PROMPT_MINUTES,
        var lastPromptDate: String = "",
        var backupFolder: String = "",
    )

    fun load(): Data = synchronized(LOCK) {
        if (!file.exists()) return Data()
        return try {
            fromJson(JSONObject(file.readText()))
        } catch (e: Exception) {
            e.printStackTrace()
            Data()
        }
    }

    fun update(block: (Data) -> Unit) {
        synchronized(LOCK) {
            val data = load()
            block(data)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(toJson(data).toString(2))
            tmp.renameTo(file)
        }
        BackupManager.requestSync(appContext)
    }

    fun getDay(date: LocalDate): Day? = load().days[date.toString()]

    fun setMood(date: LocalDate, mood: Int?) = update { data ->
        val key = date.toString()
        val old = data.days[key]
        data.days[key] = Day(mood, old?.usageMs)
    }

    /** Keeps the larger value, so a partial snapshot never overwrites a complete day. */
    fun setUsage(date: LocalDate, usageMs: Long) {
        val old = getDay(date)?.usageMs
        if (old != null && old >= usageMs) return
        update { data ->
            val key = date.toString()
            data.days[key] = Day(data.days[key]?.mood, maxOf(usageMs, data.days[key]?.usageMs ?: 0L))
        }
    }

    // --- Daily prompt ---

    fun isPromptDue(now: java.time.LocalDateTime = java.time.LocalDateTime.now()): Boolean {
        val data = load()
        if (!data.promptEnabled) return false
        val today = now.toLocalDate().toString()
        if (data.lastPromptDate == today) return false
        if (data.days[today]?.mood != null) return false
        return now.hour * 60 + now.minute >= data.promptMinutes
    }

    fun answerPrompt(mood: Int?) = update { data ->
        val today = LocalDate.now().toString()
        data.lastPromptDate = today
        if (mood != null) data.days[today] = Day(mood, data.days[today]?.usageMs)
    }

    // --- Backup ---

    /** Everything except the backup folder link, which only makes sense on this device. */
    fun toBackupJson(): JSONObject = synchronized(LOCK) {
        toJson(load()).apply { remove("backupFolder") }
    }

    /**
     * Merges a backup into local data: moods from the backup win, usage keeps the larger value,
     * and the prompt settings are taken from the backup. Returns the number of days in the backup.
     */
    fun restoreFrom(json: JSONObject): Int {
        val backup = fromJson(json)
        update { data ->
            backup.days.forEach { (key, day) ->
                val local = data.days[key]
                val usage = listOfNotNull(local?.usageMs, day.usageMs).maxOrNull()
                data.days[key] = Day(day.mood ?: local?.mood, usage)
            }
            if (json.has("promptEnabled")) data.promptEnabled = backup.promptEnabled
            if (json.has("promptMinutes")) data.promptMinutes = backup.promptMinutes
        }
        return backup.days.size
    }

    private fun toJson(data: Data) = JSONObject().apply {
        put("version", 1)
        put("promptEnabled", data.promptEnabled)
        put("promptMinutes", data.promptMinutes)
        put("lastPromptDate", data.lastPromptDate)
        put("backupFolder", data.backupFolder)
        put("days", JSONObject().apply {
            data.days.forEach { (key, day) ->
                put(key, JSONObject().apply {
                    day.mood?.let { put("mood", it) }
                    day.usageMs?.let { put("usageMs", it) }
                })
            }
        })
    }

    private fun fromJson(json: JSONObject): Data {
        val data = Data(
            promptEnabled = json.optBoolean("promptEnabled", false),
            promptMinutes = json.optInt("promptMinutes", DEFAULT_PROMPT_MINUTES),
            lastPromptDate = json.optString("lastPromptDate", ""),
            backupFolder = json.optString("backupFolder", ""),
        )
        val days = json.optJSONObject("days") ?: return data
        days.keys().forEach { key ->
            val day = days.optJSONObject(key) ?: return@forEach
            val mood = if (day.has("mood")) day.getInt("mood").coerceIn(0, 4) else null
            val usage = if (day.has("usageMs")) day.getLong("usageMs") else null
            if (mood != null || usage != null) data.days[key] = Day(mood, usage)
        }
        return data
    }

    companion object {
        const val DIR = "mood"
        const val FILE_NAME = "mood_data.json"
        const val DEFAULT_PROMPT_MINUTES = 21 * 60

        const val HAPPY = 0
        const val MILDLY_HAPPY = 1
        const val NEUTRAL = 2
        const val MILDLY_SAD = 3
        const val SAD = 4

        private val LOCK = Any()

        fun moodForUsage(usageMs: Long): Int {
            val minutes = usageMs / 60_000
            return when {
                minutes < 30 -> HAPPY
                minutes < 60 -> MILDLY_HAPPY
                minutes < 120 -> NEUTRAL
                minutes < 240 -> MILDLY_SAD
                else -> SAD
            }
        }
    }
}
