package com.grocerypos.v11.ui

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.User
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var currentUsernameField: EditText
    private lateinit var newUsernameField: EditText
    private lateinit var newPasswordField: EditText

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        l.addView(TextView(this).apply { text = "SETTINGS"; textSize = 24f; setPadding(0, 0, 0, 20) })

        listOf("Shop Name", "Phone", "Address", "Receipt Footer", "Currency", "Tax %").forEach {
            l.addView(EditText(this).apply { hint = it })
        }
        l.addView(Button(this).apply { text = "SAVE SETTINGS" })

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

        setContentView(ScrollView(this).apply { addView(l) })
    }

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

            // Username badla ho to purana record hatayein aur session update karein
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
