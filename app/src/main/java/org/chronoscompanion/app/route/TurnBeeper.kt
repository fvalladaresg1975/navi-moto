package org.chronoscompanion.app.route

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A beep at the moment to turn, through the intercom - the last "it's HERE" cue after
 * the voice has announced the turn. One beep per maneuver; a distinct double beep for
 * the destination.
 *
 * WHY THE PHONE AND NOT THE WATCH: the board has no speaker fitted (only a connector) and
 * a handlebar speaker can't be heard in a helmet at speed anyway. Played as navigation
 * guidance, like the voice, so Android routes it to the Bluetooth intercom and ducks music.
 *
 * WHEN: by TIME, not a fixed distance - [LEAD_S] seconds before the turn point, at least
 * [MIN_M] metres out. A fixed 30 m would be 1 s at 100 km/h (too late) and 5 s at 20 km/h
 * (confusable with the corner before). And PREDICTED, not polled: GPS is 1 Hz, so at
 * 60 km/h the rider covers 17 m between fixes and a polled beep could land up to a second
 * late. When the beep point falls before the next expected update, it is scheduled for the
 * computed instant (distance / speed) instead.
 *
 * Generated in memory (sine, short fades so it doesn't click) - no sound files.
 */
class TurnBeeper(context: Context) {

    companion object {
        private const val TAG = "TurnBeeper"
        const val LEAD_S = 2.0
        const val MIN_M = 20.0
        /** Schedule ahead only this far; beyond it the next update will reschedule anyway. */
        private const val PREDICT_WINDOW_S = 1.6
        private const val RATE = 22050
        /** Same maneuver key seen again this much FARTHER away = a new instance of a
         * look-alike maneuver (or a reroute brought it back), so it may beep again. */
        private const val REARM_JUMP_M = 60.0
    }

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .build()
    private val handler = Handler(Looper.getMainLooper())

    private val turnTone = tone(listOf(880.0 to 180))
    private val arrivalTone = tone(listOf(1046.0 to 110, 0.0 to 90, 1046.0 to 110))

    private val fired = HashSet<String>()
    private val lastDist = HashMap<String, Double>()
    private var scheduledKey: String? = null
    private var scheduled: Runnable? = null

    /**
     * Feed on every position update. [key] identifies the upcoming maneuver (null = none
     * that deserves a beep, e.g. "keep straight", or off route); [distM] metres to it;
     * [speedMps] null when unknown (then it beeps on reaching [MIN_M]).
     */
    fun update(key: String?, distM: Double, speedMps: Double?, arrival: Boolean, enabled: Boolean) {
        if (scheduledKey != null && scheduledKey != key) cancel()
        if (!enabled || key == null || distM.isNaN()) return

        val prev = lastDist[key]
        if (prev != null && distM > prev + REARM_JUMP_M) fired.remove(key)
        if (lastDist.size > 50) lastDist.clear()
        lastDist[key] = distM
        if (key in fired) return

        val v = speedMps ?: 0.0
        val beepAt = max(MIN_M, v * LEAD_S)
        if (distM <= beepAt) {
            fire(key, arrival)
            return
        }
        if (v > 1.0) {
            val inS = (distM - beepAt) / v
            if (inS <= PREDICT_WINDOW_S) {
                schedule(key, arrival, inS) // replaces any earlier estimate for this key
                return
            }
        }
        if (scheduledKey == key) cancel() // slowed down: the earlier estimate no longer holds
    }

    /** New route / navigation ended. */
    fun reset() {
        cancel()
        fired.clear()
        lastDist.clear()
    }

    fun release() {
        reset()
        audio.abandonAudioFocusRequest(focus)
    }

    private fun schedule(key: String, arrival: Boolean, inS: Double) {
        cancel()
        val r = Runnable { if (scheduledKey == key) fire(key, arrival) }
        scheduled = r
        scheduledKey = key
        handler.postDelayed(r, (inS * 1000).toLong().coerceAtLeast(0))
    }

    private fun cancel() {
        scheduled?.let { handler.removeCallbacks(it) }
        scheduled = null
        scheduledKey = null
    }

    private fun fire(key: String, arrival: Boolean) {
        cancel()
        if (!fired.add(key)) return
        Log.i(TAG, if (arrival) "beep: arrival" else "beep: turn")
        play(if (arrival) arrivalTone else turnTone)
    }

    private fun play(pcm: ShortArray) {
        runCatching {
            audio.requestAudioFocus(focus)
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            val ms = pcm.size * 1000L / RATE
            handler.postDelayed({
                runCatching { track.stop(); track.release() }
                audio.abandonAudioFocusRequest(focus)
            }, ms + 200)
        }.onFailure { Log.w(TAG, "beep failed", it) }
    }

    /** Segments of (Hz, ms); 0 Hz = silence. 8 ms fade in/out per tone: no clicks. */
    private fun tone(segments: List<Pair<Double, Int>>): ShortArray {
        val out = ArrayList<Short>()
        val fade = RATE * 8 / 1000
        for ((hz, ms) in segments) {
            val n = RATE * ms / 1000
            for (i in 0 until n) {
                if (hz == 0.0) { out.add(0); continue }
                val env = min(1.0, min(i, n - 1 - i).toDouble() / fade)
                out.add((sin(2 * PI * hz * i / RATE) * env * 0.7 * Short.MAX_VALUE).toInt().toShort())
            }
        }
        return out.toShortArray()
    }
}

/** On/off for the turn beep, remembered. On by default: the user asked for it. */
object TurnBeepPrefs {
    private const val PREFS = "chronos_prefs"
    private const val KEY = "turn_beep"
    fun isEnabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
    fun setEnabled(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
}
