package org.chronoscompanion.app.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/** Resolves a raw incoming phone number to a saved contact's display name, for showing on
 * the watch's call screen instead of a bare number. */
object CallerLookup {

    fun nameFor(context: Context, phoneNumber: String?): String {
        if (phoneNumber.isNullOrBlank()) return "Desconocido"
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return phoneNumber
        }
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
                } else {
                    phoneNumber
                }
            } ?: phoneNumber
        } catch (e: SecurityException) {
            phoneNumber
        }
    }
}
