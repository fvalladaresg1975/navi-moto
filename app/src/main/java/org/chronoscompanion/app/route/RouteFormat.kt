package org.chronoscompanion.app.route

import kotlin.math.roundToInt

/** Spanish formatting for what the watch shows. Matches the style Google Maps sends, so
 * the watch's own parsers (trip tracker, turn ring) read route mode exactly like Maps. */
object RouteFormat {

    /** "850 m" / "1,2 km". Below 1 km rounded to 10 m: finer than that only flickers. */
    fun distance(m: Double): String {
        val d = m.coerceAtLeast(0.0)
        return if (d >= 1000) "%.1f km".format(java.util.Locale.US, d / 1000).replace('.', ',')
        else "${(d / 10).roundToInt() * 10} m"
    }

    /** "7 min" / "1 h 34 min". */
    fun duration(seconds: Double): String {
        val min = (seconds / 60).roundToInt().coerceAtLeast(1)
        return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
    }

    /** "850 m" / "1,2 km" (Google Maps' own strings) -> metres; null if unparsable. */
    fun parseMeters(text: String): Double? {
        val m = Regex("""([\d]+(?:[.,]\d+)?)\s*(km|m)\b""").find(text.lowercase()) ?: return null
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return if (m.groupValues[2] == "km") v * 1000 else v
    }

    /** "20:36" - local arrival time. */
    fun clock(epochMs: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(epochMs))
}
