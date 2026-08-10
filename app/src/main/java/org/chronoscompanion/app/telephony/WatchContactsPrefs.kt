package org.chronoscompanion.app.telephony

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The contacts the user picked to show on the watch, in the order they'll be sent.
 * Order matters: the watch identifies a contact purely by its index in this list when it
 * asks the phone to dial one (see ChronosProtocol.decodeDialContactIndex), so the list
 * the phone holds must stay in the same order it synced.
 *
 * Stored as JSON in its own SharedPreferences file - a plain string set wouldn't preserve
 * order, and each entry is a name/number pair rather than a single value.
 */
object WatchContactsPrefs {
    private const val PREFS_NAME = "watch_contacts_prefs"
    private const val KEY_CONTACTS = "contacts"

    data class Entry(val name: String, val number: String)

    fun get(context: Context): List<Entry> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CONTACTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(o.getString("name"), o.getString("number"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun set(context: Context, entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().put("name", e.name).put("number", e.number))
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CONTACTS, arr.toString()).apply()
    }
}
