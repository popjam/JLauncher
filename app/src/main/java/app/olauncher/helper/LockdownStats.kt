package app.olauncher.helper

import app.olauncher.ui.Style
import android.content.Context
import android.os.SystemClock
import app.olauncher.data.MoodStore
import java.time.LocalDate
import kotlin.concurrent.thread

/**
 * The Lockdown stat: how long the phone spent in lockdown each day, from [LockdownLog]. It runs from
 * good (a long lockdown) to bad (none).
 */
object LockdownStats {

    const val NAME = "Lockdown"
    private val COLOR = Style.LOCKDOWN_ON_DARK

    /** Left to right on the inverted row, happy to sad */
    private val LABELS = listOf("8h+", "4–8h", "2–4h", "Under 2h", "None")

    fun faceFor(lockedMs: Long): Int {
        val hours = lockedMs / 3_600_000.0
        return when {
            hours >= 8 -> MoodStore.HAPPY
            hours >= 4 -> MoodStore.MILDLY_HAPPY
            hours >= 2 -> MoodStore.NEUTRAL
            lockedMs >= 60_000L -> MoodStore.MILDLY_SAD
            else -> MoodStore.SAD
        }
    }

    private var lastUpdate = 0L

    /** Fills the stat in with [days], at most every 10 minutes, off the main thread since the stats file is written */
    fun update(context: Context, days: Map<LocalDate, LockdownLog.Day>, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && lastUpdate != 0L && now - lastUpdate < 10 * 60_000L) return
        lastUpdate = now
        val app = context.applicationContext
        thread {
            runCatching {
                val id = AutoStats.ensure(app, AutoStats.LOCKDOWN, NAME) { store ->
                    store.addStat(NAME, COLOR, daily = false, scale = 5, question = "", labels = LABELS, inverted = true,
                        phrases = emptyList(), medicine = false, nextDay = false)
                } ?: return@runCatching
                val store = MoodStore(app)
                // It counted blocked attempts before; its labels now say hours
                store.load().stat(id)?.takeIf { it.labels != LABELS }?.let {
                    store.editStat(id, it.name, it.color, it.daily, it.question, LABELS, it.inverted, it.phrases, it.medicine, it.nextDay)
                }
                val since = LocalDate.now().minusDays(60)
                store.setFaces(id, days.filterKeys { it > since }.mapValues { faceFor(it.value.phoneMs) })
            }
        }
    }
}
