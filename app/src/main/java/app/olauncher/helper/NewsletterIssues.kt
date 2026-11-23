package app.olauncher.helper

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * The newsletter's issues on the phone: copied from the Newsletter folder the PC fills on Google Drive
 * (inside the JLauncher folder picked for other devices' screen time) to the app's cache, where the
 * reader opens them. The last few are kept.
 */
object NewsletterIssues {

    private const val FOLDER = "Newsletter"
    private const val KEEP = 3

    /** Whether there's a Drive folder picked to look in */
    fun hasFolder(context: Context) = DeviceTime.folder(context).isNotEmpty()

    fun cached(context: Context, file: String) = File(dir(context), file)

    private fun dir(context: Context) = File(context.cacheDir, "newsletter").apply { mkdirs() }

    /**
     * Copies [file] from Drive to the cache, telling [progress] the bytes done and the size (0 when it
     * isn't known). False when it isn't on Drive, or not yet on the phone's side.
     */
    fun fetch(context: Context, file: String, progress: (Long, Long) -> Unit): Boolean {
        val tree = DeviceTime.folder(context).takeIf { it.isNotEmpty() }?.let(Uri::parse) ?: return false
        val resolver = context.contentResolver
        val folder = child(context, tree, DocumentsContract.getTreeDocumentId(tree), FOLDER) ?: return false
        val (docId, size) = child(context, tree, folder.first, file) ?: return false
        val target = cached(context, file)
        val part = File(target.path + ".part")
        val copied = runCatching {
            resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, docId))?.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        progress(done, size)
                    }
                }
                true
            } ?: false
        }.getOrDefault(false)
        if (!copied || !part.renameTo(target)) {
            part.delete()
            return false
        }
        trim(context, keep = file)
        return true
    }

    /** A file's document id and size, by name, among a folder's children */
    private fun child(context: Context, tree: Uri, parentId: String, name: String): Pair<String, Long>? = runCatching {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        context.contentResolver.query(children, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
        ), null, null, null)?.use { cursor ->
            var found: Pair<String, Long>? = null
            while (cursor.moveToNext()) if (cursor.getString(1) == name) found = cursor.getString(0) to (if (cursor.isNull(2)) 0L else cursor.getLong(2))
            found
        }
    }.getOrNull()

    /** Removes all but the last few issues, never [keep] */
    private fun trim(context: Context, keep: String) {
        dir(context).listFiles { f -> f.name.endsWith(".epub") }?.sortedByDescending { it.lastModified() }
            ?.filter { it.name != keep }?.drop(KEEP - 1)?.forEach { it.delete() }
    }
}
