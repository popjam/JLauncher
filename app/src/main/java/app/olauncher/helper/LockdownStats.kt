package app.olauncher.helper

import android.content.Context
import app.olauncher.data.MoodStore
import java.time.LocalDate
import kotlin.concurrent.thread

/**
 * The Lockdown stat: blocked attempts (apps and sites) each day, added the first time something is
 * blocked. It runs from good (none) to bad (many).
 */
object LockdownStats {

    const val NAME = "Lockdown"
    private val COLOR = 0xFF8FB3FF.toInt()

    /** Left to right on the inverted row, happy to sad */
    private val LABELS = listOf("None", "1–3", "4–9", "10–19", "20+")

    fun faceFor(blocked: Int) = when {
        blocked <= 0 -> MoodStore.HAPPY
        blocked <= 3 -> MoodStore.MILDLY_HAPPY
        blocked <= 9 -> MoodStore.NEUTRAL
        blocked <= 19 -> MoodStore.MILDLY_SAD
        else -> MoodStore.SAD
    }

    /** Fills the stat in with [days], off the main thread since the stats file is written */
    fun update(context: Context, days: Map<LocalDate, Int>) {
        val app = context.applicationContext
        thread {
            runCatching {
                val id = AutoStats.ensure(app, AutoStats.LOCKDOWN, NAME) { store ->
                    store.addStat(NAME, COLOR, daily = false, scale = 5, question = "", labels = LABELS, inverted = true,
                        phrases = emptyList(), medicine = false, nextDay = false)
                } ?: return@runCatching
                val since = LocalDate.now().minusDays(60)
                MoodStore(app).setFaces(id, days.filterKeys { it > since }.mapValues { faceFor(it.value) })
            }
        }
    }
}
