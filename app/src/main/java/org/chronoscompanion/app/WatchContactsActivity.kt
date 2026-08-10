package org.chronoscompanion.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.chronoscompanion.app.ble.ChronosBleService
import org.chronoscompanion.app.telephony.WatchContactsPrefs
import java.text.Collator

/**
 * Lets the user pick which phone contacts get synced to the watch's Contactos app, where
 * tapping one asks the phone to dial it. Selections are pushed to the watch on save.
 */
class WatchContactsActivity : AppCompatActivity() {

    private data class Row(val name: String, val number: String)

    private lateinit var recyclerView: RecyclerView
    private lateinit var searchBox: EditText
    private lateinit var adapter: ContactAdapter
    private var allContacts: List<Row> = emptyList()

    /** Keyed by number so a contact stays selected even if reloaded/refiltered. */
    private val selected = LinkedHashMap<String, Row>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_watch_contacts)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        recyclerView = findViewById(R.id.contactList)
        searchBox = findViewById(R.id.searchBox)

        adapter = ContactAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        WatchContactsPrefs.get(this).forEach { selected[it.number] = Row(it.name, it.number) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, R.string.watch_contacts_permission_missing, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        loadContacts()
    }

    /** Persist + push on the way out, so the user doesn't need a separate "save" step. */
    override fun onPause() {
        super.onPause()
        val entries = selected.values.map { WatchContactsPrefs.Entry(it.name, it.number) }
        WatchContactsPrefs.set(this, entries)
        ChronosBleService.instance?.sendContacts()
    }

    private fun loadContacts() {
        Thread {
            val collator = Collator.getInstance()
            val rows = LinkedHashMap<String, Row>() // dedupe by number
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: continue
                    val number = cursor.getString(numIdx)?.replace(" ", "")?.replace("-", "") ?: continue
                    rows[number] = Row(name, number)
                }
            }
            val sorted = rows.values.sortedWith(compareBy(collator) { it.name })
            Handler(Looper.getMainLooper()).post {
                allContacts = sorted
                adapter.filter(searchBox.text?.toString().orEmpty())
            }
        }.start()
    }

    private inner class ContactAdapter : RecyclerView.Adapter<ContactAdapter.ViewHolder>() {

        private var shown: List<Row> = emptyList()

        fun filter(query: String) {
            shown = if (query.isBlank()) {
                allContacts
            } else {
                allContacts.filter {
                    it.name.contains(query, ignoreCase = true) || it.number.contains(query)
                }
            }
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.contactName)
            val number: TextView = view.findViewById(R.id.contactNumber)
            val checkbox: CheckBox = view.findViewById(R.id.contactCheckbox)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_watch_contact, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val row = shown[position]
            holder.name.text = row.name
            holder.number.text = row.number
            holder.checkbox.isChecked = selected.containsKey(row.number)
            holder.itemView.setOnClickListener {
                if (selected.containsKey(row.number)) {
                    selected.remove(row.number)
                    holder.checkbox.isChecked = false
                } else {
                    // The watch identifies a contact by its index in the synced list, and
                    // the protocol's index is a single byte - cap well below that so a
                    // huge selection can't silently overflow or fill the watch's screen.
                    if (selected.size >= MAX_WATCH_CONTACTS) {
                        Toast.makeText(
                            this@WatchContactsActivity,
                            getString(R.string.watch_contacts_limit, MAX_WATCH_CONTACTS),
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setOnClickListener
                    }
                    selected[row.number] = row
                    holder.checkbox.isChecked = true
                }
            }
        }

        override fun getItemCount(): Int = shown.size
    }

    companion object {
        private const val MAX_WATCH_CONTACTS = 20
    }
}
