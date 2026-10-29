package app.olauncher.helper

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import app.olauncher.R
import app.olauncher.data.Prefs
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** Today's forecast from Open-Meteo (free, no API key), for the home screen. */
object Weather {

    private const val LOCATION_TIMEOUT_MS = 10_000L

    fun hasLocationPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isEnabled(context: Context) = Prefs(context).weatherOn && hasLocationPermission(context)

    /** One day's forecast. [code] is a WMO weather code, temperatures are in the unit requested. */
    data class Forecast(val code: Int, val max: Int, val min: Int, val uv: Int) {
        fun text(context: Context) = "$max° / $min°\n${context.getString(R.string.uv_index, uv)}"

        fun conditionName(context: Context) = context.getString(
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

        fun toCache() = "$code,$max,$min,$uv"

        companion object {
            fun fromCache(value: String): Forecast? {
                val parts = value.split(",").mapNotNull { it.toIntOrNull() }
                return if (parts.size == 4) Forecast(parts[0], parts[1], parts[2], parts[3]) else null
            }
        }
    }

    /** Cached forecast if it's for today, else null. */
    fun cached(context: Context): Forecast? {
        val prefs = Prefs(context)
        if (prefs.weatherDate != LocalDate.now().toString()) return null
        return Forecast.fromCache(prefs.weatherData)
    }

    /** Network call, run off the main thread. Returns null on failure. */
    suspend fun fetch(context: Context): Forecast? {
        val prefs = Prefs(context)
        val location = currentLocation(context)
        if (location != null) {
            prefs.weatherLatitude = location.latitude.toFloat()
            prefs.weatherLongitude = location.longitude.toFloat()
        }
        // Fall back to where we last were, e.g. location services briefly off
        if (prefs.weatherLatitude.isNaN()) return null

        val fahrenheit = Locale.getDefault().country in setOf("US", "LR", "MM")
        val url = URL(
            "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=${prefs.weatherLatitude}&longitude=${prefs.weatherLongitude}" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,uv_index_max" +
                    "&timezone=auto&forecast_days=1" +
                    if (fahrenheit) "&temperature_unit=fahrenheit" else ""
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

        val daily = JSONObject(body).getJSONObject("daily")
        val forecast = Forecast(
            code = daily.getJSONArray("weather_code").getInt(0),
            max = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
            min = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
            uv = daily.getJSONArray("uv_index_max").optDouble(0, 0.0).roundToInt(),
        )
        prefs.weatherData = forecast.toCache()
        prefs.weatherDate = LocalDate.now().toString()
        prefs.weatherLastUpdated = System.currentTimeMillis()
        return forecast
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
