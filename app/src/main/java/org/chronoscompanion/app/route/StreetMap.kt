package org.chronoscompanion.app.route

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The streets around the route, for the watch's faint street layer under the route line.
 *
 * Coordinates live in their own local metric frame (x = east, y = north, metres) fixed for
 * the whole trip, NOT the route's frame: a reroute builds a new Route with a new origin,
 * but the streets already on the watch must stay valid. The watch keeps them in this frame
 * and places them itself from the rider pose sent with every route frame, so streets cross
 * the link once instead of every second.
 *
 * Long ways are cut into [Street] pieces (<= MAX_PIECE_POINTS points, <= MAX_PIECE_M long)
 * so a piece fits a packet with others and can be culled by distance on its own.
 *
 * Filled from [StreetTiles] (the phone's tile cache), from any thread: everything that
 * touches the collections is synchronized.
 */
class StreetMap(val originLat: Double, val originLon: Double) {

    class Street(val id: Long, val cls: Int, val xs: IntArray, val ys: IntArray) {
        /** Bounding circle, for "is it anywhere near the rider" checks. */
        val cx: Int
        val cy: Int
        val radius: Int

        init {
            var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE
            var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
            for (i in xs.indices) {
                minX = minOf(minX, xs[i]); maxX = maxOf(maxX, xs[i])
                minY = minOf(minY, ys[i]); maxY = maxOf(maxY, ys[i])
            }
            cx = (minX + maxX) / 2
            cy = (minY + maxY) / 2
            radius = hypot((maxX - minX) / 2.0, (maxY - minY) / 2.0).roundToInt() + 1
        }
    }

    private val kx = OsrmClient.M_PER_DEG_LON * cos(Math.toRadians(originLat))
    private val streets = LinkedHashMap<Long, Street>()
    /** Tiles already turned into pieces here (StreetTiles.Key.name). */
    private val loadedTiles = HashSet<String>()

    fun toMap(lat: Double, lon: Double): Pair<Double, Double> =
        Pair((lon - originLon) * kx, (lat - originLat) * OsrmClient.M_PER_DEG_LAT)

    /** Adds one tile's ways. A way spanning several tiles yields the same piece ids from
     * each, so it simply replaces itself. */
    fun addTile(name: String, ways: List<StreetTiles.Way>) {
        val out = ArrayList<Street>()
        for (w in ways) {
            val xs = DoubleArray(w.lat.size)
            val ys = DoubleArray(w.lat.size)
            for (i in w.lat.indices) {
                val (x, y) = toMap(w.lat[i] / 1e6, w.lon[i] / 1e6)
                xs[i] = x; ys[i] = y
            }
            out.addAll(pieces(w.id, w.cls, xs, ys))
        }
        synchronized(this) {
            for (s in out) streets[s.id] = s
            loadedTiles.add(name)
        }
    }

    @Synchronized
    fun hasTile(name: String): Boolean = name in loadedTiles

    /** Pieces already in this map's frame - the demo's made-up city. */
    @Synchronized
    fun addStreets(list: List<Street>) {
        for (s in list) streets[s.id] = s
    }

    @Synchronized
    fun size(): Int = streets.size

    /** Pieces whose bounding circle comes within [radiusM] of (x, y) and are not in
     * [exclude], nearest first. */
    @Synchronized
    fun near(x: Double, y: Double, radiusM: Double, exclude: Set<Long>): List<Street> =
        streets.values
            .filter { it.id !in exclude && hypot(it.cx - x, it.cy - y) - it.radius <= radiusM }
            .sortedBy { hypot(it.cx - x, it.cy - y) - it.radius }

    companion object {
        /** Corridor around the route whose tiles are loaded: the view shows up to ~390 m
         * to each side, but streets that far from the route are of no use to the rider. */
        const val CORRIDOR_M = 250
        /** Loaded in stretches of route this long. */
        const val WINDOW_M = 10000.0

        /** (lat, lon) every ~100 m along [r] between arclengths [from] and [to]. */
        fun corridorPath(r: Route, from: Double, to: Double): List<Pair<Double, Double>> {
            val steps = ((to - from) / 100.0).toInt().coerceAtLeast(1)
            return (0..steps).map { i ->
                val (x, y) = r.road.pointAt(from + (to - from) * i / steps)
                r.toLatLon(x, y)
            }
        }

        const val MAX_PIECE_POINTS = 30
        const val MAX_PIECE_M = 1500.0
        /** Douglas-Peucker tolerance: well under a watch pixel even zoomed in (1.2 px/m). */
        private const val SIMPLIFY_M = 2.5

        /** Splits one OSM way (already in map metres) into simplified pieces. */
        fun pieces(wayId: Long, cls: Int, xs: DoubleArray, ys: DoubleArray): List<Street> {
            if (xs.size < 2) return emptyList()
            val keep = simplify(xs, ys)
            val out = ArrayList<Street>()
            var start = 0
            var k = 0
            while (start < keep.size - 1) {
                var end = start + 1
                var len = 0.0
                while (end < keep.size && end - start < MAX_PIECE_POINTS) {
                    val a = keep[end - 1]; val b = keep[end]
                    val step = hypot(xs[b] - xs[a], ys[b] - ys[a])
                    if (len + step > MAX_PIECE_M && end - start >= 2) break
                    len += step
                    end++
                }
                // pieces share their joining vertex, so the street stays continuous
                val idx = keep.subList(start, end)
                out.add(Street(wayId * 1000 + k,
                    cls,
                    IntArray(idx.size) { xs[idx[it]].roundToInt() },
                    IntArray(idx.size) { ys[idx[it]].roundToInt() }))
                k++
                start = end - 1
            }
            return out
        }

        /** Indices of the vertices kept by Douglas-Peucker at [SIMPLIFY_M]. */
        private fun simplify(xs: DoubleArray, ys: DoubleArray): List<Int> {
            val keep = BooleanArray(xs.size)
            keep[0] = true
            keep[xs.size - 1] = true
            val stack = ArrayDeque<Pair<Int, Int>>()
            stack.addLast(Pair(0, xs.size - 1))
            while (stack.isNotEmpty()) {
                val (a, b) = stack.removeLast()
                if (b - a < 2) continue
                val dx = xs[b] - xs[a]; val dy = ys[b] - ys[a]
                val len = max(hypot(dx, dy), 1e-6)
                var worst = -1
                var worstD = SIMPLIFY_M
                for (i in a + 1 until b) {
                    val d = abs((xs[i] - xs[a]) * dy - (ys[i] - ys[a]) * dx) / len
                    if (d > worstD) { worstD = d; worst = i }
                }
                if (worst >= 0) {
                    keep[worst] = true
                    stack.addLast(Pair(a, worst))
                    stack.addLast(Pair(worst, b))
                }
            }
            return keep.indices.filter { keep[it] }
        }
    }
}

/**
 * The phone's street cache: OpenStreetMap streets saved in tiles of 0.01 x 0.01 degrees
 * (~1.1 x 0.9 km here), one small file each under files/streets/. A tile downloaded once -
 * the whole route's corridor as soon as the share screen computes it (StreetPrefetch), or
 * on a ride - loads instantly and without signal from then on; only missing tiles go to
 * Overpass. Tiles older than MAX_AGE_MS are still used, and refreshed when online.
 *
 * All functions here block (file and network I/O): worker threads only.
 */
object StreetTiles {
    private const val TAG = "StreetTiles"
    private const val TILE_DEG = 0.01
    private const val MAX_AGE_MS = 90L * 24 * 3600 * 1000
    /** Tiles per Overpass request. Each answer takes ~30-40 s whatever its size, so
     * fewer, bigger requests; 40 dense city tiles stay well inside the server's limits. */
    private const val BATCH = 40
    private const val MAGIC = 0x4E4D5354 // "NMST"
    /** v2 adds the download time to the header: the file's own date now means "last
     * used", for the size cap. v1 files (first day only) read as missing and refetch. */
    private const val VERSION = 2
    /** Size cap: above it the tiles unused the longest are deleted down to TRIM_TO_BYTES.
     * ~50 MB is ~4000 km2 of dense city - far more than a rider's usual area - so in
     * practice only one-off trips ever get trimmed, and they refetch if ridden again. */
    private const val MAX_CACHE_BYTES = 50L * 1024 * 1024
    private const val TRIM_TO_BYTES = 40L * 1024 * 1024

    /** One OSM way, coordinates in 1e-6 degrees. */
    class Way(val id: Long, val cls: Int, val lat: IntArray, val lon: IntArray) {
        val minLat = lat.minOf { it } / 1e6
        val maxLat = lat.maxOf { it } / 1e6
        val minLon = lon.minOf { it } / 1e6
        val maxLon = lon.maxOf { it } / 1e6
    }

    data class Key(val ilat: Int, val ilon: Int) {
        val name: String get() = "${ilat}_$ilon"
        val south: Double get() = ilat * TILE_DEG
        val west: Double get() = ilon * TILE_DEG
        val north: Double get() = (ilat + 1) * TILE_DEG
        val east: Double get() = (ilon + 1) * TILE_DEG
    }

    /** Tiles within [radiusM] of any of [points] (lat, lon). */
    fun tilesAround(points: List<Pair<Double, Double>>, radiusM: Double): Set<Key> {
        val out = LinkedHashSet<Key>()
        for ((lat, lon) in points) {
            val dLat = radiusM / OsrmClient.M_PER_DEG_LAT
            val dLon = radiusM / (OsrmClient.M_PER_DEG_LON * cos(Math.toRadians(lat)))
            for (a in floor((lat - dLat) / TILE_DEG).toInt()..floor((lat + dLat) / TILE_DEG).toInt())
                for (b in floor((lon - dLon) / TILE_DEG).toInt()..floor((lon + dLon) / TILE_DEG).toInt())
                    out.add(Key(a, b))
        }
        return out
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "streets").apply { mkdirs() }
    private fun file(ctx: Context, k: Key) = File(dir(ctx), k.name + ".bin")

    /** Missing, unreadable, or downloaded more than MAX_AGE_MS ago. */
    fun needsFetch(ctx: Context, k: Key): Boolean {
        val f = file(ctx, k)
        if (!f.exists()) return true
        val fetchedAt = try {
            DataInputStream(f.inputStream()).use { d ->
                if (d.readInt() != MAGIC || d.readInt() != VERSION) return true
                d.readLong()
            }
        } catch (e: Exception) {
            return true
        }
        return System.currentTimeMillis() - fetchedAt > MAX_AGE_MS
    }

    enum class Outcome {
        /** Every tile is in the map. */
        OK,
        /** Some tiles are being downloaded right now by another job (the route download):
         * the rest is in the map; ask again in a few seconds. */
        PENDING,
        /** A download failed: whatever was cached is in the map; retry later. */
        FAILED,
    }

    /** Tiles some job is downloading right now, so two jobs never ask Overpass twice. */
    private val inFlight = HashSet<String>()

    /**
     * Puts the tiles around [path] into [map]: cached ones at once, then fetches what is
     * missing or old and adds that too.
     */
    fun ensure(ctx: Context, path: List<Pair<Double, Double>>, radiusM: Double, map: StreetMap): Outcome {
        val keys = tilesAround(path, radiusM)
        val toFetch = ArrayList<Key>()
        var pending = false
        for (k in keys) {
            if (!map.hasTile(k.name)) read(ctx, k)?.let { map.addTile(k.name, it) }
            if (needsFetch(ctx, k)) {
                if (synchronized(inFlight) { k.name in inFlight }) pending = true else toFetch.add(k)
            }
        }
        Log.i(TAG, "ensure: ${keys.size} tiles, ${keys.size - toFetch.size} cached, fetching ${toFetch.size}, pending=$pending")
        return try {
            fetch(ctx, toFetch) { k, ways -> map.addTile(k.name, ways) }
            if (pending) Outcome.PENDING else Outcome.OK
        } catch (e: Exception) {
            Log.w(TAG, "fetch failed", e)
            Outcome.FAILED
        }
    }

    /** Downloads [keys] in batches, saving each tile; [onTile] gets every saved tile.
     * [onBatch] reports progress (tiles done). Throws on the first failed batch. */
    fun fetch(ctx: Context, keys: List<Key>, onBatch: (Int) -> Unit = {},
              stop: () -> Boolean = { false }, onTile: (Key, List<Way>) -> Unit) {
        var done = 0
        for (chunk in keys.chunked(BATCH)) {
            if (stop()) return
            // Another job may have saved some of these since the list was made.
            val batch = chunk.filter { needsFetch(ctx, it) }
            if (batch.isEmpty()) {
                done += chunk.size
                onBatch(done)
                continue
            }
            synchronized(inFlight) { batch.forEach { inFlight.add(it.name) } }
            try {
                val ways = fetchWithRetry(batch)
                for (k in batch) {
                    // A way belongs to every tile its bounding box touches (full geometry
                    // kept, so one crossing several tiles is stored whole in each).
                    val mine = ways.filter { w ->
                        w.minLat <= k.north && w.maxLat >= k.south && w.minLon <= k.east && w.maxLon >= k.west
                    }
                    write(ctx, k, mine)
                    onTile(k, mine)
                }
            } finally {
                synchronized(inFlight) { batch.forEach { inFlight.remove(it.name) } }
            }
            done += chunk.size
            onBatch(done)
        }
        trim(ctx)
    }

    /** Overpass answers 429 (too many requests) or 504 (gateway timeout) when it is busy -
     * seen on the user's 102 km route download, which then stopped. Those are worth a
     * patient retry (15 s, then 45 s); anything else fails at once. */
    private fun fetchWithRetry(batch: List<Key>): List<Way> {
        val waits = longArrayOf(15000L, 45000L)
        var attempt = 0
        while (true) {
            try {
                return OverpassClient.fetchBoxes(batch)
            } catch (e: OverpassClient.BusyException) {
                if (attempt >= waits.size) throw e
                Log.w(TAG, "Overpass busy (${e.message}), retrying in ${waits[attempt] / 1000} s")
                Thread.sleep(waits[attempt])
                attempt++
            }
        }
    }

    private fun read(ctx: Context, k: Key): List<Way>? {
        val f = file(ctx, k)
        if (!f.exists()) return null
        return try {
            val ways = DataInputStream(BufferedInputStream(f.inputStream())).use { d ->
                if (d.readInt() != MAGIC || d.readInt() != VERSION) return null
                d.readLong() // downloaded at (see needsFetch)
                val n = d.readInt()
                List(n) {
                    val id = d.readLong()
                    val cls = d.readByte().toInt()
                    val pts = d.readUnsignedShort()
                    val lat = IntArray(pts)
                    val lon = IntArray(pts)
                    for (i in 0 until pts) { lat[i] = d.readInt(); lon[i] = d.readInt() }
                    Way(id, cls, lat, lon)
                }
            }
            f.setLastModified(System.currentTimeMillis()) // "used now", for trim()
            ways
        } catch (e: Exception) {
            Log.w(TAG, "unreadable tile ${k.name}, will refetch", e)
            f.delete()
            null
        }
    }

    private fun write(ctx: Context, k: Key, ways: List<Way>) {
        val tmp = File(dir(ctx), k.name + ".tmp")
        DataOutputStream(BufferedOutputStream(tmp.outputStream())).use { d ->
            d.writeInt(MAGIC)
            d.writeInt(VERSION)
            d.writeLong(System.currentTimeMillis())
            d.writeInt(ways.size)
            for (w in ways) {
                d.writeLong(w.id)
                d.writeByte(w.cls)
                d.writeShort(w.lat.size)
                for (i in w.lat.indices) { d.writeInt(w.lat[i]); d.writeInt(w.lon[i]) }
            }
        }
        tmp.renameTo(file(ctx, k)) // whole tile or none, even if the app dies mid-write
    }

    /** Tiles and bytes in the cache. */
    fun stats(ctx: Context): Pair<Int, Long> {
        val files = dir(ctx).listFiles { f -> f.name.endsWith(".bin") } ?: return Pair(0, 0L)
        return Pair(files.size, files.sumOf { it.length() })
    }

    /** Over MAX_CACHE_BYTES: deletes the tiles unused the longest (file date = last
     * load or download) down to TRIM_TO_BYTES. Tiles of the area ridden often are
     * touched on every ride, so they are the last to go. */
    @Synchronized
    private fun trim(ctx: Context) {
        val files = dir(ctx).listFiles { f -> f.name.endsWith(".bin") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        var deleted = 0
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= TRIM_TO_BYTES) break
            val len = f.length()
            if (f.delete()) { total -= len; deleted++ }
        }
        Log.i(TAG, "trim: deleted $deleted least-recently-used tiles, cache now ${total / 1024} KB")
    }

    /** "Borrar calles guardadas". Streets already in a running trip's map stay there. */
    @Synchronized
    fun clear(ctx: Context): Int {
        val files = dir(ctx).listFiles() ?: return 0
        return files.count { it.delete() }
    }
}

/**
 * Street geometry from the public Overpass API (OpenStreetMap - the same data Valhalla
 * routes on). Only ever asked for tiles the phone does not have yet, as the server's
 * fair-use policy asks.
 */
object OverpassClient {
    private const val TAG = "Overpass"
    private const val URL_API = "https://overpass-api.de/api/interpreter"
    private const val USER_AGENT = "NaviMoto/1.0 (personal handlebar display; fvalladaresg1975)"

    /** The server is overloaded right now (429/502/503/504): try again later. */
    class BusyException(msg: String) : Exception(msg)

    /** Drivable streets only: footways, tracks, parking aisles etc. would just be noise. */
    private val CLASS = mapOf(
        "motorway" to 2, "trunk" to 2, "primary" to 2,
        "motorway_link" to 2, "trunk_link" to 2, "primary_link" to 2,
        "secondary" to 1, "tertiary" to 1, "secondary_link" to 1, "tertiary_link" to 1,
        "unclassified" to 0, "residential" to 0, "living_street" to 0,
    )

    /** Blocking - call off the main thread. Every drivable way touching any of [tiles]. */
    fun fetchBoxes(tiles: List<StreetTiles.Key>): List<StreetTiles.Way> {
        if (tiles.isEmpty()) return emptyList()
        val filter = "[\"highway\"~\"^(${CLASS.keys.joinToString("|")})$\"]"
        val boxes = tiles.joinToString("") {
            String.format(java.util.Locale.US, "way(%.4f,%.4f,%.4f,%.4f)%s;", it.south, it.west, it.north, it.east, filter)
        }
        // Measured 2026-10-16: ~30-40 s per answer however small the query (a 1 km and a
        // bbox query took the same) - it is the server's queue, not the work. Hence the
        // long timeouts, few big requests, and the cache.
        val query = "[out:json][timeout:120];($boxes);out geom;"
        val conn = URL(URL_API).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 150000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
        val code = conn.responseCode
        if (code != 200) {
            conn.disconnect()
            if (code == 429 || code == 502 || code == 503 || code == 504) throw BusyException("HTTP $code")
            throw IllegalStateException("Overpass HTTP $code")
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val elements = JSONObject(body).getJSONArray("elements")
        val out = ArrayList<StreetTiles.Way>()
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            if (el.optString("type") != "way") continue
            val cls = CLASS[el.optJSONObject("tags")?.optString("highway")] ?: continue
            val geom = el.optJSONArray("geometry") ?: continue
            if (geom.length() < 2) continue
            val lat = IntArray(geom.length())
            val lon = IntArray(geom.length())
            for (j in 0 until geom.length()) {
                val p = geom.getJSONObject(j)
                lat[j] = (p.getDouble("lat") * 1e6).roundToInt()
                lon[j] = (p.getDouble("lon") * 1e6).roundToInt()
            }
            out.add(StreetTiles.Way(el.getLong("id"), cls, lat, lon))
        }
        Log.i(TAG, "${tiles.size} tiles: ${out.size} ways, ${body.length / 1024} KB")
        return out
    }
}

/**
 * Downloads the streets of the WHOLE route into the phone's cache as soon as the route is
 * computed - while the rider is still reading the summary - so on the ride every stretch
 * loads from the cache instantly, and without signal (user's request: "descargar las
 * caches de la ruta que voy a tomar"). The first stretch also goes straight into a
 * StreetMap, which the service adopts in startRoute() if it is for the route being
 * started; the rest only fills the cache. Tiles go in route order, nearest first. Runs on
 * after the share screen closes (the service keeps the process alive).
 */
object StreetPrefetch {
    class Job(val route: Route, val map: StreetMap, val toS: Double) {
        /** Both only touched on the main thread. */
        var done = false
        var outcome = StreetTiles.Outcome.FAILED
        /** Set by the service when it adopts an unfinished job; called on the main thread. */
        var onDone: (() -> Unit)? = null
    }

    private var job: Job? = null
    /** The newest job: an older one (the rider changed the route options and a new route
     * was computed) stops downloading at its next batch. Not cleared by take(). */
    @Volatile private var latest: Job? = null
    /** Route-download progress for the share screen (main thread): tiles done, tiles to
     * download, finished, ok. */
    var progress: ((Int, Int, Boolean, Boolean) -> Unit)? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** Not single-threaded: a new route's first stretch must not wait behind the old
     * route's batch still on the air (StreetTiles' in-flight set prevents double asks). */
    private val worker = java.util.concurrent.Executors.newCachedThreadPool()

    /** Main thread. */
    fun start(ctx: Context, route: Route) {
        if (!StreetMapPrefs.isEnabled(ctx)) return
        val app = ctx.applicationContext
        val map = StreetMap(route.originLat, route.originLon)
        val to = minOf(route.road.lengthM, StreetMap.WINDOW_M)
        val radius = StreetMap.CORRIDOR_M.toDouble()
        val j = Job(route, map, to)
        job = j
        latest = j
        worker.execute {
            if (latest !== j) return@execute // superseded before it even started
            val allKeys = StreetTiles.tilesAround(StreetMap.corridorPath(route, 0.0, route.road.lengthM), radius)
            val total = allKeys.count { StreetTiles.needsFetch(app, it) }
            main.post { progress?.invoke(0, total, false, true) }
            // First stretch: into the map the ride starts with.
            val first = StreetTiles.ensure(app, StreetMap.corridorPath(route, 0.0, to), radius, map)
            main.post {
                j.outcome = first
                j.done = true
                j.onDone?.invoke()
            }
            // The rest of the route: into the cache.
            val rest = allKeys.filter { StreetTiles.needsFetch(app, it) }
            val doneFirst = total - rest.size
            val ok = first != StreetTiles.Outcome.FAILED && try {
                StreetTiles.fetch(app, rest,
                    onBatch = { d -> main.post { progress?.invoke(doneFirst + d, total, false, true) } },
                    stop = { latest !== j }) { _, _ -> }
                true
            } catch (e: Exception) {
                Log.w("StreetPrefetch", "route download stopped", e)
                false
            }
            Log.i("StreetPrefetch", "route download: $total tiles to fetch, ok=$ok, superseded=${latest !== j}")
            if (latest === j) main.post { progress?.invoke(total, total, true, ok) }
        }
    }

    /** Main thread. The job for [route], if one was started for exactly that route. */
    fun take(route: Route): Job? {
        val j = job
        job = null
        return j?.takeIf { it.route === route }
    }
}

/** On/off for the street layer, remembered. On by default: the user asked for it. */
object StreetMapPrefs {
    private const val PREFS = "chronos_prefs"
    private const val KEY = "street_map"
    fun isEnabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
    fun setEnabled(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
}
