package app.olauncher.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Mood + daily usage history, stored as a single JSON file in app storage.
 *
 * [BackupManager] bundles this file with the launcher settings.
 */
class MoodStore(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(File(appContext.filesDir, DIR), FILE_NAME)

    data class Day(val mood: Int?, val usageMs: Long?, val note: String? = null)

    class Data(
        val days: MutableMap<String, Day> = sortedMapOf(),
        var promptEnabled: Boolean = false,
        var promptMinutes: Int = DEFAULT_PROMPT_MINUTES,
        var promptSnoozedUntil: String = "",
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

    /** [sync] = false skips the backup; used for usage, which changes too often to back up every time. */
    fun update(sync: Boolean = true, block: (Data) -> Unit) {
        synchronized(LOCK) {
            val data = load()
            block(data)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(toJson(data).toString(2))
            tmp.renameTo(file)
        }
        if (sync) BackupManager.requestSync(appContext)
    }

    fun getDay(date: LocalDate): Day? = load().days[date.toString()]

    fun setMood(date: LocalDate, mood: Int?) = update { data ->
        val key = date.toString()
        val old = data.days[key]
        data.days[key] = Day(mood, old?.usageMs, old?.note)
    }

    /** Cleaned with [sanitizeNote]; a blank note removes it. */
    fun setNote(date: LocalDate, note: String?) = update { data ->
        val key = date.toString()
        val old = data.days[key]
        data.days[key] = Day(old?.mood, old?.usageMs, sanitizeNote(note))
    }

    /**
     * Keeps the larger value, so a partial snapshot never overwrites a complete day.
     * Not backed up straight away: [BackupManager.syncIfStale] and other changes pick it up.
     */
    fun setUsage(date: LocalDate, usageMs: Long) {
        val old = getDay(date)?.usageMs
        if (old != null && old >= usageMs) return
        update(sync = false) { data ->
            val key = date.toString()
            val day = data.days[key]
            data.days[key] = Day(day?.mood, maxOf(usageMs, day?.usageMs ?: 0L), day?.note)
        }
    }

    // --- Daily prompt ---

    fun isPromptDue(now: LocalDateTime = LocalDateTime.now()): Boolean = nextPromptAt(now)?.let { it <= now } ?: false

    /**
     * When the prompt is next due today: the prompt time, or [PROMPT_SNOOZE_MINUTES] after it was
     * last dismissed. Null when it's off or today's mood is already in; it stops at midnight.
     */
    fun nextPromptAt(now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        val data = load()
        if (!data.promptEnabled) return null
        val today = now.toLocalDate()
        if (data.days[today.toString()]?.mood != null) return null
        val promptTime = today.atStartOfDay().plusMinutes(data.promptMinutes.toLong())
        val snoozedUntil = data.promptSnoozedUntil.takeIf { it.isNotEmpty() }
            ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        return listOfNotNull(promptTime, snoozedUntil).max()
    }

    /** A null [mood] means the prompt was dismissed, so it comes back after [PROMPT_SNOOZE_MINUTES]. */
    fun answerPrompt(mood: Int?, note: String? = null) = update { data ->
        val today = LocalDate.now().toString()
        if (mood == null) {
            data.promptSnoozedUntil = LocalDateTime.now().plusMinutes(PROMPT_SNOOZE_MINUTES).withNano(0).toString()
        } else {
            val old = data.days[today]
            data.days[today] = Day(mood, old?.usageMs, sanitizeNote(note) ?: old?.note)
        }
    }

    // --- Backup ---

    /** Everything except the backup folder link, which only makes sense on this device. */
    fun toBackupJson(): JSONObject = synchronized(LOCK) {
        toJson(load()).apply { remove("backupFolder") }
    }

    /**
     * Merges a backup into local data: moods and notes from the backup win, usage keeps the larger value,
     * and the prompt settings are taken from the backup. Returns the number of days in the backup.
     */
    fun restoreFrom(json: JSONObject): Int {
        val backup = fromJson(json)
        update { data ->
            backup.days.forEach { (key, day) ->
                val local = data.days[key]
                val usage = listOfNotNull(local?.usageMs, day.usageMs).maxOrNull()
                data.days[key] = Day(day.mood ?: local?.mood, usage, day.note ?: local?.note)
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
        put("promptSnoozedUntil", data.promptSnoozedUntil)
        put("backupFolder", data.backupFolder)
        put("days", JSONObject().apply {
            data.days.forEach { (key, day) ->
                put(key, JSONObject().apply {
                    day.mood?.let { put("mood", it) }
                    day.usageMs?.let { put("usageMs", it) }
                    day.note?.let { put("note", it) }
                })
            }
        })
    }

    private fun fromJson(json: JSONObject): Data {
        val data = Data(
            promptEnabled = json.optBoolean("promptEnabled", false),
            promptMinutes = json.optInt("promptMinutes", DEFAULT_PROMPT_MINUTES),
            promptSnoozedUntil = json.optString("promptSnoozedUntil", ""),
            backupFolder = json.optString("backupFolder", ""),
        )
        val days = json.optJSONObject("days") ?: return data
        days.keys().forEach { key ->
            val day = days.optJSONObject(key) ?: return@forEach
            val mood = if (day.has("mood")) day.getInt("mood").coerceIn(0, 4) else null
            val usage = if (day.has("usageMs")) day.getLong("usageMs") else null
            val note = sanitizeNote(day.optString("note"))
            if (mood != null || usage != null || note != null) data.days[key] = Day(mood, usage, note)
        }
        return data
    }

    companion object {
        const val DIR = "mood"
        const val FILE_NAME = "mood_data.json"
        const val DEFAULT_PROMPT_MINUTES = 21 * 60
        const val MAX_NOTE_LENGTH = 2000
        const val PROMPT_SNOOZE_MINUTES = 30L

        const val HAPPY = 0
        const val MILDLY_HAPPY = 1
        const val NEUTRAL = 2
        const val MILDLY_SAD = 3
        const val SAD = 4

        private val LOCK = Any()

        // Control characters (except newlines) and zero-width spaces
        private val INVISIBLE_CHARS = Regex("[\\p{Cntrl}\\u200B-\\u200D\\uFEFF&&[^\\n]]")
        private val SPACES = Regex("\\h+")
        private val BLANK_LINES = Regex("\\n{3,}")

        /**
         * Strips control characters, trims each line, collapses runs of spaces and blank lines,
         * and caps the length. Returns null when nothing is left.
         */
        fun sanitizeNote(note: String?): String? = note
            ?.replace("\r\n", "\n")
            ?.replace('\t', ' ')
            ?.replace(INVISIBLE_CHARS, "")
            ?.lines()
            ?.joinToString("\n") { it.replace(SPACES, " ").trim() }
            ?.replace(BLANK_LINES, "\n\n")
            ?.trim()
            ?.take(MAX_NOTE_LENGTH)
            ?.trim()
            ?.ifEmpty { null }

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
