package org.chronoscompanion.app.ble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import org.chronoscompanion.app.R
import java.util.ArrayDeque
import java.util.UUID
import org.chronoscompanion.app.notification.ChronosNotificationListener
import android.net.Uri
import org.chronoscompanion.app.telephony.CallAlertsPrefs
import org.chronoscompanion.app.telephony.CallerLookup
import org.chronoscompanion.app.telephony.WatchContactsPrefs
import org.chronoscompanion.app.weather.WeatherFetcher
import org.chronoscompanion.app.route.RouteDemo
import org.chronoscompanion.app.route.Route
import org.chronoscompanion.app.route.RouteFormat
import org.chronoscompanion.app.route.RouteIcons
import org.chronoscompanion.app.route.RouteTracker
import org.chronoscompanion.app.route.RouteVoice
import org.chronoscompanion.app.route.TurnBeeper
import org.chronoscompanion.app.route.TurnBeepPrefs
import org.chronoscompanion.app.route.StreetTiles
import org.chronoscompanion.app.route.Polyline
import org.chronoscompanion.app.route.StreetMap
import org.chronoscompanion.app.route.StreetMapPrefs
import kotlin.math.abs
import kotlin.math.roundToInt
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle

/**
 * Foreground service owning the single BLE connection to the watch (ChronosESP32
 * firmware). Scans for a device advertising [ChronosProtocol.SERVICE_UUID], connects,
 * and exposes send* methods that build + chunk + queue packets over the RX
 * characteristic. Writes are sent one at a time, waiting for each
 * onCharacteristicWrite callback before sending the next chunk, since the firmware's
 * chunk-reassembly relies on receiving them in order.
 */
class ChronosBleService : Service() {

    enum class Status { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

    /** A BLE device advertising the Chronos UART service, found during [startDeviceDiscovery]. */
    data class DiscoveredDevice(val name: String, val address: String)

    companion object {
        private const val TAG = "ChronosBleService"
        private const val CHANNEL_ID = "chronos_ble"
        private const val NOTIF_ID = 1
        private const val TIME_SYNC_INTERVAL_MS = 10 * 60 * 1000L // re-sync every 10 min to correct drift
        private const val WEATHER_REFRESH_INTERVAL_MS = 30 * 60 * 1000L // Open-Meteo updates hourly at most
        private const val DISCOVERY_DURATION_MS = 8000L
        private const val MUSIC_STATE_POLL_MS = 5000L

        // Packet kinds, used only to let navigation updates supersede each other in the
        // write queue. Everything else is KIND_OTHER and is never dropped.
        private const val KIND_OTHER = 0
        private const val KIND_NAV_DATA = 1
        private const val KIND_NAV_ICON = 2
        private const val KIND_ROUTE = 3
        private const val KIND_SPEED = 4
        /** Street-layer packets: background filler, sent behind everything else. */
        private const val KIND_STREETS = 5
        private const val KIND_BRIGHTNESS = 6

        // Street layer (route mode). See StreetMap / ChronosProtocol.buildStreets.
        /** The next corridor stretch (StreetMap.WINDOW_M) is loaded this far before the
         * end of the covered one - from the phone's tile cache, and whatever is missing
         * from Overpass (then saved to the cache): ~2.5 min of margin at 120 km/h against
         * ~40 s for Overpass to answer. The user asked for the cache to keep filling ahead
         * of the rider this way. */
        private const val STREETS_REFETCH_BEFORE_M = 5000.0
        private const val STREETS_RETRY_MS = 60000L
        private const val STREETS_PENDING_RETRY_MS = 5000L
        /** Streets sent once they come this close (the view reaches ~500 m ahead). */
        private const val STREETS_SEND_RADIUS_M = 900.0
        /** What the watch can hold (firmware: 8000 points / 2000 pieces), with a margin. */
        private const val STREETS_WATCH_MAX_POINTS = 7000
        private const val STREETS_WATCH_MAX_PIECES = 1800
        /** Street packets waiting in the queue at once: enough to fill the view in a few
         * seconds, few enough that the queue never piles up behind a slow link. */
        private const val STREETS_MAX_PENDING = 3
        /** Route view zoom: fully zoomed in at or below the first speed, fully out at or
         * above the second, in between proportionally. */
        private const val ZOOM_CLOSE_KMH = 30.0
        private const val ZOOM_FAR_KMH = 90.0
        /** Google Maps mode: re-check the posted limit this often while moving... */
        private const val MAPS_LIMIT_EVERY_MS = 12000L
        /** ...or this soon after a turn, which is when the road (and limit) changes. */
        private const val MAPS_LIMIT_AFTER_TURN_MS = 3000L

        /** Per-chunk hex dump of every BLE write. Off: in route mode that is ~16 log lines a
         * second, which made logd prune this app's other lines (the route computation's log
         * was lost to it on the phase-2a test) and is wasted work on a multi-hour ride.
         * Flip on only to debug the wire format. */
        private const val LOG_WIRE = false
        /** Arrived: stay on "Llegada a destino" this long, then end route mode by itself. */
        private const val ARRIVAL_HOLD_MS = 15000L
        private const val ARRIVAL_RADIUS_M = 30.0
        /** Off the route (or riding it the wrong way) this long before rerouting: rides
         * through one bad fix, catches a wrong turn within a block. */
        private const val OFF_ROUTE_MS = 5000L
        /** A fix worse than this can't tell "off the route" from "GPS is bad here". */
        private const val REROUTE_MAX_ACCURACY_M = 50f
        /** At most one reroute this often - fair use of the public router, and no
         * thrashing while the rider is still deciding which street to take. */
        private const val REROUTE_MIN_GAP_MS = 10000L
        /** No fix (tunnel): keep advancing for at most this long. The Zapata tunnel on Ruta
         * 68 takes well under a minute; beyond 90 s an estimate is worse than a frozen view. */
        private const val DEAD_RECKON_MAX_MS = 90000L
        /** Advance at this share of the last speed: undershooting is safe because s only
         * moves forward and the returning fix pulls it ahead; overshooting can't be undone. */
        private const val DEAD_RECKON_FACTOR = 0.9

        const val ACTION_STATUS_CHANGED = "org.chronoscompanion.app.STATUS_CHANGED"
        const val EXTRA_STATUS = "status"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val ACTION_STOP = "org.chronoscompanion.app.ACTION_STOP"

        const val PREFS_NAME = "chronos_prefs"
        const val PREF_DEVICE_NAME = "device_name"
        private const val PREF_DEVICE_ADDRESS = "device_address"

        @Volatile
        var instance: ChronosBleService? = null
            private set

        @Volatile
        var status: Status = Status.DISCONNECTED
            private set

        /** Set by MainActivity while a device-picker dialog is open; cleared on dismiss/destroy. */
        @Volatile
        var discoveryCallback: ((List<DiscoveredDevice>) -> Unit)? = null
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null

    /**
     * One 20-byte BLE chunk waiting to go out, tagged so [coalesce] can drop superseded
     * navigation updates. [group] identifies the logical packet the chunk came from:
     * chunks of a packet that has already begun transmitting must never be dropped, or the
     * firmware would reassemble a truncated packet.
     */
    private class Chunk(val bytes: ByteArray, val kind: Int, val group: Long)

    private val writeQueue = ArrayDeque<Chunk>()
    private var writeInFlight = false
    private var writeGeneration = 0
    private var nextGroupId = 0L

    /**
     * The one packet that may be half-transmitted right now, and so must never be coalesced
     * away. A single value suffices: chunks leave the queue strictly in order, so every
     * earlier packet is already fully sent and every later one is still untouched.
     */
    private var startedGroup = -1L
    private val mainHandler = Handler(Looper.getMainLooper())
    // Wall-clock time (SystemClock.elapsedRealtime) of the last weather send that actually
    // ran - lets startWeatherTimer() skip redundant sends on rapid reconnects (see there).
    private var lastWeatherSendAtMs = 0L
    // Last forecast actually fetched, so a reconnecting/rebooted watch can be repopulated
    // from memory instead of waiting on (or paying for) another Open-Meteo request.
    private var cachedWeather: WeatherFetcher.Result? = null
    private var cachedWeatherAtMs = 0L
    // Guards beginSession() so the descriptor callback and its fallback timer can't both
    // kick off a second round of time sync / contacts / weather on one connection.
    private var sessionStarted = false
    // Whether weather has been pushed since the CURRENT connection came up. lastWeatherSendAtMs
    // alone wasn't enough: it's stamped when the packets are queued, not when the watch
    // actually receives them, so while BLE was silently broken every attempt still "counted"
    // and then blocked the next one for 30 minutes - the watch's weather never refreshed.
    private var weatherSentThisConnection = false

    // --- Navigation latency (see sendNavigationData / sendNavigationIcon) -----------------
    // Google Maps re-posts its notification about once a second while driving, and each one
    // used to push the full 48x48 turn icon (18 chunks) plus the data (6 chunks). At the
    // default connection interval that is ~1s of airtime per update - the queue ran at 100%
    // capacity, so any hiccup accumulated into the multi-second lag seen on a real ride.
    //
    // CRC of the turn icon currently on the watch. The arrow is identical for the whole
    // approach to a turn, so resending it is pure waste - the firmware already discards it
    // by CRC on arrival, but only after the bytes have crossed the air. Reset whenever the
    // watch may have lost it (disconnect, navigation stop) so it is pushed again.
    private var lastSentIconCrc = 0L
    private var navPriorityActive = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        registerCallStateReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Stop requested from notification action")
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground()
        // The service is a singleton within the process: once running, every extra
        // startForegroundService() call (each tap of "Conectar", or the system
        // restarting a START_STICKY service) re-enters here. Only kick off a scan if
        // we're not already scanning/connecting/connected - otherwise each call used
        // to register a brand new BLE scanner (see startScan()'s old bug) without ever
        // stopping the previous one, leaking scanner registrations until the phone's
        // BLE stack ran out of scan slots and stopped working entirely.
        if (status == Status.DISCONNECTED) {
            startScan()
        } else {
            // Already connected/scanning - nothing to restart, but the UI that just
            // launched us is showing "iniciando…" and would otherwise sit there forever
            // waiting for a state-change broadcast that will never come.
            broadcastStatus()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        turnBeeper.release()
        stopMapsSpeed(sendHide = false)
        stopRouteDemo()
        stopRoute()
        stopTimeSyncTimer()
        stopWeatherTimer()
        stopMusicStateTimer()
        stopScanIfRunning()
        unregisterCallStateReceiver()
        bluetoothGatt?.close()
        bluetoothGatt = null
        instance = null
        status = Status.DISCONNECTED
        broadcastStatus()
        // setStatus()'s updateNotification() re-posts the ongoing "Buscando el navegador…"
        // notification, which is right for a live reconnect-retry but wrong here: the
        // service is being torn down (e.g. the user tapped "Detener"), so re-posting it
        // orphans an ongoing, un-dismissable notification that never goes away once the
        // service is actually gone. stopForeground(STOP_FOREGROUND_REMOVE) is what
        // actually clears it.
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun broadcastStatus() {
        val i = Intent(ACTION_STATUS_CHANGED)
        i.putExtra(EXTRA_STATUS, status.name)
        i.putExtra(EXTRA_DEVICE_NAME, prefs().getString(PREF_DEVICE_NAME, null))
        sendBroadcast(i)
        org.chronoscompanion.app.widget.NaviWidget.update(this)
    }

    private fun setStatus(s: Status) {
        status = s
        broadcastStatus()
        updateNotification(s)
    }

    // ---------------------------------------------------------------------
    // Remembered device (which specific watch to reconnect to)
    // ---------------------------------------------------------------------

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun savedAddress(): String? = prefs().getString(PREF_DEVICE_ADDRESS, null)

    private fun saveDevice(address: String, name: String) {
        prefs().edit().putString(PREF_DEVICE_ADDRESS, address).putString(PREF_DEVICE_NAME, name).apply()
    }

    /** Disconnects and clears the remembered device, so the next scan won't auto-pick one -
     * the user has to choose again via [startDeviceDiscovery]. */
    fun forgetDevice() {
        Log.i(TAG, "Forgetting saved device")
        prefs().edit().remove(PREF_DEVICE_ADDRESS).remove(PREF_DEVICE_NAME).apply()
        stopScanIfRunning()
        stopTimeSyncTimer()
        stopWeatherTimer()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        rxCharacteristic = null
        writeQueue.clear()
        writeInFlight = false
        resetNavState()
        setStatus(Status.DISCONNECTED)
    }

    // ---------------------------------------------------------------------
    // Scanning / connecting
    // ---------------------------------------------------------------------

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private var scanCallback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val adapter = bluetoothAdapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || scanner == null) {
            // adapter.bluetoothLeScanner returns null when Bluetooth is off or the
            // stack is mid-restart. This used to just bail out here forever - status
            // stayed on DISCONNECTED/"Buscando el navegador..." with nothing left to ever
            // retry, so a transient BT hiccup meant the app never reconnected again
            // until manually force-stopped. Retry instead of giving up.
            Log.w(TAG, "startScan: BluetoothLeScanner unavailable, retrying in 3s")
            setStatus(Status.DISCONNECTED)
            mainHandler.postDelayed({ startScan() }, 3000)
            return
        }
        // Always stop whatever scan (if any) is still registered under the current
        // scanCallback before registering a new one - startScan() used to overwrite
        // scanCallback unconditionally, orphaning the previous scanner registration.
        stopScanIfRunning()
        setStatus(Status.SCANNING)

        // If the user has already picked/connected to a specific watch before, only
        // reconnect to THAT one (by MAC) - otherwise, with several Chronos boards
        // nearby, a plain reconnect scan could latch onto a different board than the
        // one the user chose via startDeviceDiscovery().
        val address = savedAddress()
        val filterBuilder = ScanFilter.Builder().setServiceUuid(ParcelUuid(ChronosProtocol.SERVICE_UUID))
        if (address != null) {
            filterBuilder.setDeviceAddress(address)
        }
        val filter = filterBuilder.build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                Log.i(TAG, "Found device: ${result.device.address} (${result.device.name})")
                stopScanIfRunning()
                if (address == null) {
                    // First-time connect with nothing remembered yet - adopt whatever
                    // Chronos-compatible device answered first, same as before this
                    // feature existed. From now on reconnects target this specific one.
                    saveDevice(result.device.address, result.device.name ?: "Navegador Navi Moto")
                }
                connect(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                setStatus(Status.DISCONNECTED)
            }
        }
        scanCallback = callback
        scanner.startScan(listOf(filter), settings, callback)
    }

    @SuppressLint("MissingPermission")
    private fun stopScanIfRunning() {
        val adapter = bluetoothAdapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return
        scanCallback?.let { scanner.stopScan(it) }
        scanCallback = null
    }

    /**
     * Scans for [DISCOVERY_DURATION_MS] collecting every nearby device advertising the
     * Chronos UART service (not just the remembered one), for the "Cambiar de navegador"
     * picker. Interrupts whatever scan was running and resumes normal reconnect
     * scanning afterwards if still disconnected.
     */
    @SuppressLint("MissingPermission")
    fun startDeviceDiscovery(onResult: (List<DiscoveredDevice>) -> Unit) {
        val adapter = bluetoothAdapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || scanner == null) {
            onResult(emptyList())
            return
        }
        stopScanIfRunning()
        val found = LinkedHashMap<String, DiscoveredDevice>()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(ChronosProtocol.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: "Navegador Navi Moto"
                found[result.device.address] = DiscoveredDevice(name, result.device.address)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Discovery scan failed: $errorCode")
            }
        }
        scanCallback = callback
        scanner.startScan(listOf(filter), settings, callback)
        mainHandler.postDelayed({
            stopScanIfRunning()
            onResult(found.values.toList())
            if (status != Status.CONNECTED) {
                startScan()
            }
        }, DISCOVERY_DURATION_MS)
    }

    /** Connects to a device picked from [startDeviceDiscovery]'s list, replacing whatever was remembered before. */
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: DiscoveredDevice) {
        Log.i(TAG, "Switching to device ${device.name} (${device.address})")
        stopScanIfRunning()
        stopTimeSyncTimer()
        stopWeatherTimer()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        rxCharacteristic = null
        writeQueue.clear()
        writeInFlight = false
        resetNavState()
        saveDevice(device.address, device.name)
        val adapter = bluetoothAdapter ?: return
        connect(adapter.getRemoteDevice(device.address))
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        setStatus(Status.CONNECTING)
        // Defensive: the disconnect handler already closes+nulls bluetoothGatt before
        // any rescan can reach here, but don't leak a native GATT client if some other
        // path ever calls connect() while one is still held.
        bluetoothGatt?.close()
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "GATT connected, discovering services")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "GATT disconnected (status=$status)")
                setStatus(Status.DISCONNECTED)
                // close() releases this BluetoothGatt's underlying native client resource.
                // Android caps how many of these can be open at once (~a handful) - the
                // watch's Extreme Power Save mode disconnects and reconnects BLE every
                // time the screen sleeps/wakes on battery, and without this the app was
                // leaking one native GATT client per cycle (connect() below just
                // overwrote the field with a new connectGatt() call, never releasing the
                // old one). After enough cycles the stack ran out of slots, and only a
                // full app restart (which tears down every leaked client at once) freed
                // them - matching what was actually observed on-device.
                gatt.close()
                if (bluetoothGatt === gatt) {
                    bluetoothGatt = null
                }
                rxCharacteristic = null
                writeQueue.clear()
                writeInFlight = false
                resetNavState()
                // The watch may reboot before the next connection: assume it holds no
                // streets, and start the next street upload with a clear in case it does.
                sentStreetIds.clear()
                sentStreetPts = 0
                streetsNeedClear = true
                watchBrightness = null
                weatherSentThisConnection = false
                sessionStarted = false
                stopTimeSyncTimer()
                stopWeatherTimer()
                stopMusicStateTimer()
                mainHandler.postDelayed({ startScan() }, 3000)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(ChronosProtocol.SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "Chronos UART service not found on this device")
                gatt.disconnect()
                return
            }
            rxCharacteristic = service.getCharacteristic(ChronosProtocol.CHARACTERISTIC_UUID_RX)
            val txCharacteristic = service.getCharacteristic(ChronosProtocol.CHARACTERISTIC_UUID_TX)

            var waitingForDescriptor = false
            if (txCharacteristic != null) {
                gatt.setCharacteristicNotification(txCharacteristic, true)
                val cccd = txCharacteristic.getDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                )
                if (cccd != null) {
                    waitingForDescriptor = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(cccd)
                    }
                }
            }

            setStatus(Status.CONNECTED)
            Log.i(TAG, "Chronos UART service ready")

            // Android runs ONE GATT operation at a time. The descriptor write above is a
            // GATT operation, so firing a characteristic write before it completes gets
            // the write silently dropped - no callback, no error. That is exactly what
            // happened on every single connection: the first packet (time sync) stalled
            // for the full 5s write timeout and only went through on the retry. Wait for
            // onDescriptorWrite instead, with a timer as a safety net in case some stack
            // never delivers it.
            if (waitingForDescriptor) {
                mainHandler.postDelayed({ beginSession() }, 2000)
            } else {
                beginSession()
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Descriptor write failed (status=$status), sending anyway")
            }
            beginSession()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeInFlight = false
            writeGeneration++ // invalidates any pending timeout scheduled for this write
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Write failed: status=$status")
            } else {
                if (LOG_WIRE) Log.i(TAG, "Write OK (${writeQueue.size} chunk(s) still queued)")
            }
            drainQueue()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            Log.d(TAG, "TX notify: ${value.joinToString(" ") { "%02x".format(it) }}")
            ChronosProtocol.decodeWatchCommand(value)?.let { handleWatchCommand(it) }
            ChronosProtocol.decodeDialContactIndex(value)?.let { dialContactAt(it) }
            ChronosProtocol.decodeBrightness(value)?.let { level ->
                mainHandler.post {
                    watchBrightness = level
                    org.chronoscompanion.app.widget.NaviWidget.update(this@ChronosBleService)
                }
            }
        }
    }

    /** Everything we push once a connection is genuinely ready to accept writes. */
    private fun beginSession() {
        if (sessionStarted) return
        sessionStarted = true
        sendTimeSync()
        startTimeSyncTimer()
        startWeatherTimer()
        sendContacts()
        startMusicStateTimer()
        // Ask the watch for its brightness, for the widget's bar.
        enqueuePacket(ChronosProtocol.buildBrightness(null))
    }

    // ---------------------------------------------------------------------
    // Watch screen brightness (home-screen widget)
    // ---------------------------------------------------------------------

    /** Last level the watch reported (1-255); null until it does, or after a disconnect. */
    @Volatile var watchBrightness: Int? = null
        private set

    /** Sets the watch's brightness. Shown at once; the watch confirms with its own report.
     * Main thread. */
    fun setWatchBrightness(level: Int) {
        if (status != Status.CONNECTED) return
        val l = level.coerceIn(1, 255)
        watchBrightness = l
        coalesce(KIND_BRIGHTNESS) // tapping + several times: only the last level matters
        enqueuePacket(ChronosProtocol.buildBrightness(l), KIND_BRIGHTNESS)
        org.chronoscompanion.app.widget.NaviWidget.update(this)
    }

    // ---------------------------------------------------------------------
    // Music/volume control commands from the watch
    // ---------------------------------------------------------------------

    private fun handleWatchCommand(command: ChronosProtocol.WatchCommand) {
        Log.i(TAG, "Watch command: $command")
        when (command) {
            ChronosProtocol.WatchCommand.MUSIC_PLAY -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            ChronosProtocol.WatchCommand.MUSIC_PAUSE -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            ChronosProtocol.WatchCommand.MUSIC_TOGGLE -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            ChronosProtocol.WatchCommand.MUSIC_NEXT -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            ChronosProtocol.WatchCommand.MUSIC_PREVIOUS -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            ChronosProtocol.WatchCommand.VOLUME_UP -> adjustVolume(AudioManager.ADJUST_RAISE)
            ChronosProtocol.WatchCommand.VOLUME_DOWN -> adjustVolume(AudioManager.ADJUST_LOWER)
            ChronosProtocol.WatchCommand.VOLUME_MUTE -> adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE)
            ChronosProtocol.WatchCommand.CALL_ACCEPT -> answerRingingCall()
            ChronosProtocol.WatchCommand.CALL_REJECT -> declineRingingCall()
            ChronosProtocol.WatchCommand.NAVIGATION_STOP -> stopNavigationFromWatch()
        }
    }

    /**
     * Dispatches a media button press to the currently playing app's MediaSession, the
     * same way a physical headset button would - works across apps (Spotify, YouTube
     * Music, etc.) without needing to track play/pause state ourselves. Requires
     * notification listener access, which [ChronosNotificationListener] already holds.
     */
    private fun sendMediaKey(keyCode: Int) {
        val sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val controllers: List<MediaController> = try {
            sessionManager.getActiveSessions(ComponentName(this, ChronosNotificationListener::class.java))
        } catch (e: SecurityException) {
            Log.e(TAG, "No notification listener access, cannot control media: ${e.message}")
            return
        }
        val controller = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull()
        if (controller == null) {
            Log.w(TAG, "No active media session to control")
            return
        }
        val eventTime = SystemClock.uptimeMillis()
        controller.dispatchMediaButtonEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
        controller.dispatchMediaButtonEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))
    }

    // ---------------------------------------------------------------------
    // Playback state -> watch, so its play button can show pause while playing
    // ---------------------------------------------------------------------

    /** null until known, so the first reading is always pushed. */
    private var lastSentMusicPlaying: Boolean? = null

    private fun isMusicPlaying(): Boolean {
        val sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val controllers = try {
            sessionManager.getActiveSessions(ComponentName(this, ChronosNotificationListener::class.java))
        } catch (e: SecurityException) {
            return false
        }
        return controllers.any { it.playbackState?.state == PlaybackState.STATE_PLAYING }
    }

    /**
     * Polls rather than subscribing to MediaController callbacks: the state has to be
     * tracked across whichever app happens to own the session, and juggling
     * per-controller listeners as sessions come and go is a lot of lifecycle for
     * something this small. The query is local and cheap, and a packet only goes out when
     * the value actually changes, so an idle phone sends nothing at all.
     */
    private val musicStateRunnable = object : Runnable {
        override fun run() {
            val playing = isMusicPlaying()
            if (playing != lastSentMusicPlaying) {
                lastSentMusicPlaying = playing
                Log.i(TAG, "Music ${if (playing) "playing" else "paused"}, updating watch")
                enqueuePacket(ChronosProtocol.buildMusicState(playing))
            }
            mainHandler.postDelayed(this, MUSIC_STATE_POLL_MS)
        }
    }

    private fun startMusicStateTimer() {
        mainHandler.removeCallbacks(musicStateRunnable)
        lastSentMusicPlaying = null // force a push on this connection
        mainHandler.postDelayed(musicStateRunnable, 2000)
    }

    private fun stopMusicStateTimer() {
        mainHandler.removeCallbacks(musicStateRunnable)
    }

    private fun adjustVolume(direction: Int) {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    // ---------------------------------------------------------------------
    // Incoming calls: mirror the caller onto the watch, and let its Accept/Reject
    // buttons act on the call here. Entirely opt-in - CallAlertsPrefs defaults to off
    // and the user turns it on from MainActivity's switch.
    // ---------------------------------------------------------------------

    private var lastRingingNumber: String? = null
    // Set when the watch asked us to dial, so the IDLE broadcast knows to tell the watch
    // to close its call screen even though no incoming call was ever announced.
    private var watchInitiatedCall = false

    /**
     * Watches the phone's own call state. Uses the ACTION_PHONE_STATE_CHANGED broadcast
     * rather than TelephonyCallback.CallStateListener because the latter only reports the
     * state on API 31+, never the number - and the caller's identity is the whole point
     * here. EXTRA_INCOMING_NUMBER still arrives on this broadcast when both
     * READ_PHONE_STATE and READ_CALL_LOG are granted (see AndroidManifest's comment).
     */
    private val callStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

            when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    // Only the incoming-call popup is gated by the switch; a call the user
                    // placed from the watch still has to be able to close its own screen.
                    if (!CallAlertsPrefs.isEnabled(this@ChronosBleService)) return
                    @Suppress("DEPRECATION")
                    val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                    // Android delivers this broadcast more than once per call (and
                    // sometimes without the number on the first one) - only push to the
                    // watch on a genuinely new ringing number, not on every repeat.
                    if (number != null && number == lastRingingNumber) return
                    lastRingingNumber = number
                    val name = CallerLookup.nameFor(this@ChronosBleService, number)
                    Log.i(TAG, "Incoming call, notifying watch")
                    enqueuePacket(ChronosProtocol.buildRingerStart(name))
                }
                // An incoming call being answered means the watch should drop its
                // incoming-call screen. A watch-placed call reaching OFFHOOK is the
                // opposite: it just connected, and the screen must stay so it can be
                // hung up from there.
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    if (lastRingingNumber == null) return
                    lastRingingNumber = null
                    enqueuePacket(ChronosProtocol.buildRingerStop())
                }
                // Call over - close whichever screen the watch is showing.
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    if (lastRingingNumber == null && !watchInitiatedCall) return
                    lastRingingNumber = null
                    watchInitiatedCall = false
                    enqueuePacket(ChronosProtocol.buildRingerStop())
                }
            }
        }
    }

    private fun registerCallStateReceiver() {
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(callStateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(callStateReceiver, filter)
        }
    }

    private fun unregisterCallStateReceiver() {
        try {
            unregisterReceiver(callStateReceiver)
        } catch (e: IllegalArgumentException) {
            // wasn't registered (service torn down before onCreate finished) - nothing to do
        }
    }

    // ---------------------------------------------------------------------
    // Contacts on the watch
    // ---------------------------------------------------------------------

    /**
     * Pushes the user's picked contacts (WatchContactsPrefs) to the watch's Contactos app.
     * Order is significant: the watch reports back only the index of the row tapped, so
     * this list's order is the contract between both sides. Names first, then numbers -
     * the firmware fires its "contacts received" callback on the LAST number packet.
     */
    fun sendContacts() {
        val contacts = WatchContactsPrefs.get(this)
        enqueuePacket(ChronosProtocol.buildContactsHeader(contacts.size))
        contacts.forEachIndexed { i, c -> enqueuePacket(ChronosProtocol.buildContactName(i, c.name)) }
        contacts.forEachIndexed { i, c -> enqueuePacket(ChronosProtocol.buildContactNumber(i, c.number)) }
        Log.i(TAG, "Sent ${contacts.size} contact(s) to the watch")
    }

    /** Places a call to the contact the watch tapped, identified by its index in the
     * same list [sendContacts] pushed. */
    private fun dialContactAt(index: Int) {
        val contacts = WatchContactsPrefs.get(this)
        val contact = contacts.getOrNull(index)
        if (contact == null) {
            // Can happen if the user changed the selection while the watch still showed
            // the old list - it'll be back in sync on the next connect/save.
            Log.w(TAG, "Watch asked to dial index $index but only ${contacts.size} contact(s) are synced")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Tell the user instead of only logging it: this exact case (permission added
            // to the app after the call switch was already on, so it was never requested)
            // made tapping a contact do nothing at all, with no visible reason why.
            Log.w(TAG, "Cannot dial: CALL_PHONE not granted")
            toast(R.string.missing_call_permission)
            return
        }
        Log.i(TAG, "Dialing contact at index $index on the watch's request")
        watchInitiatedCall = true
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(contact.number)}")).apply {
            // Started from a service, so it needs its own task.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: SecurityException) {
            Log.e(TAG, "Dial refused: ${e.message}")
        }
    }

    private fun hasAnswerPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED

    /** Toasts must run on the main thread; watch commands arrive on a BLE callback one. */
    private fun toast(msgRes: Int) {
        mainHandler.post { Toast.makeText(this, msgRes, Toast.LENGTH_LONG).show() }
    }

    /**
     * The watch's "Salir" button. Nothing is sent back to the watch on success on purpose:
     * Maps removes its notification, onNotificationRemoved fires the normal
     * sendNavigationStop(), and the watch closes its screen through the path it always
     * uses. One source of truth (Maps), no state to keep in sync.
     */
    private fun stopNavigationFromWatch() {
        // The watch's "Salir" ends whichever navigation is driving it: our own route (or
        // the demo) if one is active, otherwise Google Maps.
        if (isRouteActive || isRouteDemoRunning) {
            stopRoute()
            stopRouteDemo()
            return
        }
        val listener = ChronosNotificationListener.instance
        if (listener == null) {
            Log.w(TAG, "NAVIGATION_STOP: notification listener not connected")
            toast(R.string.nav_stop_failed)
            return
        }
        if (!listener.stopMapsNavigation()) {
            toast(R.string.nav_stop_failed)
        }
    }

    private fun answerRingingCall() {
        if (!hasAnswerPermission()) {
            Log.w(TAG, "Cannot answer: ANSWER_PHONE_CALLS not granted")
            toast(R.string.missing_answer_permission)
            return
        }
        val telecom = getSystemService(TELECOM_SERVICE) as TelecomManager
        try {
            telecom.acceptRingingCall()
        } catch (e: SecurityException) {
            Log.e(TAG, "acceptRingingCall refused: ${e.message}")
        }
    }

    private fun declineRingingCall() {
        if (!hasAnswerPermission()) {
            Log.w(TAG, "Cannot decline: ANSWER_PHONE_CALLS not granted")
            toast(R.string.missing_answer_permission)
            return
        }
        // endCall() is API 28+; there's no public equivalent on 26/27, so on those the
        // watch's Reject button simply does nothing rather than failing loudly.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.w(TAG, "Declining calls needs Android 9+")
            return
        }
        val telecom = getSystemService(TELECOM_SERVICE) as TelecomManager
        try {
            @Suppress("DEPRECATION")
            telecom.endCall()
        } catch (e: SecurityException) {
            Log.e(TAG, "endCall refused: ${e.message}")
        }
    }

    // ---------------------------------------------------------------------
    // Sending
    // ---------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun drainQueue(retryCount: Int = 0) {
        if (writeInFlight) return
        if (writeQueue.isEmpty()) return
        val gatt = bluetoothGatt
        val characteristic = rxCharacteristic
        if (gatt == null || characteristic == null) {
            Log.w(TAG, "drainQueue: no GATT/characteristic yet, ${writeQueue.size} packet(s) waiting")
            return
        }
        val next = writeQueue.poll() ?: return
        // From here on this packet is committed: dropping its remaining chunks would leave
        // the firmware reassembling a truncated packet.
        startedGroup = next.group
        writeInFlight = true
        val myGeneration = ++writeGeneration
        if (LOG_WIRE) Log.i(TAG, "Writing ${next.bytes.size} bytes: ${next.bytes.joinToString(" ") { "%02x".format(it) }}")
        // onCharacteristicWrite occasionally never arrives after a stop/reconnect cycle
        // (observed: BLE stays "connected" but the watch's write ack callback is lost),
        // which used to leave writeInFlight stuck true forever - every future packet
        // (weather, navigation, everything) then got silently queued and never sent,
        // with no error logged. Force it back open after a timeout so the queue keeps
        // moving; the generation check no-ops this if the real callback still lands late.
        //
        // This used to just move on to the NEXT queued packet, silently dropping
        // whichever one timed out - harmless for weather/notifications, but the very
        // first packet after reconnect (time sync) hit exactly this timeout in testing,
        // so the watch's clock never got corrected and nothing ever retried it. Put the
        // packet back at the front of the queue instead, up to a few attempts, so a lost
        // callback delays delivery rather than silently dropping it.
        mainHandler.postDelayed({
            if (writeInFlight && writeGeneration == myGeneration) {
                writeInFlight = false
                if (retryCount < 3) {
                    Log.w(TAG, "Write callback timed out, retrying (attempt ${retryCount + 1})")
                    writeQueue.addFirst(next)
                    drainQueue(retryCount + 1)
                } else {
                    Log.e(TAG, "Write callback timed out $retryCount times, giving up on this packet")
                    drainQueue()
                }
            }
        }, 5000)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                next.bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = next.bytes
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    private fun enqueuePacket(logicalPacket: ByteArray, kind: Int = KIND_OTHER) {
        val group = ++nextGroupId
        val chunks = ChronosProtocol.chunk(logicalPacket).map { Chunk(it, kind, group) }
        val waitingStreets: (Chunk) -> Boolean = { it.kind == KIND_STREETS && it.group != startedGroup }
        if (kind != KIND_STREETS && writeQueue.any(waitingStreets)) {
            // Street packets are background filler: everything else goes in front of the
            // ones that haven't started, so they never add latency to navigation. Whole
            // packets only - the watch reassembles a packet from consecutive chunks, so the
            // one already on the air keeps its place.
            val held = writeQueue.filter(waitingStreets)
            writeQueue.removeAll(waitingStreets)
            writeQueue.addAll(chunks)
            writeQueue.addAll(held)
        } else {
            writeQueue.addAll(chunks)
        }
        mainHandler.post { drainQueue() }
    }

    /**
     * Drops queued chunks of the given kind that have not started transmitting yet.
     *
     * Only ever used for navigation: a superseded turn instruction has negative value - the
     * watch showing a stale street name is worse than showing nothing - so transmitting it
     * before the current one is pure added latency. This is what stops delay accumulating
     * over a long ride: the backlog can never exceed one update, no matter how far behind
     * the link falls.
     */
    private fun coalesce(kind: Int) {
        val before = writeQueue.size
        writeQueue.removeAll { it.kind == kind && it.group != startedGroup }
        val dropped = before - writeQueue.size
        if (dropped > 0) Log.i(TAG, "Coalesced $dropped stale chunk(s) of kind $kind")
    }

    fun sendNavigationData(
        title: String,
        duration: String,
        distance: String,
        eta: String,
        directions: String,
        speed: String,
        hasIcon: Boolean,
        iconCrc: Long
    ) {
        val packet = ChronosProtocol.buildNavigationData(
            title = title,
            duration = duration,
            distance = distance,
            eta = eta,
            directions = directions,
            speed = speed,
            hasIcon = hasIcon,
            isNavigation = true,
            iconCrc = iconCrc
        )
        // Navigation is the watch's primary job, so it gets the tightest connection interval
        // the phone will grant - but only while actually navigating, so the rest of the day
        // costs no extra battery on either side.
        requestNavPriority(true)
        // This update replaces any earlier one still waiting, rather than queueing behind it.
        //
        // Except while an icon is queued: the firmware takes the icon's identifying CRC from
        // the DATA packet, so the data packet sitting in front of those icon chunks is what
        // makes the watch actually draw them (see the ordering note in
        // ChronosNotificationListener.handleMapsNotification). Dropping it would leave the
        // icon arriving against a stale CRC and the redraw would be skipped - the very bug
        // the send-order fix addresses. Icons are only queued on an actual turn change now,
        // so this costs at most one un-coalesced update.
        if (writeQueue.none { it.kind == KIND_NAV_ICON }) {
            coalesce(KIND_NAV_DATA)
        }
        enqueuePacket(packet, KIND_NAV_DATA)
    }

    // ---------------------------------------------------------------------
    // Route mode (0xC5). See ChronosProtocol.buildRouteFrame for the format.
    // ---------------------------------------------------------------------

    /** One rider-relative polyline frame. A newer frame always replaces a queued older one -
     * a stale view of the road is worse than none, same reasoning as nav-data coalescing. */
    fun sendRouteFrame(points: List<Pair<Int, Int>>, speedKmh: Int? = null, limitKmh: Int? = null,
                       pose: ChronosProtocol.RiderPose? = null) {
        coalesce(KIND_ROUTE)
        enqueuePacket(ChronosProtocol.buildRouteFrame(points, speedKmh, limitKmh, pose), KIND_ROUTE)
    }

    fun sendRouteClear() {
        coalesce(KIND_ROUTE)
        enqueuePacket(ChronosProtocol.buildRouteClear(), KIND_ROUTE)
    }

    // --- Demo, for filming the system: route mode through a made-up city, then arrow mode
    // (Google Maps style). See RouteDemo. Everything goes through the real paths - nav data,
    // icons, route frames with rider pose, street upload, speed info, beep - so the video
    // shows exactly what a ride does. The remaining distance is a real figure now, so the
    // watch's "Viaje" app records the demo as the last trip.
    private var routeDemo: RouteDemo? = null
    private var routeDemoS = 0.0
    private var demoTick = 0
    private var demoLastManeuver: RouteDemo.Maneuver? = null
    /** Arrow part: index into RouteDemo.arrowSteps (-1 = still in the route part), metres
     * left to that step's maneuver, and ticks held on the arrival screen. */
    private var demoArrowStep = -1
    private var demoArrowLeft = 0.0
    private var demoArrowHold = 0
    private var demoVoice: RouteVoice? = null
    private val demoSaid = HashSet<String>()

    val isRouteDemoRunning: Boolean get() = routeDemo != null

    private val routeDemoRunnable = object : Runnable {
        override fun run() {
            val demo = routeDemo ?: return
            if (status != Status.CONNECTED) {
                // Link dropped mid-demo: stop quietly. Queuing a clear for a watch that is
                // not there would only deliver a stale "stop" after the next reconnect.
                routeDemo = null
                demoVoice?.shutdown()
                demoVoice = null
                return
            }
            if (demoArrowStep < 0) {
                if (routeDemoS < demo.lengthM - 3) {
                    demoRouteTick(demo)
                    mainHandler.postDelayed(this, RouteDemo.TICK_MS)
                    return
                }
                // Route part done: hold the finish flag a moment, then switch to arrows.
                if (demoArrowHold < 12) {
                    demoArrowHold++
                    mainHandler.postDelayed(this, RouteDemo.TICK_MS)
                    return
                }
                startDemoArrows(demo)
            }
            if (!demoArrowTick(demo)) {
                stopRouteDemo()
                return
            }
            mainHandler.postDelayed(this, RouteDemo.ARROW_TICK_MS)
        }
    }

    /** One route-mode frame: same packets as sendRouteState(), from the demo's road. */
    private fun demoRouteTick(demo: RouteDemo) {
        val s = routeDemoS
        val m = demo.nextManeuver(s)
        val toTurn = (m.atM - s).coerceAtLeast(0.0)
        val kmh = demo.speedKmhAt(s)
        routeSpeedKmh = kmh.roundToInt()
        if (demoTick % 4 == 0 || m !== demoLastManeuver) {
            demoLastManeuver = m
            val remaining = (demo.lengthM - s).coerceAtLeast(0.0)
            val remainingS = remaining / 11.0
            val bits = demo.iconBits(m.glyph)
            val crc = ChronosProtocol.crc32Of(bits)
            sendNavigationData(
                title = RouteFormat.distance(toTurn),
                duration = RouteFormat.duration(remainingS),
                distance = RouteFormat.distance(remaining),
                eta = "Llegada: " + RouteFormat.clock(System.currentTimeMillis() + (remainingS * 1000).toLong()),
                directions = m.text,
                speed = "",
                hasIcon = true,
                iconCrc = crc
            )
            sendNavigationIcon(bits, crc)
        }
        updateRouteScale()
        val (px, py) = demo.road.pointAt(s)
        var hdg = Math.toDegrees(demo.road.headingAt(s))
        if (hdg < 0) hdg += 360.0
        // The demo's street map shares the road's frame, so the pose is the road point itself.
        val pose = ChronosProtocol.RiderPose((px * 10).roundToInt(), (py * 10).roundToInt(),
            (hdg * 100).roundToInt() % 36000, (routeScale * 100).roundToInt())
        sendRouteFrame(demo.road.frameAt(s, routeScale), routeSpeedKmh, demo.limitAt(s), pose)
        sendStreetsNear(px, py)
        // Beep timed on the speed the demo really advances at (see RouteDemo.TIME_SCALE).
        turnBeeper.update("d:${m.atM}", toTurn, kmh / 3.6 * RouteDemo.TIME_SCALE,
            m.glyph == RouteIcons.Glyph.ARRIVE, TurnBeepPrefs.isEnabled(this))
        demoSay("r${m.atM}", m.text, m.glyph, toTurn)
        routeDemoS += kmh / 3.6 * RouteDemo.TIME_SCALE * RouteDemo.TICK_MS / 1000.0
        demoTick++
    }

    private fun startDemoArrows(demo: RouteDemo) {
        sendRouteClear() // back to the arrow layout; navigation itself stays on
        routeSpeedKmh = null
        turnBeeper.reset()
        demoArrowStep = 0
        demoArrowLeft = demo.arrowSteps[0].lengthM
        demoArrowHold = 0
    }

    /** One arrow-mode update, as Google Maps would post it. False = demo finished. */
    private fun demoArrowTick(demo: RouteDemo): Boolean {
        val steps = demo.arrowSteps
        var st = steps[demoArrowStep]
        if (demoArrowLeft <= 0) {
            if (st.glyph == RouteIcons.Glyph.ARRIVE) {
                if (++demoArrowHold > 4) return false
                demoArrowLeft = 0.0
            } else {
                demoArrowStep++
                st = steps[demoArrowStep]
                demoArrowLeft = st.lengthM
            }
        }
        val remaining = demoArrowLeft + steps.drop(demoArrowStep + 1).sumOf { it.lengthM }
        val remainingS = remaining / 11.0
        val bits = demo.iconBits(st.glyph)
        val crc = ChronosProtocol.crc32Of(bits)
        sendNavigationData(
            title = RouteFormat.distance(demoArrowLeft),
            duration = RouteFormat.duration(remainingS),
            distance = RouteFormat.distance(remaining),
            eta = "Llegada: " + RouteFormat.clock(System.currentTimeMillis() + (remainingS * 1000).toLong()),
            directions = st.text,
            speed = "",
            hasIcon = true,
            iconCrc = crc
        )
        sendNavigationIcon(bits, crc)
        // A little wobble, like a real speedometer; 0 once arrived.
        val kmh = if (demoArrowLeft <= 0) 0 else st.kmh + listOf(0, 1, -1, 2, 0, -2)[demoTick % 6]
        coalesce(KIND_SPEED)
        enqueuePacket(ChronosProtocol.buildSpeedInfo(kmh, st.limit), KIND_SPEED)
        turnBeeper.update("a$demoArrowStep", demoArrowLeft, st.kmh / 3.6 * RouteDemo.TIME_SCALE,
            st.glyph == RouteIcons.Glyph.ARRIVE, TurnBeepPrefs.isEnabled(this))
        demoSay("a$demoArrowStep", st.text, st.glyph, demoArrowLeft)
        demoArrowLeft -= st.kmh / 3.6 * RouteDemo.TIME_SCALE * RouteDemo.ARROW_TICK_MS / 1000.0
        demoTick++
        return true
    }

    /** Spoken prompts: an early call around 150 m and one at the turn, once each. */
    private fun demoSay(key: String, text: String, glyph: RouteIcons.Glyph, toTurn: Double) {
        val voice = demoVoice ?: return
        val arrive = glyph == RouteIcons.Glyph.ARRIVE
        if (toTurn in 60.0..170.0 && demoSaid.add("$key:far")) {
            val m = ((toTurn / 50).roundToInt() * 50).coerceAtLeast(50)
            voice.say(if (arrive) "En $m metros, llegará a su destino"
                      else "En $m metros, " + text.replaceFirstChar { it.lowercase() })
        } else if (toTurn <= 30.0 && demoSaid.add("$key:now")) {
            voice.say(if (arrive) "Ha llegado a su destino" else text)
        }
    }

    fun startRouteDemo() {
        if (status != Status.CONNECTED) {
            toast(R.string.route_demo_not_connected)
            return
        }
        stopRoute()
        stopRouteDemo()
        val demo = RouteDemo()
        routeDemo = demo
        routeDemoS = 0.0
        demoTick = 0
        demoLastManeuver = null
        demoArrowStep = -1
        demoArrowHold = 0
        demoSaid.clear()
        demoVoice = RouteVoice(this)
        // The made-up city goes up to the watch like real streets do.
        streetMap = StreetMap(0.0, 0.0).also { it.addStreets(demo.streets) }
        streetsRoute = null
        streetsNeedClear = true
        routeScale = Polyline.SCALE_MAX_PX_PER_M
        mainHandler.post(routeDemoRunnable)
        org.chronoscompanion.app.widget.NaviWidget.update(this)
    }

    fun stopRouteDemo() {
        if (routeDemo == null) return
        val inArrows = demoArrowStep >= 0
        routeDemo = null
        turnBeeper.reset()
        mainHandler.removeCallbacks(routeDemoRunnable)
        demoVoice?.shutdown()
        demoVoice = null
        streetMap = null
        coalesce(KIND_STREETS)
        sentStreetIds.clear()
        sentStreetPts = 0
        streetsNeedClear = true
        routeSpeedKmh = null
        if (inArrows) {
            coalesce(KIND_SPEED)
            enqueuePacket(ChronosProtocol.buildSpeedInfo(null, null), KIND_SPEED)
        }
        sendRouteClear()
        sendNavigationStop()
        org.chronoscompanion.app.widget.NaviWidget.update(this)
    }

    // --- Real route: GPS-tracked (phase 2b). Reroute / tunnels / GPS loss: phase 3 -------
    private var activeRoute: Route? = null
    private var tracker: RouteTracker? = null
    private var voice: RouteVoice? = null
    /** Rider's position along the route, metres of arclength. */
    private var routeS = 0.0
    /** Rider's speed from the last GPS fix, km/h; null until a fix reports one. */
    private var routeSpeedKmh: Int? = null
    private var lastRouteSendMs = 0L
    private var arrivedAtMs = 0L
    // Phase 3: reroute + GPS loss
    private var routeDest: org.chronoscompanion.app.route.Destination? = null
    private var routePrefs = org.chronoscompanion.app.route.RoutePrefs(false, false)
    private var offRouteSinceMs = 0L
    private var rerouting = false
    private var lastRerouteAtMs = 0L
    /** Bumped on every reroute request and on stop: a late answer for an older request
     * (or for a route that was stopped) is ignored. */
    private var rerouteGen = 0
    private var rerouteProblemSaid = false
    private var lastFixAtMs = 0L
    private var lastDeadReckonMs = 0L
    private var lastSpeedMps = 0.0
    private val routeWorker = java.util.concurrent.Executors.newSingleThreadExecutor()

    // Street layer: OSM streets around the route, uploaded to the watch in the background.
    // One StreetMap per trip (survives reroutes); see StreetMap for the frame.
    private var streetMap: StreetMap? = null
    /** The route [streetsCoveredUntil] refers to - a reroute starts coverage over. */
    private var streetsRoute: Route? = null
    private var streetsCoveredUntil = 0.0
    private var streetsFetching = false
    /** No new attempt to load streets before this (elapsedRealtime); 0 = any time. */
    private var streetsRetryAtMs = 0L
    /** What the watch holds, as far as this side knows. */
    private val sentStreetIds = HashSet<Long>()
    private var sentStreetPts = 0
    private var streetsNeedClear = true
    private val streetWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    /** Route view zoom, px/m: close in town, far on the open road (see updateRouteScale). */
    private var routeScale = Polyline.SCALE_MAX_PX_PER_M

    /** Speed-dependent zoom, as car navigators do: at town speeds the next junctions fill
     * the screen; at road speeds it backs out to show ~470 m ahead, about 17 s at 100
     * km/h. Eased (at most 0.05 px/m per frame, ~12 s end to end) so a stop at a light or
     * a GPS speed blip doesn't make the view jump. Unknown speed (tunnel estimate) keeps
     * the zoom it has. */
    private fun updateRouteScale() {
        val v = routeSpeedKmh?.toDouble() ?: return
        val t = ((v - ZOOM_CLOSE_KMH) / (ZOOM_FAR_KMH - ZOOM_CLOSE_KMH)).coerceIn(0.0, 1.0)
        val target = Polyline.SCALE_MAX_PX_PER_M + (Polyline.SCALE_PX_PER_M - Polyline.SCALE_MAX_PX_PER_M) * t
        routeScale += (target - routeScale).coerceIn(-0.05, 0.05)
    }

    val isRouteActive: Boolean get() = activeRoute != null

    /** Distance left on the active route, metres; null without one. For the widget. */
    val routeRemainingM: Double? get() = activeRoute?.remainingDistanceM(routeS)
    /** Remaining distance the widget last showed: it is redrawn every 500 m, not per fix. */
    private var widgetRemainingM = -1.0

    /** The street-layer switch in the app. Off: the watch forgets its streets now. */
    fun onStreetMapToggled(on: Boolean) {
        if (on || streetMap == null) return
        coalesce(KIND_STREETS)
        sentStreetIds.clear()
        sentStreetPts = 0
        streetsNeedClear = false
        if (status == Status.CONNECTED) enqueuePacket(ChronosProtocol.buildStreetsClear(), KIND_STREETS)
    }

    /** Where the rider is drawn, in the street map's frame - the same point and heading
     * [Polyline.frameAt] draws the route line from, so streets and route line agree. */
    private fun riderPose(r: Route, s: Double): ChronosProtocol.RiderPose? {
        val map = streetMap ?: return null
        val (rx, ry) = r.road.pointAt(s)
        val (lat, lon) = r.toLatLon(rx, ry)
        val (mx, my) = map.toMap(lat, lon)
        var hdg = Math.toDegrees(r.road.headingAt(s))
        if (hdg < 0) hdg += 360.0
        return ChronosProtocol.RiderPose(
            (mx * 10).roundToInt(), (my * 10).roundToInt(),
            (hdg * 100).roundToInt() % 36000, (routeScale * 100).roundToInt())
    }

    /** Fetches the next stretch of street corridor when the rider gets near the end of
     * what is covered (or the route changed). One Overpass query per ~10 km. */
    private fun maybeFetchStreets(r: Route, s: Double) {
        val map = streetMap ?: return
        if (streetsFetching || !StreetMapPrefs.isEnabled(this)) return
        if (SystemClock.elapsedRealtime() < streetsRetryAtMs) return
        if (streetsRoute === r &&
            (streetsCoveredUntil >= r.road.lengthM || s < streetsCoveredUntil - STREETS_REFETCH_BEFORE_M)) return
        val from = (s - 300.0).coerceAtLeast(0.0)
        val to = (s + StreetMap.WINDOW_M).coerceAtMost(r.road.lengthM)
        val path = StreetMap.corridorPath(r, from, to)
        streetsFetching = true
        streetWorker.execute {
            // Cached tiles land in the map at once; missing ones after Overpass answers.
            val outcome = StreetTiles.ensure(this, path, StreetMap.CORRIDOR_M.toDouble(), map)
            mainHandler.post {
                streetsFetching = false
                if (streetMap !== map) return@post // trip ended meanwhile
                onStreetsOutcome(r, to, outcome)
            }
        }
    }

    /** Main thread. Records how loading the stretch up to [to] of [r] went. */
    private fun onStreetsOutcome(r: Route, to: Double, outcome: StreetTiles.Outcome) {
        when (outcome) {
            StreetTiles.Outcome.OK -> {
                streetsRoute = r
                streetsCoveredUntil = to
                streetsRetryAtMs = 0L
            }
            // The route download is fetching some of these tiles right now: look again
            // shortly - they come out of the cache then.
            StreetTiles.Outcome.PENDING ->
                streetsRetryAtMs = SystemClock.elapsedRealtime() + STREETS_PENDING_RETRY_MS
            StreetTiles.Outcome.FAILED -> {
                Log.w(TAG, "Streets: fetch failed, retrying in ${STREETS_RETRY_MS / 1000} s")
                streetsRetryAtMs = SystemClock.elapsedRealtime() + STREETS_RETRY_MS
            }
        }
    }

    /** Queues the streets near the rider the watch doesn't have yet, nearest first, a few
     * packets at a time. Runs after each route frame; the frame itself always goes first. */
    private fun sendStreetsNear(x: Double, y: Double) {
        val map = streetMap ?: return
        if (status != Status.CONNECTED || !StreetMapPrefs.isEnabled(this)) return
        var free = STREETS_MAX_PENDING - writeQueue.filter { it.kind == KIND_STREETS }.map { it.group }.distinct().size
        if (free <= 0) return
        if (streetsNeedClear) {
            enqueuePacket(ChronosProtocol.buildStreetsClear(), KIND_STREETS)
            sentStreetIds.clear()
            sentStreetPts = 0
            streetsNeedClear = false
        }
        val todo = map.near(x, y, STREETS_SEND_RADIUS_M, sentStreetIds)
        val ax = x.roundToInt()
        val ay = y.roundToInt()
        var i = 0
        while (free > 0 && i < todo.size) {
            val batch = ArrayList<StreetMap.Street>()
            var bytes = 9
            var pts = 0
            while (i < todo.size) {
                val st = todo[i]
                val fits = st.xs.indices.all { abs(st.xs[it] - ax) <= 32767 && abs(st.ys[it] - ay) <= 32767 }
                if (!fits) { i++; continue }
                val b = ChronosProtocol.streetBytes(st.xs.size)
                if (bytes + b > ChronosProtocol.STREETS_MAX_PAYLOAD) break
                batch.add(st)
                bytes += b
                pts += st.xs.size
                i++
            }
            if (batch.isEmpty()) break
            if (sentStreetPts + pts > STREETS_WATCH_MAX_POINTS ||
                sentStreetIds.size + batch.size > STREETS_WATCH_MAX_PIECES) {
                // Watch full: start over next frame with whatever is nearest by then.
                streetsNeedClear = true
                return
            }
            enqueuePacket(ChronosProtocol.buildStreets(ax, ay, batch), KIND_STREETS)
            batch.forEach { sentStreetIds.add(it.id) }
            sentStreetPts += pts
            free--
        }
    }

    /** Frames go out the moment a fix arrives (lowest latency: the GPS is the clock).
     * This heartbeat only covers gaps - no fix for 1.5 s, or a reconnect - because the
     * watch drops a route view that goes 5 s without a frame, so a dead phone never leaves
     * a frozen road on screen. Frames and nav data coalesce, icons are CRC-deduplicated. */
    private val routeRunnable = object : Runnable {
        override fun run() {
            val r = activeRoute ?: return
            val now = SystemClock.elapsedRealtime()
            if (arrivedAtMs != 0L && now - arrivedAtMs > ARRIVAL_HOLD_MS) {
                stopRoute()
                return
            }
            deadReckon(now)
            if (status == Status.CONNECTED && now - lastRouteSendMs > 1500) {
                sendRouteState(r, routeS)
                lastRouteSendMs = now
            }
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private val routeLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val r = activeRoute ?: return
            val t = tracker ?: return
            t.update(location)
            routeS = t.s
            val now = SystemClock.elapsedRealtime()
            lastFixAtMs = now
            lastDeadReckonMs = now
            lastSpeedMps = if (location.hasSpeed()) location.speed.toDouble() else 0.0
            routeSpeedKmh = if (location.hasSpeed()) (location.speed * 3.6f).toInt() else null
            if (t.onRoute && !t.wrongWay) {
                offRouteSinceMs = 0L
                rerouteProblemSaid = false
                voice?.onProgress(r, routeS, lastSpeedMps)
                beepForRoute(r, routeS, if (location.hasSpeed()) lastSpeedMps else null)
            } else {
                // Off route: whatever turn was coming no longer applies.
                turnBeeper.update(null, 0.0, null, false, false)
                if (offRouteSinceMs == 0L) offRouteSinceMs = now
                if (now - offRouteSinceMs >= OFF_ROUTE_MS && location.accuracy <= REROUTE_MAX_ACCURACY_M) {
                    maybeReroute(location)
                }
            }
            if (arrivedAtMs == 0L && r.road.lengthM - routeS <= ARRIVAL_RADIUS_M) {
                arrivedAtMs = SystemClock.elapsedRealtime()
                Log.i(TAG, "Route: arrived")
            }
            if (status == Status.CONNECTED) {
                sendRouteState(r, routeS)
                lastRouteSendMs = SystemClock.elapsedRealtime()
            }
        }
        // Explicit overrides: these only gained defaults in later API levels; minSdk is 26.
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    /** Tunnel / GPS loss: keep the road moving at an estimate of the rider's progress,
     * with the speed readout hidden so the screen admits it is estimating. */
    private fun deadReckon(now: Long) {
        val t = tracker ?: return
        val r = activeRoute ?: return
        val gap = now - lastFixAtMs
        if (lastFixAtMs == 0L || gap < 2000 || gap > DEAD_RECKON_MAX_MS) return
        if (offRouteSinceMs != 0L || lastSpeedMps < 1.0) return // off route or stopped: nothing to extrapolate
        val dt = (now - lastDeadReckonMs) / 1000.0
        lastDeadReckonMs = now
        t.deadReckon(DEAD_RECKON_FACTOR * lastSpeedMps * dt, System.currentTimeMillis())
        routeS = t.s
        routeSpeedKmh = null
        // Keep announcing on the estimate: an exit right after a tunnel still gets its call.
        voice?.onProgress(r, routeS, lastSpeedMps)
        beepForRoute(r, routeS, DEAD_RECKON_FACTOR * lastSpeedMps)
        if (status == Status.CONNECTED) {
            sendRouteState(r, routeS)
            lastRouteSendMs = now
        }
    }

    /** Off the route for OFF_ROUTE_MS: ask for a new route from here to the same
     * destination, starting in the direction the rider is already moving. */
    private fun maybeReroute(fix: Location) {
        val dest = routeDest ?: return
        val now = SystemClock.elapsedRealtime()
        if (rerouting || now - lastRerouteAtMs < REROUTE_MIN_GAP_MS) return
        rerouting = true
        lastRerouteAtMs = now
        val gen = ++rerouteGen
        val prefs = routePrefs
        val heading = if (fix.hasBearing() && fix.hasSpeed() && fix.speed > 3) fix.bearing.toDouble() else null
        Log.i(TAG, "Route: off route ${tracker?.lateralM?.toInt()} m (wrongWay=${tracker?.wrongWay}), rerouting")
        voice?.say("Recalculando ruta")
        routeWorker.execute {
            val result = runCatching {
                org.chronoscompanion.app.route.Router.fetch(
                    fix.latitude, fix.longitude, dest.lat, dest.lon, prefs, heading, withLimits = false)
            }
            mainHandler.post { onRerouteResult(gen, result) }
        }
    }

    private fun onRerouteResult(gen: Int, result: Result<Route>) {
        if (gen != rerouteGen || activeRoute == null) return // superseded or stopped
        rerouting = false
        result.onFailure { e ->
            Log.w(TAG, "Route: reroute failed", e)
            // Said once per off-route episode; retried on the next off-route fix after the gap.
            if (!rerouteProblemSaid) {
                rerouteProblemSaid = true
                voice?.say(if (e is org.chronoscompanion.app.route.OsrmClient.RouteException)
                    "No encontré una ruta desde aquí" else "Sin conexión para recalcular. Reintentando")
            }
            return
        }
        val newRoute = result.getOrThrow()
        if (offRouteSinceMs == 0L) {
            // Back on the original route while the request was out: keep it.
            Log.i(TAG, "Route: back on route, new route discarded")
            return
        }
        Log.i(TAG, "Route: rerouted, ${"%.1f".format(java.util.Locale.US, newRoute.distanceM / 1000)} km")
        tracker?.close()
        tracker = RouteTracker(newRoute, this, appendTrace = true)
        activeRoute = newRoute
        routeS = 0.0
        offRouteSinceMs = 0L
        arrivedAtMs = 0L
        rerouteProblemSaid = false
        voice?.reset()
        turnBeeper.reset()
        if (status == Status.CONNECTED) {
            sendRouteState(newRoute, routeS)
            lastRouteSendMs = SystemClock.elapsedRealtime()
        }
        // Limits after the swap: the rider gets the new line ~1 s sooner, the sign follows.
        routeWorker.execute { org.chronoscompanion.app.route.ValhallaClient.attachSpeedLimits(newRoute) }
    }

    // --- Google Maps mode: current speed + posted limit (0xC6) ---------------------------
    // Maps' notification carries neither. GPS is already on for Maps' own navigation, so
    // listening too costs next to nothing; started on the first Maps update, stopped when
    // its notification goes away or a route takes over.
    private var mapsSpeedActive = false
    /** Latest GPS speed while Google Maps navigates, for timing the turn beep there. */
    private var mapsSpeedMps: Double? = null
    private var mapsSpeedAtMs = 0L
    /** Beep at the moment to turn - both modes. See TurnBeeper. */
    private val turnBeeper by lazy { TurnBeeper(this) }
    private val mapsFixes = ArrayDeque<Location>()
    private var mapsLimitKmh: Int? = null
    private var mapsLimitAtMs = 0L
    private var mapsLimitBearing = Float.NaN
    private var mapsLimitInFlight = false

    private val mapsSpeedListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onMapsFix(location)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun hasFineLocation() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Called by the notification listener on every Google Maps navigation update. */
    @SuppressLint("MissingPermission")
    fun onMapsNavigationUpdate() {
        if (mapsSpeedActive || isRouteActive || isRouteDemoRunning || !hasFineLocation()) return
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, mapsSpeedListener, Looper.getMainLooper())
            mapsSpeedActive = true
            Log.i(TAG, "Maps speedometer started")
        } catch (e: Exception) {
            // Typically: the service was restarted in the background and couldn't take the
            // location type. Opening the app and tapping "Conectar" fixes it.
            Log.w(TAG, "Maps speedometer: no GPS access", e)
        }
    }

    fun onMapsNavigationStopped() {
        stopMapsSpeed(sendHide = true)
        turnBeeper.reset()
    }

    /**
     * Google Maps mode, on every Maps navigation update: [title] = distance to the next
     * maneuver ("150 m"), [totalDistance] = to the destination. Maps decides what is a
     * maneuver here (it only lists real ones); the last one is the destination when the
     * two distances meet. Speed comes from the speedometer's GPS (fresh within 3 s).
     */
    fun onMapsManeuver(title: String, totalDistance: String, text: String, iconCrc: Long) {
        if (isRouteActive || isRouteDemoRunning) return
        val dist = RouteFormat.parseMeters(title)
        if (dist == null) {
            turnBeeper.update(null, 0.0, null, false, false)
            return
        }
        val total = RouteFormat.parseMeters(totalDistance)
        val arrival = total != null && total <= dist + 30
        val speed = if (SystemClock.elapsedRealtime() - mapsSpeedAtMs < 3000) mapsSpeedMps else null
        turnBeeper.update("m:$text:$iconCrc", dist, speed, arrival, TurnBeepPrefs.isEnabled(this))
    }

    /** Route mode: beep for the next maneuver if it's a real turn or the destination -
     * "keep straight" style steps (STRAIGHT arrow) don't get one. */
    private fun beepForRoute(r: Route, s: Double, speedMps: Double?) {
        val next = r.nextManeuver(s)
        val arrival = next == null || next.glyph == RouteIcons.Glyph.ARRIVE
        val real = next != null && next.glyph != RouteIcons.Glyph.STRAIGHT
        val at = next?.atM ?: r.road.lengthM
        val key = if (arrival || real) "r${System.identityHashCode(r)}:$at" else null
        turnBeeper.update(key, at - s, speedMps, arrival, TurnBeepPrefs.isEnabled(this))
    }

    private fun stopMapsSpeed(sendHide: Boolean) {
        if (!mapsSpeedActive) return
        mapsSpeedActive = false
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        runCatching { lm.removeUpdates(mapsSpeedListener) }
        mapsFixes.clear()
        mapsLimitKmh = null
        if (sendHide && status == Status.CONNECTED) {
            coalesce(KIND_SPEED)
            enqueuePacket(ChronosProtocol.buildSpeedInfo(null, null), KIND_SPEED)
        }
        Log.i(TAG, "Maps speedometer stopped")
    }

    private fun onMapsFix(fix: Location) {
        if (!mapsSpeedActive) return
        mapsSpeedMps = if (fix.hasSpeed()) fix.speed.toDouble() else null
        mapsSpeedAtMs = SystemClock.elapsedRealtime()
        mapsFixes.addLast(fix)
        while (mapsFixes.size > 10 || (fix.time - mapsFixes.first.time) > 60000) mapsFixes.removeFirst()
        maybeLookupMapsLimit(fix)
        if (status == Status.CONNECTED) {
            val kmh = if (fix.hasSpeed()) (fix.speed * 3.6f).toInt() else null
            coalesce(KIND_SPEED)
            enqueuePacket(ChronosProtocol.buildSpeedInfo(kmh, mapsLimitKmh), KIND_SPEED)
        }
    }

    /** Ask the map which road we're on and its limit: every 12 s while moving, or 3 s
     * after a turn. Uses the recent fixes (accurate ones only, >= 30 m of track) so the
     * matcher can tell a road from the one crossing it. One request at a time. */
    private fun maybeLookupMapsLimit(fix: Location) {
        if (mapsLimitInFlight || !fix.hasSpeed() || fix.speed < 3f) return
        val now = SystemClock.elapsedRealtime()
        val turned = fix.hasBearing() && !mapsLimitBearing.isNaN() &&
            Math.abs(((fix.bearing - mapsLimitBearing) % 360 + 540) % 360 - 180) > 50
        val due = now - mapsLimitAtMs > MAPS_LIMIT_EVERY_MS ||
            (turned && now - mapsLimitAtMs > MAPS_LIMIT_AFTER_TURN_MS)
        if (!due) return
        val pts = mapsFixes.filter { it.accuracy <= 30f }.takeLast(8)
        if (pts.size < 3 || pts.first().distanceTo(pts.last()) < 30f) return
        mapsLimitInFlight = true
        mapsLimitAtMs = now
        mapsLimitBearing = if (fix.hasBearing()) fix.bearing else Float.NaN
        routeWorker.execute {
            val limit = runCatching { org.chronoscompanion.app.route.ValhallaClient.speedLimitHere(pts) }
                .onFailure { Log.w(TAG, "Maps limit lookup failed", it) }.getOrNull()
            mainHandler.post {
                mapsLimitInFlight = false
                if (mapsSpeedActive) mapsLimitKmh = limit
            }
        }
    }

    /** Re-promote the foreground service, adding the location type while a route runs.
     * Required on Android 14 to keep getting fixes with the screen off; only allowed
     * while the app is visible - startRoute() is called from the share screen, so it is. */
    private fun setForegroundTypes(withLocation: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (withLocation) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        try {
            startForeground(NOTIF_ID, buildNotification(status), types)
        } catch (e: Exception) {
            // SecurityException without the permission, or ForegroundServiceStartNotAllowed
            // if somehow not visible. Tracking still works with the screen on; say so.
            Log.w(TAG, "Could not set foreground types (location=$withLocation)", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRouteTracking() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            // GPS only, 1 Hz. The network provider is too coarse to place a rider on a lane
            // of road, and mixing it in would make s jump between the two sources.
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, routeLocationListener, Looper.getMainLooper())
        } catch (e: Exception) {
            Log.w(TAG, "Route: cannot request GPS updates", e)
            toast(R.string.route_err_no_fix)
        }
    }

    private fun stopRouteTracking() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        runCatching { lm.removeUpdates(routeLocationListener) }
        tracker?.close()
        tracker = null
    }

    private fun sendRouteState(r: Route, s: Double) {
        val next = r.nextManeuver(s)
        val glyph = next?.glyph ?: RouteIcons.Glyph.ARRIVE
        val bits = RouteIcons.bits(glyph)
        val crc = ChronosProtocol.crc32Of(bits)
        val remainingS = r.remainingDurationS(s)
        // Same shapes Google Maps sends ("850 m", "1 h 20 min", "121,8 km", "Llegada:
        // 20:36"), so the watch's ring, trip tracker and info line parse route mode exactly
        // like Maps. Data before icon - see handleMapsNotification for why order matters.
        sendNavigationData(
            title = RouteFormat.distance((next?.atM ?: r.road.lengthM) - s),
            duration = RouteFormat.duration(remainingS),
            distance = RouteFormat.distance(r.remainingDistanceM(s)),
            eta = "Llegada: " + RouteFormat.clock(System.currentTimeMillis() + (remainingS * 1000).toLong()),
            directions = next?.text ?: "Llegada a destino",
            speed = "",
            hasIcon = true,
            iconCrc = crc
        )
        sendNavigationIcon(bits, crc)
        val left = r.remainingDistanceM(s)
        if (abs(left - widgetRemainingM) >= 500) {
            widgetRemainingM = left
            org.chronoscompanion.app.widget.NaviWidget.update(this)
        }
        updateRouteScale()
        // Same zoom for the route line and, through the pose, for the streets.
        val pose = riderPose(r, s)
        sendRouteFrame(r.road.frameAt(s, routeScale), routeSpeedKmh, r.speedLimitAt(s), pose)
        if (pose != null) {
            maybeFetchStreets(r, s)
            sendStreetsNear(pose.xDm / 10.0, pose.yDm / 10.0)
        }
    }

    fun startRoute(route: Route, destination: org.chronoscompanion.app.route.Destination? = null,
                   prefs: org.chronoscompanion.app.route.RoutePrefs? = null) {
        stopMapsSpeed(sendHide = false) // the route frames carry speed/limit from here on
        stopRouteDemo()
        stopRoute()
        routeDest = destination
        if (prefs != null) routePrefs = prefs
        offRouteSinceMs = 0L
        rerouting = false
        rerouteProblemSaid = false
        lastFixAtMs = 0L
        lastSpeedMps = 0.0
        activeRoute = route
        routeS = 0.0
        routeSpeedKmh = null
        arrivedAtMs = 0L
        lastRouteSendMs = 0L
        tracker = RouteTracker(route, this)
        streetsRoute = null
        streetsCoveredUntil = 0.0
        streetsFetching = false
        streetsRetryAtMs = 0L
        streetsNeedClear = true
        routeScale = Polyline.SCALE_MAX_PX_PER_M
        // Streets the share screen already started loading for this very route.
        val pre = org.chronoscompanion.app.route.StreetPrefetch.take(route)
        val map = pre?.map ?: StreetMap(route.originLat, route.originLon)
        streetMap = map
        if (pre != null) {
            if (pre.done) {
                onStreetsOutcome(route, pre.toS, pre.outcome)
            } else {
                streetsFetching = true
                pre.onDone = {
                    if (streetMap === map) {
                        streetsFetching = false
                        onStreetsOutcome(route, pre.toS, pre.outcome)
                    }
                }
            }
        }
        voice = RouteVoice(this).also { it.announceStart(route) }
        setForegroundTypes(withLocation = true)
        startRouteTracking()
        mainHandler.post(routeRunnable)
        widgetRemainingM = route.distanceM
        org.chronoscompanion.app.widget.NaviWidget.update(this)
    }

    fun stopRoute() {
        if (activeRoute == null) return
        activeRoute = null
        rerouteGen++ // any in-flight reroute answer is now stale
        rerouting = false
        turnBeeper.reset()
        mainHandler.removeCallbacks(routeRunnable)
        stopRouteTracking()
        voice?.shutdown()
        voice = null
        // The route clear below makes the watch drop its streets too.
        streetMap = null
        streetsRoute = null
        coalesce(KIND_STREETS)
        sentStreetIds.clear()
        sentStreetPts = 0
        streetsNeedClear = true
        // Keep the location type: the Google Maps speedometer may need it next.
        setForegroundTypes(withLocation = hasFineLocation())
        sendRouteClear()
        sendNavigationStop()
        org.chronoscompanion.app.widget.NaviWidget.update(this)
    }

    fun sendNavigationStop() {
        requestNavPriority(false)
        // The watch has no icon once navigation stops, so the next one must be sent again.
        lastSentIconCrc = 0L
        coalesce(KIND_NAV_DATA)
        coalesce(KIND_NAV_ICON)
        enqueuePacket(ChronosProtocol.buildNavigationStop())
    }

    /**
     * Raises the BLE connection interval while navigating (~40ms -> ~11ms on most phones),
     * which is the dominant cost of a multi-chunk update since every chunk waits a full
     * interval for its write acknowledgement.
     */
    /**
     * Clears navigation link state when the GATT connection goes away. Without this a
     * reconnecting watch would sit with no turn icon at all: the CRC check would keep
     * matching the icon the *previous* connection had delivered and skip every resend.
     * The connection priority is a property of that dead connection too, so the flag must
     * drop as well or the next connection would never be raised to HIGH.
     */
    private fun resetNavState() {
        lastSentIconCrc = 0L
        navPriorityActive = false
        startedGroup = -1L
    }

    @SuppressLint("MissingPermission")
    private fun requestNavPriority(active: Boolean) {
        if (active == navPriorityActive) return
        val gatt = bluetoothGatt ?: return
        navPriorityActive = active
        val granted = gatt.requestConnectionPriority(
            if (active) BluetoothGatt.CONNECTION_PRIORITY_HIGH
            else BluetoothGatt.CONNECTION_PRIORITY_BALANCED
        )
        Log.i(TAG, "Connection priority ${if (active) "HIGH" else "BALANCED"} requested=$granted")
    }

    fun sendNotification(iconCode: Int, message: String) {
        enqueuePacket(ChronosProtocol.buildNotification(iconCode, message))
    }

    private val timeSyncRunnable = object : Runnable {
        override fun run() {
            sendTimeSync()
            mainHandler.postDelayed(this, TIME_SYNC_INTERVAL_MS)
        }
    }

    private fun sendTimeSync() {
        Log.i(TAG, "Sending time sync")
        enqueuePacket(ChronosProtocol.buildSetTime(java.util.Calendar.getInstance()))
    }

    private fun startTimeSyncTimer() {
        mainHandler.removeCallbacks(timeSyncRunnable)
        mainHandler.postDelayed(timeSyncRunnable, TIME_SYNC_INTERVAL_MS)
    }

    private fun stopTimeSyncTimer() {
        mainHandler.removeCallbacks(timeSyncRunnable)
    }

    private val weatherRunnable = object : Runnable {
        override fun run() {
            sendWeather()
            mainHandler.postDelayed(this, WEATHER_REFRESH_INTERVAL_MS)
        }
    }

    /** Fetches from Open-Meteo on a background thread (network I/O), then sends the
     * resulting packets back on the main handler like every other BLE write. */
    /**
     * Fetches from Open-Meteo on a background thread, then hands the result to
     * [pushWeather]. Reuses [cachedWeather] when it is still fresh, so a watch that just
     * rebooted can be repopulated without paying for another network round trip.
     */
    private fun sendWeather() {
        val cached = cachedWeather
        if (cached != null &&
            SystemClock.elapsedRealtime() - cachedWeatherAtMs < WEATHER_REFRESH_INTERVAL_MS
        ) {
            Log.i(TAG, "Weather still fresh, resending cached data")
            pushWeather(cached)
            return
        }
        Thread {
            // resolveLocation() falls back through an active fix and then a remembered
            // one; plain getLastLocation() returned null whenever no other app had asked
            // for a position recently, which silently killed weather altogether.
            val location = WeatherFetcher.resolveLocation(this)
            if (location == null) {
                Log.w(TAG, "sendWeather: could not determine a location")
                return@Thread
            }
            val result = WeatherFetcher.fetch(this, location)
            if (result == null) {
                Log.w(TAG, "sendWeather: fetch failed")
                return@Thread
            }
            mainHandler.post {
                cachedWeather = result
                cachedWeatherAtMs = SystemClock.elapsedRealtime()
                pushWeather(result)
            }
        }.start()
    }

    /** Queues the packets for an already-obtained forecast. Main thread only. */
    private fun pushWeather(result: WeatherFetcher.Result) {
        lastWeatherSendAtMs = SystemClock.elapsedRealtime()
        weatherSentThisConnection = true
        Log.i(TAG, "Sending weather: ${result.city}, today ${result.days.firstOrNull()?.temp}°")
        // Order matters: the high/low packet (0x88) is the only one that makes the watch
        // rebuild its weather lists (CF_WEATHER a=2 -> weatherUpdate), and it rebuilds at
        // once if the weather screen is open. Hourly, city and UV carry no trigger, so they
        // must already be there by then - sent after it, the hourly panels stayed at
        // "00:00 / 0°C" until the next refresh 30 minutes later.
        if (result.hourly.isNotEmpty()) {
            enqueuePacket(ChronosProtocol.buildWeatherHourly(result.hourly))
        }
        if (result.city.isNotEmpty()) {
            enqueuePacket(ChronosProtocol.buildWeatherCity(result.city))
        }
        enqueuePacket(ChronosProtocol.buildWeatherUvPressure(result.uvIndex, result.pressureHpa))
        enqueuePacket(ChronosProtocol.buildWeatherDaily(result.days))
        enqueuePacket(ChronosProtocol.buildWeatherHighLow(result.days))
    }

    /**
     * Called on every successful reconnect - not just the very first one. The watch's
     * Extreme Power Save mode disconnects and reconnects BLE every time the screen
     * sleeps/wakes on battery, which used to make this always re-send weather 2s later
     * regardless of how recently it last succeeded. That turned a normally-rare
     * rebuildWeatherLists() LVGL rebuild (see app_hal.cpp) into something running many
     * times per hour, which is what actually triggered a real firmware crash there
     * (Guru Meditation LoadProhibited inside lv_obj_clean, confirmed via a live serial
     * capture). Only schedule the near-immediate send on a genuinely fresh connection or
     * once the normal 30-minute interval has actually elapsed; otherwise just let the
     * already-running weatherRunnable cycle continue on its own schedule.
     */
    private fun startWeatherTimer() {
        mainHandler.removeCallbacks(weatherRunnable)
        val elapsed = SystemClock.elapsedRealtime() - lastWeatherSendAtMs
        val delay = when {
            // Any connection that hasn't been given weather yet gets it right away. A
            // power-cycled watch has lost everything it was told, so making it wait is
            // never right - and it costs nothing, because sendWeather() reuses the
            // cached forecast instead of re-fetching. The old version gated this behind
            // a 60s floor, which meant power-cycling within a minute of a send left the
            // watch with no weather for the remainder of the 30-minute window.
            !weatherSentThisConnection -> 2000L
            // Normal periodic refresh.
            elapsed >= WEATHER_REFRESH_INTERVAL_MS -> 2000L
            else -> WEATHER_REFRESH_INTERVAL_MS - elapsed
        }
        mainHandler.postDelayed(weatherRunnable, delay)
    }

    private fun stopWeatherTimer() {
        mainHandler.removeCallbacks(weatherRunnable)
    }

    /** [packedIcon] must be the 288-byte buffer from [ChronosProtocol.packIconBits]. */
    fun sendNavigationIcon(packedIcon: ByteArray, crc: Long) {
        // The single biggest win for navigation latency: the turn arrow is the same image
        // for the entire approach to a turn, yet Maps re-posts its notification about once a
        // second. Sending it every time meant ~18 of the ~24 chunks per update - roughly 75%
        // of all navigation airtime - carried an image the watch already had and discarded
        // by CRC on arrival.
        if (crc == lastSentIconCrc) return
        lastSentIconCrc = crc
        coalesce(KIND_NAV_ICON)
        for (chunkPos in 0..2) {
            val chunkBytes = packedIcon.copyOfRange(chunkPos * 96, chunkPos * 96 + 96)
            enqueuePacket(
                ChronosProtocol.buildNavigationIconChunk(chunkPos, crc, chunkBytes),
                KIND_NAV_ICON
            )
        }
    }

    // ---------------------------------------------------------------------
    // Foreground notification (required to keep a BLE connection + notification
    // listener alive reliably while the app is backgrounded)
    // ---------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Conexión con el navegador", NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun startForeground() {
        // Use the CURRENT status, not a hardcoded DISCONNECTED: onStartCommand() runs
        // again on every tap of "Conectar", so tapping it while already connected used to
        // rewrite the notification to "Buscando el navegador…" even though the connection was
        // fine and nothing was actually re-scanned.
        val notification = buildNotification(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Location type from the start (this runs on "Conectar", with the app visible):
            // Android 14 only lets a location-type service be promoted while the app is in
            // the foreground, and Google Maps navigation - when the speedometer needs GPS -
            // starts with this app in the background. Declaring the type costs nothing;
            // the GPS is only read while navigating.
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (hasFineLocation()) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            try {
                startForeground(NOTIF_ID, notification, types)
            } catch (e: Exception) {
                Log.w(TAG, "Location FGS type refused, connectedDevice only", e)
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            }
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(s: Status) {
        val notification = buildNotification(s)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, notification)
    }

    private fun buildNotification(s: Status): Notification {
        val text = when (s) {
            Status.DISCONNECTED -> "Buscando el navegador…"
            Status.SCANNING -> "Buscando el navegador…"
            Status.CONNECTING -> "Conectando con el navegador…"
            Status.CONNECTED -> "Conectado al navegador"
        }
        val stopIntent = Intent(this, ChronosBleService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Detener",
            stopPendingIntent
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .addAction(stopAction)
            .build()
    }
}
