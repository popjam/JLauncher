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
    /**
     * [topApp] is the name of the app used most that day and [topAppMs] its time; [weatherCode] is the
     * day's WMO weather code. Both are kept for the day's written summary. [usageMs] is the phone's
     * screen time and [deviceMs] other devices' (the Mac's and TV's, by [DeviceTime.Device.key]);
     * Screen time is them all added up.
     */
    data class Day(
        val mood: Int?,
        val usageMs: Long?,
        val note: String? = null,
        val values: Map<String, Int> = emptyMap(),
        val topApp: String? = null,
        val topAppMs: Long? = null,
        val weatherCode: Int? = null,
        val deviceMs: Map<String, Long> = emptyMap(),
    ) {
        val isEmpty
            get() = mood == null && usageMs == null && note == null && values.isEmpty() && topApp == null && weatherCode == null &&
                deviceMs.isEmpty()

        /** Screen time: the phone's and every other device's */
        val totalUsageMs: Long?
            get() = if (usageMs == null && deviceMs.isEmpty()) null else (usageMs ?: 0L) + deviceMs.values.sum()

        /** The face for [MOOD], [USAGE] or a custom stat's id. */
        fun face(statId: String): Int? = when (statId) {
            MOOD -> mood
            USAGE -> totalUsageMs?.let { moodForTotalUsage(it) }
            else -> values[statId]
        }
    }

    /**
     * A custom stat. [scale] is 2, 3 or 5 faces (see [facesFor]) and can't change once added. [question]
     * replaces the daily check's usual title while it's asked; empty for the default. [labels] go under
     * the faces, left to right; empty when there are none. [phrases] are the day summary's sentence
     * for each place in the row, in the same order, with {} standing for that place's label. Both stay
     * in place when the faces swap. [inverted] stats show happy on the left and score sad highest
     * (see [score]). [medicine] stats colour their faces white (leftmost, none taken) then light to dark
     * purple, and leave the leftmost blank on the calendar and daily graph, so the days it was taken stand out.
     * [nextDay] medicines (taken at night, say) are counted towards the day after on the stat pages.
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
        val phrases: List<String> = emptyList(),
        val medicine: Boolean = false,
        val nextDay: Boolean = false,
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
        /** Stat ids drawn in colour on the weekly and monthly graphs; the rest are greyed behind them */
        val comparedInGraph: MutableSet<String> = mutableSetOf(MOOD),
        /** Whether the All graph's day details are a written summary rather than each stat's value */
        var daySummary: Boolean = false,
        /** The All graph last shown: daily, weekly or monthly */
        var graphMode: String = "",
        var promptEnabled: Boolean = false,
        var promptMinutes: Int = DEFAULT_PROMPT_MINUTES,
        var promptSnoozedUntil: String = "",
        /** Ask about a missed day the next morning, until [catchUpMinutes] past midnight */
        var catchUpEnabled: Boolean = true,
        var catchUpMinutes: Int = DEFAULT_CATCH_UP_MINUTES,
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

    /** When the stats were last saved, to tell whether something drawn from them is out of date */
    fun lastSaved(): Long = file.lastModified()

    /** The last good copy, kept beside the file each time it's replaced */
    private val previous get() = File(file.parentFile, "$FILE_NAME.bak")

    private fun read(from: File): Data? = try {
        fromJson(JSONObject(from.readText()))
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    /**
     * The stats, or the last good copy if the file can't be read. Empty only when there's nothing at
     * all yet, or neither can be read (and then [update] won't save over them).
     */
    fun load(): Data = synchronized(LOCK) {
        // Read once for each time it's saved; what comes back is shared, so it's only read, never changed
        val stamp = file.lastModified() to file.length()
        if (cached != null && stamp == cachedStamp && cachedPath == file.path) return cached!!
        val data = loadForWrite() ?: Data()
        cached = data
        cachedStamp = stamp
        cachedPath = file.path
        return data
    }

    /** Like [load], but null when there's a file that can't be read and no good copy, so nothing is saved over it */
    private fun loadForWrite(): Data? = loadChecked().first

    /** [loadForWrite], and whether it came from the file itself (so the file reads, and can be kept as the good copy) */
    private fun loadChecked(): Pair<Data?, Boolean> {
        if (file.exists()) read(file)?.let { return it to true }
        if (previous.exists()) read(previous)?.let { return it to false }
        return (if (file.exists()) null else Data()) to false
    }

    /**
     * [sync] = false skips the backup; used for usage, which changes too often to back up every time.
     * Nothing is saved while the stats can't be read, so a bad read can never wipe them; [force] (a
     * restore) starts afresh instead.
     */
    fun update(sync: Boolean = true, force: Boolean = false, block: (Data) -> Unit) {
        synchronized(LOCK) {
            val (loaded, fileReads) = loadChecked()
            val data = loaded ?: if (force) {
                // Restoring a backup over a file that can't be read: the file is kept aside, just in case
                runCatching { file.copyTo(File(file.parentFile, "$FILE_NAME.unreadable"), overwrite = true) }
                Data()
            } else {
                android.util.Log.e("MoodStore", "Stats couldn't be read; not saving over them")
                return
            }
            block(data)
            file.parentFile?.mkdirs()
            // The file as it was goes aside first, if it reads
            if (fileReads) runCatching { file.copyTo(previous, overwrite = true) }
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(toJson(data).toString())
            tmp.renameTo(file)
            // What was just written is what the next load would read
            cached = data
            cachedStamp = file.lastModified() to file.length()
            cachedPath = file.path
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

    /** The check-in asks these stats (after the mood) and no others */
    fun setDaily(ids: Set<String>) = update { data ->
        data.stats.indices.forEach { i -> data.stats[i] = data.stats[i].copy(daily = data.stats[i].id in ids) }
    }

    /** Several days' faces for [statId] in one save, only where they've changed */
    fun setFaces(statId: String, faces: Map<LocalDate, Int>) {
        val days = load().days
        val changed = faces.filter { (date, face) -> days[date.toString()]?.values?.get(statId) != face }
        if (changed.isEmpty()) return
        update { data ->
            changed.forEach { (date, face) -> data.editDay(date.toString()) { day -> day.copy(values = day.values + (statId to face)) } }
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
    fun setUsage(date: LocalDate, usageMs: Long, topApp: String? = null, topAppMs: Long? = null) {
        val old = getDay(date)
        val oldUsage = old?.usageMs
        // Still worth saving the top app for a day that was recorded before it was kept
        if (oldUsage != null && oldUsage >= usageMs && (old.topApp != null || topApp == null)) return
        update(sync = false) { data ->
            data.editDay(date.toString()) {
                when {
                    usageMs >= (it.usageMs ?: 0L) -> it.copy(usageMs = usageMs, topApp = topApp, topAppMs = topAppMs)
                    it.topApp == null -> it.copy(topApp = topApp, topAppMs = topAppMs)
                    else -> it
                }
            }
        }
    }

    /** A device's screen time by day, replacing what it had. Not backed up straight away, like usage. */
    fun setDeviceUsage(device: String, byDay: Map<LocalDate, Long>) {
        val data = load()
        val changed = byDay.filter { (date, ms) -> data.days[date.toString()]?.deviceMs?.get(device) != ms }
        if (changed.isEmpty()) return
        update(sync = false) { d ->
            changed.forEach { (date, ms) ->
                d.editDay(date.toString()) { it.copy(deviceMs = if (ms > 0) it.deviceMs + (device to ms) else it.deviceMs - device) }
            }
        }
    }

    /** The latest forecast's code for [date]. Not backed up straight away, like usage. */
    fun setWeather(date: LocalDate, code: Int) {
        if (getDay(date)?.weatherCode == code) return
        update(sync = false) { data -> data.editDay(date.toString()) { it.copy(weatherCode = code) } }
    }

    // --- Stats ---

    fun addStat(
        name: String, color: Int, daily: Boolean, scale: Int, question: String, labels: List<String>, inverted: Boolean,
        phrases: List<String>, medicine: Boolean, nextDay: Boolean,
    ): Stat {
        val stat = Stat(
            "s" + System.currentTimeMillis().toString(36), sanitizeName(name), color, daily, scale,
            sanitizeQuestion(question), sanitizeLabels(labels, scale), inverted, sanitizePhrases(phrases, scale), medicine,
            nextDay && medicine,
        )
        update { it.stats.add(stat) }
        return stat
    }

    /** The scale stays as it was. Changing [inverted] flips the stat's history (see [flipHistory]). */
    fun editStat(
        id: String, name: String, color: Int, daily: Boolean, question: String, labels: List<String>, inverted: Boolean,
        phrases: List<String>, medicine: Boolean, nextDay: Boolean,
    ) = update { data ->
        val index = data.stats.indexOfFirst { it.id == id }
        if (index >= 0) data.stats[index] = data.stats[index].let {
            if (it.inverted != inverted) data.flipHistory(id)
            it.copy(
                name = sanitizeName(name), color = color, daily = daily, question = sanitizeQuestion(question),
                labels = sanitizeLabels(labels, it.scale), inverted = inverted, phrases = sanitizePhrases(phrases, it.scale),
                medicine = medicine, nextDay = nextDay && medicine,
            )
        }
    }

    /** Screen time isn't in the daily check, so its [question] is ignored. */
    fun editBuiltIn(id: String, color: Int, question: String, inverted: Boolean) = update { data ->
        when (id) {
            MOOD -> {
                data.moodColor = color
                data.moodQuestion = sanitizeQuestion(question)
                if (data.moodInverted != inverted) data.flipHistory(MOOD)
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

    /**
     * Swaps every saved face for its opposite, so sad becomes happy and the other way round, for [MOOD]
     * or a custom stat. With the faces swapped too, each entry keeps its place in the row and its score.
     */
    private fun Data.flipHistory(statId: String) {
        days.keys.toList().forEach { key ->
            editDay(key) { day ->
                if (statId == MOOD) day.copy(mood = day.mood?.let { SAD - it })
                else day.copy(values = day.values.mapValues { (id, face) -> if (id == statId) SAD - face else face })
            }
        }
    }

    /** Removes the stat and all its history. */
    fun deleteStat(id: String) = update { data ->
        data.stats.removeAll { it.id == id }
        data.hiddenInGraph.remove(id)
        data.comparedInGraph.remove(id)
        data.order.remove(id)
        data.days.keys.toList().forEach { key -> data.editDay(key) { it.copy(values = it.values - id) } }
    }

    fun setHiddenInGraph(ids: Set<String>) = update { it.setHidden(ids) }

    private fun Data.setHidden(ids: Set<String>) {
        hiddenInGraph.clear()
        hiddenInGraph.addAll(ids)
    }

    fun setComparedInGraph(ids: Set<String>) = update { it.setCompared(ids) }

    private fun Data.setCompared(ids: Set<String>) {
        comparedInGraph.clear()
        comparedInGraph.addAll(ids)
    }

    // --- Daily prompt ---

    /** The prompt for [date] is due [at]. */
    data class PromptDue(val date: LocalDate, val at: LocalDateTime)

    /**
     * When the prompt is next due, and for which day. Yesterday comes first: if it was missed and
     * [Data.catchUpEnabled], it's asked from midnight until [Data.catchUpMinutes]. Then today, from the
     * prompt time. Either way not until [PROMPT_SNOOZE_MINUTES] after it was last dismissed. Null when
     * it's off or both days are in; today's stops at midnight.
     */
    fun nextPrompt(now: LocalDateTime = LocalDateTime.now()): PromptDue? {
        val data = load()
        if (!data.promptEnabled) return null
        val today = now.toLocalDate()
        val snoozedUntil = data.promptSnoozedUntil.takeIf { it.isNotEmpty() }
            ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        fun due(from: LocalDateTime) = listOfNotNull(from, snoozedUntil).max()

        val yesterday = today.minusDays(1)
        val catchUpEnd = today.atStartOfDay().plusMinutes(data.catchUpMinutes.toLong())
        if (data.catchUpEnabled && !data.isComplete(yesterday)) {
            val at = due(today.atStartOfDay())
            if (at < catchUpEnd && now < catchUpEnd) return PromptDue(yesterday, at)
        }
        if (data.isComplete(today)) return null
        return PromptDue(today, due(today.atStartOfDay().plusMinutes(data.promptMinutes.toLong())))
    }

    /** Changing when it's asked starts afresh, rather than waiting out an earlier dismissal. */
    fun setPromptTimes(promptMinutes: Int? = null, catchUpEnabled: Boolean? = null, catchUpMinutes: Int? = null) =
        update { data ->
            promptMinutes?.let { data.promptMinutes = it }
            catchUpEnabled?.let { data.catchUpEnabled = it }
            catchUpMinutes?.let { data.catchUpMinutes = it }
            data.promptSnoozedUntil = ""
        }

    /**
     * Saves the prompt's answers for [date]. [faces] maps [MOOD] and the daily stat ids to faces. Null
     * means the prompt was dismissed, so it comes back after [PROMPT_SNOOZE_MINUTES].
     */
    fun answerPrompt(date: LocalDate, faces: Map<String, Int>?, note: String? = null) = update { data ->
        if (faces == null) {
            data.promptSnoozedUntil = LocalDateTime.now().plusMinutes(PROMPT_SNOOZE_MINUTES).withNano(0).toString()
        } else {
            data.editDay(date.toString()) { day ->
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
        update(force = true) { data ->
            backup.stats.forEach { stat ->
                val index = data.stats.indexOfFirst { it.id == stat.id }
                if (index >= 0) {
                    // Days only found here need to match the backup's way round
                    if (data.stats[index].inverted != stat.inverted) data.flipHistory(stat.id)
                    data.stats[index] = stat
                } else data.stats.add(stat)
            }
            if (json.has("moodInverted") && data.moodInverted != backup.moodInverted) data.flipHistory(MOOD)
            backup.days.forEach { (key, day) ->
                val local = data.days[key]
                val usage = listOfNotNull(local?.usageMs, day.usageMs).maxOrNull()
                // The top app goes with whichever usage was kept
                val usageDay = if (usage == day.usageMs || local?.topApp == null) day else local
                data.days[key] = Day(
                    day.mood ?: local?.mood, usage, day.note ?: local?.note, local?.values.orEmpty() + day.values,
                    usageDay.topApp ?: local?.topApp, usageDay.topAppMs ?: local?.topAppMs,
                    day.weatherCode ?: local?.weatherCode,
                    // Each device's larger, like the phone's
                    (local?.deviceMs.orEmpty().keys + day.deviceMs.keys).associateWith { key ->
                        maxOf(local?.deviceMs?.get(key) ?: 0L, day.deviceMs[key] ?: 0L)
                    },
                )
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
            if (json.has("daySummary")) data.daySummary = backup.daySummary
            if (json.has("graphMode")) data.graphMode = backup.graphMode
            if (json.has("comparedInGraph")) data.setCompared(backup.comparedInGraph)
            if (json.has("promptEnabled")) data.promptEnabled = backup.promptEnabled
            if (json.has("promptMinutes")) data.promptMinutes = backup.promptMinutes
            if (json.has("catchUpEnabled")) data.catchUpEnabled = backup.catchUpEnabled
            if (json.has("catchUpMinutes")) data.catchUpMinutes = backup.catchUpMinutes
        }
        return backup.days.size
    }

    private fun toJson(data: Data) = JSONObject().apply {
        put("version", 2)
        put("promptEnabled", data.promptEnabled)
        put("promptMinutes", data.promptMinutes)
        put("promptSnoozedUntil", data.promptSnoozedUntil)
        put("catchUpEnabled", data.catchUpEnabled)
        put("catchUpMinutes", data.catchUpMinutes)
        put("backupFolder", data.backupFolder)
        put("moodColor", data.moodColor)
        put("usageColor", data.usageColor)
        put("moodQuestion", data.moodQuestion)
        put("moodInverted", data.moodInverted)
        put("usageInverted", data.usageInverted)
        put("hiddenInGraph", JSONArray(data.hiddenInGraph.toList()))
        put("daySummary", data.daySummary)
        put("graphMode", data.graphMode)
        put("comparedInGraph", JSONArray(data.comparedInGraph.toList()))
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
                    if (stat.phrases.isNotEmpty()) put("phrases", JSONArray(stat.phrases))
                    put("medicine", stat.medicine)
                    put("nextDay", stat.nextDay)
                })
            }
        })
        put("days", JSONObject().apply {
            data.days.forEach { (key, day) ->
                put(key, JSONObject().apply {
                    day.mood?.let { put("mood", it) }
                    day.usageMs?.let { put("usageMs", it) }
                    day.note?.let { put("note", it) }
                    day.topApp?.let { put("topApp", it) }
                    day.topAppMs?.let { put("topAppMs", it) }
                    day.weatherCode?.let { put("weather", it) }
                    if (day.deviceMs.isNotEmpty()) put("devices", JSONObject().apply { day.deviceMs.forEach { (key, ms) -> put(key, ms) } })
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
            catchUpEnabled = json.optBoolean("catchUpEnabled", true),
            catchUpMinutes = json.optInt("catchUpMinutes", DEFAULT_CATCH_UP_MINUTES).coerceIn(0, 24 * 60 - 1),
            backupFolder = json.optString("backupFolder", ""),
            daySummary = json.optBoolean("daySummary"),
            graphMode = json.optString("graphMode"),
        )
        json.optJSONArray("stats")?.let { stats ->
            for (i in 0 until stats.length()) {
                val stat = stats.optJSONObject(i) ?: continue
                val id = stat.optString("id").takeIf { it.isNotEmpty() && it != MOOD && it != USAGE && it != ALL } ?: continue
                if (data.stat(id) != null) continue
                val scale = stat.optInt("scale", 5).takeIf { it in SCALES } ?: 5
                val name = sanitizeName(stat.optString("name")).ifEmpty { "?" }
                val question = sanitizeQuestion(stat.optString("question"))
                fun strings(key: String) = stat.optJSONArray(key)?.let { array -> List(array.length()) { array.optString(it) } }.orEmpty()
                val labels = strings("labels")
                data.stats.add(
                    Stat(
                        id, name, stat.optInt("color", DEFAULT_MOOD_COLOR), stat.optBoolean("daily"), scale,
                        question, sanitizeLabels(labels, scale), stat.optBoolean("inverted"),
                        sanitizePhrases(strings("phrases"), scale), stat.optBoolean("medicine"),
                        stat.optBoolean("nextDay") && stat.optBoolean("medicine"),
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
        json.optJSONArray("comparedInGraph")?.let { compared ->
            data.comparedInGraph.clear()
            for (i in 0 until compared.length()) compared.optString(i).takeIf { it.isNotEmpty() }?.let { data.comparedInGraph.add(it) }
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
            val parsed = Day(
                mood, usage, note, values,
                topApp = day.optString("topApp").takeIf { it.isNotBlank() },
                topAppMs = if (day.has("topAppMs")) day.getLong("topAppMs") else null,
                weatherCode = if (day.has("weather")) day.getInt("weather") else null,
                deviceMs = day.optJSONObject("devices")?.let { d -> d.keys().asSequence().associateWith { d.getLong(it) } }.orEmpty(),
            )
            if (!parsed.isEmpty) data.days[key] = parsed
        }
        return data
    }

    companion object {
        /** The last stats read, and the file's time and size then */
        @Volatile private var cached: Data? = null
        private var cachedStamp: Pair<Long, Long>? = null
        private var cachedPath: String? = null

        const val DIR = "mood"
        const val FILE_NAME = "mood_data.json"
        const val DEFAULT_PROMPT_MINUTES = 21 * 60
        const val DEFAULT_CATCH_UP_MINUTES = 12 * 60
        const val MAX_NOTE_LENGTH = 2000
        const val MAX_NAME_LENGTH = 24
        const val MAX_QUESTION_LENGTH = 80
        const val MAX_PHRASE_LENGTH = 100
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

        /** One per face of [scale], each one line of any length; empty when all are blank. */
        fun sanitizeLabels(labels: List<String>, scale: Int) = sanitizePerFace(labels, scale, null)

        /** Like [sanitizeLabels], capped at [MAX_PHRASE_LENGTH]. */
        fun sanitizePhrases(phrases: List<String>, scale: Int) = sanitizePerFace(phrases, scale, MAX_PHRASE_LENGTH)

        private fun sanitizePerFace(texts: List<String>, scale: Int, maxLength: Int?): List<String> {
            val cleaned = List(scale) { i ->
                sanitizeNote(texts.getOrNull(i)?.replace('\n', ' '))?.let { if (maxLength == null) it else it.take(maxLength) }?.trim().orEmpty()
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

        /**
         * The face for Screen time, phone and computer and TV together, which run longer than the
         * phone's own: under 2 hours, 2–4, 4–6, 6–8, and 8 or more.
         */
        fun moodForTotalUsage(usageMs: Long): Int {
            val hours = usageMs / 3_600_000.0
            return when {
                hours < 2 -> HAPPY
                hours < 4 -> MILDLY_HAPPY
                hours < 6 -> NEUTRAL
                hours < 8 -> MILDLY_SAD
                else -> SAD
            }
        }

        /** The face for the phone's own screen time, as on the home screen */
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
