package app.olauncher.helper

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.DocumentsContract
import android.provider.Settings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Updating JLauncher from the build on Google Drive (JLauncher-debug.apk, in the folder picked for
 * other devices' screen time, where new builds are put). The build there is newer when it was changed
 * after this one was installed; nothing is downloaded to tell.
 *
 * Drive's app answers from what it last saw and looks again in the background, saying so; then the
 * check waits for the fresh answer, so a build put there lately isn't missed.
 */
object AppUpdate {

    const val FILE = "JLauncher-debug.apk"

    /** A minute's leeway, so the build just installed never counts as newer than itself */
    private const val LEEWAY_MS = 60_000L

    /** How long to wait for Drive to look again */
    private const val FRESH_WAIT_MS = 8_000L

    sealed class State {
        object NoFolder : State()
        object NotFound : State()
        /** No newer build; [modified] is when the build on Drive was changed */
        class Latest(val modified: Long) : State()
        /** A newer build: the file, and when it was changed on Drive */
        class Newer(val uri: Uri, val modified: Long) : State()
    }

    /** Whether the build on Drive changed after [installed], both on the wall clock */
    fun isNewer(modified: Long, installed: Long) = modified > installed + LEEWAY_MS

    /** Looks at the build on Drive, waiting for Drive's fresh answer when it's still looking; off the main thread */
    fun check(context: Context): State {
        val tree = DeviceTime.folder(context).takeIf { it.isNotEmpty() }?.let(Uri::parse) ?: return State.NoFolder
        val found = runCatching { find(context, tree) }.getOrNull() ?: return State.NotFound
        val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        return if (isNewer(found.second, installed)) State.Newer(DocumentsContract.buildDocumentUriUsingTree(tree, found.first), found.second)
            else State.Latest(found.second)
    }

    /** The build's document id and when it was changed, from the folder's listing */
    private fun find(context: Context, tree: Uri): Pair<String, Long>? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val first = context.contentResolver.query(children, columns, null, null, null) ?: return null
        val stillLooking = first.extras?.getBoolean(DocumentsContract.EXTRA_LOADING, false) == true
        if (!stillLooking) return first.use { read(it) }
        // Drive says it's looking again: wait for it to say it's done (or a few seconds), then ask again
        val done = CountDownLatch(1)
        val thread = HandlerThread("drive-wait").apply { start() }
        val observer = object : ContentObserver(Handler(thread.looper)) {
            override fun onChange(selfChange: Boolean) = done.countDown()
        }
        val cached = try {
            first.registerContentObserver(observer)
            val now = read(first)
            done.await(FRESH_WAIT_MS, TimeUnit.MILLISECONDS)
            now
        } finally {
            runCatching { first.unregisterContentObserver(observer) }
            first.close()
            thread.quitSafely()
        }
        return context.contentResolver.query(children, columns, null, null, null)?.use { read(it) } ?: cached
    }

    private fun read(cursor: Cursor): Pair<String, Long>? {
        var match: Pair<String, Long>? = null
        cursor.moveToPosition(-1)
        while (cursor.moveToNext()) if (cursor.getString(1) == FILE) match = cursor.getString(0) to (if (cursor.isNull(2)) 0L else cursor.getLong(2))
        return match
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
