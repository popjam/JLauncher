package app.olauncher.helper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.olauncher.data.MoodStore
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Time out of the house, from Wi-Fi: on one of up to two homes' Wi-Fi is home, off it is out. Each
 * change is kept on the phone, and each day's time out fills in the Social stat. Android only gives
 * the Wi-Fi's name with location access ("all the time", to read it in the background); without it
 * nothing is counted. Dropping off Wi-Fi for under [BLIP_MS] doesn't count as going out.
 */
object Social {

    const val OUT = -1
    const val UNKNOWN = -2

    /** Work's place, after the homes' slots */
    const val WORK = 2

    class Home(val ssid: String, val name: String)

    /** A stretch of a day in one place: [OUT], [UNKNOWN] or a home's slot */
    class Stretch(val start: Long, val end: Long, val place: Int) {
        val ms get() = end - start
        val startTime: LocalDateTime get() = LocalDateTime.ofInstant(Instant.ofEpochMilli(start), ZoneId.systemDefault())
        val endTime: LocalDateTime get() = LocalDateTime.ofInstant(Instant.ofEpochMilli(end), ZoneId.systemDefault())
    }

    class Day(val stretches: List<Stretch>) {
        /** Out of the house: anywhere but a home, work included */
        val outMs = stretches.filter { it.place == OUT || it.place == WORK }.sumOf { it.ms }
        val workMs get() = homeMs(WORK)
        val trackedMs = stretches.filter { it.place != UNKNOWN }.sumOf { it.ms }
        fun homeMs(slot: Int) = stretches.filter { it.place == slot }.sumOf { it.ms }

        /** The home most of the day was spent at, if any */
        val mainHome: Int? get() = (0 until HOMES).filter { homeMs(it) > 0 }.maxByOrNull { homeMs(it) }

        /** Each time out of [LEAVE_MS] or more */
        val outings get() = stretches.filter { it.place == OUT && it.ms >= LEAVE_MS }

        /** Under an hour out */
        val stayedIn get() = outMs < STAYED_IN_MS
    }

    // --- Homes ---

    fun homes(context: Context): List<Home?> = (0 until HOMES).map { slot ->
        val prefs = prefs(context)
        prefs.getString("home${slot}Ssid", null)?.let { Home(it, prefs.getString("home${slot}Name", null) ?: it) }
    }

    fun setHome(context: Context, slot: Int, home: Home?) {
        prefs(context).edit().apply {
            if (home == null) {
                remove("home${slot}Ssid")
                remove("home${slot}Name")
            } else {
                putString("home${slot}Ssid", home.ssid)
                putString("home${slot}Name", home.name)
            }
        }.apply()
        check(context)
    }

    /** Work's Wi-Fi, set like a home's */
    fun work(context: Context): Home? = prefs(context).getString("workSsid", null)?.let { Home(it, prefs(context).getString("workName", null) ?: it) }

    fun setWork(context: Context, home: Home?) {
        prefs(context).edit().apply {
            if (home == null) {
                remove("workSsid")
                remove("workName")
            } else {
                putString("workSsid", home.ssid)
                putString("workName", home.name)
                // Days before it was set aren't counted as off
                if (!prefs(context).contains(WORK_SINCE)) putString(WORK_SINCE, LocalDate.now().toString())
            }
        }.apply()
        check(context)
    }

    /** Whether anywhere is set, so there's something to track */
    private fun anyPlace(context: Context) = homes(context).any { it != null } || work(context) != null

    /** When tracking began: the first change kept */
    fun firstChange(context: Context): Long? = changes(context).firstOrNull()?.first

    fun hasLocation(context: Context) = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasBackgroundLocation(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** The Wi-Fi connected to now, without quotes; null when not on Wi-Fi or its name is hidden. */
    @Suppress("DEPRECATION")
    fun currentSsid(context: Context): String? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        return cleanSsid(wifi.connectionInfo?.ssid)
    }

    private fun cleanSsid(ssid: String?) = ssid?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID && it != "<unknown ssid>" }

    // --- Changes ---

    /** Where the phone is now, from its Wi-Fi: [ssid] if known, or whether it's on Wi-Fi at all. */
    fun record(context: Context, onWifi: Boolean, ssid: String?) {
        val homes = homes(context)
        if (!anyPlace(context) || !hasLocation(context)) return
        val place = when {
            !onWifi -> OUT
            ssid == null -> lastPlace(context).takeIf { it >= 0 } ?: UNKNOWN
            ssid == work(context)?.ssid -> WORK
            else -> homes.indexOfFirst { it?.ssid == ssid }.takeIf { it >= 0 } ?: OUT
        }
        add(context, place)
        markAlive(context)
    }

    /**
     * After the phone starts up: if it was off since it was last seen running, that time is marked
     * unknown, from when it was last seen, so it doesn't count as wherever it was before.
     */
    fun afterStart(context: Context) = synchronized(LOCK) {
        val bootedAt = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
        val alive = prefs(context).getLong(ALIVE, 0L)
        val changes = changes(context)
        val last = changes.lastOrNull() ?: return
        if (alive in last.first until bootedAt && last.second != UNKNOWN) save(context, changes + (alive to UNKNOWN))
    }

    /** Notes the phone is running now, for [afterStart] */
    private fun markAlive(context: Context) = prefs(context).edit().putLong(ALIVE, System.currentTimeMillis()).apply()

    /** Checked from the foreground too, in case the background watcher missed a change. */
    fun check(context: Context) {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val onWifi = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        record(context, onWifi, if (onWifi) currentSsid(context) else null)
    }

    /** Tracking stopped (the watcher let go), so the time after isn't counted as anywhere. */
    fun stopped(context: Context) {
        if (anyPlace(context)) add(context, UNKNOWN)
    }

    private fun add(context: Context, place: Int) = synchronized(LOCK) {
        val changes = changes(context).toMutableList()
        if (changes.lastOrNull()?.second == place) return
        val now = System.currentTimeMillis()
        changes.add(now to place)
        val cutoff = now - KEEP_DAYS * DAY_MS
        // Keeps the last change before the cutoff, so the first day kept still knows where it started
        val firstKept = changes.indexOfFirst { it.first >= cutoff }.let { if (it > 0) it - 1 else 0 }
        save(context, changes.drop(firstKept))
    }

    private fun lastPlace(context: Context) = changes(context).lastOrNull()?.second ?: UNKNOWN

    private fun changes(context: Context): List<Pair<Long, Int>> = synchronized(LOCK) {
        val json = prefs(context).getString(KEY, null) ?: return emptyList()
        // Read once for each list saved: the same string comes back until it changes
        if (json === parsedJson) return parsedChanges
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return List(array.length()) { array.getJSONArray(it).let { c -> c.getLong(0) to c.getInt(1) } }.also {
            parsedJson = json
            parsedChanges = it
        }
    }

    private var parsedJson: String? = null
    private var parsedChanges: List<Pair<Long, Int>> = emptyList()
    private var daysKey: Pair<List<Pair<Long, Int>>, Long>? = null
    private var daysCache: Map<LocalDate, Day> = emptyMap()

    private fun save(context: Context, changes: List<Pair<Long, Int>>) {
        val array = JSONArray()
        changes.forEach { (time, place) -> array.put(JSONArray().put(time).put(place)) }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    // --- Days ---

    /** Every day with something tracked, from the first change to today */
    fun days(context: Context): Map<LocalDate, Day> {
        val changes = changes(context)
        if (changes.isEmpty()) return emptyMap()
        // Worked out again when the changes do, or a minute on (today grows as it goes)
        val key = changes to System.currentTimeMillis() / 60_000
        synchronized(LOCK) { if (daysKey?.let { it.first === key.first && it.second == key.second } == true) return daysCache }
        return computeDays(changes).also {
            synchronized(LOCK) {
                daysKey = key
                daysCache = it
            }
        }
    }

    private fun computeDays(changes: List<Pair<Long, Int>>): Map<LocalDate, Day> {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val first = Instant.ofEpochMilli(changes.first().first).atZone(zone).toLocalDate()
        val days = mutableMapOf<LocalDate, Day>()
        var date = first
        val today = LocalDate.now()
        while (date <= today) {
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = minOf(now, date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
            val stretches = changes.mapIndexedNotNull { i, (time, place) ->
                val from = maxOf(time, start)
                val to = minOf(changes.getOrNull(i + 1)?.first ?: now, end)
                if (to > from) Stretch(from, to, place) else null
            }
            val day = Day(merge(stretches))
            if (day.trackedMs > 0) days[date] = day
            date = date.plusDays(1)
        }
        return days
    }

    /** Short drops off a home's Wi-Fi go back to that home, and stretches in the same place join up. */
    private fun merge(stretches: List<Stretch>): List<Stretch> {
        val fixed = stretches.mapIndexed { i, stretch ->
            val before = stretches.getOrNull(i - 1)?.place
            val after = stretches.getOrNull(i + 1)?.place
            if (stretch.place == OUT && stretch.ms < BLIP_MS && before != null && before >= 0 && before == after) Stretch(stretch.start, stretch.end, before)
            else stretch
        }
        val joined = mutableListOf<Stretch>()
        fixed.forEach { stretch ->
            val last = joined.lastOrNull()
            if (last != null && last.place == stretch.place && last.end == stretch.start) joined[joined.lastIndex] = Stretch(last.start, stretch.end, last.place)
            else joined.add(stretch)
        }
        return joined
    }

    /**
     * Adds the Social stat the first time, and fills in the last [DAYS_BACK] days' time out: today as it
     * goes, earlier days once at least [MIN_TRACKED_MS] of them was tracked.
     */
    fun refresh(context: Context) {
        val id = AutoStats.ensure(context, AutoStats.SOCIAL, NAME) { store ->
            store.addStat(NAME, COLOR, daily = false, scale = 5, question = "", labels = LABELS, inverted = false,
                phrases = emptyList(), medicine = false, nextDay = false)
        } ?: return
        check(context)
        val store = MoodStore(context)
        val today = LocalDate.now()
        days(context).filterKeys { it > today.minusDays(DAYS_BACK) }.forEach { (date, day) ->
            if (date == today || day.trackedMs >= MIN_TRACKED_MS) store.setFace(date, id, faceFor(day.outMs))
        }
        runCatching { refreshWork(context) }.onFailure { it.printStackTrace() }
    }

    /**
     * Once work's Wi-Fi is set: adds the Work stat, and marks the last [WORK_DAYS_BACK] days worked (an hour
     * or more there) or off (a day gone by, mostly tracked, without). Days before work was set are left.
     */
    private fun refreshWork(context: Context) {
        work(context) ?: return
        val id = AutoStats.ensure(context, AutoStats.WORK, WORK_NAME) { store ->
            store.addStat(WORK_NAME, WORK_COLOR, daily = false, scale = 2, question = "", labels = WORK_LABELS, inverted = false,
                phrases = emptyList(), medicine = true, nextDay = false)
        } ?: return
        val since = prefs(context).getString(WORK_SINCE, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
        val store = MoodStore(context)
        val stat = store.load().stat(id) ?: return
        val faces = MoodStore.facesFor(stat.scale).let { if (stat.inverted) it.reversed() else it }
        val today = LocalDate.now()
        days(context).filterKeys { it > today.minusDays(WORK_DAYS_BACK) && it >= since }.forEach { (date, day) ->
            val worked = day.workMs >= MIN_WORK_MS
            if (worked) store.setFace(date, id, faces.last())
            else if (date < today && day.trackedMs >= MIN_TRACKED_MS) store.setFace(date, id, faces.first())
        }
    }

    /** Under an hour out is the saddest face, then 1–2, 2–4, 4–6 and 6 hours or more. */
    fun faceFor(outMs: Long): Int {
        val hours = outMs / HOUR_MS.toDouble()
        return when {
            hours < 1 -> MoodStore.SAD
            hours < 2 -> MoodStore.MILDLY_SAD
            hours < 4 -> MoodStore.NEUTRAL
            hours < 6 -> MoodStore.MILDLY_HAPPY
            else -> MoodStore.HAPPY
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    const val NAME = "Social"
    const val WORK_NAME = "Work"
    private const val ALIVE = "alive"
    private val WORK_COLOR = 0xFF7986CB.toInt()
    private val WORK_LABELS = listOf("Off", "Worked")
    private const val WORK_SINCE = "workSince"
    private const val MIN_WORK_MS = 60 * 60 * 1000L
    private const val WORK_DAYS_BACK = 60L
    const val HOMES = 2
    private val COLOR = 0xFF4FC3F7.toInt()
    private val LABELS = listOf("Under 1h", "1–2h", "2–4h", "4–6h", "6h+")

    private val LOCK = Any()
    private const val PREFS = "social"
    private const val KEY = "changes"
    private const val HOUR_MS = 60 * 60 * 1000L
    private const val DAY_MS = 24 * HOUR_MS
    private const val KEEP_DAYS = 400L
    private const val DAYS_BACK = 7L
    private const val MIN_TRACKED_MS = 12 * HOUR_MS
    private const val BLIP_MS = 5 * 60 * 1000L
    private const val LEAVE_MS = 15 * 60 * 1000L
    private const val STAYED_IN_MS = HOUR_MS
}

/** Follows Wi-Fi coming and going while the notification listener runs, for [Social]. */
class WifiWatcher(private val context: Context) {

    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    // Android 12 on only shares the Wi-Fi's name with a callback that asks for it
    private val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = changed(capabilities)
            override fun onLost(network: Network) = lost()
        }
    } else {
        object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = changed(capabilities)
            override fun onLost(network: Network) = lost()
        }
    }

    private fun changed(capabilities: NetworkCapabilities) {
        runCatching {
            val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                (capabilities.transportInfo as? WifiInfo)?.ssid?.removeSurrounding("\"")
                    ?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID && it != "<unknown ssid>" }
            else null
            Social.record(context, true, ssid ?: Social.currentSsid(context))
        }
    }

    private fun lost() {
        runCatching { Social.check(context) }
    }

    /** Switching off: from now until it's back is unknown */
    private val shutdown = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            runCatching { Social.stopped(context) }
        }
    }

    fun start() {
        runCatching {
            Social.afterStart(context)
            connectivity?.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
            ContextCompat.registerReceiver(context, shutdown, android.content.IntentFilter(android.content.Intent.ACTION_SHUTDOWN), ContextCompat.RECEIVER_NOT_EXPORTED)
            Social.check(context)
        }
    }

    fun stop() {
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
        runCatching { context.unregisterReceiver(shutdown) }
        runCatching { Social.stopped(context) }
    }
}
