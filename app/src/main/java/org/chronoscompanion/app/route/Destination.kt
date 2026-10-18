package org.chronoscompanion.app.route

import android.content.Intent
import android.util.Log
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Where the rider wants to go. [approximate] = found by searching a NAME, not given as
 * coordinates, so the UI must show what was found and let the rider confirm it.
 */
data class Destination(val lat: Double, val lon: Double, val name: String, val approximate: Boolean)

/**
 * Turns what other apps share into a [Destination]. Formats verified on the user's phone
 * in phase 0 (see the phase-0 backup README):
 *  - CoMaps: "...https://comaps.at/<code>/<Name_With_Underscores> ... geo:LAT,LON ..."
 *    -> exact coordinates, offline. The primary path.
 *  - Google Maps: only "https://maps.app.goo.gl/..." + the name as EXTRA_SUBJECT. No
 *    coordinates anywhere - not in the link, not in the redirect, not usable in the page.
 *    -> resolve the link to the full place name and geocode it with Nominatim.
 *  - Any app's "Open with" geo: URI -> direct.
 */
object DestinationParser {
    private const val TAG = "DestinationParser"
    private const val USER_AGENT = "chronos-nav/0.2 (personal handlebar display; fvalladaresg1975)"

    private val GEO = Regex("""geo:(-?\d{1,2}(?:\.\d+)?),(-?\d{1,3}(?:\.\d+)?)""")
    /** A bare "lat, lon" pair - Google's shares of a dropped pin, and plain pasted text. */
    private val BARE = Regex("""(-?\d{1,2}\.\d{3,}),\s*(-?\d{1,3}\.\d{3,})""")
    private val COMAPS_NAME = Regex("""https?://comaps\.at/[^/\s]+/([^\s]+)""")
    private val GOOGLE = Regex("""https?://(?:maps\.app\.goo\.gl|goo\.gl/maps|(?:www\.)?google\.[a-z.]+/maps)\S*""")

    /** Offline parse. Null if the intent has no coordinates in it. */
    fun parseLocal(intent: Intent): Destination? {
        val text = sharedText(intent)
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        val m = GEO.find(text) ?: BARE.find(text) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val name = COMAPS_NAME.find(text)?.groupValues?.get(1)?.let { decodeName(it) }
            ?: geoQueryName(text)
            ?: subject.ifBlank { "%.5f, %.5f".format(java.util.Locale.US, lat, lon) }
        return Destination(lat, lon, name, approximate = false)
    }

    fun googleLink(intent: Intent): String? = GOOGLE.find(sharedText(intent))?.value

    /**
     * Blocking, network. Follows the Google short link and reads the destination out of
     * whichever URL it lands on:
     *  - a place ("/maps/place/<name>/...!3d<lat>!4d<lon>"),
     *  - directions ("/maps/dir/<from>/<to>/...!1d<lon>!2d<lat>..." - the last point is
     *    the destination; "Ruta compartida" shares from Maps' directions screen, which the
     *    first version didn't understand: it then geocoded the words "Ruta compartida"),
     *  - a search or ?q= / destination= / daddr= parameter.
     * Exact coordinates when the URL carries them; otherwise the name, geocoded with
     * Nominatim. Generic share subjects ("Ruta compartida") are never used as a name.
     * Null if nothing usable was found.
     */
    fun resolveGoogle(link: String, subject: String, sharedText: String = ""): Destination? {
        var name: String? = null
        for (url in followRedirects(link)) {
            Log.i(TAG, "Google URL: ${url.take(300)}")
            val n = nameFromUrl(url)
            if (n != null) name = n
            coordsFromUrl(url)?.let { (lat, lon) ->
                Log.i(TAG, "Google coordinates in URL: $lat, $lon ($n)")
                return Destination(lat, lon, n ?: name ?: subject.takeUnless { isGeneric(it) }
                    ?: "%.5f, %.5f".format(java.util.Locale.US, lat, lon), approximate = false)
            }
        }
        val query = name
            ?: textName(sharedText)
            ?: subject.takeUnless { it.isBlank() || isGeneric(it) }
            ?: return null
        Log.i(TAG, "Google place name: $query")
        return geocode(query)
    }

    /** Share subjects that name the kind of share, not the place. */
    private fun isGeneric(s: String): Boolean =
        Regex("""(?i)^\s*(ruta compartida|ubicaci[oó]n compartida|compartir|shared route|directions|indicaciones)\s*$""")
            .matches(s)

    private val PLACE_COORDS = Regex("""!3d(-?\d{1,2}\.\d+)!4d(-?\d{1,3}\.\d+)""")
    private val DIR_POINT = Regex("""!1d(-?\d{1,3}\.\d+)!2d(-?\d{1,2}\.\d+)""")

    private fun coordsFromUrl(url: String): Pair<Double, Double>? {
        PLACE_COORDS.find(url)?.let { m ->
            return Pair(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
        }
        if ("/maps/dir/" in url) {
            // Waypoints in order, lon first: the last one is where the route ends.
            DIR_POINT.findAll(url).lastOrNull()?.let { m ->
                return Pair(m.groupValues[2].toDouble(), m.groupValues[1].toDouble())
            }
        }
        return null
    }

    private fun nameFromUrl(url: String): String? {
        fun dec(s: String) = URLDecoder.decode(s, "UTF-8").replace('+', ' ').trim().ifBlank { null }
        Regex("""/maps/(?:place|search)/([^/?]+)""").find(url)?.let { return dec(it.groupValues[1]) }
        Regex("""/maps/dir/([^?]*)""").find(url)?.let { m ->
            // /maps/dir/<from>/<to>/@lat,lon,z/data=...: the last real segment is <to>.
            return m.groupValues[1].split('/')
                .filter { it.isNotBlank() && !it.startsWith("@") && !it.startsWith("data=") }
                .lastOrNull()?.let { dec(it) }
        }
        Regex("""[?&](?:destination|daddr|q)=([^&#]+)""").find(url)?.let { return dec(it.groupValues[1]) }
        return null
    }

    /** The shared text minus links, if that reads like an address or place name. */
    private fun textName(text: String): String? =
        text.replace(Regex("""https?://\S+"""), " ").replace(Regex("""\s+"""), " ").trim()
            .takeIf { it.length >= 4 && !isGeneric(it) }

    private fun sharedText(intent: Intent): String =
        listOfNotNull(intent.getStringExtra(Intent.EXTRA_TEXT), intent.dataString).joinToString(" ")

    /** The text another app shared, for resolveGoogle's fallbacks. */
    fun sharedTextOf(intent: Intent): String = sharedText(intent)

    private fun decodeName(raw: String): String =
        URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8").replace('_', ' ').trim()

    private fun geoQueryName(text: String): String? {
        val q = Regex("""[?&]q=([^&\s]+)""").find(text)?.groupValues?.get(1) ?: return null
        return URLDecoder.decode(q, "UTF-8").replace(Regex("""^-?[\d.]+,-?[\d.]+\s*"""), "")
            .trim('(', ')', ' ').ifBlank { null }
    }

    /** The short link and every URL it redirects through, in order (at most 6). */
    private fun followRedirects(start: String): List<String> {
        val out = arrayListOf(start)
        var url = start
        repeat(5) {
            if ("/maps/place/" in url || "/maps/dir/" in url) return out // the one we need
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Mobile")
            }
            try {
                val next = conn.getHeaderField("Location") ?: return out
                url = if (next.startsWith("http")) next else URL(URL(url), next).toString()
                out.add(url)
            } catch (e: Exception) {
                Log.w(TAG, "redirect failed", e)
                return out
            } finally {
                conn.disconnect()
            }
        }
        return out
    }

    private fun geocode(query: String): Destination? {
        val url = URL(
            "https://nominatim.openstreetmap.org/search?format=json&limit=1&accept-language=es&q=" +
                java.net.URLEncoder.encode(query, "UTF-8")
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            if (arr.length() == 0) return null
            val r = arr.getJSONObject(0)
            Destination(r.getString("lat").toDouble(), r.getString("lon").toDouble(),
                r.optString("display_name", query), approximate = true)
        } finally {
            conn.disconnect()
        }
    }
}
