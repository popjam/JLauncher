package app.olauncher.helper

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.location.LocationManagerCompat
import androidx.core.text.util.LocalePreferences
import app.olauncher.R
import app.olauncher.data.Prefs
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** Forecasts from Open-Meteo (free, no API key), for the home screen and the weather screen. */
object Weather {

    private const val LOCATION_TIMEOUT_MS = 10_000L

    // Added to Open-Meteo's response before it's cached
    private const val KEY_FAHRENHEIT = "jlauncher_fahrenheit"
    private const val KEY_FETCHED_AT = "jlauncher_fetched_at"

    fun hasLocationPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isEnabled(context: Context) = Prefs(context).weatherOn && hasLocationPermission(context)

    /** [code]s are WMO weather codes; temperatures, rain and wind are in the units the forecast was asked for. */
    data class Day(
        val date: LocalDate,
        val code: Int,
        val max: Int,
        val min: Int,
        val uv: Int,
        val rainChance: Int,
        val rain: Double,
        val windMax: Int,
        val sunrise: LocalTime?,
        val sunset: LocalTime?,
    )

    data class Hour(
        val time: LocalDateTime,
        val code: Int,
        val temp: Int,
        val feelsLike: Int,
        val rainChance: Int,
        val uv: Int,
        val isDay: Boolean,
    )

    /** [place] is the suburb or town the forecast is for, if it could be looked up. */
    data class Forecast(
        val fahrenheit: Boolean,
        val fetchedAt: Long,
        val days: List<Day>,
        val hours: List<Hour>,
        val place: String = "",
    ) {
        val today: Day? get() = days.firstOrNull { it.date == LocalDate.now() }

        fun upcomingDays() = days.filter { it.date >= LocalDate.now() }

        /** From the start of this hour, so the first is "now". */
        fun upcomingHours(count: Int = 24): List<Hour> {
            val thisHour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
            return hours.filter { it.time >= thisHour }.take(count)
        }

        val windUnit get() = if (fahrenheit) "mph" else "km/h"

        fun rainText(amount: Double) =
            if (fahrenheit) String.format(Locale.getDefault(), "%.2f in", amount)
            else String.format(Locale.getDefault(), "%.1f mm", amount)
    }

    /** What the home screen shows under the icon: today's high and the UV index now. */
    fun homeText(day: Day, uv: Int) = "${day.max}° / $uv"

    /** Colours from cold to hot, at temperatures in °C */
    private val TEMP_COLORS = listOf(
        0f to 0xFF2F4FC4.toInt(),  // dark blue
        10f to 0xFF4A9BE8.toInt(), // light blue
        17f to 0xFF56C7A8.toInt(), // teal
        22f to 0xFFF2C84B.toInt(), // yellow
        28f to 0xFFF2843A.toInt(), // orange
        34f to 0xFFE53935.toInt(), // red
    )

    /** Dark blue for cold through to red for hot. */
    fun tempColor(temp: Int, fahrenheit: Boolean): Int {
        val celsius = if (fahrenheit) (temp - 32) * 5 / 9f else temp.toFloat()
        val upper = TEMP_COLORS.indexOfFirst { celsius <= it.first }
        return when (upper) {
            0 -> TEMP_COLORS.first().second
            -1 -> TEMP_COLORS.last().second
            else -> {
                val (fromTemp, fromColor) = TEMP_COLORS[upper - 1]
                val (toTemp, toColor) = TEMP_COLORS[upper]
                ColorUtils.blendARGB(fromColor, toColor, (celsius - fromTemp) / (toTemp - fromTemp))
            }
        }
    }

    fun conditionName(context: Context, code: Int) = context.getString(
        when (code) {
            0 -> R.string.weather_clear
            1 -> R.string.weather_mostly_clear
            2 -> R.string.weather_partly_cloudy
            3 -> R.string.weather_cloudy
            45, 48 -> R.string.weather_fog
            in 51..57 -> R.string.weather_drizzle
            in 61..67 -> R.string.weather_rain
            in 71..77, 85, 86 -> R.string.weather_snow
            in 80..82 -> R.string.weather_showers
            in 95..99 -> R.string.weather_storms
            else -> R.string.weather_cloudy
        }
    )

    /** A day's weather as a sentence for the mood tracker's day summary. */
    fun pastSentence(context: Context, code: Int) = context.getString(
        when (code) {
            0, 1 -> R.string.weather_was_clear
            2 -> R.string.weather_was_partly_cloudy
            3 -> R.string.weather_was_cloudy
            45, 48 -> R.string.weather_was_fog
            in 51..57 -> R.string.weather_was_drizzle
            61, 66, 80 -> R.string.weather_was_light_rain
            63 -> R.string.weather_was_rain
            65, 67, 82 -> R.string.weather_was_pouring
            81 -> R.string.weather_was_showers
            in 71..77, 85, 86 -> R.string.weather_was_snow
            in 95..99 -> R.string.weather_was_storms
            else -> R.string.weather_was_cloudy
        }
    )

    fun uvLevel(context: Context, uv: Int) = context.getString(
        when {
            uv <= 2 -> R.string.uv_low
            uv <= 5 -> R.string.uv_moderate
            uv <= 7 -> R.string.uv_high
            uv <= 10 -> R.string.uv_very_high
            else -> R.string.uv_extreme
        }
    )

    /** The last forecast fetched, if it still covers today and is in the unit wanted now. */
    fun cached(context: Context): Forecast? {
        val prefs = Prefs(context)
        val forecast = try {
            parse(prefs.weatherData)
        } catch (e: Exception) {
            null
        } ?: return null
        if (forecast.today == null || forecast.fahrenheit != useFahrenheit(context)) return null
        return forecast.copy(place = prefs.weatherPlace)
    }

    /** Network call, run off the main thread. Returns null on failure. */
    suspend fun fetch(context: Context): Forecast? {
        val prefs = Prefs(context)
        val location = currentLocation(context)
        if (location != null) {
            prefs.weatherLatitude = location.latitude.toFloat()
            prefs.weatherLongitude = location.longitude.toFloat()
            // Keeps the last name found if the lookup fails, e.g. offline
            placeName(context, location)?.let { prefs.weatherPlace = it }
        }
        // Fall back to where we last were, e.g. location services briefly off
        if (prefs.weatherLatitude.isNaN()) return null

        val fahrenheit = useFahrenheit(context)
        val url = URL(
            "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=${prefs.weatherLatitude}&longitude=${prefs.weatherLongitude}" +
                    "&hourly=temperature_2m,apparent_temperature,weather_code,precipitation_probability,uv_index,is_day" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,uv_index_max,precipitation_probability_max," +
                    "precipitation_sum,wind_speed_10m_max,sunrise,sunset" +
                    // Two days of hours, so a cached forecast still has the next 24 hours later on
                    "&timezone=auto&forecast_days=7&forecast_hours=48" +
                    if (fahrenheit) "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch" else ""
        )
        val connection = url.openConnection() as HttpURLConnection
        val body = try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }

        val json = JSONObject(body)
            .put(KEY_FAHRENHEIT, fahrenheit)
            .put(KEY_FETCHED_AT, System.currentTimeMillis())
            .toString()
        val forecast = parse(json)?.copy(place = prefs.weatherPlace) ?: return null
        prefs.weatherData = json
        prefs.weatherLastUpdated = forecast.fetchedAt
        return forecast
    }

    private fun parse(value: String): Forecast? {
        if (value.isBlank()) return null
        val json = JSONObject(value)
        if (!json.has(KEY_FAHRENHEIT)) return null
        val daily = json.getJSONObject("daily")
        val hourly = json.getJSONObject("hourly")

        // Open-Meteo gives null for anything a model doesn't cover
        fun JSONObject.double(name: String, i: Int) =
            optJSONArray(name)?.optDouble(i)?.takeUnless { it.isNaN() } ?: 0.0
        fun JSONObject.int(name: String, i: Int) = double(name, i).roundToInt()
        fun JSONObject.time(name: String, i: Int) =
            optJSONArray(name)?.optString(i)?.takeIf { it.isNotBlank() && it != "null" }
                ?.let { LocalDateTime.parse(it).toLocalTime() }

        val dates = daily.getJSONArray("time")
        val days = (0 until dates.length()).map { i ->
            Day(
                date = LocalDate.parse(dates.getString(i)),
                code = daily.int("weather_code", i),
                max = daily.int("temperature_2m_max", i),
                min = daily.int("temperature_2m_min", i),
                uv = daily.int("uv_index_max", i),
                rainChance = daily.int("precipitation_probability_max", i),
                rain = daily.double("precipitation_sum", i),
                windMax = daily.int("wind_speed_10m_max", i),
                sunrise = daily.time("sunrise", i),
                sunset = daily.time("sunset", i),
            )
        }
        val times = hourly.getJSONArray("time")
        val hours = (0 until times.length()).map { i ->
            Hour(
                time = LocalDateTime.parse(times.getString(i)),
                code = hourly.int("weather_code", i),
                temp = hourly.int("temperature_2m", i),
                feelsLike = hourly.int("apparent_temperature", i),
                rainChance = hourly.int("precipitation_probability", i),
                uv = hourly.int("uv_index", i),
                isDay = hourly.int("is_day", i) == 1,
            )
        }
        return Forecast(json.getBoolean(KEY_FAHRENHEIT), json.optLong(KEY_FETCHED_AT), days, hours)
    }

    /**
     * The temperature unit from Android's regional preferences (Android 14+) if one is picked, else
     * the country of the SIM or mobile network. The phone's language is no guide: plenty of phones
     * outside the US are set to English (United States).
     */
    private fun useFahrenheit(context: Context): Boolean {
        when (LocalePreferences.getTemperatureUnit(false)) {
            LocalePreferences.TemperatureUnit.FAHRENHEIT -> return true
            LocalePreferences.TemperatureUnit.CELSIUS, LocalePreferences.TemperatureUnit.KELVIN -> return false
        }
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val country = telephony?.simCountryIso?.takeIf { it.isNotBlank() }
            ?: telephony?.networkCountryIso?.takeIf { it.isNotBlank() }
            ?: Locale.getDefault().country
        return country.uppercase(Locale.ROOT) in setOf("US", "LR", "MM")
    }

    /** Suburb, else town, from Android's geocoder. Blocking; null if it isn't available or finds nothing. */
    @Suppress("DEPRECATION")
    private fun placeName(context: Context, location: Location): String? {
        if (!Geocoder.isPresent()) return null
        return try {
            Geocoder(context).getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()?.let {
                it.subLocality ?: it.locality ?: it.subAdminArea
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Coarse location is plenty for a forecast: the freshest last-known fix, else ask for one. */
    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(context: Context): Location? {
        if (!hasLocationPermission(context)) return null
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val lastKnown = locationManager.getProviders(true).mapNotNull {
            try {
                locationManager.getLastKnownLocation(it)
            } catch (e: Exception) {
                null
            }
        }.maxByOrNull { it.time }
        // Under three hours old is close enough for the day's weather
        if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < 3 * 60 * 60 * 1000L) return lastKnown

        val provider = when {
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            locationManager.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            else -> return lastKnown
        }
        val fresh = withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val cancellationSignal = CancellationSignal()
                continuation.invokeOnCancellation { cancellationSignal.cancel() }
                try {
                    LocationManagerCompat.getCurrentLocation(
                        locationManager, provider, cancellationSignal, ContextCompat.getMainExecutor(context)
                    ) { location: Location? -> if (continuation.isActive) continuation.resume(location) }
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
        return fresh ?: lastKnown
    }
}
