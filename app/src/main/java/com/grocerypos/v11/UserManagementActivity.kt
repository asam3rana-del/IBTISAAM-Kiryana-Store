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
import com.grocerypos.v11.PasswordHasher
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.SyncQueueHelper
import com.grocerypos.v11.User
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class UserManagementActivity : AppCompatActivity() {

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
                // FIX (Phase 4 - Security): password typed here is now hashed via
                // PasswordHasher before being saved (and before being pushed to the
                // sync queue) — previously stored and synced as plain text.
                passwordHash = PasswordHasher.hash(password),
                active = true,
                phone = phone
            )
            val db = PosDatabase.get(this@UserManagementActivity)
            db.userDao().upsert(user)

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
            orientation = LinearLayout.VERTICAL
            background = strokedBg(border, "#FAFAFF", 14)
            setPadding(16, 14, 16, 14)

            val topRow = LinearLayout(this@UserManagementActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            topRow.addView(TextView(this@UserManagementActivity).apply {
                text = user.displayName.take(1).uppercase()
                textSize = 15f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                background = ovalBg(roleColor)
                // ADDED: dim the avatar when the user is inactive, so it's visually
                // obvious at a glance without having to read the badge text.
                alpha = if (user.active) 1f else 0.4f
                val px = (36 * resources.displayMetrics.density).toInt()
                width = px; height = px
            })
            topRow.addView(spacerH(14))

            val info = LinearLayout(this@UserManagementActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this@UserManagementActivity).apply {
                text = "${user.displayName}  ·  ${user.username}"
                textSize = 14f
                setTextColor(Color.parseColor(if (user.active) textDark else textGray))
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
            val badgeRow = LinearLayout(this@UserManagementActivity).apply { orientation = LinearLayout.HORIZONTAL }
            badgeRow.addView(roleBadge(user.role, roleColor))
            // ADDED: Active/Inactive status badge next to the role badge.
            badgeRow.addView(spacerH(6))
            badgeRow.addView(roleBadge(if (user.active) "Active" else "Inactive", if (user.active) green else textGray))
            info.addView(badgeRow)
            topRow.addView(info)
            addView(topRow)

            // ADDED: action row — Reset Password (any user) + Activate/Deactivate
            // toggle (any user except yourself, so you can never lock yourself out
            // by accident) + Delete (unchanged, still hidden for yourself).
            addView(spacer(10))
            val actionRow = LinearLayout(this@UserManagementActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val weighted = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            actionRow.addView(secondaryButton("🔑 Reset PW", blue) { openResetPasswordDialog(user) }, weighted)
            actionRow.addView(spacerH(8))
            if (user.username != myUsername) {
                actionRow.addView(
                    secondaryButton(
                        if (user.active) "⏸ Deactivate" else "▶ Activate",
                        if (user.active) amber else green
                    ) { toggleActive(user) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
                actionRow.addView(spacerH(8))
                actionRow.addView(
                    secondaryButton("🗑 DELETE", red) { confirmDelete(user) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
            addView(actionRow)
        }
    }

    // ADDED: lets an admin set a brand-new password for any user (including one who
    // forgot theirs) without needing to know the old one. Purely local — passwordHash
    // is deliberately never included in what gets synced (see SyncQueueHelper.userJson),
    // so this never enqueues a sync row; each device keeps its own password for a
    // given username, by design.
    private fun openResetPasswordDialog(user: User) {
        val newPasswordField = EditText(this).apply {
            hint = "New password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(28, 22, 28, 22)
        }
        AlertDialog.Builder(this)
            .setTitle("Reset Password — ${user.displayName}")
            .setMessage("${user.username} ke liye naya password set karein.")
            .setView(newPasswordField)
            .setPositiveButton("Reset") { _, _ ->
                val newPassword = newPasswordField.text.toString()
                if (newPassword.length < 4) {
                    Toast.makeText(this, "Password kam az kam 4 characters ka ho", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    val db = PosDatabase.get(this@UserManagementActivity)
                    db.userDao().upsert(user.copy(passwordHash = PasswordHasher.hash(newPassword)))
                    Toast.makeText(this@UserManagementActivity, "Password reset ho gaya", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ADDED: flips a user's active flag. Unlike password, `active` IS part of the
    // synced payload (see userJson), so this enqueues like any other user edit —
    // deactivating someone on one device correctly deactivates them everywhere once
    // synced.
    private fun toggleActive(user: User) {
        val turningOff = user.active
        val action = if (turningOff) "Deactivate" else "Activate"
        AlertDialog.Builder(this)
            .setTitle("$action ${user.displayName}?")
            .setMessage(
                if (turningOff) "${user.username} ab login nahi kar sakega jab tak dobara activate na kiya jaye."
                else "${user.username} ab dobara login kar sakega."
            )
            .setPositiveButton(action) { _, _ ->
                lifecycleScope.launch {
                    val db = PosDatabase.get(this@UserManagementActivity)
                    val updated = user.copy(active = !user.active)
                    db.userDao().upsert(updated)
                    SyncQueueHelper.enqueue(
                        db = db,
                        entityType = "user",
                        entityId = SyncQueueHelper.userEntityId(updated),
                        operation = "upsert",
                        payloadJson = SyncQueueHelper.userJson(updated)
                    )
                    SyncQueueHelper.trigger(this@UserManagementActivity)
                    Toast.makeText(
                        this@UserManagementActivity,
                        if (updated.active) "User activated" else "User deactivated",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun roleBadge(role: String, colorHex: String) = TextView(this).apply {
        text = role.replaceFirstChar { it.uppercase() }
        textSize = 10.5f
        setTextColor(Color.parseColor(colorHex))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(14, 4, 14, 4)
        background = GradientDrawable().apply {
            setColor(Color.parseColor(colorHex).let { Color.argb(28, Color.red(it), Color.green(it), Color.blue(it)) })
            cornerRadius = 20f
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = 6
        layoutParams = lp
    }

    private fun confirmDelete(user: User) {
        AlertDialog.Builder(this)
            .setTitle("Delete User?")
            .setMessage("${user.displayName} (${user.username}) ko delete karna hai?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    val db = PosDatabase.get(this@UserManagementActivity)
                    db.userDao().delete(user.username)

                    SyncQueueHelper.enqueue(
                        db = db,
                        entityType = "user",
                        entityId = SyncQueueHelper.userEntityId(user),
                        operation = "delete",
                        payloadJson = SyncQueueHelper.userJson(user)
                    )
                    SyncQueueHelper.trigger(this@UserManagementActivity)

                    Toast.makeText(this@UserManagementActivity, "User deleted", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sectionCard(icon: String, title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24, 22, 24, 22)
        background = strokedBg(border, cardBg, 18)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        applyElevation(this, 3f)
        addView(sectionLabel(icon, title))
        addView(spacer(4))
    }

    private fun sectionLabel(icon: String, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, 12)
        addView(TextView(this@UserManagementActivity).apply { text = "$icon  "; textSize = 15f })
        addView(TextView(this@UserManagementActivity).apply {
            text = label
            textSize = 14.5f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, Typeface.BOLD)
        })
    }

    private fun fieldBox(icon: String, field: EditText) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = strokedBg(border, "#FAFAFF", 12)
        setPadding(18, 4, 18, 4)
        addView(TextView(this@UserManagementActivity).apply { text = "$icon  "; textSize = 14f })
        field.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        addView(field)
    }

    private fun primaryButton(label: String, colorHex: String, colorDarkHex: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 14.5f
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor(colorHex), Color.parseColor(colorDarkHex))
        ).apply { cornerRadius = 16f }
        setPadding(0, 24, 0, 24)
        setOnClickListener { onClick() }
        applyElevation(this, 4f)
    }

    private fun secondaryButton(label: String, colorHex: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.parseColor(colorHex))
        textSize = 11.5f
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        background = strokedBg(colorHex, "#FFFFFF", 14)
        setPadding(20, 14, 20, 14)
        minWidth = 0
        minimumWidth = 0
        setOnClickListener { onClick() }
    }

    private fun circleIcon(label: String, colorHex: String, sizeDp: Int) = TextView(this).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        background = ovalBg(colorHex)
        val px = (sizeDp * resources.displayMetrics.density).toInt()
        width = px; height = px
    }

    private fun ovalBg(colorHex: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(colorHex))
    }

    private fun strokedBg(strokeHex: String, fillHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(fillHex))
        setStroke((1.4 * resources.displayMetrics.density).toInt(), Color.parseColor(strokeHex))
        cornerRadius = radius.toFloat()
    }

    private fun applyElevation(view: View, dp: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.elevation = dp * resources.displayMetrics.density
            view.outlineProvider = ViewOutlineProvider.BACKGROUND
        }
    }

    private fun spacer(heightDp: Int) = View(this).apply {
        val px = (heightDp * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px)
    }

    private fun spacerH(widthDp: Int) = View(this).apply {
        val px = (widthDp * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(px, 1)
    }
}
