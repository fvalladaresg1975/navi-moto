package org.chronoscompanion.app.crash

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Crash detection settings: on/off, the emergency contacts, and what the SOS says. */
object CrashPrefs {

    data class Contact(val name: String, val number: String)

    const val MAX_CONTACTS = 3

    private const val PREFS = "crash_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_CONTACTS = "contacts"
    private const val KEY_NAME = "rider_name"
    private const val KEY_CALL = "call_first"
    private const val FIELD_SEP = '\u001F'

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(c: Context) = prefs(c).getBoolean(KEY_ENABLED, false)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_ENABLED, on).apply()

    fun riderName(c: Context): String = prefs(c).getString(KEY_NAME, "") ?: ""
    fun setRiderName(c: Context, name: String) = prefs(c).edit().putString(KEY_NAME, name.trim()).apply()

    fun callFirst(c: Context) = prefs(c).getBoolean(KEY_CALL, false)
    fun setCallFirst(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_CALL, on).apply()

    fun contacts(c: Context): List<Contact> =
        (prefs(c).getString(KEY_CONTACTS, "") ?: "").lines().mapNotNull { line ->
            val p = line.split(FIELD_SEP)
            if (p.size == 2 && p[1].isNotBlank()) Contact(p[0], p[1]) else null
        }

    fun setContacts(c: Context, list: List<Contact>) =
        prefs(c).edit().putString(
            KEY_CONTACTS,
            list.take(MAX_CONTACTS).joinToString("\n") { "${it.name.replace(FIELD_SEP, ' ')}$FIELD_SEP${it.number}" }
        ).apply()

    fun canSendSms(c: Context) =
        ContextCompat.checkSelfPermission(c, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** What the watch is told: watching only makes sense if someone can be texted. */
    fun isArmed(c: Context) = isEnabled(c) && contacts(c).isNotEmpty() && canSendSms(c)
}
