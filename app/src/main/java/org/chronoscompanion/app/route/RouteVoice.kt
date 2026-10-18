package org.chronoscompanion.app.route

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Spoken turn-by-turn for route mode, through Android's TTS.
 *
 * The point of doing this here rather than running CoMaps' or Google's navigation in
 * parallel for their voice: voice, arrow and drawn road all come from the SAME route, so
 * they can never disagree at a junction - two routers would.
 *
 * Plays as navigation guidance (USAGE_ASSISTANCE_NAVIGATION_GUIDANCE), which is what makes
 * Android route it to the Bluetooth intercom like Maps' voice, and takes transient audio
 * focus with ducking, so music dips under each announcement and comes back.
 *
 * Two announcements per maneuver, like Maps: an early one ("En 300 metros, gira a la
 * derecha en Calle 22") at a distance that grows with speed, and one at the turn ("Gira
 * a la derecha"). Each fires once.
 */
class RouteVoice(context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "RouteVoice"
        /** Seconds of travel before the turn for the "now" call: time to hear it and act. */
        private const val NOW_LEAD_S = 4.0
        private const val NOW_MIN_M = 30.0
    }

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .build()
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var pendingFirst: String? = null
    private var utteranceId = 0
    /** True while the "Ruta iniciada" confirmation is still playing. The first fix
     * arrives about a second after start and usually triggers the first early call;
     * with QUEUE_FLUSH that cut the confirmation off mid-sentence. While it plays,
     * instructions queue BEHIND it instead. Set/cleared from TTS callbacks (binder
     * thread), read on the main thread. */
    @Volatile private var startPlaying = false
    private val spokenEarly = HashSet<Double>()
    private val spokenNow = HashSet<Double>()

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS init failed: $status")
            return
        }
        // Chilean Spanish first; any Spanish voice is far better than none.
        val locale = listOf(Locale("es", "CL"), Locale("es", "US"), Locale("es", "ES"), Locale("es"))
            .firstOrNull { tts.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }
        if (locale == null) {
            Log.w(TAG, "No Spanish TTS voice installed")
            return
        }
        tts.language = locale
        tts.setAudioAttributes(attrs)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { finished(id) }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { finished(id) }
        })
        ready = true
        Log.i(TAG, "TTS ready: $locale")
        pendingFirst?.let { speak(it, start = true) }
        pendingFirst = null
    }

    /** "Ruta iniciada, 13,8 kilómetros." - confirms on the intercom that it took. */
    fun announceStart(route: Route) {
        val text = "Ruta iniciada. ${spokenDistance(route.distanceM)}."
        if (ready) speak(text, start = true) else pendingFirst = text
    }

    /** Call on every fix with the rider's position and speed. */
    fun onProgress(route: Route, s: Double, speedMps: Double) {
        if (!ready) return
        val next = route.nextManeuver(s) ?: return
        val dist = next.atM - s
        val arrive = next.glyph == RouteIcons.Glyph.ARRIVE
        val nowAt = maxOf(NOW_MIN_M, speedMps * NOW_LEAD_S)
        if (dist <= nowAt) {
            if (spokenNow.add(next.atM)) {
                spokenEarly.add(next.atM) // too late for the early call now - never say it after
                // Valhalla's at-turn phrasing chains a close follow-up turn ("... Después
                // gire a la derecha hacia ..."), which a fixed template can't.
                speak(next.atTurn ?: if (arrive) "Has llegado a tu destino" else short(next.text))
            }
            return
        }
        // Early call: further out the faster you go. If the turn is already closer than
        // that when it becomes "next" (two turns 100 m apart), it still gets one early
        // call, with the real distance.
        val earlyAt = when {
            speedMps > 22 -> 1000.0 // > ~80 km/h
            speedMps > 14 -> 500.0  // > ~50 km/h
            else -> 300.0
        }
        if (dist <= earlyAt && spokenEarly.add(next.atM)) {
            val what = next.alert?.let { lowerFirst(it) }
                ?: if (arrive) "llegas a tu destino" else lowerFirst(next.text)
            speak("En ${spokenDistance(dist)}, $what")
        }
    }

    /** One-off announcement ("Recalculando ruta"). */
    fun say(text: String) {
        if (ready) speak(text)
    }

    /** New route after a reroute: its maneuvers are different, forget what was said. */
    fun reset() {
        spokenEarly.clear()
        spokenNow.clear()
    }

    fun shutdown() {
        runCatching { tts.stop(); tts.shutdown() }
        audio.abandonAudioFocusRequest(focus)
    }

    private fun speak(text: String, start: Boolean = false) {
        Log.i(TAG, "say: $text")
        audio.requestAudioFocus(focus)
        // QUEUE_FLUSH: a newer instruction always replaces an unfinished older one - a stale
        // "in 300 m" said after the turn is worse than silence. The one exception is the
        // start confirmation, which instructions wait behind (see startPlaying).
        if (start) startPlaying = true
        val mode = if (!start && startPlaying) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        tts.speak(text, mode, null, if (start) "start" else "nav${utteranceId++}")
    }

    private fun finished(id: String?) {
        if (id == "start") startPlaying = false
        if (!tts.isSpeaking) audio.abandonAudioFocusRequest(focus)
    }

    /** At the turn itself only the action matters: "Gira a la derecha", not the street. */
    private fun short(text: String) = text.substringBefore(" en ").substringBefore(" hacia ")

    private fun lowerFirst(t: String) = t.replaceFirstChar { it.lowercase() }

    /** Rounded the way people say it: "300 metros", "1 kilómetro", "1,5 kilómetros". */
    private fun spokenDistance(m: Double): String {
        val step = if (m < 100) 50 else 100
        val rounded = maxOf(step, (m / step).roundToInt() * step)
        return when {
            rounded < 1000 -> "$rounded metros"
            m < 1050 -> "1 kilómetro"
            else -> spokenKm(m)
        }
    }

    private fun spokenKm(m: Double): String = when {
        m < 10000 -> "%.1f kilómetros".format(Locale.US, m / 1000).replace(".0 ", " ").replace('.', ',')
        else -> "${(m / 1000).toInt()} kilómetros"
    }
}
