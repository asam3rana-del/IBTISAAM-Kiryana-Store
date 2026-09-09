package com.grocerypos.v11.ui

import com.grocerypos.v11.R

/*
 * Settings screen — Cloud Sync subsystem: connectivity check, the "Sync Now"
 * row + its status dot, the resync-from-a-date-time long-press action, the
 * Sync History viewer, and the Cloud Sync Setup dialog. Split out of
 * SettingsActivity.kt as part of the "Oversized Activity files" cleanup (see
 * IMPROVEMENT-PLAN.md), same approach as the Sale/Product/Party screens:
 * extension functions on SettingsActivity, no behavior change.
 */

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.*
import com.grocerypos.v11.sync.SyncApi
import com.grocerypos.v11.ui.components.*
import kotlinx.coroutines.launch

internal fun SettingsActivity.isNetworkConnected(): Boolean {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/** Updates the small dot + label under "Sync Now" to reflect current connectivity
 *  AND whether this device even has a cloud project configured — see
 *  CloudConfigStore.kt. Previously this only checked network connectivity, so a
 *  device with no cloud project at all still showed a reassuring green
 *  "Connected" dot even though Sync Now could never do anything. */
internal fun SettingsActivity.refreshSyncStatus() {
    val online = isNetworkConnected()
    val cloudConfigured = com.grocerypos.v11.CloudConfigStore.firebaseApp(this) != null
    when {
        !cloudConfigured -> {
            syncRowDot.setTextColor(Color.parseColor(amber))
            syncRowStatusText.text = "Not set up — tap Cloud Sync Setup"
        }
        !BranchConfigStore.isConfigured() -> {
            syncRowDot.setTextColor(Color.parseColor(amber))
            syncRowStatusText.text = "Branch Code missing — tap Cloud Sync Setup"
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
internal fun SettingsActivity.buildSyncRow(): LinearLayout {
    val row = premiumCard().apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(18, 17, 18, 17)
        isClickable = true
        isFocusable = true
    }
    row.addView(iconBadge(R.drawable.ic_sync, teal))
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

internal fun SettingsActivity.onSyncNowClicked() {
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
            com.grocerypos.v11.sync.SyncRepository.syncNow(this@onSyncNowClicked)
        } catch (e: Exception) {
            Toast.makeText(this@onSyncNowClicked, "Sync failed: ${e.message}", Toast.LENGTH_LONG).show()
            refreshSyncStatus()
            return@launch
        }
        Toast.makeText(this@onSyncNowClicked, result.summary(), Toast.LENGTH_LONG).show()
        refreshSyncStatus()
    }
}

/** Long-press "Sync Now" → pick a date & time → rewinds the pull checkpoint to that
 *  moment and immediately resyncs, so anything the server has changed since that time
 *  gets re-pulled (recovers a window where sync wasn't working, e.g. "yesterday 11am
 *  onward"). Does not affect what's queued to be pushed — only what gets pulled. */
internal fun SettingsActivity.showResyncFromDialog() {
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
internal fun SettingsActivity.openSyncHistoryDialog() {
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
        val db = PosDatabase.get(this@openSyncHistoryDialog)
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
            container.addView(LinearLayout(this@openSyncHistoryDialog).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16, 12, 16, 12)
<<<<<<< HEAD
                background = strokedBg(amber, amberBg, 12)
=======
                background = strokedBg("#F5A524", "#FFF8F0", 12)
>>>>>>> cc8b3ed1c3be113f6b2a67aab0b9727c246553fa
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 12) }

                addView(TextView(this@openSyncHistoryDialog).apply {
                    text = "${stuckItems.size} item(s) 10 baar fail hone ke baad rukk gaye"
                    textSize = 12f
                    setTextColor(Color.parseColor(amber))
                    setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setLeadingIcon(R.drawable.ic_warning, amber, 14, 6)
                })
                addView(TextView(this@openSyncHistoryDialog).apply {
                    text = "Retry Now"
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, Typeface.BOLD)
                    background = roundedBg(amber, 20)
                    setPadding(20, 10, 20, 10)
                    setLeadingIcon(R.drawable.ic_sync, "#FFFFFF", 13, 5)
                    setOnClickListener {
                        lifecycleScope.launch {
                            db.syncQueueDao().resetAllStuck()
                            Toast.makeText(this@openSyncHistoryDialog, "Dobara try kiya jayega agli Sync Now par", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                        }
                    }
                })
            })
        }

        if (entries.isEmpty()) {
            container.addView(TextView(this@openSyncHistoryDialog).apply {
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
            container.addView(LinearLayout(this@openSyncHistoryDialog).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 12, 16, 12)
<<<<<<< HEAD
                background = strokedBg(border, if (isConflict || isFailure) amberBg else cardWhite, 12)
=======
                background = strokedBg(border, if (isConflict || isFailure) "#FFF8F0" else cardWhite, 12)
>>>>>>> cc8b3ed1c3be113f6b2a67aab0b9727c246553fa
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 8) }

                addView(TextView(this@openSyncHistoryDialog).apply {
                    text = when (e.action) {
                        "sync_conflict" -> "Conflict — ${e.reference}"
                        "sync_push_failed" -> "Push failed — ${e.reference}"
                        else -> e.reference
                    }
                    setTextColor(Color.parseColor(labelColor))
                    setTypeface(typeface, Typeface.BOLD)
                    textSize = 12.5f
                    when (e.action) {
                        "sync_conflict" -> setLeadingIcon(R.drawable.ic_warning, labelColor, 13, 5)
                        "sync_push_failed" -> setLeadingIcon(R.drawable.ic_close, labelColor, 13, 5)
                    }
                })
                if (e.details.isNotBlank()) {
                    addView(TextView(this@openSyncHistoryDialog).apply {
                        text = e.details
                        setTextColor(Color.parseColor(textDark))
                        textSize = 11.5f
                        setPadding(0, 4, 0, 0)
                    })
                }
                addView(TextView(this@openSyncHistoryDialog).apply {
                    text = fmt.format(java.util.Date(e.createdAt))
                    setTextColor(Color.parseColor(textGray))
                    textSize = 10.5f
                    setPadding(0, 4, 0, 0)
                })
            })
        }
    }
}

internal fun SettingsActivity.openCloudSyncSetupDialog() {
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

    // ADDED (runtime branch config): each branch is now told apart by a code
    // entered here instead of a compile-time BuildConfig value baked into a
    // separate APK per branch — see BranchConfigStore.kt.
    container.addView(TextView(this).apply {
        text = "Is device ka Branch Code — har branch ke liye alag, jaise \"main-branch\" ya \"dusri-branch\". Sab devices jo ek hi branch ka data share karna chahte hain, unka code same hona chahiye."
        textSize = 11.5f
        setTextColor(Color.parseColor(textGray))
        setPadding(2, 10, 0, 4)
    })
    val branchIdField = labeledField("Branch Code", BranchConfigStore.current)

    // ADDED (branch approval): this device's Firebase Auth UID, so the shop
    // owner/admin can hand it off to whoever manages the Firebase console to
    // create the matching branch_members/{uid} document — see firestore.rules
    // and SyncApi.currentUid(). Without this document existing server-side, sync
    // will authenticate fine but every read/write gets rejected as permission-
    // denied — this field is what lets a human actually fix that.
    val uid = SyncApi.currentUid(this)
    container.addView(TextView(this).apply {
        text = "Device ID (admin ko share karein taake yeh device branch access ke liye approve ho sake)"
        textSize = 11f
        setTextColor(Color.parseColor(textGray))
        setPadding(2, 14, 0, 4)
    })
    val uidRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    uidRow.addView(TextView(this).apply {
        text = uid ?: "Pehle Save karein — pehli sync attempt ke baad ID yahan aayegi"
        textSize = 12.5f
        setTextColor(Color.parseColor(if (uid != null) textDark else textGray))
        setPadding(0, 0, 12, 0)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    })
    if (uid != null) {
        uidRow.addView(Button(this).apply {
            text = "Copy"
            textSize = 11f
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Device ID", uid))
                Toast.makeText(this@openCloudSyncSetupDialog, "Device ID copy ho gayi", Toast.LENGTH_SHORT).show()
            }
        })
    }
    container.addView(uidRow)

    val scroll = ScrollView(this).apply { addView(container) }

    val dialogBuilder = AlertDialog.Builder(this)
        .setTitle("Cloud Sync Setup")
        .setView(scroll)
        .setPositiveButton("Save") { _, _ ->
            val projectId = projectIdField.text.toString().trim()
            val apiKey = apiKeyField.text.toString().trim()
            val appId = appIdField.text.toString().trim()
            val storageBucket = storageBucketField.text.toString().trim()
            val branchId = branchIdField.text.toString().trim()

            if (projectId.isEmpty() || apiKey.isEmpty() || appId.isEmpty()) {
                Toast.makeText(this, "Project ID, API Key aur App ID zaroori hain", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            if (!BranchConfigStore.isValid(branchId)) {
                Toast.makeText(this, "Branch Code 2-50 characters ka ho: A-Z, 0-9, _ ya -", Toast.LENGTH_LONG).show()
                return@setPositiveButton
            }
            com.grocerypos.v11.CloudConfigStore.save(
                this,
                com.grocerypos.v11.CloudConfig(projectId, apiKey, appId, storageBucket)
            )
            BranchConfigStore.set(this, branchId)
            Toast.makeText(this, "Cloud project connected — ab Sync Now try karein", Toast.LENGTH_LONG).show()
            refreshSyncStatus()
        }
        .setNegativeButton("Cancel", null)

    // FIX (duplicate Cancel button): setNeutralButton used to always be added with
    // label "Cancel" whenever there was no existing config, which put two
    // identically-labelled Cancel buttons on the dialog side by side. Only add the
    // neutral button at all when there's something to actually disconnect.
    if (existing != null) {
        dialogBuilder.setNeutralButton("Disconnect") { _, _ ->
            com.grocerypos.v11.CloudConfigStore.clear(this)
            BranchConfigStore.clear(this)
            Toast.makeText(this, "Cloud project aur Branch Code disconnect ho gaye", Toast.LENGTH_SHORT).show()
            refreshSyncStatus()
        }
    }

    dialogBuilder.show()
}
