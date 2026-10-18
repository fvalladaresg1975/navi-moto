package org.chronoscompanion.app.route

import android.content.Context
import android.location.Location
import android.util.Log
import java.io.BufferedWriter
import java.io.File

/**
 * Follows the rider along a [Route]: each GPS fix becomes an arclength [s] on the road.
 *
 * The rider is always drawn ON the line (at the snapped point), Beeline-style, with the
 * road's own heading - so GPS noise moves the marker along the road, never sideways off
 * it, and a stop at a light doesn't spin the view.
 *
 * Off-route and wrong-way are only DETECTED here; deciding to reroute (and when) is the
 * service's job (ChronosBleService.maybeReroute). While off route, [s] holds its last
 * on-route value.
 */
class RouteTracker(private val route: Route, context: Context, appendTrace: Boolean = false) {

    companion object {
        private const val TAG = "ChronosRoute"
        /** Beyond this from the road, a fix is not trusted to place the rider on it. */
        private const val MAX_LATERAL_M = 60.0
        const val TRACE_FILE = "route_trace.csv"
    }

    var s = 0.0
        private set
    var lateralM = 0.0
        private set
    var onRoute = true
        private set
    /** On the road but heading against it (a U-turn). Lateral distance can't see this -
     * the rider is still on the line - so it is judged by course vs road direction. */
    var wrongWay = false
        private set
    private var firstFix = true
    private var lastFixMs = 0L
    private var trace: BufferedWriter? = null

    init {
        // One file, overwritten per route: enough to analyse the last ride, never grows.
        // Lives in app-private storage; pulled with `adb exec-out run-as ... cat`.
        // A reroute APPENDS to the same file, so one ride stays one trace across reroutes.
        trace = runCatching {
            java.io.FileWriter(File(context.filesDir, TRACE_FILE), appendTrace).buffered().apply {
                write("# ${if (appendTrace) "REROUTE" else "route"} " +
                    "${"%.1f".format(java.util.Locale.US, route.distanceM / 1000)} km via ${route.engine}, " +
                    "${route.maneuvers.size} maneuvers, start snap ${route.startSnapM.toInt()} m\n")
                if (!appendTrace) write("t_ms,lat,lon,acc_m,speed_mps,s_m,lateral_m,on_route,next_m,next,event\n")
            }
        }.onFailure { Log.w(TAG, "No trace file", it) }.getOrNull()
    }

    /** Feed one fix. Returns true if [s] moved (i.e. the watch needs a fresh frame). */
    fun update(fix: Location): Boolean {
        val (x, y) = route.toLocal(fix.latitude, fix.longitude)
        val speed = if (fix.hasSpeed()) fix.speed.toDouble() else 0.0
        val dtS = if (lastFixMs == 0L) 1.0 else ((fix.time - lastFixMs) / 1000.0).coerceIn(0.5, 30.0)
        lastFixMs = fix.time

        // Search only road the rider can have reached since the last fix (plus slack for
        // GPS error and a missed fix or two). The first fix searches the opening stretch,
        // since the rider may have rolled a bit between "Iniciar" and the first fix.
        // After a gap (tunnel) s was dead-reckoned and may be slightly ahead of the truth;
        // look further back so the returning fix can still be matched.
        val from = if (firstFix) 0.0 else s - (if (dtS > 3) 250 else 50)
        val to = if (firstFix) 1500.0 else s + 200 + speed * dtS * 2
        val hit = route.road.project(x, y, from, to)
        val before = s
        val threshold = maxOf(MAX_LATERAL_M, fix.accuracy + 30.0)
        if (hit != null && hit.second <= threshold) {
            val (newS, lat) = hit
            lateralM = lat
            onRoute = true
            // Only forward: along a route the rider never legitimately moves backwards, so
            // a smaller s is GPS jitter around a stop and would make the ring and distance
            // wobble at every red light. (A real U-turn is off-route - phase 3's reroute.)
            if (firstFix || newS > s) s = newS
            firstFix = false
        } else {
            lateralM = hit?.second ?: Double.NaN
            onRoute = false
        }

        // Wrong way: moving (> ~11 km/h, so course means something) and pointing more than
        // 120 deg away from the road's direction at s.
        wrongWay = onRoute && fix.hasBearing() && speed > 3 && run {
            val road = Math.toDegrees(route.road.headingAt(s))
            val d = Math.abs(((fix.bearing - road) % 360 + 540) % 360 - 180)
            d > 120
        }

        writeTrace(fix.time, fix.latitude, fix.longitude, fix.accuracy.toDouble(), speed,
            if (wrongWay) "wrong_way" else "")
        Log.i(TAG, "fix acc=%.0f s=%.0f lat=%.0f on=%b".format(java.util.Locale.US, fix.accuracy, s, lateralM, onRoute))
        return s != before
    }

    /** No fix (tunnel): advance along the road by an estimate. Never past the end. */
    fun deadReckon(meters: Double, nowMs: Long) {
        s = minOf(s + meters, route.road.lengthM)
        writeTrace(nowMs, Double.NaN, Double.NaN, Double.NaN, Double.NaN, "dead_reckoning")
    }

    private fun writeTrace(t: Long, lat: Double, lon: Double, acc: Double, speed: Double, event: String) {
        val next = route.nextManeuver(s)
        trace?.runCatching {
            write("%d,%.6f,%.6f,%.0f,%.1f,%.1f,%.1f,%d,%.0f,%s,%s\n".format(java.util.Locale.US,
                t, lat, lon, acc, speed, s, lateralM, if (onRoute) 1 else 0,
                (next?.atM ?: route.road.lengthM) - s,
                (next?.text ?: "llegada").replace(',', ' '), event))
            flush()
        }
    }

    fun close() {
        runCatching { trace?.close() }
        trace = null
    }
}
