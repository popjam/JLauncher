package app.olauncher.helper

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.olauncher.data.BackupManager
import app.olauncher.data.MoodStore
import java.time.LocalDate
import java.util.concurrent.Executors

/**
 * A short summary of each long journal note, written by Claude ([AiTidy.summarise]) and kept apart
 * from the note itself. The Journal list shows it in place of the note's start, and the day's page
 * above the note. Made again whenever the note changes (after a spoken note goes in, or the note's
 * page is left having changed it); one for an older version of the note isn't shown. Backed up with
 * the other settings ([NAME] is in the backup's list).
 */
object JournalSummaries {

    const val NAME = "journal_summaries"

    /** Notes at least this many words long get a summary */
    const val WORDS = 250

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Kept as the note's fingerprint, a line break, then the summary */
    private fun fingerprint(note: String) = note.trim().hashCode().toString()

    /** [date]'s summary, if there's one for [note] as it is now */
    fun get(context: Context, date: LocalDate, note: String?): String? {
        if (note.isNullOrBlank()) return null
        val kept = prefs(context).getString(date.toString(), null) ?: return null
        val (print, summary) = kept.split('\n', limit = 2).takeIf { it.size == 2 } ?: return null
        return summary.takeIf { print == fingerprint(note) && it.isNotBlank() }
    }

    /** Told when a day's summary is made, so pages showing it can show the new one */
    private val listeners = mutableListOf<(LocalDate) -> Unit>()

    fun addListener(listener: (LocalDate) -> Unit) { listeners += listener }
    fun removeListener(listener: (LocalDate) -> Unit) { listeners -= listener }

    private val main = Handler(Looper.getMainLooper())

    /** One at a time, off the main thread */
    private val work = Executors.newSingleThreadExecutor()

    /**
     * Makes [date]'s summary soon if its note is long enough, Claude is set up, and there isn't one
     * for the note as it is now. Costs a request, so only when the note has changed.
     */
    fun refreshLater(context: Context, date: LocalDate) {
        val app = context.applicationContext
        if (!AiTidy.isOn(app)) return
        work.execute {
            val note = MoodStore(app).load().days[date.toString()]?.note.orEmpty()
            if (Transcript.wordCount(note) < WORDS || get(app, date, note) != null) return@execute
            val summary = AiTidy.summarise(app, note)?.replace(Regex("\\s+"), " ")?.trim()
                ?.takeIf { Transcript.acceptSummary(it) } ?: return@execute
            prefs(app).edit().putString(date.toString(), fingerprint(note) + "\n" + summary).apply()
            BackupManager.requestSync(app)
            main.post { listeners.toList().forEach { it(date) } }
        }
    }
}
