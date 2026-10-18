package org.chronoscompanion.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import org.chronoscompanion.app.MainActivity
import org.chronoscompanion.app.R
import org.chronoscompanion.app.ble.ChronosBleService
import org.chronoscompanion.app.route.RouteFormat
import org.chronoscompanion.app.route.StreetMapPrefs
import org.chronoscompanion.app.route.TurnBeepPrefs

/**
 * Home-screen widget: connection status (and the distance left while a route runs), one
 * main action - Conectar when stopped, Detener ruta during a route, otherwise Abrir - and
 * the turn-beep and street-layer switches, so the common things don't need the app open.
 *
 * Nothing polls: NaviWidget.update() is called wherever that state changes (the service's
 * status broadcast, route start/stop/progress, the switches here and in the app).
 *
 * "Conectar" opens the app and connects from there, rather than starting the service from
 * the background: the app is what asks for any missing permission, and Android only lets
 * a location-type foreground service start from a visible app.
 */
class NaviWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_STOP_ROUTE = "org.chronoscompanion.app.widget.STOP_ROUTE"
        private const val ACTION_TOGGLE_BEEP = "org.chronoscompanion.app.widget.TOGGLE_BEEP"
        private const val ACTION_TOGGLE_STREETS = "org.chronoscompanion.app.widget.TOGGLE_STREETS"
        private const val ACTION_BRIGHT_DOWN = "org.chronoscompanion.app.widget.BRIGHT_DOWN"
        private const val ACTION_BRIGHT_UP = "org.chronoscompanion.app.widget.BRIGHT_UP"
        /** Brightness steps of 10 %, never below 10 % (an AMOLED at 0 looks switched off). */
        private const val BRIGHT_STEP_PCT = 10
        private const val BRIGHT_MIN_PCT = 10

        private fun toPct(level: Int) = (level * 100 + 127) / 255
        private fun toLevel(pct: Int) = ((pct * 255 + 50) / 100).coerceIn(1, 255)

        /** Redraws every placed widget from the current state. Cheap; main thread. */
        fun update(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, NaviWidget::class.java))
            if (ids.isEmpty()) return
            mgr.updateAppWidget(ids, views(ctx))
        }

        private fun views(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_navi)
            val service = ChronosBleService.instance
            val status = ChronosBleService.status
            val routeLeft = service?.routeRemainingM
            val demo = service?.isRouteDemoRunning == true

            val (text, color) = when {
                service == null -> ctx.getString(R.string.widget_status_stopped) to R.color.status_disconnected
                status == ChronosBleService.Status.CONNECTED && routeLeft != null ->
                    ctx.getString(R.string.widget_status_route, RouteFormat.distance(routeLeft)) to R.color.status_connected
                status == ChronosBleService.Status.CONNECTED && demo ->
                    ctx.getString(R.string.widget_status_demo) to R.color.status_connected
                status == ChronosBleService.Status.CONNECTED ->
                    ctx.getString(R.string.widget_status_connected) to R.color.status_connected
                status == ChronosBleService.Status.CONNECTING ->
                    ctx.getString(R.string.widget_status_connecting) to R.color.status_connecting
                else -> ctx.getString(R.string.widget_status_searching) to R.color.status_connecting
            }
            v.setTextViewText(R.id.w_status, text)
            v.setInt(R.id.w_dot, "setColorFilter", ContextCompat.getColor(ctx, color))

            // Main action
            val open = PendingIntent.getActivity(ctx, 1,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            v.setOnClickPendingIntent(R.id.w_open, open)
            when {
                service == null -> {
                    v.setTextViewText(R.id.w_main, ctx.getString(R.string.widget_btn_connect))
                    v.setOnClickPendingIntent(R.id.w_main, PendingIntent.getActivity(ctx, 2,
                        Intent(ctx, MainActivity::class.java)
                            .setAction(MainActivity.ACTION_WIDGET_CONNECT)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                }
                routeLeft != null || demo -> {
                    v.setTextViewText(R.id.w_main, ctx.getString(R.string.widget_btn_stop))
                    v.setOnClickPendingIntent(R.id.w_main, broadcast(ctx, 3, ACTION_STOP_ROUTE))
                }
                else -> {
                    v.setTextViewText(R.id.w_main, ctx.getString(R.string.widget_btn_open))
                    v.setOnClickPendingIntent(R.id.w_main, open)
                }
            }

            // Switches: lit when on, dimmed when off.
            toggle(v, R.id.w_beep_icon, R.id.w_beep_label, TurnBeepPrefs.isEnabled(ctx))
            v.setOnClickPendingIntent(R.id.w_beep, broadcast(ctx, 4, ACTION_TOGGLE_BEEP))
            toggle(v, R.id.w_streets_icon, R.id.w_streets_label, StreetMapPrefs.isEnabled(ctx))
            v.setOnClickPendingIntent(R.id.w_streets, broadcast(ctx, 5, ACTION_TOGGLE_STREETS))

            // Brightness: the watch's own reported level; "—" until it reports (or offline).
            val level = if (status == ChronosBleService.Status.CONNECTED) service?.watchBrightness else null
            v.setProgressBar(R.id.w_bright_bar, 100, level?.let { toPct(it) } ?: 0, false)
            v.setTextViewText(R.id.w_bright_pct, level?.let { "${toPct(it)} %" } ?: "—")
            v.setOnClickPendingIntent(R.id.w_bright_down, broadcast(ctx, 6, ACTION_BRIGHT_DOWN))
            v.setOnClickPendingIntent(R.id.w_bright_up, broadcast(ctx, 7, ACTION_BRIGHT_UP))
            return v
        }

        /** One step of brightness up or down from the watch's current level. */
        private fun stepBrightness(ctx: Context, up: Boolean) {
            val s = ChronosBleService.instance
            val cur = s?.watchBrightness
            if (s == null || cur == null || ChronosBleService.status != ChronosBleService.Status.CONNECTED) {
                android.widget.Toast.makeText(ctx, R.string.widget_bright_offline, android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            // Snap to the 10 % grid first, so steps land on 10, 20 ... 100 whatever the
            // watch's own slider had left it at.
            val pct = (toPct(cur) + BRIGHT_STEP_PCT / 2) / BRIGHT_STEP_PCT * BRIGHT_STEP_PCT
            val next = (pct + if (up) BRIGHT_STEP_PCT else -BRIGHT_STEP_PCT).coerceIn(BRIGHT_MIN_PCT, 100)
            s.setWatchBrightness(toLevel(next))
        }

        private fun toggle(v: RemoteViews, icon: Int, label: Int, on: Boolean) {
            v.setInt(icon, "setImageAlpha", if (on) 255 else 80)
            v.setTextColor(label, if (on) 0xFFFFC24D.toInt() else 0x66FFFFFF)
        }

        private fun broadcast(ctx: Context, code: Int, action: String): PendingIntent =
            PendingIntent.getBroadcast(ctx, code, Intent(ctx, NaviWidget::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        mgr.updateAppWidget(ids, views(ctx))
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        when (intent.action) {
            ACTION_STOP_ROUTE -> ChronosBleService.instance?.let {
                if (it.isRouteActive) it.stopRoute() else it.stopRouteDemo()
            }
            ACTION_TOGGLE_BEEP -> TurnBeepPrefs.setEnabled(ctx, !TurnBeepPrefs.isEnabled(ctx))
            ACTION_TOGGLE_STREETS -> {
                val on = !StreetMapPrefs.isEnabled(ctx)
                StreetMapPrefs.setEnabled(ctx, on)
                ChronosBleService.instance?.onStreetMapToggled(on)
            }
            ACTION_BRIGHT_DOWN -> stepBrightness(ctx, up = false)
            ACTION_BRIGHT_UP -> stepBrightness(ctx, up = true)
            else -> return
        }
        update(ctx)
    }
}
