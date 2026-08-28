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
import com.grocerypos.v11.SyncQueueHelper
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Opened from PartyDashboardActivity when the user taps a party in the Parties tab.
 * Shows ONLY that party's own transactions (sales if customer, purchases if supplier),
 * newest first. Tapping a row shows a read-only "Billed Items" dialog for that specific
 * sale/purchase — just the items on that bill, not the full edit screen.
 *
 * Expected intent extras:
 *   partyId: Long, partyName: String, isCustomer: Boolean
 *
 * *** CHANGE *** — previously tapping a row opened the full SaleActivity/PurchaseActivity
 * edit screen (with Add Item, quantity/rate fields, etc.), which let the user edit/extend
 * a bill from what was meant to be a simple history view. That has been replaced with
 * showBilledItemsDialog(), a read-only list of just the items on that transaction, built
 * the same way PartyReportsActivity's "Party Report by Item" reads line items
 * (saleDao().itemsForInvoice / purchaseDao().itemsForBill).
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
    private val blue = "#5B6EE8"

    private lateinit var listContainer: LinearLayout
    private lateinit var partyNameLabel: TextView
    private var partyId: Long = -1
    private var partyName: String = ""
    private var isCustomer: Boolean = true

    // ---- FIX (duplicate-row race) ----
    // loadTransactions() used to be called from BOTH onCreate() and onResume(). On a
    // fresh launch, Android calls onResume() immediately after onCreate() — before the
    // first coroutine's suspend DB query has returned. Both loadTransactions() calls did
    // removeAllViews() on the still-empty container, then each of their coroutines later
    // resumed and independently added a row for the same purchase/sale, producing a
    // transient duplicate that "settled" back to one row once nothing else reloaded.
    // Fixed by (a) loading only once per visible entry — onResume() alone, since it
    // already fires right after onCreate() for a fresh launch — and (b) cancelling any
    // in-flight load before starting a new one so two loads can never both mutate the list.
    private var loadJob: kotlinx.coroutines.Job? = null

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

        // ---- FIX: no longer loading here — onResume() (called right after onCreate on
        // a fresh launch, and again whenever the screen returns to the foreground) is now
        // the single place that triggers a load. See loadJob comment above for why. ----
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
                        val updated = customer.copy(name = newName)
                        db.customerDao().update(updated)
                        // FIX (sync): this rename path (via PartyTransactionActivity's Edit
                        // button) never enqueued a sync_queue entry, so a name changed here
                        // never reached Firestore even though the equivalent edit dialog in
                        // PartyActivity does enqueue correctly. Mirrors that same pattern.
                        SyncQueueHelper.enqueue(
                            db,
                            "customer",
                            SyncQueueHelper.customerEntityId(updated),
                            "update",
                            SyncQueueHelper.customerJson(updated)
                        )
                        SyncQueueHelper.trigger(this@PartyTransactionActivity)
                    }
                } else {
                    db.supplierDao().find(partyId)?.let { supplier ->
                        found = true
                        val updated = supplier.copy(name = newName)
                        db.supplierDao().update(updated)
                        SyncQueueHelper.enqueue(
                            db,
                            "supplier",
                            SyncQueueHelper.supplierEntityId(updated),
                            "update",
                            SyncQueueHelper.supplierJson(updated)
                        )
                        SyncQueueHelper.trigger(this@PartyTransactionActivity)
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
        // Cancel any load already in flight so its (possibly late-arriving) results can
        // never race with this one and duplicate rows — see loadJob comment above.
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            listContainer.removeAllViews()
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
                            // ---- CHANGE: was startActivity(SaleActivity, "invoice") which
                            // opened the full edit screen. Now shows just this sale's
                            // billed items in a read-only dialog. ----
                            showBilledItemsDialog(
                                isSale = true,
                                reference = s.invoice,
                                dateText = fmt.format(Date(s.createdAt)),
                                total = s.total,
                                status = s.status
                            )
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
                            // ---- CHANGE: was startActivity(PurchaseActivity, "billNo") which
                            // opened the full edit screen. Now shows just this purchase's
                            // billed items in a read-only dialog. ----
                            showBilledItemsDialog(
                                isSale = false,
                                reference = p.billNo,
                                dateText = fmt.format(Date(p.createdAt)),
                                total = p.total,
                                status = p.status
                            )
                        }
                        )
                    }
                }
            }
        }
    }

    // ---------------- Billed Items (read-only) ----------------
    // Shows just the line items on ONE sale (by invoice) or purchase (by billNo) — not
    // the full edit screen. Mirrors how PartyReportsActivity.showItemReport() reads line
    // items (saleDao().itemsForInvoice / purchaseDao().itemsForBill), but scoped to a
    // single transaction instead of aggregated across all of the party's transactions.
    private fun showBilledItemsDialog(isSale: Boolean, reference: String, dateText: String, total: Double, status: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PartyTransactionActivity)
            val accent = if (isSale) green else orange

            val outer = LinearLayout(this@PartyTransactionActivity).apply { orientation = LinearLayout.VERTICAL }

            outer.addView(TextView(this@PartyTransactionActivity).apply {
                text = if (isSale) Loc.t(this@PartyTransactionActivity, "Billed Items — Sale", "بل شدہ آئٹمز — سیل")
                else Loc.t(this@PartyTransactionActivity, "Billed Items — Purchase", "بل شدہ آئٹمز — خریداری")
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(textDark))
                setPadding(24, 24, 24, 4)
            })
            outer.addView(TextView(this@PartyTransactionActivity).apply {
                text = dateText + if (status == "returned") "  \u2022  " + Loc.t(this@PartyTransactionActivity, "Returned", "\u0648\u0627\u067E\u0633") else ""
                textSize = 12f
                setTextColor(Color.parseColor(if (status == "returned") red else labelGray))
                setPadding(24, 0, 24, 12)
            })

            val body = LinearLayout(this@PartyTransactionActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 0, 24, 12)
            }
            val scroll = ScrollView(this@PartyTransactionActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (350 * resources.displayMetrics.density).toInt()
                )
                addView(body)
            }
            outer.addView(scroll)

            var itemCount = 0

            if (isSale) {
                val items = db.saleDao().itemsForInvoice(reference)
                itemCount = items.size
                if (items.isEmpty()) {
                    body.addView(emptyText(Loc.t(this@PartyTransactionActivity, "No items found", "\u06A9\u0648\u0626\u06CC \u0622\u0626\u0679\u0645 \u0646\u06C1\u06CC\u06BA \u0645\u0644\u0627")))
                } else {
                    items.forEach { it ->
                        body.addView(billedItemRow(it.product, formatQty(it.qty.toDouble()), it.amount, accent))
                    }
                }
            } else {
                val items = db.purchaseDao().itemsForBill(reference)
                itemCount = items.size
                if (items.isEmpty()) {
                    body.addView(emptyText(Loc.t(this@PartyTransactionActivity, "No items found", "\u06A9\u0648\u0626\u06CC \u0622\u0626\u0679\u0645 \u0646\u06C1\u06CC\u06BA \u0645\u0644\u0627")))
                } else {
                    items.forEach { it ->
                        val productName = db.productDao().find(it.barcode)?.name ?: it.barcode
                        body.addView(billedItemRow(productName, formatQty(it.qty.toDouble()), it.amount, accent))
                    }
                }
            }

            if (itemCount > 0) {
                body.addView(View(this@PartyTransactionActivity).apply {
                    setBackgroundColor(Color.parseColor(cardBorder))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                        setMargins(0, 8, 0, 8)
                    }
                })
                body.addView(LinearLayout(this@PartyTransactionActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(4, 6, 4, 6)
                    addView(TextView(this@PartyTransactionActivity).apply {
                        text = Loc.t(this@PartyTransactionActivity, "Total", "\u06A9\u0644")
                        textSize = 14f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(Color.parseColor(textDark))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(TextView(this@PartyTransactionActivity).apply {
                        text = "Rs %.2f".format(total)
                        textSize = 14f
                        gravity = Gravity.END
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(Color.parseColor(accent))
                    })
                })
            }

            AlertDialog.Builder(this@PartyTransactionActivity)
                .setView(outer)
                .setPositiveButton(Loc.t(this@PartyTransactionActivity, "Close", "\u0628\u0646\u062F \u06A9\u0631\u06CC\u06BA"), null)
                .show()
        }
    }

    private fun billedItemRow(product: String, qtyText: String, amount: Double, accent: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 8, 4, 8)
            addView(LinearLayout(this@PartyTransactionActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@PartyTransactionActivity).apply {
                    text = product
                    textSize = 13.5f
                    setTextColor(Color.parseColor(textDark))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(TextView(this@PartyTransactionActivity).apply {
                    text = Loc.t(this@PartyTransactionActivity, "Qty", "مقدار") + ": $qtyText"
                    textSize = 11.5f
                    setTextColor(Color.parseColor(labelGray))
                })
            })
            addView(TextView(this@PartyTransactionActivity).apply {
                text = "Rs %.2f".format(amount)
                textSize = 13.5f
                gravity = Gravity.END
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(accent))
            })
        }
    }

    private fun formatQty(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

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

    private fun emptyText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor(labelGray))
            textSize = 13f
            setPadding(0, 6, 0, 6)
        }
    }
}
