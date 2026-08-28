package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
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
import com.grocerypos.v11.Product
import com.grocerypos.v11.PurchaseItem
import com.grocerypos.v11.SaleItem
import com.grocerypos.v11.SyncQueueHelper
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
                            // ---- CHANGE: opens billed items dialog with edit/delete
                            // instead of the full SaleActivity edit screen. ----
                            showBilledItemsDialog(isSale = true, invoice = s.invoice, billNo = "")
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
                            // ---- CHANGE: opens billed items dialog with edit/delete
                            // instead of the full PurchaseActivity edit screen. ----
                            showBilledItemsDialog(isSale = false, invoice = "", billNo = p.billNo)
                        }
                        )
                    }
                }
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

                if (isSale) {
                    val items = db.saleDao().itemsForInvoice(reference)
                    itemCount = items.size
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
                dialog = AlertDialog.Builder(this@PartyTransactionActivity)
                    .setView(outer)
                    .setPositiveButton(Loc.t(this@PartyTransactionActivity, "Close", "\u0628\u0646\u062F \u06A9\u0631\u06CC\u06BA")) { _, _ ->
                        loadTransactions()
                    }
                    .setOnCancelListener { loadTransactions() }
                    .show()
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
            try {
                val db = PosDatabase.get(this@PartyTransactionActivity)
                val product = db.productDao().find(item.barcode)

                val oldQty = item.qty
                val deltaQty = newQty - oldQty

                // Stock: a sale decreases stock, so selling MORE (deltaQty > 0) must
                // decrease stock further; selling LESS gives stock back.
                if (product != null && deltaQty != 0.0) {
                    val deltaSmallest = product.toSmallestUnits(kotlin.math.abs(deltaQty), item.unit)
                    if (deltaQty > 0) {
                        val rows = db.productDao().decrease(item.barcode, deltaSmallest)
                        if (rows == 0) {
                            Toast.makeText(this@PartyTransactionActivity, Loc.t(this@PartyTransactionActivity, "Not enough stock", "\u0627\u0633\u0679\u0627\u06A9 \u06A9\u0645 \u06C1\u06D2"), Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                    } else {
                        db.productDao().increase(item.barcode, deltaSmallest)
                    }
                }

                val perUnitCost = if (oldQty != 0.0) item.cost / oldQty else 0.0
                val newAmount = newQty * newRate
                val newCost = perUnitCost * newQty
                val updatedItem = item.copy(qty = newQty, unitPrice = newRate, amount = newAmount, cost = newCost)
                db.saleDao().updateItemRow(updatedItem)

                val deltaAmount = newAmount - item.amount
                val updatedSale = sale.copy(
                    subtotal = sale.subtotal + deltaAmount,
                    total = sale.total + deltaAmount
                )
                db.saleDao().updateSale(updatedSale)

                sale.customerId?.let { custId ->
                    db.customerDao().addBalance(custId, deltaAmount)
                    db.customerDao().find(custId)?.let { c -> SyncQueueHelper.enqueueCustomer(db, c) }
                }
                if (product != null) {
                    db.productDao().find(item.barcode)?.let { p -> SyncQueueHelper.enqueueProduct(db, p) }
                }
                SyncQueueHelper.enqueueSale(db, updatedSale, db.saleDao().itemCountForInvoice(updatedSale.invoice))
                SyncQueueHelper.trigger(this@PartyTransactionActivity)

                onDone()
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
            try {
                val db = PosDatabase.get(this@PartyTransactionActivity)
                val product = db.productDao().find(item.barcode)

                // Deleting a sold item gives the stock back.
                if (product != null) {
                    val deltaSmallest = product.toSmallestUnits(item.qty, item.unit)
                    db.productDao().increase(item.barcode, deltaSmallest)
                    db.productDao().find(item.barcode)?.let { p -> SyncQueueHelper.enqueueProduct(db, p) }
                }

                db.saleDao().deleteItemById(item.id)

                val remaining = db.saleDao().itemCountForInvoice(sale.invoice)
                if (remaining == 0) {
                    db.saleDao().deleteSale(sale.invoice)
                    sale.customerId?.let { custId ->
                        db.customerDao().addBalance(custId, -item.amount)
                        db.customerDao().find(custId)?.let { c -> SyncQueueHelper.enqueueCustomer(db, c) }
                    }
                    SyncQueueHelper.enqueueDelete(db, "sale", SyncQueueHelper.saleEntityId(sale))
                    SyncQueueHelper.trigger(this@PartyTransactionActivity)
                    Toast.makeText(this@PartyTransactionActivity, Loc.t(this@PartyTransactionActivity, "Sale deleted", "\u0633\u06CC\u0644 \u0688\u06CC\u0644\u06CC\u0679 \u06C1\u0648 \u06AF\u0626\u06CC"), Toast.LENGTH_SHORT).show()
                } else {
                    val updatedSale = sale.copy(subtotal = sale.subtotal - item.amount, total = sale.total - item.amount)
                    db.saleDao().updateSale(updatedSale)
                    sale.customerId?.let { custId ->
                        db.customerDao().addBalance(custId, -item.amount)
                        db.customerDao().find(custId)?.let { c -> SyncQueueHelper.enqueueCustomer(db, c) }
                    }
                    SyncQueueHelper.enqueueSale(db, updatedSale, remaining)
                    SyncQueueHelper.trigger(this@PartyTransactionActivity)
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
            try {
                val db = PosDatabase.get(this@PartyTransactionActivity)
                val product = db.productDao().find(item.barcode)

                val oldQty = item.qty
                val deltaQty = newQty - oldQty

                // Stock: a purchase increases stock, so buying MORE (deltaQty > 0) adds
                // more stock; buying less must remove stock (fails if already sold/used).
                if (product != null && deltaQty != 0.0) {
                    val deltaSmallest = product.toSmallestUnits(kotlin.math.abs(deltaQty), item.unit)
                    if (deltaQty > 0) {
                        db.productDao().increase(item.barcode, deltaSmallest)
                    } else {
                        val rows = db.productDao().decrease(item.barcode, deltaSmallest)
                        if (rows == 0) {
                            Toast.makeText(this@PartyTransactionActivity, Loc.t(this@PartyTransactionActivity, "Not enough stock to reduce", "\u06A9\u0645 \u06A9\u0631\u0646\u06D2 \u06A9\u06D2 \u0644\u06CC\u06D2 \u0627\u0633\u0679\u0627\u06A9 \u06A9\u0645 \u06C1\u06D2"), Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                    }
                }

                val perUnitCostRatio = if (oldQty != 0.0) item.amount / oldQty else 0.0
                val newAmount = newQty * newRate
                val updatedItem = item.copy(qty = newQty, unitCost = newRate, amount = newAmount)
                db.purchaseDao().updateItemRow(updatedItem)

                val deltaAmount = newAmount - item.amount
                val updatedPurchase = purchase.copy(
                    subtotal = purchase.subtotal + deltaAmount,
                    total = purchase.total + deltaAmount
                )
                db.purchaseDao().updatePurchase(updatedPurchase)

                purchase.supplierId?.let { supId ->
                    db.supplierDao().addBalance(supId, deltaAmount)
                    db.supplierDao().find(supId)?.let { s -> SyncQueueHelper.enqueueSupplier(db, s) }
                }
                if (product != null) {
                    db.productDao().find(item.barcode)?.let { p -> SyncQueueHelper.enqueueProduct(db, p) }
                }
                SyncQueueHelper.enqueuePurchase(db, updatedPurchase, db.purchaseDao().itemCountForBill(updatedPurchase.billNo))
                SyncQueueHelper.trigger(this@PartyTransactionActivity)

                onDone()
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
            try {
                val db = PosDatabase.get(this@PartyTransactionActivity)
                val product = db.productDao().find(item.barcode)

                // Deleting a purchased item removes the stock it had added — fails if
                // that stock has already been sold/used elsewhere.
                if (product != null) {
                    val deltaSmallest = product.toSmallestUnits(item.qty, item.unit)
                    val rows = db.productDao().decrease(item.barcode, deltaSmallest)
                    if (rows == 0) {
                        Toast.makeText(this@PartyTransactionActivity, Loc.t(this@PartyTransactionActivity, "Cannot delete: stock already used", "\u0688\u06CC\u0644\u06CC\u0679 \u0646\u06C1\u06CC\u06BA \u06C1\u0648 \u0633\u06A9\u062A\u0627: \u0627\u0633\u0679\u0627\u06A9 \u067E\u06C1\u0644\u06D2 \u06C1\u06CC \u0627\u0633\u062A\u0639\u0645\u0627\u0644 \u06C1\u0648 \u0686\u06A9\u0627"), Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    db.productDao().find(item.barcode)?.let { p -> SyncQueueHelper.enqueueProduct(db, p) }
                }

                db.purchaseDao().deleteItemById(item.id)

                val remaining = db.purchaseDao().itemCountForBill(purchase.billNo)
                if (remaining == 0) {
                    db.purchaseDao().deletePurchase(purchase.billNo)
                    purchase.supplierId?.let { supId ->
                        db.supplierDao().addBalance(supId, -item.amount)
                        db.supplierDao().find(supId)?.let { s -> SyncQueueHelper.enqueueSupplier(db, s) }
                    }
                    SyncQueueHelper.enqueueDelete(db, "purchase", SyncQueueHelper.purchaseEntityId(purchase))
                    SyncQueueHelper.trigger(this@PartyTransactionActivity)
                    Toast.makeText(this@PartyTransactionActivity, Loc.t(this@PartyTransactionActivity, "Purchase deleted", "\u062E\u0631\u06CC\u062F\u0627\u0631\u06CC \u0688\u06CC\u0644\u06CC\u0679 \u06C1\u0648 \u06AF\u0626\u06CC"), Toast.LENGTH_SHORT).show()
                } else {
                    val updatedPurchase = purchase.copy(subtotal = purchase.subtotal - item.amount, total = purchase.total - item.amount)
                    db.purchaseDao().updatePurchase(updatedPurchase)
                    purchase.supplierId?.let { supId ->
                        db.supplierDao().addBalance(supId, -item.amount)
                        db.supplierDao().find(supId)?.let { s -> SyncQueueHelper.enqueueSupplier(db, s) }
                    }
                    SyncQueueHelper.enqueuePurchase(db, updatedPurchase, remaining)
                    SyncQueueHelper.trigger(this@PartyTransactionActivity)
                }

                onDone()
            } catch (e: Exception) {
                Toast.makeText(this@PartyTransactionActivity, "Could not delete item: ${e.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
            }
        }
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
