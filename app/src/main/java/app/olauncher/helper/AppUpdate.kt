package app.olauncher.helper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings

/**
 * Updating JLauncher from the build on Google Drive (JLauncher-debug.apk, in the folder picked for
 * other devices' screen time, where new builds are put). The build there is newer when it was changed
 * after this one was installed; nothing is downloaded to tell.
 */
object AppUpdate {

    const val FILE = "JLauncher-debug.apk"

    /** A minute's leeway, so the build just installed never counts as newer than itself */
    private const val LEEWAY_MS = 60_000L

    sealed class State {
        object NoFolder : State()
        object NotFound : State()
        object Latest : State()
        /** A newer build: the file, and when it was changed on Drive */
        class Newer(val uri: Uri, val modified: Long) : State()
    }

    /** Whether the build on Drive changed after [installed], both on the wall clock */
    fun isNewer(modified: Long, installed: Long) = modified > installed + LEEWAY_MS

    /** Looks at the build on Drive; off the main thread */
    fun check(context: Context): State {
        val tree = DeviceTime.folder(context).takeIf { it.isNotEmpty() }?.let(Uri::parse) ?: return State.NoFolder
        val found = runCatching {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            context.contentResolver.query(children, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ), null, null, null)?.use { cursor ->
                var match: Pair<String, Long>? = null
                while (cursor.moveToNext()) if (cursor.getString(1) == FILE) match = cursor.getString(0) to (if (cursor.isNull(2)) 0L else cursor.getLong(2))
                match
            }
        }.getOrNull() ?: return State.NotFound
        val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        return if (isNewer(found.second, installed)) State.Newer(DocumentsContract.buildDocumentUriUsingTree(tree, found.first), found.second)
            else State.Latest
    }

    /**
     * Opens the phone's installer on [uri]. The first time, the phone asks for JLauncher to be allowed
     * to install apps; returns false when it sent you there instead.
     */
    fun install(context: Context, uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return false
        }
        context.startActivity(Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
