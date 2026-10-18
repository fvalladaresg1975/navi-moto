package org.chronoscompanion.app.route

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import org.chronoscompanion.app.R
import org.chronoscompanion.app.ble.ChronosBleService
import java.util.concurrent.Executors

/**
 * Route mode entry point: a destination shared from CoMaps / Google Maps / "Open with".
 *
 * Flow: destination -> rider's position -> OSRM route -> summary -> "Iniciar en el reloj".
 * Every step reports progress on screen, and every failure says what went wrong in plain
 * words - this runs with the phone on a mount, where a silent failure is indistinguishable
 * from "still working".
 */
class ShareDestinationActivity : Activity() {

    companion object {
        private const val TAG = "ChronosRoute"
        private const val REQ_LOCATION = 41
        /** Above this the rider's fix is probably not on the road the route starts on. */
        private const val SNAP_WARN_M = 200.0
        private const val PREFS = "route_prefs"
        private const val PREF_AVOID_HIGHWAYS = "avoid_highways"
        private const val PREF_AVOID_TOLLS = "avoid_tolls"
        /** Router fair-use policy is 1 request/s; keep a margin between recomputes. */
        private const val MIN_REQUEST_GAP_MS = 1500L
    }

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var destText: TextView
    private lateinit var statusText: TextView
    private lateinit var summaryText: TextView
    private lateinit var notesText: TextView
    private lateinit var streetsText: TextView
    private lateinit var startButton: MaterialButton
    private var destination: Destination? = null
    private var route: Route? = null
    private var fix: LocationFix? = null
    private var lastFix: Location? = null
    private var requestInFlight = false
    private var recomputePending = false
    private var lastRequestAt = 0L
    private lateinit var avoidHighways: SwitchMaterial
    private lateinit var avoidTolls: SwitchMaterial

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_route)
        destText = findViewById(R.id.routeDestination)
        statusText = findViewById(R.id.routeStatus)
        summaryText = findViewById(R.id.routeSummary)
        notesText = findViewById(R.id.routeNotes)
        streetsText = findViewById(R.id.routeStreets)
        startButton = findViewById(R.id.routeStartButton)
        // The route's streets download runs on after this screen closes; this only shows it.
        StreetPrefetch.progress = { done, total, finished, ok ->
            streetsText.visibility = View.VISIBLE
            streetsText.text = when {
                !finished -> getString(R.string.route_streets_progress, done, total)
                !ok -> getString(R.string.route_streets_failed)
                total == 0 -> getString(R.string.route_streets_cached)
                else -> getString(R.string.route_streets_done)
            }
        }
        findViewById<MaterialButton>(R.id.routeCancelButton).setOnClickListener { finish() }
        startButton.setOnClickListener { startOnWatch() }

        // Remembered between routes: a rider who avoids the TAG highways in town wants
        // that every time, not re-ticked per trip.
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        avoidHighways = findViewById(R.id.routeAvoidHighways)
        avoidTolls = findViewById(R.id.routeAvoidTolls)
        avoidHighways.isChecked = sp.getBoolean(PREF_AVOID_HIGHWAYS, false)
        avoidTolls.isChecked = sp.getBoolean(PREF_AVOID_TOLLS, false)
        val onPrefChanged = { _: android.widget.CompoundButton, _: Boolean ->
            sp.edit()
                .putBoolean(PREF_AVOID_HIGHWAYS, avoidHighways.isChecked)
                .putBoolean(PREF_AVOID_TOLLS, avoidTolls.isChecked)
                .apply()
            lastFix?.let { computeRoute(it) }
            Unit
        }
        avoidHighways.setOnCheckedChangeListener(onPrefChanged)
        avoidTolls.setOnCheckedChangeListener(onPrefChanged)

        // What the other app actually shared: share formats change between Maps screens
        // and versions, and this line is what diagnoses a "no destination" report.
        Log.i(TAG, "shared: text='${DestinationParser.sharedTextOf(intent).take(400)}' " +
            "subject='${intent.getStringExtra(android.content.Intent.EXTRA_SUBJECT).orEmpty().take(200)}'")
        val local = DestinationParser.parseLocal(intent)
        if (local != null) {
            onDestination(local)
            return
        }
        val link = DestinationParser.googleLink(intent)
        if (link == null) {
            fail(getString(R.string.route_err_no_destination))
            return
        }
        status(getString(R.string.route_status_resolving))
        val subject = intent.getStringExtra(android.content.Intent.EXTRA_SUBJECT).orEmpty()
        val text = DestinationParser.sharedTextOf(intent)
        worker.execute {
            val d = runCatching { DestinationParser.resolveGoogle(link, subject, text) }
                .onFailure { Log.w(TAG, "resolveGoogle failed", it) }.getOrNull()
            ui { if (d == null) fail(getString(R.string.route_err_google)) else onDestination(d) }
        }
    }

    override fun onDestroy() {
        StreetPrefetch.progress = null
        fix?.cancel()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun onDestination(d: Destination) {
        destination = d
        destText.text = d.name
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locate()
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOCATION
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_LOCATION) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locate()
        } else {
            // Android lets the user grant only "approximate" - not enough to draw the road
            // relative to the rider, so say exactly that instead of a generic denial.
            fail(getString(R.string.route_err_precise_location))
        }
    }

    private fun locate() {
        status(getString(R.string.route_status_locating))
        val f = LocationFix(this)
        fix = f
        f.onProgress = { l -> status(getString(R.string.route_status_locating_acc, l.accuracy.toInt())) }
        f.start { l -> if (l == null) fail(getString(R.string.route_err_no_fix)) else computeRoute(l) }
    }

    private fun computeRoute(from: Location) {
        val d = destination ?: return
        lastFix = from
        // One request at a time, spaced out: toggling both switches quickly must not fire
        // a burst at a fair-use server. A change during a request is applied right after.
        if (requestInFlight) {
            recomputePending = true
            return
        }
        val wait = MIN_REQUEST_GAP_MS - (android.os.SystemClock.elapsedRealtime() - lastRequestAt)
        if (wait > 0) {
            requestInFlight = true
            startButton.postDelayed({ requestInFlight = false; computeRoute(from) }, wait)
            return
        }
        requestInFlight = true
        // The switches are read below, so this request already reflects any change that
        // arrived while waiting - it must not trigger another one afterwards.
        recomputePending = false
        lastRequestAt = android.os.SystemClock.elapsedRealtime()
        startButton.visibility = View.GONE
        status(getString(R.string.route_status_routing))
        val prefs = RoutePrefs(avoidHighways.isChecked, avoidTolls.isChecked)
        worker.execute {
            val result = runCatching { Router.fetch(from.latitude, from.longitude, d.lat, d.lon, prefs) }
            ui {
                requestInFlight = false
                if (recomputePending) {
                    recomputePending = false
                    computeRoute(from)
                    return@ui
                }
                result.onSuccess { r -> showRoute(r, from, prefs) }
                    .onFailure { e ->
                        Log.w(TAG, "Routing failed", e)
                        fail(if (e is OsrmClient.RouteException) e.message.orEmpty()
                             else getString(R.string.route_err_network))
                    }
            }
        }
    }

    private fun showRoute(r: Route, from: Location, prefs: RoutePrefs) {
        route = r
        // The whole route's streets into the phone's cache, starting now: ready by the
        // time "Iniciar" is pressed and usable without signal on the ride.
        StreetPrefetch.start(this, r)
        status(getString(R.string.route_status_ready))
        summaryText.text = "${RouteFormat.distance(r.distanceM)} · ${RouteFormat.duration(r.durationS)}"
        summaryText.visibility = View.VISIBLE

        val notes = ArrayList<String>()
        notes.add(getString(R.string.route_note_no_traffic))
        if (r.engine == "osrm") notes.add(getString(R.string.route_note_fallback))
        if (r.hasToll == true) notes.add(getString(
            if (prefs.avoidTolls) R.string.route_note_toll_unavoidable else R.string.route_note_toll))
        if (prefs.avoidHighways && r.hasHighway == true) notes.add(getString(R.string.route_note_highway_unavoidable))
        r.nextManeuver(0.0)?.let { notes.add(getString(R.string.route_note_first, RouteFormat.distance(it.atM), it.text)) }
        if (destination?.approximate == true) notes.add(getString(R.string.route_note_approximate))
        if (r.startSnapM > SNAP_WARN_M) notes.add(getString(R.string.route_note_far_from_road, r.startSnapM.toInt()))
        Log.i(TAG, "fix accuracy ${from.accuracy} m, start snap ${r.startSnapM} m")
        notesText.text = notes.joinToString("\n\n")
        notesText.visibility = View.VISIBLE
        startButton.visibility = View.VISIBLE
    }

    private fun startOnWatch() {
        val r = route ?: return
        val service = ChronosBleService.instance
        if (service == null || ChronosBleService.status != ChronosBleService.Status.CONNECTED) {
            Toast.makeText(this, R.string.route_demo_not_connected, Toast.LENGTH_LONG).show()
            return
        }
        // Destination and preferences go along so the service can reroute on its own.
        service.startRoute(r, destination, RoutePrefs(avoidHighways.isChecked, avoidTolls.isChecked))
        Toast.makeText(this, R.string.route_started, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun status(text: String) {
        statusText.text = text
    }

    private fun fail(text: String) {
        statusText.text = text
        startButton.visibility = View.GONE
    }

    private fun ui(block: () -> Unit) = runOnUiThread { if (!isFinishing && !isDestroyed) block() }
}
