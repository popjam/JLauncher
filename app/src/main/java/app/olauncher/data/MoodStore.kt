package app.olauncher.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Mood, daily usage and custom stat history, stored as a single JSON file in app storage.
 *
 * [BackupManager] bundles this file with the launcher settings.
 */
class MoodStore(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(File(appContext.filesDir, DIR), FILE_NAME)

    /** Custom stat values are faces, like [mood]: 0 (happy) to 4 (sad), keyed by [Stat.id]. */
    data class Day(
        val mood: Int?,
        val usageMs: Long?,
        val note: String? = null,
        val values: Map<String, Int> = emptyMap(),
    ) {
        val isEmpty get() = mood == null && usageMs == null && note == null && values.isEmpty()

        /** The face for [MOOD], [USAGE] or a custom stat's id. */
        fun face(statId: String): Int? = when (statId) {
            MOOD -> mood
            USAGE -> usageMs?.let { moodForUsage(it) }
            else -> values[statId]
        }
    }

    /**
     * A custom stat. [scale] is 2, 3 or 5 faces (see [facesFor]) and can't change once added. [question]
     * replaces the daily check's usual title while it's asked; empty for the default. [labels] go under
     * the faces, in [facesFor] order; empty when there are none. [inverted] stats show happy on the
     * left and score sad highest (see [score]).
     */
    data class Stat(
        val id: String,
        val name: String,
        val color: Int,
        val daily: Boolean,
        val scale: Int,
        val question: String = "",
        val labels: List<String> = emptyList(),
        val inverted: Boolean = false,
    )

    class Data(
        val days: MutableMap<String, Day> = sortedMapOf(),
        val stats: MutableList<Stat> = mutableListOf(),
        var moodColor: Int = DEFAULT_MOOD_COLOR,
        var usageColor: Int = DEFAULT_USAGE_COLOR,
        /** Like [Stat.question], for the mood */
        var moodQuestion: String = "",
        /** Like [Stat.inverted], for the built-in stats */
        var moodInverted: Boolean = false,
        var usageInverted: Boolean = false,
        /**
         * Stat ids, built-ins included, in the order picked by dragging the tabs. Stats missing from it
         * come after, Mood and Screen time first.
         */
        val order: MutableList<String> = mutableListOf(),
        /** Stat ids whose lines are switched off in the All graph */
        val hiddenInGraph: MutableSet<String> = mutableSetOf(),
        var promptEnabled: Boolean = false,
        var promptMinutes: Int = DEFAULT_PROMPT_MINUTES,
        var promptSnoozedUntil: String = "",
        var backupFolder: String = "",
    ) {
        fun stat(id: String): Stat? = stats.firstOrNull { it.id == id }

        fun isInverted(statId: String): Boolean = when (statId) {
            MOOD -> moodInverted
            USAGE -> usageInverted
            else -> stat(statId)?.inverted ?: false
        }

        fun colorOf(statId: String): Int = when (statId) {
            MOOD -> moodColor
            USAGE -> usageColor
            else -> stat(statId)?.color ?: DEFAULT_MOOD_COLOR
        }

        /** Custom stats asked in the daily prompt, after the mood. */
        val dailyStats get() = stats.filter { it.daily }

        /** A day is done when its mood and every daily stat are in. */
        fun isComplete(date: LocalDate): Boolean {
            val day = days[date.toString()] ?: return false
            return day.mood != null && dailyStats.all { day.values[it.id] != null }
        }
    }

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

    /** Days left with nothing in them are removed. */
    private fun Data.editDay(key: String, block: (Day) -> Day) {
        val day = block(days[key] ?: Day(null, null))
        if (day.isEmpty) days.remove(key) else days[key] = day
    }

    /** Sets [MOOD] or a custom stat; null clears it. */
    fun setFace(date: LocalDate, statId: String, face: Int?) = update { data ->
        data.editDay(date.toString()) { day ->
            if (statId == MOOD) day.copy(mood = face)
            else day.copy(values = if (face == null) day.values - statId else day.values + (statId to face))
        }
    }

    /** Cleaned with [sanitizeNote]; a blank note removes it. */
    fun setNote(date: LocalDate, note: String?) = update { data ->
        data.editDay(date.toString()) { it.copy(note = sanitizeNote(note)) }
    }

    /**
     * Keeps the larger value, so a partial snapshot never overwrites a complete day.
     * Not backed up straight away: [BackupManager.syncIfStale] and other changes pick it up.
     */
    fun setUsage(date: LocalDate, usageMs: Long) {
        val old = getDay(date)?.usageMs
        if (old != null && old >= usageMs) return
        update(sync = false) { data ->
            data.editDay(date.toString()) { it.copy(usageMs = maxOf(usageMs, it.usageMs ?: 0L)) }
        }
    }

    // --- Stats ---

    fun addStat(
        name: String, color: Int, daily: Boolean, scale: Int, question: String, labels: List<String>, inverted: Boolean,
    ): Stat {
        val stat = Stat(
            "s" + System.currentTimeMillis().toString(36), sanitizeName(name), color, daily, scale,
            sanitizeQuestion(question), sanitizeLabels(labels, scale), inverted,
        )
        update { it.stats.add(stat) }
        return stat
    }

    /** The scale stays as it was. */
    fun editStat(
        id: String, name: String, color: Int, daily: Boolean, question: String, labels: List<String>, inverted: Boolean,
    ) = update { data ->
        val index = data.stats.indexOfFirst { it.id == id }
        if (index >= 0) data.stats[index] = data.stats[index].let {
            it.copy(
                name = sanitizeName(name), color = color, daily = daily, question = sanitizeQuestion(question),
                labels = sanitizeLabels(labels, it.scale), inverted = inverted,
            )
        }
    }

    /** Screen time isn't in the daily check, so its [question] is ignored. */
    fun editBuiltIn(id: String, color: Int, question: String, inverted: Boolean) = update { data ->
        when (id) {
            MOOD -> {
                data.moodColor = color
                data.moodQuestion = sanitizeQuestion(question)
                data.moodInverted = inverted
            }
            USAGE -> {
                data.usageColor = color
                data.usageInverted = inverted
            }
        }
    }

    fun setOrder(ids: List<String>) = update { data ->
        data.order.clear()
        data.order.addAll(ids)
    }

    /** Removes the stat and all its history. */
    fun deleteStat(id: String) = update { data ->
        data.stats.removeAll { it.id == id }
        data.hiddenInGraph.remove(id)
        data.order.remove(id)
        data.days.keys.toList().forEach { key -> data.editDay(key) { it.copy(values = it.values - id) } }
    }

    fun setHiddenInGraph(ids: Set<String>) = update { it.setHidden(ids) }

    private fun Data.setHidden(ids: Set<String>) {
        hiddenInGraph.clear()
        hiddenInGraph.addAll(ids)
    }

    // --- Daily prompt ---

    fun isPromptDue(now: LocalDateTime = LocalDateTime.now()): Boolean = nextPromptAt(now)?.let { it <= now } ?: false

    /**
     * When the prompt is next due today: the prompt time, or [PROMPT_SNOOZE_MINUTES] after it was
     * last dismissed. Null when it's off or today's mood and daily stats are all in; it stops at midnight.
     */
    fun nextPromptAt(now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        val data = load()
        if (!data.promptEnabled) return null
        val today = now.toLocalDate()
        if (data.isComplete(today)) return null
        val promptTime = today.atStartOfDay().plusMinutes(data.promptMinutes.toLong())
        val snoozedUntil = data.promptSnoozedUntil.takeIf { it.isNotEmpty() }
            ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        return listOfNotNull(promptTime, snoozedUntil).max()
    }

    /**
     * [faces] maps [MOOD] and the daily stat ids to faces. Null means the prompt was dismissed,
     * so it comes back after [PROMPT_SNOOZE_MINUTES].
     */
    fun answerPrompt(faces: Map<String, Int>?, note: String? = null) = update { data ->
        if (faces == null) {
            data.promptSnoozedUntil = LocalDateTime.now().plusMinutes(PROMPT_SNOOZE_MINUTES).withNano(0).toString()
        } else {
            data.editDay(LocalDate.now().toString()) { day ->
                day.copy(
                    mood = faces[MOOD] ?: day.mood,
                    note = sanitizeNote(note) ?: day.note,
                    values = day.values + (faces - MOOD),
                )
            }
        }
    }

    // --- Backup ---

    /** Everything except the backup folder link, which only makes sense on this device. */
    fun toBackupJson(): JSONObject = synchronized(LOCK) {
        toJson(load()).apply { remove("backupFolder") }
    }

    /**
     * Merges a backup into local data: stats, moods, stat values and notes from the backup win, usage
     * keeps the larger value, and the prompt and graph settings are taken from the backup.
     * Returns the number of days in the backup.
     */
    fun restoreFrom(json: JSONObject): Int {
        val backup = fromJson(json)
        update { data ->
            backup.stats.forEach { stat ->
                val index = data.stats.indexOfFirst { it.id == stat.id }
                if (index >= 0) data.stats[index] = stat else data.stats.add(stat)
            }
            backup.days.forEach { (key, day) ->
                val local = data.days[key]
                val usage = listOfNotNull(local?.usageMs, day.usageMs).maxOrNull()
                data.days[key] = Day(day.mood ?: local?.mood, usage, day.note ?: local?.note, local?.values.orEmpty() + day.values)
            }
            if (json.has("moodColor")) data.moodColor = backup.moodColor
            if (json.has("usageColor")) data.usageColor = backup.usageColor
            if (json.has("moodQuestion")) data.moodQuestion = backup.moodQuestion
            if (json.has("moodInverted")) data.moodInverted = backup.moodInverted
            if (json.has("usageInverted")) data.usageInverted = backup.usageInverted
            if (json.has("order")) {
                data.order.clear()
                data.order.addAll(backup.order)
            }
            if (json.has("hiddenInGraph")) data.setHidden(backup.hiddenInGraph)
            if (json.has("promptEnabled")) data.promptEnabled = backup.promptEnabled
            if (json.has("promptMinutes")) data.promptMinutes = backup.promptMinutes
        }
        return backup.days.size
    }

    private fun toJson(data: Data) = JSONObject().apply {
        put("version", 2)
        put("promptEnabled", data.promptEnabled)
        put("promptMinutes", data.promptMinutes)
        put("promptSnoozedUntil", data.promptSnoozedUntil)
        put("backupFolder", data.backupFolder)
        put("moodColor", data.moodColor)
        put("usageColor", data.usageColor)
        put("moodQuestion", data.moodQuestion)
        put("moodInverted", data.moodInverted)
        put("usageInverted", data.usageInverted)
        put("hiddenInGraph", JSONArray(data.hiddenInGraph.toList()))
        put("order", JSONArray(data.order))
        put("stats", JSONArray().apply {
            data.stats.forEach { stat ->
                put(JSONObject().apply {
                    put("id", stat.id)
                    put("name", stat.name)
                    put("color", stat.color)
                    put("daily", stat.daily)
                    put("scale", stat.scale)
                    put("question", stat.question)
                    if (stat.labels.isNotEmpty()) put("labels", JSONArray(stat.labels))
                    put("inverted", stat.inverted)
                })
            }
        })
        put("days", JSONObject().apply {
            data.days.forEach { (key, day) ->
                put(key, JSONObject().apply {
                    day.mood?.let { put("mood", it) }
                    day.usageMs?.let { put("usageMs", it) }
                    day.note?.let { put("note", it) }
                    if (day.values.isNotEmpty()) put("values", JSONObject().apply { day.values.forEach { (id, face) -> put(id, face) } })
                })
            }
        })
    }

    private fun fromJson(json: JSONObject): Data {
        val data = Data(
            moodColor = json.optInt("moodColor", DEFAULT_MOOD_COLOR),
            usageColor = json.optInt("usageColor", DEFAULT_USAGE_COLOR),
            moodQuestion = sanitizeQuestion(json.optString("moodQuestion")),
            moodInverted = json.optBoolean("moodInverted"),
            usageInverted = json.optBoolean("usageInverted"),
            promptEnabled = json.optBoolean("promptEnabled", false),
            promptMinutes = json.optInt("promptMinutes", DEFAULT_PROMPT_MINUTES),
            promptSnoozedUntil = json.optString("promptSnoozedUntil", ""),
            backupFolder = json.optString("backupFolder", ""),
        )
        json.optJSONArray("stats")?.let { stats ->
            for (i in 0 until stats.length()) {
                val stat = stats.optJSONObject(i) ?: continue
                val id = stat.optString("id").takeIf { it.isNotEmpty() && it != MOOD && it != USAGE && it != ALL } ?: continue
                if (data.stat(id) != null) continue
                val scale = stat.optInt("scale", 5).takeIf { it in SCALES } ?: 5
                val name = sanitizeName(stat.optString("name")).ifEmpty { "?" }
                val question = sanitizeQuestion(stat.optString("question"))
                val labels = stat.optJSONArray("labels")?.let { array -> List(array.length()) { array.optString(it) } }.orEmpty()
                data.stats.add(
                    Stat(
                        id, name, stat.optInt("color", DEFAULT_MOOD_COLOR), stat.optBoolean("daily"), scale,
                        question, sanitizeLabels(labels, scale), stat.optBoolean("inverted"),
                    )
                )
            }
        }
        json.optJSONArray("order")?.let { order ->
            for (i in 0 until order.length()) {
                order.optString(i).takeIf { it.isNotEmpty() && it !in data.order }?.let { data.order.add(it) }
            }
        }
        json.optJSONArray("hiddenInGraph")?.let { hidden ->
            for (i in 0 until hidden.length()) hidden.optString(i).takeIf { it.isNotEmpty() }?.let { data.hiddenInGraph.add(it) }
        }
        val days = json.optJSONObject("days") ?: return data
        days.keys().forEach { key ->
            val day = days.optJSONObject(key) ?: return@forEach
            val mood = if (day.has("mood")) day.getInt("mood").coerceIn(0, 4) else null
            val usage = if (day.has("usageMs")) day.getLong("usageMs") else null
            val note = sanitizeNote(day.optString("note"))
            val values = mutableMapOf<String, Int>()
            day.optJSONObject("values")?.let { obj ->
                obj.keys().forEach { id -> values[id] = obj.optInt(id, NEUTRAL).coerceIn(0, 4) }
            }
            val parsed = Day(mood, usage, note, values)
            if (!parsed.isEmpty) data.days[key] = parsed
        }
        return data
    }

    companion object {
        const val DIR = "mood"
        const val FILE_NAME = "mood_data.json"
        const val DEFAULT_PROMPT_MINUTES = 21 * 60
        const val MAX_NOTE_LENGTH = 2000
        const val MAX_NAME_LENGTH = 24
        const val MAX_QUESTION_LENGTH = 80
        const val MAX_LABEL_LENGTH = 10
        const val PROMPT_SNOOZE_MINUTES = 30L

        const val HAPPY = 0
        const val MILDLY_HAPPY = 1
        const val NEUTRAL = 2
        const val MILDLY_SAD = 3
        const val SAD = 4

        /** The built-in stats' ids, and [ALL] for the graph of every stat */
        const val ALL = "all"
        const val MOOD = "mood"
        const val USAGE = "usage"

        val SCALES = listOf(2, 3, 5)
        const val DEFAULT_MOOD_COLOR = 0xFFF2B33D.toInt()
        const val DEFAULT_USAGE_COLOR = 0xFFB388FF.toInt()

        /** New stats start with the next of these; any colour can be picked */
        val NEW_STAT_COLORS = intArrayOf(
            0xFF5B8DEF.toInt(), 0xFF4FC3A1.toInt(), 0xFFE05FA8.toInt(),
            0xFF4DD0E1.toInt(), 0xFFFF8A65.toInt(), 0xFFAED581.toInt(),
        )

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

        /** One line, capped at [MAX_NAME_LENGTH]. */
        fun sanitizeName(name: String?): String =
            sanitizeNote(name?.replace('\n', ' '))?.take(MAX_NAME_LENGTH)?.trim().orEmpty()

        /** One line, capped at [MAX_QUESTION_LENGTH]. */
        fun sanitizeQuestion(question: String?): String =
            sanitizeNote(question?.replace('\n', ' '))?.take(MAX_QUESTION_LENGTH)?.trim().orEmpty()

        /** One per face of [scale], each one line capped at [MAX_LABEL_LENGTH]; empty when all are blank. */
        fun sanitizeLabels(labels: List<String>, scale: Int): List<String> {
            val cleaned = List(scale) { i ->
                sanitizeNote(labels.getOrNull(i)?.replace('\n', ' '))?.take(MAX_LABEL_LENGTH)?.trim().orEmpty()
            }
            return if (cleaned.all { it.isEmpty() }) emptyList() else cleaned
        }

        /**
         * The faces a scale offers, sad to happy as shown left to right: sad and happy, plus neutral
         * for 3, plus the mild ones for 5.
         */
        fun facesFor(scale: Int): List<Int> = when (scale) {
            2 -> listOf(SAD, HAPPY)
            3 -> listOf(SAD, NEUTRAL, HAPPY)
            else -> listOf(SAD, MILDLY_SAD, NEUTRAL, MILDLY_HAPPY, HAPPY)
        }

        /** Happy scores 5 and sad 1, whatever the scale. */
        fun score(face: Int, inverted: Boolean = false): Int = if (inverted) face + 1 else 5 - face

        /** The face that gets [score]. */
        fun faceForScore(score: Int, inverted: Boolean = false): Int = if (inverted) score - 1 else 5 - score

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
