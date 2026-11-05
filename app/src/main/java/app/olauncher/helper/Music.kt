package app.olauncher.helper

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import app.olauncher.data.MoodStore
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Time spent listening each day (anything playing through a media session: music apps, podcasts,
 * YouTube), with time by artist, kept on the phone and filled into the Music stat. Read by [MusicTracker]
 * while notification access is on.
 */
object Music {

    class Day(val ms: Long, val artists: Map<String, Long>)

    fun days(context: Context): Map<LocalDate, Day> = synchronized(LOCK) {
        val json = prefs(context).getString(KEY, null) ?: return emptyMap()
        val days = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        return days.keys().asSequence().mapNotNull { key ->
            val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@mapNotNull null
            val day = days.getJSONObject(key)
            val artists = day.optJSONObject("artists")?.let { a -> a.keys().asSequence().associateWith { a.getLong(it) } }.orEmpty()
            date to Day(day.getLong("ms"), artists)
        }.toMap()
    }

    /** Adds [ms] of listening to [artist], counted on the day it ended, and updates that day's Music. */
    fun add(context: Context, endTime: Long, ms: Long, artist: String?) {
        if (ms < MIN_MS) return
        val date = Instant.ofEpochMilli(endTime).atZone(ZoneId.systemDefault()).toLocalDate()
        val total = synchronized(LOCK) {
            val all = days(context).toMutableMap()
            val day = all[date]
            val artists = day?.artists.orEmpty().toMutableMap()
            artist?.takeIf { it.isNotBlank() }?.let { artists[it] = (artists[it] ?: 0) + ms }
            all[date] = Day((day?.ms ?: 0) + ms, artists)
            val cutoff = LocalDate.now().minusDays(KEEP_DAYS)
            save(context, all.filterKeys { it >= cutoff })
            all.getValue(date).ms
        }
        setFace(context, date, total)
    }

    /**
     * Days gone by with nothing played, since the listener first ran, are filled in as none, so they
     * count as days without music rather than days not logged.
     */
    fun fillQuietDays(context: Context) {
        val since = prefs(context).getString(SINCE_KEY, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
        val store = MoodStore(context)
        val data = store.load()
        val id = AutoStats.id(context, AutoStats.MUSIC, data) ?: return
        val stat = data.stat(id) ?: return
        val played = days(context)
        val today = LocalDate.now()
        for (daysAgo in 1..QUIET_DAYS_BACK) {
            val date = today.minusDays(daysAgo.toLong())
            // The first day may have had music before the listener started
            if (date <= since) break
            if (played.containsKey(date) || data.days[date.toString()]?.values?.containsKey(id) == true) continue
            store.setFace(date, id, rowOf(stat).first())
        }
    }

    /** When the listener first ran, so earlier days aren't marked as none */
    fun markStarted(context: Context) {
        val prefs = prefs(context)
        if (!prefs.contains(SINCE_KEY)) prefs.edit().putString(SINCE_KEY, LocalDate.now().toString()).apply()
    }

    private fun setFace(context: Context, date: LocalDate, ms: Long) {
        val id = AutoStats.ensure(context, AutoStats.MUSIC, NAME) { store ->
            store.addStat(NAME, COLOR, daily = false, scale = 5, question = "", labels = LABELS, inverted = false,
                phrases = emptyList(), medicine = true, nextDay = false)
        } ?: return
        val store = MoodStore(context)
        val stat = store.load().stat(id) ?: return
        val minutes = ms / 60_000
        val place = when {
            minutes < 1 -> 0
            minutes < 30 -> 1
            minutes < 60 -> 2
            minutes < 120 -> 3
            else -> 4
        }
        store.setFace(date, id, rowOf(stat)[place.coerceAtMost(stat.scale - 1)])
    }

    /** The faces left to right, so place 0 is the medicine-style blank for none. */
    private fun rowOf(stat: MoodStore.Stat) = MoodStore.facesFor(stat.scale).let { if (stat.inverted) it.reversed() else it }

    private fun save(context: Context, days: Map<LocalDate, Day>) {
        val json = JSONObject()
        days.forEach { (date, day) ->
            val artists = JSONObject()
            day.artists.forEach { (name, ms) -> artists.put(name, ms) }
            json.put(date.toString(), JSONObject().put("ms", day.ms).put("artists", artists))
        }
        prefs(context).edit().putString(KEY, json.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    const val NAME = "Music"
    private val COLOR = 0xFF4DD0E1.toInt()
    private val LABELS = listOf("None", "Under 30m", "30m–1h", "1–2h", "2h+")

    private val LOCK = Any()
    private const val PREFS = "music"
    private const val KEY = "days"
    private const val SINCE_KEY = "since"
    private const val KEEP_DAYS = 400L
    private const val QUIET_DAYS_BACK = 7
    private const val MIN_MS = 5_000L
}

/**
 * Follows every media session while [listener] is connected, adding up the time each spends playing
 * and handing it to [Music] whenever playback pauses, stops or moves to another track.
 */
class MusicTracker(private val context: Context, private val listener: ComponentName) {

    private val sessions = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    private val tracked = mutableMapOf<android.media.session.MediaSession.Token, Tracked>()
    private val onChanged = MediaSessionManager.OnActiveSessionsChangedListener { follow(it.orEmpty()) }

    private inner class Tracked(val controller: MediaController) : MediaController.Callback() {
        var playingSince: Long? = null
        var artist: String? = artistOf(controller.metadata)

        fun flush() {
            val since = playingSince ?: return
            val now = System.currentTimeMillis()
            Music.add(context, now, now - since, artist)
            playingSince = now
        }

        fun update(state: PlaybackState?) {
            val playing = state?.state == PlaybackState.STATE_PLAYING
            if (playing && playingSince == null) playingSince = System.currentTimeMillis()
            if (!playing && playingSince != null) {
                flush()
                playingSince = null
            }
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) = runSafely { update(state) }

        override fun onMetadataChanged(metadata: MediaMetadata?) = runSafely {
            // The time so far goes to the track that was playing
            flush()
            artist = artistOf(metadata)
        }

        override fun onSessionDestroyed() = runSafely {
            flush()
            playingSince = null
            tracked.remove(controller.sessionToken)
        }
    }

    fun start() {
        val sessions = sessions ?: return
        runSafely {
            Music.markStarted(context)
            sessions.addOnActiveSessionsChangedListener(onChanged, listener)
            follow(sessions.getActiveSessions(listener))
        }
    }

    fun stop() {
        runSafely {
            sessions?.removeOnActiveSessionsChangedListener(onChanged)
            tracked.values.forEach {
                it.flush()
                it.controller.unregisterCallback(it)
            }
            tracked.clear()
        }
    }

    private fun follow(controllers: List<MediaController>) {
        controllers.forEach { controller ->
            if (tracked.containsKey(controller.sessionToken)) return@forEach
            val session = Tracked(controller)
            tracked[controller.sessionToken] = session
            controller.registerCallback(session)
            session.update(controller.playbackState)
        }
    }

    private fun artistOf(metadata: MediaMetadata?): String? =
        metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)

    private inline fun runSafely(block: () -> Unit) {
        runCatching(block).onFailure { it.printStackTrace() }
    }
}
