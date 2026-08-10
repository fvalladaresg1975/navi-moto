package org.chronoscompanion.app

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.chronoscompanion.app.notification.NotificationAppsPrefs
import java.text.Collator

/**
 * Lets the user pick which installed apps forward their notifications to the watch
 * (see [org.chronoscompanion.app.notification.ChronosNotificationListener]). Loading
 * every installed app's label/icon can take a moment, so it happens off the main
 * thread and the list populates once ready.
 */
class NotificationAppsActivity : AppCompatActivity() {

    private data class AppEntry(val packageName: String, val label: String, val icon: Drawable)

    private lateinit var recyclerView: RecyclerView
    private lateinit var searchBox: EditText
    private lateinit var adapter: AppAdapter
    private var allApps: List<AppEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_apps)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        recyclerView = findViewById(R.id.appList)
        searchBox = findViewById(R.id.searchBox)

        adapter = AppAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadInstalledApps()
    }

    private fun loadInstalledApps() {
        Thread {
            val pm = packageManager
            val collator = Collator.getInstance()
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { it.packageName != packageName }
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { AppEntry(it.packageName, pm.getApplicationLabel(it).toString(), pm.getApplicationIcon(it)) }
                .sortedWith(compareBy(collator) { it.label })
                .toList()

            Handler(Looper.getMainLooper()).post {
                allApps = apps
                adapter.filter(searchBox.text?.toString().orEmpty())
            }
        }.start()
    }

    private inner class AppAdapter : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

        private var shown: List<AppEntry> = emptyList()

        fun filter(query: String) {
            shown = if (query.isBlank()) {
                allApps
            } else {
                allApps.filter { it.label.contains(query, ignoreCase = true) }
            }
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.appIcon)
            val label: TextView = view.findViewById(R.id.appLabel)
            val checkbox: CheckBox = view.findViewById(R.id.appCheckbox)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_notification_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = shown[position]
            holder.icon.setImageDrawable(entry.icon)
            holder.label.text = entry.label
            holder.checkbox.isChecked = NotificationAppsPrefs.isEnabled(this@NotificationAppsActivity, entry.packageName)
            holder.itemView.setOnClickListener {
                val newValue = !holder.checkbox.isChecked
                holder.checkbox.isChecked = newValue
                NotificationAppsPrefs.setEnabled(this@NotificationAppsActivity, entry.packageName, newValue)
            }
        }

        override fun getItemCount(): Int = shown.size
    }
}
