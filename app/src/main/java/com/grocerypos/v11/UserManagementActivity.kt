package com.grocerypos.v11.ui

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.User
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class UserManagementActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var usernameField: EditText
    private lateinit var displayNameField: EditText
    private lateinit var passwordField: EditText
    private lateinit var roleSpinner: Spinner
    private val roles = listOf("admin", "manager", "cashier")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        // ---- Only admin can access this screen ----
        val session = getSharedPreferences("session", MODE_PRIVATE)
        val myRole = session.getString("role", "cashier") ?: "cashier"
        val myUsername = session.getString("username", "") ?: ""
        if (myRole != "admin") {
            Toast.makeText(this, "Sirf Admin is screen ko access kar sakta hai", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 32)
        }

        root.addView(TextView(this).apply { text = "Manage Users"; textSize = 22f; setPadding(0, 0, 0, 20) })

        // ---- Add new user form ----
        usernameField = EditText(this).apply { hint = "Username" }
        displayNameField = EditText(this).apply { hint = "Display Name" }
        passwordField = EditText(this).apply { hint = "Password"; inputType = 0x81 }
        roleSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@UserManagementActivity, android.R.layout.simple_spinner_dropdown_item, roles)
        }

        root.addView(usernameField)
        root.addView(displayNameField)
        root.addView(passwordField)
        root.addView(TextView(this).apply { text = "Role:"; setPadding(0, 12, 0, 4) })
        root.addView(roleSpinner)
        root.addView(Button(this).apply {
            text = "ADD / UPDATE USER"
            setOnClickListener { saveUser() }
        })

        root.addView(divider())
        root.addView(TextView(this).apply {
            text = "All Users"
            textSize = 15f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, 12, 0, 8)
        })

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        setContentView(ScrollView(this).apply { addView(root) })

        loadUsers(myUsername)
    }

    private fun saveUser() {
        val username = usernameField.text.toString().trim()
        val displayName = displayNameField.text.toString().trim()
        val password = passwordField.text.toString()
        val role = roles[roleSpinner.selectedItemPosition]

        if (username.isEmpty() || displayName.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Sab fields zaroori hain", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            PosDatabase.get(this@UserManagementActivity).userDao().upsert(
                User(username = username, displayName = displayName, role = role, passwordHash = password, active = true)
            )
            Toast.makeText(this@UserManagementActivity, "User saved", Toast.LENGTH_SHORT).show()
            usernameField.text.clear()
            displayNameField.text.clear()
            passwordField.text.clear()
        }
    }

    private fun loadUsers(myUsername: String) {
        lifecycleScope.launch {
            PosDatabase.get(this@UserManagementActivity).userDao().all().collectLatest { users ->
                listContainer.removeAllViews()
                if (users.isEmpty()) {
                    listContainer.addView(TextView(this@UserManagementActivity).apply {
                        text = "Koi user nahi hai"
                        setTextColor(android.graphics.Color.GRAY)
                    })
                }
                for (user in users) {
                    listContainer.addView(userRow(user, myUsername))
                }
            }
        }
    }

    private fun userRow(user: User, myUsername: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 14, 12, 14)

            val info = LinearLayout(this@UserManagementActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this@UserManagementActivity).apply {
                text = "${user.displayName}  (${user.username})"
                textSize = 15f
            })
            info.addView(TextView(this@UserManagementActivity).apply {
                text = user.role.replaceFirstChar { it.uppercase() }
                textSize = 12f
                setTextColor(android.graphics.Color.GRAY)
            })
            addView(info)

            if (user.username != myUsername) {
                addView(Button(this@UserManagementActivity).apply {
                    text = "Delete"
                    setOnClickListener { confirmDelete(user) }
                })
            }
            addView(divider())
        }
    }

    private fun confirmDelete(user: User) {
        AlertDialog.Builder(this)
            .setTitle("Delete User?")
            .setMessage("${user.displayName} (${user.username}) ko delete karna hai?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    PosDatabase.get(this@UserManagementActivity).userDao().delete(user.username)
                    Toast.makeText(this@UserManagementActivity, "User deleted", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun divider() = android.view.View(this).apply {
        setBackgroundColor(0xFFEEEEEE.toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
    }
}
