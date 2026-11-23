package org.chronoscompanion.app.ble

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Brings the connection back after the phone restarts or the app is updated, both of
 * which stop the service: before this, the watch got nothing (weather, time,
 * notifications) until someone opened the app and tapped «Conectar».
 *
 * Only when the user wants it running: «Conectar» sets the flag, «Detener» in the
 * notification clears it.
 */
object ServiceAutoStart {
    private const val TAG = "ServiceAutoStart"
    private const val PREFS = "service_autostart"
    private const val KEY_WANTED = "wanted"

    fun setWanted(ctx: Context, wanted: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_WANTED, wanted).apply()
    }

    fun isWanted(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WANTED, false)

    private fun hasBluetoothPermissions(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN).all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** Starts the service if the user left it running. Safe to call from a boot broadcast. */
    fun startIfWanted(ctx: Context, why: String) {
        if (!isWanted(ctx)) return
        if (!hasBluetoothPermissions(ctx)) {
            Log.w(TAG, "$why: Bluetooth permissions missing, not starting")
            return
        }
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, ChronosBleService::class.java))
            Log.i(TAG, "$why: connection service started")
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException if Android refuses it.
            Log.w(TAG, "$why: could not start the service", e)
        }
    }

    /** BOOT_COMPLETED and MY_PACKAGE_REPLACED, both allowed to start a foreground service. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BOOT_COMPLETED -> startIfWanted(ctx, "boot")
                Intent.ACTION_MY_PACKAGE_REPLACED -> startIfWanted(ctx, "app updated")
            }
        }
    }
}
