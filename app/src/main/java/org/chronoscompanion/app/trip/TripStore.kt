package org.chronoscompanion.app.trip

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** One finished ride, as summed up by [TripRecorder]. Times are wall-clock ms. */
data class Trip(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    /** "route" (a route of this app), "comaps" or "maps" (Google Maps). */
    val mode: String,
    val dest: String?,
    val distanceM: Double,
    val movingS: Long,
    val maxKmh: Double,
) {
    val avgKmh: Double get() = if (movingS > 0) distanceM / movingS * 3.6 else 0.0
}

/**
 * The last [MAX_TRIPS] rides, in the app's private files (files/trips/): an index, plus
 * per ride its track (for drawing it again) and its picture. Nothing leaves the phone
 * unless the rider shares or saves the picture.
 */
object TripStore {
    private const val TAG = "TripStore"
    const val MAX_TRIPS = 10
    /** Sent (to this app only) when a ride is added, its picture redrawn, or one deleted. */
    const val ACTION_CHANGED = "org.chronoscompanion.app.TRIPS_CHANGED"
    private const val PTS_MAGIC = 0x4E4D5450 // "NMTP"

    fun dir(ctx: Context) = File(ctx.filesDir, "trips").apply { mkdirs() }
    fun imageFile(ctx: Context, id: Long) = File(dir(ctx), "trip_$id.png")
    private fun pointsFile(ctx: Context, id: Long) = File(dir(ctx), "trip_$id.pts")
    private fun indexFile(ctx: Context) = File(dir(ctx), "index.json")

    /** Newest first. */
    @Synchronized
    fun list(ctx: Context): List<Trip> {
        val f = indexFile(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Trip(
                    id = o.getLong("id"),
                    startMs = o.getLong("start"),
                    endMs = o.getLong("end"),
                    mode = o.optString("mode", "route"),
                    dest = o.optString("dest").takeIf { it.isNotEmpty() },
                    distanceM = o.getDouble("dist"),
                    movingS = o.getLong("moving"),
                    maxKmh = o.optDouble("max", 0.0),
                )
            }.sortedByDescending { it.startMs }
        } catch (e: Exception) {
            Log.w(TAG, "unreadable trip index", e)
            emptyList()
        }
    }

    fun latest(ctx: Context): Trip? = list(ctx).firstOrNull()

    fun get(ctx: Context, id: Long): Trip? = list(ctx).firstOrNull { it.id == id }

    /** Adds [trip] with its track (1e-6 degrees) and drops the oldest past MAX_TRIPS. */
    @Synchronized
    fun add(ctx: Context, trip: Trip, lat: IntArray, lon: IntArray) {
        writePoints(ctx, trip.id, lat, lon)
        val all = (listOf(trip) + list(ctx).filter { it.id != trip.id }).sortedByDescending { it.startMs }
        val keep = all.take(MAX_TRIPS)
        for (old in all.drop(MAX_TRIPS)) deleteFiles(ctx, old.id)
        writeIndex(ctx, keep)
    }

    @Synchronized
    fun delete(ctx: Context, id: Long) {
        deleteFiles(ctx, id)
        writeIndex(ctx, list(ctx).filter { it.id != id })
    }

    private fun deleteFiles(ctx: Context, id: Long) {
        imageFile(ctx, id).delete()
        pointsFile(ctx, id).delete()
    }

    private fun writeIndex(ctx: Context, trips: List<Trip>) {
        val arr = JSONArray()
        for (t in trips) {
            arr.put(JSONObject()
                .put("id", t.id).put("start", t.startMs).put("end", t.endMs)
                .put("mode", t.mode).put("dest", t.dest ?: "")
                .put("dist", t.distanceM).put("moving", t.movingS).put("max", t.maxKmh))
        }
        val tmp = File(dir(ctx), "index.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(indexFile(ctx))
    }

    private fun writePoints(ctx: Context, id: Long, lat: IntArray, lon: IntArray) {
        DataOutputStream(BufferedOutputStream(pointsFile(ctx, id).outputStream())).use { d ->
            d.writeInt(PTS_MAGIC)
            d.writeInt(lat.size)
            for (i in lat.indices) { d.writeInt(lat[i]); d.writeInt(lon[i]) }
        }
    }

    fun readPoints(ctx: Context, id: Long): Pair<IntArray, IntArray>? {
        val f = pointsFile(ctx, id)
        if (!f.exists()) return null
        return try {
            DataInputStream(BufferedInputStream(f.inputStream())).use { d ->
                if (d.readInt() != PTS_MAGIC) return null
                val n = d.readInt()
                val lat = IntArray(n)
                val lon = IntArray(n)
                for (i in 0 until n) { lat[i] = d.readInt(); lon[i] = d.readInt() }
                Pair(lat, lon)
            }
        } catch (e: Exception) {
            Log.w(TAG, "unreadable track $id", e)
            null
        }
    }

    /** Whole file or none, so a half-written picture is never shown. */
    fun writeImage(ctx: Context, id: Long, bmp: Bitmap) {
        val tmp = File(dir(ctx), "trip_$id.tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tmp.renameTo(imageFile(ctx, id))
    }

    fun notifyChanged(ctx: Context) {
        ctx.sendBroadcast(Intent(ACTION_CHANGED).setPackage(ctx.packageName))
    }
}
