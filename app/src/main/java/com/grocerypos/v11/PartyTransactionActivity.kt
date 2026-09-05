package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.grocerypos.v11.CashTransaction
import com.grocerypos.v11.Payment
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Product
import com.grocerypos.v11.PurchaseActivity
import com.grocerypos.v11.PurchaseItem
import com.grocerypos.v11.PurchaseRepository
import com.grocerypos.v11.RoomSaleRepository
import com.grocerypos.v11.SaleActivity
import com.grocerypos.v11.SaleItem
import com.grocerypos.v11.SyncQueueHelper
import com.grocerypos.v11.smallestPerUnitOf
import com.grocerypos.v11.smallestQty
import com.grocerypos.v11.smallestUnitFactor
import com.grocerypos.v11.toSmallestUnits
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Opened from PartyDashboardActivity when the user taps a party in the Parties tab.
 * Shows ONLY that party's own transactions (sales if customer, purchases if supplier),
 * newest first. Tapping a row shows a "Billed Items" dialog for that specific sale/
 * purchase — the items on that bill, each editable (qty/rate) and deletable, without
 * opening the full SaleActivity/PurchaseActivity edit screen.
 *
 * Expected intent extras:
 *   partyId: Long, partyName: String, isCustomer: Boolean
 *
 * *** CHANGE *** — billed items dialog is no longer read-only. Each line item now has
 * Edit and Delete actions:
 *   - Edit: change qty/rate for that line. Adjusts product stock by the delta, updates
 *     the parent Sale/Purchase total, and adjusts the party's balance by the same delta
 *     (so credit owed reflects the corrected bill).
 *   - Delete: removes the line. Reverses its stock effect, subtracts its amount from the
 *     parent total and the party balance. If it was the last item on the bill, prompts
 *     to delete the whole sale/purchase too.
 * All of the above enqueue sync_queue entries (product/sale-or-purchase/party) the same
 * way the Edit Party Name flow already does, via SyncQueueHelper.
 *
 * Edit Party Name button uses customerDao()/supplierDao() (picked via isCustomer) — each
 * needs a suspend fun find(id: Long): Customer?/Supplier? and the existing suspend fun
 * update(...). find() was added to both DAOs in Database.kt alongside this change.
 *
 * Manifest registration required:
 *   <activity android:name=".ui.PartyTransactionActivity" android:exported="false" />
 */
/** FIX (#8 — dangerous editing pattern): thrown from inside a db.withTransaction block
 * below to abort it cleanly (Room rolls back every write made so far in that block)
 * when stock turns out to be insufficient mid-way through — caught just outside to
 * show the normal user-facing toast instead of the generic "unknown error" one. */
private class InsufficientStockException(message: String) : Exception(message)

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
        // NEW: "Receive Payment" (customer) / "Make Payment" (supplier) — previously there
        // was no way at all to record money actually changing hands against the running
        // balance; the only entries possible were full sale/purchase bills. This opens a
        // small dialog (amount, cash/bank, optional note) that adjusts the balance directly.
        headerRow.addView(TextView(this).apply {
            text = "  \uD83D\uDCB0  " + (if (isCustomer) Loc.t(this@PartyTransactionActivity, "Receive Payment", "\u0627\u062F\u0627\u0626\u06CC\u06AF\u06CC \u0648\u0635\u0648\u0644 \u06A9\u0631\u06CC\u06BA")
                else Loc.t(this@PartyTransactionActivity, "Make Payment", "\u0627\u062F\u0627\u0626\u06CC\u06AF\u06CC \u06A9\u0631\u06CC\u06BA"))
            textSize = 12.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(if (isCustomer) green else orange))
                cornerRadius = 30f
            }
            setPadding(22, 12, 22, 12)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginStart = 12
            layoutParams = lp
            setOnClickListener { showPaymentDialog() }
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

        // NEW: dashboard "+" quick menu's "Payment Received"/"Payment Made" launches this
        // screen with openPayment=true after the user already picked the party, so open the
        // payment dialog immediately instead of making them tap the header button again.
        // Showing an AlertDialog here doesn't trigger onPause/onResume of this activity, so
        // this only runs once per launch (never re-fires when the dialog itself is dismissed).
        if (intent.getBooleanExtra("openPayment", false)) {
            showPaymentDialog()
        }
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
                        SyncQueueHelper.enqueueCustomer(db, updated)
                        SyncQueueHelper.trigger(this@PartyTransactionActivity)
                    }
                } else {
                    db.supplierDao().find(partyId)?.let { supplier ->
                        found = true
                        val updated = supplier.copy(name = newName)
                        db.supplierDao().update(updated)
                        SyncQueueHelper.enqueueSupplier(db, updated)
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

    // ---------------- Receive Payment / Make Payment ----------------

    // NEW: small dialog to record money actually paid/received against this party's
    // balance, without needing a full sale or purchase bill.
    private fun showPaymentDialog() {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, padding, padding, padding) }

        val amountInput = EditText(this).apply {
            hint = Loc.t(this@PartyTransactionActivity, "Amount", "\u0631\u0642\u0645")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        col.addView(amountInput)

        val methodSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@PartyTransactionActivity, android.R.layout.simple_spinner_dropdown_item, listOf("cash", "bank"))
        }
        col.addView(methodSpinner)

        val noteInput = EditText(this).apply {
            hint = Loc.t(this@PartyTransactionActivity, "Note (optional)", "\u0646\u0648\u0679 (\u0627\u062E\u062A\u06CC\u0627\u0631\u06CC)")
        }
        col.addView(noteInput)

        AlertDialog.Builder(this)
            .setTitle(if (isCustomer) Loc.t(this, "Receive Payment", "\u0627\u062F\u0627\u0626\u06CC\u06AF\u06CC \u0648\u0635\u0648\u0644 \u06A9\u0631\u06CC\u06BA")
                else Loc.t(this, "Make Payment", "\u0627\u062F\u0627\u0626\u06CC\u06AF\u06CC \u06A9\u0631\u06CC\u06BA"))
            .setView(col)
            .setPositiveButton(Loc.t(this, "Save", "\u0645\u062D\u0641\u0648\u0638 \u06A9\u0631\u06CC\u06BA")) { _, _ ->
                val amt = amountInput.text.toString().toDoubleOrNull()
                if (amt == null || amt <= 0.0) {
                    Toast.makeText(this, Loc.t(this, "Enter a valid amount", "\u0635\u062D\u06CC\u062D \u0631\u0642\u0645 \u0644\u06A9\u06BE\u06CC\u06BA"), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                savePayment(amt, methodSpinner.selectedItem?.toString() ?: "cash", noteInput.text.toString().trim())
            }
            .setNegativeButton(Loc.t(this, "Cancel", "\u0645\u0646\u0633\u0648\u062E \u06A9\u0631\u06CC\u06BA"), null)
            .show()
    }

    private fun savePayment(amount: Double, method: String, note: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PartyTransactionActivity)
            val partyType = if (isCustomer) "customer" else "supplier"
            val reference = "manual-$partyType-$partyId-${System.currentTimeMillis()}"
            val reasonText = (if (isCustomer) "Payment received from $partyName" else "Payment made to $partyName") +
                if (note.isNotEmpty()) " | $note" else ""

            val payment = Payment(reference = reference, partyType = partyType, partyId = partyId, amount = amount, method = method, note = note)
            val paymentId = db.paymentDao().insert(payment)
            SyncQueueHelper.enqueuePayment(db, payment.copy(id = paymentId))

            // A customer paying us reduces what they owe (balance goes down); us paying a
            // supplier reduces what we owe them — both are a negative adjustment.
            if (isCustomer) SyncQueueHelper.adjustCustomerBalance(db, partyId, -amount)
            else SyncQueueHelper.adjustSupplierBalance(db, partyId, -amount)

            val cashTx = CashTransaction(type = if (isCustomer) "IN" else "OUT", method = method.lowercase(), amount = amount, reason = reasonText, reference = reference)
            val cashTxId = db.cashTransactionDao().insert(cashTx)
            SyncQueueHelper.enqueueCashTransaction(db, cashTx.copy(id = cashTxId))
            SyncQueueHelper.trigger(this@PartyTransactionActivity)

            Toast.makeText(this@PartyTransactionActivity, Loc.t(this@PartyTransactionActivity, "Payment saved", "\u0627\u062F\u0627\u0626\u06CC\u06AF\u06CC \u0645\u062D\u0641\u0648\u0638 \u06C1\u0648 \u06AF\u0626\u06CC"), Toast.LENGTH_SHORT).show()
            loadTransactions()
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

            // NEW: standalone payments recorded via the Receive Payment/Make Payment button
            // — merged chronologically with the sale/purchase bills below so the full money
            // trail for this party shows in one list instead of only ever showing bills.
            val payments = db.paymentDao().listByParty(if (isCustomer) "customer" else "supplier", partyId)

            data class Entry(val createdAt: Long, val view: LinearLayout)
            val entries = mutableListOf<Entry>()

            if (isCustomer) {
                db.saleDao().salesByCustomer(partyId).forEach { s ->
                    entries.add(Entry(s.createdAt, row(
                        amount = s.total,
                        dateText = fmt.format(Date(s.createdAt)),
                        typeLabel = Loc.t(this@PartyTransactionActivity, "Sale", "\u0633\u06CC\u0644"),
                        status = s.status,
                        accent = green,
                        emoji = "\uD83D\uDED2"
                    ) {
                        // ---- CHANGE: opens billed items dialog with edit/delete
                        // instead of the full SaleActivity edit screen. ----
                        showBilledItemsDialog(isSale = true, invoice = s.invoice, billNo = "")
                    }))
                }
            } else {
                db.purchaseDao().purchasesBySupplier(partyId).forEach { p ->
                    entries.add(Entry(p.createdAt, row(
                        amount = p.total,
                        dateText = fmt.format(Date(p.createdAt)),
                        typeLabel = Loc.t(this@PartyTransactionActivity, "Purchase", "\u062E\u0631\u06CC\u062F\u0627\u0631\u06CC"),
                        status = p.status,
                        accent = orange,
                        emoji = "\uD83E\uDDFE"
                    ) {
                        // ---- CHANGE: opens billed items dialog with edit/delete
                        // instead of the full PurchaseActivity edit screen. ----
                        showBilledItemsDialog(isSale = false, invoice = "", billNo = p.billNo)
                    }))
                }
            }
            payments.forEach { pay ->
                val label = (if (isCustomer) Loc.t(this@PartyTransactionActivity, "Payment Received", "\u0627\u062F\u0627\u0626\u06CC\u06AF\u06CC \u0648\u0635\u0648\u0644 \u06C1\u0648\u0626\u06CC")
                    else Loc.t(this@PartyTransactionActivity, "Payment Made", "\u0627\u062F\u0627\u0626\u06CC\u06AF\u06CC \u06C1\u0648\u0626\u06CC")) +
                    "  \u2022  " + pay.method.uppercase() + (if (pay.note.isNotEmpty()) "  \u2022  ${pay.note}" else "")
                entries.add(Entry(pay.createdAt, row(
                    amount = pay.amount,
                    dateText = fmt.format(Date(pay.createdAt)),
                    typeLabel = label,
                    status = "",
                    accent = teal,
                    emoji = "\uD83D\uDCB5"
                ) { }))
            }

            if (entries.isEmpty()) {
                listContainer.addView(placeholderCard(Loc.t(this@PartyTransactionActivity, "No transactions yet", "\u0627\u0628\u06BE\u06CC \u062A\u06A9 \u06A9\u0648\u0626\u06CC \u0644\u06CC\u0646 \u062F\u06CC\u0646 \u0646\u06C1\u06CC\u06BA \u06C1\u06D2")))
            } else {
                entries.sortedByDescending { it.createdAt }.forEach { listContainer.addView(it.view) }
            }
        }
    }

    // ---------------- Billed Items (editable / deletable) ----------------
    // Shows the line items on ONE sale (by invoice) or purchase (by billNo). Each row can
    // be edited (qty/rate) or deleted, without opening the full Sale/PurchaseActivity edit
    // screen. Reloads itself after every change so totals/rows stay in sync, and reloads
    // the parent transaction list underneath once the dialog is dismissed.
    private fun showBilledItemsDialog(isSale: Boolean, invoice: String, billNo: String) {
        val reference = if (isSale) invoice else billNo
        var dialog: AlertDialog? = null

        fun rebuildAndShow() {
            lifecycleScope.launch {
                val db = PosDatabase.get(this@PartyTransactionActivity)
                val accent = if (isSale) green else orange

                // Bill may have been deleted entirely (last item removed) — bail out.
                val stillExists = if (isSale) db.saleDao().findSale(reference) != null
                else db.purchaseDao().findPurchase(reference) != null
                if (!stillExists) {
                    dialog?.dismiss()
                    loadTransactions()
                    return@launch
                }

                val sale = if (isSale) db.saleDao().findSale(reference) else null
                val purchase = if (!isSale) db.purchaseDao().findPurchase(reference) else null
                val total = sale?.total ?: purchase?.total ?: 0.0
                val status = sale?.status ?: purchase?.status ?: "active"
                val createdAt = sale?.createdAt ?: purchase?.createdAt ?: System.currentTimeMillis()
                val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
                val dateText = fmt.format(Date(createdAt))

                val outer = LinearLayout(this@PartyTransactionActivity).apply { orientation = LinearLayout.VERTICAL }

                outer.addView(TextView(this@PartyTransactionActivity).apply {
                    text = if (isSale) Loc.t(this@PartyTransactionActivity, "Billed Items \u2014 Sale", "\u0628\u0644 \u0634\u062F\u06C1 \u0622\u0626\u0679\u0645\u0632 \u2014 \u0633\u06CC\u0644")
                    else Loc.t(this@PartyTransactionActivity, "Billed Items \u2014 Purchase", "\u0628\u0644 \u0634\u062F\u06C1 \u0622\u0626\u0679\u0645\u0632 \u2014 \u062E\u0631\u06CC\u062F\u0627\u0631\u06CC")
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
                        (400 * resources.displayMetrics.density).toInt()
                    )
                    addView(body)
                }
                outer.addView(scroll)

                var itemCount = 0
                var totalQty = 0.0
                // NEW: kept so the "Delete bill" action below can pass the exact item
                // snapshot into SaleRepository/PurchaseRepository's delete function —
                // same tested reversal logic (stock/balance/cash) the full edit screens use.
                var saleItemsSnapshot: List<SaleItem> = emptyList()
                var purchaseItemsSnapshot: List<PurchaseItem> = emptyList()

                if (isSale) {
                    val items = db.saleDao().itemsForInvoice(reference)
                    itemCount = items.size
                    totalQty = items.sumOf { it.qty }
                    saleItemsSnapshot = items
                    if (items.isEmpty()) {
                        body.addView(emptyText(Loc.t(this@PartyTransactionActivity, "No items found", "\u06A9\u0648\u0626\u06CC \u0622\u0626\u0679\u0645 \u0646\u06C1\u06CC\u06BA \u0645\u0644\u0627")))
                    } else {
                        items.forEach { item ->
                            body.addView(billedItemRow(
                                productName = item.product,
                                qtyText = formatQty(item.qty),
                                amount = item.amount,
                                accent = accent,
                                onEdit = { promptEditSaleItem(item, sale!!) { rebuildAndShow() } },
                                onDelete = { confirmDeleteSaleItem(item, sale!!) { rebuildAndShow() } }
                            ))
                        }
                    }
                } else {
                    val items = db.purchaseDao().itemsForBill(reference)
                    itemCount = items.size
                    totalQty = items.sumOf { it.qty }
                    purchaseItemsSnapshot = items
                    if (items.isEmpty()) {
                        body.addView(emptyText(Loc.t(this@PartyTransactionActivity, "No items found", "\u06A9\u0648\u0626\u06CC \u0622\u0626\u0679\u0645 \u0646\u06C1\u06CC\u06BA \u0645\u0644\u0627")))
                    } else {
                        items.forEach { item ->
                            val productName = db.productDao().find(item.barcode)?.name ?: item.barcode
                            body.addView(billedItemRow(
                                productName = productName,
                                qtyText = formatQty(item.qty),
                                amount = item.amount,
                                accent = accent,
                                onEdit = { promptEditPurchaseItem(item, purchase!!) { rebuildAndShow() } },
                                onDelete = { confirmDeletePurchaseItem(item, purchase!!) { rebuildAndShow() } }
                            ))
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
                    // NEW: Total Qty row above the Total amount row, matching the reference
                    // bill-view layout (qty + subtotal shown together as a summary strip).
                    body.addView(LinearLayout(this@PartyTransactionActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(4, 0, 4, 2)
                        addView(TextView(this@PartyTransactionActivity).apply {
                            text = Loc.t(this@PartyTransactionActivity, "Total Qty", "\u06A9\u0644 \u0645\u0642\u062F\u0627\u0631") + ": ${formatQty(totalQty)}"
                            textSize = 12f
                            setTextColor(Color.parseColor(labelGray))
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        })
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

                dialog?.dismiss()
                val builder = AlertDialog.Builder(this@PartyTransactionActivity)
                    .setView(outer)
                    .setPositiveButton(Loc.t(this@PartyTransactionActivity, "Close", "\u0628\u0646\u062F \u06A9\u0631\u06CC\u06BA")) { _, _ ->
                        loadTransactions()
                    }
                    .setOnCancelListener { loadTransactions() }
                // NEW: "Edit bill" opens the full Sale/PurchaseActivity screen (already
                // handles reloading every line + party + payment for editing) and
                // "Delete bill" reuses the same repository delete used by the History
                // screens (reverses stock/balance/cash in one transaction) — both only
                // offered when the bill actually has items to act on.
                if (itemCount > 0) {
                    builder.setNegativeButton(Loc.t(this@PartyTransactionActivity, "Edit bill", "\u0628\u0644 \u062A\u0631\u0645\u06CC\u0645 \u06A9\u0631\u06CC\u06BA")) { _, _ ->
                        val intent = if (isSale) {
                            Intent(this@PartyTransactionActivity, SaleActivity::class.java)
                                .putExtra(SaleActivity.EXTRA_INVOICE, reference)
                        } else {
                            Intent(this@PartyTransactionActivity, PurchaseActivity::class.java)
                                .putExtra(PurchaseActivity.EXTRA_BILL_NO, reference)
                        }
                        startActivity(intent)
                    }
                    builder.setNeutralButton(Loc.t(this@PartyTransactionActivity, "Delete bill", "\u0628\u0644 \u062D\u0630\u0641 \u06A9\u0631\u06CC\u06BA")) { _, _ ->
                        android.app.AlertDialog.Builder(this@PartyTransactionActivity)
                            .setTitle(Loc.t(this@PartyTransactionActivity, "Delete bill", "\u0628\u0644 \u062D\u0630\u0641 \u06A9\u0631\u06CC\u06BA"))
                            .setMessage(Loc.t(this@PartyTransactionActivity, "This will remove the whole bill and reverse its stock and balance effect. Continue?", "\u0627\u0633 \u0633\u06D2 \u067E\u0648\u0631\u0627 \u0628\u0644 \u062E\u062A\u0645 \u06C1\u0648 \u062C\u0627\u0626\u06D2 \u06AF\u0627 \u0627\u0648\u0631 \u0627\u0633 \u06A9\u0627 \u0633\u0679\u0627\u06A9 \u0627\u0648\u0631 \u0628\u0642\u0627\u06CC\u0627 \u0627\u062B\u0631 \u0648\u0627\u067E\u0633 \u06C1\u0648\u06AF\u0627\u06D4 \u062C\u0627\u0631\u06CC \u0631\u06A9\u06BE\u06CC\u06BA\u061F"))
                            .setPositiveButton(Loc.t(this@PartyTransactionActivity, "Delete", "\u062D\u0630\u0641 \u06A9\u0631\u06CC\u06BA")) { _, _ ->
                                lifecycleScope.launch {
                                    try {
                                        if (isSale) {
                                            RoomSaleRepository(db, applicationContext).deleteSale(reference, sale, saleItemsSnapshot)
                                        } else {
                                            PurchaseRepository(db, applicationContext).deletePurchase(reference, purchase, purchaseItemsSnapshot)
                                        }
                                        Toast.makeText(this@PartyTransactionActivity, Loc.t(this@PartyTransactionActivity, "Bill deleted", "\u0628\u0644 \u062D\u0630\u0641 \u06C1\u0648 \u06AF\u06CC\u0627"), Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(this@PartyTransactionActivity, e.message ?: "Delete failed", Toast.LENGTH_SHORT).show()
                                    }
                                    loadTransactions()
                                }
                            }
                            .setNegativeButton(Loc.t(this@PartyTransactionActivity, "Cancel", "\u0645\u0646\u0633\u0648\u062E \u06A9\u0631\u06CC\u06BA"), null)
                            .show()
                    }
                }
                dialog = builder.show()
            }
        }

        rebuildAndShow()
    }

    // ---------------- Sale item edit / delete ----------------

    private fun promptEditSaleItem(item: SaleItem, sale: com.grocerypos.v11.Sale, onDone: () -> Unit) {
        val (qtyInput, rateInput, container) = qtyRateEditDialogView(item.qty, item.unitPrice)
        AlertDialog.Builder(this)
            .setTitle(item.product)
            .setView(container)
            .setPositiveButton(Loc.t(this, "Save", "\u0645\u062D\u0641\u0648\u0638 \u06A9\u0631\u06CC\u06BA")) { _, _ ->
                val newQty = qtyInput.text.toString().toDoubleOrNull()
                val newRate = rateInput.text.toString().toDoubleOrNull()
                if (newQty == null || newQty <= 0 || newRate == null || newRate < 0) {
                    Toast.makeText(this, Loc.t(this, "Enter a valid qty and rate", "\u062F\u0631\u0633\u062A \u0645\u0642\u062F\u0627\u0631 \u0627\u0648\u0631 \u0631\u06CC\u0679 \u062F\u0631\u062C \u06A9\u0631\u06CC\u06BA"), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                applySaleItemEdit(item, sale, newQty, newRate, onDone)
            }
            .setNegativeButton(Loc.t(this, "Cancel", "\u0645\u0646\u0633\u0648\u062E \u06A9\u0631\u06CC\u06BA"), null)
            .show()
    }

    private fun applySaleItemEdit(item: SaleItem, sale: com.grocerypos.v11.Sale, newQty: Double, newRate: Double, onDone: () -> Unit) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PartyTransactionActivity)
            try {
                // FIX (#8 — dangerous editing pattern): the item row change, the parent
                // Sale total/paid change, the stock change, the customer-balance change
                // and the linked cash-transaction change are now all one Room
                // transaction. Previously these were separate sequential writes — if the
                // app crashed/got killed partway through (e.g. right after stock updated
                // but before the sale row was), the database was left inconsistent with
                // no way to recover. A failure now rolls back everything above, exactly
                // as SaleRepository.saveSale()/deleteSale() already do for the full-bill
                // edit/delete screens.
                db.withTransaction {
                    val product = db.productDao().find(item.barcode)
                    val oldQty = item.qty

                    // FIX (historical unit conversion bug): the OLD portion must be
                    // reversed using the factor that was actually in effect when this
                    // line was sold (item.conversionFactor via smallestQty()), not the
                    // product's CURRENT unit config — otherwise editing a product's unit
                    // ladder after the fact would silently reverse the wrong quantity.
                    // The NEW portion legitimately uses the CURRENT config, since it's a
                    // fresh decision being made right now. Net delta is what's actually
                    // applied to stock; the two are computed separately instead of a
                    // simple qty delta so a factor change between old and new can't
                    // silently corrupt the result either way.
                    val oldSmallest = item.smallestQty(product)
                    val newSmallest = product?.toSmallestUnits(newQty, item.unit.ifBlank { product.unit }) ?: newQty
                    val netSmallestDelta = newSmallest - oldSmallest

                    // Stock: a sale decreases stock, so selling MORE (net positive) must
                    // decrease stock further; selling LESS gives stock back.
                    if (product != null && netSmallestDelta != 0.0) {
                        if (netSmallestDelta > 0) {
                            val rows = SyncQueueHelper.decreaseProductStock(db, item.barcode, netSmallestDelta, "SALE_EDIT", sale.invoice)
                            if (rows == 0) {
                                throw InsufficientStockException(Loc.t(this@PartyTransactionActivity, "Not enough stock", "\u0627\u0633\u0679\u0627\u06A9 \u06A9\u0645 \u06C1\u06D2"))
                            }
                        } else {
                            SyncQueueHelper.increaseProductStock(db, item.barcode, -netSmallestDelta, "SALE_EDIT", sale.invoice)
                        }
                    }

                    val perUnitCost = if (oldQty != 0.0) item.cost / oldQty else 0.0
                    val newAmount = newQty * newRate
                    val newCost = perUnitCost * newQty
                    val updatedItem = item.copy(
                        qty = newQty, unitPrice = newRate, amount = newAmount, cost = newCost,
                        // Re-stamp with the CURRENT factor — this line now reflects "now", same
                        // reasoning as newSmallest above.
                        conversionFactor = product?.smallestPerUnitOf(item.unit) ?: item.conversionFactor
                    )
                    db.saleDao().updateItemRow(updatedItem)

                    val deltaAmount = newAmount - item.amount
                    val newTotal = sale.total + deltaAmount

                    // FIX (#9 — cash transaction consistency): if the bill's paid amount
                    // no longer fits under its new (possibly lower) total, cap it there
                    // and shrink the linked cash transaction by the same amount — instead
                    // of leaving "Paid" and Cash IN permanently stuck at the old total.
                    val newPaid = reconcilePaidAndCashRecords(db, sale.invoice, sale.paid, newTotal, isPurchase = false)

                    val updatedSale = sale.copy(
                        subtotal = sale.subtotal + deltaAmount,
                        total = newTotal,
                        paid = newPaid
                    )
                    db.saleDao().updateSale(updatedSale)

                    sale.customerId?.let { custId ->
                        SyncQueueHelper.adjustCustomerBalance(db, custId, deltaAmount)
                        db.customerDao().find(custId)?.let { c -> SyncQueueHelper.enqueueCustomer(db, c) }
                    }
                    if (product != null) {
                        db.productDao().find(item.barcode)?.let { p -> SyncQueueHelper.enqueueProduct(db, p) }
                    }
                    SyncQueueHelper.enqueueSale(db, updatedSale)
                }
                SyncQueueHelper.trigger(this@PartyTransactionActivity)

                onDone()
            } catch (e: InsufficientStockException) {
                Toast.makeText(this@PartyTransactionActivity, e.message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@PartyTransactionActivity, "Could not update item: ${e.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDeleteSaleItem(item: SaleItem, sale: com.grocerypos.v11.Sale, onDone: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(Loc.t(this, "Delete Item", "\u0622\u0626\u0679\u0645 \u0688\u06CC\u0644\u06CC\u0679 \u06A9\u0631\u06CC\u06BA"))
            .setMessage(item.product)
            .setPositiveButton(Loc.t(this, "Delete", "\u0688\u06CC\u0644\u06CC\u0679 \u06A9\u0631\u06CC\u06BA")) { _, _ -> applyDeleteSaleItem(item, sale, onDone) }
            .setNegativeButton(Loc.t(this, "Cancel", "\u0645\u0646\u0633\u0648\u062E \u06A9\u0631\u06CC\u06BA"), null)
            .show()
    }

    private fun applyDeleteSaleItem(item: SaleItem, sale: com.grocerypos.v11.Sale, onDone: () -> Unit) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PartyTransactionActivity)
            try {
                var deletedWholeSale = false
                // FIX (#8 — dangerous editing pattern): stock restore, item delete,
                // parent Sale update/delete, customer-balance change and the linked
                // cash-transaction change all now run as one Room transaction — see
                // applySaleItemEdit() above for why.
                db.withTransaction {
                    val product = db.productDao().find(item.barcode)

                    // Deleting a sold item gives the stock back — using the factor that
                    // was in effect when it was actually sold (see applySaleItemEdit above).
                    if (product != null) {
                        val deltaSmallest = item.smallestQty(product)
                        SyncQueueHelper.increaseProductStock(db, item.barcode, deltaSmallest, "SALE_ITEM_DELETE", sale.invoice)
                        db.productDao().find(item.barcode)?.let { p -> SyncQueueHelper.enqueueProduct(db, p) }
                    }

                    db.saleDao().deleteItemById(item.id)

                    val remaining = db.saleDao().itemCountForInvoice(sale.invoice)
                    if (remaining == 0) {
                        deletedWholeSale = true
                        db.saleDao().deleteSale(sale.invoice)
                        sale.customerId?.let { custId ->
                            SyncQueueHelper.adjustCustomerBalance(db, custId, -item.amount)
                            db.customerDao().find(custId)?.let { c -> SyncQueueHelper.enqueueCustomer(db, c) }
                        }
                        // FIX (#9 — cash transaction consistency): the whole sale is
                        // gone now, so its linked cash transaction must go with it —
                        // previously only db.saleDao().deleteSale() ran here, leaving a
                        // stale Cash IN entry behind forever (mirrors what
                        // SaleRepository.deleteSale() already does for the full-bill
                        // delete screen).
                        db.cashTransactionDao().deleteByReference(sale.invoice)
                        SyncQueueHelper.enqueueDelete(db, "sale", SyncQueueHelper.saleEntityId(sale))
                    } else {
                        val newTotal = sale.total - item.amount
                        val newPaid = reconcilePaidAndCashRecords(db, sale.invoice, sale.paid, newTotal, isPurchase = false)
                        val updatedSale = sale.copy(
                            subtotal = sale.subtotal - item.amount,
                            total = newTotal,
                            paid = newPaid
                        )
                        db.saleDao().updateSale(updatedSale)
                        sale.customerId?.let { custId ->
                            SyncQueueHelper.adjustCustomerBalance(db, custId, -item.amount)
                            db.customerDao().find(custId)?.let { c -> SyncQueueHelper.enqueueCustomer(db, c) }
                        }
                        SyncQueueHelper.enqueueSale(db, updatedSale)
                    }
                }
                SyncQueueHelper.trigger(this@PartyTransactionActivity)

                if (deletedWholeSale) {
                    Toast.makeText(this@PartyTransactionActivity, Loc.t(this@PartyTransactionActivity, "Sale deleted", "\u0633\u06CC\u0644 \u0688\u06CC\u0644\u06CC\u0679 \u06C1\u0648 \u06AF\u0626\u06CC"), Toast.LENGTH_SHORT).show()
                }

                onDone()
            } catch (e: Exception) {
                Toast.makeText(this@PartyTransactionActivity, "Could not delete item: ${e.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------------- Purchase item edit / delete ----------------

    private fun promptEditPurchaseItem(item: PurchaseItem, purchase: com.grocerypos.v11.Purchase, onDone: () -> Unit) {
        val (qtyInput, rateInput, container) = qtyRateEditDialogView(item.qty, item.unitCost)
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PartyTransactionActivity)
            val name = db.productDao().find(item.barcode)?.name ?: item.barcode
            AlertDialog.Builder(this@PartyTransactionActivity)
                .setTitle(name)
                .setView(container)
                .setPositiveButton(Loc.t(this@PartyTransactionActivity, "Save", "\u0645\u062D\u0641\u0648\u0638 \u06A9\u0631\u06CC\u06BA")) { _, _ ->
                    val newQty = qtyInput.text.toString().toDoubleOrNull()
                    val newRate = rateInput.text.toString().toDoubleOrNull()
                    if (newQty == null || newQty <= 0 || newRate == null || newRate < 0) {
                        Toast.makeText(this@PartyTransactionActivity, Loc.t(this@PartyTransactionActivity, "Enter a valid qty and rate", "\u062F\u0631\u0633\u062A \u0645\u0642\u062F\u0627\u0631 \u0627\u0648\u0631 \u0631\u06CC\u0679 \u062F\u0631\u062C \u06A9\u0631\u06CC\u06BA"), Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    applyPurchaseItemEdit(item, purchase, newQty, newRate, onDone)
                }
                .setNegativeButton(Loc.t(this@PartyTransactionActivity, "Cancel", "\u0645\u0646\u0633\u0648\u062E \u06A9\u0631\u06CC\u06BA"), null)
                .show()
        }
    }

    private fun applyPurchaseItemEdit(item: PurchaseItem, purchase: com.grocerypos.v11.Purchase, newQty: Double, newRate: Double, onDone: () -> Unit) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PartyTransactionActivity)
            try {
                // FIX (#8 — dangerous editing pattern): the item row change, the parent
                // Purchase total/paid change, the stock+cost change, the supplier-balance
                // change and the linked payment/cash-transaction change all now run as
                // one Room transaction — see applySaleItemEdit() above for why.
                db.withTransaction {
                    val product = db.productDao().find(item.barcode)
                    val oldQty = item.qty

                    // FIX (historical unit conversion bug): same reasoning as
                    // applySaleItemEdit() above — reverse the OLD portion using the
                    // factor frozen at purchase time, apply the NEW portion using the
                    // CURRENT product config, and only touch stock with the net delta.
                    val oldSmallest = item.smallestQty(product)
                    val newSmallest = product?.toSmallestUnits(newQty, item.unit.ifBlank { product.unit }) ?: newQty
                    val netSmallestDelta = newSmallest - oldSmallest
                    val newAmount = newQty * newRate

                    // FIX (#7 — purchase costing): this path used to only touch stock and
                    // never product.cost at all, so editing a purchase item's qty/rate here
                    // silently left the product's weighted-average cost stale — worse than
                    // PurchaseRepository's own reversal method (used by the full
                    // PurchaseActivity edit screen), which at least recomputes it. Now
                    // mirrors that same reversal-then-reapply approach: first remove this
                    // line's OLD contribution from the current average (reversePurchaseLineCost,
                    // using the factor/amount frozen at purchase time), then blend in the
                    // NEW line's contribution as a fresh weighted-average purchase
                    // (addPurchaseLineCost) — same math PurchaseRepository.savePurchase()
                    // uses for a brand-new line, applied here to just the one edited line
                    // instead of the whole bill.
                    if (product != null) {
                        // FIX (#7 — purchase costing safety): same guard as
                        // PurchaseRepository.reverseStockAndCostForItems() — if this
                        // line's own purchased quantity has already been drawn down
                        // below what's currently in stock (by a sale or another
                        // transaction since), reversing its cost contribution here
                        // would produce a wrong/zero cost instead of a real error.
                        // Refuse the edit rather than silently corrupting it.
                        if (oldSmallest > product.stock) {
                            throw InsufficientStockException(
                                Loc.t(this@PartyTransactionActivity,
                                    "Cannot edit: this item's stock has already been used elsewhere. Use a stock adjustment instead.",
                                    "\u0627\u06CC\u0688\u0679 \u0646\u06C1\u06CC\u06BA \u06C1\u0648 \u0633\u06A9\u062A\u0627: \u0627\u0633 \u0622\u0626\u0679\u0645 \u06A9\u0627 \u0627\u0633\u0679\u0627\u06A9 \u067E\u06C1\u0644\u06D2 \u06C1\u06CC \u06A9\u06C1\u06CC\u06BA \u0627\u0648\u0631 \u0627\u0633\u062A\u0639\u0645\u0627\u0644 \u06C1\u0648 \u0686\u06A9\u0627 \u06C1\u06D2\u06D4 \u0628\u062C\u0627\u0626\u06D2 \u0627\u0633 \u06A9\u06D2 \u0633\u0679\u0627\u06A9 \u0627\u06CC\u0688\u062C\u0633\u0679 \u0645\u0646\u0679 \u0627\u0633\u062A\u0639\u0645\u0627\u0644 \u06A9\u0631\u06CC\u06BA\u06D4")
                            )
                        }
                        val costAfterReversal = reversePurchaseLineCost(product, item, oldSmallest)
                        val stockAfterReversal = product.stock - oldSmallest
                        val productAfterReversal = product.copy(stock = stockAfterReversal, cost = costAfterReversal)
                        val finalCost = if (newSmallest > 0) {
                            addPurchaseLineCost(productAfterReversal, newSmallest, newAmount)
                        } else {
                            costAfterReversal
                        }

                        // Stock: a purchase increases stock, so buying MORE (net positive)
                        // adds more stock; buying less must remove stock (fails if already
                        // sold/used).
                        if (netSmallestDelta > 0) {
                            SyncQueueHelper.increaseProductStock(db, item.barcode, netSmallestDelta, "PURCHASE_EDIT", purchase.billNo, finalCost)
                        } else if (netSmallestDelta < 0) {
                            val rows = SyncQueueHelper.decreaseProductStock(db, item.barcode, -netSmallestDelta, "PURCHASE_EDIT", purchase.billNo, finalCost)
                            if (rows == 0) {
                                throw InsufficientStockException(Loc.t(this@PartyTransactionActivity, "Not enough stock to reduce", "\u06A9\u0645 \u06A9\u0631\u0646\u06D2 \u06A9\u06D2 \u0644\u06CC\u06D2 \u0627\u0633\u0679\u0627\u06A9 \u06A9\u0645 \u06C1\u06D2"))
                            }
                        }
                        SyncQueueHelper.updateProductCost(db, item.barcode, finalCost)
                    }

                    val updatedItem = item.copy(
                        qty = newQty, unitCost = newRate, amount = newAmount,
                        conversionFactor = product?.smallestPerUnitOf(item.unit) ?: item.conversionFactor
                    )
                    db.purchaseDao().updateItemRow(updatedItem)

                    val deltaAmount = newAmount - item.amount
                    val newTotal = purchase.total + deltaAmount

                    // FIX (#9 — cash transaction consistency): cap "paid" at the new
                    // total and shrink the linked payment + cash transaction by the same
                    // amount if it no longer fits — see applySaleItemEdit() above.
                    val newPaid = reconcilePaidAndCashRecords(db, purchase.billNo, purchase.paid, newTotal, isPurchase = true)

                    val updatedPurchase = purchase.copy(
                        subtotal = purchase.subtotal + deltaAmount,
                        total = newTotal,
                        paid = newPaid
                    )
                    db.purchaseDao().updatePurchase(updatedPurchase)

                    purchase.supplierId?.let { supId ->
                        SyncQueueHelper.adjustSupplierBalance(db, supId, deltaAmount)
                        db.supplierDao().find(supId)?.let { s -> SyncQueueHelper.enqueueSupplier(db, s) }
                    }
                    if (product != null) {
                        db.productDao().find(item.barcode)?.let { p -> SyncQueueHelper.enqueueProduct(db, p) }
                    }
                    SyncQueueHelper.enqueuePurchase(db, updatedPurchase)
                }
                SyncQueueHelper.trigger(this@PartyTransactionActivity)

                onDone()
            } catch (e: InsufficientStockException) {
                Toast.makeText(this@PartyTransactionActivity, e.message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@PartyTransactionActivity, "Could not update item: ${e.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDeletePurchaseItem(item: PurchaseItem, purchase: com.grocerypos.v11.Purchase, onDone: () -> Unit) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PartyTransactionActivity)
            val name = db.productDao().find(item.barcode)?.name ?: item.barcode
            AlertDialog.Builder(this@PartyTransactionActivity)
                .setTitle(Loc.t(this@PartyTransactionActivity, "Delete Item", "\u0622\u0626\u0679\u0645 \u0688\u06CC\u0644\u06CC\u0679 \u06A9\u0631\u06CC\u06BA"))
                .setMessage(name)
                .setPositiveButton(Loc.t(this@PartyTransactionActivity, "Delete", "\u0688\u06CC\u0644\u06CC\u0679 \u06A9\u0631\u06CC\u06BA")) { _, _ -> applyDeletePurchaseItem(item, purchase, onDone) }
                .setNegativeButton(Loc.t(this@PartyTransactionActivity, "Cancel", "\u0645\u0646\u0633\u0648\u062E \u06A9\u0631\u06CC\u06BA"), null)
                .show()
        }
    }

    private fun applyDeletePurchaseItem(item: PurchaseItem, purchase: com.grocerypos.v11.Purchase, onDone: () -> Unit) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PartyTransactionActivity)
            try {
                var deletedWholePurchase = false
                // FIX (#8 — dangerous editing pattern): stock+cost reversal, item
                // delete, parent Purchase update/delete, supplier-balance change and
                // the linked payment/cash-transaction change all now run as one Room
                // transaction — see applySaleItemEdit() above for why.
                db.withTransaction {
                    val product = db.productDao().find(item.barcode)

                    // Deleting a purchased item removes the stock it had added — using
                    // the factor that was in effect when it was actually purchased (see
                    // applyPurchaseItemEdit above) — fails if that stock has already been
                    // sold/used elsewhere.
                    if (product != null) {
                        val deltaSmallest = item.smallestQty(product)
                        // FIX (#7 — purchase costing): deleting a purchase line here used
                        // to only touch stock and never recompute product.cost at all —
                        // now removes this line's contribution from the weighted-average
                        // cost too, same as reversePurchaseLineCost()/PurchaseRepository's
                        // full-bill delete. Computed BEFORE the stock decrease below so the
                        // stock_movements row logged by decreaseProductStock() can carry
                        // this same already-known cost instead of a stale pre-reversal one.
                        val newCost = reversePurchaseLineCost(product, item, deltaSmallest)
                        val rows = SyncQueueHelper.decreaseProductStock(db, item.barcode, deltaSmallest, "PURCHASE_ITEM_DELETE", purchase.billNo, newCost)
                        if (rows == 0) {
                            throw InsufficientStockException(Loc.t(this@PartyTransactionActivity, "Cannot delete: stock already used", "ڈیلیٹ نہیں ہو سکتا: اسٹاک پہلے ہی استعمال ہو چکا"))
                        }
                        SyncQueueHelper.updateProductCost(db, item.barcode, newCost)
                        db.productDao().find(item.barcode)?.let { p -> SyncQueueHelper.enqueueProduct(db, p) }
                    }

                    db.purchaseDao().deleteItemById(item.id)

                    val remaining = db.purchaseDao().itemCountForBill(purchase.billNo)
                    if (remaining == 0) {
                        deletedWholePurchase = true
                        db.purchaseDao().deletePurchase(purchase.billNo)
                        purchase.supplierId?.let { supId ->
                            SyncQueueHelper.adjustSupplierBalance(db, supId, -item.amount)
                            db.supplierDao().find(supId)?.let { s -> SyncQueueHelper.enqueueSupplier(db, s) }
                        }
                        // FIX (#9 — cash transaction consistency): the whole purchase is
                        // gone now, so its linked payment and cash transaction must go
                        // with it — previously only db.purchaseDao().deletePurchase() ran
                        // here, leaving stale Payment/Cash OUT entries behind forever
                        // (mirrors what PurchaseRepository.deletePurchase() already does
                        // for the full-bill delete screen).
                        db.paymentDao().deleteByReference(purchase.billNo)
                        db.cashTransactionDao().deleteByReference(purchase.billNo)
                        SyncQueueHelper.enqueueDelete(db, "purchase", SyncQueueHelper.purchaseEntityId(purchase))
                    } else {
                        val newTotal = purchase.total - item.amount
                        val newPaid = reconcilePaidAndCashRecords(db, purchase.billNo, purchase.paid, newTotal, isPurchase = true)
                        val updatedPurchase = purchase.copy(
                            subtotal = purchase.subtotal - item.amount,
                            total = newTotal,
                            paid = newPaid
                        )
                        db.purchaseDao().updatePurchase(updatedPurchase)
                        purchase.supplierId?.let { supId ->
                            SyncQueueHelper.adjustSupplierBalance(db, supId, -item.amount)
                            db.supplierDao().find(supId)?.let { s -> SyncQueueHelper.enqueueSupplier(db, s) }
                        }
                        SyncQueueHelper.enqueuePurchase(db, updatedPurchase)
                    }
                }
                SyncQueueHelper.trigger(this@PartyTransactionActivity)

                if (deletedWholePurchase) {
                    Toast.makeText(this@PartyTransactionActivity, Loc.t(this@PartyTransactionActivity, "Purchase deleted", "خریداری ڈیلیٹ ہو گئی"), Toast.LENGTH_SHORT).show()
                }

                onDone()
            } catch (e: InsufficientStockException) {
                Toast.makeText(this@PartyTransactionActivity, e.message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@PartyTransactionActivity, "Could not delete item: ${e.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------------- Cost / cash-record helpers (FIX #7, #9) ----------------

    /** FIX (#9 — cash transaction consistency): a billed-item edit/delete changes the
     * parent bill's total. If the bill's existing `paid` amount no longer fits under
     * the new total (paid > new total — e.g. a fully-paid bill that just got smaller),
     * cap paid at the new total and shrink the ONE cash transaction (and, for a
     * purchase, the ONE payment record) tied to this bill's reference by the same
     * amount — instead of leaving Cash Report / Day Book / payment totals silently
     * stuck at the old, now-wrong figure. Growing a bill's total never auto-increases
     * `paid` — that would fabricate a payment nobody actually made — so this only ever
     * moves `paid` down, never up. Returns the (possibly capped) paid amount to store
     * back on the parent Sale/Purchase. */
    private suspend fun reconcilePaidAndCashRecords(
        db: PosDatabase,
        reference: String,
        oldPaid: Double,
        newTotal: Double,
        isPurchase: Boolean
    ): Double {
        val newPaid = oldPaid.coerceIn(0.0, newTotal.coerceAtLeast(0.0))
        val paidDelta = newPaid - oldPaid
        if (paidDelta == 0.0) return newPaid

        db.cashTransactionDao().findByReference(reference)?.let { tx ->
            val updatedTx = tx.copy(
                amount = (tx.amount + paidDelta).coerceAtLeast(0.0),
                updatedAt = System.currentTimeMillis(),
                dirty = true
            )
            db.cashTransactionDao().update(updatedTx)
            SyncQueueHelper.enqueueCashTransaction(db, updatedTx)
        }
        if (isPurchase) {
            db.paymentDao().findByReference(reference)?.let { pay ->
                val updatedPay = pay.copy(
                    amount = (pay.amount + paidDelta).coerceAtLeast(0.0),
                    updatedAt = System.currentTimeMillis(),
                    dirty = true
                )
                db.paymentDao().update(updatedPay)
                SyncQueueHelper.enqueuePayment(db, updatedPay)
            }
        }
        return newPaid
    }

    /** FIX (#7 — purchase costing): removes ONE purchase line's contribution from
     * [product]'s current weighted-average cost — the same math
     * PurchaseRepository.reverseStockAndCostForItems() uses for a full-bill delete,
     * applied here to a single line so PartyTransactionActivity's item-level
     * edit/delete stays consistent with it instead of silently leaving cost stale
     * (which is what this screen used to do before this fix). [smallestQtyToRemove]
     * must already be expressed in the SAME smallest-unit terms as [item]'s frozen
     * conversionFactor (see PurchaseItem.smallestQty()), not the product's current
     * unit config. */
    private fun reversePurchaseLineCost(product: Product, item: PurchaseItem, smallestQtyToRemove: Double): Double {
        if (smallestQtyToRemove <= 0) return product.cost
        val factor = product.smallestUnitFactor()
        val currentCostPerSmallest = if (factor > 0) product.cost / factor else product.cost
        val currentStock = product.stock
        val newStock = currentStock - smallestQtyToRemove
        val totalValueBefore = currentStock * currentCostPerSmallest
        val totalValueAfterRemoval = (totalValueBefore - item.amount).coerceAtLeast(0.0)
        val newCostPerSmallest = if (newStock > 0) totalValueAfterRemoval / newStock else 0.0
        return newCostPerSmallest * factor
    }

    /** FIX (#7 — purchase costing): blends ONE new/edited purchase line into
     * [product]'s weighted-average cost — the same math
     * PurchaseRepository.savePurchase() uses when adding a brand-new line, applied
     * here to a single edited line. [product] must reflect the stock/cost state
     * immediately BEFORE this line is added (i.e. after any prior reversal via
     * [reversePurchaseLineCost] for an edit). */
    private fun addPurchaseLineCost(product: Product, addedSmallestQty: Double, lineAmount: Double): Double {
        if (addedSmallestQty <= 0) return product.cost
        val factor = product.smallestUnitFactor()
        val oldStockSmallest = product.stock
        val oldCostPerSmallest = if (factor > 0) product.cost / factor else product.cost
        val purchaseRatePerSmallest = lineAmount / addedSmallestQty
        val newCostPerSmallest = if (oldStockSmallest <= 0) purchaseRatePerSmallest
            else ((oldStockSmallest * oldCostPerSmallest) + (addedSmallestQty * purchaseRatePerSmallest)) / (oldStockSmallest + addedSmallestQty)
        return newCostPerSmallest * factor
    }

    // ---------------- Shared small UI builders ----------------

    /** Small qty + rate edit form used by both the sale-item and purchase-item edit dialogs. */
    private fun qtyRateEditDialogView(qty: Double, rate: Double): Triple<EditText, EditText, LinearLayout> {
        val qtyInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = Loc.t(this@PartyTransactionActivity, "Quantity", "\u0645\u0642\u062F\u0627\u0631")
            setText(formatQty(qty))
        }
        val rateInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = Loc.t(this@PartyTransactionActivity, "Rate", "\u0631\u06CC\u0679")
            setText(formatQty(rate))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(qtyInput)
            addView(View(this@PartyTransactionActivity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 20)
            })
            addView(rateInput)
        }
        return Triple(qtyInput, rateInput, container)
    }

    private fun billedItemRow(
        productName: String,
        qtyText: String,
        amount: Double,
        accent: String,
        onEdit: () -> Unit,
        onDelete: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 8, 4, 8)
            addView(LinearLayout(this@PartyTransactionActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@PartyTransactionActivity).apply {
                    text = productName
                    textSize = 13.5f
                    setTextColor(Color.parseColor(textDark))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(TextView(this@PartyTransactionActivity).apply {
                    text = Loc.t(this@PartyTransactionActivity, "Qty", "\u0645\u0642\u062F\u0627\u0631") + ": $qtyText"
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
                setPadding(0, 0, 20, 0)
            })
            addView(TextView(this@PartyTransactionActivity).apply {
                text = "\u270F\uFE0F"
                textSize = 15f
                setPadding(14, 0, 14, 0)
                setOnClickListener { onEdit() }
            })
            addView(TextView(this@PartyTransactionActivity).apply {
                text = "\uD83D\uDDD1\uFE0F"
                textSize = 15f
                setPadding(6, 0, 0, 0)
                setOnClickListener { onDelete() }
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
