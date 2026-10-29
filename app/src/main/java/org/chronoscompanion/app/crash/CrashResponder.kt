package org.chronoscompanion.app.crash

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.chronoscompanion.app.MainActivity
import org.chronoscompanion.app.R
import org.chronoscompanion.app.ble.ChronosProtocol
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The phone's half of crash detection (0xCC, firmware hal/esp32/navi_crash.cpp).
 *
 * - The watch starts its countdown: a loud, repeating alarm notification with
 *   "Estoy bien" (which cancels the watch's countdown too).
 * - The countdown ran out: get a location fix, text every emergency contact a Google Maps
 *   link, tell the watch how many got it, and optionally call the first one.
 * - If the link drops during a countdown, the watch can't ask: the phone sends the SOS
 *   itself once the countdown would have ended (unless "Estoy bien" was pressed here).
 *
 * Main thread. [send] queues a packet to the watch; [cancelIntent] is what the alarm's
 * "Estoy bien" action starts.
 */
class CrashResponder(
    private val context: Context,
    private val send: (ByteArray) -> Unit,
    private val cancelIntent: () -> PendingIntent
) {
    companion object {
        private const val TAG = "CrashResponder"
        private const val CHANNEL_ALARM = "crash_alarm"
        private const val CHANNEL_INFO = "crash_info"
        private const val NOTIF_ID = 40
        /** Firmware CRASH_COUNTDOWN_S, plus a margin before the phone acts on its own. */
        private const val FALLBACK_AFTER_MS = (30 + 15) * 1000L
        /** A second SOS this soon (watch asking again after a reconnect) is not re-sent. */
        private const val SOS_REPEAT_MS = 10 * 60 * 1000L
        private const val FIX_TIMEOUT_MS = 20000L
        private const val FRESH_FIX_MS = 60000L
        private const val SMS_RESULT_TIMEOUT_MS = 30000L
        private const val ACTION_SMS_SENT = "org.chronoscompanion.app.SOS_SMS_SENT"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var countdownActive = false
    private var countdownTest = false
    private var cancelledHere = false
    private var lastSosAt = 0L
    private var lastSosSent = -1
    private var sosBusy = false
    private var smsSeq = 0

    private val fallback = Runnable {
        if (countdownActive && !countdownTest && !cancelledHere) {
            Log.w(TAG, "Link lost during the countdown: sending the SOS from the phone")
            countdownActive = false
            sendSos(test = false)
        }
    }

    fun onWatchEvent(event: Int) {
        Log.i(TAG, "Watch crash event $event")
        when (event) {
            ChronosProtocol.CRASH_COUNTDOWN, ChronosProtocol.CRASH_TEST_COUNTDOWN -> {
                countdownActive = true
                countdownTest = event == ChronosProtocol.CRASH_TEST_COUNTDOWN
                cancelledHere = false
                handler.removeCallbacks(fallback)
                showAlarm(countdownTest)
            }
            ChronosProtocol.CRASH_CANCELLED -> {
                countdownActive = false
                handler.removeCallbacks(fallback)
                notifications().cancel(NOTIF_ID)
            }
            ChronosProtocol.CRASH_SOS, ChronosProtocol.CRASH_TEST_SOS -> {
                countdownActive = false
                handler.removeCallbacks(fallback)
                sendSos(test = event == ChronosProtocol.CRASH_TEST_SOS)
            }
        }
    }

    /** "Estoy bien" on the phone's alarm. */
    fun cancelFromPhone() {
        Log.i(TAG, "Cancelled on the phone")
        cancelledHere = true
        countdownActive = false
        handler.removeCallbacks(fallback)
        notifications().cancel(NOTIF_ID)
        send(ChronosProtocol.buildCrashCancel())
    }

    fun onDisconnected() {
        if (countdownActive && !countdownTest) {
            handler.removeCallbacks(fallback)
            handler.postDelayed(fallback, FALLBACK_AFTER_MS)
        }
    }

    // ---- SOS ----

    private fun sendSos(test: Boolean) {
        if (!test && SystemClock.elapsedRealtime() - lastSosAt < SOS_REPEAT_MS && lastSosAt != 0L) {
            // The watch asked again (it reconnected before hearing the answer): just answer.
            if (lastSosSent >= 0) send(ChronosProtocol.buildCrashResult(lastSosSent))
            return
        }
        if (sosBusy) return
        val contacts = CrashPrefs.contacts(context)
        if (contacts.isEmpty() || !CrashPrefs.canSendSms(context)) {
            Log.e(TAG, "No contacts or no SMS permission: nobody can be told")
            send(ChronosProtocol.buildCrashResult(0))
            showInfo(context.getString(R.string.crash_sos_failed))
            return
        }
        sosBusy = true
        if (!test) lastSosAt = SystemClock.elapsedRealtime()
        locate { fix ->
            val text = message(test, fix)
            Log.i(TAG, "SOS to ${contacts.size} contact(s): $text")
            textAll(contacts, text) { sent ->
                sosBusy = false
                if (!test) lastSosSent = sent
                send(ChronosProtocol.buildCrashResult(sent))
                showInfo(
                    if (sent > 0) context.resources.getQuantityString(R.plurals.crash_sos_sent, sent, sent)
                    else context.getString(R.string.crash_sos_failed)
                )
                if (!test && CrashPrefs.callFirst(context)) callFirst(contacts[0])
            }
        }
    }

    private fun message(test: Boolean, fix: Location?): String {
        // Plain ASCII on purpose: it keeps the SMS in the GSM alphabet (160 characters per
        // part instead of 70), so it is fewer parts for the network to deliver.
        val name = CrashPrefs.riderName(context)
        val where = if (fix != null) {
            val details = listOfNotNull(
                if (fix.hasAccuracy()) "precision ${fix.accuracy.toInt()} m" else null,
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(fix.time))
            ).joinToString(", ")
            "Ubicacion: https://maps.google.com/?q=%.6f,%.6f (%s).".format(
                Locale.US, fix.latitude, fix.longitude, details
            )
        } else {
            "Ubicacion no disponible (sin GPS)."
        }
        return if (test) {
            "PRUEBA de Navi Moto, no es una emergencia. Si hubiera una caida te llegaria esto. $where"
        } else {
            val who = if (name.isNotEmpty()) " de $name" else ""
            "NAVI MOTO: posible accidente en moto$who. Se detecto una caida y nadie cancelo la alarma. $where Llama o pide ayuda."
        }
    }

    @SuppressLint("MissingPermission")
    private fun locate(done: (Location?) -> Unit) {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine) {
            done(null)
            return
        }
        val lm = context.getSystemService(LocationManager::class.java)
        val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < FRESH_FIX_MS) {
            done(last)
            return
        }
        var finished = false
        val cancel = CancellationSignal()
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = finish(location)
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            fun finish(l: Location?) {
                if (finished) return
                finished = true
                cancel.cancel()
                runCatching { lm.removeUpdates(this) }
                done(l ?: last)
            }
        }
        handler.postDelayed({ listener.finish(null) }, FIX_TIMEOUT_MS)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.getCurrentLocation(LocationManager.GPS_PROVIDER, cancel, context.mainExecutor) { listener.finish(it) }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper())
            }
        } catch (e: Exception) {
            Log.w(TAG, "No GPS fix: ${e.message}")
            listener.finish(null)
        }
    }

    /** Texts everyone; [done] gets how many contacts got every part of it. */
    private fun textAll(contacts: List<CrashPrefs.Contact>, text: String, done: (Int) -> Unit) {
        val sms: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        val parts = sms.divideMessage(text)
        val action = "$ACTION_SMS_SENT.${++smsSeq}"
        val pending = IntArray(contacts.size) { parts.size } // parts not reported yet
        val failed = BooleanArray(contacts.size)
        var finished = false
        lateinit var receiver: BroadcastReceiver
        val finish = {
            if (!finished) {
                finished = true
                runCatching { context.unregisterReceiver(receiver) }
                done(contacts.indices.count { pending[it] == 0 && !failed[it] })
            }
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val i = intent.getIntExtra("contact", -1)
                if (i !in contacts.indices) return
                if (resultCode != Activity.RESULT_OK) failed[i] = true
                pending[i]--
                if (pending.all { it <= 0 }) finish()
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        handler.postDelayed({ finish() }, SMS_RESULT_TIMEOUT_MS)
        contacts.forEachIndexed { i, contact ->
            val sent = ArrayList<PendingIntent>()
            for (k in parts.indices) {
                val intent = Intent(action).setPackage(context.packageName).putExtra("contact", i)
                sent.add(
                    PendingIntent.getBroadcast(
                        context, smsSeq * 100 + i * 10 + k, intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                    )
                )
            }
            try {
                sms.sendMultipartTextMessage(contact.number, null, parts, sent, null)
            } catch (e: Exception) {
                Log.e(TAG, "SMS to ${contact.number} refused", e)
                failed[i] = true
                pending[i] = 0
            }
        }
        if (pending.all { it <= 0 }) finish()
    }

    @SuppressLint("MissingPermission")
    private fun callFirst(contact: CrashPrefs.Contact) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        try {
            val telecom = context.getSystemService(TelecomManager::class.java)
            telecom.placeCall(Uri.fromParts("tel", contact.number, null), Bundle())
        } catch (e: Exception) {
            Log.e(TAG, "Call refused", e)
        }
    }

    // ---- Notifications ----

    private fun notifications() = context.getSystemService(NotificationManager::class.java)

    private fun channels() {
        val nm = notifications()
        if (nm.getNotificationChannel(CHANNEL_ALARM) == null) {
            val alarm = NotificationChannel(
                CHANNEL_ALARM, context.getString(R.string.crash_channel_alarm), NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
                setBypassDnd(true)
            }
            nm.createNotificationChannel(alarm)
        }
        if (nm.getNotificationChannel(CHANNEL_INFO) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_INFO, context.getString(R.string.crash_channel_info), NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 41, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    private fun showAlarm(test: Boolean) {
        channels()
        val n = NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(if (test) R.string.crash_alarm_test_title else R.string.crash_alarm_title))
            .setContentText(context.getString(R.string.crash_alarm_text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openApp())
            .setOngoing(true)
            .addAction(0, context.getString(R.string.crash_im_ok), cancelIntent())
            .build()
        // Insistent: the sound repeats until "Estoy bien" or the SOS replaces it - the phone
        // may be in a pocket or a tank bag. The test only rings once.
        if (!test) n.flags = n.flags or android.app.Notification.FLAG_INSISTENT
        notifications().notify(NOTIF_ID, n)
    }

    private fun showInfo(text: String) {
        channels()
        val n = NotificationCompat.Builder(context, CHANNEL_INFO)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.crash_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        notifications().notify(NOTIF_ID, n)
    }
}
