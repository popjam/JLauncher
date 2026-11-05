package app.olauncher.helper

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.olauncher.data.BackupManager
import app.olauncher.data.MoodStore
import org.json.JSONObject
import java.time.LocalDate

/**
 * Screen time from the MacBook, written to Google Drive by tools/mac-screen-time (minutes with the
 * keyboard, mouse or trackpad in use, by app) and read here from a folder picked in Drive, or the
 * backup folder if none was. Kept on the phone and filled into the Mac screen time stat, added the
 * first time the file is found.
 */
object DeviceTime {

    class Day(val minutes: Int, val first: String?, val last: String?, val apps: Map<String, Int>)

    fun folder(context: Context): String =
        prefs(context).getString(FOLDER_KEY, null)?.takeIf { it.isNotEmpty() } ?: BackupManager.backupFolder(context)

    fun setFolder(context: Context, treeUri: Uri) {
        prefs(context).edit().putString(FOLDER_KEY, treeUri.toString()).putLong(LAST_KEY, 0).apply()
    }

    fun days(context: Context): Map<LocalDate, Day> = synchronized(LOCK) {
        val json = prefs(context).getString(KEY, null) ?: return emptyMap()
        return runCatching { parse(JSONObject(json)) }.getOrDefault(emptyMap())
    }

    /** When the Mac last wrote its file, or 0 */
    fun updated(context: Context) = prefs(context).getLong(UPDATED_KEY, 0)

    /** Reads the Mac's file, at most every [REFRESH_MS] unless [force]; on a background thread. */
    fun refresh(context: Context, force: Boolean = false) {
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(LAST_KEY, 0) < REFRESH_MS) return
        prefs.edit().putLong(LAST_KEY, now).apply()
        val folder = folder(context).takeIf { it.isNotEmpty() } ?: return
        val text = readFile(context, Uri.parse(folder), MAC_FILE) ?: return
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        val read = runCatching { parse(json) }.getOrNull() ?: return
        synchronized(LOCK) {
            // The Mac keeps 60 days; older days stay as they were last read
            val all = days(context).toMutableMap()
            all.putAll(read)
            val cutoff = LocalDate.now().minusDays(KEEP_DAYS)
            save(context, all.filterKeys { it >= cutoff })
            prefs.edit().putLong(UPDATED_KEY, json.optLong("updated")).apply()
        }
        val id = AutoStats.ensure(context, AutoStats.MAC) { store ->
            store.addStat(NAME, COLOR, daily = false, scale = 5, question = "", labels = LABELS, inverted = false,
                phrases = emptyList(), medicine = false, nextDay = false)
        } ?: return
        val store = MoodStore(context)
        read.forEach { (date, day) -> store.setFace(date, id, faceFor(day.minutes)) }
    }

    /** 8 hours or more is the saddest face, then 5–8, 3–5, 1–3, and under an hour the happiest. */
    fun faceFor(minutes: Int) = when {
        minutes < 60 -> MoodStore.HAPPY
        minutes < 180 -> MoodStore.MILDLY_HAPPY
        minutes < 300 -> MoodStore.NEUTRAL
        minutes < 480 -> MoodStore.MILDLY_SAD
        else -> MoodStore.SAD
    }

    private fun parse(json: JSONObject): Map<LocalDate, Day> {
        val days = json.optJSONObject("days") ?: return emptyMap()
        return days.keys().asSequence().mapNotNull { key ->
            val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@mapNotNull null
            val day = days.getJSONObject(key)
            val apps = day.optJSONObject("apps")?.let { a -> a.keys().asSequence().associateWith { a.getInt(it) } }.orEmpty()
            date to Day(day.getInt("minutes"), day.optString("first").ifEmpty { null }, day.optString("last").ifEmpty { null }, apps)
        }.toMap()
    }

    private fun readFile(context: Context, treeUri: Uri, name: String): String? = runCatching {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val id = context.contentResolver.query(
            children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            var found: String? = null
            while (cursor.moveToNext()) if (cursor.getString(1) == name) found = cursor.getString(0)
            found
        } ?: return null
        val doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
        context.contentResolver.openInputStream(doc)?.use { it.readBytes().decodeToString() }
    }.getOrNull()

    private fun save(context: Context, days: Map<LocalDate, Day>) {
        val json = JSONObject()
        days.forEach { (date, day) ->
            val apps = JSONObject()
            day.apps.forEach { (app, minutes) -> apps.put(app, minutes) }
            json.put(date.toString(), JSONObject().put("minutes", day.minutes).put("first", day.first ?: "")
                .put("last", day.last ?: "").put("apps", apps))
        }
        prefs(context).edit().putString(KEY, JSONObject().put("days", json).toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    const val NAME = "Mac screen time"
    const val MAC_FILE = "JLauncher Mac screen time.json"
    private val COLOR = 0xFF90A4AE.toInt()
    private val LABELS = listOf("8h+", "5–8h", "3–5h", "1–3h", "Under 1h")

    private val LOCK = Any()
    private const val PREFS = "device_time"
    private const val KEY = "mac"
    private const val FOLDER_KEY = "folder"
    private const val LAST_KEY = "refreshed"
    private const val UPDATED_KEY = "updated"
    private const val REFRESH_MS = 15 * 60 * 1000L
    private const val KEEP_DAYS = 400L
}
