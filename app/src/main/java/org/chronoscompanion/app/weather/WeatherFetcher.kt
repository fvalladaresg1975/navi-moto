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
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

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

    /**
     * Runs blocking network calls - callers must invoke this off the main thread.
     * Open-Meteo first; if it fails (down, timeout, bad answer), MET Norway as a backup.
     */
    fun fetch(context: Context, location: Location): Result? {
        fetchOpenMeteo(context, location)?.let { return it }
        Log.w(TAG, "Open-Meteo failed, trying MET Norway")
        return fetchMetNo(context, location)?.also {
            Log.i(TAG, "Weather from MET Norway (backup)")
        }
    }

    private fun fetchOpenMeteo(context: Context, location: Location): Result? {
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

    // ---------------------------------------------------------------------------------
    // Backup source: MET Norway (api.met.no), free and keyless, worldwide. Its terms ask
    // every client to identify itself in the User-Agent with a way to reach the author.
    // ---------------------------------------------------------------------------------

    private const val MET_USER_AGENT = "NaviMoto/2.5 (+https://github.com/fvalladaresg1975/navi-moto)"

    /** MET Norway symbol_code (e.g. "lightrainshowers_day") -> firmware icon 0-7. */
    private fun iconForMetSymbol(symbol: String?): Int {
        val s = symbol?.substringBefore('_') ?: return 4
        return when {
            "thunder" in s -> 5
            "snow" in s || "sleet" in s -> 2
            "showers" in s || s == "lightrain" -> 7
            "rain" in s -> 3
            s == "fog" -> 6
            s == "clearsky" || s == "fair" -> 1
            s == "partlycloudy" -> 0
            else -> 4
        }
    }

    private fun org.json.JSONObject.symbol(): String? =
        (optJSONObject("next_1_hours") ?: optJSONObject("next_6_hours") ?: optJSONObject("next_12_hours"))
            ?.optJSONObject("summary")?.optString("symbol_code")

    /**
     * Chance of rain for one hour. MET gives probability_of_precipitation only in some
     * regions (not Chile); elsewhere it is estimated from the expected millimetres so the
     * "Clima de ruta" face still shows when rain is coming.
     */
    private fun rainChance(next1h: org.json.JSONObject?): Int {
        val d = next1h?.optJSONObject("details") ?: return -1
        if (d.has("probability_of_precipitation")) return d.getDouble("probability_of_precipitation").roundToInt()
        if (!d.has("precipitation_amount")) return -1
        val mm = d.getDouble("precipitation_amount")
        return when {
            mm < 0.1 -> 0
            mm < 0.5 -> 40
            mm < 2.0 -> 70
            else -> 90
        }
    }

    private fun fetchMetNo(context: Context, location: Location): Result? {
        return try {
            // MET asks for at most 4 decimals (better caching on their side).
            val url = URL(String.format(Locale.US,
                "https://api.met.no/weatherapi/locationforecast/2.0/complete?lat=%.4f&lon=%.4f",
                location.latitude, location.longitude))
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", MET_USER_AGENT)
            if (conn.responseCode != 200) {
                Log.e(TAG, "MET Norway answered HTTP ${conn.responseCode}")
                conn.disconnect()
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val series = JSONObject(body).getJSONObject("properties").getJSONArray("timeseries")
            if (series.length() == 0) return null
            val zone = java.time.ZoneId.systemDefault()
            data class Step(val at: java.time.ZonedDateTime, val data: JSONObject) {
                val details: JSONObject get() = data.getJSONObject("instant").getJSONObject("details")
            }
            val steps = (0 until series.length()).map { i ->
                val o = series.getJSONObject(i)
                Step(java.time.Instant.parse(o.getString("time")).atZone(zone), o.getJSONObject("data"))
            }

            val now = steps.first()
            val currentTemp = now.details.getDouble("air_temperature").roundToInt()
            val currentIcon = iconForMetSymbol(now.data.symbol())
            val pressure = now.details.optDouble("air_pressure_at_sea_level", 1013.0).roundToInt()

            // Next 24 hours (the first ~2.5 days come hour by hour).
            val next24 = steps.take(24).filter { it.data.has("next_1_hours") }
            val hourly = next24.map {
                ChronosProtocol.HourlyData(
                    hour = it.at.hour,
                    icon = iconForMetSymbol(it.data.symbol()),
                    temp = it.details.getDouble("air_temperature").roundToInt(),
                    windKmh = (it.details.optDouble("wind_speed", 0.0) * 3.6).roundToInt(),
                    humidity = it.details.optDouble("relative_humidity", 0.0).roundToInt(),
                    uv = it.details.optDouble("ultraviolet_index_clear_sky", 0.0).roundToInt(),
                )
            }
            val rainByHour = IntArray(24) { -1 }
            next24.forEach { rainByHour[it.at.hour] = rainChance(it.data.optJSONObject("next_1_hours")) }

            // Days: highs/lows from every step of that local date, plus the 6-hour max/min.
            val byDate = steps.groupBy { it.at.toLocalDate() }
            val today = now.at.toLocalDate()
            val days = (0 until 7).mapNotNull { d ->
                val date = today.plusDays(d.toLong())
                val list = byDate[date] ?: return@mapNotNull null
                val temps = list.flatMap { s ->
                    val six = s.data.optJSONObject("next_6_hours")?.optJSONObject("details")
                    listOfNotNull(
                        s.details.optDouble("air_temperature").takeIf { !it.isNaN() },
                        six?.optDouble("air_temperature_max")?.takeIf { !it.isNaN() },
                        six?.optDouble("air_temperature_min")?.takeIf { !it.isNaN() },
                    )
                }
                if (temps.isEmpty()) return@mapNotNull null
                val high = temps.max().roundToInt()
                val low = temps.min().roundToInt()
                if (d == 0) {
                    ChronosProtocol.WeatherDay(icon = currentIcon, temp = currentTemp,
                        high = maxOf(high, currentTemp), low = minOf(low, currentTemp))
                } else {
                    val noon = list.minBy { kotlin.math.abs(it.at.hour - 12) }
                    ChronosProtocol.WeatherDay(icon = iconForMetSymbol(noon.data.symbol()),
                        temp = (high + low) / 2, high = high, low = low)
                }
            }
            if (days.isEmpty()) return null
            val uvToday = (byDate[today] ?: listOf(now))
                .maxOf { it.details.optDouble("ultraviolet_index_clear_sky", 0.0) }.roundToInt()

            val (sunrise, sunset) = sunTimes(location.latitude, location.longitude, today, zone)
            Result(city = cityName(context, location), days = days, uvIndex = uvToday, pressureHpa = pressure,
                hourly = hourly, rainByHour = rainByHour, sunriseMin = sunrise, sunsetMin = sunset)
        } catch (e: Exception) {
            Log.e(TAG, "MET Norway fetch failed: ${e.message}")
            null
        }
    }

    /**
     * Sunrise and sunset in local minutes after midnight (NOAA approximation, about a
     * minute off), so the backup needs no second request. -1 when the sun doesn't rise
     * or set that day.
     */
    private fun sunTimes(lat: Double, lon: Double, date: java.time.LocalDate, zone: java.time.ZoneId): Pair<Int, Int> {
        val g = 2 * Math.PI / 365.0 * (date.dayOfYear - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) -
            0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) +
            0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val latR = Math.toRadians(lat)
        val arg = cos(Math.toRadians(90.833)) / (cos(latR) * cos(decl)) - tan(latR) * tan(decl)
        if (arg < -1 || arg > 1) return -1 to -1
        val ha = Math.toDegrees(acos(arg))
        val offsetMin = zone.rules.getOffset(date.atTime(12, 0)).totalSeconds / 60
        fun local(utcMin: Double) = (((utcMin + offsetMin).roundToInt() % 1440) + 1440) % 1440
        return local(720 - 4 * (lon + ha) - eqTime) to local(720 - 4 * (lon - ha) - eqTime)
    }
}
