package org.chronoscompanion.app.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.chronoscompanion.app.R
import org.chronoscompanion.app.ble.ChronosBleService
import org.chronoscompanion.app.ble.ChronosProtocol
import java.io.ByteArrayOutputStream

/**
 * "Esfera con foto": pick a photo, frame it in the watch's circle, choose where the time
 * goes, and send it to the watch's "Foto" face (ChronosBleService.sendPhoto, 0xCA).
 */
class PhotoFaceActivity : AppCompatActivity(), ChronosBleService.PhotoListener {

    companion object {
        private const val TAG = "PhotoFace"
        private const val PREF_TIME_POS = "photo_time_pos"
        /** JPEG size to aim for: a few seconds over BLE, well under the watch's 300 KB. */
        private const val JPEG_TARGET_BYTES = 180 * 1024
    }

    private lateinit var crop: PhotoCropView
    private lateinit var sendButton: MaterialButton
    private lateinit var removeButton: MaterialButton
    private lateinit var pickButton: MaterialButton
    private lateinit var progress: LinearProgressIndicator
    private lateinit var statusText: TextView

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) load(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_face)
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        crop = findViewById(R.id.cropView)
        sendButton = findViewById(R.id.sendButton)
        removeButton = findViewById(R.id.removeButton)
        pickButton = findViewById(R.id.pickButton)
        progress = findViewById(R.id.sendProgress)
        statusText = findViewById(R.id.sendStatus)

        val prefs = getSharedPreferences(ChronosBleService.PREFS_NAME, MODE_PRIVATE)
        crop.timePos = prefs.getInt(PREF_TIME_POS, 0)
        val group = findViewById<MaterialButtonToggleGroup>(R.id.timePosGroup)
        val ids = intArrayOf(R.id.posTop, R.id.posCentre, R.id.posBottom)
        group.check(ids[crop.timePos.coerceIn(0, 2)])
        group.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            crop.timePos = ids.indexOf(id).coerceAtLeast(0)
            prefs.edit().putInt(PREF_TIME_POS, crop.timePos).apply()
        }

        pickButton.setOnClickListener {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        sendButton.setOnClickListener { send() }
        removeButton.setOnClickListener {
            val service = connectedService() ?: return@setOnClickListener
            service.removePhoto()
            statusText.setText(R.string.photo_removed)
        }
    }

    override fun onResume() {
        super.onResume()
        ChronosBleService.instance?.photoListener = this
        setBusy(ChronosBleService.instance?.isSendingPhoto == true)
    }

    override fun onPause() {
        super.onPause()
        ChronosBleService.instance?.let { if (it.photoListener === this) it.photoListener = null }
    }

    private fun connectedService(): ChronosBleService? {
        val s = ChronosBleService.instance
        if (s == null || ChronosBleService.status != ChronosBleService.Status.CONNECTED) {
            statusText.setText(R.string.photo_not_connected)
            return null
        }
        return s
    }

    private fun load(uri: Uri) {
        statusText.setText(R.string.photo_loading)
        Thread {
            val bmp = try {
                decode(uri)
            } catch (e: Exception) {
                Log.e(TAG, "Could not open $uri", e)
                null
            }
            runOnUiThread {
                if (bmp == null) {
                    statusText.setText(R.string.photo_open_failed)
                } else {
                    crop.bitmap = bmp
                    sendButton.isEnabled = true
                    statusText.text = ""
                }
            }
        }.start()
    }

    /** Software bitmap (drawn into a Canvas later), upright, at most PHOTO_MAX_SIDE. */
    private fun decode(uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val src = ImageDecoder.createSource(contentResolver, uri)
            return ImageDecoder.decodeBitmap(src) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                d.setTargetSampleSize(clampSide(info.size.width, info.size.height))
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val opts = BitmapFactory.Options().apply { inSampleSize = clampSide(bounds.outWidth, bounds.outHeight) }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun send() {
        val service = connectedService() ?: return
        val framed = crop.render() ?: return
        val pos = crop.timePos
        setBusy(true)
        statusText.setText(R.string.photo_preparing)
        Thread {
            val jpeg = compress(framed)
            runOnUiThread {
                Log.i(TAG, "JPEG ${jpeg.size} bytes")
                if (!service.sendPhoto(jpeg, pos)) {
                    setBusy(false)
                    statusText.setText(R.string.photo_not_connected)
                }
            }
        }.start()
    }

    /** Baseline JPEG (what the watch's decoder reads), quality lowered until it fits. */
    private fun compress(b: Bitmap): ByteArray {
        var q = 88
        while (true) {
            val out = ByteArrayOutputStream()
            b.compress(Bitmap.CompressFormat.JPEG, q, out)
            if (out.size() <= JPEG_TARGET_BYTES || q <= 40) return out.toByteArray()
            q -= 8
        }
    }

    private fun setBusy(busy: Boolean) {
        sendButton.isEnabled = !busy && crop.bitmap != null
        pickButton.isEnabled = !busy
        removeButton.isEnabled = !busy
        progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
        if (!busy) progress.setProgressCompat(0, false)
    }

    override fun onPhotoProgress(sent: Int, total: Int) {
        if (total <= 0) return
        val pct = sent * 100 / total
        progress.setProgressCompat(pct, true)
        statusText.text = if (sent < total) getString(R.string.photo_sending, pct)
        else getString(R.string.photo_saving)
    }

    override fun onPhotoDone(status: Int) {
        setBusy(false)
        statusText.setText(
            when (status) {
                ChronosProtocol.PHOTO_OK -> R.string.photo_done
                ChronosProtocol.PHOTO_ERR_TRANSFER -> R.string.photo_err_transfer
                ChronosProtocol.PHOTO_ERR_DECODE -> R.string.photo_err_decode
                ChronosProtocol.PHOTO_ERR_MEMORY -> R.string.photo_err_memory
                else -> R.string.photo_err_no_answer
            }
        )
    }
}
