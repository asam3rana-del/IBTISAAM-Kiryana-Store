package com.grocerypos.v11.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.SyncQueueHelper
import com.grocerypos.v11.User
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class UserManagementActivity : AppCompatActivity() {

    // ================= PREMIUM COLOR PALETTE (matches Sale / Product / Purchase / Settings) =================
    private val bg = "#F3F2FA"
    private val cardBg = "#FFFFFF"
    private val primary = "#4A3AFF"
    private val primaryDark = "#3527D6"
    private val green = "#1FA971"
    private val red = "#E5484D"
    private val redDark = "#C93A3E"
    private val blue = "#2F6FED"
    private val amber = "#F5A524"
    private val textDark = "#1A1A2E"
    private val textGray = "#8A8A9E"
    private val border = "#E7E5F3"

    private lateinit var listContainer: LinearLayout
    private lateinit var usernameField: EditText
    private lateinit var displayNameField: EditText
    private lateinit var phoneField: EditText
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
            setPadding(24, 48, 24, 24)
            setBackgroundColor(Color.parseColor(bg))
        }

        // ================= HEADER =================
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(26, 22, 26, 22)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(primary), Color.parseColor(primaryDark))
            ).apply { cornerRadius = 22f }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 20) }
            applyElevation(this, 10f)
        }
        header.addView(circleIcon("👥", "#5C4DFF", 42))
        header.addView(spacerH(16))
        val headerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerCol.addView(TextView(this).apply {
            text = "Manage Users"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        headerCol.addView(TextView(this).apply {
            text = "Add, update, or remove staff logins"
            textSize = 11.5f
            setTextColor(Color.parseColor("#D8D3FF"))
            setPadding(0, 4, 0, 0)
        })
        header.addView(headerCol)
        root.addView(header)

        // ================= ADD / UPDATE USER FORM =================
        val formCard = sectionCard("➕", "Add / Update User")

        usernameField = EditText(this).apply {
            hint = "Username"
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
        }
        displayNameField = EditText(this).apply {
            hint = "Display Name"
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
        }
        phoneField = EditText(this).apply {
            hint = "Phone (+92XXXXXXXXXX) — for OTP login"
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
            inputType = InputType.TYPE_CLASS_PHONE
        }
        passwordField = EditText(this).apply {
            hint = "Password"
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
            inputType = 0x81
        }

        formCard.addView(fieldBox("👤", usernameField))
        formCard.addView(spacer(10))
        formCard.addView(fieldBox("🪪", displayNameField))
        formCard.addView(spacer(10))
        formCard.addView(fieldBox("📱", phoneField))
        formCard.addView(spacer(10))
        formCard.addView(fieldBox("🔒", passwordField))
        formCard.addView(spacer(10))

        formCard.addView(TextView(this).apply {
            text = "Role"
            textSize = 12f
            setTextColor(Color.parseColor(textGray))
            setPadding(4, 0, 0, 6)
        })
        roleSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@UserManagementActivity, android.R.layout.simple_spinner_dropdown_item, roles)
            background = strokedBg(border, "#FAFAFF", 12)
            setPadding(18, 10, 18, 10)
        }
        formCard.addView(roleSpinner)
        formCard.addView(spacer(14))
        formCard.addView(primaryButton("✓  ADD / UPDATE USER", primary, primaryDark) { saveUser() })
        root.addView(formCard)
        root.addView(spacer(18))

        // ================= USER LIST =================
        val listCard = sectionCard("📋", "All Users")
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listCard.addView(listContainer)
        root.addView(listCard)
        root.addView(spacer(30))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        })

        loadUsers(myUsername)
    }

    private fun saveUser() {
        val username = usernameField.text.toString().trim()
        val displayName = displayNameField.text.toString().trim()
        val phone = phoneField.text.toString().trim()
        val password = passwordField.text.toString()
        val role = roles[roleSpinner.selectedItemPosition]

        if (username.isEmpty() || displayName.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Sab fields zaroori hain", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val user = User(
                username = username,
                displayName = displayName,
                role = role,
                passwordHash = password,
                active = true,
                phone = phone
            )
            val db = PosDatabase.get(this@UserManagementActivity)
            db.userDao().upsert(user)

            // ---- Queue this change so it pushes up to Firebase ----
            SyncQueueHelper.enqueue(
                db = db,
                entityType = "user",
                entityId = SyncQueueHelper.userEntityId(user),
                operation = "upsert",
                payloadJson = SyncQueueHelper.userJson(user)
            )
            SyncQueueHelper.trigger(this@UserManagementActivity)

            Toast.makeText(this@UserManagementActivity, "User saved", Toast.LENGTH_SHORT).show()
            usernameField.text.clear()
            displayNameField.text.clear()
            phoneField.text.clear()
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
                        setTextColor(Color.parseColor(textGray))
                        setPadding(4, 8, 4, 8)
                    })
                }
                users.forEachIndexed { index, user ->
                    listContainer.addView(userRow(user, myUsername))
                    if (index != users.lastIndex) listContainer.addView(spacer(10))
                }
            }
        }
    }

    private fun userRow(user: User, myUsername: String): LinearLayout {
        val roleColor = when (user.role) {
            "admin" -> primary
            "manager" -> blue
            else -> amber
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = strokedBg(border, "#FAFAFF", 14)
            setPadding(16, 14, 16, 14)

            addView(TextView(this@UserManagementActivity).apply {
                text = user.displayName.take(1).uppercase()
                textSize = 15f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                background = ovalBg(roleColor)
                val px = (36 * resources.displayMetrics.density).toInt()
                width = px; height = px
            })
            addView(spacerH(14))

            val info = LinearLayout(this@UserManagementActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this@UserManagementActivity).apply {
                text = "${user.displayName}  ·  ${user.username}"
                textSize = 14f
                setTextColor(Color.parseColor(textDark))
                setTypeface(typeface, Typeface.BOLD)
            })
            if (user.phone.isNotBlank()) {
                info.addView(TextView(this@UserManagementActivity).apply {
                    text = "📱 ${user.phone}"
                    textSize = 11.5f
                    setTextColor(Color.parseColor(textGray))
                    setPadding(0, 2, 0, 0)
                })
            }
            info.addView(roleBadge(user.role, roleColor))
            addView(info)

            if (user.username != myUsername) {
                addView(secondaryButton("🗑  DELETE", red) { confirmDelete(user) })
            }
        }
    }

    private fun roleBadge(role: String, colorHex: String) = TextView(this).apply {
        text = role.replaceFirstChar { it.uppercase() }
        textSize = 10.5f
        set
