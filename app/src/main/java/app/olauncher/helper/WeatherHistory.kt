package app.olauncher.helper

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import app.olauncher.data.MoodStore
import app.olauncher.data.Prefs
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Locale

/**
 * Each day's weather where you are, from Open-Meteo (free, no key): sunshine, daylight, high and low,
 * and rain. The last three months come with each refresh, and the first time everything back to the
 * first day logged, so the Weather stat (faces by hours of sunshine) and Insights have history at once.
 * Always kept in °C and mm; shown in the units the forecast uses.
 */
object WeatherHistory {

    class Day(val code: Int, val max: Float, val min: Float, val rain: Float, val sunHours: Float, val daylightHours: Float) {
        val rainy get() = rain >= RAINY_MM
    }

    fun days(context: Context): Map<LocalDate, Day> {
        val json = prefs(context).getString(DAYS, null) ?: return emptyMap()
        if (json === parsedJson) return parsed
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        return obj.keys().asSequence().mapNotNull { key ->
            val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@mapNotNull null
            val d = obj.getJSONObject(key)
            date to Day(d.optInt("c"), d.optDouble("hi").toFloat(), d.optDouble("lo").toFloat(), d.optDouble("r").toFloat(),
                d.optDouble("s").toFloat(), d.optDouble("l").toFloat())
        }.toMap().also {
            parsedJson = json
            parsed = it
        }
    }

    private var parsedJson: String? = null
    private var parsed: Map<LocalDate, Day> = emptyMap()

    /** At most every [REFRESH_MS]; on a background thread */
    fun refresh(context: Context) {
        if (!Weather.hasLocationPermission(context) && !Social.hasLocation(context)) return
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(LAST, 0L) < REFRESH_MS) return
        val (lat, lon) = where(context) ?: return
        prefs.edit().putLong(LAST, now).apply()
        val kept = prefs.getString(DAYS, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val place = "latitude=%.3f&longitude=%.3f".format(Locale.ROOT, lat, lon)
        val recent = get("https://api.open-meteo.com/v1/forecast?$place&daily=$FIELDS&timezone=auto&past_days=92&forecast_days=1") ?: return
        merge(kept, recent)
        // The first time: back to the first day logged, up to two years
        if (!prefs.getBoolean(ARCHIVED, false)) {
            val today = LocalDate.now()
            val first = MoodStore(context).load().days.keys.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.minOrNull()
            val start = maxOf(first ?: today, today.minusYears(2))
            val end = today.minusDays(85)
            val done = if (start < end) get("https://archive-api.open-meteo.com/v1/archive?$place&daily=$FIELDS&timezone=auto&start_date=$start&end_date=$end")
                ?.let { merge(kept, it); true } ?: false
            else true
            if (done) prefs.edit().putBoolean(ARCHIVED, true).apply()
        }
        prefs.edit().putString(DAYS, kept.toString()).apply()
        fill(context)
    }

    /** Adds the Weather stat the first time, and sets each day's face from its sunshine */
    private fun fill(context: Context) {
        val days = days(context)
        if (days.isEmpty()) return
        val id = AutoStats.ensure(context, AutoStats.WEATHER, NAME) { store ->
            store.addStat(NAME, COLOR, daily = false, scale = 5, question = "", labels = LABELS, inverted = false,
                phrases = emptyList(), medicine = false, nextDay = false)
        } ?: return
        val today = LocalDate.now()
        MoodStore(context).setFaces(id, days.filterKeys { it <= today }.mapValues { faceFor(it.value.sunHours) })
    }

    /** Under an hour of sun is the saddest face, then up to 3, 6 and 9 hours, and 9 or more */
    fun faceFor(sunHours: Float) = when {
        sunHours < 1f -> MoodStore.SAD
        sunHours < 3f -> MoodStore.MILDLY_SAD
        sunHours < 6f -> MoodStore.NEUTRAL
        sunHours < 9f -> MoodStore.MILDLY_HAPPY
        else -> MoodStore.HAPPY
    }

    private fun merge(kept: JSONObject, response: JSONObject) {
        val daily = response.optJSONObject("daily") ?: return
        val dates = daily.optJSONArray("time") ?: return
        fun value(name: String, i: Int) = daily.optJSONArray(name)?.optDouble(i)?.takeUnless { it.isNaN() }
        val today = LocalDate.now().toString()
        for (i in 0 until dates.length()) {
            val date = dates.optString(i)
            if (date > today) continue
            val sun = value("sunshine_duration", i) ?: continue
            kept.put(date, JSONObject()
                .put("c", value("weather_code", i)?.toInt() ?: 0)
                .put("hi", value("temperature_2m_max", i) ?: 0.0)
                .put("lo", value("temperature_2m_min", i) ?: 0.0)
                .put("r", value("precipitation_sum", i) ?: 0.0)
                .put("s", Math.round(sun / 360.0) / 10.0)
                .put("l", Math.round((value("daylight_duration", i) ?: 0.0) / 360.0) / 10.0))
        }
    }

    private fun get(url: String): JSONObject? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /** Where the forecast was last for, else the newest location the phone has */
    @SuppressLint("MissingPermission")
    private fun where(context: Context): Pair<Double, Double>? {
        val prefs = Prefs(context)
        if (!prefs.weatherLatitude.isNaN()) return prefs.weatherLatitude.toDouble() to prefs.weatherLongitude.toDouble()
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val known = manager.getProviders(true).mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        // None kept: ask for one (this runs in the background)
        val location = known ?: runCatching { Places.locate(context) }.getOrNull()
        return location?.let { it.latitude to it.longitude }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    const val NAME = "Weather"
    private val COLOR = 0xFFFFCA28.toInt()
    private val LABELS = listOf("Grey", "1–3h sun", "3–6h sun", "6–9h sun", "9h+ sun")
    private const val FIELDS = "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,sunshine_duration,daylight_duration"
    private const val PREFS = "weather_days"
    private const val DAYS = "days"
    private const val LAST = "last"
    private const val ARCHIVED = "archived"
    private const val REFRESH_MS = 6 * 3_600_000L
    const val RAINY_MM = 1f
}
