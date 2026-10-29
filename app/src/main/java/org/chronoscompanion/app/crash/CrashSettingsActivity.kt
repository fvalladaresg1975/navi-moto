package org.chronoscompanion.app.crash

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import org.chronoscompanion.app.R
import org.chronoscompanion.app.ble.ChronosBleService

/**
 * "Detección de caídas": the switch, up to [CrashPrefs.MAX_CONTACTS] emergency contacts,
 * the rider's name for the message, and a test that runs the whole chain (the watch's
 * countdown, the location, a "PRUEBA" SMS). The watch itself only watches while this is
 * on and someone can be texted - see [CrashPrefs.isArmed] / CrashResponder.
 */
class CrashSettingsActivity : AppCompatActivity() {

    private lateinit var enabledSwitch: SwitchMaterial
    private lateinit var contactsList: LinearLayout
    private lateinit var addButton: MaterialButton
    private lateinit var statusText: TextView

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!CrashPrefs.canSendSms(this)) {
            enabledSwitch.isChecked = false
            statusText.setText(R.string.crash_need_sms)
        }
        applied()
    }

    private val contactPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data ?: return@registerForActivityResult
        contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0) ?: ""
                val number = c.getString(1) ?: ""
                if (number.isNotBlank()) {
                    val list = CrashPrefs.contacts(this).filter { it.number != number } +
                        CrashPrefs.Contact(name, number)
                    CrashPrefs.setContacts(this, list)
                    showContacts()
                    applied()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash_settings)
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        enabledSwitch = findViewById(R.id.enabledSwitch)
        contactsList = findViewById(R.id.contactsList)
        addButton = findViewById(R.id.addContactButton)
        statusText = findViewById(R.id.statusText)

        enabledSwitch.isChecked = CrashPrefs.isEnabled(this)
        enabledSwitch.setOnCheckedChangeListener { _, on ->
            CrashPrefs.setEnabled(this, on)
            if (on) askPermissions() else applied()
        }

        val name = findViewById<EditText>(R.id.nameInput)
        name.setText(CrashPrefs.riderName(this))
        name.doAfterTextChanged { CrashPrefs.setRiderName(this, it?.toString() ?: "") }

        val call = findViewById<SwitchMaterial>(R.id.callSwitch)
        call.isChecked = CrashPrefs.callFirst(this)
        call.setOnCheckedChangeListener { _, on -> CrashPrefs.setCallFirst(this, on) }

        addButton.setOnClickListener {
            contactPicker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
        }
        findViewById<MaterialButton>(R.id.testButton).setOnClickListener { test() }
        showContacts()
    }

    override fun onResume() {
        super.onResume()
        describe()
    }

    private fun askPermissions() {
        val missing = listOf(Manifest.permission.SEND_SMS, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CALL_PHONE)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) applied() else permissions.launch(missing.toTypedArray())
    }

    /** Tell the watch, and say what state that leaves things in. */
    private fun applied() {
        ChronosBleService.instance?.sendCrashConfig()
        describe()
    }

    private fun describe() {
        statusText.setText(
            when {
                !CrashPrefs.isEnabled(this) -> R.string.crash_state_off
                CrashPrefs.contacts(this).isEmpty() -> R.string.crash_state_no_contacts
                !CrashPrefs.canSendSms(this) -> R.string.crash_need_sms
                ChronosBleService.instance?.crashSpeedMissing() == true -> R.string.crash_need_gps
                ChronosBleService.status != ChronosBleService.Status.CONNECTED -> R.string.crash_state_not_connected
                else -> R.string.crash_state_on
            }
        )
    }

    private fun showContacts() {
        contactsList.removeAllViews()
        val list = CrashPrefs.contacts(this)
        for (c in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val label = TextView(this).apply {
                text = getString(R.string.crash_contact_row, c.name.ifEmpty { c.number }, c.number)
                textSize = 15f
            }
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val remove = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                setText(R.string.crash_remove)
                setOnClickListener {
                    CrashPrefs.setContacts(this@CrashSettingsActivity, CrashPrefs.contacts(this@CrashSettingsActivity).filter { it != c })
                    showContacts()
                    applied()
                }
            }
            row.addView(remove)
            contactsList.addView(row)
        }
        if (list.isEmpty()) {
            contactsList.addView(TextView(this).apply {
                setText(R.string.crash_no_contacts)
                textSize = 14f
            })
        }
        addButton.isEnabled = list.size < CrashPrefs.MAX_CONTACTS
    }

    private fun test() {
        val service = ChronosBleService.instance
        when {
            CrashPrefs.contacts(this).isEmpty() -> statusText.setText(R.string.crash_state_no_contacts)
            !CrashPrefs.canSendSms(this) -> askPermissions()
            service == null || ChronosBleService.status != ChronosBleService.Status.CONNECTED ->
                statusText.setText(R.string.crash_state_not_connected)
            else -> {
                service.startCrashTest()
                statusText.setText(R.string.crash_test_started)
            }
        }
    }
}
