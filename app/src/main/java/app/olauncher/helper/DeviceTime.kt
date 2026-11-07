package app.olauncher.helper

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.olauncher.R
import app.olauncher.data.BackupManager
import app.olauncher.data.MoodStore
import org.json.JSONObject
import java.time.LocalDate

/**
 * Screen time from other devices, each written to Google Drive as a file of days (minutes, first and
 * last use, minutes by app): the MacBook's by tools/mac-screen-time (minutes with the keyboard, mouse
 * or trackpad in use) and the TV's by tools/tv-time on the PC (minutes with its screen on). Read from
 * a folder picked in Drive, or the backup folder if none was. Each day's minutes go into Screen time,
 * added to the phone's; the days by app are kept here for its page.
 */
object DeviceTime {

    /**
     * Whether the Mac's time in VLC counts as porn time (Libido's card, and an insight). Only ever on
     * where the Mac's time comes in at all, and then until turned off.
     */
    fun pornOn(context: Context): Boolean = context.getSharedPreferences("personal", Context.MODE_PRIVATE)
        .getBoolean("pornFromMac", true) && updated(context, Device.MAC) > 0

    fun setPornOn(context: Context, on: Boolean) =
        context.getSharedPreferences("personal", Context.MODE_PRIVATE).edit().putBoolean("pornFromMac", on).apply()


    /** [key] is the device's in [MoodStore.Day.deviceMs] */
    enum class Device(val file: String, val key: String, val label: Int, val color: Int, val updatedKey: String) {
        MAC("JLauncher Mac screen time.json", "mac", R.string.device_mac, 0xFF90A4AE.toInt(), "updated"),
        TV("JLauncher TV time.json", "tv", R.string.device_tv, 0xFFFF8A65.toInt(), "updatedTv"),
    }

    class Day(val minutes: Int, val first: String?, val last: String?, val apps: Map<String, Int>)

    fun folder(context: Context): String =
        prefs(context).getString(FOLDER_KEY, null)?.takeIf { it.isNotEmpty() } ?: BackupManager.backupFolder(context)

    fun setFolder(context: Context, treeUri: Uri) {
        prefs(context).edit().putString(FOLDER_KEY, treeUri.toString()).putLong(LAST_KEY, 0).apply()
    }

    fun days(context: Context, device: Device): Map<LocalDate, Day> = synchronized(LOCK) {
        val json = prefs(context).getString(device.key, null) ?: return emptyMap()
        return runCatching { parse(JSONObject(json)) }.getOrDefault(emptyMap())
    }

    /** When the device last wrote its file, or 0 */
    fun updated(context: Context, device: Device) = prefs(context).getLong(device.updatedKey, 0)

    /** Reads every device's file, at most every [REFRESH_MS] unless [force]; on a background thread. */
    fun refresh(context: Context, force: Boolean = false) {
        removeOldMacStat(context)
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(LAST_KEY, 0) < REFRESH_MS) return
        prefs.edit().putLong(LAST_KEY, now).apply()
        val folder = folder(context).takeIf { it.isNotEmpty() } ?: return
        Device.values().forEach { device -> runCatching { refresh(context, Uri.parse(folder), device) } }
    }

    private fun refresh(context: Context, folder: Uri, device: Device) {
        val text = readFile(context, folder, device.file) ?: return
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        val read = runCatching { parse(json) }.getOrNull() ?: return
        synchronized(LOCK) {
            // Each device keeps 60 days; older days stay as they were last read
            val all = days(context, device).toMutableMap()
            all.putAll(read)
            val cutoff = LocalDate.now().minusDays(KEEP_DAYS)
            save(context, device, all.filterKeys { it >= cutoff })
            prefs(context).edit().putLong(device.updatedKey, json.optLong("updated")).apply()
        }
        MoodStore(context).setDeviceUsage(device.key, read.mapValues { it.value.minutes * 60_000L })
    }

    /** Mac screen time was briefly a stat of its own; it's part of Screen time now. */
    private fun removeOldMacStat(context: Context) {
        val store = MoodStore(context)
        AutoStats.id(context, AutoStats.MAC, store.load())?.let { store.deleteStat(it) }
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

    /** A file's text from the picked folder, by its name */
    fun readFile(context: Context, treeUri: Uri, name: String): String? = runCatching {
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

    private fun save(context: Context, device: Device, days: Map<LocalDate, Day>) {
        val json = JSONObject()
        days.forEach { (date, day) ->
            val apps = JSONObject()
            day.apps.forEach { (app, minutes) -> apps.put(app, minutes) }
            json.put(date.toString(), JSONObject().put("minutes", day.minutes).put("first", day.first ?: "")
                .put("last", day.last ?: "").put("apps", apps))
        }
        prefs(context).edit().putString(device.key, JSONObject().put("days", json).toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val LOCK = Any()
    private const val PREFS = "device_time"
    private const val FOLDER_KEY = "folder"
    private const val LAST_KEY = "refreshed"
    private const val REFRESH_MS = 5 * 60 * 1000L
    private const val KEEP_DAYS = 400L
}
