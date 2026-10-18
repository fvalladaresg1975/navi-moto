package org.chronoscompanion.app.route

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Demo for showing the system off (the user films it for social media), no GPS or
 * routing needed. Two parts:
 *
 *  1. Route mode through a made-up city (~75 s): a street grid with a roundabout drawn
 *     around the route, right / roundabout / slight-left / left turns and the finish flag,
 *     a speed profile that makes the zoom breathe (slow -> avenue -> slow), an over-speed
 *     stretch (red glow), changing limits (50 / 60 / 30), turn beep and voice.
 *  2. Arrow mode, as with Google Maps (~55 s): scripted turn-by-turn steps with the big
 *     arrow, progress ring, street names, speed and limit.
 *
 * Geometry and icons go through the same Polyline / RouteIcons / StreetMap pieces as real
 * routes, so what is filmed is what the rider gets. Coordinates: x = east, y = north,
 * metres; the street map uses the same frame (origin at the start).
 */
class RouteDemo {

    companion object {
        /** Route part: 4 frames a second, so the road moves smoothly on video. */
        const val TICK_MS = 250L
        /** Arrow part: Google Maps itself updates about once a second. */
        const val ARROW_TICK_MS = 1000L
        /** The demo moves this much faster than its speedometer reads: at the real speeds
         * both parts took ~170 s, too long for a social-media clip; at 1.6x it is ~1:45 and
         * on screen the road still looks like the speed shown. */
        const val TIME_SCALE = 1.6
        private const val STEP_M = 3.0
    }

    data class Maneuver(val atM: Double, val text: String, val glyph: RouteIcons.Glyph)

    /** Target speed at arclength [atM] (km/h); linear in between. */
    private data class SpeedPoint(val atM: Double, val kmh: Double)
    private data class LimitZone(val fromM: Double, val kmh: Int)

    /** One scripted arrow-mode step: [lengthM] to cover before the maneuver, at [kmh]. */
    data class ArrowStep(val lengthM: Double, val kmh: Int, val limit: Int,
                         val street: String, val text: String, val glyph: RouteIcons.Glyph)

    val maneuvers = ArrayList<Maneuver>()
    val road: Polyline
    val lengthM: Double get() = road.lengthM
    /** The made-up city around the route, in the road's own frame. */
    val streets = ArrayList<StreetMap.Street>()

    private val speeds = ArrayList<SpeedPoint>()
    private val limits = ArrayList<LimitZone>()

    val arrowSteps = listOf(
        ArrowStep(140.0, 42, 50, "Av. Providencia", "Gire a la derecha hacia Av. Providencia", RouteIcons.Glyph.RIGHT),
        ArrowStep(200.0, 64, 60, "Av. Andrés Bello", "Mantenga la izquierda hacia Av. Andrés Bello", RouteIcons.Glyph.SLIGHT_LEFT),
        ArrowStep(120.0, 35, 50, "Rotonda", "En la rotonda, tome la segunda salida", RouteIcons.Glyph.ROUNDABOUT),
        ArrowStep(90.0, 28, 30, "Los Aromos", "Gire a la izquierda hacia Los Aromos", RouteIcons.Glyph.LEFT),
        ArrowStep(45.0, 18, 30, "Los Aromos 1491", "Llegada a destino", RouteIcons.Glyph.ARRIVE),
    )

    init {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        var x = 0.0
        var y = 0.0
        var h = 0.0
        var s = 0.0
        fun add() { xs.add(x); ys.add(y) }
        fun step() { x += STEP_M * sin(h); y += STEP_M * cos(h); s += STEP_M; add() }
        fun straight(m: Double) { var d = 0.0; while (d < m) { step(); d += STEP_M } }
        fun bend(radius: Double, degrees: Double) {
            val total = Math.toRadians(degrees)
            val steps = (abs(total) * radius / STEP_M).roundToInt().coerceAtLeast(1)
            repeat(steps) { h += total / steps; step() }
        }
        fun speed(kmh: Double) = speeds.add(SpeedPoint(s, kmh))
        fun limit(kmh: Int) = limits.add(LimitZone(s, kmh))

        add()
        speed(18.0); limit(50)
        straight(120.0)                                   // north, accelerating off the line
        speed(45.0); straight(30.0); speed(22.0)
        maneuvers.add(Maneuver(s, "Gire a la derecha", RouteIcons.Glyph.RIGHT))
        bend(14.0, 90.0)                                   // now heading east
        speed(25.0); limit(60)
        straight(100.0); speed(70.0)                       // the avenue: zoom backs out
        straight(110.0); speed(70.0)                       // 70 in a 60: red glow
        straight(55.0); speed(30.0)                        // braking for the roundabout
        maneuvers.add(Maneuver(s, "En la rotonda, tome la segunda salida", RouteIcons.Glyph.ROUNDABOUT))
        // Roundabout, anticlockwise (traffic on the right): veer right, around the island
        // on the left, veer right out - straight through, i.e. the second exit.
        limit(50)
        bend(12.0, 60.0)
        val rr = 22.0
        val cx = x - rr * cos(h); val cy = y + rr * sin(h) // island centre: left of heading
        bend(rr, -120.0)
        bend(12.0, 60.0)
        speed(32.0)
        straight(110.0); speed(52.0)
        straight(50.0); speed(38.0)
        maneuvers.add(Maneuver(s, "Mantenga la izquierda", RouteIcons.Glyph.SLIGHT_LEFT))
        bend(70.0, -35.0)
        speed(40.0)
        straight(90.0); speed(22.0)
        maneuvers.add(Maneuver(s, "Gire a la izquierda", RouteIcons.Glyph.LEFT))
        bend(13.0, -90.0)
        limit(30)                                          // residential street
        speed(26.0)
        straight(70.0); speed(20.0)
        straight(25.0); speed(10.0)
        maneuvers.add(Maneuver(s, "Llegada a destino", RouteIcons.Glyph.ARRIVE))
        straight(6.0)
        road = Polyline(xs.toDoubleArray(), ys.toDoubleArray())

        buildCity(xs, ys, cx, cy, rr)
    }

    /** A street grid every ~110 m over the route's surroundings, a few avenues, and the
     * roundabout's ring - enough to read as a city on the watch. Negative ids: they can
     * never clash with OpenStreetMap ones. */
    private fun buildCity(xs: List<Double>, ys: List<Double>, cx: Double, cy: Double, r: Double) {
        val minX = xs.min() - 400; val maxX = xs.max() + 400
        val minY = ys.min() - 400; val maxY = ys.max() + 400
        var id = -1L
        fun line(x0: Double, y0: Double, x1: Double, y1: Double, cls: Int) {
            streets.add(StreetMap.Street(id--, cls,
                intArrayOf(x0.roundToInt(), x1.roundToInt()), intArrayOf(y0.roundToInt(), y1.roundToInt())))
        }
        val grid = 110.0
        var gx = Math.floor(minX / grid) * grid + 35.0
        var col = 0
        while (gx < maxX) {
            line(gx, minY, gx, maxY, if (col % 4 == 2) 1 else 0)
            gx += grid; col++
        }
        var gy = Math.floor(minY / grid) * grid + 55.0
        var row = 0
        while (gy < maxY) {
            line(minX, gy, maxX, gy, if (row % 5 == 1) 2 else 0)
            gy += grid; row++
        }
        // the roundabout ring
        val n = 24
        val rx = IntArray(n + 1) { (cx + r * sin(2 * Math.PI * it / n)).roundToInt() }
        val ry = IntArray(n + 1) { (cy + r * cos(2 * Math.PI * it / n)).roundToInt() }
        streets.add(StreetMap.Street(id--, 1, rx, ry))
    }

    fun speedKmhAt(s: Double): Double {
        if (s <= speeds.first().atM) return speeds.first().kmh
        for (i in 1 until speeds.size) {
            val a = speeds[i - 1]; val b = speeds[i]
            if (s <= b.atM) return a.kmh + (b.kmh - a.kmh) * (s - a.atM) / (b.atM - a.atM).coerceAtLeast(1.0)
        }
        return speeds.last().kmh
    }

    fun limitAt(s: Double): Int = limits.lastOrNull { it.fromM <= s }?.kmh ?: 50

    fun nextManeuver(s: Double): Maneuver = maneuvers.firstOrNull { it.atM > s } ?: maneuvers.last()

    fun iconBits(glyph: RouteIcons.Glyph): ByteArray = RouteIcons.bits(glyph)
}
