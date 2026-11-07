package app.olauncher.helper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.time.LocalDate
import java.time.ZoneId

/**
 * The phone's battery over the last [KEEP_DAYS] days, from a reading each time its level, charging or
 * the screen changes (nothing is written when nothing's changed). From these: how fast it drains with
 * the screen on and off, how often and from where it's charged, and how long it has left.
 */
object Battery {

    class Sample(val time: Long, val level: Int, val charging: Boolean, val screenOn: Boolean)

    /** A charge: plugged in at [fromLevel], unplugged (or still charging) at [toLevel] */
    class Charge(val start: Long, val end: Long, val fromLevel: Int, val toLevel: Int)

    /** Notes the battery now, if anything's changed since the last reading */
    fun record(context: Context) = synchronized(LOCK) {
        val intent = ContextCompat.registerReceiver(context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED) ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return
        val percent = level * 100 / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val screenOn = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive == true
        val samples = samples(context)
        val last = samples.lastOrNull()
        if (last != null && last.level == percent && last.charging == charging && last.screenOn == screenOn) return
        val now = System.currentTimeMillis()
        val cutoff = now - KEEP_DAYS * DAY_MS
        save(context, samples.filter { it.time >= cutoff } + Sample(now, percent, charging, screenOn))
    }

    fun samples(context: Context): List<Sample> {
        val json = prefs(context).getString(KEY, null) ?: return emptyList()
        if (json === parsedJson) return parsed
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return List(array.length()) { array.getJSONArray(it) }.map { a ->
            Sample(a.getLong(0), a.getInt(1), a.getInt(2) == 1, a.getInt(3) == 1)
        }.also {
            parsedJson = json
            parsed = it
        }
    }

    private var parsedJson: String? = null
    private var parsed: List<Sample> = emptyList()

    private fun save(context: Context, samples: List<Sample>) {
        val array = JSONArray()
        samples.forEach { array.put(JSONArray().put(it.time).put(it.level).put(if (it.charging) 1 else 0).put(if (it.screenOn) 1 else 0)) }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    /** Each time it was charged, oldest first */
    fun charges(samples: List<Sample>): List<Charge> {
        val charges = mutableListOf<Charge>()
        var start: Sample? = null
        samples.forEachIndexed { i, s ->
            if (s.charging && start == null) start = samples.getOrNull(i - 1)?.takeIf { !it.charging }?.let { Sample(s.time, it.level, true, s.screenOn) } ?: s
            if (!s.charging && start != null) {
                charges += Charge(start!!.time, s.time, start!!.level, samples[i - 1].level)
                start = null
            }
        }
        start?.let { charges += Charge(it.time, samples.last().time, it.level, samples.last().level) }
        return charges
    }

    /**
     * Percent an hour lost while on battery, with the screen on ([screenOn]) or off, over samples since
     * [since]; null without enough to go by.
     */
    fun drainPerHour(samples: List<Sample>, since: Long, screenOn: Boolean): Float? {
        var lost = 0f
        var ms = 0L
        samples.zipWithNext().forEach { (a, b) ->
            if (a.time < since || a.charging || b.charging || a.screenOn != screenOn) return@forEach
            val gap = b.time - a.time
            // Gaps over a few hours are the phone off or a missed reading, not the battery
            if (gap <= 0 || gap > 6 * 3_600_000L) return@forEach
            lost += (a.level - b.level).coerceAtLeast(0)
            ms += gap
        }
        return if (ms < 20 * 60_000L) null else lost / (ms / 3_600_000f)
    }

    /** Charges a day over the last [days] days (as far as readings go back) */
    fun chargesPerDay(samples: List<Sample>, days: Int): Float? {
        val first = samples.firstOrNull()?.time ?: return null
        val since = System.currentTimeMillis() - days * DAY_MS
        val span = (System.currentTimeMillis() - maxOf(first, since)) / DAY_MS.toFloat()
        if (span < 1f) return null
        return charges(samples).count { it.start >= since } / span
    }

    fun dayOf(time: Long): LocalDate = java.time.Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val LOCK = Any()
    private const val PREFS = "battery"
    private const val KEY = "samples"
    const val KEEP_DAYS = 14L
    const val DAY_MS = 24 * 3_600_000L
}

/** Takes a reading whenever the battery or the screen changes, while the notification listener runs */
class BatteryWatcher(private val context: Context) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            runCatching { Battery.record(context) }
        }
    }

    fun start() {
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            Battery.record(context)
        }
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }
}
