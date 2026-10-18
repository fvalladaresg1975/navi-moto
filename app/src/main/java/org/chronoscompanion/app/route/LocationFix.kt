package org.chronoscompanion.app.route

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * One good position to start a route from. Unlike WeatherFetcher's single fix - which
 * keeps the FIRST answer, fine for a forecast - this listens to GPS and network together
 * for a few seconds and keeps the MOST ACCURATE one: the network fix usually lands first
 * and can be 100+ m off, which would start the route on the wrong street.
 *
 * Returns early once a fix is good enough; on timeout returns the best seen, else a
 * recent last-known fix, else null. Callback on the main thread. Caller must already
 * hold ACCESS_FINE_LOCATION.
 */
class LocationFix(private val context: Context) {

    companion object {
        const val GOOD_ENOUGH_M = 25f
        const val TIMEOUT_MS = 15000L
        private const val LAST_KNOWN_MAX_AGE_MS = 2 * 60 * 1000L
    }

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var best: Location? = null
    private var done = false
    private var onResult: ((Location?) -> Unit)? = null
    /** Progress callback so the UI can show "precisión 40 m" while it waits. */
    var onProgress: ((Location) -> Unit)? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val b = best
            if (b == null || location.accuracy < b.accuracy) {
                best = location
                onProgress?.invoke(location)
            }
            if (location.accuracy <= GOOD_ENOUGH_M) finish()
        }
        // Explicit overrides: these only gained defaults in later API levels; minSdk is 26.
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    fun start(onResult: (Location?) -> Unit) {
        this.onResult = onResult
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            finish()
            return
        }
        providers.forEach { p ->
            runCatching { lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper()) }
        }
        handler.postDelayed({ finish() }, TIMEOUT_MS)
    }

    fun cancel() {
        done = true
        onResult = null
        runCatching { lm.removeUpdates(listener) }
        handler.removeCallbacksAndMessages(null)
    }

    @SuppressLint("MissingPermission")
    private fun finish() {
        if (done) return
        done = true
        runCatching { lm.removeUpdates(listener) }
        handler.removeCallbacksAndMessages(null)
        val result = best ?: listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { ageMs(it) < LAST_KNOWN_MAX_AGE_MS }
            .minByOrNull { it.accuracy }
        onResult?.invoke(result)
        onResult = null
    }

    private fun ageMs(l: Location) =
        (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
}
