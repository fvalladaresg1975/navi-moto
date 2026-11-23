package org.chronoscompanion.app.trip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import org.chronoscompanion.app.route.StreetMap
import org.chronoscompanion.app.route.StreetTiles
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The ride's picture: the track over the streets of the area (from the phone's street
 * cache, OpenStreetMap), with distance, moving time, average and top speed. Dark, like the
 * board. 1080 x 1350 (4:5), the shape chats and galleries show whole.
 */
object TripImage {
    private const val TAG = "TripImage"
    const val W = 1080
    const val H = 1350
    /** Tiles fetched for one picture at most (~5 Overpass answers' worth of a big city):
     * a very long ride shows the streets that are cached, the track always. */
    private const val MAX_FETCH_TILES = 120

    private val worker = Executors.newSingleThreadExecutor()

    /** Saves [trip], draws it with the cached streets at once, then - if streets of the
     * area are missing and there is signal - downloads them and draws it again. */
    fun saveNew(ctx: Context, trip: Trip, lat: IntArray, lon: IntArray) {
        worker.execute {
            try {
                TripStore.add(ctx, trip, lat, lon)
                val path = pathOf(lat, lon)
                val radius = streetRadius(lat, lon)
                val map = StreetMap(centerOf(lat), centerOf(lon))
                StreetTiles.ensure(ctx, path, radius, map, fetchMissing = false)
                TripStore.writeImage(ctx, trip.id, render(trip, lat, lon, map))
                TripStore.notifyChanged(ctx)

                val missing = StreetTiles.missing(ctx, path, radius)
                if (missing.isNotEmpty() && missing.size <= MAX_FETCH_TILES) {
                    val outcome = StreetTiles.ensure(ctx, path, radius, map, fetchMissing = true)
                    Log.i(TAG, "${missing.size} street tiles missing, download: $outcome")
                    if (TripStore.get(ctx, trip.id) != null) {
                        TripStore.writeImage(ctx, trip.id, render(trip, lat, lon, map))
                        TripStore.notifyChanged(ctx)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "could not save the ride", e)
            }
        }
    }

    /** Draws a saved ride again (picture missing), cached streets only. */
    fun redraw(ctx: Context, trip: Trip, done: () -> Unit) {
        worker.execute {
            try {
                val (lat, lon) = TripStore.readPoints(ctx, trip.id) ?: return@execute
                val map = StreetMap(centerOf(lat), centerOf(lon))
                StreetTiles.ensure(ctx, pathOf(lat, lon), streetRadius(lat, lon), map, fetchMissing = false)
                TripStore.writeImage(ctx, trip.id, render(trip, lat, lon, map))
                done()
            } catch (e: Exception) {
                Log.w(TAG, "could not draw ride ${trip.id}", e)
            }
        }
    }

    private fun centerOf(v: IntArray): Double = (v.min() + v.max()) / 2.0 / 1e6

    /** A point every ~100 m of track, for picking street tiles. */
    private fun pathOf(lat: IntArray, lon: IntArray): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var lastLat = Int.MIN_VALUE
        var lastLon = 0
        for (i in lat.indices) {
            if (lastLat == Int.MIN_VALUE || i == lat.size - 1 ||
                kotlin.math.abs(lat[i] - lastLat) + kotlin.math.abs(lon[i] - lastLon) > 900) {
                out.add(Pair(lat[i] / 1e6, lon[i] / 1e6))
                lastLat = lat[i]; lastLon = lon[i]
            }
        }
        return out
    }

    /** Streets this far around the track: enough to fill the frame of a short ride. */
    private fun streetRadius(lat: IntArray, lon: IntArray): Double {
        val spanM = max((lat.max() - lat.min()) * 0.111, (lon.max() - lon.min()) * 0.095)
        return (spanM * 0.25).coerceIn(300.0, 800.0)
    }

    // --- drawing ---------------------------------------------------------------------

    private val BG = Color.parseColor("#0A0E10")
    private val PANEL = Color.parseColor("#121A1D")
    private val CARD = Color.parseColor("#151E21")
    private val TEXT = Color.parseColor("#F2F7F6")
    private val MUTED = Color.parseColor("#8FA3A0")
    private val ACCENT = Color.parseColor("#19D3BE")
    private val START = Color.parseColor("#2ECC71")
    private val STREET = intArrayOf(Color.parseColor("#223034"), Color.parseColor("#2E4045"), Color.parseColor("#3C5359"))

    fun render(trip: Trip, lat: IntArray, lon: IntArray, map: StreetMap): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(BG)
        val es = Locale("es", "CL")
        val bold = Typeface.create("sans-serif", Typeface.BOLD)
        val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val regular = Typeface.create("sans-serif", Typeface.NORMAL)

        // Header
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = bold; p.textSize = 28f; p.color = ACCENT; p.letterSpacing = 0.18f
        c.drawText("NAVI MOTO", 48f, 74f, p)
        p.typeface = medium; p.letterSpacing = 0.04f; p.color = MUTED; p.textAlign = Paint.Align.RIGHT
        c.drawText(modeLabel(trip.mode).uppercase(es), W - 48f, 74f, p)
        p.textAlign = Paint.Align.LEFT; p.letterSpacing = 0f
        p.typeface = bold; p.textSize = 58f; p.color = TEXT
        c.drawText(fit(trip.dest ?: "Viaje en moto", p, W - 96f), 48f, 150f, p)
        p.typeface = regular; p.textSize = 32f; p.color = MUTED
        val day = SimpleDateFormat("EEEE d 'de' MMMM yyyy", es).format(Date(trip.startMs))
            .replaceFirstChar { it.titlecase(es) }
        val hours = SimpleDateFormat("HH:mm", es)
        c.drawText(fit("$day · ${hours.format(Date(trip.startMs))} – ${hours.format(Date(trip.endMs))}", p, W - 96f),
            48f, 202f, p)

        // Map
        val box = RectF(48f, 240f, W - 48f, 1030f)
        val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PANEL }
        c.drawRoundRect(box, 36f, 36f, bp)
        drawMap(c, box, lat, lon, map)

        // Figures
        val gap = 16f
        val cw = (W - 96f - gap) / 2
        val ch = 124f
        val top = 1048f
        val (tv, tu) = durationParts(trip.movingS)
        cell(c, RectF(48f, top, 48f + cw, top + ch), "DISTANCIA", km(trip.distanceM), "km", bold, medium)
        cell(c, RectF(48f + cw + gap, top, W - 48f, top + ch), "TIEMPO EN MOVIMIENTO", tv, tu, bold, medium)
        cell(c, RectF(48f, top + ch + gap, 48f + cw, top + 2 * ch + gap), "VELOCIDAD MEDIA",
            trip.avgKmh.roundToInt().toString(), "km/h", bold, medium)
        cell(c, RectF(48f + cw + gap, top + ch + gap, W - 48f, top + 2 * ch + gap), "VELOCIDAD MÁXIMA",
            trip.maxKmh.roundToInt().toString(), "km/h", bold, medium)

        p.typeface = regular; p.textSize = 20f; p.color = Color.parseColor("#5E716E"); p.textAlign = Paint.Align.RIGHT
        c.drawText("Calles © colaboradores de OpenStreetMap", W - 48f, H - 10f, p)
        return bmp
    }

    private fun drawMap(c: Canvas, box: RectF, lat: IntArray, lon: IntArray, map: StreetMap) {
        val xs = DoubleArray(lat.size)
        val ys = DoubleArray(lat.size)
        for (i in lat.indices) {
            val (x, y) = map.toMap(lat[i] / 1e6, lon[i] / 1e6)
            xs[i] = x; ys[i] = y
        }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val inner = RectF(box.left + 70f, box.top + 70f, box.right - 70f, box.bottom - 90f)
        val spanX = max(maxX - minX, 300.0)
        val spanY = max(maxY - minY, 300.0)
        val scale = min(inner.width() / spanX, inner.height() / spanY) // px per metre
        val cx = (minX + maxX) / 2
        val cy = (minY + maxY) / 2
        fun px(x: Double) = (inner.centerX() + (x - cx) * scale).toFloat()
        fun py(y: Double) = (inner.centerY() - (y - cy) * scale).toFloat()

        c.save()
        val clip = Path().apply { addRoundRect(box, 36f, 36f, Path.Direction.CW) }
        c.clipPath(clip)

        // Streets: thin and faint, small ones left out when zoomed far out.
        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val zoom = (scale / 0.25).coerceIn(0.5, 2.0).toFloat()
        val halfW = box.width() / 2 / scale + 50
        val halfH = box.height() / 2 / scale + 50
        val streets = map.all()
        for (cls in 0..2) {
            if (cls == 0 && scale < 0.05) continue
            sp.color = STREET[cls]
            sp.strokeWidth = (floatArrayOf(2.5f, 4f, 6f)[cls] * zoom).coerceAtLeast(1.5f)
            val path = Path()
            for (st in streets) {
                if (st.cls != cls) continue
                if (kotlin.math.abs(st.cx - cx) - st.radius > halfW || kotlin.math.abs(st.cy - cy) - st.radius > halfH) continue
                path.moveTo(px(st.xs[0].toDouble()), py(st.ys[0].toDouble()))
                for (k in 1 until st.xs.size) path.lineTo(px(st.xs[k].toDouble()), py(st.ys[k].toDouble()))
            }
            c.drawPath(path, sp)
        }

        // Track, with a soft glow under it.
        val track = Path()
        track.moveTo(px(xs[0]), py(ys[0]))
        for (i in 1 until xs.size) track.lineTo(px(xs[i]), py(ys[i]))
        sp.color = ACCENT
        sp.alpha = 55; sp.strokeWidth = 24f
        c.drawPath(track, sp)
        sp.alpha = 255; sp.strokeWidth = 9f
        c.drawPath(track, sp)

        // Start: white dot ringed in green. Finish: chequered flag.
        val sx = px(xs[0]); val sy = py(ys[0])
        val mp = Paint(Paint.ANTI_ALIAS_FLAG)
        mp.color = START; c.drawCircle(sx, sy, 20f, mp)
        mp.color = Color.WHITE; c.drawCircle(sx, sy, 10f, mp)
        drawFlag(c, px(xs.last()), py(ys.last()))

        drawScale(c, box, scale)
        c.restore()
    }

    private fun drawFlag(c: Canvas, x: Float, y: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE; c.drawCircle(x, y, 9f, p)
        p.strokeWidth = 5f; p.strokeCap = Paint.Cap.ROUND
        c.drawLine(x, y, x, y - 64f, p)
        val left = x + 2f
        val top = y - 64f
        val cell = 11f
        for (r in 0 until 3) for (k in 0 until 4) {
            p.color = if ((r + k) % 2 == 0) Color.WHITE else Color.parseColor("#11181B")
            c.drawRect(left + k * cell, top + r * cell, left + (k + 1) * cell, top + (r + 1) * cell, p)
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = Color.WHITE
        c.drawRect(left, top, left + 4 * cell, top + 3 * cell, p)
    }

    /** A round length, 80-200 px long, bottom left of the map. */
    private fun drawScale(c: Canvas, box: RectF, scale: Double) {
        val steps = doubleArrayOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0, 50000.0, 100000.0)
        val m = steps.firstOrNull { it * scale >= 80 } ?: steps.last()
        val len = (m * scale).toFloat()
        val x0 = box.left + 36f
        val y0 = box.bottom - 36f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; strokeWidth = 4f; strokeCap = Paint.Cap.ROUND }
        c.drawLine(x0, y0, x0 + len, y0, p)
        c.drawLine(x0, y0 - 10f, x0, y0, p)
        c.drawLine(x0 + len, y0 - 10f, x0 + len, y0, p)
        p.textSize = 24f; p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        c.drawText(if (m >= 1000) "${(m / 1000).roundToInt()} km" else "${m.roundToInt()} m", x0 + len + 14f, y0 + 4f, p)
    }

    private fun cell(c: Canvas, r: RectF, label: String, value: String, unit: String, bold: Typeface, medium: Typeface) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = CARD
        c.drawRoundRect(r, 28f, 28f, p)
        p.typeface = medium; p.textSize = 24f; p.color = MUTED; p.letterSpacing = 0.08f
        c.drawText(label, r.left + 30f, r.top + 44f, p)
        p.letterSpacing = 0f
        p.typeface = bold; p.textSize = 62f; p.color = TEXT
        c.drawText(value, r.left + 30f, r.top + 106f, p)
        val vw = p.measureText(value)
        p.typeface = medium; p.textSize = 30f; p.color = MUTED
        c.drawText(unit, r.left + 30f + vw + 10f, r.top + 106f, p)
    }

    private fun fit(text: String, p: Paint, width: Float): String {
        if (p.measureText(text) <= width) return text
        var t = text
        while (t.length > 1 && p.measureText("$t…") > width) t = t.dropLast(1)
        return t.trimEnd() + "…"
    }

    fun modeLabel(mode: String) = when (mode) {
        "comaps" -> "CoMaps"
        "maps" -> "Google Maps"
        else -> "Ruta Navi Moto"
    }

    /** "12,4" (1 decimal under 100 km). */
    fun km(m: Double): String {
        val k = m / 1000
        return if (k >= 100) k.roundToInt().toString()
        else String.format(Locale.US, "%.1f", k).replace('.', ',')
    }

    /** ("28", "min") or ("1:05", "h"). */
    fun durationParts(s: Long): Pair<String, String> {
        val min = ((s + 30) / 60).coerceAtLeast(1)
        return if (min < 60) Pair(min.toString(), "min")
        else Pair("${min / 60}:${String.format(Locale.US, "%02d", min % 60)}", "h")
    }
}
