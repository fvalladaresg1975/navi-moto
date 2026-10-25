package org.chronoscompanion.app.weather

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.util.Log
import org.chronoscompanion.app.ble.ChronosProtocol
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Fetches current weather + a 7-day forecast from Open-Meteo (free, no API key) for the
 * phone's last known location, and maps it to the fields the watch's existing weather
 * screen expects (see ChronosProtocol's weather builders).
 */
object WeatherFetcher {
    private const val TAG = "WeatherFetcher"

    data class Result(
        val city: String,
        val days: List<ChronosProtocol.WeatherDay>, // index 0 = today
        val uvIndex: Int,
        val pressureHpa: Int,
        val hourly: List<ChronosProtocol.HourlyData>, // today's 24 hours, index = hour of day
        /** Chance of rain (%) by hour of day for the next 24 hours, -1 = unknown. For the
         * watch's "Clima de ruta" face (0xC9); the library's protocol has no rain. */
        val rainByHour: IntArray = IntArray(24) { -1 },
        /** Today's sunrise / sunset, minutes after midnight local time; -1 = unknown. */
        val sunriseMin: Int = -1,
        val sunsetMin: Int = -1,
    )

    private const val PREFS_NAME = "weather_prefs"
    private const val KEY_LAST_LAT = "last_lat"
    private const val KEY_LAST_LON = "last_lon"

    /**
     * Best effort location, in descending order of freshness:
     *   1. Android's cached last-known fix.
     *   2. An actively requested one-shot fix (blocking, so call from a worker thread).
     *   3. The last coordinates we ever successfully used, remembered on disk.
     *
     * Step 2 exists because step 1 alone is not reliable: this app never asked for a
     * position, it only read whatever some other app happened to leave cached, and
     * Android refuses to hand back fixes that have gone stale. On a phone where nothing
     * else requested location for a few days, getLastKnownLocation() returned null and
     * weather silently stopped working entirely ("sendWeather: no last known location
     * available"). Step 3 then guarantees that a temporary failure never leaves the watch
     * with no weather at all - yesterday's city is far better than nothing.
     */
    fun resolveLocation(context: Context): Location? {
        getLastLocation(context)?.let {
            rememberLocation(context, it)
            return it
        }
        Log.i(TAG, "No cached location, requesting a fresh fix")
        requestSingleFix(context)?.let {
            rememberLocation(context, it)
            return it
        }
        val remembered = rememberedLocation(context)
        if (remembered != null) {
            Log.w(TAG, "Falling back to the last location we used")
        }
        return remembered
    }

    @SuppressLint("MissingPermission")
    fun getLastLocation(context: Context): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try {
                if (lm.isProviderEnabled(provider)) {
                    lm.getLastKnownLocation(provider)?.let { return it }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "No permission to read location from $provider")
            } catch (e: IllegalArgumentException) {
                // provider not supported on this device
            }
        }
        return null
    }

    /**
     * Asks for one position and waits up to [timeoutMs] for it. BLOCKS - only call from a
     * background thread (sendWeather already runs on one). NETWORK first: it works
     * indoors, resolves in seconds and is satisfied by ACCESS_COARSE_LOCATION, which is
     * the only location permission this app holds on modern Android.
     */
    @SuppressLint("MissingPermission")
    private fun requestSingleFix(context: Context, timeoutMs: Long = 25000): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter {
                try {
                    lm.isProviderEnabled(it)
                } catch (e: Exception) {
                    false
                }
            }
        if (providers.isEmpty()) {
            Log.w(TAG, "No location provider is enabled")
            return null
        }

        val latch = java.util.concurrent.CountDownLatch(1)
        val found = java.util.concurrent.atomic.AtomicReference<Location?>(null)
        // Explicit object rather than a lambda: onStatusChanged and friends only gained
        // default implementations in newer API levels, and this app's minSdk is 26.
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: Location) {
                found.set(location)
                latch.countDown()
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        val looper = android.os.Looper.getMainLooper()
        val handler = android.os.Handler(looper)
        handler.post {
            providers.forEach { p ->
                try {
                    lm.requestLocationUpdates(p, 0L, 0f, listener, looper)
                } catch (e: SecurityException) {
                    Log.w(TAG, "Not allowed to request updates from $p")
                } catch (e: IllegalArgumentException) {
                    // provider vanished between the check above and here
                }
            }
        }

        val got = try {
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }
        handler.post {
            try {
                lm.removeUpdates(listener)
            } catch (e: SecurityException) {
                // nothing to clean up
            }
        }
        if (!got) {
            Log.w(TAG, "Timed out waiting for a location fix")
        }
        return found.get()
    }

    private fun rememberLocation(context: Context, location: Location) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_LAST_LAT, location.latitude.toFloat())
            .putFloat(KEY_LAST_LON, location.longitude.toFloat())
            .apply()
    }

    private fun rememberedLocation(context: Context): Location? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_LAST_LAT)) return null
        return Location("remembered").apply {
            latitude = prefs.getFloat(KEY_LAST_LAT, 0f).toDouble()
            longitude = prefs.getFloat(KEY_LAST_LON, 0f).toDouble()
        }
    }

    private fun cityName(context: Context, location: Location): String {
        return try {
            @Suppress("DEPRECATION")
            val addresses = Geocoder(context, Locale.getDefault()).getFromLocation(location.latitude, location.longitude, 1)
            addresses?.firstOrNull()?.locality
                ?: addresses?.firstOrNull()?.subAdminArea
                ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "Reverse geocoding failed: ${e.message}")
            ""
        }
    }

    /** Open-Meteo weather_code (WMO) -> firmware icon 0-7, see ChronosProtocol.buildWeatherDaily. */
    private fun iconForWmoCode(code: Int): Int = when (code) {
        0, 1 -> 1
        2 -> 0
        3 -> 4
        45, 48 -> 6
        51, 53, 55, 56, 57 -> 7
        61, 63, 65, 66, 67 -> 3
        71, 73, 75, 77 -> 2
        80, 81, 82 -> 7
        85, 86 -> 2
        95, 96, 99 -> 5
        else -> 4
    }

    /** Runs a blocking network call - callers must invoke this off the main thread. */
    fun fetch(context: Context, location: Location): Result? {
        return try {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast?latitude=${location.latitude}&longitude=${location.longitude}" +
                    "&current=temperature_2m,weather_code,pressure_msl" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,uv_index_max,sunrise,sunset" +
                    "&hourly=temperature_2m,weather_code,wind_speed_10m,relative_humidity_2m,uv_index,precipitation_probability" +
                    "&forecast_days=7&forecast_hours=24&timezone=auto"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.requestMethod = "GET"
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(body)
            val current = json.getJSONObject("current")
            val currentTemp = current.getDouble("temperature_2m").roundToInt()
            val currentIcon = iconForWmoCode(current.getInt("weather_code"))
            val pressure = current.optDouble("pressure_msl", 1013.0).roundToInt()

            val daily = json.getJSONObject("daily")
            val codes = daily.getJSONArray("weather_code")
            val highs = daily.getJSONArray("temperature_2m_max")
            val lows = daily.getJSONArray("temperature_2m_min")
            val uvArr = daily.getJSONArray("uv_index_max")
            val dayCount = minOf(7, codes.length())

            val days = (0 until dayCount).map { i ->
                val high = highs.getDouble(i).roundToInt()
                val low = lows.getDouble(i).roundToInt()
                if (i == 0) {
                    // today: show the actual current temp/condition, not the daily summary
                    ChronosProtocol.WeatherDay(icon = currentIcon, temp = currentTemp, high = high, low = low)
                } else {
                    ChronosProtocol.WeatherDay(icon = iconForWmoCode(codes.getInt(i)), temp = (high + low) / 2, high = high, low = low)
                }
            }
            val uvToday = uvArr.optDouble(0, 0.0).roundToInt()

            val hourly = json.optJSONObject("hourly")?.let { h ->
                val times = h.getJSONArray("time")
                val temps = h.getJSONArray("temperature_2m")
                val hcodes = h.getJSONArray("weather_code")
                val winds = h.getJSONArray("wind_speed_10m")
                val humidity = h.getJSONArray("relative_humidity_2m")
                val uv = h.getJSONArray("uv_index")
                (0 until times.length()).map { i ->
                    // "time" is e.g. "2026-08-05T14:00" - hour-of-day is characters 11-12.
                    val hourOfDay = times.getString(i).substring(11, 13).toInt()
                    ChronosProtocol.HourlyData(
                        hour = hourOfDay,
                        icon = iconForWmoCode(hcodes.getInt(i)),
                        temp = temps.getDouble(i).roundToInt(),
                        windKmh = winds.getDouble(i).roundToInt(),
                        humidity = humidity.getInt(i),
                        uv = uv.optDouble(i, 0.0).roundToInt()
                    )
                }
            } ?: emptyList()

            // Chance of rain by hour of day, from the same 24 hourly rows (the next 24 h).
            val rainByHour = IntArray(24) { -1 }
            json.optJSONObject("hourly")?.let { h ->
                val times = h.getJSONArray("time")
                val rain = h.optJSONArray("precipitation_probability")
                if (rain != null) for (i in 0 until times.length()) {
                    if (rain.isNull(i)) continue
                    rainByHour[times.getString(i).substring(11, 13).toInt()] = rain.optInt(i, -1)
                }
            }
            // "2026-10-25T07:12" -> minutes after midnight (timezone=auto: already local)
            fun minutesOf(t: String?): Int =
                if (t == null || t.length < 16) -1 else t.substring(11, 13).toInt() * 60 + t.substring(14, 16).toInt()
            val sunrise = minutesOf(daily.optJSONArray("sunrise")?.optString(0))
            val sunset = minutesOf(daily.optJSONArray("sunset")?.optString(0))

            Result(city = cityName(context, location), days = days, uvIndex = uvToday, pressureHpa = pressure,
                hourly = hourly, rainByHour = rainByHour, sunriseMin = sunrise, sunsetMin = sunset)
        } catch (e: Exception) {
            Log.e(TAG, "Weather fetch failed: ${e.message}")
            null
        }
    }
}
