package org.chronoscompanion.app.notification

import android.app.Notification
import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.Color
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.chronoscompanion.app.ble.ChronosBleService
import org.chronoscompanion.app.ble.ChronosProtocol

/**
 * Listens for ALL notifications (Android's API doesn't let a listener subscribe to a
 * single package) and handles two cases differently:
 *   - Google Maps: treated as turn-by-turn navigation data (see handleMapsNotification).
 *   - Everything else in [PACKAGE_TO_ICON]: forwarded as a plain watch notification
 *     (WhatsApp, SMS, Telegram, etc. - see handleGeneralNotification).
 * Anything not in that map is ignored, so e.g. silent/system notifications don't spam
 * the watch.
 */
class ChronosNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "ChronosNotifListener"
        private const val MAPS_PACKAGE = "com.google.android.apps.maps"

        // Package name -> ChronosProtocol.AppIcon code. Extend this list as needed;
        // anything not listed here is silently ignored.
        private val PACKAGE_TO_ICON = mapOf(
            "com.whatsapp" to ChronosProtocol.AppIcon.WHATSAPP,
            "com.whatsapp.w4b" to ChronosProtocol.AppIcon.WHATSAPP_BUSINESS,
            "com.google.android.apps.messaging" to ChronosProtocol.AppIcon.SMS,
            "com.android.mms" to ChronosProtocol.AppIcon.SMS,
            "com.google.android.gm" to ChronosProtocol.AppIcon.GMAIL,
            "com.android.email" to ChronosProtocol.AppIcon.MAIL,
            "org.telegram.messenger" to ChronosProtocol.AppIcon.TELEGRAM,
            "com.facebook.orca" to ChronosProtocol.AppIcon.MESSENGER,
            "com.facebook.katana" to ChronosProtocol.AppIcon.FACEBOOK,
            "com.instagram.android" to ChronosProtocol.AppIcon.INSTAGRAM,
            "com.twitter.android" to ChronosProtocol.AppIcon.TWITTER,
            "com.viber.voip" to ChronosProtocol.AppIcon.VIBER,
            "com.skype.raider" to ChronosProtocol.AppIcon.SKYPE,
            "com.kakao.talk" to ChronosProtocol.AppIcon.KAKAOTALK,
        )

        /**
         * The live listener, so [ChronosBleService] can reach [getActiveNotifications] for
         * [stopMapsNavigation]. Only a bound listener can read active notifications, hence
         * set/cleared on the connect/disconnect callbacks rather than in onCreate/onDestroy.
         */
        @Volatile
        var instance: ChronosNotificationListener? = null
            private set
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    /**
     * Ends the running Google Maps navigation by firing the "Salir de la navegación" action
     * on Maps' own notification - the same thing a tap on that button in the shade does, and
     * how Wear OS / Android Auto end it too. There is no public "stop navigation" intent in
     * Maps, so this is the only sanctioned way in.
     *
     * Returns false when there is nothing to fire, so the caller can tell the user instead of
     * failing silently.
     */
    fun stopMapsNavigation(): Boolean {
        val sbn = try {
            activeNotifications?.firstOrNull { it.packageName == MAPS_PACKAGE }
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot read active notifications", e)
            null
        } ?: run {
            Log.w(TAG, "stopMapsNavigation: no Maps notification is active")
            return false
        }

        val actions = sbn.notification.actions
        if (actions.isNullOrEmpty()) {
            Log.w(TAG, "stopMapsNavigation: Maps notification has no actions")
            return false
        }

        // Verified on a real device (dumpsys notification): the navigation notification
        // carries exactly one action, "Salir de la navegación". Prefer matching it by title
        // in case Maps ever adds others (e.g. mute); fall back to the sole action so a
        // different locale still works.
        val action = actions.firstOrNull { a ->
            val t = a.title?.toString()?.lowercase().orEmpty()
            t.contains("navegaci") || t.contains("navigation") || t.contains("salir") || t.contains("exit")
        } ?: actions.singleOrNull() ?: run {
            Log.w(TAG, "stopMapsNavigation: ${actions.size} actions, none recognised as exit: " +
                actions.joinToString { "'${it.title}'" })
            return false
        }

        return try {
            action.actionIntent.send()
            Log.i(TAG, "stopMapsNavigation: fired '${action.title}'")
            true
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "stopMapsNavigation: action intent was cancelled", e)
            false
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return // never forward our own foreground-service notification
        if (sbn.packageName == MAPS_PACKAGE) {
            handleMapsNotification(sbn)
            return
        }
        if (!NotificationAppsPrefs.isEnabled(this, sbn.packageName)) return
        // Apps the user enabled that aren't in PACKAGE_TO_ICON still get forwarded, just
        // with a generic icon - the firmware already falls back to the same generic icon
        // for any code it doesn't recognize (see getNotificationIconIndex's default case
        // in ui.c), so this doesn't change what the watch can already handle.
        val iconCode = PACKAGE_TO_ICON[sbn.packageName] ?: ChronosProtocol.AppIcon.SMS
        handleGeneralNotification(sbn, iconCode)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != MAPS_PACKAGE) return
        Log.i(TAG, "Maps notification removed")
        ChronosBleService.instance?.sendNavigationStop()
    }

    // -------------------------------------------------------------------
    // General app notifications (WhatsApp, SMS, etc.)
    // -------------------------------------------------------------------

    private fun handleGeneralNotification(sbn: StatusBarNotification, iconCode: Int) {
        val notification = sbn.notification

        // Skip ongoing (foreground-service-style) and group-summary notifications - these
        // aren't "a new message arrived" events and would otherwise spam the watch every
        // time e.g. a call-in-progress notification updates its timer.
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        if (title.isEmpty() && text.isEmpty()) return

        Log.i(TAG, "General notification [${sbn.packageName}]: title='$title' text='$text'")

        val service = ChronosBleService.instance
        if (service == null) {
            Log.w(TAG, "BLE service not running, dropping notification")
            return
        }

        // "Title: body" so the firmware's splitTitle() (splits on the first ':' before
        // index 30) shows the sender/title separately from the message body.
        val message = if (title.isNotEmpty()) "$title: $text" else text
        service.sendNotification(iconCode, message)
    }

    // -------------------------------------------------------------------
    // Google Maps navigation
    // -------------------------------------------------------------------

    private fun handleMapsNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification
        val extras = notification.extras

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()

        Log.i(TAG, "Maps notification: title='$title' text='$text' subText='$subText'")

        if (text.isEmpty()) {
            // Without a street/direction line this isn't real turn-by-turn data - it's a
            // transient state like "Iniciando navegación…" (which also has no subText).
            // Also sidesteps sending accented words into the watch's `title` field, whose
            // font (size 30) was intentionally kept ASCII-only to save flash - Maps' real
            // per-turn `title` values are just numbers ("0 m"), never accented text.
            return
        }

        val service = ChronosBleService.instance
        if (service == null) {
            Log.w(TAG, "BLE service not running, dropping notification")
            return
        }

        val iconBitmap = extractIconBitmap(notification)
        var packedIcon: ByteArray? = null
        var hasIcon = false
        var crc = 0L

        if (iconBitmap != null) {
            packedIcon = bitmapTo1bpp(iconBitmap)
            crc = ChronosProtocol.crc32Of(packedIcon)
            hasIcon = true
        }

        // title = next-turn distance (e.g. "0 m"), text = street/direction. subText is
        // one combined string "<duration> · <distance> · Llegada: <eta>" (confirmed via
        // logcat against a real device) - split it into the 3 fields the watch expects
        // separately, since it re-joins them itself as "eta\nduration distance".
        val subParts = subText.split("·").map { it.trim() }
        val duration = subParts.getOrElse(0) { "" }
        val distance = subParts.getOrElse(1) { "" }
        val eta = subParts.getOrElse(2) { "" }

        // ORDER MATTERS - data before icon, and the watch draws the wrong arrow if reversed.
        //
        // The firmware redraws the turn arrow when the icon's LAST chunk arrives (0xEE, pos 2
        // -> CF_NAV_ICON), but gates that redraw on nav.iconCRC, which the library only sets
        // from the navigation DATA packet (0xEF). Sending the icon first therefore hits the
        // redraw while iconCRC still holds the PREVIOUS arrow's value: the CRC compares equal
        // to what is already displayed, the redraw is skipped, and the new bytes sit in the
        // buffer undrawn.
        //
        // This was always wrong, but used to self-correct within a second because the icon was
        // retransmitted on every Maps update - the next round found iconCRC updated. Now that
        // an unchanged icon is no longer resent, there is no next round, and the watch would
        // keep showing the previous turn's arrow until the turn after it.
        service.sendNavigationData(
            title = title,
            duration = duration,
            distance = distance,
            eta = eta,
            directions = text,
            speed = "",
            hasIcon = hasIcon,
            iconCrc = crc
        )

        if (packedIcon != null) {
            service.sendNavigationIcon(packedIcon, crc)
        }
    }

    private fun extractIconBitmap(notification: Notification): Bitmap? {
        return try {
            val icon = notification.getLargeIcon() ?: notification.smallIcon ?: return null
            val drawable = icon.loadDrawable(this) ?: return null
            val width = drawable.intrinsicWidth.coerceAtLeast(1)
            val height = drawable.intrinsicHeight.coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract notification icon", e)
            null
        }
    }

    /**
     * Resizes [src] to 48x48 and thresholds it to black/white, matching what the watch
     * canvas expects (see ChronosProtocol.packIconBits / the firmware's setNavIconPx loop).
     */
    private fun bitmapTo1bpp(src: Bitmap): ByteArray {
        val resized = Bitmap.createScaledBitmap(src, 48, 48, true)
        val lit = BooleanArray(48 * 48)
        for (y in 0 until 48) {
            for (x in 0 until 48) {
                val pixel = resized.getPixel(x, y)
                val alpha = Color.alpha(pixel)
                val luminance = (Color.red(pixel) * 0.299 + Color.green(pixel) * 0.587 + Color.blue(pixel) * 0.114)
                lit[y * 48 + x] = alpha > 64 && luminance > 128
            }
        }
        return ChronosProtocol.packIconBits(lit)
    }
}
