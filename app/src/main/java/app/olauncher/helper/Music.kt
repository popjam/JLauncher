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

    /**
     * A day's listening: in all, by artist, by track ([trackKey]: time and plays of 30s or more), by
     * hour of the day (24, from midnight) and by the app playing it (package name).
     */
    class Day(
        val ms: Long,
        val artists: Map<String, Long>,
        val tracks: Map<String, Long> = emptyMap(),
        val plays: Map<String, Int> = emptyMap(),
        val hours: List<Long> = emptyList(),
        val apps: Map<String, Long> = emptyMap(),
    )

    /** A track as kept: its title and artist in one key */
    fun trackKey(title: String, artist: String?) = title + TRACK_SEPARATOR + artist.orEmpty()

    /** A [trackKey]'s title and artist (empty if none) */
    fun splitTrack(key: String): Pair<String, String> = key.substringBefore(TRACK_SEPARATOR) to key.substringAfter(TRACK_SEPARATOR, "")

    fun days(context: Context): Map<LocalDate, Day> = synchronized(LOCK) {
        val json = prefs(context).getString(KEY, null) ?: return emptyMap()
        // Read once for each time it's saved: the same string comes back until it changes
        if (json === parsedJson) return parsedDays
        val days = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        parsedJson = json
        parsedDays = days.keys().asSequence().mapNotNull { key ->
            val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@mapNotNull null
            val day = days.getJSONObject(key)
            fun longs(name: String) = day.optJSONObject(name)?.let { a -> a.keys().asSequence().associateWith { a.getLong(it) } }.orEmpty()
            val plays = day.optJSONObject("plays")?.let { a -> a.keys().asSequence().associateWith { a.getInt(it) } }.orEmpty()
            val hours = day.optJSONArray("hours")?.let { a -> (0 until a.length()).map { a.getLong(it) } }.orEmpty()
            date to Day(day.getLong("ms"), longs("artists"), longs("tracks"), plays, hours, longs("apps"))
        }.toMap()
        return parsedDays
    }

    private var parsedJson: String? = null
    private var parsedDays: Map<LocalDate, Day> = emptyMap()

    /**
     * Adds [ms] of listening to [artist] and [title], counted on the day it ended, and updates that day's
     * Music. [play] counts it as one more play of the track; [app] is the package playing it.
     */
    fun add(context: Context, endTime: Long, ms: Long, artist: String?, title: String? = null, app: String? = null, play: Boolean = false) {
        if (ms < MIN_MS) return
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(endTime).atZone(zone).toLocalDate()
        val total = synchronized(LOCK) {
            val all = days(context).toMutableMap()
            val day = all[date]
            val artists = day?.artists.orEmpty().toMutableMap()
            artist?.takeIf { it.isNotBlank() }?.let { artists[it] = (artists[it] ?: 0) + ms }
            val tracks = day?.tracks.orEmpty().toMutableMap()
            val plays = day?.plays.orEmpty().toMutableMap()
            title?.takeIf { it.isNotBlank() }?.let { trackKey(it, artist?.takeIf { a -> a.isNotBlank() }) }?.let { key ->
                tracks[key] = (tracks[key] ?: 0) + ms
                if (play) plays[key] = (plays[key] ?: 0) + 1
            }
            // A day keeps its most played tracks only
            val kept = tracks.entries.sortedByDescending { it.value }.take(MAX_TRACKS).associate { it.key to it.value }
            val apps = day?.apps.orEmpty().toMutableMap()
            app?.let { apps[it] = (apps[it] ?: 0) + ms }
            // Split across the hours it spanned
            val hours = day?.hours?.takeIf { it.size == 24 }?.toMutableList() ?: MutableList(24) { 0L }
            var at = endTime - ms
            while (at < endTime) {
                val time = Instant.ofEpochMilli(at).atZone(zone)
                val nextHour = time.withMinute(0).withSecond(0).withNano(0).plusHours(1).toInstant().toEpochMilli()
                val until = minOf(nextHour, endTime)
                hours[time.hour] += until - at
                at = until
            }
            all[date] = Day((day?.ms ?: 0) + ms, artists, kept, plays.filterKeys { it in kept }, hours, apps)
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
            val tracks = JSONObject().also { o -> day.tracks.forEach { (k, v) -> o.put(k, v) } }
            val plays = JSONObject().also { o -> day.plays.forEach { (k, v) -> o.put(k, v) } }
            val apps = JSONObject().also { o -> day.apps.forEach { (k, v) -> o.put(k, v) } }
            val entry = JSONObject().put("ms", day.ms).put("artists", artists).put("tracks", tracks).put("plays", plays).put("apps", apps)
            if (day.hours.size == 24) entry.put("hours", org.json.JSONArray(day.hours))
            json.put(date.toString(), entry)
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
    private const val MAX_TRACKS = 40
    private const val TRACK_SEPARATOR = "␟"

    /** How long a track plays before it counts as a play */
    const val PLAY_MS = 30_000L
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
        var title: String? = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)

        /** This track's time so far, and whether it's been counted as a play */
        var trackMs = 0L
        var counted = false

        fun flush() {
            val since = playingSince ?: return
            val now = System.currentTimeMillis()
            trackMs += now - since
            val play = !counted && trackMs >= Music.PLAY_MS
            if (play) counted = true
            Music.add(context, now, now - since, artist, title, controller.packageName, play)
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
            val newTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            val newArtist = artistOf(metadata)
            if (newTitle != title || newArtist != artist) {
                trackMs = 0
                counted = false
            }
            artist = newArtist
            title = newTitle
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
        runCatching { Workload.track(context, Workload.MUSIC, block) }.onFailure { it.printStackTrace() }
    }
}
