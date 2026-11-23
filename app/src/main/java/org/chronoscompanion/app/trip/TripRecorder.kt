package org.chronoscompanion.app.trip

import android.content.Context
import android.location.Location
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Records the ride while the app navigates (route of its own, CoMaps or Google Maps) from
 * the GPS fixes the service already receives every second, and at the end saves it with
 * its picture ([TripStore], [TripImage]). The route test (demo) never gets here.
 *
 * Main thread only; saving and drawing run on [TripImage]'s worker.
 */
class TripRecorder(private val ctx: Context) {

    private class Active(val mode: String, var dest: String?) {
        var lat = IntArray(1024)
        var lon = IntArray(1024)
        var n = 0
        var lastStored: Location? = null
        var last: Location? = null
        var anchor: Location? = null
        var firstFixMs = 0L
        var lastFixMs = 0L
        var distanceM = 0.0
        var movingMs = 0L
        var maxMps = 0f
        var prevSpeed = 0f

        fun store(l: Location) {
            if (n == lat.size) { lat = lat.copyOf(n * 2); lon = lon.copyOf(n * 2) }
            lat[n] = (l.latitude * 1e6).roundToInt()
            lon[n] = (l.longitude * 1e6).roundToInt()
            n++
            lastStored = l
        }
    }

    private var active: Active? = null

    val isRecording: Boolean get() = active != null

    /** A navigation began. Same mode already recording (a reroute, CoMaps rebuilding its
     * route): it is the same ride and goes on. */
    fun start(mode: String, dest: String?) {
        val a = active
        if (a != null && a.mode == mode) {
            if (dest != null) a.dest = dest
            return
        }
        finish()
        active = Active(mode, dest)
        Log.i(TAG, "recording ($mode)")
    }

    fun onFix(l: Location) {
        val a = active ?: return
        if (l.hasAccuracy() && l.accuracy > MAX_ACCURACY_M) return
        val prev = a.last
        // Top speed needs two good fixes in a row, so one wild reading doesn't count.
        if (l.hasSpeed() && (!l.hasAccuracy() || l.accuracy <= SPEED_ACCURACY_M)) {
            a.maxMps = max(a.maxMps, min(l.speed, a.prevSpeed))
            a.prevSpeed = l.speed
        } else {
            a.prevSpeed = 0f
        }
        if (prev == null) {
            a.firstFixMs = l.time
            a.lastFixMs = l.time
            a.last = l
            a.store(l)
            return
        }
        val dtMs = l.time - prev.time
        if (dtMs <= 0) return
        val d = prev.distanceTo(l)
        val v = d / (dtMs / 1000f)
        if (v > MAX_JUMP_MPS) return // GPS jump: keep the previous fix
        val moving = v >= MOVING_MPS || (l.hasSpeed() && l.speed >= MOVING_FIX_MPS)
        if (moving) a.movingMs += dtMs
        // Distance in steps of >= DIST_STEP_M: summing every 1 s fix would add the GPS
        // wobble to the ride. Standing still it only counts a real move (no fixes for a
        // while, e.g. a tunnel), not the wander of the fix around one spot.
        val anchor = a.anchor ?: l.also { a.anchor = it }
        val da = anchor.distanceTo(l)
        if (da >= DIST_STEP_M && (moving || da >= SLOW_GAP_M)) {
            a.distanceM += da
            a.anchor = l
        }
        a.last = l
        a.lastFixMs = l.time
        val ls = a.lastStored
        if (ls == null || ls.distanceTo(l) >= STORE_EVERY_M) a.store(l)
    }

    /** The navigation ended (or was replaced). A real ride is saved; a few metres are not. */
    fun finish() {
        val a = active ?: return
        active = null
        a.last?.let { if (a.lastStored !== it) a.store(it) }
        if (a.distanceM < MIN_DISTANCE_M || a.movingMs < MIN_MOVING_MS || a.n < 2) {
            Log.i(TAG, "not saved: ${a.distanceM.roundToInt()} m, ${a.movingMs / 1000} s moving")
            return
        }
        val trip = Trip(
            id = a.firstFixMs,
            startMs = a.firstFixMs,
            endMs = a.lastFixMs,
            mode = a.mode,
            dest = a.dest,
            distanceM = a.distanceM,
            movingS = a.movingMs / 1000,
            maxKmh = a.maxMps * 3.6,
        )
        Log.i(TAG, "saved: ${a.distanceM.roundToInt()} m, ${trip.movingS} s moving, ${a.n} points")
        TripImage.saveNew(ctx.applicationContext, trip, a.lat.copyOf(a.n), a.lon.copyOf(a.n))
    }

    companion object {
        private const val TAG = "TripRecorder"
        private const val PREFS = "trips"
        private const val KEY_TRACE_IMPORTED = "trace_imported"

        /**
         * Once, on the first start of app 2.6: the last route / CoMaps ride's GPS trace
         * (RouteTracker's route_trace.csv, kept for diagnosis) becomes the first saved ride,
         * so the history doesn't start empty. Background thread.
         */
        fun importLastTraceOnce(ctx: Context) {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_TRACE_IMPORTED, false)) return
            prefs.edit().putBoolean(KEY_TRACE_IMPORTED, true).apply()
            if (TripStore.list(ctx).isNotEmpty()) return
            val f = java.io.File(ctx.filesDir, org.chronoscompanion.app.route.RouteTracker.TRACE_FILE)
            if (!f.exists()) return
            Thread {
                runCatching {
                    val rec = TripRecorder(ctx.applicationContext)
                    var started = false
                    f.forEachLine { line ->
                        if (line.startsWith("#")) {
                            if (!started) {
                                rec.start(if ("via comaps" in line) "comaps" else "route", null)
                                started = true
                            }
                            return@forEachLine
                        }
                        val c = line.split(',')
                        if (c.size < 5) return@forEachLine
                        val t = c[0].toLongOrNull() ?: return@forEachLine
                        val lat = c[1].toDoubleOrNull()?.takeIf { !it.isNaN() } ?: return@forEachLine
                        val lon = c[2].toDoubleOrNull()?.takeIf { !it.isNaN() } ?: return@forEachLine
                        rec.onFix(Location("trace").apply {
                            time = t
                            latitude = lat
                            longitude = lon
                            c[3].toFloatOrNull()?.takeIf { !it.isNaN() }?.let { accuracy = it }
                            c[4].toFloatOrNull()?.takeIf { !it.isNaN() }?.let { speed = it }
                        })
                    }
                    rec.finish()
                }.onFailure { Log.w(TAG, "trace import failed", it) }
            }.start()
        }

        private const val MAX_ACCURACY_M = 30f
        private const val SPEED_ACCURACY_M = 20f
        /** ~250 km/h between two fixes: not a motorbike, a GPS jump. */
        private const val MAX_JUMP_MPS = 70f
        private const val MOVING_MPS = 1.0f
        private const val MOVING_FIX_MPS = 1.5f
        private const val DIST_STEP_M = 20f
        private const val SLOW_GAP_M = 50f
        private const val STORE_EVERY_M = 5f
        private const val MIN_DISTANCE_M = 300.0
        private const val MIN_MOVING_MS = 60_000L
    }
}
