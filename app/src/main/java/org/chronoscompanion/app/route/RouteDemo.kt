package org.chronoscompanion.app.route

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * PHASE 1 demo: a made-up road with two bends and a simulated rider driving it, so the
 * watch's route view can be judged without GPS or routing. Geometry and icons go through
 * the same Polyline / RouteIcons as real routes.
 */
class RouteDemo {

    companion object {
        const val SPEED_M_S = 11.0 // ~40 km/h
        const val TICK_MS = 1000L
        private const val STEP_M = 4.0
    }

    data class Maneuver(val atM: Double, val text: String, val glyph: RouteIcons.Glyph)

    val maneuvers = ArrayList<Maneuver>()
    val road: Polyline
    val lengthM: Double get() = road.lengthM

    init {
        // x = east, y = north, metres. Heading clockwise from north.
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
            val steps = (abs(total) * radius / STEP_M).roundToInt()
            repeat(steps) { h += total / steps; step() }
        }
        add()
        straight(150.0)
        maneuvers.add(Maneuver(s, "Curva a la derecha", RouteIcons.Glyph.RIGHT))
        bend(80.0, 70.0)
        straight(120.0)
        maneuvers.add(Maneuver(s, "Curva a la izquierda", RouteIcons.Glyph.LEFT))
        bend(60.0, -110.0)
        straight(300.0)
        maneuvers.add(Maneuver(s, "Llegada al destino", RouteIcons.Glyph.ARRIVE))
        road = Polyline(xs.toDoubleArray(), ys.toDoubleArray())
    }

    fun frameAt(s: Double) = road.frameAt(s)

    fun nextManeuver(s: Double): Maneuver = maneuvers.firstOrNull { it.atM > s } ?: maneuvers.last()

    fun distanceText(m: Double): String = RouteFormat.distance(m)

    fun iconBits(glyph: RouteIcons.Glyph): ByteArray = RouteIcons.bits(glyph)
}
