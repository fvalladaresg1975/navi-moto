package org.chronoscompanion.app.trip

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.chronoscompanion.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** «Último viaje»: the picture of the chosen ride (the newest at first), with Compartir
 * and Guardar en galería, and the last rides below to pick another. */
class TripsActivity : AppCompatActivity() {

    private lateinit var image: ImageView
    private lateinit var drawingText: TextView
    private lateinit var historyList: LinearLayout
    private var trips: List<Trip> = emptyList()
    private var selectedId = 0L
    private val redrawing = HashSet<Long>()

    private val changedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trips)
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        image = findViewById(R.id.tripImage)
        drawingText = findViewById(R.id.drawingText)
        historyList = findViewById(R.id.historyList)
        findViewById<View>(R.id.shareButton).setOnClickListener { share() }
        findViewById<View>(R.id.saveButton).apply {
            // Android 9 and older would need a storage permission for this; share instead.
            visibility = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) View.VISIBLE else View.GONE
            setOnClickListener { saveToGallery() }
        }
        findViewById<View>(R.id.deleteButton).setOnClickListener { askDelete() }
        selectedId = savedInstanceState?.getLong("selected") ?: 0L
        ContextCompat.registerReceiver(this, changedReceiver, IntentFilter(TripStore.ACTION_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(changedReceiver) }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("selected", selectedId)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        trips = TripStore.list(this)
        val empty = trips.isEmpty()
        findViewById<View>(R.id.emptyText).visibility = if (empty) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tripPanel).visibility = if (empty) View.GONE else View.VISIBLE
        if (empty) return
        if (trips.none { it.id == selectedId }) selectedId = trips.first().id
        showSelected()
        buildHistory()
    }

    private val selected: Trip? get() = trips.firstOrNull { it.id == selectedId }

    private fun showSelected() {
        val t = selected ?: return
        val f = TripStore.imageFile(this, t.id)
        val bmp = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
        image.setImageBitmap(bmp)
        if (bmp == null) {
            image.minimumHeight = (resources.displayMetrics.widthPixels * 1.1).toInt()
            drawingText.visibility = View.VISIBLE
            // Just saved (still drawing), or the picture got lost: draw it from the track.
            if (redrawing.add(t.id)) {
                window.decorView.postDelayed({
                    if (!TripStore.imageFile(this, t.id).exists()) {
                        TripImage.redraw(applicationContext, t) { TripStore.notifyChanged(applicationContext) }
                    }
                }, 4000)
            }
        } else {
            image.minimumHeight = 0
            drawingText.visibility = View.GONE
        }
    }

    private fun buildHistory() {
        findViewById<TextView>(R.id.historyTitle).text = getString(R.string.trip_history, TripStore.MAX_TRIPS)
        historyList.removeAllViews()
        val dp = resources.displayMetrics.density
        val es = Locale("es", "CL")
        val when_ = SimpleDateFormat("EEE d MMM, HH:mm", es)
        val secondary = obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary)).let {
            val c = it.getColorStateList(0)
            it.recycle()
            c
        }
        trips.forEachIndexed { i, t ->
            if (i > 0) {
                historyList.addView(View(this).apply {
                    setBackgroundColor(ContextCompat.getColor(context, R.color.row_divider))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt())
                        .apply { marginStart = (84 * dp).toInt() }
                })
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
                val bg = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, bg, true)
                setBackgroundResource(bg.resourceId)
                setOnClickListener {
                    selectedId = t.id
                    showSelected()
                    buildHistory()
                }
            }
            val thumb = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams((52 * dp).toInt(), (65 * dp).toInt())
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF0A0E10.toInt())
            }
            loadThumb(thumb, t.id)
            row.addView(thumb)
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = (16 * dp).toInt() }
            }
            texts.addView(TextView(this).apply {
                text = t.dest ?: TripImage.modeLabel(t.mode)
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                if (t.id == selectedId) {
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(context, R.color.cat_navigation))
                }
            })
            val (dv, du) = TripImage.durationParts(t.movingS)
            texts.addView(TextView(this).apply {
                text = "${TripImage.km(t.distanceM)} km · $dv $du · ${when_.format(Date(t.startMs))}"
                textSize = 12.5f
                if (secondary != null) setTextColor(secondary)
            })
            row.addView(texts)
            historyList.addView(row)
        }
    }

    private fun loadThumb(view: ImageView, id: Long) {
        val f = TripStore.imageFile(this, id)
        Thread {
            val bmp: Bitmap? = if (f.exists())
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 4 }) else null
            runOnUiThread { if (!isDestroyed) view.setImageBitmap(bmp) }
        }.start()
    }

    private fun share() {
        val t = selected ?: return
        val f = TripStore.imageFile(this, t.id)
        if (!f.exists()) return
        val uri = FileProvider.getUriForFile(this, "$packageName.trips", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.trip_share_chooser)))
    }

    private fun saveToGallery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val t = selected ?: return
        val f = TripStore.imageFile(this, t.id)
        if (!f.exists()) return
        // One copy per ride: a second tap would only add "(1)" duplicates to the gallery.
        val prefs = getSharedPreferences("trips", MODE_PRIVATE)
        val saved = prefs.getStringSet("saved_to_gallery", emptySet())!!
        if (t.id.toString() in saved) {
            Toast.makeText(this, R.string.trip_already_saved, Toast.LENGTH_LONG).show()
            return
        }
        val name = "NaviMoto_viaje_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(t.startMs)) + ".png"
        val ok = runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Navi Moto")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
            contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        }.isSuccess
        if (ok) prefs.edit().putStringSet("saved_to_gallery", saved + t.id.toString()).apply()
        Toast.makeText(this, if (ok) R.string.trip_saved else R.string.trip_save_failed, Toast.LENGTH_LONG).show()
    }

    private fun askDelete() {
        val t = selected ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.trip_delete_title)
            .setMessage(R.string.trip_delete_msg)
            .setPositiveButton(R.string.trip_delete_yes) { _, _ ->
                TripStore.delete(this, t.id)
                selectedId = 0L
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
