package com.grocerypos.v11.ui

import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.AppSetting
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.User
import com.grocerypos.v11.util.BackupHelper
import com.grocerypos.v11.util.PrinterHelper
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var currentUsernameField: EditText
    private lateinit var newUsernameField: EditText
    private lateinit var newPasswordField: EditText
    private lateinit var printerStatusText: TextView

    private val BT_PERMISSION_REQUEST_CODE = 501

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        l.addView(TextView(this).apply { text = "SETTINGS"; textSize = 24f; setPadding(0, 0, 0, 20) })

        listOf("Shop Name", "Phone", "Address", "Receipt Footer", "Currency", "Tax %").forEach {
            l.addView(EditText(this).apply { hint = it })
        }
        l.addView(Button(this).apply { text = "SAVE SETTINGS" })

        // ---- Printer Setup (Bluetooth 58mm) ----
        l.addView(divider())
        l.addView(TextView(this).apply {
            text = "Printer Setup (58mm Bluetooth)"
            textSize = 18f
            setPadding(0, 24, 0, 12)
        })

        printerStatusText = TextView(this).apply {
            text = "No printer selected"
            setPadding(0, 0, 0, 12)
        }
        l.addView(printerStatusText)
        loadPrinterStatus()

        l.addView(Button(this).apply {
            text = "SELECT PRINTER"
            setOnClickListener { onSelectPrinterClicked() }
        })
        l.addView(Button(this).apply {
            text = "TEST PRINT"
            setOnClickListener { onTestPrintClicked() }
        })

        // ---- Backup & Restore ----
        l.addView(divider())
        l.addView(TextView(this).apply {
            text = "Backup & Restore"
            textSize = 18f
            setPadding(0, 24, 0, 12)
        })
        l.addView(Button(this).apply {
            text = "BACKUP NOW"
            setOnClickListener { onBackupClicked() }
        })
        l.addView(Button(this).apply {
            text = "RESTORE BACKUP"
            setOnClickListener { onRestoreClicked() }
        })

        // ---- Change Username / Password ----
        l.addView(divider())
        l.addView(TextView(this).apply {
            text = "Change Login (Username / Password)"
            textSize = 18f
            setPadding(0, 24, 0, 12)
        })

        val session = getSharedPreferences("session", MODE_PRIVATE)
        val loggedInUsername = session.getString("username", "") ?: ""

        currentUsernameField = EditText(this).apply {
            hint = "Current Username"
            setText(loggedInUsername)
            isEnabled = false
        }
        newUsernameField = EditText(this).apply { hint = "New Username (blank = keep same)" }
        newPasswordField = EditText(this).apply {
            hint = "New Password (blank = keep same)"
            inputType = 0x81
        }

        l.addView(currentUsernameField)
        l.addView(newUsernameField)
        l.addView(newPasswordField)
        l.addView(Button(this).apply {
            text = "UPDATE LOGIN"
            setOnClickListener { updateLogin(loggedInUsername) }
        })

        // ---- Users & Account ----
        l.addView(divider())
        l.addView(TextView(this).apply {
            text = "Users & Account"
            textSize = 18f
            setPadding(0, 24, 0, 12)
        })
        l.addView(Button(this).apply {
            text = "MANAGE USERS"
            setOnClickListener { startActivity(Intent(this@SettingsActivity, UserManagementActivity::class.java)) }
        })
        l.addView(Button(this).apply {
            text = "LOGOUT"
            setOnClickListener { doLogout() }
        })

        setContentView(ScrollView(this).apply { addView(l) })
    }

    private fun doLogout() {
        getSharedPreferences("session", MODE_PRIVATE).edit().clear().apply()
        startActivity(Intent(this@SettingsActivity, LoginActivity::class.java))
        finish()
    }

    // ================= PRINTER =================

    private fun loadPrinterStatus() {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SettingsActivity)
            val name = db.appSettingDao().get("printer_name")?.value
            printerStatusText.text = if (!name.isNullOrEmpty()) "Selected: $name" else "No printer selected"
        }
    }

    private fun onSelectPrinterClicked() {
        if (!PrinterHelper.hasBluetoothPermission(this)) {
            PrinterHelper.requestBluetoothPermission(this, BT_PERMISSION_REQUEST_CODE)
            Toast.makeText(this, "Bluetooth permission dein, phir dobara SELECT PRINTER dabayein", Toast.LENGTH_LONG).show()
            return
        }

        val devices: List<BluetoothDevice> = PrinterHelper.pairedDevices(this)
        if (devices.isEmpty()) {
            Toast.makeText(
                this,
                "Koi paired Bluetooth printer nahi mila. Pehle phone ki Bluetooth Settings se printer ko pair karein.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val names = devices.map { it.name ?: it.address }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select 58mm Printer")
            .setItems(names) { _, which ->
                val device = devices[which]
                savePrinter(device.name ?: "Printer", device.address)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun savePrinter(name: String, mac: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SettingsActivity)
            db.appSettingDao().set(AppSetting("printer_name", name))
            db.appSettingDao().set(AppSetting("printer_mac", mac))
            db.appSettingDao().set(AppSetting("printer_width", "58"))
            printerStatusText.text = "Selected: $name"
            Toast.makeText(this@SettingsActivity, "Printer saved: $name", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onTestPrintClicked() {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SettingsActivity)
            val mac = db.appSettingDao().get("printer_mac")?.value
            if (mac.isNullOrEmpty()) {
                Toast.makeText(this@SettingsActivity, "Pehle printer select karein", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val shopName = db.appSettingDao().get("shop_name")?.value ?: "IBTISAAM Kiryana Store"
            val ok = PrinterHelper.testPrint(
                this@SettingsActivity,
                PrinterHelper.PrinterType.BLUETOOTH,
                mac,
                shopName
            )
            Toast.makeText(
                this@SettingsActivity,
                if (ok) "Test print bhej diya" else "Print fail ho gaya. Printer on hai aur range mein hai check karein.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ================= BACKUP / RESTORE =================

    private fun onBackupClicked() {
        val file = BackupHelper.backupNow(this)
        if (file != null) {
            Toast.makeText(this, "Backup ho gaya: ${file.name}", Toast.LENGTH_LONG).show()
            BackupHelper.shareBackup(this, file)
        } else {
            Toast.makeText(this, "Backup fail ho gaya", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onRestoreClicked() {
        val backups = BackupHelper.listBackups(this)
        if (backups.isEmpty()) {
            Toast.makeText(this, "Koi backup nahi mila", Toast.LENGTH_SHORT).show()
            return
        }
        val names = backups.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Backup to Restore")
            .setItems(names) { _, which ->
                confirmRestore(backups[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRestore(file: java.io.File) {
        AlertDialog.Builder(this)
            .setTitle("Restore Backup?")
            .setMessage("Ye current data ko overwrite kar dega: ${file.name}. Aage badhein?")
            .setPositiveButton("Restore") { _, _ ->
                val ok = BackupHelper.restore(this, file)
                if (ok) {
                    Toast.makeText(this, "Restore ho gaya. App ko dobara open karein.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Restore fail ho gaya", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ================= LOGIN =================

    private fun updateLogin(currentUsername: String) {
        if (currentUsername.isEmpty()) {
            Toast.makeText(this, "Login session nahi mila, dobara login karein", Toast.LENGTH_SHORT).show()
            return
        }
        val newUsername = newUsernameField.text.toString().trim()
        val newPassword = newPasswordField.text.toString()

        lifecycleScope.launch {
            val db = PosDatabase.get(this@SettingsActivity)
            val user = db.userDao().find(currentUsername)
            if (user == null) {
                Toast.makeText(this@SettingsActivity, "User nahi mila", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val finalUsername = if (newUsername.isNotEmpty()) newUsername else user.username
            val finalPassword = if (newPassword.isNotEmpty()) newPassword else user.passwordHash

            db.userDao().upsert(
                User(
                    username = finalUsername,
                    displayName = user.displayName,
                    role = user.role,
                    passwordHash = finalPassword,
                    active = true
                )
            )

            if (finalUsername != currentUsername) {
                db.userDao().delete(currentUsername)
                getSharedPreferences("session", MODE_PRIVATE).edit()
                    .putString("username", finalUsername).apply()
                currentUsernameField.setText(finalUsername)
            }

            Toast.makeText(this@SettingsActivity, "Login updated", Toast.LENGTH_SHORT).show()
            newUsernameField.text.clear()
            newPasswordField.text.clear()
        }
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(0xFFDDDDDD.toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 2
        ).apply { setMargins(0, 16, 0, 16) }
    }
}
