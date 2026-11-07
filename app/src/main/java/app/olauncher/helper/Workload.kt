package app.olauncher.helper

import android.content.Context
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * What JLauncher's own background work costs. Android doesn't tell an app how much battery each part of
 * it uses, so each part is measured as it runs: how often, how long it kept the processor working, and
 * the location checks, Wi-Fi scans and downloads it asked for. Everything else the app's process did is
 * kept too, so nothing goes unseen. Kept by the hour for [KEEP_DAYS] days, split by screen on and off.
 */
object Workload {

    // The parts measured
    const val PLACES = "places"
    const val WIFI = "wifi"
    const val STEPS = "steps"
    const val BATTERY = "battery"
    const val NOTIFICATIONS = "notifications"
    const val MUSIC = "music"
    const val WEATHER = "weather"
    const val PEOPLE = "people"
    /** The rest of the app's work: its screens, and anything not measured on its own */
    const val OTHER = "other"

    /** One part's work: runs, processor time (µs), location checks, Wi-Fi scans and downloads */
    class Use(var runs: Long = 0, var cpuUs: Long = 0, var locations: Long = 0, var scans: Long = 0, var downloads: Long = 0) {
        fun add(o: Use) {
            runs += o.runs; cpuUs += o.cpuUs; locations += o.locations; scans += o.scans; downloads += o.downloads
        }

        /** A rough battery cost in mAh: a few hundred mA while working, plus each wake, check, scan and download */
        val mAh: Double
            get() = cpuUs / 1e6 * CPU_MAH_PER_S + runs * WAKE_MAH + locations * LOCATION_MAH + scans * SCAN_MAH + downloads * DOWNLOAD_MAH
    }

    /** Runs [block] as part of [part], timing the processor work it does */
    inline fun <T> track(context: Context, part: String, block: () -> T): T {
        val start = threadCpuUs()
        try {
            return block()
        } finally {
            add(context, part, runs = 1, cpuUs = (threadCpuUs() - start).coerceAtLeast(0))
        }
    }

    fun threadCpuUs(): Long {
        val ns = Debug.threadCpuTimeNanos()
        return if (ns >= 0) ns / 1000 else SystemClock.currentThreadTimeMillis() * 1000
    }

    /** Notes work for [part] in the hour now */
    fun add(context: Context, part: String, runs: Long = 0, cpuUs: Long = 0, locations: Long = 0, scans: Long = 0, downloads: Long = 0) {
        runCatching {
            val app = context.applicationContext
            val off = !screenOn(app)
            synchronized(LOCK) {
                load(app)
                val key = keyOf(part, off)
                pending.getOrPut(hourNow()) { mutableMapOf() }.getOrPut(key) { Use() }
                    .add(Use(runs, cpuUs, locations, scans, downloads))
                if (SystemClock.elapsedRealtime() - lastFlush >= FLUSH_MS) flushLocked(app)
            }
        }
    }

    /** The screen's about to change: the app's work so far goes to the state it was in. Then saves. */
    fun screenChanged(context: Context) = runCatching {
        val app = context.applicationContext
        // It's already changed: the work so far was done in the other state
        synchronized(LOCK) { flushLocked(app, processOff = screenOn(app)) }
    }

    /** Saves what's been measured, with the process's total work since the last save */
    fun flush(context: Context) = runCatching { synchronized(LOCK) { flushLocked(context.applicationContext) } }

    /**
     * Use per part over the hours from [since] (screen off only when [screenOffOnly]), largest first.
     * [OTHER] is the process's total work less what the parts account for.
     */
    fun since(context: Context, since: Long, screenOffOnly: Boolean): Map<String, Use> {
        flush(context)
        val from = since / HOUR_MS
        val totals = mutableMapOf<String, Use>()
        synchronized(LOCK) {
            load(context.applicationContext)
            hours.filterKeys { it >= from }.values.forEach { parts ->
                parts.forEach { (key, use) ->
                    val (part, off) = split(key)
                    if (screenOffOnly && !off) return@forEach
                    totals.getOrPut(part) { Use() }.add(use)
                }
            }
        }
        // What the process did beyond the measured parts
        totals.remove(PROCESS)?.let { process ->
            val measured = totals.values.sumOf { it.cpuUs }
            val rest = process.cpuUs - measured
            if (rest > 0) totals.getOrPut(OTHER) { Use() }.cpuUs += rest
        }
        return totals.filterValues { it.mAh > 0 }.entries.sortedByDescending { it.value.mAh }.associate { it.key to it.value }
    }

    /** The battery's size in mAh, from what it holds now and its level; a typical size when unknown */
    fun capacityMah(context: Context): Double {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return TYPICAL_MAH
        val counter = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (counter <= 0 || level !in 5..100) return TYPICAL_MAH
        val mah = counter / 1000.0 * 100 / level
        return if (mah in 1000.0..15000.0) mah else TYPICAL_MAH
    }

    // --- Storage ---

    private val LOCK = Any()
    private var loaded = false
    /** Saved hours: hour number → part (with ":off" when the screen was off) → use */
    private val hours = sortedMapOf<Long, MutableMap<String, Use>>()
    /** Measured since the last save */
    private val pending = mutableMapOf<Long, MutableMap<String, Use>>()
    private var lastFlush = 0L
    /** The process's processor time at the last save, so only the work since is added */
    private var lastProcessMs = -1L

    private fun keyOf(part: String, off: Boolean) = if (off) "$part:off" else part
    private fun split(key: String) = if (key.endsWith(":off")) key.removeSuffix(":off") to true else key to false
    private fun hourNow() = System.currentTimeMillis() / HOUR_MS

    private fun screenOn(context: Context) =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive != false

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        runCatching {
            val root = JSONObject(json)
            root.keys().forEach { hour ->
                val parts = root.getJSONObject(hour)
                hours[hour.toLong()] = parts.keys().asSequence().associateWith { key ->
                    val a = parts.getJSONArray(key)
                    Use(a.getLong(0), a.getLong(1), a.getLong(2), a.getLong(3), a.getLong(4))
                }.toMutableMap()
            }
        }
    }

    private fun flushLocked(context: Context, processOff: Boolean = !screenOn(context)) {
        load(context)
        // The process's own processor time since the last save, under the screen state now
        val processMs = Process.getElapsedCpuTime()
        if (lastProcessMs in 0..processMs) {
            pending.getOrPut(hourNow()) { mutableMapOf() }.getOrPut(keyOf(PROCESS, processOff)) { Use() }
                .cpuUs += (processMs - lastProcessMs) * 1000
        }
        lastProcessMs = processMs
        lastFlush = SystemClock.elapsedRealtime()
        if (pending.isEmpty()) return
        pending.forEach { (hour, parts) ->
            val saved = hours.getOrPut(hour) { mutableMapOf() }
            parts.forEach { (key, use) -> saved.getOrPut(key) { Use() }.add(use) }
        }
        pending.clear()
        val cutoff = hourNow() - KEEP_DAYS * 24
        hours.headMap(cutoff).clear()
        val root = JSONObject()
        hours.forEach { (hour, parts) ->
            val o = JSONObject()
            parts.forEach { (key, u) -> o.put(key, JSONArray().put(u.runs).put(u.cpuUs).put(u.locations).put(u.scans).put(u.downloads)) }
            root.put(hour.toString(), o)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, root.toString()).apply()
    }

    private const val PROCESS = "process"
    private const val PREFS = "workload"
    private const val KEY = "hours"
    private const val KEEP_DAYS = 14L
    private const val HOUR_MS = 3_600_000L
    private const val FLUSH_MS = 15 * 60_000L
    private const val TYPICAL_MAH = 4500.0

    // Rough costs, from typical phone figures
    private const val CPU_MAH_PER_S = 0.06      // ~200 mA while working
    private const val WAKE_MAH = 0.01           // waking the phone up and letting it sleep again
    private const val LOCATION_MAH = 0.1        // a rough location from Wi-Fi and phone masts
    private const val SCAN_MAH = 0.03           // a Wi-Fi scan
    private const val DOWNLOAD_MAH = 0.1        // a small download over mobile data or Wi-Fi
}
