package org.chronoscompanion.app.telephony

import android.content.Context

/** Whether incoming calls should be forwarded to the watch (caller ID + Accept/Reject).
 * Off by default - this feature needs phone/contacts/call-answering permissions the user
 * hasn't granted yet, so it should only turn on when they explicitly opt in from the
 * switch in MainActivity. */
object CallAlertsPrefs {
    private const val PREFS_NAME = "call_alerts_prefs"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
