package com.grocerypos.v11.ui

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.AppSetting
import com.grocerypos.v11.MainActivity
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.User
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val l = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 30)
        }
        val u = EditText(this).apply { hint = "Username" }
        val p = EditText(this).apply { hint = "Password"; inputType = 0x81 }
        val btn = Button(this).apply { text = "LOGIN" }
        val hint = TextView(this).apply {
            textSize = 12f
            setPadding(0, 20, 0, 0)
        }

        l.addView(TextView(this).apply { text = "Grocery POS V13"; textSize = 26f; setPadding(0,0,0,24) })
        l.addView(u)
        l.addView(p)
        l.addView(btn)
        l.addView(hint)
        setContentView(l)

        // ---- First-time setup: create a default admin login if none exists yet ----
        lifecycleScope.launch {
            val db = PosDatabase.get(this@LoginActivity)
            val seeded = db.appSettingDao().get("admin_seeded")
            if (seeded == null) {
                db.userDao().upsert(
                    User(username = "admin", displayName = "Admin", role = "admin", passwordHash = "admin123", active = true)
                )
                db.appSettingDao().set(AppSetting("admin_seeded", "1"))
                hint.text = "Pehli baar? Username: admin   Password: admin123\n(Settings mein jaake baad mein badal sakte hain)"
            }
        }

        btn.setOnClickListener {
            lifecycleScope.launch {
                val db = PosDatabase.get(this@LoginActivity)
                val user = db.userDao().find(u.text.toString().trim())
                if (user != null && user.passwordHash == p.text.toString()) {
                    getSharedPreferences("session", MODE_PRIVATE)
                        .edit()
                        .putString("username", user.username)
                        .putString("role", user.role)
                        .apply()
                    Toast.makeText(this@LoginActivity, "Welcome ${user.displayName}", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                } else {
                    Toast.makeText(this@LoginActivity, "Invalid login", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
