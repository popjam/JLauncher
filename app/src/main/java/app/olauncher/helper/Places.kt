package app.olauncher.helper

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import app.olauncher.helper.PlaceMath.Reading
import app.olauncher.helper.PlaceMath.Spot
import app.olauncher.helper.PlaceMath.Stay
import app.olauncher.helper.PlaceMath.Visit
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Places you spend time at, found from where the phone is: a rough location (from Wi-Fi and phone masts,
 * never GPS) about every [CHECK_MS], and the Wi-Fi networks nearby. Readings at one spot make a stay;
 * stays of [PlaceMath.MIN_VISIT_MS] or more are kept as visits, and somewhere visited on a few days is
 * suggested as a place to name. A named place then counts in Social: time out spent there shows as there.
 * Homes and work stay as they are, from their Wi-Fi. Everything is kept on the phone (and in backups).
 */
object Places {

    /** A named place: its number in Social (from [FIRST_ID]), name, where it is, its networks and colour */
    class Place(val id: Int, val name: String, lat: Double, lon: Double, nets: Set<String>, val color: Int) : Spot(lat, lon, nets)

    /** Somewhere worth naming: how many visits, on how many days, and how long in all */
    class Suggestion(val lat: Double, val lon: Double, val nets: Set<String>, val visits: Int, val days: Int, val ms: Long)

    fun enabled(context: Context) = prefs(context).getBoolean(ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(ENABLED, on).apply()
        schedule(context)
        if (!on) synchronized(LOCK) { prefs(context).edit().remove(STAY).apply() }
    }

    // --- Readings ---

    /**
     * A new [location] (null when only the networks are known): noted, and the stay it's part of carried
     * on or ended. While Social has you at a home or work it's only used to learn where that is.
     */
    fun record(context: Context, location: Location?) = synchronized(LOCK) {
        if (!enabled(context) || !Social.hasLocation(context)) return
        val now = System.currentTimeMillis()
        val prefs = prefs(context)
        // Other apps' fixes come in often: a reading every few minutes is plenty
        if (now - prefs.getLong(LAST_READING, 0L) < MIN_GAP_MS) return
        count(context, CHECKS)
        val nets = nearbyNets(context)
        val fix = location?.takeIf { now - it.time < STALE_FIX_MS }
        val reading = Reading(now, fix?.latitude ?: 0.0, fix?.longitude ?: 0.0, fix?.accuracy ?: Float.MAX_VALUE, nets)
        if (!PlaceMath.usable(reading)) return
        prefs.edit().putLong(LAST_READING, now).apply()
        val social = Social.currentPlace(context)
        val stay = stay(context)
        // Only the networks known: enough to carry a stay on, not to start one
        if (fix == null && social !in 0..Social.WORK) {
            if (stay != null && (stay.nets intersect nets).size >= PlaceMath.SHARED_NETS)
                saveStay(context, Stay(stay.start, now, stay.lat, stay.lon, stay.weight, stay.nets))
            return
        }
        if (social in 0..Social.WORK) {
            // At a home or work: learn where it is, so it's never suggested
            if (fix != null && fix.accuracy <= LEARN_ACCURACY_M) learn(context, social, reading)
            if (stay != null) close(context, stay, now)
            prefs.edit().remove(STAY).apply()
            return
        }
        val (next, visit) = PlaceMath.step(stay, reading)
        if (visit != null && stay != null) {
            addVisit(context, visit)
            close(context, stay, stay.last + (now - stay.last) / 2)
        } else if (stay != null && next.start != stay.start) close(context, stay, now)
        saveStay(context, next)
        // At a named place: Social shows it from when the stay began
        placeAt(context, next)?.let { place -> Social.paint(context, next.start, Long.MAX_VALUE, Social.OUT, place.id) }
        Unit
    }

    /** A stay that's ended: if it was at a named place, Social has you back out from [leftAt] */
    private fun close(context: Context, stay: Stay, leftAt: Long) {
        placeAt(context, stay)?.let { Social.paint(context, leftAt, Long.MAX_VALUE, it.id, Social.OUT) }
    }

    /** The named place [spot] is at, if any */
    private fun placeAt(context: Context, spot: Spot) = places(context).firstOrNull { PlaceMath.same(it, spot) }

    /** The strongest nearby networks from the phone's latest scan, as short codes; a new scan when that's old */
    @SuppressLint("MissingPermission")
    private fun nearbyNets(context: Context): Set<String> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return emptySet()
        val results = runCatching { wifi.scanResults }.getOrNull().orEmpty()
        val nowMicros = SystemClock.elapsedRealtime() * 1000
        val fresh = results.filter { nowMicros - it.timestamp < SCAN_FRESH_MS * 1000 }
        if (fresh.isEmpty() && System.currentTimeMillis() - prefs(context).getLong(LAST_SCAN, 0L) >= SCAN_FRESH_MS) {
            // Android allows one every half hour in the background anyway
            @Suppress("DEPRECATION")
            if (runCatching { wifi.startScan() }.getOrDefault(false)) {
                prefs(context).edit().putLong(LAST_SCAN, System.currentTimeMillis()).apply()
                count(context, SCANS)
            }
        }
        return fresh.sortedByDescending { it.level }.take(NETS_KEPT).mapNotNull { r -> r.BSSID?.let(::code) }.toSet()
    }

    /** A network's id as a short code, so the list doesn't hold the real ones */
    private fun code(bssid: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(("jl:" + bssid.lowercase()).toByteArray())
        return digest.take(5).joinToString("") { "%02x".format(it) }
    }

    // --- Today's checks, for Settings ---

    private fun count(context: Context, key: String) {
        val prefs = prefs(context)
        val today = LocalDate.now().toString()
        val edit = prefs.edit()
        if (prefs.getString(COUNT_DAY, null) != today) edit.putString(COUNT_DAY, today).putInt(CHECKS, 0).putInt(SCANS, 0)
        edit.putInt(key, (if (prefs.getString(COUNT_DAY, null) == today) prefs.getInt(key, 0) else 0) + 1).apply()
    }

    /** Location checks and Wi-Fi scans today */
    fun todayCounts(context: Context): Pair<Int, Int> {
        val prefs = prefs(context)
        if (prefs.getString(COUNT_DAY, null) != LocalDate.now().toString()) return 0 to 0
        return prefs.getInt(CHECKS, 0) to prefs.getInt(SCANS, 0)
    }

    fun lastReading(context: Context) = prefs(context).getLong(LAST_READING, 0L)

    // --- Stays and visits ---

    private fun stay(context: Context): Stay? = prefs(context).getString(STAY, null)?.let { json ->
        runCatching {
            val o = JSONObject(json)
            Stay(o.getLong("start"), o.getLong("last"), o.getDouble("lat"), o.getDouble("lon"), o.getDouble("weight"), nets(o))
        }.getOrNull()
    }

    private fun saveStay(context: Context, stay: Stay) = prefs(context).edit().putString(STAY, JSONObject()
        .put("start", stay.start).put("last", stay.last).put("lat", stay.lat).put("lon", stay.lon).put("weight", stay.weight)
        .put("nets", JSONArray(stay.nets.toList())).toString()).apply()

    private fun nets(o: JSONObject): Set<String> = o.optJSONArray("nets")?.let { a -> List(a.length()) { a.getString(it) }.toSet() } ?: emptySet()

    /** Every visit kept, oldest first, with the one going on now if it's long enough */
    fun visits(context: Context): List<Visit> {
        val kept = storedVisits(context)
        val now = stay(context)?.takeIf { it.ms >= PlaceMath.MIN_VISIT_MS }?.let { Visit(it.start, it.last, it.lat, it.lon, it.nets) }
        return if (now == null) kept else kept + now
    }

    private fun storedVisits(context: Context): List<Visit> {
        val json = prefs(context).getString(VISITS, null) ?: return emptyList()
        if (json === parsedVisitsJson) return parsedVisits
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return List(array.length()) { array.getJSONObject(it) }.mapNotNull { o ->
            runCatching { Visit(o.getLong("s"), o.getLong("e"), o.getDouble("a"), o.getDouble("o"), nets(o)) }.getOrNull()
        }.also {
            parsedVisitsJson = json
            parsedVisits = it
        }
    }

    private var parsedVisitsJson: String? = null
    private var parsedVisits: List<Visit> = emptyList()

    private fun addVisit(context: Context, visit: Visit) {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24 * 3_600_000L
        saveVisits(context, (storedVisits(context) + visit).filter { it.end >= cutoff })
    }

    private fun saveVisits(context: Context, visits: List<Visit>) {
        val array = JSONArray()
        visits.forEach { v ->
            // To about a metre
            array.put(JSONObject().put("s", v.start).put("e", v.end).put("a", round(v.lat)).put("o", round(v.lon)).put("nets", JSONArray(v.nets.toList())))
        }
        prefs(context).edit().putString(VISITS, array.toString()).apply()
    }

    private fun round(v: Double) = Math.round(v * 100_000) / 100_000.0

    /** Forgets every visit and the stay going on; named places and the time already counted at them stay */
    fun deleteHistory(context: Context) = synchronized(LOCK) {
        prefs(context).edit().remove(VISITS).remove(STAY).remove(DISMISSED).apply()
    }

    // --- Homes and work, learned ---

    private fun learn(context: Context, slot: Int, reading: Reading) {
        val prefs = prefs(context)
        val key = LEARNED + slot
        val old = prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val n = old?.optInt("n") ?: 0
        val lat = if (old == null) reading.lat else (old.getDouble("lat") * n + reading.lat) / (n + 1)
        val lon = if (old == null) reading.lon else (old.getDouble("lon") * n + reading.lon) / (n + 1)
        val nets = ((old?.let(::nets) ?: emptySet()) + reading.nets).take(PlaceMath.MAX_NETS * 2)
        prefs.edit().putString(key, JSONObject().put("lat", lat).put("lon", lon).put("n", minOf(n + 1, 50)).put("nets", JSONArray(nets)).toString()).apply()
    }

    /** Where homes and work are, as far as learned */
    private fun learned(context: Context): List<Spot> = (0..Social.WORK).mapNotNull { slot ->
        prefs(context).getString(LEARNED + slot, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.let { Spot(it.getDouble("lat"), it.getDouble("lon"), nets(it)) }
    }

    // --- Named places ---

    fun places(context: Context): List<Place> {
        val json = prefs(context).getString(NAMED, null) ?: return emptyList()
        if (json === parsedPlacesJson) return parsedPlaces
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return List(array.length()) { array.getJSONObject(it) }.mapNotNull { o ->
            runCatching { Place(o.getInt("id"), o.getString("name"), o.getDouble("lat"), o.getDouble("lon"), nets(o), o.getInt("color")) }.getOrNull()
        }.also {
            parsedPlacesJson = json
            parsedPlaces = it
        }
    }

    private var parsedPlacesJson: String? = null
    private var parsedPlaces: List<Place> = emptyList()

    fun place(context: Context, id: Int) = places(context).firstOrNull { it.id == id }

    private fun savePlaces(context: Context, places: List<Place>) {
        val array = JSONArray()
        places.forEach { p ->
            array.put(JSONObject().put("id", p.id).put("name", p.name).put("lat", p.lat).put("lon", p.lon)
                .put("nets", JSONArray(p.nets.toList())).put("color", p.color))
        }
        prefs(context).edit().putString(NAMED, array.toString()).apply()
    }

    /** Names [suggestion]: from now it counts in Social, and so do the visits there already kept */
    fun name(context: Context, suggestion: Suggestion, name: String) = synchronized(LOCK) {
        val places = places(context)
        val id = (places.maxOfOrNull { it.id } ?: (FIRST_ID - 1)) + 1
        val used = places.map { it.color }
        val color = COLORS.firstOrNull { it !in used } ?: COLORS[places.size % COLORS.size]
        val place = Place(id, name, suggestion.lat, suggestion.lon, suggestion.nets, color)
        savePlaces(context, places + place)
        visits(context).filter { PlaceMath.same(place, it) }.forEach { Social.paint(context, it.start, it.end, Social.OUT, id) }
        stay(context)?.takeIf { PlaceMath.same(place, it) }?.let { Social.paint(context, it.start, Long.MAX_VALUE, Social.OUT, id) }
    }

    fun rename(context: Context, id: Int, name: String) = synchronized(LOCK) {
        savePlaces(context, places(context).map { if (it.id == id) Place(it.id, name, it.lat, it.lon, it.nets, it.color) else it })
    }

    /** The next colour along for a place */
    fun recolor(context: Context, id: Int) = synchronized(LOCK) {
        savePlaces(context, places(context).map {
            if (it.id == id) Place(it.id, it.name, it.lat, it.lon, it.nets, COLORS[(COLORS.indexOf(it.color) + 1).mod(COLORS.size)]) else it
        })
    }

    /** Removes a place: its time goes back to being out, and it won't be suggested again */
    fun remove(context: Context, id: Int) = synchronized(LOCK) {
        val place = place(context, id) ?: return
        Social.paint(context, 0L, Long.MAX_VALUE, id, Social.OUT)
        savePlaces(context, places(context).filter { it.id != id })
        dismiss(context, Suggestion(place.lat, place.lon, place.nets, 0, 0, 0L))
    }

    // --- Suggestions ---

    fun suggestions(context: Context): List<Suggestion> {
        val known = learned(context) + places(context)
        return PlaceMath.suggestions(visits(context), known, dismissed(context), ZoneId.systemDefault()).map { g ->
            Suggestion(g.lat, g.lon, g.nets, g.visits.size, g.days(ZoneId.systemDefault()).size, g.ms)
        }
    }

    fun dismiss(context: Context, suggestion: Suggestion) = synchronized(LOCK) {
        val array = prefs(context).getString(DISMISSED, null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
        array.put(JSONObject().put("lat", suggestion.lat).put("lon", suggestion.lon).put("nets", JSONArray(suggestion.nets.toList())))
        prefs(context).edit().putString(DISMISSED, array.toString()).apply()
    }

    private fun dismissed(context: Context): List<Spot> {
        val array = prefs(context).getString(DISMISSED, null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return List(array.length()) { array.getJSONObject(it) }.map { Spot(it.getDouble("lat"), it.getDouble("lon"), nets(it)) }
    }

    /** A street name near [lat], [lon] from the phone's address lookup, remembered; null where there isn't one */
    fun nearName(context: Context, lat: Double, lon: Double): String? {
        val key = LABEL + "%.4f,%.4f".format(java.util.Locale.ROOT, lat, lon)
        prefs(context).getString(key, null)?.let { return it.ifEmpty { null } }
        if (!android.location.Geocoder.isPresent()) return null
        @Suppress("DEPRECATION")
        val address = runCatching { android.location.Geocoder(context).getFromLocation(lat, lon, 1)?.firstOrNull() }.getOrNull()
        val name = address?.let { it.thoroughfare ?: it.featureName ?: it.subLocality ?: it.locality }.orEmpty()
        prefs(context).edit().putString(key, name).apply()
        return name.ifEmpty { null }
    }

    // --- The check every quarter hour, when the listener isn't giving readings ---

    fun schedule(context: Context) {
        val work = WorkManager.getInstance(context)
        if (!enabled(context)) return run { work.cancelUniqueWork(WORK_NAME) }
        work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PlacesWorker>(15, TimeUnit.MINUTES).build())
    }

    /** A rough location now: the newest one the phone has, or a new one (Android 11 on) when that's old */
    @SuppressLint("MissingPermission")
    fun locate(context: Context): Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val known = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (known != null && System.currentTimeMillis() - known.time < CHECK_MS) return known
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return known
        val provider = provider(manager) ?: return known
        val latch = CountDownLatch(1)
        var found: Location? = null
        runCatching {
            manager.getCurrentLocation(provider, null, context.mainExecutor) {
                found = it
                latch.countDown()
            }
        }.onFailure { return known }
        latch.await(30, TimeUnit.SECONDS)
        return found ?: known
    }

    /** Wi-Fi and phone masts; GPS only where there's nothing else (the emulator) */
    internal fun provider(manager: LocationManager) = when {
        runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) -> LocationManager.NETWORK_PROVIDER
        manager.allProviders.contains(LocationManager.NETWORK_PROVIDER) -> null
        runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) -> LocationManager.GPS_PROVIDER
        else -> null
    }

    internal fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val LOCK = Any()
    const val PREFS = "places"
    internal const val ENABLED = "enabled"
    const val FIRST_ID = 10
    private const val STAY = "stay"
    private const val VISITS = "visits"
    private const val NAMED = "named"
    private const val DISMISSED = "dismissed"
    private const val LEARNED = "learned"
    private const val LABEL = "label_"
    private const val LAST_READING = "lastReading"
    private const val LAST_SCAN = "lastScan"
    private const val COUNT_DAY = "countDay"
    private const val CHECKS = "checks"
    private const val SCANS = "scans"
    private const val WORK_NAME = "places"
    const val CHECK_MS = 15 * 60_000L
    private const val MIN_GAP_MS = 5 * 60_000L
    private const val STALE_FIX_MS = 30 * 60_000L
    private const val SCAN_FRESH_MS = 30 * 60_000L
    private const val NETS_KEPT = 5
    private const val LEARN_ACCURACY_M = 200f
    private const val KEEP_DAYS = 400L
    private val COLORS = listOf(0xFF4DB6AC, 0xFFF06292, 0xFFBA68C8, 0xFFFFD54F, 0xFFE57373, 0xFFAED581, 0xFFA1887F, 0xFF90A4AE).map { it.toInt() }
}

/** The quarter-hourly check, for when the notification listener isn't running to get readings */
class PlacesWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        runCatching {
            if (Places.enabled(applicationContext) && System.currentTimeMillis() - Places.lastReading(applicationContext) >= Places.CHECK_MS - 60_000L) {
                Places.record(applicationContext, Places.locate(applicationContext))
            }
        }
        return Result.success()
    }
}

/** Asks for a rough location every [Places.CHECK_MS] while the notification listener runs, and takes any other app's for free */
class PlaceWatcher(private val context: Context) {

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            runCatching { Places.record(context, location) }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val switched = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Places.ENABLED) {
            stopUpdates()
            startUpdates()
        }
    }

    fun start() {
        runCatching { Places.prefs(context).registerOnSharedPreferenceChangeListener(switched) }
        startUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        runCatching {
            if (!Places.enabled(context) || !Social.hasLocation(context)) return
            val manager = manager ?: return
            Places.provider(manager)?.let { manager.requestLocationUpdates(it, Places.CHECK_MS, 0f, listener, Looper.getMainLooper()) }
            manager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 5 * 60_000L, 0f, listener, Looper.getMainLooper())
        }
    }

    private fun stopUpdates() {
        runCatching { manager?.removeUpdates(listener) }
    }

    fun stop() {
        runCatching { Places.prefs(context).unregisterOnSharedPreferenceChangeListener(switched) }
        stopUpdates()
    }
}
