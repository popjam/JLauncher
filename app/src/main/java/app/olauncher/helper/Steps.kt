package app.olauncher.helper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import app.olauncher.data.MoodStore
import org.json.JSONObject
import java.time.LocalDate

/**
 * Steps from the phone's own step counter, which counts in hardware all the time at next to no battery.
 * It only gives a running total since the phone started, so each reading adds what's new since the
 * last one to the day it's read on. Read by [StepWatcher] while the notification listener runs, and on
 * each Screen time refresh. Kept on the phone and filled into the Steps stat.
 */
object Steps {

    fun hasPermission(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    fun hasSensor(context: Context) = sensor(context) != null

    private fun sensor(context: Context) =
        (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    fun days(context: Context): Map<LocalDate, Int> = synchronized(LOCK) {
        val json = prefs(context).getString(KEY, null) ?: return emptyMap()
        if (json === parsedJson) return parsedDays
        val days = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        parsedJson = json
        parsedDays = days.keys().asSequence().mapNotNull { key ->
            runCatching { LocalDate.parse(key) }.getOrNull()?.let { it to days.getInt(key) }
        }.toMap()
        return parsedDays
    }

    /** A reading of the step counter's running total */
    fun counted(context: Context, total: Long) {
        val today = LocalDate.now()
        val steps = synchronized(LOCK) {
            val prefs = prefs(context)
            val last = prefs.getLong(COUNTER_KEY, -1)
            prefs.edit().putLong(COUNTER_KEY, total).apply()
            // The first reading only sets where counting starts; a lower total means the phone restarted
            val added = when {
                last < 0 -> 0L
                total < last -> total
                else -> total - last
            }
            val days = days(context).toMutableMap()
            if (added <= 0 && days.containsKey(today)) return
            days[today] = (days[today] ?: 0) + added.toInt()
            val cutoff = today.minusDays(KEEP_DAYS)
            save(context, days.filterKeys { it >= cutoff })
            days.getValue(today)
        }
        if (steps > 0) setFace(context, today, steps)
    }

    /** Adds the Steps stat the first time, and reads the counter once. */
    fun refresh(context: Context) {
        ensureStat(context)
        if (!hasPermission(context)) return
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = sensor(context) ?: return
        val handler = Handler(Looper.getMainLooper())
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                manager.unregisterListener(this)
                runCatching { counted(context, event.values[0].toLong()) }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        handler.post { runCatching { manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL) } }
        // In case no reading comes
        handler.postDelayed({ manager.unregisterListener(listener) }, ONE_SHOT_MS)
    }

    /** Steps had a stat of its own; it's shown on Exercise's page now, so the old one goes. */
    fun ensureStat(context: Context): String? {
        val store = MoodStore(context)
        val data = store.load()
        (AutoStats.id(context, AutoStats.STEPS, data) ?: data.stats.firstOrNull { it.name == NAME && it.labels == LABELS }?.id)?.let { store.deleteStat(it) }
        return null
    }

    @Suppress("UNUSED_PARAMETER")
    private fun setFace(context: Context, date: LocalDate, steps: Int) {}

    /** Under 2,000 is the saddest face, then 2–5k, 5–8k, 8–10k and 10k or more. */
    fun faceFor(steps: Int) = when {
        steps < 2000 -> MoodStore.SAD
        steps < 5000 -> MoodStore.MILDLY_SAD
        steps < 8000 -> MoodStore.NEUTRAL
        steps < GOAL -> MoodStore.MILDLY_HAPPY
        else -> MoodStore.HAPPY
    }

    private fun save(context: Context, days: Map<LocalDate, Int>) {
        val json = JSONObject()
        days.forEach { (date, steps) -> json.put(date.toString(), steps) }
        prefs(context).edit().putString(KEY, json.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    const val NAME = "Steps"
    const val GOAL = 10_000

    /** The day's goal, 10,000 unless changed */
    fun goal(context: Context) = prefs(context).getInt(GOAL_KEY, GOAL)

    fun setGoal(context: Context, steps: Int) = prefs(context).edit().putInt(GOAL_KEY, steps).apply()

    private const val GOAL_KEY = "goal"
    private val COLOR = 0xFFAED581.toInt()
    private val LABELS = listOf("Under 2k", "2–5k", "5–8k", "8–10k", "10k+")

    private val LOCK = Any()
    private const val PREFS = "steps"
    private var parsedJson: String? = null
    private var parsedDays: Map<LocalDate, Int> = emptyMap()
    private const val KEY = "days"
    private const val COUNTER_KEY = "counter"
    private const val KEEP_DAYS = 400L
    private const val ONE_SHOT_MS = 10_000L
}

/** Listens to the step counter while the notification listener runs, so steps land on the right day. */
class StepWatcher(private val context: Context) : SensorEventListener {

    companion object {
        /** Whether one is listening, so nothing else needs to ask the sensor */
        @Volatile var running = false
        private const val BATCH_US = 5 * 60 * 1000 * 1000
    }

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    fun start() {
        if (!Steps.hasPermission(context)) return
        val manager = manager ?: return
        val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        // Batched by the hardware for up to a few minutes, so it barely wakes the phone
        running = runCatching { manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, BATCH_US) }.getOrDefault(false)
    }

    fun stop() {
        runCatching { manager?.unregisterListener(this) }
        running = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        runCatching { Steps.counted(context, event.values[0].toLong()) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
