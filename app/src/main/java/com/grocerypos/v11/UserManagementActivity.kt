package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.User
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class UserManagementActivity : AppCompatActivity() {

    private val tealStart = "#14B8A6"
    private val bg = "#F0FDFA"
    private val textDark = "#0F172A"
    private val border = "#CCFBF1"
    private val lightTeal = "#F0FDFA"

    private lateinit var usernameField: EditText
    private lateinit var passwordField: EditText
    private lateinit var nameField: EditText
    private lateinit var roleField: TextView
    private lateinit var listContainer: LinearLayout
    private var selectedRole = "Admin"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor(bg)) }
        val header = LinearLayout(this).apply {
            setPadding(22,26,22,26)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(tealStart), Color.parseColor("#0F766E"))).apply { cornerRadii = floatArrayOf(0f,0f,0f,0f,0f,0f,32f,32f) }
        }
        header.addView(TextView(this).apply { text = "👥 User Management - Ultra"; textSize = 20f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
        outer.addView(header)

        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(-1,0,1f) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16,18,16,24) }

        val card = ultraCard("👤", "Add User", "#6366F1", "#8B5CF6")
        usernameField = ultraInput("👤 Username")
        passwordField = ultraInput("🔑 Password")
        nameField = ultraInput("📝 Display Name")
        roleField = TextView(this).apply {
            text = "🎭 Role: Admin ▼"; textSize = 13.5f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD)
            setPadding(18,16,18,16)
            background = GradientDrawable().apply { setColor(Color.parseColor(lightTeal)); cornerRadius = 14f; setStroke(2, Color.parseColor(border)) }
            setOnClickListener { pickRole() }
        }
        card.addView(usernameField); card.addView(spacer(10)); card.addView(passwordField); card.addView(spacer(10))
        card.addView(nameField); card.addView(spacer(10)); card.addView(roleField)
        root.addView(card); root.addView(spacer(16))

        val saveBtn = TextView(this).apply {
            text = "✨ SAVE USER - ULTRA"; textSize = 15f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER; setPadding(0,18,0,18)
            background = GradientDrawable().apply { setColor(Color.parseColor(tealStart)); cornerRadius = 16f }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { elevation = 8f }
            setOnClickListener { saveUser() }
        }
        root.addView(saveBtn); root.addView(spacer(20))

        root.addView(TextView(this).apply { text = "📋 All Users"; textSize = 13f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD) })
        root.addView(spacer(8))
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        scroll.addView(root); outer.addView(scroll); setContentView(outer)
        load()
    }

    private fun ultraCard(icon: String, title: String, c1: String, c2: String): LinearLayout {
        val outer = LinearLayout(this).apply { setPadding(3,3,3,3); background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(c1), Color.parseColor(c2))).apply { cornerRadius = 22f } }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18,16,18,18); background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 19f } }
        inner.addView(TextView(this).apply { text = "$icon $title"; textSize = 11f; setTextColor(Color.parseColor("#64748B")); setTypeface(typeface, Typeface.BOLD) })
        outer.addView(inner); return inner
    }
    private fun ultraInput(h: String) = EditText(this).apply {
        hint = h; textSize = 13.5f; setPadding(18,16,18,16)
        background = GradientDrawable().apply { setColor(Color.parseColor(lightTeal)); cornerRadius = 14f; setStroke(2, Color.parseColor(border)) }
    }
    private fun spacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, (h*resources.displayMetrics.density).toInt()) }
    private fun pickRole() {
        val roles = arrayOf("Admin", "Cashier", "Manager")
        AlertDialog.Builder(this).setTitle("🎭 Select Role").setItems(roles) { _, i ->
            selectedRole = roles[i]; roleField.text = "🎭 Role: $selectedRole ▼"
        }.show()
    }
    private fun saveUser() {
        val u = usernameField.text.toString().trim()
        val p = passwordField.text.toString().trim()
        val n = nameField.text.toString().trim()
        if (u.isEmpty() || p.isEmpty()) { Toast.makeText(this, "Username/Password required", Toast.LENGTH_SHORT).show(); return }
        lifecycleScope.launch {
            val db = PosDatabase.get(this@UserManagementActivity)
            // FIXED: passwordHash -> password, active removed, role correct
            val user = User(username = u, password = p, displayName = n.ifEmpty { u }, role = selectedRole)
            db.userDao().upsert(user)
            Toast.makeText(this@UserManagementActivity, "✅ User Saved: $u", Toast.LENGTH_SHORT).show()
            usernameField.text.clear(); passwordField.text.clear(); nameField.text.clear()
            load()
        }
    }
    private fun load() {
        lifecycleScope.launch {
            val list = PosDatabase.get(this@UserManagementActivity).userDao().all().first()
            listContainer.removeAllViews()
            for (user in list) {
                val row = LinearLayout(this@UserManagementActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(16,14,16,14)
                    background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 14f; setStroke(1, Color.parseColor(border)) }
                    layoutParams = LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,0,0,10) }
                }
                row.addView(TextView(this@UserManagementActivity).apply {
                    text = "👤 ${user.displayName} | ${user.username} | ${user.role}"; textSize = 12f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0,-2,1f)
                })
                row.addView(TextView(this@UserManagementActivity).apply {
                    text = "🗑️"; textSize = 14f; gravity = Gravity.CENTER
                    setPadding(12,8,12,8)
                    background = GradientDrawable().apply { setColor(Color.parseColor("#FEE2E2")); cornerRadius = 8f }
                    // FIXED: delete -> deleteById
                    setOnClickListener {
                        lifecycleScope.launch {
                            PosDatabase.get(this@UserManagementActivity).userDao().deleteById(user.id)
                            load()
                        }
                    }
                })
                listContainer.addView(row)
            }
        }
    }
}
