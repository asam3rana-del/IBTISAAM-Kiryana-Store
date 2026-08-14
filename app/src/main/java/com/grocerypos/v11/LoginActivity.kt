package com.grocerypos.v11.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
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

    private lateinit var u: EditText
    private lateinit var p: EditText
    private lateinit var btn: Button
    private lateinit var fingerprintBtn: Button
    private lateinit var hint: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val l = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 30)
        }
        u = EditText(this).apply { hint = "Username" }
        p = EditText(this).apply { hint = "Password"; inputType = 0x81 }
        btn = Button(this).apply { text = "LOGIN" }
        fingerprintBtn = Button(this).apply {
            text = "🔓  UNLOCK WITH FINGERPRINT"
            visibility = View.GONE
        }
        hint = TextView(this).apply {
            textSize = 12f
            setPadding(0, 20, 0, 0)
        }

        l.addView(TextView(this).apply { text = "Grocery POS V13"; textSize = 26f; setPadding(0,0,0,24) })
        l.addView(u)
        l.addView(p)
        l.addView(btn)
        l.addView(fingerprintBtn)
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
            applyLoginMethod(db)
        }

        btn.setOnClickListener {
            lifecycleScope.launch {
                val db = PosDatabase.get(this@LoginActivity)
                val user = db.userDao().find(u.text.toString().trim())
                if (user != null && user.passwordHash == p.text.toString()) {
                    loggedInUser = user
                    db.appSettingDao().set(AppSetting("last_username", user.username))

                    val method = db.appSettingDao().get("login_method")?.value ?: "password"
                    if (method == "both") {
                        requireFingerprintThenProceed()
                    } else {
                        completeLogin()
                    }
                } else {
                    Toast.makeText(this@LoginActivity, "Invalid login", Toast.LENGTH_SHORT).show()
                }
            }
        }

        fingerprintBtn.setOnClickListener {
            lifecycleScope.launch {
                val db = PosDatabase.get(this@LoginActivity)
                val lastUsername = db.appSettingDao().get("last_username")?.value
                if (lastUsername.isNullOrEmpty()) {
                    Toast.makeText(this@LoginActivity, "Pehli dafa password se login karein", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val user = db.userDao().find(lastUsername)
                if (user == null) {
                    Toast.makeText(this@LoginActivity, "User nahi mila, password se login karein", Toast.LENGTH_LONG).show()
                    return@launch
                }
                loggedInUser = user
                showBiometricPrompt()
            }
        }
    }

    /** Settings mein select kiye gaye login_method ke hisab se screen ke elements dikhata/chupata hai. */
    private suspend fun applyLoginMethod(db: com.grocerypos.v11.PosDatabase) {
        val method = db.appSettingDao().get("login_method")?.value ?: "password"
        val lastUsername = db.appSettingDao().get("last_username")?.value

        when (method) {
            "fingerprint" -> {
                if (lastUsername.isNullOrEmpty()) {
                    // Kabhi login nahi hua — pehli dafa password lena zaroori hai
                    u.visibility = View.VISIBLE
                    p.visibility = View.VISIBLE
                    btn.visibility = View.VISIBLE
                    fingerprintBtn.visibility = View.GONE
                } else {
                    u.visibility = View.GONE
                    p.visibility = View.GONE
                    btn.visibility = View.GONE
                    fingerprintBtn.visibility = View.VISIBLE
                }
            }
            "both" -> {
                u.visibility = View.VISIBLE
                p.visibility = View.VISIBLE
                btn.visibility = View.VISIBLE
                fingerprintBtn.visibility = View.GONE
            }
            else -> { // "password"
                u.visibility = View.VISIBLE
                p.visibility = View.VISIBLE
                btn.visibility = View.VISIBLE
                fingerprintBtn.visibility = View.GONE
            }
        }
    }

    /** "Both" mode mein password ke baad fingerprint bhi verify karta hai. */
    private fun requireFingerprintThenProceed() {
        val biometricManager = BiometricManager.from(this)
        val canAuthenticate = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)

        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, "Fingerprint set nahi hai is device par, password se login ho raha hai", Toast.LENGTH_SHORT).show()
            completeLogin()
            return
        }
        showBiometricPrompt()
    }

    private fun showBiometricPrompt() {
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
