package org.chronoscompanion.app.route

import org.chronoscompanion.app.ble.ChronosProtocol
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A road in local metric coordinates (x = east, y = north, metres) with cumulative
 * arclength, plus the per-frame projection the watch's route view is drawn from.
 *
 * Shared by the phase-1 demo (a made-up road) and real routes (the OSRM polyline), so the
 * geometry the user approved on the demo is exactly the geometry real routes use.
 * Vertex spacing may be irregular - OSRM returns 22 m apart in town and 2.6 km apart on a
 * straight highway - so lookups binary-search the arclength instead of assuming a step.
 */
class Polyline(private val xs: DoubleArray, private val ys: DoubleArray) {

    companion object {
        /** Watch pixels per metre, zoomed out (open road; also the demo's fixed zoom). 0.6
         * puts ~470 m of road between the rider and the top edge. */
        const val SCALE_PX_PER_M = 0.6
        /** Zoomed in, for town speeds: twice as close, ~240 m ahead. Route mode moves
         * between the two with speed (ChronosBleService.updateRouteScale). */
        const val SCALE_MAX_PX_PER_M = 1.2
        /** Road drawn behind / ahead of the rider at [SCALE_PX_PER_M]; both shrink in
         * proportion when zoomed in, so the line always spans the same screen area. */
        const val BEHIND_M = 40.0
        const val AHEAD_M = 480.0
    }

    private val arc = DoubleArray(xs.size)

    init {
        require(xs.size == ys.size && xs.size >= 2) { "a polyline needs at least two points" }
        for (i in 1 until xs.size) {
            arc[i] = arc[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        }
    }

    val lengthM: Double get() = arc.last()

    /** Arclength at vertex [i]: the exact position of a maneuver given by shape index. */
    fun arcAtVertex(i: Int): Double = arc[i.coerceIn(0, arc.size - 1)]

    /** Index i such that arc[i] <= s < arc[i+1]. */
    private fun segmentAt(s: Double): Int {
        var lo = 0
        var hi = arc.size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (arc[mid] <= s) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Closest point of the road to (x, y), searching only arclengths [fromS, toS].
     * Returns (arclength, lateral distance in metres), or null if the window is empty.
     * The window is what keeps a GPS fix from snapping onto a different part of the same
     * route that happens to pass nearby (the way back on an out-and-back road, a hairpin
     * on the mountain section) - only road the rider can have reached is considered. */
    fun project(x: Double, y: Double, fromS: Double, toS: Double): Pair<Double, Double>? {
        val a = fromS.coerceIn(0.0, lengthM)
        val b = toS.coerceIn(0.0, lengthM)
        if (b <= a) return null
        var bestS = a
        var bestD = Double.MAX_VALUE
        var i = segmentAt(a)
        while (i < arc.size - 1 && arc[i] <= b) {
            val x0 = xs[i]; val y0 = ys[i]
            val dx = xs[i + 1] - x0; val dy = ys[i + 1] - y0
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0) (((x - x0) * dx + (y - y0) * dy) / len2).coerceIn(0.0, 1.0) else 0.0
            val d = hypot(x - (x0 + dx * t), y - (y0 + dy * t))
            val sHere = (arc[i] + (arc[i + 1] - arc[i]) * t).coerceIn(a, b)
            if (d < bestD) { bestD = d; bestS = sHere }
            i++
        }
        return Pair(bestS, bestD)
    }

    fun pointAt(s: Double): Pair<Double, Double> {
        val c = s.coerceIn(0.0, lengthM)
        val i = segmentAt(c)
        val len = arc[i + 1] - arc[i]
        val t = if (len > 0) (c - arc[i]) / len else 0.0
        return Pair(xs[i] + (xs[i + 1] - xs[i]) * t, ys[i] + (ys[i + 1] - ys[i]) * t)
    }

    /** Direction of travel at [s], radians clockwise from north. Taken from the road, not
     * from the GPS course: the road is smooth and is right even when stopped, while GPS
     * course is noise below walking pace. */
    fun headingAt(s: Double): Double {
        val (ax, ay) = pointAt(s - 5)
        val (bx, by) = pointAt(s + 5)
        return atan2(bx - ax, by - ay)
    }

    /**
     * Rider-relative watch-pixel polyline for a rider at arclength [s]: heading-up,
     * resampled to ChronosProtocol.ROUTE_MAX_POINTS evenly along the visible stretch.
     * Even resampling (rather than keeping OSRM's vertices) bounds the packet size
     * whatever the source density, and on a 2.6 km straight it costs nothing visible.
     */
    fun frameAt(s: Double, scale: Double = SCALE_PX_PER_M): List<Pair<Int, Int>> {
        val (px, py) = pointAt(s)
        val h = headingAt(s)
        val sinH = sin(h)
        val cosH = cos(h)
        val zoom = SCALE_PX_PER_M / scale
        val from = (s - BEHIND_M * zoom).coerceAtLeast(0.0)
        val to = (s + AHEAD_M * zoom).coerceAtMost(lengthM)
        val n = ChronosProtocol.ROUTE_MAX_POINTS
        val out = ArrayList<Pair<Int, Int>>(n)
        for (k in 0 until n) {
            val (x, y) = pointAt(from + (to - from) * k / (n - 1))
            val dx = x - px
            val dy = y - py
            val forward = dx * sinH + dy * cosH
            val right = dx * cosH - dy * sinH
            out.add(Pair((right * scale).roundToInt(), (-forward * scale).roundToInt()))
        }
        return out
    }
}
