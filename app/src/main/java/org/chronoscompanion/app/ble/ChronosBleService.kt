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
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInFlight = false
    private var writeGeneration = 0
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
        stopTimeSyncTimer()
        stopWeatherTimer()
        stopScanIfRunning()
        unregisterCallStateReceiver()
        bluetoothGatt?.close()
        bluetoothGatt = null
        instance = null
        status = Status.DISCONNECTED
        broadcastStatus()
        // setStatus()'s updateNotification() re-posts the ongoing "Buscando el reloj…"
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
            // stayed on DISCONNECTED/"Buscando el reloj..." with nothing left to ever
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
                    saveDevice(result.device.address, result.device.name ?: "Reloj Chronos")
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
     * Chronos UART service (not just the remembered one), for the "Cambiar de reloj"
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
                val name = result.device.name ?: "Reloj Chronos"
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
                weatherSentThisConnection = false
                sessionStarted = false
                stopTimeSyncTimer()
                stopWeatherTimer()
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
                Log.i(TAG, "Write OK (${writeQueue.size} chunk(s) still queued)")
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
        writeInFlight = true
        val myGeneration = ++writeGeneration
        Log.i(TAG, "Writing ${next.size} bytes: ${next.joinToString(" ") { "%02x".format(it) }}")
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
                next,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = next
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    private fun enqueuePacket(logicalPacket: ByteArray) {
        val chunks = ChronosProtocol.chunk(logicalPacket)
        writeQueue.addAll(chunks)
        mainHandler.post { drainQueue() }
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
        enqueuePacket(packet)
    }

    fun sendNavigationStop() {
        enqueuePacket(ChronosProtocol.buildNavigationStop())
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
        enqueuePacket(ChronosProtocol.buildWeatherDaily(result.days))
        enqueuePacket(ChronosProtocol.buildWeatherHighLow(result.days))
        enqueuePacket(ChronosProtocol.buildWeatherUvPressure(result.uvIndex, result.pressureHpa))
        if (result.city.isNotEmpty()) {
            enqueuePacket(ChronosProtocol.buildWeatherCity(result.city))
        }
        if (result.hourly.isNotEmpty()) {
            enqueuePacket(ChronosProtocol.buildWeatherHourly(result.hourly))
        }
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
        for (chunkPos in 0..2) {
            val chunkBytes = packedIcon.copyOfRange(chunkPos * 96, chunkPos * 96 + 96)
            enqueuePacket(ChronosProtocol.buildNavigationIconChunk(chunkPos, crc, chunkBytes))
        }
    }

    // ---------------------------------------------------------------------
    // Foreground notification (required to keep a BLE connection + notification
    // listener alive reliably while the app is backgrounded)
    // ---------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Conexión con el reloj", NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun startForeground() {
        // Use the CURRENT status, not a hardcoded DISCONNECTED: onStartCommand() runs
        // again on every tap of "Conectar", so tapping it while already connected used to
        // rewrite the notification to "Buscando el reloj…" even though the connection was
        // fine and nothing was actually re-scanned.
        val notification = buildNotification(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
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
            Status.DISCONNECTED -> "Buscando el reloj…"
            Status.SCANNING -> "Buscando el reloj…"
            Status.CONNECTING -> "Conectando con el reloj…"
            Status.CONNECTED -> "Conectado al reloj"
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
            .setContentTitle("Chronos Companion")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .addAction(stopAction)
            .build()
    }
}
