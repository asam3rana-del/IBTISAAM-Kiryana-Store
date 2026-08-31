package com.grocerypos.v11.ui

import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.AppSetting
import com.grocerypos.v11.PasswordHasher
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.R
import com.grocerypos.v11.SyncQueueHelper
import com.grocerypos.v11.User
import com.grocerypos.v11.util.BackupHelper
import com.grocerypos.v11.util.BackupPasswordStore
import com.grocerypos.v11.util.PrinterHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    // ================= PALETTE (matches Product/Purchase/Sale premium look) =================
    private val bg = "#F4F6F8"
    private val cardWhite = "#FFFFFF"
    private val navy = "#0B2545"
    private val navyLight = "#1C2C4F"
    private val teal = "#0F9B8E"
    private val red = "#E5484D"
    private val amber = "#F5A524"
    private val badgeRed = "#E53950"
    private val textDark = "#0B2545"
    private val textGray = "#7C8798"
    private val border = "#E3E8EE"

    private lateinit var currentUsernameField: EditText
    private lateinit var newUsernameField: EditText
    private lateinit var newPasswordField: EditText
    private lateinit var printerStatusText: TextView
    private lateinit var printerStatusDot: TextView
    private lateinit var loginMethodGroup: RadioGroup
    private lateinit var passwordOnlyRadio: RadioButton
    private lateinit var fingerprintOnlyRadio: RadioButton
    private lateinit var bothRadio: RadioButton
    private lateinit var noPasswordRadio: RadioButton
    private lateinit var otpRadio: RadioButton

    // Shop Information fields
    private lateinit var shopNameField: EditText
    private lateinit var phoneField: EditText
    private lateinit var addressField: EditText
    private lateinit var footerField: EditText
    private lateinit var currencyField: EditText
    private lateinit var taxField: EditText

    // Header shop name label (kept in sync with the Shop Information "Shop Name" field)
    private lateinit var shopNameHeaderText: TextView

    // NEW: Sync Now row — connected/offline status indicator
    private lateinit var syncRowDot: TextView
    private lateinit var syncRowStatusText: TextView

    private val BT_PERMISSION_REQUEST_CODE = 501

    // FIX (restore reliability): Android 11+ blocks normal file-manager apps from
    // browsing into Android/data/<package>/files, which is where Restore's list
    // reads backups from — so a backup copied there by hand often silently fails
    // to appear/restore. This launcher lets the user pick a .db file from ANYWHERE
    // (Downloads, a cloud app, etc.) via the system picker, which is exempt from
    // that restriction, and restores directly from it — no manual file-copying needed.
    private lateinit var importBackupLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(b: Bundle?) {
        setTheme(R.style.Theme_SettingsSheet)
        super.onCreate(b)

        importBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { confirmRestoreFromUri(it) }
        }

        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor("#66000000")))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(bg))
        }

        // ================= PREMIUM GRADIENT HEADER =================
        root.addView(buildHeader())

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 10)
        }

        // ---- Parties (real) ----
        list.addView(menuRow("👥", "Parties", showChevron = true) {
            startActivity(Intent(this@SettingsActivity, PartyDashboardActivity::class.java))
        })

        // ---- Items (real) ----
        list.addView(menuRow("📋", "Items") {
            startActivity(Intent(this@SettingsActivity, ItemsActivity::class.java))
        })

        // ---- Reports (real) ----
        list.addView(menuRow("📈", "Reports") {
            startActivity(Intent(this@SettingsActivity, ReportsActivity::class.java))
        })

        // ---- Sale (real) ----
        list.addView(menuRow("🧾", "Sale", showChevron = true) {
            startActivity(Intent(this@SettingsActivity, SaleActivity::class.java))
        })

        // ---- Purchase (real) ----
        list.addView(menuRow("🛒", "Purchase", showChevron = true) {
            startActivity(Intent(this@SettingsActivity, PurchaseActivity::class.java))
        })

        // ---- Expense (no screen yet — reflection fallback kept in case it's added later) ----
        list.addView(menuRow("💼", "Expense", trailingText = "+") {
            tryOpenActivity("com.grocerypos.v11.ui.ExpenseActivity", "Expense")
        })

        // ---- Cash & Bank (real) ----
        list.addView(menuRow("🏦", "Cash & Bank", showChevron = true) {
            startActivity(Intent(this@SettingsActivity, CashActivity::class.java))
        })

        // ---- Sync Now (now shows live Connected/Offline status) ----
        list.addView(buildSyncRow())

        // ADDED (multi-tenant support): lets this device be pointed at its own
        // Firebase project instead of always using whatever this build shipped with —
        // see CloudConfigStore.kt for why. Sync Now above stays disabled/no-op until
        // this is filled in (or this build already has a usable default baked in).
        list.addView(menuRow("☁️", "Cloud Sync Setup", showChevron = true) {
            openCloudSyncSetupDialog()
        })

        // ADDED (sync recoverability): shows the audit log SyncApi.kt now writes to —
        // mainly conflicts (two devices editing the same record while both offline) and
        // push failures, so if something looks wrong after a sync, there's a trail to
        // check instead of it being a silent mystery.
        list.addView(menuRow("🧾", "Sync History", showChevron = true) {
            openSyncHistoryDialog()
        })

        list.addView(spacer(10))

        // ---- Settings (expandable — holds all the real settings sections) ----
        val settingsContent = buildSettingsContent()
        settingsContent.visibility = View.GONE
        val settingsRow = expandableMenuRow("⚙️", "Settings", target = settingsContent)
        list.addView(settingsRow)
        list.addView(settingsContent)

        // ---- Backup/Restore (expandable) ----
        val backupContent = buildBackupContent()
        backupContent.visibility = View.GONE
        val backupRow = expandableMenuRow(
            "🗄️", "Backup/Restore",
            subtitle = "Auto backup not enabled.",
            target = backupContent
        )
        list.addView(backupRow)
        list.addView(backupContent)

        list.addView(spacer(10))

        // ---- Logout ----
        list.addView(menuRow("🚪", "Logout", textColorHex = red, iconBgHex = red) {
            doLogout()
        })

        list.addView(spacer(20))

        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(Color.parseColor(bg))
            addView(list)
        })

        setContentView(root)

        loadHeaderShopName()

        applyFloatingSheetLayout()
        window.decorView.post { applyFloatingSheetLayout() }
    }

    override fun onResume() {
        super.onResume()
        // Refresh the Connected/Offline indicator every time this sheet becomes visible
        // (e.g. user toggled Wi-Fi/mobile data while Settings was open in the background).
        if (::syncRowDot.isInitialized) refreshSyncStatus()
    }

    private fun comingSoon(label: String) {
        Toast.makeText(this, "$label — Coming Soon", Toast.LENGTH_SHORT).show()
    }

    /** Opens the given activity by class name if it exists in the app; falls back to a "Coming Soon" toast otherwise.
     *  Kept only for features that don't have a real screen yet (e.g. Expense) — anything confirmed to exist
     *  (Parties, Items, Reports, Sale, Purchase, Cash & Bank) is launched directly above instead. */
    private fun tryOpenActivity(activityClassName: String, label: String) {
        try {
            val clazz = Class.forName(activityClassName)
            startActivity(Intent(this, clazz))
        } catch (e: ClassNotFoundException) {
            comingSoon(label)
        }
    }

    private fun applyFloatingSheetLayout() {
        val screenWidth = resources.displayMetrics.widthPixels
        window.setGravity(Gravity.START)
        window.setLayout(
            (screenWidth * 0.80).toInt(),
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private fun doLogout() {
        getSharedPreferences("session", MODE_PRIVATE).edit().clear().apply()
        startActivity(Intent(this@SettingsActivity, LoginActivity::class.java))
        finish()
    }

    // ================= SYNC =================

    /** True if the device currently has an active network with internet capability.
     *  This is a connectivity check only (not a Firestore reachability check) — it tells
     *  the user whether the app *can* sync right now, matching how PurchaseActivity's
     *  header sync chip works. */
    private fun isNetworkConnected(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Updates the small dot + label under "Sync Now" to reflect current connectivity
     *  AND whether this device even has a cloud project configured — see
     *  CloudConfigStore.kt. Previously this only checked network connectivity, so a
     *  device with no cloud project at all still showed a reassuring green
     *  "Connected" dot even though Sync Now could never do anything. */
    private fun refreshSyncStatus() {
        val online = isNetworkConnected()
        val cloudConfigured = com.grocerypos.v11.CloudConfigStore.firebaseApp(this) != null
        when {
            !cloudConfigured -> {
                syncRowDot.setTextColor(Color.parseColor(amber))
                syncRowStatusText.text = "Not set up — tap Cloud Sync Setup"
            }
            online -> {
                syncRowDot.setTextColor(Color.parseColor(teal))
                syncRowStatusText.text = "Connected"
            }
            else -> {
                syncRowDot.setTextColor(Color.parseColor(red))
                syncRowStatusText.text = "Offline"
            }
        }
    }

    /** Builds the "Sync Now" row with a live Connected/Offline status line under the label,
     *  instead of the plain menuRow() used before. Tapping it still triggers SyncQueueHelper. */
    private fun buildSyncRow(): LinearLayout {
        val row = premiumCard().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 17, 18, 17)
            isClickable = true
            isFocusable = true
        }
        row.addView(iconBadge("🔄", teal))
        row.addView(spacerH(16))

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = "Sync Now"
            textSize = 14.5f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, Typeface.BOLD)
        })

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 3, 0, 0)
        }
        syncRowDot = TextView(this).apply {
            text = "●"
            textSize = 9f
        }
        statusRow.addView(syncRowDot)
        statusRow.addView(spacerH(4))
        syncRowStatusText = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor(textGray))
        }
        statusRow.addView(syncRowStatusText)
        textCol.addView(statusRow)
        row.addView(textCol)

        row.setOnClickListener { onSyncNowClicked() }
        // NEW: long-press "Sync Now" to rewind the pull checkpoint to a chosen date/time
        // and immediately resync from there — recovers a window where sync wasn't working
        // (e.g. "yesterday 11am onward") without needing a full re-install/clear-data.
        row.setOnLongClickListener { showResyncFromDialog(); true }

        refreshSyncStatus()
        return row
    }

    private fun onSyncNowClicked() {
        if (!isNetworkConnected()) {
            Toast.makeText(this, "No internet connection", Toast.LENGTH_SHORT).show()
            refreshSyncStatus()
            return
        }
        // FIX (sync diagnostics): this used to just enqueue a background WorkManager job
        // and immediately show a static "Syncing…" toast with no idea whether it actually
        // worked — a real failure (missing Firestore index, wrong Firebase project,
        // permission error, etc.) looked identical to success. Run it directly here and
        // await the real result so the user (and anyone debugging this) can actually see
        // what happened.
        Toast.makeText(this, "Syncing…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = try {
                com.grocerypos.v11.sync.SyncRepository.syncNow(this@SettingsActivity)
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "Sync failed: ${e.message}", Toast.LENGTH_LONG).show()
                refreshSyncStatus()
                return@launch
            }
            Toast.makeText(this@SettingsActivity, result.summary(), Toast.LENGTH_LONG).show()
            refreshSyncStatus()
        }
    }

    /** Long-press "Sync Now" → pick a date & time → rewinds the pull checkpoint to that
     *  moment and immediately resyncs, so anything the server has changed since that time
     *  gets re-pulled (recovers a window where sync wasn't working, e.g. "yesterday 11am
     *  onward"). Does not affect what's queued to be pushed — only what gets pulled. */
    private fun showResyncFromDialog() {
        val cal = java.util.Calendar.getInstance()
        android.app.DatePickerDialog(
            this,
            { _, y, m, d ->
                cal.set(java.util.Calendar.YEAR, y)
                cal.set(java.util.Calendar.MONTH, m)
                cal.set(java.util.Calendar.DAY_OF_MONTH, d)
                android.app.TimePickerDialog(
                    this,
                    { _, hour, minute ->
                        cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
                        cal.set(java.util.Calendar.MINUTE, minute)
                        cal.set(java.util.Calendar.SECOND, 0)
                        com.grocerypos.v11.sync.SyncRepository.resetSyncCheckpoint(this, cal.timeInMillis)
                        val fmt = java.text.SimpleDateFormat("dd MMM, hh:mm a", java.util.Locale.getDefault())
                        Toast.makeText(
                            this,
                            "Resyncing from ${fmt.format(cal.time)}…",
                            Toast.LENGTH_LONG
                        ).show()
                        onSyncNowClicked()
                    },
                    cal.get(java.util.Calendar.HOUR_OF_DAY),
                    cal.get(java.util.Calendar.MINUTE),
                    false
                ).show()
            },
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH),
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        ).show()
    }

    // ADDED (multi-tenant support): admin pastes their own Firebase project's 4
    // values here (from Firebase Console > Project Settings > General > Your apps).
    // See CloudConfigStore.kt for exactly why this exists and how it's used.
    // ADDED (sync recoverability): a simple read-only viewer for the audit log —
    // conflicts and push failures first (most likely to need attention), then
    // everything else, newest first. Purely local — doesn't touch Firestore.
    private fun openSyncHistoryDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 8)
        }
        val scroll = ScrollView(this).apply { addView(container) }
        val loading = TextView(this).apply {
            text = "Loading…"
            setPadding(4, 8, 4, 8)
            setTextColor(Color.parseColor(textGray))
        }
        container.addView(loading)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Sync History")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .create()
        dialog.show()

        lifecycleScope.launch {
            val db = PosDatabase.get(this@SettingsActivity)
            val stuckItems = try {
                db.syncQueueDao().stuck()
            } catch (e: Exception) {
                emptyList()
            }
            val entries = try {
                db.auditDao().recent()
            } catch (e: Exception) {
                emptyList()
            }
            container.removeAllViews()

            // ADDED (risk-free POS): items that gave up retrying after 10 failed
            // attempts — shown first with a one-tap way to give them another chance,
            // e.g. after fixing whatever was wrong (internet, Firestore rules, etc).
            if (stuckItems.isNotEmpty()) {
                container.addView(LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(16, 12, 16, 12)
                    background = strokedBg("#F5A524", "#FFF8F0", 12)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 0, 12) }

                    addView(TextView(this@SettingsActivity).apply {
                        text = "⚠️ ${stuckItems.size} item(s) 10 baar fail hone ke baad rukk gaye"
                        textSize = 12f
                        setTextColor(Color.parseColor(amber))
                        setTypeface(typeface, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(TextView(this@SettingsActivity).apply {
                        text = "🔄 Retry Now"
                        textSize = 12f
                        setTextColor(Color.WHITE)
                        setTypeface(typeface, Typeface.BOLD)
                        background = roundedBg(amber, 20)
                        setPadding(20, 10, 20, 10)
                        setOnClickListener {
                            lifecycleScope.launch {
                                db.syncQueueDao().resetAllStuck()
                                Toast.makeText(this@SettingsActivity, "Dobara try kiya jayega agli Sync Now par", Toast.LENGTH_SHORT).show()
                                dialog.dismiss()
                            }
                        }
                    })
                })
            }

            if (entries.isEmpty()) {
                container.addView(TextView(this@SettingsActivity).apply {
                    text = "Koi sync activity ya conflict abhi tak record nahi hua."
                    setTextColor(Color.parseColor(textGray))
                    setPadding(4, 8, 4, 8)
                })
                return@launch
            }

            val fmt = java.text.SimpleDateFormat("dd MMM, hh:mm a", java.util.Locale.getDefault())
            for (e in entries) {
                val isConflict = e.action == "sync_conflict"
                val isFailure = e.action == "sync_push_failed"
                val labelColor = when {
                    isConflict -> amber
                    isFailure -> red
                    else -> textGray
                }
                container.addView(LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(16, 12, 16, 12)
                    background = strokedBg(border, if (isConflict || isFailure) "#FFF8F0" else cardWhite, 12)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 0, 8) }

                    addView(TextView(this@SettingsActivity).apply {
                        text = when (e.action) {
                            "sync_conflict" -> "⚠️ Conflict — ${e.reference}"
                            "sync_push_failed" -> "❌ Push failed — ${e.reference}"
                            else -> e.reference
                        }
                        setTextColor(Color.parseColor(labelColor))
                        setTypeface(typeface, Typeface.BOLD)
                        textSize = 12.5f
                    })
                    if (e.details.isNotBlank()) {
                        addView(TextView(this@SettingsActivity).apply {
                            text = e.details
                            setTextColor(Color.parseColor(textDark))
                            textSize = 11.5f
                            setPadding(0, 4, 0, 0)
                        })
                    }
                    addView(TextView(this@SettingsActivity).apply {
                        text = fmt.format(java.util.Date(e.createdAt))
                        setTextColor(Color.parseColor(textGray))
                        textSize = 10.5f
                        setPadding(0, 4, 0, 0)
                    })
                })
            }
        }
    }

    private fun openCloudSyncSetupDialog() {
        val existing = com.grocerypos.v11.CloudConfigStore.get(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 8)
        }

        fun labeledField(label: String, prefill: String): EditText {
            container.addView(TextView(this).apply {
                text = label
                textSize = 11f
                setTextColor(Color.parseColor(textGray))
                setPadding(2, 14, 0, 4)
            })
            val field = EditText(this).apply {
                setText(prefill)
                setSingleLine(true)
                background = strokedBg(border, cardWhite, 10)
                setPadding(20, 18, 20, 18)
                textSize = 13.5f
            }
            container.addView(field)
            return field
        }

        container.addView(TextView(this).apply {
            text = "Firebase Console → Project Settings → General → Your apps (Android) → Config mein ye 4 values milengi. Khali chhod kar wapas is build ke default project par ja sakte hain (agar koi ho)."
            textSize = 11.5f
            setTextColor(Color.parseColor(textGray))
            setPadding(2, 0, 0, 4)
        })

        val projectIdField = labeledField("Project ID", existing?.projectId ?: "")
        val apiKeyField = labeledField("API Key", existing?.apiKey ?: "")
        val appIdField = labeledField("App ID", existing?.appId ?: "")
        val storageBucketField = labeledField("Storage Bucket", existing?.storageBucket ?: "")

        val scroll = ScrollView(this).apply { addView(container) }

        AlertDialog.Builder(this)
            .setTitle("Cloud Sync Setup")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val projectId = projectIdField.text.toString().trim()
                val apiKey = apiKeyField.text.toString().trim()
                val appId = appIdField.text.toString().trim()
                val storageBucket = storageBucketField.text.toString().trim()

                if (projectId.isEmpty() || apiKey.isEmpty() || appId.isEmpty()) {
                    Toast.makeText(this, "Project ID, API Key aur App ID zaroori hain", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                com.grocerypos.v11.CloudConfigStore.save(
                    this,
                    com.grocerypos.v11.CloudConfig(projectId, apiKey, appId, storageBucket)
                )
                Toast.makeText(this, "Cloud project connected — ab Sync Now try karein", Toast.LENGTH_LONG).show()
                refreshSyncStatus()
            }
            .setNeutralButton(if (existing != null) "Disconnect" else "Cancel") { _, _ ->
                if (existing != null) {
                    com.grocerypos.v11.CloudConfigStore.clear(this)
                    Toast.makeText(this, "Cloud project disconnected", Toast.LENGTH_SHORT).show()
                    refreshSyncStatus()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ================= PREMIUM GRADIENT HEADER (matches Product/Purchase/Sale headers) =================
    private fun buildHeader(): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(26, 48, 22, 28)
            background = gradientBg(navy, navyLight, cornerBottom = 30)
            applyElevation(this, 10f)
        }

        val iconCircle = FrameLayout(this).apply {
            val px = (56 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(px, px)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke((1.5 * resources.displayMetrics.density).toInt(), Color.parseColor("#DCE3F0"))
            }
            applyElevation(this, 3f)
        }
        iconCircle.addView(TextView(this).apply {
            text = "🏪"
            textSize = 25f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        })
        header.addView(iconCircle)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        shopNameHeaderText = TextView(this).apply {
            text = "My Shop"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        textCol.addView(shopNameHeaderText)
        textCol.addView(TextView(this).apply {
            text = "POINT OF SALE"
            textSize = 10.5f
            setTextColor(Color.parseColor("#A7B4CC"))
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.12f
            setPadding(0, 6, 0, 0)
        })
        header.addView(textCol)

        return header
    }

    /** Loads the saved shop name and reflects it at the top of the header (falls back to the default label). */
    private fun loadHeaderShopName() {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SettingsActivity)
            val name = db.appSettingDao().get("shop_name")?.value
            if (!name.isNullOrBlank()) {
                shopNameHeaderText.text = name
            }
        }
    }

    // ================= MENU ROW HELPERS (premium card rows) =================

    /** A simple, non-expanding premium card row: icon-in-circle + label (+ optional chevron / trailing text). */
    private fun menuRow(
        icon: String,
        label: String,
        showChevron: Boolean = false,
        trailingText: String? = null,
        textColorHex: String = textDark,
        iconBgHex: String = teal,
        onClick: () -> Unit
    ): LinearLayout {
        val row = premiumCard().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 17, 18, 17)
            isClickable = true
            isFocusable = true
        }
        row.addView(iconBadge(icon, iconBgHex))
        row.addView(spacerH(16))
        row.addView(TextView(this).apply {
            text = label
            textSize = 14.5f
            setTextColor(Color.parseColor(textColorHex))
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (trailingText != null) {
            row.addView(TextView(this).apply {
                text = trailingText
                textSize = 18f
                setTextColor(Color.parseColor(teal))
                setTypeface(typeface, Typeface.BOLD)
            })
        } else if (showChevron) {
            row.addView(chevronText())
        }
        row.setOnClickListener { onClick() }
        return row
    }

    /** A premium card row with an optional subtitle line that expands/collapses a target view when tapped. */
    private fun expandableMenuRow(
        icon: String,
        label: String,
        subtitle: String? = null,
        target: View
    ): LinearLayout {
        val row = premiumCard().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 17, 18, 17)
            isClickable = true
            isFocusable = true
        }
        row.addView(iconBadge(icon, navy))
        row.addView(spacerH(16))

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = label
            textSize = 14.5f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, Typeface.BOLD)
        })
        if (subtitle != null) {
            textCol.addView(TextView(this).apply {
                text = subtitle
                textSize = 11f
                setTextColor(Color.parseColor(textGray))
                setPadding(0, 2, 0, 0)
            })
        }
        row.addView(textCol)

        val chevron = chevronText()
        row.addView(chevron)

        row.setOnClickListener {
            val expanding = target.visibility != View.VISIBLE
            target.visibility = if (expanding) View.VISIBLE else View.GONE
            chevron.text = if (expanding) "⌃" else "⌄"
        }
        return row
    }

    private fun iconBadge(icon: String, colorHex: String) = TextView(this).apply {
        text = icon
        textSize = 18f
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(lightenTint(colorHex)))
        }
        val px = (44 * resources.displayMetrics.density).toInt()
        width = px; height = px
    }

    private fun chevronText() = TextView(this).apply {
        text = "⌄"
        textSize = 16f
        setTextColor(Color.parseColor(teal))
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun spacerH(widthDp: Int) = View(this).apply {
        val px = (widthDp * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(px, 1)
    }

    // ================= EXPANDABLE "SETTINGS" CONTENT (all the original sections) =================
    private fun buildSettingsContent(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 4, 0, 8)
        }

        // ---- Shop Info ----
        val shopCard = premiumCard("🏪", "Shop Information")
        shopNameField = plainField("Shop Name")
        phoneField = plainField("Phone")
        addressField = plainField("Address")
        footerField = plainField("Receipt Footer")
        currencyField = plainField("Currency")
        taxField = plainField("Tax %")

        shopCard.addView(iconFieldBox("🏪", "Shop Name", shopNameField))
        shopCard.addView(spacer(10))
        shopCard.addView(iconFieldBox("📞", "Phone", phoneField))
        shopCard.addView(spacer(10))
        shopCard.addView(iconFieldBox("📍", "Address", addressField))
        shopCard.addView(spacer(10))
        shopCard.addView(iconFieldBox("🧾", "Receipt Footer", footerField))
        shopCard.addView(spacer(10))
        shopCard.addView(iconFieldBox("💱", "Currency", currencyField))
        shopCard.addView(spacer(10))
        shopCard.addView(iconFieldBox("📊", "Tax %", taxField))
        shopCard.addView(spacer(12))
        shopCard.addView(primaryButton("SAVE SETTINGS", navy) { saveShopSettings() })
        container.addView(shopCard)
        loadShopSettings()

        // ---- Printer ----
        val printerCard = premiumCard("🖨️", "Printer Setup (58mm Bluetooth)")
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = strokedBg(border, "#FAFBFC", 12)
            setPadding(16, 12, 16, 12)
        }
        printerStatusDot = TextView(this).apply {
            text = "●"
            textSize = 14f
            setTextColor(Color.parseColor(red))
        }
        statusRow.addView(printerStatusDot)
        statusRow.addView(spacerH(10))
        printerStatusText = TextView(this).apply {
            text = "No printer selected"
            textSize = 13f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, Typeface.BOLD)
        }
        statusRow.addView(printerStatusText)
        printerCard.addView(statusRow)
        printerCard.addView(spacer(12))
        loadPrinterStatus()

        val printerBtnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        printerBtnRow.addView(secondaryButton("SELECT PRINTER", navy) { onSelectPrinterClicked() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 6, 0) }
        })
        printerBtnRow.addView(secondaryButton("TEST PRINT", teal) { onTestPrintClicked() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 0, 0) }
        })
        printerCard.addView(printerBtnRow)
        container.addView(printerCard)

        // ---- Security (Login Method) ----
        val securityCard = premiumCard("🔐", "Security — Login Method")
        loginMethodGroup = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        passwordOnlyRadio = radioOption("Password Only")
        fingerprintOnlyRadio = radioOption("Fingerprint Only")
        bothRadio = radioOption("Both (Password + Fingerprint)")
        // "No Password" — app opens straight to the dashboard, no login screen at all
        // (see LoginActivity.onCreate — it checks this setting before building any UI).
        noPasswordRadio = radioOption("No Password (App won't lock)")
        // NEW: "OTP (Phone Number)" — activates LoginActivity's existing otpSection /
        // Firebase Phone Auth flow (applyLoginMethod's "otp" branch), which previously
        // had no way to be turned on since this radio didn't exist.
        otpRadio = radioOption("OTP (Phone Number)")
        loginMethodGroup.addView(passwordOnlyRadio)
        loginMethodGroup.addView(fingerprintOnlyRadio)
        loginMethodGroup.addView(bothRadio)
        loginMethodGroup.addView(noPasswordRadio)
        loginMethodGroup.addView(otpRadio)
        securityCard.addView(loginMethodGroup)

        loginMethodGroup.setOnCheckedChangeListener { _, checkedId ->
            val method = when (checkedId) {
                fingerprintOnlyRadio.id -> "fingerprint"
                bothRadio.id -> "both"
                noPasswordRadio.id -> "none"
                otpRadio.id -> "otp"
                else -> "password"
            }
            lifecycleScope.launch {
                val db = PosDatabase.get(this@SettingsActivity)
                db.appSettingDao().set(AppSetting("login_method", method))
                com.grocerypos.v11.AppLock.updateCachedLoginMethod(method)
            }
        }
        container.addView(securityCard)
        loadLoginMethodSetting()

        // ---- Change Username / Password ----
        val loginCard = premiumCard("🔑", "Change Login (Username / Password)")
        val session = getSharedPreferences("session", MODE_PRIVATE)
        val loggedInUsername = session.getString("username", "") ?: ""

        currentUsernameField = plainField("Current Username").apply {
            setText(loggedInUsername)
            isEnabled = false
            setTextColor(Color.parseColor(textGray))
        }
        newUsernameField = plainField("New Username (blank = keep same)")
        newPasswordField = plainField("New Password (blank = keep same)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        loginCard.addView(iconFieldBox("👤", "Current Username", currentUsernameField, muted = true))
        loginCard.addView(spacer(10))
        loginCard.addView(iconFieldBox("🆕", "New Username", newUsernameField))
        loginCard.addView(spacer(10))
        loginCard.addView(passwordFieldBox("🔒", "New Password", newPasswordField))
        loginCard.addView(spacer(12))
        loginCard.addView(primaryButton("UPDATE LOGIN", navy) { updateLogin(loggedInUsername) })
        container.addView(loginCard)

        // ---- Language ----
        val languageCard = premiumCard("🌐", "Language")
        val langRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val englishBtn = pillToggleButton("English")
        val urduBtn = pillToggleButton("اردو")
        fun refreshLanguageButtons() {
            val isUrdu = com.grocerypos.v11.util.Loc.isUrdu(this)
            englishBtn.background = if (!isUrdu) roundedBg(navy, 30) else strokedBg(border, "#FAFBFC", 30)
            englishBtn.setTextColor(if (!isUrdu) Color.WHITE else Color.parseColor(textGray))
            urduBtn.background = if (isUrdu) roundedBg(navy, 30) else strokedBg(border, "#FAFBFC", 30)
            urduBtn.setTextColor(if (isUrdu) Color.WHITE else Color.parseColor(textGray))
        }
        englishBtn.setOnClickListener {
            com.grocerypos.v11.util.Loc.setLanguage(this, "en")
            refreshLanguageButtons()
            recreate()
        }
        urduBtn.setOnClickListener {
            com.grocerypos.v11.util.Loc.setLanguage(this, "ur")
            refreshLanguageButtons()
            recreate()
        }
        englishBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 5, 0) }
        urduBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(5, 0, 0, 0) }
        langRow.addView(englishBtn)
        langRow.addView(urduBtn)
        languageCard.addView(langRow)
        refreshLanguageButtons()
        container.addView(languageCard)

        // ---- Users ----
        val usersCard = premiumCard("👥", "Users & Account")
        usersCard.addView(secondaryButton("MANAGE USERS", navy) {
            startActivity(Intent(this@SettingsActivity, UserManagementActivity::class.java))
        })
        container.addView(usersCard)

        return container
    }

    // ================= EXPANDABLE "BACKUP/RESTORE" CONTENT =================
    private fun buildBackupContent(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 4, 0, 8)
        }
        val backupCard = premiumCard("🗄️", "Backup & Restore")
        backupCard.addView(primaryButton("BACKUP NOW", teal) { onBackupClicked() })
        backupCard.addView(spacer(10))
        backupCard.addView(primaryButton("RESTORE BACKUP", red) { onRestoreClicked() })
        backupCard.addView(spacer(10))
        backupCard.addView(primaryButton("IMPORT BACKUP FILE", navy) { importBackupLauncher.launch(arrayOf("*/*")) })
        backupCard.addView(spacer(10))
        backupCard.addView(primaryButton("BACKUP PASSWORD DEKHEIN", navy) { onViewBackupPasswordClicked() })
        backupCard.addView(spacer(6))
        backupCard.addView(TextView(this).apply {
            text = "Backups ab encrypted hoti hain (.ibbackup). BACKUP PASSWORD DEKHEIN se apna password kahin surakshit likh kar rakh lein — dusre phone par ya app reinstall ke baad restore karne ke liye zaroori hoga."
            textSize = 11f
            setTextColor(Color.parseColor(textGray))
        })
        backupCard.addView(spacer(6))
        backupCard.addView(TextView(this).apply {
            text = "Agar RESTORE BACKUP list mein sahi backup nahi dikh raha (jaise app reinstall karne ke baad), to IMPORT BACKUP FILE se Downloads ya kahin bhi se backup file seedha select kar ke restore karein."
            textSize = 11f
            setTextColor(Color.parseColor(textGray))
        })
        backupCard.addView(spacer(12))
        backupCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = strokedBg("#F4C9CB", "#FDEEEE", 12)
            setPadding(16, 12, 16, 12)
            addView(TextView(this@SettingsActivity).apply { text = "⚠️  "; textSize = 13f })
            addView(TextView(this@SettingsActivity).apply {
                text = "Restore purani backup laata hai aur is waqt ka sara naya data (jo backup ke baad add hua) permanently mita deta hai. Sirf tab use karein jab aapko waqai purani state par jaana ho."
                textSize = 11.5f
                setTextColor(Color.parseColor(red))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        })
        container.addView(backupCard)
        return container
    }

    // ================= UI HELPERS =================

    /** Premium card container — matches Product/Purchase/Sale's premiumCard() pattern. */
    private fun premiumCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = strokedBg(border, cardWhite, 18)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 14) }
        applyElevation(this, 3f)
    }

    /** Premium card with a small icon-badge section title inside — used for the Settings sub-sections. */
    private fun premiumCard(icon: String, title: String) = premiumCard().apply {
        setPadding(20, 18, 20, 18)
        addView(sectionLabel(icon, title))
        addView(spacer(4))
    }

    private fun sectionLabel(icon: String, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, 14)
        addView(TextView(this@SettingsActivity).apply {
            text = "$icon  "
            textSize = 14f
        })
        addView(TextView(this@SettingsActivity).apply {
            text = label
            textSize = 13.5f
            setTextColor(Color.parseColor(teal))
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.01f
        })
    }

    private fun microLabel(label: String) = TextView(this).apply {
        text = label.uppercase()
        textSize = 10f
        setTextColor(Color.parseColor(textGray))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, 4)
        letterSpacing = 0.03f
    }

    private fun plainField(hint: String) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(Color.parseColor(textGray))
        setTextColor(Color.parseColor(textDark))
        background = null
        textSize = 14.5f
    }

    /** Icon-prefixed labeled field box — matches ProductActivity's fieldBox(icon) pattern. */
    private fun iconFieldBox(icon: String, label: String, field: EditText, muted: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = strokedBg(border, if (muted) "#F1F3F5" else "#FAFBFC", 12)
        setPadding(16, 10, 16, 10)
        addView(microLabel(label))
        val row = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this@SettingsActivity).apply { text = "$icon  "; textSize = 14f })
        (field.parent as? ViewGroup)?.removeView(field)
        field.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(field)
        addView(row)
    }

    private fun passwordFieldBox(icon: String, label: String, field: EditText) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = strokedBg(border, "#FAFBFC", 12)
        setPadding(16, 10, 16, 10)
        addView(microLabel(label))
        val row = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this@SettingsActivity).apply { text = "$icon  "; textSize = 14f })
        (field.parent as? ViewGroup)?.removeView(field)
        field.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(field)

        val toggle = TextView(this@SettingsActivity).apply {
            text = "SHOW"
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = roundedBg(teal, 20)
            setPadding(16, 6, 16, 6)
            var visible = false
            setOnClickListener {
                visible = !visible
                field.inputType = if (visible)
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                field.setSelection(field.text.length)
                text = if (visible) "HIDE" else "SHOW"
            }
        }
        row.addView(spacerH(8))
        row.addView(toggle)
        addView(row)
    }

    private fun radioOption(label: String) = RadioButton(this).apply {
        text = label
        setTextColor(Color.parseColor(textDark))
        textSize = 13.5f
        setPadding(8, 10, 0, 10)
    }

    private fun primaryButton(label: String, colorHex: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 13.5f
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        background = roundedBg(colorHex, 14)
        setPadding(0, 20, 0, 20)
        setOnClickListener { onClick() }
        applyElevation(this, 3f)
    }

    private fun secondaryButton(label: String, colorHex: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.parseColor(colorHex))
        textSize = 12.5f
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        background = strokedBg(colorHex, "#FFFFFF", 14)
        setPadding(0, 16, 0, 16)
        setOnClickListener { onClick() }
    }

    /** Pill-style toggle button — matches the unit chip / spinner pill pattern used elsewhere. */
    private fun pillToggleButton(label: String) = Button(this).apply {
        text = label
        textSize = 12.5f
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 14, 0, 14)
        minHeight = 0
        stateListAnimator = null
    }

    private fun roundedBg(colorHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(colorHex))
        cornerRadius = radius.toFloat()
    }

    private fun strokedBg(strokeHex: String, fillHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(fillHex))
        setStroke((1.2 * resources.displayMetrics.density).toInt(), Color.parseColor(strokeHex))
        cornerRadius = radius.toFloat()
    }

    private fun gradientBg(startHex: String, endHex: String, cornerTop: Int = 0, cornerBottom: Int = 0) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(startHex), Color.parseColor(endHex))
    ).apply {
        val density = resources.displayMetrics.density
        cornerRadii = floatArrayOf(
            cornerTop * density, cornerTop * density,
            cornerTop * density, cornerTop * density,
            cornerBottom * density, cornerBottom * density,
            cornerBottom * density, cornerBottom * density
        )
    }

    /** Same lightening approach as CashActivity's stat-card tint — used for menu-row icon badges. */
    private fun lightenTint(colorHex: String): String {
        val base = Color.parseColor(colorHex)
        val factor = 0.82f
        val r = (Color.red(base) + (255 - Color.red(base)) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(base) + (255 - Color.green(base)) * factor).toInt().coerceIn(0, 255)
        val bl = (Color.blue(base) + (255 - Color.blue(base)) * factor).toInt().coerceIn(0, 255)
        return String.format("#%02X%02X%02X", r, g, bl)
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

    // ================= SHOP INFO SAVE/LOAD =================
    private fun loadShopSettings() {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SettingsActivity)
            shopNameField.setText(db.appSettingDao().get("shop_name")?.value ?: "")
            phoneField.setText(db.appSettingDao().get("shop_phone")?.value ?: "")
            addressField.setText(db.appSettingDao().get("shop_address")?.value ?: "")
            footerField.setText(db.appSettingDao().get("receipt_footer")?.value ?: "")
            currencyField.setText(db.appSettingDao().get("currency")?.value ?: "")
            taxField.setText(db.appSettingDao().get("tax_percent")?.value ?: "")
        }
    }

    private fun saveShopSettings() {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SettingsActivity)
            val newShopName = shopNameField.text.toString().trim()
            db.appSettingDao().set(AppSetting("shop_name", newShopName))
            db.appSettingDao().set(AppSetting("shop_phone", phoneField.text.toString().trim()))
            db.appSettingDao().set(AppSetting("shop_address", addressField.text.toString().trim()))
            db.appSettingDao().set(AppSetting("receipt_footer", footerField.text.toString().trim()))
            db.appSettingDao().set(AppSetting("currency", currencyField.text.toString().trim()))
            db.appSettingDao().set(AppSetting("tax_percent", taxField.text.toString().trim()))
            if (newShopName.isNotEmpty()) {
                shopNameHeaderText.text = newShopName
            }
            Toast.makeText(this@SettingsActivity, "Settings saved", Toast.LENGTH_SHORT).show()
        }
    }

    // ================= SECURITY (LOGIN METHOD) =================
    private fun loadLoginMethodSetting() {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SettingsActivity)
            when (db.appSettingDao().get("login_method")?.value ?: "password") {
                "fingerprint" -> fingerprintOnlyRadio.isChecked = true
                "both" -> bothRadio.isChecked = true
                "none" -> noPasswordRadio.isChecked = true
                "otp" -> otpRadio.isChecked = true
                else -> passwordOnlyRadio.isChecked = true
            }
        }
    }

    // ================= PRINTER =================
    private fun loadPrinterStatus() {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SettingsActivity)
            val printerName = db.appSettingDao().get("printer_name")?.value
            if (!printerName.isNullOrEmpty()) {
                printerStatusText.text = "Selected: $printerName"
                printerStatusDot.setTextColor(Color.parseColor(teal))
            } else {
                printerStatusText.text = "No printer selected"
                printerStatusDot.setTextColor(Color.parseColor(red))
            }
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

    private fun savePrinter(printerName: String, mac: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SettingsActivity)
            db.appSettingDao().set(AppSetting("printer_name", printerName))
            db.appSettingDao().set(AppSetting("printer_mac", mac))
            db.appSettingDao().set(AppSetting("printer_width", "58"))
            printerStatusText.text = "Selected: $printerName"
            printerStatusDot.setTextColor(Color.parseColor(teal))
            Toast.makeText(this@SettingsActivity, "Printer saved: $printerName", Toast.LENGTH_SHORT).show()
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
            val shopName = db.appSettingDao().get("shop_name")?.value ?: "My Shop"
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
    // FIX (perf): backupNow() does a WAL checkpoint + file copy + AES encryption + a
    // second copy into Downloads — all real disk/CPU work that used to run directly
    // on the main thread here, freezing the UI for the duration of every backup.
    // Moved onto Dispatchers.IO; only the Toast/share (UI work) hops back to Main.
    private fun onBackupClicked() {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { BackupHelper.backupNow(this@SettingsActivity) }
            if (file != null) {
                Toast.makeText(this@SettingsActivity, "Backup ho gaya: ${file.name}", Toast.LENGTH_LONG).show()
                BackupHelper.shareBackup(this@SettingsActivity, file)
            } else {
                Toast.makeText(this@SettingsActivity, "Backup fail ho gaya", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Shows the current backup password (view/copy) — the owner should save this
     * somewhere safe outside the phone; it's needed to restore a backup on a
     * different device, or after reinstalling the app. */
    private fun onViewBackupPasswordClicked() {
        val password = BackupPasswordStore.getOrCreate(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        container.addView(TextView(this).apply {
            text = "Ye password aapki backup files ko decrypt karne ke liye chahiye — " +
                "isay kahin surakshit likh kar rakh lein (dusre phone par restore karne ke liye zaroori hai):"
            textSize = 13.5f
            setTextColor(Color.parseColor(textDark))
        })
        val passwordField = EditText(this).apply {
            setText(password)
            isFocusable = false
            isClickable = false
            setPadding(0, 32, 0, 0)
            textSize = 18f
            setTextColor(Color.parseColor(textDark))
        }
        container.addView(passwordField)

        AlertDialog.Builder(this)
            .setTitle("Backup Password")
            .setView(container)
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Backup Password", password))
                Toast.makeText(this, "Password copy ho gaya", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Change") { _, _ -> onChangeBackupPasswordClicked() }
            .setNegativeButton("Band Karein", null)
            .show()
    }

    private fun onChangeBackupPasswordClicked() {
        val field = EditText(this).apply {
            hint = "Naya password"
            setPadding(48, 32, 48, 0)
        }
        AlertDialog.Builder(this)
            .setTitle("Backup Password Badlein")
            .setMessage("Yaad rahe: purani backups is naye password se decrypt nahi hongi — unke liye purana password chahiye hoga.")
            .setView(field)
            .setPositiveButton("Save") { _, _ ->
                val newPass = field.text.toString().trim()
                if (newPass.length < 4) {
                    Toast.makeText(this, "Password kam se kam 4 characters ka ho", Toast.LENGTH_SHORT).show()
                } else {
                    BackupPasswordStore.setPassword(this, newPass)
                    Toast.makeText(this, "Password update ho gaya", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onRestoreClicked() {
        val backups = BackupHelper.listBackups(this)
        if (backups.isEmpty()) {
            Toast.makeText(this, "Koi backup nahi mila", Toast.LENGTH_SHORT).show()
            return
        }
        val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        val labels = backups.mapIndexed { index, file ->
            val dateLabel = fmt.format(java.util.Date(file.lastModified()))
            if (index == 0) "$dateLabel  (most recent)" else dateLabel
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Backup to Restore")
            .setItems(labels) { _, which ->
                confirmRestore(backups[which], labels[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRestore(file: java.io.File, dateLabel: String) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        container.addView(TextView(this).apply {
            text = "Ye backup ($dateLabel) is waqt ke current data ko OVERWRITE kar dega.\n\n" +
                "Iska matlab: is backup ke baad add ki gayi tamam sales, purchases, aur baaki entries HAMESHA ke liye mit jayengi. Ye wapis nahi hoga."
            textSize = 13.5f
            setTextColor(Color.parseColor(textDark))
        })
        val checkBox = CheckBox(this).apply {
            text = "Mujhe samajh aa gaya, aage badhein"
            setTextColor(Color.parseColor(textDark))
            setPadding(0, 28, 0, 0)
        }
        container.addView(checkBox)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Restore Backup?")
            .setView(container)
            .setPositiveButton("Restore", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val positiveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveBtn.isEnabled = false
            positiveBtn.setTextColor(Color.parseColor(red))
            checkBox.setOnCheckedChangeListener { _, isChecked -> positiveBtn.isEnabled = isChecked }
            positiveBtn.setOnClickListener {
                dialog.dismiss()
                if (BackupHelper.needsPassword(file)) {
                    promptPasswordAndRestore { pass -> runRestore { BackupHelper.restore(this, file, pass) } }
                } else {
                    runRestore { BackupHelper.restore(this, file) }
                }
            }
        }
        dialog.show()
    }

    /** Prompts for the backup password, pre-filled with this device's saved one
     * (same-device restore is then just tap-confirm; a different/reinstalled
     * device needs the owner to type in the password they saved earlier). */
    private fun promptPasswordAndRestore(onPassword: (String) -> Unit) {
        val field = EditText(this).apply {
            setText(BackupPasswordStore.getOrCreate(this@SettingsActivity))
            setPadding(48, 32, 48, 0)
        }
        AlertDialog.Builder(this)
            .setTitle("Backup Password Dalein")
            .setView(field)
            .setPositiveButton("Restore") { _, _ -> onPassword(field.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // FIX (perf): restore also does real file I/O (+ decryption) — same main-thread
    // freeze issue as backup. Moved onto Dispatchers.IO.
    private fun runRestore(action: () -> Boolean) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { action() }
            if (ok) {
                Toast.makeText(this@SettingsActivity, "Restore ho gaya. App ko dobara open karein.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@SettingsActivity, "Restore fail ho gaya — password check karein", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Same confirmation flow as [confirmRestore], but for a file picked via the
     * system document picker (see [importBackupLauncher]) instead of one already
     * sitting in the app's own Backups folder. */
    private fun confirmRestoreFromUri(uri: Uri) {
        val displayName = try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
            }
        } catch (e: Exception) { null } ?: "selected file"

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        container.addView(TextView(this).apply {
            text = "Ye backup ($displayName) is waqt ke current data ko OVERWRITE kar dega.\n\n" +
                "Iska matlab: is backup ke baad add ki gayi tamam sales, purchases, aur baaki entries HAMESHA ke liye mit jayengi. Ye wapis nahi hoga.\n\n" +
                "Agar ye galat file hai to Cancel dabayein."
            textSize = 13.5f
            setTextColor(Color.parseColor(textDark))
        })
        val checkBox = CheckBox(this).apply {
            text = "Mujhe samajh aa gaya, aage badhein"
            setTextColor(Color.parseColor(textDark))
            setPadding(0, 28, 0, 0)
        }
        container.addView(checkBox)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Restore Backup?")
            .setView(container)
            .setPositiveButton("Restore", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val positiveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveBtn.isEnabled = false
            positiveBtn.setTextColor(Color.parseColor(red))
            checkBox.setOnCheckedChangeListener { _, isChecked -> positiveBtn.isEnabled = isChecked }
            positiveBtn.setOnClickListener {
                dialog.dismiss()
                // We can't tell if the picked file is encrypted until it's read, so
                // always ask for the password — restoreFromUri only uses it if the
                // file turns out to actually need one (ignored for old plain .db files).
                promptPasswordAndRestore { pass ->
                    runRestore { BackupHelper.restoreFromUri(this, uri, pass) }
                }
            }
        }
        dialog.show()
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
            // FIX (Phase 4 - Security): a newly typed password is now hashed before
            // saving (previously stored as-typed, plain text). If left blank, the
            // existing passwordHash is kept unchanged either way.
            val finalPassword = if (newPassword.isNotEmpty()) PasswordHasher.hash(newPassword) else user.passwordHash

            val updatedUser = User(
                username = finalUsername,
                displayName = user.displayName,
                role = user.role,
                passwordHash = finalPassword,
                active = true,
                phone = user.phone
            )
            db.userDao().upsert(updatedUser)

            if (finalUsername != currentUsername) {
                db.userDao().delete(currentUsername)
                getSharedPreferences("session", MODE_PRIVATE).edit()
                    .putString("username", finalUsername).apply()
                currentUsernameField.setText(finalUsername)
            }

            // ---- Queue this change so it pushes up to Firebase ----
            SyncQueueHelper.enqueue(
                db = db,
                entityType = "user",
                entityId = SyncQueueHelper.userEntityId(updatedUser),
                operation = "upsert",
                payloadJson = SyncQueueHelper.userJson(updatedUser)
            )
            SyncQueueHelper.trigger(this@SettingsActivity)

            Toast.makeText(this@SettingsActivity, "Login updated", Toast.LENGTH_SHORT).show()
            newUsernameField.text.clear()
            newPasswordField.text.clear()
        }
    }
}
