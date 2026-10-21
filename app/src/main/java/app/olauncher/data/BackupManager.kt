package app.olauncher.data

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Full app backup: every launcher setting (home apps, gestures, appearance, lockdown, ...) plus the
 * mood tracker data, in one JSON file.
 *
 * Settings are copied generically from SharedPreferences, so new settings are included without
 * changes here. Keys that only describe this device or session are listed in [EXCLUDED_KEYS].
 */
object BackupManager {

    const val BACKUP_FILE_NAME = "jlauncher_backup.json"
    private const val LEGACY_BACKUP_FILE_NAME = "jlauncher_mood.json"
    private const val VERSION = 2
    private const val SYNC_DELAY_MS = 2_000L

    private val EXCLUDED_KEYS = setOf(
        "FIRST_OPEN",                   // first-run setup state
        "HOME_PAGE",                    // page currently shown on the home screen
        "LOCK_MODE",                    // depends on accessibility/admin being enabled on this phone
        "SCREEN_TIME_LAST_UPDATED",
        "USAGE_HISTORY_LAST_UPDATED",
        "LAUNCHER_RECREATE_TIMESTAMP",
    )

    private val io = Executors.newSingleThreadScheduledExecutor()
    private var pendingSync: ScheduledFuture<*>? = null
    @Volatile
    private var lastSyncedJson: String? = null
    private var settingsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    // --- Building and restoring ---

    fun buildBackup(context: Context): JSONObject = JSONObject().apply {
        put("app", "JLauncher")
        put("version", VERSION)
        put("settings", settingsToJson(prefs(context)))
        put("mood", MoodStore(context).toBackupJson())
    }

    /**
     * Restores a backup. Settings in the backup replace local ones (settings it doesn't contain are
     * left alone), and mood data is merged. Accepts older mood-only exports too.
     * Returns the number of mood days in the backup.
     */
    fun restore(context: Context, backup: JSONObject): Int {
        backup.optJSONObject("settings")?.let { restoreSettings(prefs(context), it) }
        val mood = backup.optJSONObject("mood") ?: backup.takeIf { it.has("days") }
        return mood?.let { MoodStore(context).restoreFrom(it) } ?: 0
    }

    fun read(context: Context, uri: Uri): JSONObject? = try {
        context.contentResolver.openInputStream(uri)?.use { JSONObject(it.readBytes().decodeToString()) }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    fun export(context: Context, uri: Uri): Boolean = try {
        val json = buildBackup(context).toString(2)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) }
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    private fun settingsToJson(prefs: SharedPreferences) = JSONObject().apply {
        prefs.all.toSortedMap().forEach { (key, value) ->
            if (key in EXCLUDED_KEYS || value == null) return@forEach
            val entry = when (value) {
                is Boolean -> typed("boolean", value)
                is Int -> typed("int", value)
                is Long -> typed("long", value)
                is Float -> typed("float", value.toDouble())
                is String -> typed("string", value)
                is Set<*> -> typed("stringSet", JSONArray(value.filterIsInstance<String>().sorted()))
                else -> return@forEach
            }
            put(key, entry)
        }
    }

    private fun typed(type: String, value: Any) = JSONObject().put("type", type).put("value", value)

    private fun restoreSettings(prefs: SharedPreferences, settings: JSONObject) {
        val editor = prefs.edit()
        settings.keys().forEach { key ->
            if (key in EXCLUDED_KEYS) return@forEach
            val entry = settings.optJSONObject(key) ?: return@forEach
            try {
                when (entry.getString("type")) {
                    "boolean" -> editor.putBoolean(key, entry.getBoolean("value"))
                    "int" -> editor.putInt(key, entry.getInt("value"))
                    "long" -> editor.putLong(key, entry.getLong("value"))
                    "float" -> editor.putFloat(key, entry.getDouble("value").toFloat())
                    "string" -> editor.putString(key, entry.getString("value"))
                    "stringSet" -> {
                        val array = entry.getJSONArray("value")
                        editor.putStringSet(key, (0 until array.length()).map { array.getString(it) }.toSet())
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        // Synchronous, so the activity can be recreated with the restored values straight after
        editor.commit()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(Prefs.FILE_NAME, 0)

    // --- Backup folder ---

    fun backupFolder(context: Context): String = MoodStore(context).load().backupFolder

    fun setBackupFolder(context: Context, treeUri: Uri?) {
        MoodStore(context).update { it.backupFolder = treeUri?.toString().orEmpty() }
        lastSyncedJson = null
        requestSync(context)
    }

    /** The backup file in a picked folder, if there is one (including the older mood-only name). */
    fun findBackupInFolder(context: Context, treeUri: Uri): Uri? {
        val files = listFolder(context, treeUri)
        return (files[BACKUP_FILE_NAME] ?: files[LEGACY_BACKUP_FILE_NAME])
            ?.let { DocumentsContract.buildDocumentUriUsingTree(treeUri, it) }
    }

    /** Display name -> document id. Document ids aren't names on every provider (e.g. Drive). */
    private fun listFolder(context: Context, treeUri: Uri): Map<String, String> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        return try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                val files = mutableMapOf<String, String>()
                while (cursor.moveToNext()) files[cursor.getString(1)] = cursor.getString(0)
                files
            } ?: emptyMap()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyMap()
        }
    }

    /** Writes the backup to the linked folder shortly after changes. No-op outside the main process. */
    fun requestSync(context: Context) {
        val appContext = context.applicationContext
        if (!isMainProcess(appContext)) return
        synchronized(this) {
            pendingSync?.cancel(false)
            pendingSync = io.schedule({ syncNow(appContext) }, SYNC_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun syncNow(context: Context) {
        val folder = backupFolder(context)
        if (folder.isEmpty()) return
        try {
            val json = buildBackup(context).toString(2)
            if (json == lastSyncedJson) return
            val treeUri = Uri.parse(folder)
            val doc = listFolder(context, treeUri)[BACKUP_FILE_NAME]
                ?.let { DocumentsContract.buildDocumentUriUsingTree(treeUri, it) }
                ?: DocumentsContract.createDocument(
                    context.contentResolver,
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)),
                    "application/json",
                    BACKUP_FILE_NAME
                ) ?: return
            context.contentResolver.openOutputStream(doc, "wt")?.use { it.write(json.toByteArray()) }
            lastSyncedJson = json
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Keeps the backup folder up to date whenever a setting changes. Call once from the launcher. */
    fun watchSettings(context: Context) {
        if (settingsListener != null) return
        val appContext = context.applicationContext
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key !in EXCLUDED_KEYS) requestSync(appContext)
        }
        settingsListener = listener
        prefs(appContext).registerOnSharedPreferenceChangeListener(listener)
    }

    private fun isMainProcess(context: Context): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Application.getProcessName()
        else try {
            File("/proc/self/cmdline").readText().trim('\u0000', ' ')
        } catch (e: Exception) {
            context.packageName
        }
        return name == context.packageName
    }
}
