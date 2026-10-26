package org.chronoscompanion.app

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import org.chronoscompanion.app.ble.ChronosBleService
import org.chronoscompanion.app.route.StreetTiles
import org.chronoscompanion.app.telephony.CallAlertsPrefs
import org.chronoscompanion.app.widget.NaviWidget

class MainActivity : AppCompatActivity() {

    companion object {
        /** Sent by the home-screen widget's "Conectar". */
        const val ACTION_WIDGET_CONNECT = "org.chronoscompanion.app.WIDGET_CONNECT"
    }

    private lateinit var turnBeepSwitch: SwitchMaterial
    private lateinit var streetMapSwitch: SwitchMaterial

    private lateinit var statusText: TextView
    private lateinit var deviceNameText: TextView
    private lateinit var statusDot: View
    private lateinit var callAlertsSwitch: SwitchMaterial
    private lateinit var routeDemoButton: MaterialButton

    /** Permissions the incoming-call feature needs: read the ringing state and number,
     * resolve it against contacts, and answer/decline from the watch. */
    private val callPermissions = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.CALL_PHONE
    )

    private val callPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Enable only if everything was granted - a partial grant means the feature would
        // half-work (e.g. showing "Desconocido" for every caller, or an inert Reject
        // button), which is more confusing than leaving it off.
        val allGranted = results.values.all { it }
        CallAlertsPrefs.setEnabled(this, allGranted)
        callAlertsSwitch.isChecked = allGranted
        if (!allGranted) {
            Toast.makeText(this, R.string.call_alerts_permissions_missing, Toast.LENGTH_LONG).show()
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val statusName = intent.getStringExtra(ChronosBleService.EXTRA_STATUS) ?: return
            applyStatus(ChronosBleService.Status.valueOf(statusName))
            applyDeviceName(intent.getStringExtra(ChronosBleService.EXTRA_DEVICE_NAME))
        }
    }

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startBleService()
        } else {
            statusText.text = getString(R.string.permissions_missing)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        deviceNameText = findViewById(R.id.deviceNameText)
        statusDot = findViewById(R.id.statusDot)

        findViewById<MaterialButton>(R.id.notifAccessButton).setOnClickListener {
            openNotificationAccessSettings()
        }
        findViewById<MaterialButton>(R.id.connectButton).setOnClickListener {
            requestPermissionsAndStart()
        }
        findViewById<MaterialButton>(R.id.changeDeviceButton).setOnClickListener {
            changeDevice()
        }
        findViewById<MaterialButton>(R.id.forgetDeviceButton).setOnClickListener {
            confirmForgetDevice()
        }
        findViewById<MaterialButton>(R.id.notificationAppsButton).setOnClickListener {
            startActivity(Intent(this, NotificationAppsActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.watchContactsButton).setOnClickListener {
            startActivity(Intent(this, WatchContactsActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.photoFaceButton).setOnClickListener {
            startActivity(Intent(this, org.chronoscompanion.app.photo.PhotoFaceActivity::class.java))
        }
        routeDemoButton = findViewById(R.id.routeDemoButton)
        routeDemoButton.setOnClickListener {
            val service = ChronosBleService.instance ?: return@setOnClickListener
            when {
                service.isRouteActive -> service.stopRoute()
                service.isRouteDemoRunning -> service.stopRouteDemo()
                else -> service.startRouteDemo()
            }
            refreshRouteButton()
        }

        turnBeepSwitch = findViewById<SwitchMaterial>(R.id.turnBeepSwitch).apply {
            isChecked = org.chronoscompanion.app.route.TurnBeepPrefs.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                org.chronoscompanion.app.route.TurnBeepPrefs.setEnabled(this@MainActivity, on)
                NaviWidget.update(this@MainActivity)
            }
        }

        streetMapSwitch = findViewById<SwitchMaterial>(R.id.streetMapSwitch).apply {
            isChecked = org.chronoscompanion.app.route.StreetMapPrefs.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                org.chronoscompanion.app.route.StreetMapPrefs.setEnabled(this@MainActivity, on)
                ChronosBleService.instance?.onStreetMapToggled(on)
                NaviWidget.update(this@MainActivity)
            }
        }
        streetCacheText = findViewById(R.id.streetCacheText)
        findViewById<MaterialButton>(R.id.streetClearButton).setOnClickListener { askClearStreets() }

        callAlertsSwitch = findViewById(R.id.callAlertsSwitch)
        callAlertsSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) {
                CallAlertsPrefs.setEnabled(this, false)
                return@setOnCheckedChangeListener
            }
            val missing = callPermissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) {
                CallAlertsPrefs.setEnabled(this, true)
            } else {
                // Don't persist "on" yet - the launcher's callback decides based on what
                // the user actually grants, and resets the switch if they decline.
                callPermissionLauncher.launch(missing.toTypedArray())
            }
        }

        val filter = IntentFilter(ChronosBleService.ACTION_STATUS_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
        handleWidgetIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWidgetIntent(intent)
    }

    /** The widget's "Conectar": connect exactly as the button here does (permissions
     * included), from the now-visible app. */
    private fun handleWidgetIntent(intent: Intent?) {
        if (intent?.action != ACTION_WIDGET_CONNECT) return
        intent.action = null // a rotation / recreation must not connect again
        requestPermissionsAndStart()
    }

    /** One button for both route things: stops whichever is running (a real route sent
     * from "Ruta al navegador", or the demo), otherwise starts the demo. Refreshed on resume
     * because a route is usually started from the share screen, not from here. */
    private fun refreshRouteButton() {
        val service = ChronosBleService.instance
        routeDemoButton.setText(
            when {
                service?.isRouteActive == true -> R.string.button_route_stop
                service?.isRouteDemoRunning == true -> R.string.button_route_demo_stop
                else -> R.string.button_route_demo
            }
        )
    }

    // --- Street cache (filled by each route's download, see StreetPrefetch) ----------
    private lateinit var streetCacheText: TextView

    private fun showStreetCache() {
        val (tiles, bytes) = StreetTiles.stats(this)
        streetCacheText.text = getString(R.string.street_cache_info,
            android.text.format.Formatter.formatShortFileSize(this, bytes), tiles)
    }

    /** The cache trims itself at 50 MB (least recently used first); this is the manual way. */
    private fun askClearStreets() {
        val (_, bytes) = StreetTiles.stats(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.street_clear_title)
            .setMessage(getString(R.string.street_clear_msg,
                android.text.format.Formatter.formatShortFileSize(this, bytes)))
            .setPositiveButton(R.string.street_clear_yes) { _, _ ->
                Thread {
                    StreetTiles.clear(applicationContext)
                    runOnUiThread { if (!isDestroyed) showStreetCache() }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshRouteButton()
        showStreetCache()
        // The widget can flip these while the app is in the background.
        turnBeepSwitch.isChecked = org.chronoscompanion.app.route.TurnBeepPrefs.isEnabled(this)
        streetMapSwitch.isChecked = org.chronoscompanion.app.route.StreetMapPrefs.isEnabled(this)
        NaviWidget.update(this)
        // The service keeps running (and stays connected) across the activity being
        // closed and reopened - it only reacts to NEW state-change broadcasts, so
        // reopening the app showed the hardcoded "sin iniciar" text forever even while
        // already connected. Read the service's current state directly on resume.
        //
        // ChronosBleService.status is a companion-object var that defaults to
        // DISCONNECTED and is never reset - if the OS killed the whole app process
        // (this phone's battery manager does that aggressively) and the service never
        // got a chance to restart, that stale default reads as "Buscando el navegador..."
        // even though nothing is actually running, which is misleading (and confusing
        // together with "Cambiar de navegador" correctly refusing to work). Show a distinct
        // "stopped" state instead whenever there's no live service instance.
        if (ChronosBleService.instance == null) {
            statusText.text = getString(R.string.status_prefix) + "Detenido"
            (statusDot.background as GradientDrawable).setColor(ContextCompat.getColor(this, R.color.status_disconnected))
        } else {
            applyStatus(ChronosBleService.status)
        }
        val savedName = getSharedPreferences(ChronosBleService.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(ChronosBleService.PREF_DEVICE_NAME, null)
        applyDeviceName(savedName)

        // Re-check on every resume: the user may have revoked a call permission from
        // Android's own Settings while the app was backgrounded, which would leave the
        // switch showing "on" for a feature that can no longer work. Re-assigning
        // isChecked re-runs the listener above, which is idempotent in both directions.
        val callPermsOk = callPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        callAlertsSwitch.isChecked = CallAlertsPrefs.isEnabled(this) && callPermsOk
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        ChronosBleService.discoveryCallback = null
        super.onDestroy()
    }

    private fun applyStatus(status: ChronosBleService.Status) {
        val (label, colorRes) = when (status) {
            ChronosBleService.Status.DISCONNECTED -> getString(R.string.status_prefix) + "Buscando el navegador…" to R.color.status_disconnected
            ChronosBleService.Status.SCANNING -> getString(R.string.status_prefix) + "Buscando el navegador…" to R.color.status_connecting
            ChronosBleService.Status.CONNECTING -> getString(R.string.status_prefix) + "Conectando…" to R.color.status_connecting
            ChronosBleService.Status.CONNECTED -> getString(R.string.status_prefix) + "Conectado" to R.color.status_connected
        }
        statusText.text = label
        (statusDot.background as GradientDrawable).setColor(ContextCompat.getColor(this, colorRes))
    }

    private fun applyDeviceName(name: String?) {
        if (name.isNullOrEmpty()) {
            deviceNameText.visibility = View.GONE
        } else {
            deviceNameText.text = name
            deviceNameText.visibility = View.VISIBLE
        }
    }

    private fun openNotificationAccessSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun requestPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Needed to fetch weather for the phone's location (WeatherFetcher), not BLE-related.
        needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startBleService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startBleService() {
        val intent = Intent(this, ChronosBleService::class.java)
        ContextCompat.startForegroundService(this, intent)
        statusText.text = getString(R.string.status_starting)
    }

    // -------------------------------------------------------------------
    // Device picker / switching
    // -------------------------------------------------------------------

    private fun changeDevice() {
        val service = ChronosBleService.instance
        if (service == null) {
            Toast.makeText(this, "Primero conectate una vez con el botón 2", Toast.LENGTH_SHORT).show()
            return
        }
        val progressBar = android.widget.ProgressBar(this).apply {
            setPadding(0, 32, 0, 0)
        }
        val progress = AlertDialog.Builder(this)
            .setTitle(R.string.discovery_searching)
            .setView(progressBar)
            .setCancelable(false)
            .show()
        service.startDeviceDiscovery { devices ->
            runOnUiThread {
                progress.dismiss()
                showDevicePicker(devices)
            }
        }
    }

    private fun showDevicePicker(devices: List<ChronosBleService.DiscoveredDevice>) {
        if (devices.isEmpty()) {
            Toast.makeText(this, R.string.discovery_none_found, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = devices.map { "${it.name}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.discovery_title)
            .setItems(labels) { _, which ->
                ChronosBleService.instance?.connectToDevice(devices[which])
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun confirmForgetDevice() {
        AlertDialog.Builder(this)
            .setTitle(R.string.button_forget_device)
            .setMessage("Se cortará la conexión actual y vas a tener que elegir un navegador de nuevo.")
            .setPositiveButton(R.string.button_forget_device) { _, _ ->
                ChronosBleService.instance?.forgetDevice()
                applyDeviceName(null)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
}
