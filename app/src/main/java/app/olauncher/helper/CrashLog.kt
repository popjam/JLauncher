package app.olauncher.helper

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Keeps the last error, so a crash on the phone can be read back from Settings › About without a
 * cable. A crash ([install]) is written before the app goes down; errors caught in the accessibility
 * service ([guard]) are written and the service carries on, so the launcher doesn't go down with it.
 */
object CrashLog {

    private const val FILE = "last_crash.txt"
    private var installed = false
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(error, fatal = true)
            previous?.uncaughtException(thread, error)
        }
    }

    /** Runs [block], keeping any error instead of letting it take the app down */
    inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            record(e, fatal = false)
        }
    }

    fun record(error: Throwable, fatal: Boolean) {
        val context = appContext ?: return
        runCatching {
            val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            val text = "${java.time.LocalDateTime.now().withNano(0)} · ${if (fatal) "crash" else "caught"} · " +
                "${app.olauncher.BuildConfig.VERSION_NAME}\n$trace"
            File(context.filesDir, FILE).writeText(text)
        }
    }

    /** The last error kept, or null */
    fun last(context: Context): String? = runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }
}
