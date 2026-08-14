package com.grocerypos.v11.ui

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.AppSetting
import com.grocerypos.v11.MainActivity
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.User
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var loggedInUser: User

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
                    loggedInUser = user
                    // Password sahi hai — ab fingerprint mangenge (extra security layer)
                    requireFingerprintThenProceed()
                } else {
                    Toast.makeText(this@LoginActivity, "Invalid login", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Password ke baad fingerprint verify karta hai; agar device par fingerprint set hi nahi hai to seedha login continue kar deta hai. */
    private fun requireFingerprintThenProceed() {
        val biometricManager = BiometricManager.from(this)
        val canAuthenticate = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)

        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            // Is device par fingerprint hardware/enrollment nahi hai — normal login se aage badhein
            Toast.makeText(this, "Fingerprint set nahi hai is device par, password se login ho raha hai", Toast.LENGTH_SHORT).show()
            completeLogin()
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(
            this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    completeLogin()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Toast.makeText(this@LoginActivity, "Fingerprint verify nahi hua: $errString", Toast.LENGTH_LONG).show()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(this@LoginActivity, "Fingerprint match nahi hua, dobara try karein", Toast.LENGTH_SHORT).show()
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Fingerprint Verify Karein")
            .setSubtitle("Login complete karne ke liye apni fingerprint dikhayein")
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private fun completeLogin() {
        getSharedPreferences("session", MODE_PRIVATE)
            .edit()
            .putString("username", loggedInUser.username)
            .putString("role", loggedInUser.role)
            .apply()
        Toast.makeText(this, "Welcome ${loggedInUser.displayName}", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this@LoginActivity, MainActivity::class.java))
        finish()
    }
}
