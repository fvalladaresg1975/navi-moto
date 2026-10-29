package org.chronoscompanion.app.ota

import android.Manifest
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.chronoscompanion.app.R
import org.chronoscompanion.app.ble.ChronosBleService
import org.chronoscompanion.app.ble.ChronosProtocol
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Date

/**
 * "Actualizar firmware": sends a firmware .bin to the watch over WiFi (0xCB, firmware
 * hal/esp32/navi_ota.cpp).
 *
 *  1. over BLE, ask the watch to open its own access point (SSID + a new random password);
 *  2. once it says it is ready, join that network for this app only (WifiNetworkSpecifier:
 *     Android shows its own "connect to device" dialog; the phone's internet stays on
 *     mobile data);
 *  3. send "NMOT" [size][MD5] + the image over TCP and read the watch's one-byte answer;
 *  4. the watch restarts into the new firmware and, once it has stayed connected to the
 *     phone for a few seconds, reports over BLE that it took (or that it went back).
 */
class FirmwareUpdateActivity : AppCompatActivity(), ChronosBleService.OtaListener {

    companion object {
        private const val TAG = "FirmwareUpdate"
        private const val SSID = "NaviMoto-OTA"
        private const val HOST = "192.168.4.1" // ESP32 soft-AP default
        private const val PORT = 3232
        private const val READY_TIMEOUT_MS = 15000L
        private const val JOIN_TIMEOUT_MS = 60000
        /** After the last byte: the watch checks the MD5 and the image, then answers. */
        private const val ANSWER_TIMEOUT_MS = 60000
        /** After a successful send: restart, reconnect, and the watch's confirmation. */
        private const val CONFIRM_TIMEOUT_MS = 120000L
        private const val TCP_OK = 0
    }

    private enum class Stage { IDLE, ASKING, JOINING, SENDING, RESTARTING }

    private lateinit var pickButton: MaterialButton
    private lateinit var sendButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var fileText: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var statusText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var image: FirmwareImage? = null
    private var stage = Stage.IDLE
    private var password = ""
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var uploadCancelled = false

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) load(uri)
    }

    private val wifiPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        startUpdate() // asked once; Android may allow the connection without it
    }

    private val readyTimeout = Runnable {
        if (stage == Stage.ASKING) fail(getString(R.string.ota_err_no_answer))
    }
    private val confirmTimeout = Runnable {
        if (stage == Stage.RESTARTING) finishStage(getString(R.string.ota_restart_no_news))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_firmware_update)
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        pickButton = findViewById(R.id.pickButton)
        sendButton = findViewById(R.id.sendButton)
        cancelButton = findViewById(R.id.cancelButton)
        fileText = findViewById(R.id.fileText)
        progress = findViewById(R.id.sendProgress)
        statusText = findViewById(R.id.sendStatus)

        pickButton.setOnClickListener { picker.launch(arrayOf("application/octet-stream", "*/*")) }
        sendButton.setOnClickListener { askWifiPermissionThenStart() }
        cancelButton.setOnClickListener { cancel() }
        setBusy(false)
    }

    override fun onResume() {
        super.onResume()
        ChronosBleService.instance?.otaListener = this
    }

    override fun onPause() {
        super.onPause()
        ChronosBleService.instance?.let { if (it.otaListener === this) it.otaListener = null }
    }

    override fun onDestroy() {
        if (stage == Stage.ASKING || stage == Stage.JOINING || stage == Stage.SENDING) cancel()
        releaseNetwork()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---- File ----

    private fun load(uri: Uri) {
        image = null
        sendButton.isEnabled = false
        statusText.text = ""
        var name = uri.lastPathSegment ?: "firmware.bin"
        var modified: Long? = null
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                c.getColumnIndex("last_modified").takeIf { it >= 0 && !c.isNull(it) }?.let { modified = c.getLong(it) }
            }
        }
        Thread {
            val result = try {
                val bytes = contentResolver.openInputStream(uri)?.use { input ->
                    // Never read more than a slot can take (+1 to notice it is too big).
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    while (buf.size() <= FirmwareImage.MAX_SIZE) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        buf.write(chunk, 0, n)
                    }
                    buf.toByteArray()
                } ?: ByteArray(0)
                Result.success(FirmwareImage.parse(bytes))
            } catch (e: Exception) {
                Log.w(TAG, "Not usable: $uri", e)
                Result.failure(e)
            }
            runOnUiThread {
                result.onSuccess { img ->
                    image = img
                    val sizeMb = "%.1f".format(img.bytes.size / 1048576.0)
                    val date = img.buildDate.ifEmpty {
                        modified?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "?"
                    }
                    fileText.text = getString(R.string.ota_file_info, name, sizeMb, date)
                    sendButton.isEnabled = stage == Stage.IDLE
                }.onFailure { e ->
                    fileText.text = name
                    statusText.setText(
                        when ((e as? FirmwareImage.Invalid)?.problem) {
                            FirmwareImage.Problem.WRONG_CHIP -> R.string.ota_err_wrong_chip
                            FirmwareImage.Problem.TOO_BIG -> R.string.ota_err_too_big
                            FirmwareImage.Problem.NO_WIFI_UPDATE -> R.string.ota_err_no_wifi_update
                            else -> R.string.ota_err_not_firmware
                        }
                    )
                }
            }
        }.start()
    }

    // ---- Update ----

    private fun askWifiPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            wifiPermission.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            startUpdate()
        }
    }

    private fun startUpdate() {
        val img = image ?: return
        val service = ChronosBleService.instance
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            statusText.setText(R.string.ota_err_android)
            return
        }
        if (service == null || ChronosBleService.status != ChronosBleService.Status.CONNECTED) {
            statusText.setText(R.string.photo_not_connected)
            return
        }
        password = randomPassword()
        uploadCancelled = false
        if (!service.startOta(SSID, password)) {
            statusText.setText(R.string.photo_not_connected)
            return
        }
        Log.i(TAG, "Asking the watch for its access point (${img.bytes.size} bytes to send)")
        stage = Stage.ASKING
        setBusy(true)
        progress.isIndeterminate = true
        statusText.setText(R.string.ota_asking)
        handler.postDelayed(readyTimeout, READY_TIMEOUT_MS)
    }

    override fun onOtaStatus(status: Int) {
        Log.i(TAG, "Watch OTA status $status in stage $stage")
        when (status) {
            // The watch notifies twice at times; only the first READY counts.
            ChronosProtocol.OTA_READY -> if (stage == Stage.ASKING) {
                handler.removeCallbacks(readyTimeout)
                join()
            }
            ChronosProtocol.OTA_ERR_BATTERY -> fail(getString(R.string.ota_err_battery))
            ChronosProtocol.OTA_ERR_WIFI -> fail(getString(R.string.ota_err_wifi))
            ChronosProtocol.OTA_ERR_TIMEOUT -> if (stage == Stage.JOINING) fail(getString(R.string.ota_err_join))
            ChronosProtocol.OTA_UPDATED -> finishStage(getString(R.string.ota_updated))
            ChronosProtocol.OTA_ROLLED_BACK -> finishStage(getString(R.string.ota_rolled_back))
        }
    }

    private fun join() {
        stage = Stage.JOINING
        statusText.setText(R.string.ota_joining)
        val cm = getSystemService(ConnectivityManager::class.java)
        val spec = WifiNetworkSpecifier.Builder()
            .setSsid(SSID)
            .setWpa2Passphrase(password)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var started = false
            override fun onAvailable(network: Network) {
                if (started) return
                started = true
                Log.i(TAG, "Joined the watch's network")
                handler.post { if (stage == Stage.JOINING) upload(network) }
            }

            override fun onUnavailable() {
                Log.w(TAG, "The watch's network is unavailable (not found, refused or timed out)")
                handler.post { if (stage == Stage.JOINING) fail(getString(R.string.ota_err_join)) }
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Lost the watch's network")
            }
        }
        networkCallback = cb
        // Android only offers (or auto-joins) the network once a scan has seen it, and its
        // own scans for the request were too slow: two attempts gave up without ever
        // finding the watch, the one with extra scans running joined at once. Scan
        // ourselves while waiting (foreground apps get 4 scans per 2 minutes).
        for (delay in longArrayOf(0, 6000, 15000, 30000)) {
            handler.postDelayed({ if (stage == Stage.JOINING) requestScan() }, delay)
        }
        try {
            cm.requestNetwork(request, cb, JOIN_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "requestNetwork refused", e)
            networkCallback = null
            fail(getString(R.string.ota_err_join))
        }
    }

    private fun requestScan() {
        @Suppress("DEPRECATION") // still the only way to ask for a scan; throttled, not removed
        val started = runCatching { getSystemService(WifiManager::class.java).startScan() }.getOrDefault(false)
        Log.i(TAG, "WiFi scan requested: $started")
    }

    private fun upload(network: Network) {
        val img = image ?: return
        stage = Stage.SENDING
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        progress.isIndeterminate = false
        progress.setProgressCompat(0, false)
        statusText.text = getString(R.string.ota_sending, 0)
        Thread {
            val answer = try {
                send(network, img.bytes)
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed", e)
                -1
            }
            runOnUiThread { uploadDone(answer) }
        }.start()
    }

    /** Runs off the main thread. The watch's one-byte answer (0 ok), -1 if none. */
    private fun send(network: Network, bytes: ByteArray): Int {
        var socket: Socket? = null
        for (attempt in 1..5) {
            if (uploadCancelled) return -1
            try {
                socket = network.socketFactory.createSocket().apply {
                    connect(InetSocketAddress(HOST, PORT), 10000)
                }
                break
            } catch (e: Exception) {
                Log.w(TAG, "Connect attempt $attempt failed: ${e.message}")
                Thread.sleep(1000)
            }
        }
        val s = socket ?: return -1
        s.use {
            it.soTimeout = 30000
            val out = BufferedOutputStream(it.getOutputStream(), 16 * 1024)
            val header = ByteArray(24)
            "NMOT".toByteArray(Charsets.US_ASCII).copyInto(header)
            val n = bytes.size
            header[4] = (n ushr 24).toByte()
            header[5] = (n ushr 16).toByte()
            header[6] = (n ushr 8).toByte()
            header[7] = n.toByte()
            MessageDigest.getInstance("MD5").digest(bytes).copyInto(header, 8)
            out.write(header)
            var off = 0
            var shown = -1
            while (off < n) {
                if (uploadCancelled) return -1
                val len = minOf(16 * 1024, n - off)
                out.write(bytes, off, len)
                off += len
                val pct = (off.toLong() * 100 / n).toInt()
                if (pct != shown) {
                    shown = pct
                    runOnUiThread {
                        progress.setProgressCompat(pct, true)
                        statusText.text = if (pct < 100) getString(R.string.ota_sending, pct)
                        else getString(R.string.ota_checking)
                    }
                }
            }
            out.flush()
            it.soTimeout = ANSWER_TIMEOUT_MS
            return it.getInputStream().read()
        }
    }

    private fun uploadDone(answer: Int) {
        releaseNetwork()
        if (stage != Stage.SENDING) return
        Log.i(TAG, "Watch answered $answer")
        if (answer == TCP_OK) {
            stage = Stage.RESTARTING
            setBusy(true) // hides "Cancelar": the watch is already installing it
            progress.isIndeterminate = true
            statusText.setText(R.string.ota_restarting)
            handler.postDelayed(confirmTimeout, CONFIRM_TIMEOUT_MS)
        } else {
            fail(
                getString(
                    when (answer) {
                        2 -> R.string.ota_err_too_big
                        4 -> R.string.ota_err_verify
                        else -> R.string.ota_err_transfer
                    }
                )
            )
        }
    }

    private fun cancel() {
        uploadCancelled = true
        if (stage == Stage.ASKING || stage == Stage.JOINING || stage == Stage.SENDING) {
            ChronosBleService.instance?.cancelOta()
        }
        releaseNetwork()
        if (stage != Stage.IDLE) finishStage(getString(R.string.ota_cancelled))
    }

    private fun fail(message: String) {
        Log.w(TAG, "Failed in stage $stage: $message")
        // Otherwise the watch keeps its access point up, waiting for us, for minutes.
        if (stage == Stage.ASKING || stage == Stage.JOINING) ChronosBleService.instance?.cancelOta()
        releaseNetwork()
        finishStage(message)
    }

    private fun finishStage(message: String) {
        handler.removeCallbacks(readyTimeout)
        handler.removeCallbacks(confirmTimeout)
        stage = Stage.IDLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setBusy(false)
        statusText.text = message
    }

    private fun releaseNetwork() {
        networkCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
        }
        networkCallback = null
    }

    private fun setBusy(busy: Boolean) {
        pickButton.isEnabled = !busy
        sendButton.isEnabled = !busy && image != null
        cancelButton.visibility = if (busy && stage != Stage.RESTARTING) View.VISIBLE else View.GONE
        progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
        if (!busy) {
            progress.isIndeterminate = false
            progress.setProgressCompat(0, false)
        }
    }

    private fun randomPassword(): String {
        val chars = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        return String(CharArray(16) { chars[r.nextInt(chars.length)] })
    }
}
