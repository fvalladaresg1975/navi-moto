package org.chronoscompanion.app.route

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import org.chronoscompanion.app.ble.ChronosProtocol

/**
 * Turn arrows drawn on the phone as 48x48 1bpp bitmaps - the exact format Google Maps'
 * icons arrive in - so route mode reuses the watch's existing icon path (canvas, CRC
 * dedup, data-before-icon ordering) unchanged.
 */
object RouteIcons {

    enum class Glyph { STRAIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, LEFT, RIGHT, SHARP_LEFT, SHARP_RIGHT, UTURN, ROUNDABOUT, ARRIVE }

    private val cache = HashMap<Glyph, ByteArray>()

    fun bits(glyph: Glyph): ByteArray = cache.getOrPut(glyph) { draw(glyph) }

    private fun draw(glyph: Glyph): ByteArray {
        val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 7f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        val left = glyph == Glyph.SLIGHT_LEFT || glyph == Glyph.LEFT || glyph == Glyph.SHARP_LEFT
        fun mx(v: Float) = if (left) 47f - v else v
        fun head(tipX: Float, tipY: Float, ax: Float, ay: Float, bx: Float, by: Float) =
            c.drawPath(Path().apply { moveTo(mx(tipX), tipY); lineTo(mx(ax), ay); lineTo(mx(bx), by); close() }, fill)

        when (glyph) {
            Glyph.STRAIGHT -> {
                c.drawLine(24f, 44f, 24f, 16f, stroke)
                c.drawPath(Path().apply { moveTo(12f, 18f); lineTo(24f, 3f); lineTo(36f, 18f); close() }, fill)
            }
            Glyph.SLIGHT_LEFT, Glyph.SLIGHT_RIGHT -> {
                c.drawPath(Path().apply { moveTo(mx(18f), 44f); lineTo(mx(18f), 30f); lineTo(mx(31f), 15f) }, stroke)
                head(41f, 4f, 25f, 10f, 37f, 21f)
            }
            Glyph.LEFT, Glyph.RIGHT -> {
                c.drawPath(Path().apply { moveTo(mx(16f), 44f); lineTo(mx(16f), 20f); lineTo(mx(32f), 20f) }, stroke)
                head(45f, 20f, 31f, 9f, 31f, 31f)
            }
            Glyph.SHARP_LEFT, Glyph.SHARP_RIGHT -> {
                c.drawPath(Path().apply { moveTo(mx(14f), 44f); lineTo(mx(14f), 10f); lineTo(mx(30f), 28f) }, stroke)
                head(38f, 38f, 24f, 34f, 36f, 22f)
            }
            Glyph.UTURN -> {
                c.drawPath(Path().apply { moveTo(30f, 44f); lineTo(30f, 18f); quadTo(30f, 6f, 20f, 6f); quadTo(10f, 6f, 10f, 18f); lineTo(10f, 28f) }, stroke)
                c.drawPath(Path().apply { moveTo(1f, 26f); lineTo(19f, 26f); lineTo(10f, 40f); close() }, fill)
            }
            Glyph.ROUNDABOUT -> {
                c.drawCircle(24f, 22f, 11f, stroke)
                c.drawLine(24f, 44f, 24f, 34f, stroke)
                c.drawLine(32f, 14f, 41f, 5f, stroke)
            }
            Glyph.ARRIVE -> {
                // Chequered finish flag: the user didn't recognise the earlier map pin
                // ("¿qué significa este símbolo?") - a finish flag reads as "you arrive".
                c.drawLine(9f, 5f, 9f, 45f, Paint(stroke).apply { strokeWidth = 5f })
                val x0 = 12f; val y0 = 5f; val cw = 8f; val ch = 7f
                for (row in 0 until 3) for (col in 0 until 4) {
                    if ((row + col) % 2 == 0) {
                        c.drawRect(x0 + col * cw, y0 + row * ch, x0 + (col + 1) * cw, y0 + (row + 1) * ch, fill)
                    }
                }
                c.drawRect(x0, y0, x0 + 4 * cw, y0 + 3 * ch,
                    Paint(stroke).apply { strokeWidth = 2.5f; strokeJoin = Paint.Join.MITER })
            }
        }
        val lit = BooleanArray(48 * 48)
        for (yy in 0 until 48) for (xx in 0 until 48) {
            val p = bmp.getPixel(xx, yy)
            lit[yy * 48 + xx] = Color.alpha(p) > 64 && Color.red(p) > 128
        }
        bmp.recycle()
        return ChronosProtocol.packIconBits(lit)
    }
}
