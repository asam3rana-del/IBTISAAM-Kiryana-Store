package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Opened from PartyDashboardActivity when the user taps a party in the Parties tab.
 * Shows ONLY that party's own transactions (sales if customer, purchases if supplier),
 * newest first. Tapping a row opens the underlying Sale/Purchase.
 *
 * Expected intent extras:
 *   partyId: Long, partyName: String, isCustomer: Boolean
 *
 * *** ASSUMPTION *** — same as PartyDashboardActivity's transaction rows: opens
 * SaleActivity with extra "invoice" / PurchaseActivity with extra "billNo". Tell me
 * if those keys are different and I'll fix the two ADJUST-EXTRA-KEY lines below.
 *
 * Edit Party Name button uses customerDao()/supplierDao() (picked via isCustomer) — each
 * needs a suspend fun find(id: Long): Customer?/Supplier? and the existing suspend fun
 * update(...). find() was added to both DAOs in Database.kt alongside this change.
 *
 * Manifest registration required:
 *   <activity android:name=".ui.PartyTransactionActivity" android:exported="false" />
 */
class PartyTransactionActivity : AppCompatActivity() {

    private val bg = "#F3F4F9"
    private val cardWhite = "#FFFFFF"
    private val cardBorder = "#EEF0F7"
    private val textDark = "#2E3242"
    private val labelGray = "#9AA0B4"
    private val green = "#4CAF50"
    private val orange = "#F5A15C"
    private val red = "#E57373"
    private val teal = "#0F9B8E"

    private lateinit var listContainer: LinearLayout
    private lateinit var partyNameLabel: TextView
    private var partyId: Long = -1
    private var partyName: String = ""
    private var isCustomer: Boolean = true

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        partyId = intent.getLongExtra("partyId", -1)
        partyName = intent.getStringExtra("partyName") ?: ""
        isCustomer = intent.getBooleanExtra("isCustomer", true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 40, 24, 32)
            setBackgroundColor(Color.parseColor(bg))
        }

        val headerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        headerRow.addView(TextView(this).apply {
            text = "\u2190"
            textSize = 18f
            setTextColor(Color.parseColor(textDark))
            setPadding(0, 0, 16, 0)
            setOnClickListener { finish() }
        })
        partyNameLabel = TextView(this).apply {
            text = partyName
            textSize = 19f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor(textDark))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(partyNameLabel)

        // ---- Edit party name button — opens a small dialog to rename this party. ----
        headerRow.addView(TextView(this).apply {
            text = "\u270F\uFE0F  " + Loc.t(this@PartyTransactionActivity, "Edit", "\u062A\u0631\u0645\u06CC\u0645")
            textSize = 12.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(teal))
                cornerRadius = 30f
            }
            setPadding(22, 12, 22, 12)
            setOnClickListener { promptEditPartyName() }
        })
        root.addView(headerRow)

        root.addView(TextView(this).apply {
            text = if (isCustomer) Loc.t(this@PartyTransactionActivity, "Customer transactions", "\u06A9\u0633\u0679\u0645\u0631 \u0644\u06CC\u0646 \u062F\u06CC\u0646")
            else Loc.t(this@PartyTransactionActivity, "Supplier transactions", "\u0633\u067E\u0644\u0627\u0626\u0631 \u0644\u06CC\u0646 \u062F\u06CC\u0646")
            textSize = 12.5f
            setTextColor(Color.parseColor(labelGray))
            setPadding(28, 4, 0, 20)
        })

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        })

        loadTransactions()
    }

    override fun onResume() {
        super.onResume()
        loadTransactions()
    }

    // ---------------- Edit party name ----------------

    private fun promptEditPartyName() {
        val input = EditText(this).apply {
            setPadding(32, 24, 32, 24)
            setText(partyName)
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle(Loc.t(this, "Edit Name", "\u0646\u0627\u0645 \u062A\u0631\u0645\u06CC\u0645 \u06A9\u0631\u06CC\u06BA"))
            .setView(input)
            .setPositiveButton(Loc.t(this, "Save", "\u0645\u062D\u0641\u0648\u0638 \u06A9\u0631\u06CC\u06BA")) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(this, Loc.t(this, "Name cannot be empty", "\u0646\u0627\u0645 \u062E\u0627\u0644\u06CC \u0646\u06C1\u06CC\u06BA \u06C1\u0648 \u0633\u06A9\u062A\u0627"), Toast.LENGTH_SHORT).show()
                } else {
                    saveNewPartyName(newName)
                }
            }
            .setNegativeButton(Loc.t(this, "Cancel", "\u0645\u0646\u0633\u0648\u062E \u06A9\u0631\u06CC\u06BA"), null)
            .show()
    }

    private fun saveNewPartyName(newName: String) {
        lifecycleScope.launch {
            try {
                val db = PosDatabase.get(this@PartyTransactionActivity)
                var found = false

                if (isCustomer) {
                    db.customerDao().find(partyId)?.let { customer ->
                        found = true
                        db.customerDao().update(customer.copy(name = newName))
                    }
                } else {
                    db.supplierDao().find(partyId)?.let { supplier ->
                        found = true
                        db.supplierDao().update(supplier.copy(name = newName))
                    }
                }

                if (found) {
                    partyName = newName
                    partyNameLabel.text = newName

                    Toast.makeText(
                        this@PartyTransactionActivity,
                        Loc.t(this@PartyTransactionActivity, "Name updated", "\u0646\u0627\u0645 \u0627\u067E \u0688\u06CC\u0679 \u06C1\u0648 \u06AF\u06CC\u0627"),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@PartyTransactionActivity,
                        Loc.t(this@PartyTransactionActivity, "Party not found", "\u067E\u0627\u0631\u0679\u06CC \u0646\u06C1\u06CC\u06BA \u0645\u0644\u06CC"),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@PartyTransactionActivity,
                    "Could not update name: ${e.message ?: "unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun loadTransactions() {
        listContainer.removeAllViews()
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PartyTransactionActivity)
            val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())

            if (isCustomer) {
                val sales = db.saleDao().salesByCustomer(partyId).sortedByDescending { it.createdAt }
                if (sales.isEmpty()) {
                    listContainer.addView(placeholderCard(Loc.t(this@PartyTransactionActivity, "No transactions yet", "\u0627\u0628\u06BE\u06CC \u062A\u06A9 \u06A9\u0648\u0626\u06CC \u0644\u06CC\u0646 \u062F\u06CC\u0646 \u0646\u06C1\u06CC\u06BA \u06C1\u06D2")))
                } else {
                    sales.forEach { s ->
                        listContainer.addView(row(
                            amount = s.total,
                            dateText = fmt.format(Date(s.createdAt)),
                            typeLabel = Loc.t(this@PartyTransactionActivity, "Sale", "\u0633\u06CC\u0644"),
                            status = s.status,
                            accent = green,
                            emoji = "\uD83D\uDED2"
                        ) {
                            startActivity(Intent(this@PartyTransactionActivity, SaleActivity::class.java).apply {
                                putExtra("invoice", s.invoice) // ADJUST-EXTRA-KEY
                            })
                        })
                    }
                }
            } else {
                val purchases = db.purchaseDao().purchasesBySupplier(partyId).sortedByDescending { it.createdAt }
                if (purchases.isEmpty()) {
                    listContainer.addView(placeholderCard(Loc.t(this@PartyTransactionActivity, "No transactions yet", "\u0627\u0628\u06BE\u06CC \u062A\u06A9 \u06A9\u0648\u0626\u06CC \u0644\u06CC\u0646 \u062F\u06CC\u0646 \u0646\u06C1\u06CC\u06BA \u06C1\u06D2")))
                } else {
                    purchases.forEach { p ->
                        listContainer.addView(row(
                            amount = p.total,
                            dateText = fmt.format(Date(p.createdAt)),
                            typeLabel = Loc.t(this@PartyTransactionActivity, "Purchase", "\u062E\u0631\u06CC\u062F\u0627\u0631\u06CC"),
                            status = p.status,
                            accent = orange,
                            emoji = "\uD83E\uDDFE"
                        ) {
                            startActivity(Intent(this@PartyTransactionActivity, PurchaseActivity::class.java).apply {
                                putExtra("billNo", p.billNo) // ADJUST-EXTRA-KEY
                            })
                        })
                    }
                }
            }
        }
    }

    private fun row(amount: Double, dateText: String, typeLabel: String, status: String, accent: String, emoji: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 14, 18, 14)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(cardWhite))
                cornerRadius = 16f
                setStroke(1, Color.parseColor(cardBorder))
            }
            elevation = 2f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 10) }
            isClickable = true
            setOnClickListener { onClick() }

            addView(TextView(this@PartyTransactionActivity).apply {
                text = emoji
                textSize = 16f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(accent)) }
                width = (38 * resources.displayMetrics.density).toInt()
                height = (38 * resources.displayMetrics.density).toInt()
            })

            val infoCol = LinearLayout(this@PartyTransactionActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 0, 12, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            infoCol.addView(TextView(this@PartyTransactionActivity).apply {
                text = dateText
                textSize = 13.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(textDark))
            })
            infoCol.addView(TextView(this@PartyTransactionActivity).apply {
                text = typeLabel + if (status == "returned") "  \u2022  " + Loc.t(this@PartyTransactionActivity, "Returned", "\u0648\u0627\u067E\u0633") else ""
                textSize = 11f
                setTextColor(Color.parseColor(if (status == "returned") red else labelGray))
            })
            addView(infoCol)

            addView(TextView(this@PartyTransactionActivity).apply {
                text = "Rs %.2f".format(amount)
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(accent))
            })
        }
    }

    private fun placeholderCard(text: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(20, 30, 20, 30)
        background = GradientDrawable().apply {
            setColor(Color.parseColor(cardWhite))
            cornerRadius = 16f
            setStroke(1, Color.parseColor(cardBorder))
        }
        addView(TextView(this@PartyTransactionActivity).apply {
            this.text = text
            setTextColor(Color.parseColor(labelGray))
            textSize = 13f
            gravity = Gravity.CENTER
        })
    }
}
