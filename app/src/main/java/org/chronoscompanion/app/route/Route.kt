package org.chronoscompanion.app.route

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.cos

/**
 * One instruction the rider actually has to act on, positioned along the polyline.
 * [alert] / [atTurn] are the engine's own spoken phrasings when it provides them (Valhalla
 * does; OSRM doesn't): [alert] for the early call, [atTurn] for the call at the turn,
 * which Valhalla chains with the next one when they are close ("... Después gire a la
 * derecha hacia ..."). Null -> RouteVoice builds its own from [text].
 */
data class RouteManeuver(
    val atM: Double,
    val text: String,
    val glyph: RouteIcons.Glyph,
    val alert: String? = null,
    val atTurn: String? = null,
)

/** What the rider asked the router to avoid. Remembered between routes. */
data class RoutePrefs(val avoidHighways: Boolean, val avoidTolls: Boolean)

/**
 * A computed route: the road as a [Polyline] in local metres, the filtered maneuvers, and
 * the engine's totals. Everything route mode shows on the watch is derived from this.
 */
class Route(
    val road: Polyline,
    val maneuvers: List<RouteManeuver>,
    val distanceM: Double,
    /** The engine's estimate. Neither engine models traffic - measured 94 min vs Google's
     * 2 h 27 on Santiago -> Viña - so everything shown from it is labelled traffic-free. */
    val durationS: Double,
    /** How far the rider's fix was from where the route starts. Large = bad fix, or the
     * rider is somewhere the router considers off-road (parking, private yard). */
    val startSnapM: Double,
    val originLat: Double,
    val originLon: Double,
    /** Null when the engine doesn't report it (the OSRM fallback). */
    val hasToll: Boolean? = null,
    val hasHighway: Boolean? = null,
    /** Which engine produced it - "valhalla" normally, "osrm" if Valhalla was unreachable. */
    val engine: String = "osrm",
    /** Valhalla's encoded shape, kept so speed limits can be fetched after the fact. */
    val shape: String? = null,
) {
    /** Posted speed limits along the road: [limitStarts] arclengths (ascending) with the
     * km/h from there on, 0 = unknown. Null if none could be fetched - the watch's limit
     * sign then simply stays hidden. Filled in after the route itself (second request). */
    @Volatile var limitStarts: DoubleArray? = null
    @Volatile var limitKmh: IntArray? = null

    /** Limit in force at arclength [s], or null when unknown. */
    fun speedLimitAt(s: Double): Int? {
        val starts = limitStarts ?: return null
        val values = limitKmh ?: return null
        var lo = 0
        var hi = starts.size - 1
        if (hi < 0 || s < starts[0]) return null
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= s) lo = mid else hi = mid - 1
        }
        return values[lo].takeIf { it > 0 }
    }

    private val kx = OsrmClient.M_PER_DEG_LON * cos(Math.toRadians(originLat))

    /** A GPS fix in the same local metric frame as [road]. */
    fun toLocal(lat: Double, lon: Double): Pair<Double, Double> =
        Pair((lon - originLon) * kx, (lat - originLat) * OsrmClient.M_PER_DEG_LAT)

    /** Inverse of [toLocal]: a point of [road] back to (lat, lon). */
    fun toLatLon(x: Double, y: Double): Pair<Double, Double> =
        Pair(originLat + y / OsrmClient.M_PER_DEG_LAT, originLon + x / kx)

    fun nextManeuver(s: Double): RouteManeuver? = maneuvers.firstOrNull { it.atM > s + 1 }

    fun remainingDurationS(s: Double): Double =
        durationS * ((road.lengthM - s) / road.lengthM).coerceIn(0.0, 1.0)

    fun remainingDistanceM(s: Double): Double =
        distanceM * ((road.lengthM - s) / road.lengthM).coerceIn(0.0, 1.0)
}

/**
 * The router actually used: Valhalla first, OSRM as fallback.
 *
 * Valhalla (FOSSGIS public server) because it does what OSRM's public servers refuse
 * ("Exclude flag combination is not supported" for both toll and motorway, verified on
 * router.project-osrm.org and routing.openstreetmap.de): avoid motorways and tolls -
 * which in Santiago means staying off the TAG urban highways - plus a motorcycle profile
 * and Spanish instructions written for voice. Same OpenStreetMap data, so a destination
 * picked in CoMaps exists in its graph too.
 *
 * OSRM stays as the fallback so a Valhalla outage doesn't strand the rider without a
 * route: two independent free servers beat one. The fallback ignores [RoutePrefs] and the
 * route says so (engine = "osrm").
 */
object Router {
    private const val TAG = "Router"

    /** Blocking - call off the main thread. [headingDeg] = rider's current course, so a
     * reroute starts in the direction they're already going instead of a U-turn.
     * [withLimits] false = return as soon as the route exists (reroutes: fetch limits
     * afterwards with ValhallaClient.attachSpeedLimits). */
    fun fetch(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, prefs: RoutePrefs,
              headingDeg: Double? = null, withLimits: Boolean = true): Route {
        return try {
            ValhallaClient.fetch(fromLat, fromLon, toLat, toLon, prefs, headingDeg, withLimits)
        } catch (e: OsrmClient.RouteException) {
            throw e // a real "no route" answer: OSRM would not find one either
        } catch (e: Exception) {
            Log.w(TAG, "Valhalla unavailable, falling back to OSRM", e)
            OsrmClient.fetch(fromLat, fromLon, toLat, toLon)
        }
    }
}

/**
 * Client for the FOSSGIS public Valhalla server. Policy: the same fair use as the OSRM and
 * Nominatim demo servers, rate-limited to 1 call/user/s. Called once per route start and
 * on a preference change - never per GPS fix.
 */
object ValhallaClient {
    private const val TAG = "ValhallaClient"
    private const val BASE = "https://valhalla1.openstreetmap.de/route?json="
    private const val USER_AGENT = "chronos-nav/0.3 (personal handlebar display; fvalladaresg1975)"

    fun fetch(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, prefs: RoutePrefs,
              headingDeg: Double? = null, withLimits: Boolean = true): Route {
        val options = JSONObject()
        val start = JSONObject().put("lat", fromLat).put("lon", fromLon)
        // Heading alone is NOT honoured: Valhalla snaps to the single closest edge first and
        // ignores a heading that edge doesn't match. Verified on Av. Kennedy (divided):
        // heading west without a radius still started eastbound (10.16 km); with radius 50
        // it took the westbound carriageway (6.44 km). The radius lets it consider the
        // carriageway the rider is actually on.
        if (headingDeg != null) {
            start.put("heading", headingDeg.toInt()).put("heading_tolerance", 45).put("radius", 50)
        }
        // 0 = avoid as much as possible. Left unset otherwise: Valhalla's own defaults.
        if (prefs.avoidHighways) options.put("use_highways", 0.0)
        if (prefs.avoidTolls) options.put("use_tolls", 0.0)
        val query = JSONObject()
            .put("locations", org.json.JSONArray()
                .put(start)
                .put(JSONObject().put("lat", toLat).put("lon", toLon)))
            .put("costing", "motorcycle")
            .put("costing_options", JSONObject().put("motorcycle", options))
            .put("directions_options", JSONObject().put("language", "es-ES").put("units", "kilometers"))

        val conn = (URL(BASE + URLEncoder.encode(query.toString(), "UTF-8")).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        val code: Int
        val body: String
        try {
            code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally {
            conn.disconnect()
        }
        if (code == 400) {
            // 400 with an error body = Valhalla answered and found no path (e.g. error_code
            // 442). A real answer: report it instead of falling back to OSRM.
            val msg = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
            throw OsrmClient.RouteException("No hay una ruta en moto hasta ese destino" +
                if (msg.isNotBlank()) " ($msg)" else "")
        }
        if (code !in 200..299) throw java.io.IOException("Valhalla HTTP $code")
        val route = parse(JSONObject(body), fromLat, fromLon)
        if (withLimits) attachSpeedLimits(route)
        return route
    }

    /**
     * Posted limit of the road the rider is on NOW, from their last few GPS fixes (Google
     * Maps mode: there is no known route to read it from). Map-matched with map_snap, which
     * tolerates GPS noise - verified 8/8 correct with ~8 m simulated noise on city streets
     * and Costanera Norte. Only the LAST matched edge counts: earlier ones are roads already
     * left behind. Null = unknown (no maxspeed in OSM, or no match). Blocking.
     */
    fun speedLimitHere(fixes: List<android.location.Location>): Int? {
        val shape = org.json.JSONArray()
        fixes.forEach { shape.put(JSONObject().put("lat", it.latitude).put("lon", it.longitude)) }
        val q = JSONObject()
            .put("shape", shape)
            .put("shape_match", "map_snap")
            .put("costing", "motorcycle")
            .put("filters", JSONObject()
                .put("attributes", org.json.JSONArray().put("edge.speed_limit").put("edge.road_class"))
                .put("action", "include"))
        val conn = (URL("https://valhalla1.openstreetmap.de/trace_attributes").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Content-Type", "application/json")
        }
        val body = try {
            conn.outputStream.use { it.write(q.toString().toByteArray()) }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val edges = JSONObject(body).optJSONArray("edges") ?: return null
        if (edges.length() == 0) return null
        return limitOf(edges.getJSONObject(edges.length() - 1)).takeIf { it > 0 }
    }

    /** Chile's default limit in urban areas (Ley de Tránsito art. 145, 50 km/h since
     * 2018), used for residential streets with no maxspeed in OpenStreetMap: on the user's
     * first ride the sign kept disappearing on exactly those - most of the 12-14% of edges
     * without a tagged limit. Residential streets are town streets, so the urban default
     * is the legal limit unless a sign says otherwise (and signed limits are what OSM
     * usually does have). Other road classes without data stay unknown (sign hidden): a
     * primary or secondary road may be rural (100) or urban (50). */
    private const val URBAN_DEFAULT_KMH = 50

    /** An edge's limit: OSM's posted one, else the urban default for residential
     * streets, else 0 (unknown). */
    private fun limitOf(edge: JSONObject): Int {
        val posted = edge.optInt("speed_limit", 0)
        if (posted > 0) return posted
        return if (edge.optString("road_class") == "residential") URBAN_DEFAULT_KMH else 0
    }

    /** Speed limits come from a second endpoint. Best effort: a route without them is
     * still a full route, the sign just stays hidden. Blocking. */
    fun attachSpeedLimits(route: Route) {
        val shape = route.shape ?: return
        runCatching {
            Thread.sleep(1100) // fair-use: 1 call/user/s
            fetchSpeedLimits(shape, route)
        }.onFailure { Log.w(TAG, "No speed limits", it) }
    }

    /**
     * Posted limits (OpenStreetMap maxspeed) per edge of the route, via trace_attributes
     * on the route's own shape, so edge shape indices line up with our polyline vertices.
     * Measured coverage: 97% of Santiago -> Viña, 86% of a city route. POST because a
     * long route's shape overflows a GET URL (HTTP 414 on 120 km).
     */
    private fun fetchSpeedLimits(shape: String, route: Route) {
        val q = JSONObject()
            .put("encoded_polyline", shape)
            .put("shape_match", "edge_walk")
            .put("costing", "motorcycle")
            .put("filters", JSONObject()
                .put("attributes", org.json.JSONArray()
                    .put("edge.speed_limit").put("edge.road_class").put("edge.begin_shape_index"))
                .put("action", "include"))
        val conn = (URL("https://valhalla1.openstreetmap.de/trace_attributes").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Content-Type", "application/json")
        }
        val body = try {
            conn.outputStream.use { it.write(q.toString().toByteArray()) }
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val edges = JSONObject(body).getJSONArray("edges")
        val starts = DoubleArray(edges.length())
        val values = IntArray(edges.length())
        var known = 0
        var urbanDefault = 0
        for (i in 0 until edges.length()) {
            val e = edges.getJSONObject(i)
            starts[i] = route.road.arcAtVertex(e.optInt("begin_shape_index", 0))
            values[i] = limitOf(e) // 0 = unknown
            if (e.optInt("speed_limit", 0) > 0) known++ else if (values[i] > 0) urbanDefault++
        }
        route.limitStarts = starts
        route.limitKmh = values
        Log.i(TAG, "Speed limits: $known of ${edges.length()} edges posted, $urbanDefault residential at the urban default")
    }

    private fun parse(json: JSONObject, fromLat: Double, fromLon: Double): Route {
        val trip = json.getJSONObject("trip")
        val leg = trip.getJSONArray("legs").getJSONObject(0)
        val pts = decodePolyline6(leg.getString("shape"))
        require(pts.size >= 2) { "route shape too short" }
        val lat0 = pts[0].first
        val lon0 = pts[0].second
        val kx = OsrmClient.M_PER_DEG_LON * cos(Math.toRadians(lat0))
        val xs = DoubleArray(pts.size) { (pts[it].second - lon0) * kx }
        val ys = DoubleArray(pts.size) { (pts[it].first - lat0) * OsrmClient.M_PER_DEG_LAT }
        val road = Polyline(xs, ys)

        val maneuvers = ArrayList<RouteManeuver>()
        val ms = leg.getJSONArray("maneuvers")
        for (i in 0 until ms.length()) {
            val m = ms.getJSONObject(i)
            val glyph = glyphFor(m.optInt("type")) ?: continue
            val idx = m.optInt("begin_shape_index", 0).coerceIn(0, pts.size - 1)
            // The watch shows two lines: the shorter alert phrasing fits ("Tome la salida 68"
            // rather than "... en dirección a Viña del Mar/Valparaíso").
            val alert = m.optString("verbal_transition_alert_instruction").trim().trimEnd('.').ifBlank { null }
            val text = alert ?: m.optString("instruction").trim().trimEnd('.')
            val atTurn = m.optString("verbal_pre_transition_instruction").trim().trimEnd('.').ifBlank { null }
            maneuvers.add(RouteManeuver(road.arcAtVertex(idx), text, glyph, alert, atTurn))
        }
        if (maneuvers.isEmpty() || maneuvers.last().glyph != RouteIcons.Glyph.ARRIVE) {
            maneuvers.add(RouteManeuver(road.lengthM, "Llegada a destino", RouteIcons.Glyph.ARRIVE))
        }

        val summary = trip.getJSONObject("summary")
        val distanceM = summary.getDouble("length") * 1000
        val kxFix = OsrmClient.M_PER_DEG_LON * cos(Math.toRadians(fromLat))
        val snap = kotlin.math.hypot((fromLon - lon0) * kxFix, (fromLat - lat0) * OsrmClient.M_PER_DEG_LAT)
        Log.i(TAG, "Route: %.1f km, %d vertices, %d maneuvers -> %d kept, toll=%b highway=%b".format(
            distanceM / 1000, pts.size, ms.length(), maneuvers.size,
            summary.optBoolean("has_toll"), summary.optBoolean("has_highway")))
        return Route(road, maneuvers, distanceM, summary.getDouble("time"), snap, lat0, lon0,
            hasToll = summary.optBoolean("has_toll"), hasHighway = summary.optBoolean("has_highway"),
            engine = "valhalla", shape = leg.getString("shape"))
    }

    /**
     * Valhalla maneuver types -> arrow, or null for steps the rider doesn't act on: start
     * (0-3), "becomes" (7, the name-change kind behind OSRM's false turns), continue (8),
     * stay straight (22), roundabout exit (27 - the entry already said which exit),
     * transit / elevator / stairs (30+, except the two merge sides 37/38).
     */
    private fun glyphFor(type: Int): RouteIcons.Glyph? = when (type) {
        4, 5, 6 -> RouteIcons.Glyph.ARRIVE
        9, 18, 20, 23, 37 -> RouteIcons.Glyph.SLIGHT_RIGHT
        10 -> RouteIcons.Glyph.RIGHT
        11 -> RouteIcons.Glyph.SHARP_RIGHT
        12, 13 -> RouteIcons.Glyph.UTURN
        14 -> RouteIcons.Glyph.SHARP_LEFT
        15 -> RouteIcons.Glyph.LEFT
        16, 19, 21, 24, 38 -> RouteIcons.Glyph.SLIGHT_LEFT
        17, 25, 28, 29 -> RouteIcons.Glyph.STRAIGHT
        26 -> RouteIcons.Glyph.ROUNDABOUT
        else -> null
    }

    /** Google encoded-polyline format at Valhalla's precision of 1e-6. */
    private fun decodePolyline6(enc: String): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var i = 0
        var lat = 0L
        var lon = 0L
        while (i < enc.length) {
            for (k in 0..1) {
                var shift = 0
                var result = 0L
                var b: Int
                do {
                    b = enc[i++].code - 63
                    result = result or ((b and 0x1f).toLong() shl shift)
                    shift += 5
                } while (b >= 0x20)
                val delta = if (result and 1L != 0L) (result shr 1).inv() else (result shr 1)
                if (k == 0) lat += delta else lon += delta
            }
            out.add(Pair(lat / 1e6, lon / 1e6))
        }
        return out
    }
}

/**
 * Client for the public OSRM demo server. Policy (verified in phase 0): at most 1 request
 * per second, reasonable non-commercial use, no availability guarantee. So it is called
 * once when a route starts (and, from phase 3, on reroute) - never per GPS fix - and a
 * route already fetched keeps working if the server later goes down.
 */
object OsrmClient {
    private const val TAG = "OsrmClient"
    private const val BASE = "https://router.project-osrm.org/route/v1/driving/"
    private const val USER_AGENT = "chronos-nav/0.2 (personal handlebar display; fvalladaresg1975)"
    const val M_PER_DEG_LAT = 110540.0
    const val M_PER_DEG_LON = 111320.0

    class RouteException(message: String) : Exception(message)

    /** Blocking - call off the main thread. */
    fun fetch(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Route {
        val url = URL(
            BASE + "%.6f,%.6f;%.6f,%.6f".format(java.util.Locale.US, fromLon, fromLat, toLon, toLat) +
                "?overview=full&geometries=geojson&steps=true"
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        val body = try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw RouteException("El servidor de rutas respondió $code")
            text
        } finally {
            conn.disconnect()
        }
        return parse(JSONObject(body))
    }

    private fun parse(json: JSONObject): Route {
        val code = json.optString("code")
        if (code != "Ok") {
            throw RouteException(
                if (code == "NoRoute") "No hay una ruta en auto hasta ese destino"
                else "El servidor de rutas respondió: ${json.optString("message", code)}"
            )
        }
        val route = json.getJSONArray("routes").getJSONObject(0)
        val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
        val lat0 = coords.getJSONArray(0).getDouble(1)
        val lon0 = coords.getJSONArray(0).getDouble(0)
        // Equirectangular around the origin: over a 120 km route the scale error at the far
        // end is ~0.5%, invisible in a 470 m window, and only the local window is ever drawn.
        val kx = M_PER_DEG_LON * cos(Math.toRadians(lat0))
        val xs = DoubleArray(coords.length())
        val ys = DoubleArray(coords.length())
        for (i in 0 until coords.length()) {
            val p = coords.getJSONArray(i)
            xs[i] = (p.getDouble(0) - lon0) * kx
            ys[i] = (p.getDouble(1) - lat0) * M_PER_DEG_LAT
        }
        val road = Polyline(xs, ys)
        val distance = route.getDouble("distance")
        // Step distances are OSRM's own (haversine); rescale their running sum onto our
        // polyline so a maneuver lands where the drawn road bends.
        val k = if (distance > 0) road.lengthM / distance else 1.0

        val maneuvers = ArrayList<RouteManeuver>()
        val steps = route.getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
        var cum = 0.0
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            describe(step)?.let { (text, glyph) -> maneuvers.add(RouteManeuver(cum * k, text, glyph)) }
            cum += step.optDouble("distance", 0.0)
        }
        if (maneuvers.isEmpty() || maneuvers.last().glyph != RouteIcons.Glyph.ARRIVE) {
            maneuvers.add(RouteManeuver(road.lengthM, "Llegada a destino", RouteIcons.Glyph.ARRIVE))
        }

        val waypoints = json.optJSONArray("waypoints")
        val startSnap = waypoints?.optJSONObject(0)?.optDouble("distance", 0.0) ?: 0.0
        Log.i(TAG, "Route: %.1f km, %d vertices, %d steps -> %d maneuvers, start snap %.0f m"
            .format(distance / 1000, coords.length(), steps.length(), maneuvers.size, startSnap))
        return Route(road, maneuvers, distance, route.getDouble("duration"), startSnap, lat0, lon0, engine = "osrm")
    }

    /**
     * Spanish instruction + arrow for one OSRM step, or null when the step is not something
     * the rider acts on. Filtering is the point: on Santiago -> Viña 6 of 24 steps were
     * "new name/straight" (the street merely changing name - Agua Santa vs Variante Agua
     * Santa alternated four times). Shown as turns they would reset the watch's ring and
     * flicker the street label with no actual turn.
     */
    private fun describe(step: JSONObject): Pair<String, RouteIcons.Glyph>? {
        val m = step.getJSONObject("maneuver")
        val type = m.optString("type")
        val mod = m.optString("modifier", "straight")
        val name = step.optString("name").ifBlank { step.optString("ref") }.trim()
        val glyph = glyphFor(mod)
        val side = when {
            mod.contains("right") -> "a la derecha"
            mod.contains("left") -> "a la izquierda"
            else -> ""
        }
        fun on(prefix: String) = if (name.isEmpty()) prefix else "$prefix en $name"
        fun towards(prefix: String) = if (name.isEmpty()) prefix else "$prefix hacia $name"

        return when (type) {
            "depart", "new name", "notification", "use lane", "exit roundabout", "exit rotary" -> null
            "arrive" -> Pair("Llegada a destino", RouteIcons.Glyph.ARRIVE)
            "roundabout", "rotary" -> {
                val exit = m.optInt("exit", 0)
                Pair(if (exit > 0) towards("En la rotonda, toma la salida $exit") else towards("Entra a la rotonda"),
                    RouteIcons.Glyph.ROUNDABOUT)
            }
            "merge" -> Pair(if (name.isEmpty()) "Incorpórate" else "Incorpórate a $name", glyph)
            "on ramp" -> Pair(towards("Toma la entrada"), glyph)
            "off ramp" -> Pair(towards(if (side.isEmpty()) "Toma la salida" else "Toma la salida $side"), glyph)
            "fork" -> Pair(towards(if (side.isEmpty()) "Sigue derecho" else "Mantente $side"), glyph)
            else -> { // turn, end of road, continue, roundabout turn, anything newer
                when (mod) {
                    "straight" -> null // a "turn" that goes straight is not a turn
                    "uturn" -> Pair("Da la vuelta en U", RouteIcons.Glyph.UTURN)
                    "slight right", "slight left" -> Pair(on("Gira levemente $side"), glyph)
                    "sharp right", "sharp left" -> Pair(on("Gira cerrado $side"), glyph)
                    else -> Pair(on("Gira $side"), glyph)
                }
            }
        }
    }

    private fun glyphFor(modifier: String) = when (modifier) {
        "uturn" -> RouteIcons.Glyph.UTURN
        "sharp right" -> RouteIcons.Glyph.SHARP_RIGHT
        "right" -> RouteIcons.Glyph.RIGHT
        "slight right" -> RouteIcons.Glyph.SLIGHT_RIGHT
        "slight left" -> RouteIcons.Glyph.SLIGHT_LEFT
        "left" -> RouteIcons.Glyph.LEFT
        "sharp left" -> RouteIcons.Glyph.SHARP_LEFT
        else -> RouteIcons.Glyph.STRAIGHT
    }
}
