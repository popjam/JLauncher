package app.olauncher.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.concurrent.Executors

/**
 * Mood + daily usage history, stored as a single JSON file in app storage.
 *
 * Kept out of SharedPreferences on purpose: the accessibility service runs in a separate process
 * and needs to read fresh data, and the file is what gets exported, synced to the backup folder
 * and included in Android Auto Backup (see backup rules).
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
        val json = synchronized(LOCK) {
            val data = load()
            block(data)
            val json = toJson(data).toString(2)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(json)
            tmp.renameTo(file)
            json
        }
        syncToBackupFolder(json)
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

    // --- Export / import / backup folder ---

    fun exportTo(uri: Uri): Boolean = try {
        val json = synchronized(LOCK) { toJson(load()).toString(2) }
        appContext.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) }
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    /** Merges days from [uri] into local data. Returns the number of days read, or -1 on failure. */
    fun importFrom(uri: Uri): Int = try {
        val text = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: throw IllegalStateException("Empty file")
        val imported = fromJson(JSONObject(text))
        update { data -> merge(data, imported) }
        imported.days.size
    } catch (e: Exception) {
        e.printStackTrace()
        -1
    }

    /**
     * Links a folder picked with ACTION_OPEN_DOCUMENT_TREE. If it already holds a backup (e.g. after
     * a reinstall) that is merged in first, then the folder is kept in sync on every change.
     * Returns the number of days restored, or -1 if the folder couldn't be used.
     */
    fun setBackupFolder(treeUri: Uri): Int {
        val existing = findBackupDocument(treeUri)
        var restored = 0
        if (existing != null) {
            restored = importFrom(existing)
            if (restored < 0) return -1
        }
        update { it.backupFolder = treeUri.toString() }
        return restored
    }

    fun clearBackupFolder() = update { it.backupFolder = "" }

    private fun syncToBackupFolder(json: String) {
        val folder = load().backupFolder
        if (folder.isEmpty()) return
        IO.execute {
            try {
                val treeUri = Uri.parse(folder)
                val doc = findBackupDocument(treeUri) ?: DocumentsContract.createDocument(
                    appContext.contentResolver,
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)),
                    "application/json",
                    BACKUP_FILE_NAME
                ) ?: return@execute
                appContext.contentResolver.openOutputStream(doc, "wt")?.use { it.write(json.toByteArray()) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun findBackupDocument(treeUri: Uri): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        return try {
            appContext.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == BACKUP_FILE_NAME)
                        return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))
                }
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun merge(into: Data, from: Data) {
        from.days.forEach { (key, day) ->
            val local = into.days[key]
            val usage = listOfNotNull(local?.usageMs, day.usageMs).maxOrNull()
            into.days[key] = Day(day.mood ?: local?.mood, usage)
        }
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
        const val BACKUP_FILE_NAME = "jlauncher_mood.json"
        const val DEFAULT_PROMPT_MINUTES = 21 * 60

        const val HAPPY = 0
        const val MILDLY_HAPPY = 1
        const val NEUTRAL = 2
        const val MILDLY_SAD = 3
        const val SAD = 4

        private val LOCK = Any()
        private val IO = Executors.newSingleThreadExecutor()

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
