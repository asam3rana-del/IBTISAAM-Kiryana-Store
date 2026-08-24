package com.grocerypos.v11.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.grocerypos.v11.AppSetting
import com.grocerypos.v11.MainActivity
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.User
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class LoginActivity : AppCompatActivity() {

    // ================= PREMIUM COLOR PALETTE =================
    private val gradientTop = "#1A237E"
    private val gradientBottom = "#3949AB"
    private val cardBg = "#FFFFFF"
    private val primary = "#4A3AFF"
    private val primaryDark = "#3527D6"
    private val green = "#1FA971"
    private val textDark = "#1A1A2E"
    private val textGray = "#8A8A9E"
    private val border = "#E7E5F3"

    private lateinit var loggedInUser: User

    private lateinit var u: EditText
    private lateinit var p: EditText
    private lateinit var btn: Button
    private lateinit var fingerprintBtn: Button
    private lateinit var hint: TextView

    // ---- Phone OTP login (Firebase Auth) ----
    private val auth by lazy { FirebaseAuth.getInstance() }
    private var storedVerificationId: String = ""
    private lateinit var otpSection: LinearLayout
    private lateinit var otpPhoneField: EditText
    private lateinit var otpCodeField: EditText
    private lateinit var otpCodeBox: LinearLayout
    private lateinit var sendOtpBtn: Button
    private lateinit var verifyOtpBtn: Button

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        // FIX: "No Password" login method now short-circuits straight into the app —
        // this has to happen before any UI is built, otherwise the login card would
        // flash on screen for a moment even when no login is required.
        lifecycleScope.launch {
            val db = PosDatabase.get(this@LoginActivity)
            val method = db.appSettingDao().get("login_method")?.value ?: "password"
            if (method == "none") {
                goToMain()
                return@launch
            }
            buildUi()
            // First-time setup: create a default admin login if none exists yet
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
    }

    private fun goToMain() {
        startActivity(Intent(this@LoginActivity, MainActivity::class.java))
        finish()
    }

    private fun buildUi() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(36, 60, 36, 60)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(gradientTop), Color.parseColor(gradientBottom))
            )
        }

        // ================= LOGO / APP TITLE =================
        outer.addView(circleIcon("🏪", "#FFFFFF", "#3949AB", 84))
        outer.addView(spacer(20))
        outer.addView(TextView(this).apply {
            text = "IBTISAAM Kiryana Store"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        outer.addView(TextView(this).apply {
            text = "Point of Sale"
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#C5CAE9"))
            setPadding(0, 6, 0, 0)
        })
        outer.addView(spacer(34))

        // ================= LOGIN CARD =================
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 34, 30, 30)
            background = roundedBg(cardBg, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            applyElevation(this, 14f)
        }

        card.addView(TextView(this).apply {
            text = "Welcome Back"
            textSize = 17f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = "Login karke jaari rakhein"
            textSize = 12f
            setTextColor(Color.parseColor(textGray))
            setPadding(0, 4, 0, 22)
        })

        u = EditText(this).apply {
            hint = "Username"
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
        }
        card.addView(fieldBox("👤", u))
        card.addView(spacer(14))

        p = EditText(this).apply {
            hint = "Password"
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        card.addView(passwordFieldBox(p))
        card.addView(spacer(22))

        btn = Button(this).apply {
            text = "🔓  LOGIN"
            setTextColor(Color.WHITE)
            textSize = 15f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor(primary), Color.parseColor(primaryDark))
            ).apply { cornerRadius = 16f }
            setPadding(0, 28, 0, 28)
            applyElevation(this, 6f)
        }
        card.addView(btn)
        card.addView(spacer(14))

        // "SHOW" fallback button — kept visible (not GONE) whenever fingerprint mode is
        // active so the person always has a manual way to re-open the prompt if the
        // auto-triggered one is dismissed, cancelled, or fails.
        fingerprintBtn = Button(this).apply {
            text = "🔓  UNLOCK WITH FINGERPRINT"
            setTextColor(Color.parseColor(green))
            textSize = 13.5f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            background = strokedBg(green, "#FFFFFF", 16)
            setPadding(0, 24, 0, 24)
            visibility = View.GONE
        }
        card.addView(fingerprintBtn)

        // ---- Phone OTP section (only visible when login_method == "otp") ----
        otpSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        otpPhoneField = EditText(this).apply {
            hint = "+92XXXXXXXXXX"
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
            inputType = InputType.TYPE_CLASS_PHONE
        }
        otpSection.addView(fieldBox("📱", otpPhoneField))
        otpSection.addView(spacer(14))

        sendOtpBtn = Button(this).apply {
            text = "📩  SEND OTP"
            setTextColor(Color.WHITE)
            textSize = 14f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor(primary), Color.parseColor(primaryDark))
            ).apply { cornerRadius = 16f }
            setPadding(0, 24, 0, 24)
        }
        otpSection.addView(sendOtpBtn)
        otpSection.addView(spacer(14))

        otpCodeField = EditText(this).apply {
            hint = "6-digit code"
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        otpCodeBox = fieldBox("🔢", otpCodeField).apply { visibility = View.GONE }
        otpSection.addView(otpCodeBox)
        otpSection.addView(spacer(14))

        verifyOtpBtn = Button(this).apply {
            text = "✅  VERIFY & LOGIN"
            setTextColor(Color.WHITE)
            textSize = 14f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(green)); cornerRadius = 16f
            }
            setPadding(0, 24, 0, 24)
            visibility = View.GONE
        }
        otpSection.addView(verifyOtpBtn)

        card.addView(otpSection)

        hint = TextView(this).apply {
            textSize = 11.5f
            setTextColor(Color.parseColor(textGray))
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 0)
        }
        card.addView(hint)

        outer.addView(card)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(gradientTop))
            addView(outer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        })

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
            lifecycleScope.launch { triggerFingerprintUnlock() }
        }

        sendOtpBtn.setOnClickListener {
            val phone = otpPhoneField.text.toString().trim()
            if (phone.isEmpty()) {
                Toast.makeText(this, "Phone number dalein", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val options = PhoneAuthOptions.newBuilder(auth)
                .setPhoneNumber(phone)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(this)
                .setCallbacks(object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                    override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                        verifyAndLogin(credential, phone)
                    }

                    override fun onVerificationFailed(e: FirebaseException) {
                        Toast.makeText(this@LoginActivity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }

                    override fun onCodeSent(verificationId: String, token: PhoneAuthProvider.ForceResendingToken) {
                        storedVerificationId = verificationId
                        otpCodeBox.visibility = View.VISIBLE
                        verifyOtpBtn.visibility = View.VISIBLE
                        Toast.makeText(this@LoginActivity, "OTP bheja gaya", Toast.LENGTH_SHORT).show()
                    }
                })
                .build()
            PhoneAuthProvider.verifyPhoneNumber(options)
        }

        verifyOtpBtn.setOnClickListener {
            val code = otpCodeField.text.toString().trim()
            val phone = otpPhoneField.text.toString().trim()
            if (code.length == 6) {
                val credential = PhoneAuthProvider.getCredential(storedVerificationId, code)
                verifyAndLogin(credential, phone)
            } else {
                Toast.makeText(this, "6-digit code dalein", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Firebase se OTP verify hone ke baad, us phone number se local User record dhoondh kar login complete karta hai. */
    private fun verifyAndLogin(credential: PhoneAuthCredential, phone: String) {
        auth.signInWithCredential(credential).addOnCompleteListener(this) { task ->
            if (task.isSuccessful) {
                lifecycleScope.launch {
                    val db = PosDatabase.get(this@LoginActivity)
                    val user = db.userDao().findByPhone(phone)
                    if (user != null) {
                        loggedInUser = user
                        db.appSettingDao().set(AppSetting("last_username", user.username))
                        completeLogin()
                    } else {
                        Toast.makeText(
                            this@LoginActivity,
                            "Ye number kisi staff se link nahi hai. Settings > Manage Users mein add karwayein.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } else {
                Toast.makeText(this, "OTP galat hai", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Settings mein select kiye gaye login_method ke hisab se screen ke elements dikhata/chupata hai. */
    private suspend fun applyLoginMethod(db: com.grocerypos.v11.PosDatabase) {
        val method = db.appSettingDao().get("login_method")?.value ?: "password"
        val lastUsername = db.appSettingDao().get("last_username")?.value

        // Default: sab hide, jo method active hai wahi visible karega
        u.visibility = View.GONE
        p.visibility = View.GONE
        btn.visibility = View.GONE
        fingerprintBtn.visibility = View.GONE
        otpSection.visibility = View.GONE

        when (method) {
            "fingerprint" -> {
                if (lastUsername.isNullOrEmpty()) {
                    // Kabhi login nahi hua — pehli dafa password lena zaroori hai
                    u.visibility = View.VISIBLE
                    p.visibility = View.VISIBLE
                    btn.visibility = View.VISIBLE
                } else {
                    fingerprintBtn.visibility = View.VISIBLE
                    // FIX: auto-detect — the biometric prompt now opens by itself as soon as
                    // this screen appears, instead of waiting for a manual button tap.
                    triggerFingerprintUnlock()
                }
            }
            "both" -> {
                u.visibility = View.VISIBLE
                p.visibility = View.VISIBLE
                btn.visibility = View.VISIBLE
            }
            "otp" -> {
                otpSection.visibility = View.VISIBLE
            }
            else -> { // "password"
                u.visibility = View.VISIBLE
                p.visibility = View.VISIBLE
                btn.visibility = View.VISIBLE
            }
        }
    }

    /** Looks up the last logged-in user and opens the biometric prompt for them. */
    private suspend fun triggerFingerprintUnlock() {
        val db = PosDatabase.get(this@LoginActivity)
        val lastUsername = db.appSettingDao().get("last_username")?.value
        if (lastUsername.isNullOrEmpty()) {
            Toast.makeText(this@LoginActivity, "Pehli dafa password se login karein", Toast.LENGTH_LONG).show()
            return
        }
        val user = db.userDao().find(lastUsername)
        if (user == null) {
            Toast.makeText(this@LoginActivity, "User nahi mila, password se login karein", Toast.LENGTH_LONG).show()
            return
        }
        loggedInUser = user
        showBiometricPrompt()
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
                    // Prompt was cancelled/failed to open — the manual fallback button
                    // stays visible so the person can retry without getting stuck.
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
        goToMain()
    }

    // ================= UI HELPERS =================
    private fun fieldBox(icon: String, field: EditText) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = strokedBg(border, "#FAFAFF", 12)
        setPadding(18, 4, 18, 4)
        addView(TextView(this@LoginActivity).apply { text = "$icon  "; textSize = 14f })
        (field.parent as? ViewGroup)?.removeView(field)
        field.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        addView(field)
    }

    /** Same as fieldBox but adds a tappable eye icon to show/hide the password text. */
    private fun passwordFieldBox(field: EditText) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = strokedBg(border, "#FAFAFF", 12)
        setPadding(18, 4, 18, 4)
        addView(TextView(this@LoginActivity).apply { text = "🔒  "; textSize = 14f })
        (field.parent as? ViewGroup)?.removeView(field)
        field.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        addView(field)

        val toggle = TextView(this@LoginActivity).apply {
            text = "👁"
            textSize = 16f
            setPadding(16, 0, 8, 0)
            var visible = false
            setOnClickListener {
                visible = !visible
                field.inputType = if (visible)
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                field.setSelection(field.text.length)
                text = if (visible) "🙈" else "👁"
            }
        }
        addView(toggle)
    }

    private fun circleIcon(label: String, colorHex: String, textColorHex: String, sizeDp: Int) = FrameLayout(this).apply {
        val px = (sizeDp * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(px, px).apply { gravity = Gravity.CENTER_HORIZONTAL }
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(colorHex))
        }
        applyElevation(this, 10f)
        addView(TextView(this@LoginActivity).apply {
            text = label
            textSize = 32f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        })
    }

    private fun roundedBg(colorHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(colorHex))
        cornerRadius = radius.toFloat()
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
}
