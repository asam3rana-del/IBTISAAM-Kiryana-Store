package com.grocerypos.v11.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.Customer
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Supplier
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ---- CHANGE (ultra-premium UI pass) ----
 * Restyled to match ReportsActivity / StockReportActivity exactly: same palette,
 * premiumHeader(), strokedBg cards with applyElevation(), same pill-tab treatment as
 * Reports' period filter, and the same summaryCard/listCard/navRow/plRow language.
 * This now reaches "andar tak" (all the way in) — every nested report dialog (Item
 * Report, Ledger, Payment History, Statement, Transactions, Profit & Loss) uses the
 * same card styling instead of the old plain AlertDialog rows.
 * No business logic changed — every DB query, calculation, and Dr/Cr/give-get sign
 * rule below is identical to before; only the view-building code changed.
 */
class PartyReportsActivity : AppCompatActivity() {

    // ================= PREMIUM PALETTE (shared with Reports / Stock Report) =================
    private val bg = "#F3F2FA"
    private val cardBg = "#FFFFFF"
    private val primary = "#4A3AFF"
    private val primaryDark = "#3527D6"
    private val purple = "#8B5CF6"
    private val amber = "#F5A524"
    private val teal = "#0F9B8E"
    private val red = "#E5484D"
    private val textDark = "#1A1A2E"
    private val textGray = "#8A8A9E"
    private val border = "#E7E5F3"

    private lateinit var listContainer: LinearLayout
    private lateinit var customersTab: TextView
    private lateinit var suppliersTab: TextView
    private var showingCustomers = true

    data class ItemAgg(val product: String, val qty: Double, val amount: Double)

    // ---- Used by Payment History. There's no dedicated Payment/installment table in
    // the current schema, so each sale/purchase's `paid` field is treated as a single
    // payment entry dated at the transaction's createdAt. If a separate payments/
    // installments table gets added later, swap the two loops in showPaymentHistory()
    // to read from it instead. ----
    data class PaymentEntry(val date: Long, val amount: Double, val against: String)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            setBackgroundColor(Color.parseColor(bg))
        }

        root.addView(premiumHeader("👥", Loc.t(this, "Party Reports", "پارٹی رپورٹس"), Loc.t(this, "Customer & supplier balances", "کسٹمر اور سپلائر کا بیلنس")))

        // ================= TAB PILLS (matches Reports' period-filter row) =================
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = strokedBg(border, cardBg, 14)
            setPadding(6, 6, 6, 6)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, 0, 16) }
        }
        customersTab = filterPill(Loc.t(this, "CUSTOMERS", "کسٹمرز")) { showingCustomers = true; refreshTabs(); loadParties() }
        suppliersTab = filterPill(Loc.t(this, "SUPPLIERS", "سپلائرز")) { showingCustomers = false; refreshTabs(); loadParties() }
        tabRow.addView(customersTab)
        tabRow.addView(suppliersTab)
        root.addView(tabRow)

        root.addView(sectionHeader(Loc.t(this, "Tap a party to select a report", "رپورٹ منتخب کرنے کے لیے پارٹی پر ٹیپ کریں")))

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)
        root.addView(spacer(30))

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        }
        setContentView(scroll)

        refreshTabs()
        loadParties()
    }

    private fun refreshTabs() {
        if (showingCustomers) {
            customersTab.background = roundedBg(primary, 10)
            customersTab.setTextColor(Color.WHITE)
            suppliersTab.setBackgroundColor(Color.TRANSPARENT)
            suppliersTab.setTextColor(Color.parseColor(textGray))
        } else {
            suppliersTab.background = roundedBg(amber, 10)
            suppliersTab.setTextColor(Color.WHITE)
            customersTab.setBackgroundColor(Color.TRANSPARENT)
            customersTab.setTextColor(Color.parseColor(textGray))
        }
    }

    private fun loadParties() {
        listContainer.removeAllViews()
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PartyReportsActivity)
            if (showingCustomers) {
                val customers = db.customerDao().all().first()
                if (customers.isEmpty()) listContainer.addView(emptyText(Loc.t(this@PartyReportsActivity, "No customers yet", "کوئی کسٹمر نہیں ہے")))
                customers.forEach { c ->
                    listContainer.addView(partyRow(c.name, c.openingBalance + c.balance, isCustomer = true) {
                        showReportMenu(true, c.id, c.name, c.openingBalance)
                    })
                }
            } else {
                val suppliers = db.supplierDao().all().first()
                if (suppliers.isEmpty()) listContainer.addView(emptyText(Loc.t(this@PartyReportsActivity, "No suppliers yet", "کوئی سپلائر نہیں ہے")))
                suppliers.forEach { s ->
                    listContainer.addView(partyRow(s.name, s.openingBalance + s.balance, isCustomer = false) {
                        showReportMenu(false, s.id, s.name, s.openingBalance)
                    })
                }
            }
        }
    }

    // FIX (Phase 2 - Accounting): balance color is customer/supplier-aware (isCustomer
    // param) instead of coloring any positive balance the same — a positive customer
    // balance means they owe us (red/"give" convention elsewhere), but a positive
    // supplier balance means WE owe them, which previously showed the wrong color. Also
    // receives the closing balance (opening + running) instead of just the running
    // balance, matching PartyActivity/PartyDashboardActivity/MainActivity.
    private fun partyRow(name: String, closing: Double, isCustomer: Boolean, onClick: () -> Unit): LinearLayout {
        val isGive = if (isCustomer) closing < 0 else closing > 0
        val accentHex = if (isCustomer) primary else amber
        val tintHex = if (isCustomer) "#E9E6FF" else "#FFF3E0"
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 18, 20, 18)
            background = strokedBg(border, cardBg, 18)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 10) }
            applyElevation(this, 2f)
            isClickable = true
            setOnClickListener { onClick() }

            addView(FrameLayout(this@PartyReportsActivity).apply {
                val size = (40 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(tintHex)) }
                addView(TextView(this@PartyReportsActivity).apply {
                    text = if (isCustomer) "👤" else "📦"; textSize = 15f; gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                })
            })

            addView(TextView(this@PartyReportsActivity).apply {
                text = name
                textSize = 14.5f
                setTextColor(Color.parseColor(textDark))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(16, 0, 8, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@PartyReportsActivity).apply {
                text = "Rs %.2f".format(closing)
                textSize = 13.5f
                setTextColor(Color.parseColor(if (isGive) red else teal))
                setTypeface(typeface, Typeface.BOLD)
            })
        }
    }

    // ---- Tap a party -> choose which report (custom-styled sheet, matches navRow list) ----
    private fun showReportMenu(isCustomer: Boolean, id: Long, name: String, opening: Double) {
        val plLabel = if (isCustomer)
            Loc.t(this, "Customer-wise Profit", "کسٹمر کے لحاظ سے منافع")
        else
            Loc.t(this, "Purchase Summary", "خریداری کا خلاصہ")

        val statementLabel = if (isCustomer)
            Loc.t(this, "Customer Statement", "کسٹمر اسٹیٹمنٹ")
        else
            Loc.t(this, "Supplier Statement", "سپلائر اسٹیٹمنٹ")

        val entries = listOf(
            Triple("📦", Loc.t(this, "Party Report by Item", "آئٹم کے لحاظ سے پارٹی رپورٹ"), 0),
            Triple("📒", Loc.t(this, "Customer Ledger", "کسٹمر لیجر"), 1),
            Triple("💵", Loc.t(this, "Payment History", "ادائیگی کی تاریخ"), 2),
            Triple("📜", statementLabel, 3),
            Triple("🧾", Loc.t(this, "Sale/Purchase by Party", "پارٹی کے لحاظ سے سیل/خریداری"), 4),
            Triple("📊", plLabel, 5)
        )

        val menuCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = strokedBg(border, cardBg, 18)
            setPadding(6, 6, 6, 6)
            applyElevation(this, 3f)
        }
        val dialog = AlertDialog.Builder(this).setView(menuCard).create()

        entries.forEachIndexed { idx, (icon, label, which) ->
            menuCard.addView(navRow(icon, primary, "#E9E6FF", label, "") {
                dialog.dismiss()
                when (which) {
                    0 -> showItemReport(isCustomer, id, name)
                    1 -> showLedger(isCustomer, id, name, opening)
                    2 -> showPaymentHistory(isCustomer, id, name)
                    3 -> showStatement(isCustomer, id, name, opening)
                    4 -> showTransactions(isCustomer, id, name)
                    5 -> showPartyPL(isCustomer, id, name)
                }
            })
            if (idx != entries.lastIndex) menuCard.addView(navDivider())
        }

        dialog.setTitle(name)
        dialog.show()
    }

    // ================= 1) Party Report by Item =================
    private fun showItemReport(isCustomer: Boolean, id: Long, name: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PartyReportsActivity)
            val items: List<ItemAgg> = if (isCustomer) {
                val sales = db.saleDao().salesByCustomer(id)
                val map = LinkedHashMap<String, ItemAgg>()
                sales.forEach { s ->
                    db.saleDao().itemsForInvoice(s.invoice).forEach { it ->
                        val ex = map[it.product]
                        map[it.product] = if (ex == null) ItemAgg(it.product, it.qty.toDouble(), it.amount)
                        else ItemAgg(it.product, ex.qty + it.qty.toDouble(), ex.amount + it.amount)
                    }
                }
                map.values.sortedByDescending { it.amount }
            } else {
                val purchases = db.purchaseDao().purchasesBySupplier(id)
                val map = LinkedHashMap<String, ItemAgg>()
                purchases.forEach { p ->
                    db.purchaseDao().itemsForBill(p.billNo).forEach { it ->
                        val productName = db.productDao().find(it.barcode)?.name ?: it.barcode
                        val ex = map[productName]
                        map[productName] = if (ex == null) ItemAgg(productName, it.qty.toDouble(), it.amount)
                        else ItemAgg(productName, ex.qty + it.qty.toDouble(), ex.amount + it.amount)
                    }
                }
                map.values.sortedByDescending { it.amount }
            }

            val content = reportContainer("📦", primary, "#E9E6FF", Loc.t(this@PartyReportsActivity, "Item Report", "آئٹم رپورٹ"), name)
            val body = (content.getChildAt(1) as ScrollView).getChildAt(0) as LinearLayout
            if (items.isEmpty()) {
                body.addView(emptyText(Loc.t(this@PartyReportsActivity, "No items found", "کوئی آئٹم نہیں ملا")))
            } else {
                items.forEach { i -> body.addView(rowText(i.product, "${formatQty(i.qty)} × — Rs %.2f".format(i.amount))) }
            }
            AlertDialog.Builder(this@PartyReportsActivity)
                .setView(content)
                .setPositiveButton(Loc.t(this@PartyReportsActivity, "Close", "بند کریں"), null)
                .show()
        }
    }

    // ================= 2) Customer / Supplier Ledger (Dr / Cr table) =================
    // Classic accounting ledger: every transaction posts a Debit (sale/purchase total)
    // and a Credit (amount paid at that time) with a running balance carried forward.
    // This is more detailed than the Statement below — it shows Dr and Cr side by side
    // per entry instead of just the net outstanding change.
    private fun showLedger(isCustomer: Boolean, id: Long, name: String, opening: Double) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PartyReportsActivity)
            val fmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            var running = opening

            val content = reportContainer("📒", primary, "#E9E6FF", Loc.t(this@PartyReportsActivity, "Ledger", "لیجر"), name)
            val body = (content.getChildAt(1) as ScrollView).getChildAt(0) as LinearLayout

            body.addView(ledgerHeaderRow())
            body.addView(plDivider())

            body.addView(
                ledgerRow(
                    Loc.t(this@PartyReportsActivity, "Opening Balance", "ابتدائی بیلنس"),
                    dr = if (opening > 0) opening else 0.0,
                    cr = if (opening < 0) -opening else 0.0,
                    balance = running,
                    bold = true
                )
            )

            if (isCustomer) {
                val sales = db.saleDao().salesByCustomer(id).sortedBy { it.createdAt }
                if (sales.isEmpty()) {
                    body.addView(emptyText(Loc.t(this@PartyReportsActivity, "No transactions yet", "کوئی لین دین نہیں ہے")))
                }
                sales.forEach { s ->
                    running += (s.total - s.paid)
                    body.addView(ledgerRow(fmt.format(Date(s.createdAt)), dr = s.total, cr = s.paid, balance = running))
                }
            } else {
                val purchases = db.purchaseDao().purchasesBySupplier(id).sortedBy { it.createdAt }
                if (purchases.isEmpty()) {
                    body.addView(emptyText(Loc.t(this@PartyReportsActivity, "No transactions yet", "کوئی لین دین نہیں ہے")))
                }
                purchases.forEach { p ->
                    running += (p.total - p.paid)
                    body.addView(ledgerRow(fmt.format(Date(p.createdAt)), dr = p.total, cr = p.paid, balance = running))
                }
            }

            body.addView(plDivider())
            body.addView(rowText(Loc.t(this@PartyReportsActivity, "Closing Balance", "اختتامی بیلنس"), "Rs %.2f".format(running)).apply {
                (getChildAt(0) as TextView).setTypeface(null, Typeface.BOLD)
                (getChildAt(1) as TextView).setTextColor(Color.parseColor(if (running > 0) red else teal))
            })

            AlertDialog.Builder(this@PartyReportsActivity)
                .setView(content)
                .setPositiveButton(Loc.t(this@PartyReportsActivity, "Close", "بند کریں"), null)
                .show()
        }
    }

    private fun ledgerHeaderRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4, 2, 4, 8)
            addView(TextView(this@PartyReportsActivity).apply {
                text = Loc.t(this@PartyReportsActivity, "Date", "تاریخ")
                textSize = 11f
                setTextColor(Color.parseColor(textGray))
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f)
            })
            addView(TextView(this@PartyReportsActivity).apply {
                text = Loc.t(this@PartyReportsActivity, "Debit", "ڈیبٹ")
                textSize = 11f
                gravity = Gravity.END
                setTextColor(Color.parseColor(red))
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@PartyReportsActivity).apply {
                text = Loc.t(this@PartyReportsActivity, "Credit", "کریڈٹ")
                textSize = 11f
                gravity = Gravity.END
                setTextColor(Color.parseColor(teal))
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(12, 0, 0, 0)
            })
        }
    }

    private fun ledgerRow(date: String, dr: Double, cr: Double, balance: Double, bold: Boolean = false): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 8, 4, 8)
            val top = LinearLayout(this@PartyReportsActivity).apply { orientation = LinearLayout.HORIZONTAL }
            top.addView(TextView(this@PartyReportsActivity).apply {
                text = date
                textSize = if (bold) 13.5f else 13f
                setTextColor(Color.parseColor(textDark))
                if (bold) setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f)
            })
            top.addView(TextView(this@PartyReportsActivity).apply {
                text = if (dr > 0) "Rs %.2f".format(dr) else "—"
                textSize = 13f
                gravity = Gravity.END
                setTextColor(Color.parseColor(if (dr > 0) red else textGray))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            top.addView(TextView(this@PartyReportsActivity).apply {
                text = if (cr > 0) "Rs %.2f".format(cr) else "—"
                textSize = 13f
                gravity = Gravity.END
                setTextColor(Color.parseColor(if (cr > 0) teal else textGray))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(12, 0, 0, 0)
            })
            addView(top)
            addView(TextView(this@PartyReportsActivity).apply {
                text = Loc.t(this@PartyReportsActivity, "Balance", "بیلنس") + ": Rs %.2f".format(balance)
                textSize = 11.5f
                setTextColor(Color.parseColor(if (balance > 0) red else teal))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 3, 0, 0)
            })
        }
    }

    // ================= 3) Payment History =================
    // Reads each sale/purchase's `paid` amount as one payment entry dated at the
    // transaction's createdAt (see PaymentEntry doc comment above — there's no
    // separate installment/payment table in the current schema).
    private fun showPaymentHistory(isCustomer: Boolean, id: Long, name: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PartyReportsActivity)
            val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())

            val content = reportContainer("💵", teal, "#E0F2F1", Loc.t(this@PartyReportsActivity, "Payment History", "ادائیگی کی تاریخ"), name)
            val body = (content.getChildAt(1) as ScrollView).getChildAt(0) as LinearLayout

            val payments = mutableListOf<PaymentEntry>()
            if (isCustomer) {
                db.saleDao().salesByCustomer(id).forEach { s ->
                    if (s.paid > 0) payments.add(PaymentEntry(s.createdAt, s.paid, Loc.t(this@PartyReportsActivity, "Against Sale", "سیل کے مقابلے میں")))
                }
            } else {
                db.purchaseDao().purchasesBySupplier(id).forEach { p ->
                    if (p.paid > 0) payments.add(PaymentEntry(p.createdAt, p.paid, Loc.t(this@PartyReportsActivity, "Against Purchase", "خریداری کے مقابلے میں")))
                }
            }
            payments.sortByDescending { it.date }

            if (payments.isEmpty()) {
                body.addView(emptyText(Loc.t(this@PartyReportsActivity, "No payments recorded yet", "ابھی تک کوئی ادائیگی درج نہیں ہوئی")))
            } else {
                var total = 0.0
                payments.forEach { pe ->
                    total += pe.amount
                    body.addView(paymentRow(fmt.format(Date(pe.date)), pe.against, pe.amount))
                }
                body.addView(plDivider())
                val totalLabel = if (isCustomer)
                    Loc.t(this@PartyReportsActivity, "Total Received", "کل موصول شدہ")
                else
                    Loc.t(this@PartyReportsActivity, "Total Paid", "کل ادا شدہ")
                body.addView(rowText(totalLabel, "Rs %.2f".format(total)).apply {
                    (getChildAt(0) as TextView).setTypeface(null, Typeface.BOLD)
                    (getChildAt(1) as TextView).setTextColor(Color.parseColor(teal))
                })
            }

            AlertDialog.Builder(this@PartyReportsActivity)
                .setView(content)
                .setPositiveButton(Loc.t(this@PartyReportsActivity, "Close", "بند کریں"), null)
                .show()
        }
    }

    private fun paymentRow(date: String, against: String, amount: Double): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 10, 4, 10)

            addView(FrameLayout(this@PartyReportsActivity).apply {
                val size = (32 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#E0F2F1")) }
                addView(TextView(this@PartyReportsActivity).apply {
                    text = "💵"; textSize = 14f; gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                })
            })

            val col = LinearLayout(this@PartyReportsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14, 0, 8, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this@PartyReportsActivity).apply {
                text = date
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor(textDark))
            })
            col.addView(TextView(this@PartyReportsActivity).apply {
                text = against
                textSize = 11f
                setTextColor(Color.parseColor(textGray))
            })
            addView(col)

            addView(TextView(this@PartyReportsActivity).apply {
                text = "Rs %.2f".format(amount)
                textSize = 13.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor(teal))
            })
        }
    }

    // ================= 4) Party Statement (running balance) =================
    private fun showStatement(isCustomer: Boolean, id: Long, name: String, opening: Double) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PartyReportsActivity)
            val fmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            var running = opening

            val content = reportContainer("📜", purple, "#F0EBFF", Loc.t(this@PartyReportsActivity, "Statement", "اسٹیٹمنٹ"), name)
            val body = (content.getChildAt(1) as ScrollView).getChildAt(0) as LinearLayout

            body.addView(rowText(Loc.t(this@PartyReportsActivity, "Opening Balance", "ابتدائی بیلنس"), "Rs %.2f".format(opening)).apply {
                (getChildAt(0) as TextView).setTypeface(null, Typeface.BOLD)
            })
            body.addView(plDivider())

            if (isCustomer) {
                val sales = db.saleDao().salesByCustomer(id).sortedBy { it.createdAt }
                if (sales.isEmpty()) body.addView(emptyText(Loc.t(this@PartyReportsActivity, "No transactions yet", "کوئی لین دین نہیں ہے")))
                sales.forEach { s ->
                    val outstanding = s.total - s.paid
                    running += outstanding
                    // ---- No invoice number shown — date is the row's identifier ----
                    body.addView(statementRow(fmt.format(Date(s.createdAt)), s.total, running, isCustomer = true))
                }
            } else {
                val purchases = db.purchaseDao().purchasesBySupplier(id).sortedBy { it.createdAt }
                if (purchases.isEmpty()) body.addView(emptyText(Loc.t(this@PartyReportsActivity, "No transactions yet", "کوئی لین دین نہیں ہے")))
                purchases.forEach { p ->
                    val outstanding = p.total - p.paid
                    running += outstanding
                    // ---- No bill number shown — date is the row's identifier ----
                    body.addView(statementRow(fmt.format(Date(p.createdAt)), p.total, running, isCustomer = false))
                }
            }

            body.addView(plDivider())
            // FIX (Phase 2 - Accounting): closing-balance color is customer/supplier-aware
            // (a positive supplier balance means WE owe them, which is not the same "red"
            // meaning as a positive customer balance) — same isGive pattern used elsewhere.
            val closingIsGive = if (isCustomer) running < 0 else running > 0
            body.addView(rowText(Loc.t(this@PartyReportsActivity, "Closing Balance", "اختتامی بیلنس"), "Rs %.2f".format(running)).apply {
                (getChildAt(0) as TextView).setTypeface(null, Typeface.BOLD)
                (getChildAt(1) as TextView).setTextColor(Color.parseColor(if (closingIsGive) red else teal))
            })

            AlertDialog.Builder(this@PartyReportsActivity)
                .setView(content)
                .setPositiveButton(Loc.t(this@PartyReportsActivity, "Close", "بند کریں"), null)
                .show()
        }
    }

    // ---- Reference/invoice number removed — date is now the only identifier shown ----
    // FIX (Phase 2 - Accounting): added isCustomer so the per-row balance color follows
    // the same customer/supplier-aware convention as the Closing Balance above it.
    private fun statementRow(date: String, total: Double, balanceAfter: Double, isCustomer: Boolean): LinearLayout {
        val isGive = if (isCustomer) balanceAfter < 0 else balanceAfter > 0
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 10, 4, 10)
            val top = LinearLayout(this@PartyReportsActivity).apply { orientation = LinearLayout.HORIZONTAL }
            top.addView(TextView(this@PartyReportsActivity).apply {
                text = date; textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor(textDark))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            top.addView(TextView(this@PartyReportsActivity).apply {
                text = "Rs %.2f".format(total); textSize = 13f
                setTextColor(Color.parseColor(textGray))
            })
            addView(top)
            addView(TextView(this@PartyReportsActivity).apply {
                text = Loc.t(this@PartyReportsActivity, "Balance", "بیلنس") + ": Rs %.2f".format(balanceAfter)
                textSize = 12f
                setTextColor(Color.parseColor(if (isGive) red else teal))
                setTypeface(typeface, Typeface.BOLD)
            })
        }
    }

    // ================= 5) Sale/Purchase by Party (plain transaction list) =================
    private fun showTransactions(isCustomer: Boolean, id: Long, name: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PartyReportsActivity)
            val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())

            val title = if (isCustomer) Loc.t(this@PartyReportsActivity, "Sales", "سیلز") else Loc.t(this@PartyReportsActivity, "Purchases", "خریداریاں")
            val content = reportContainer("🧾", if (isCustomer) primary else amber, if (isCustomer) "#E9E6FF" else "#FFF3E0", title, name)
            val body = (content.getChildAt(1) as ScrollView).getChildAt(0) as LinearLayout

            if (isCustomer) {
                val sales = db.saleDao().salesByCustomer(id).sortedByDescending { it.createdAt }
                if (sales.isEmpty()) body.addView(emptyText(Loc.t(this@PartyReportsActivity, "No sales yet", "کوئی سیل نہیں ہوئی")))
                var totalAmt = 0.0
                sales.forEach { s ->
                    totalAmt += s.total
                    // ---- Invoice number removed — date is the row's identifier ----
                    body.addView(rowText(fmt.format(Date(s.createdAt)), "Rs %.2f".format(s.total)))
                }
                body.addView(plDivider())
                body.addView(rowText(Loc.t(this@PartyReportsActivity, "Total", "کل"), "Rs %.2f".format(totalAmt)))
            } else {
                val purchases = db.purchaseDao().purchasesBySupplier(id).sortedByDescending { it.createdAt }
                if (purchases.isEmpty()) body.addView(emptyText(Loc.t(this@PartyReportsActivity, "No purchases yet", "کوئی خریداری نہیں ہوئی")))
                var totalAmt = 0.0
                purchases.forEach { p ->
                    totalAmt += p.total
                    // ---- Bill number removed — date is the row's identifier ----
                    body.addView(rowText(fmt.format(Date(p.createdAt)), "Rs %.2f".format(p.total)))
                }
                body.addView(plDivider())
                body.addView(rowText(Loc.t(this@PartyReportsActivity, "Total", "کل"), "Rs %.2f".format(totalAmt)))
            }

            AlertDialog.Builder(this@PartyReportsActivity)
                .setView(content)
                .setPositiveButton(Loc.t(this@PartyReportsActivity, "Close", "بند کریں"), null)
                .show()
        }
    }

    // ================= 6) Profit & Loss =================
    // Customer: real profit (revenue - cost) from every item they've bought.
    // Supplier: no "profit" concept — show a purchase spend summary instead.
    private fun showPartyPL(isCustomer: Boolean, id: Long, name: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PartyReportsActivity)
            val title = if (isCustomer) Loc.t(this@PartyReportsActivity, "Profit & Loss", "منافع اور نقصان") else Loc.t(this@PartyReportsActivity, "Purchase Summary", "خریداری کا خلاصہ")
            val content = reportContainer("📊", teal, "#E0F2F1", title, name)
            val body = (content.getChildAt(1) as ScrollView).getChildAt(0) as LinearLayout

            if (isCustomer) {
                val sales = db.saleDao().salesByCustomer(id)
                if (sales.isEmpty()) {
                    body.addView(emptyText(Loc.t(this@PartyReportsActivity, "No sales yet", "کوئی سیل نہیں ہوئی")))
                } else {
                    var revenue = 0.0
                    var cost = 0.0
                    sales.forEach { s ->
                        db.saleDao().itemsForInvoice(s.invoice).forEach { it ->
                            revenue += it.amount
                            cost += it.cost
                        }
                    }
                    val profit = revenue - cost
                    val profitColor = if (profit >= 0) teal else red

                    body.addView(rowText(Loc.t(this@PartyReportsActivity, "Total Sales (bills)", "کل سیلز (بلز)"), "${sales.size}"))
                    body.addView(rowText(Loc.t(this@PartyReportsActivity, "Revenue", "آمدنی"), "Rs %.2f".format(revenue)))
                    body.addView(rowText(Loc.t(this@PartyReportsActivity, "Cost of Goods", "سامان کی لاگت"), "Rs %.2f".format(cost)))
                    body.addView(plDivider())
                    body.addView(rowText(if (profit >= 0) Loc.t(this@PartyReportsActivity, "Net Profit", "خالص منافع") else Loc.t(this@PartyReportsActivity, "Net Loss", "خالص نقصان"), "Rs %.2f".format(profit)).apply {
                        (getChildAt(0) as TextView).setTypeface(null, Typeface.BOLD)
                        (getChildAt(1) as TextView).setTextColor(Color.parseColor(profitColor))
                    })
                }
            } else {
                val purchases = db.purchaseDao().purchasesBySupplier(id)
                if (purchases.isEmpty()) {
                    body.addView(emptyText(Loc.t(this@PartyReportsActivity, "No purchases yet", "کوئی خریداری نہیں ہوئی")))
                } else {
                    val totalBills = purchases.size
                    val totalAmount = purchases.sumOf { it.total }
                    val totalPaid = purchases.sumOf { it.paid }
                    val totalDue = totalAmount - totalPaid

                    body.addView(rowText(Loc.t(this@PartyReportsActivity, "Total Bills", "کل بلز"), "$totalBills"))
                    body.addView(rowText(Loc.t(this@PartyReportsActivity, "Total Purchased", "کل خریداری"), "Rs %.2f".format(totalAmount)))
                    body.addView(rowText(Loc.t(this@PartyReportsActivity, "Total Paid", "کل ادائیگی"), "Rs %.2f".format(totalPaid)))
                    body.addView(plDivider())
                    body.addView(rowText(Loc.t(this@PartyReportsActivity, "Outstanding Due", "باقی واجب الادا"), "Rs %.2f".format(totalDue)).apply {
                        (getChildAt(0) as TextView).setTypeface(null, Typeface.BOLD)
                        (getChildAt(1) as TextView).setTextColor(Color.parseColor(if (totalDue > 0) red else teal))
                    })
                    body.addView(spacer(8))
                    body.addView(TextView(this@PartyReportsActivity).apply {
                        text = Loc.t(
                            this@PartyReportsActivity,
                            "Note: Suppliers don't have their own 'profit' — this is a purchase summary.",
                            "نوٹ: سپلائرز کا اپنا منافع نہیں ہوتا — یہ خریداری کا خلاصہ ہے۔"
                        )
                        textSize = 11.5f
                        setTextColor(Color.parseColor(textGray))
                        setPadding(0, 6, 0, 0)
                    })
                }
            }

            AlertDialog.Builder(this@PartyReportsActivity)
                .setView(content)
                .setPositiveButton(Loc.t(this@PartyReportsActivity, "Close", "بند کریں"), null)
                .show()
        }
    }

    // ---- shared dialog container: icon + title header (matches summaryCard/navRow icon
    // treatment) + scrollable body card. Keeps the same child order the show*() functions
    // above rely on — index 0 header, index 1 ScrollView wrapping the body LinearLayout —
    // so none of that access code had to change. ----
    private fun reportContainer(icon: String, accentHex: String, tintHex: String, title: String, partyName: String): LinearLayout {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(4, 4, 4, 4) }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 20, 20, 12)
        }
        headerRow.addView(FrameLayout(this).apply {
            val size = (38 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(tintHex)) }
            addView(TextView(this@PartyReportsActivity).apply {
                text = icon; textSize = 15f; gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            })
        })
        val headerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 0, 0, 0)
        }
        headerCol.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, Typeface.BOLD)
        })
        headerCol.addView(TextView(this).apply {
            text = partyName
            textSize = 12f
            setTextColor(Color.parseColor(accentHex))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 2, 0, 0)
        })
        headerRow.addView(headerCol)
        outer.addView(headerRow)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 14, 18, 14)
            background = strokedBg(border, cardBg, 16)
            applyElevation(this, 1f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(20, 0, 20, 20)
            }
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (350 * resources.displayMetrics.density).toInt()
            )
            addView(body)
        }
        // outer.getChildAt(1) == this ScrollView, and its single child is `body` — the
        // show*() functions above rely on exactly that shape to reach the body container.
        outer.addView(scroll)
        return outer
    }

    private fun rowText(left: String, right: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4, 8, 4, 8)
            addView(TextView(this@PartyReportsActivity).apply {
                text = left; textSize = 13.5f
                setTextColor(Color.parseColor(textDark))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@PartyReportsActivity).apply {
                text = right; textSize = 13.5f; gravity = Gravity.END
                setTextColor(Color.parseColor(primary))
                setTypeface(typeface, Typeface.BOLD)
            })
        }
    }

    private fun plDivider(): View {
        return View(this).apply {
            setBackgroundColor(Color.parseColor(border))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                setMargins(0, 8, 0, 8)
            }
        }
    }

    private fun emptyText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor(textGray))
            textSize = 13f
            setPadding(0, 6, 0, 6)
        }
    }

    private fun formatQty(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    // ================= PREMIUM HEADER (matches Reports/Stock Report exactly) =================
    private fun premiumHeader(icon: String, title: String, subtitle: String): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(26, 22, 26, 22)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(primary), Color.parseColor(primaryDark))
            ).apply { cornerRadius = 22f }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 20) }
            applyElevation(this, 10f)
        }
        header.addView(TextView(this).apply {
            text = "‹"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = ovalBg("#33FFFFFF")
            val px = (36 * resources.displayMetrics.density).toInt()
            width = px; height = px
            setOnClickListener { finish() }
        })
        header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(14, 1) })
        header.addView(circleIcon(icon, "#5C4DFF", 42))
        header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(16, 1) })
        val headerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerCol.addView(TextView(this).apply {
            text = title
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        headerCol.addView(TextView(this).apply {
            text = subtitle
            textSize = 11f
            setTextColor(Color.parseColor("#D8D3FF"))
            setPadding(0, 4, 0, 0)
        })
        header.addView(headerCol)
        return header
    }

    // ---- Nav row (matches Reports' navRow used for Sale History / Party Reports / etc.) ----
    private fun navRow(
        icon: String,
        accentHex: String,
        tintHex: String,
        title: String,
        subtitle: String,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 18, 20, 18)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }

            addView(FrameLayout(this@PartyReportsActivity).apply {
                val size = (42 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(tintHex)) }
                addView(TextView(this@PartyReportsActivity).apply {
                    text = icon; textSize = 17f; gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                })
            })

            val textCol = LinearLayout(this@PartyReportsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 0, 8, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(this@PartyReportsActivity).apply {
                text = title
                textSize = 14.5f
                setTextColor(Color.parseColor(textDark))
                setTypeface(typeface, Typeface.BOLD)
            })
            if (subtitle.isNotBlank()) {
                textCol.addView(TextView(this@PartyReportsActivity).apply {
                    text = subtitle
                    textSize = 11.5f
                    setTextColor(Color.parseColor(textGray))
                    setPadding(0, 3, 0, 0)
                })
            }
            addView(textCol)

            addView(TextView(this@PartyReportsActivity).apply {
                text = "\u203A"
                textSize = 18f
                setTextColor(Color.parseColor(accentHex))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(8, 0, 4, 0)
            })
        }
    }

    private fun navDivider(): View {
        return View(this).apply {
            setBackgroundColor(Color.parseColor(border))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(20, 0, 20, 0)
            }
        }
    }

    private fun sectionHeader(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 12.5f
            setTextColor(Color.parseColor(textGray))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(4, 0, 0, 10)
        }
    }

    private fun filterPill(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 20, 0, 20)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
    }

    // ================= SHARED UI HELPERS (matches Reports/Stock Report exactly) =================
    private fun circleIcon(label: String, colorHex: String, sizeDp: Int) = TextView(this).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        background = ovalBg(colorHex)
        val px = (sizeDp * resources.displayMetrics.density).toInt()
        width = px; height = px
    }

    private fun ovalBg(colorHex: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(colorHex))
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
