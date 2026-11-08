package org.chronoscompanion.app.route

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * CoMaps' own turn-by-turn navigation, read from the content provider CoMaps publishes for
 * other apps (codeberg.org/comaps/comaps/wiki/Content-Provider; Gadgetbridge uses the same):
 *   content://<pkg>.provider.navigation/live   one row: state, next turn, distances, time
 *   content://<pkg>.provider.navigation/route  one row per route point (lat, lon)
 * Reading needs CoMaps' runtime permission <pkg>.permission.READ_NAVIGATION_DATA, which
 * CoMaps declares "dangerous": the rider grants it once in the usual Android dialog.
 *
 * Never polled blindly: querying a provider starts its app's process, so polling only runs
 * after a trigger - a CoMaps notification (it shows one while it navigates), CoMaps
 * announcing a route change on the provider, or the app checking at start - and stops by
 * itself once CoMaps has not been guiding for a while.
 *
 * Callbacks arrive on the main thread.
 */
class CoMapsLink(
    private val context: Context,
    /** Whether CoMaps' navigation notification is up (null = can't tell). The real "is it
     * navigating": after a stop the provider keeps serving its last reading - state and
     * all - for a while (seen on the phone: the watch reopened a second after closing). */
    private val navigationShown: () -> Boolean?,
    private val onUpdate: (Update) -> Unit,
) {

    companion object {
        private const val TAG = "CoMapsLink"
        /** Google Play, F-Droid, and the plain build: same provider under each one's id. */
        val PACKAGES = listOf("app.comaps.google", "app.comaps.fdroid", "app.comaps")
        private const val POLL_MS = 1000L
        /** Polls without CoMaps guiding before polling stops (a rebuild passes through
         * non-guiding states for a moment, so the service judges the end on its own clock). */
        private const val IDLE_POLLS_TO_STOP = 15

        /** States in which CoMaps is actually guiding the rider (RoutingInfo.RoutingSessionState). */
        private val GUIDING = setOf("OnRoute", "OffRoute", "RouteNeedsRebuild", "RouteRebuilding")

        fun isCoMaps(pkg: String) = pkg in PACKAGES

        fun permissionOf(pkg: String) = "$pkg.permission.READ_NAVIGATION_DATA"

        /** The CoMaps build installed on this phone, or null. */
        fun installedPackage(context: Context): String? = PACKAGES.firstOrNull {
            runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess
        }

        fun hasPermission(context: Context, pkg: String) =
            ContextCompat.checkSelfPermission(context, permissionOf(pkg)) == PackageManager.PERMISSION_GRANTED

        /** CoMaps' CarDirection -> the arrow drawn for the watch. */
        fun glyphOf(direction: String): RouteIcons.Glyph = when (direction) {
            "TURN_RIGHT" -> RouteIcons.Glyph.RIGHT
            "TURN_SHARP_RIGHT" -> RouteIcons.Glyph.SHARP_RIGHT
            "TURN_SLIGHT_RIGHT", "EXIT_HIGHWAY_TO_RIGHT" -> RouteIcons.Glyph.SLIGHT_RIGHT
            "TURN_LEFT" -> RouteIcons.Glyph.LEFT
            "TURN_SHARP_LEFT" -> RouteIcons.Glyph.SHARP_LEFT
            "TURN_SLIGHT_LEFT", "EXIT_HIGHWAY_TO_LEFT" -> RouteIcons.Glyph.SLIGHT_LEFT
            "U_TURN_LEFT", "U_TURN_RIGHT" -> RouteIcons.Glyph.UTURN
            "ENTER_ROUND_ABOUT", "LEAVE_ROUND_ABOUT", "STAY_ON_ROUND_ABOUT" -> RouteIcons.Glyph.ROUNDABOUT
            "REACHED_YOUR_DESTINATION" -> RouteIcons.Glyph.ARRIVE
            else -> RouteIcons.Glyph.STRAIGHT // NO_TURN, GO_STRAIGHT, START_AT_THE_END_OF_STREET
        }

        /** The instruction line the watch shows, in the same wording route mode uses. */
        fun instruction(live: Live): String {
            val base = when (live.direction) {
                "TURN_RIGHT" -> "Gira a la derecha"
                "TURN_SHARP_RIGHT" -> "Gira cerrado a la derecha"
                "TURN_SLIGHT_RIGHT" -> "Gira levemente a la derecha"
                "TURN_LEFT" -> "Gira a la izquierda"
                "TURN_SHARP_LEFT" -> "Gira cerrado a la izquierda"
                "TURN_SLIGHT_LEFT" -> "Gira levemente a la izquierda"
                "U_TURN_LEFT", "U_TURN_RIGHT" -> "Da la vuelta en U"
                "ENTER_ROUND_ABOUT" ->
                    if (live.exitNum > 0) "En la rotonda, toma la salida ${live.exitNum}" else "Entra a la rotonda"
                "LEAVE_ROUND_ABOUT" -> "Sal de la rotonda"
                "STAY_ON_ROUND_ABOUT" -> "Sigue en la rotonda"
                "EXIT_HIGHWAY_TO_RIGHT" -> "Toma la salida a la derecha"
                "EXIT_HIGHWAY_TO_LEFT" -> "Toma la salida a la izquierda"
                "START_AT_THE_END_OF_STREET" -> "Sigue hasta el final de la calle"
                "REACHED_YOUR_DESTINATION" -> return "Llegada a destino"
                else -> "Sigue derecho"
            }
            val street = live.nextStreet.trim()
            return if (street.isEmpty()) base else "$base hacia $street"
        }

        const val ENGINE = "comaps"

        /** CoMaps' route as a [Route] in route mode's local frame, so the watch draws it
         * (and the streets around it) exactly like a route of our own. It carries no
         * maneuvers - the next turn comes from CoMaps' live data each second - and no
         * limits: those are looked up from the rider's own track, as in Google Maps mode. */
        fun toRoute(pts: RoutePoints, live: Live): Route? {
            val lat0 = pts.lats[0]
            val lon0 = pts.lons[0]
            val kx = OsrmClient.M_PER_DEG_LON * kotlin.math.cos(Math.toRadians(lat0))
            val xs = ArrayList<Double>(pts.lats.size)
            val ys = ArrayList<Double>(pts.lats.size)
            for (i in pts.lats.indices) {
                val x = (pts.lons[i] - lon0) * kx
                val y = (pts.lats[i] - lat0) * OsrmClient.M_PER_DEG_LAT
                // Repeated points (segment joins) would make zero-length segments.
                if (xs.isNotEmpty() && kotlin.math.hypot(x - xs.last(), y - ys.last()) < 0.5) continue
                xs.add(x)
                ys.add(y)
            }
            if (xs.size < 2) return null
            val road = Polyline(xs.toDoubleArray(), ys.toDoubleArray())
            return Route(road, listOf(RouteManeuver(road.lengthM, "Llegada a destino", RouteIcons.Glyph.ARRIVE)),
                road.lengthM, live.totalTimeS.toDouble(), 0.0, lat0, lon0, engine = ENGINE)
        }

        /** CoMaps' formatted distances ("350 m", "2.1 km", "1,2 mi" - number, non-breaking
         * space, unit) -> metres; null if empty or unrecognised. */
        fun parseDistanceM(text: String?): Double? {
            if (text.isNullOrBlank()) return null
            val t = text.replace(' ', ' ').replace(' ', ' ').lowercase()
            val m = Regex("""(\d+(?:[.,]\d+)?)\s*(km|mi|ft|m)\b""").find(t) ?: return null
            val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            return when (m.groupValues[2]) {
                "km" -> v * 1000
                "mi" -> v * 1609.344
                "ft" -> v * 0.3048
                else -> v
            }
        }
    }

    /** One reading of /live. */
    data class Live(
        val state: String,
        val direction: String,
        val distToTurnM: Double?,
        val distToTargetM: Double?,
        val totalTimeS: Int,
        val nextStreet: String,
        val completionPercent: Double,
        val exitNum: Int,
        val revision: Int,
        val pointCount: Int,
        val destLat: Double?,
        val destLon: Double?,
    ) {
        val guiding: Boolean get() = state in GUIDING
        /** Which navigation this is: a reroute keeps the destination, a new one changes it. */
        val destKey: String get() =
            if (destLat == null || destLon == null) "?" else "%.5f,%.5f".format(java.util.Locale.US, destLat, destLon)
        val finished: Boolean get() = state == "RouteFinished"
    }

    /** The route CoMaps is following, as read from /route for [revision]. */
    class RoutePoints(val revision: Int, val lats: DoubleArray, val lons: DoubleArray)

    /** [live] null = no reading (no row, or CoMaps' navigation notification is gone).
     * [guiding] = CoMaps really guides now: its state says so AND its notification is up.
     * [route] = the latest route read, kept across updates; compare its revision to know
     * whether it is new. */
    class Update(val pkg: String, val live: Live?, val route: RoutePoints?, val guiding: Boolean)

    private val thread = HandlerThread("CoMapsLink").apply { start() }
    private val bg = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private var pkg: String? = null
    private var observer: ContentObserver? = null
    private var polling = false
    private var idlePolls = 0
    private var route: RoutePoints? = null
    private var permissionLogged = false

    private fun liveUri(p: String) = Uri.parse("content://$p.provider.navigation/live")
    private fun routeUri(p: String) = Uri.parse("content://$p.provider.navigation/route")

    /** Follows the installed CoMaps if the rider allowed it; false if not possible. */
    fun start(): Boolean {
        val p = installedPackage(context) ?: return false
        if (!hasPermission(context, p)) return false
        if (pkg == p) return true
        stop()
        pkg = p
        observer = object : ContentObserver(bg) {
            override fun onChange(selfChange: Boolean) = poke()
        }.also {
            runCatching {
                context.contentResolver.registerContentObserver(
                    Uri.parse("content://$p.provider.navigation"), true, it)
            }.onFailure { e -> Log.w(TAG, "No observer on CoMaps' provider", e) }
        }
        Log.i(TAG, "Following CoMaps ($p)")
        return true
    }

    fun stop() {
        observer?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
        observer = null
        pkg = null
        bg.removeCallbacksAndMessages(null)
        polling = false
        route = null
    }

    fun release() {
        stop()
        thread.quitSafely()
    }

    /** Something suggests CoMaps may be navigating: read it now, then every second while
     * it guides. Safe to call often. */
    fun poke() {
        bg.post {
            idlePolls = 0
            if (!polling && pkg != null) {
                polling = true
                bg.post(pollRunnable)
            }
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            val p = pkg ?: run { polling = false; return }
            val shown = runCatching { navigationShown() }.getOrNull()
            // No notification: not navigating, and don't query - a query would also start
            // CoMaps' process if it has gone.
            val live = if (shown == false) null else readLive(p)
            if (live != null && live.revision != route?.revision && live.pointCount >= 2) {
                readRoute(p, live.revision)?.let { route = it }
            }
            val r = route
            val guiding = live?.guiding == true
            main.post { onUpdate(Update(p, live, r, guiding)) }
            if (guiding) idlePolls = 0 else idlePolls++
            if (idlePolls >= IDLE_POLLS_TO_STOP) {
                polling = false
                Log.i(TAG, "CoMaps is not navigating, polling stopped")
                return
            }
            bg.postDelayed(this, POLL_MS)
        }
    }

    private fun readLive(p: String): Live? = try {
        context.contentResolver.query(liveUri(p), null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            fun str(col: String) = c.getColumnIndex(col).takeIf { it >= 0 }?.let { c.getString(it) }.orEmpty()
            fun int(col: String) = c.getColumnIndex(col).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getInt(it) } ?: 0
            fun dblOrNull(col: String) = c.getColumnIndex(col).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getDouble(it) }
            fun dbl(col: String) = dblOrNull(col) ?: 0.0
            Live(
                state = str("session_state"),
                direction = str("car_direction"),
                distToTurnM = parseDistanceM(str("dist_to_turn")),
                distToTargetM = parseDistanceM(str("dist_to_target")),
                totalTimeS = int("total_time_seconds"),
                nextStreet = str("next_street"),
                completionPercent = dbl("completion_percent"),
                exitNum = int("exit_num"),
                revision = int("route_revision"),
                pointCount = int("route_point_count"),
                destLat = dblOrNull("dest_lat"),
                destLon = dblOrNull("dest_lon"),
            )
        }
    } catch (e: SecurityException) {
        if (!permissionLogged) Log.w(TAG, "CoMaps permission missing", e)
        permissionLogged = true
        null
    } catch (e: Exception) {
        Log.w(TAG, "Could not read CoMaps' navigation", e)
        null
    }

    private fun readRoute(p: String, revision: Int): RoutePoints? = try {
        context.contentResolver.query(routeUri(p), arrayOf("seq", "lat", "lon", "revision"), null, null, null)?.use { c ->
            val iLat = c.getColumnIndexOrThrow("lat")
            val iLon = c.getColumnIndexOrThrow("lon")
            val iRev = c.getColumnIndex("revision")
            val lats = ArrayList<Double>(c.count)
            val lons = ArrayList<Double>(c.count)
            while (c.moveToNext()) {
                if (iRev >= 0 && c.getInt(iRev) != revision) return@use null // changed mid-read: next poll
                lats.add(c.getDouble(iLat))
                lons.add(c.getDouble(iLon))
            }
            if (lats.size < 2) return@use null
            Log.i(TAG, "CoMaps route revision $revision: ${lats.size} points")
            RoutePoints(revision, lats.toDoubleArray(), lons.toDoubleArray())
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read CoMaps' route", e)
        null
    }
}

object CoMapsPrefs {
    private const val PREFS = "chronos_prefs"
    private const val KEY = "comaps_follow"
    fun isEnabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun setEnabled(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
}
