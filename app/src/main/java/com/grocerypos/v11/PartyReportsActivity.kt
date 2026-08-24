package com.grocerypos.v11.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
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

class PartyReportsActivity : AppCompatActivity() {

    private val bg = "#F3F4F9"
    private val cardWhite = "#FFFFFF"
    private val textDark = "#1A1D2E"
    private val textMuted = "#8A8FA3"
    private val blue = "#1565C0"
    private val orange = "#EF6C00"
    private val green = "#2E7D32"
    private val red = "#C62828"
    private val teal = "#00695C"

    private lateinit var listContainer: LinearLayout
    private lateinit var customersTab: Button
    private lateinit var suppliersTab: Button
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
            setPadding(28, 40, 28, 32)
            setBackgroundColor(Color.parseColor(bg))
        }

        root.addView(TextView(this).apply {
            text = Loc.t(this@PartyReportsActivity, "Party Reports", "پارٹی رپورٹس")
            textSize = 21f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(4, 0, 0, 20)
        })

        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        customersTab = Button(this).apply {
            text = Loc.t(this@PartyReportsActivity, "CUSTOMERS", "کسٹمرز")
            textSize = 12f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,8,0) }
            setOnClickListener { showingCustomers = true; refreshTabs(); loadParties() }
        }
        suppliersTab = Button(this).apply {
            text = Loc.t(this@PartyReportsActivity, "SUPPLIERS", "سپلائرز")
            textSize = 12f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8,0,0,0) }
            setOnClickListener { showingCustomers = false; refreshTabs(); loadParties() }
        }
        tabRow.addView(customersTab)
        tabRow.addView(suppliersTab)
        root.addView(tabRow)

        root.addView(spacer(18))
        root.addView(TextView(this).apply {
            text = Loc.t(this@PartyReportsActivity, "Tap a party to select a report", "رپورٹ منتخب کرنے کے لیے پارٹی پر ٹیپ کریں")
            textSize = 12f
            setTextColor(Color.parseColor(textMuted))
            setPadding(4, 0, 0, 10)
        })

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        }
        setContentView(scroll)

        refreshTabs()
        loadParties()
    }

    private fun refreshTabs() {
        customersTab.background = roundedBg(if (showingCustomers) blue else "#90A4AE", 14)
        suppliersTab.background = roundedBg(if (!showingCustomers) orange else "#90A4AE", 14)
    }

    private fun loadParties() {
        listContainer.removeAllViews()
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PartyReportsActivity)
            if (showingCustomers) {
                val customers = db.customerDao().all().first()
                if (customers.isEmpty()) listContainer.addView(emptyText(Loc.t(this@PartyReportsActivity, "No customers yet", "کوئی کسٹمر نہیں ہے")))
                customers.forEach { c ->
                    listContainer.addView(partyRow(c.name, c.balance) {
                        showReportMenu(true, c.id, c.name, c.openingBalance)
                    })
                }
            } else {
                val suppliers = db.supplierDao().all().first()
                if (suppliers.isEmpty()) listContainer.addView(emptyText(Loc.t(this@PartyReportsActivity, "No suppliers yet", "کوئی سپلائر نہیں ہے")))
                suppliers.forEach { s ->
                    listContainer.addView(partyRow(s.name, s.balance) {
                        showReportMenu(false, s.id, s.name, s.openingBalance)
                    })
                }
            }
        }
    }

    private fun partyRow(name: String, balance: Double, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 18, 20, 18)
            background = roundedBg(cardWhite, 18)
            elevation = 3f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 10) }

            addView(TextView(this@PartyReportsActivity).apply {
                text = name
                textSize = 15f
                setTextColor(Color.parseColor(textDark))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@PartyReportsActivity).apply {
                text = "Rs %.2f".format(balance)
                textSize = 13f
                setTextColor(Color.parseColor(if (balance > 0) red else green))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            setOnClickListener { onClick() }
        }
    }

    // ---- Tap a party -> choose which report ----
    private fun showReportMenu(isCustomer: Boolean, id: Long, name: String, opening: Double) {
        val plLabel = if (isCustomer)
            Loc.t(this, "Customer-wise Profit", "کسٹمر کے لحاظ سے منافع")
        else
            Loc.t(this, "Purchase Summary", "خریداری کا خلاصہ")

        val statementLabel = if (isCustomer)
            Loc.t(this, "Customer Statement", "کسٹمر اسٹیٹمنٹ")
        else
            Loc.t(this, "Supplier Statement", "سپلائر اسٹیٹمنٹ")

        val options = arrayOf(
            "📦 " + Loc.t(this, "Party Report by Item", "آئٹم کے لحاظ سے پارٹی رپورٹ"),
            "📒 " + Loc.t(this, "Customer Ledger", "کسٹمر لیجر"),
            "💵 " + Loc.t(this, "Payment History", "ادائیگی کی تاریخ"),
            "📜 " + statementLabel,
            "🧾 " + Loc.t(this, "Sale/Purchase by Party", "پارٹی کے لحاظ سے سیل/خریداری"),
            "📊 " + plLabel
        )
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showItemReport(isCustomer, id, name)
                    1 -> showLedger(isCustomer, id, name, opening)
                    2 -> showPaymentHistory(isCustomer, id, name)
                    3 -> showStatement(isCustomer, id, name, opening)
                    4 -> showTransactions(isCustomer, id, name)
                    5 -> showPartyPL(isCustomer, id, name)
                }
            }
            .setNegativeButton(Loc.t(this, "Cancel", "منسوخ کریں"), null)
            .show()
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

            val content = reportContainer(Loc.t(this@PartyReportsActivity, "Item Report", "آئٹم رپورٹ") + " — $name")
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

            val content = reportContainer(Loc.t(this@PartyReportsActivity, "Ledger", "لیجر") + " — $name")
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
                (getChildAt(0) as TextView).setTypeface(null, android.graphics.Typeface.BOLD)
                (getChildAt(1) as TextView).setTextColor(Color.parseColor(if (running > 0) red else green))
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
                setTextColor(Color.parseColor(textMuted))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f)
            })
            addView(TextView(this@PartyReportsActivity).apply {
                text = Loc.t(this@PartyReportsActivity, "Debit", "ڈیبٹ")
                textSize = 11f
                gravity = Gravity.END
                setTextColor(Color.parseColor(red))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@PartyReportsActivity).apply {
                text = Loc.t(this@PartyReportsActivity, "Credit", "کریڈٹ")
                textSize = 11f
                gravity = Gravity.END
                setTextColor(Color.parseColor(green))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
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
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f)
            })
            top.addView(TextView(this@PartyReportsActivity).apply {
                text = if (dr > 0) "Rs %.2f".format(dr) else "—"
                textSize = 13f
                gravity = Gravity.END
                setTextColor(Color.parseColor(if (dr > 0) red else textMuted))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            top.addView(TextView(this@PartyReportsActivity).apply {
                text = if (cr > 0) "Rs %.2f".format(cr) else "—"
                textSize = 13f
                gravity = Gravity.END
                setTextColor(Color.parseColor(if (cr > 0) green else textMuted))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(12, 0, 0, 0)
            })
            addView(top)
            addView(TextView(this@PartyReportsActivity).apply {
                text = Loc.t(this@PartyReportsActivity, "Balance", "بیلنس") + ": Rs %.2f".format(balance)
                textSize = 11.5f
                setTextColor(Color.parseColor(if (balance > 0) red else green))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
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

            val content = reportContainer(Loc.t(this@PartyReportsActivity, "Payment History", "ادائیگی کی تاریخ") + " — $name")
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
                    (getChildAt(0) as TextView).setTypeface(null, android.graphics.Typeface.BOLD)
                    (getChildAt(1) as TextView).setTextColor(Color.parseColor(green))
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

            addView(TextView(this@PartyReportsActivity).apply {
                text = "💵"
                textSize = 15f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#E8F5E9")) }
                width = (32 * resources.displayMetrics.density).toInt()
                height = (32 * resources.displayMetrics.density).toInt()
            })

            val col = LinearLayout(this@PartyReportsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14, 0, 8, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this@PartyReportsActivity).apply {
                text = date
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(textDark))
            })
            col.addView(TextView(this@PartyReportsActivity).apply {
                text = against
                textSize = 11f
                setTextColor(Color.parseColor(textMuted))
            })
            addView(col)

            addView(TextView(this@PartyReportsActivity).apply {
                text = "Rs %.2f".format(amount)
                textSize = 13.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(green))
            })
        }
    }

    // ================= 4) Party Statement (running balance) =================
    private fun showStatement(isCustomer: Boolean, id: Long, name: String, opening: Double) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PartyReportsActivity)
            val fmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            var running = opening

            val content = reportContainer(Loc.t(this@PartyReportsActivity, "Statement", "اسٹیٹمنٹ") + " — $name")
            val body = (content.getChildAt(1) as ScrollView).getChildAt(0) as LinearLayout

            body.addView(rowText(Loc.t(this@PartyReportsActivity, "Opening Balance", "ابتدائی بیلنس"), "Rs %.2f".format(opening)).apply {
                (getChildAt(0) as TextView).setTypeface(null, android.graphics.Typeface.BOLD)
            })
            body.addView(plDivider())

            if (isCustomer) {
                val sales = db.saleDao().salesByCustomer(id).sortedBy { it.createdAt }
                if (sales.isEmpty()) body.addView(emptyText(Loc.t(this@PartyReportsActivity, "No transactions yet", "کوئی لین دین نہیں ہے")))
                sales.forEach { s ->
                    val outstanding = s.total - s.paid
                    running += outstanding
                    // ---- No invoice number shown — date is the row's identifier ----
                    body.addView(statementRow(fmt.format(Date(s.createdAt)), s.total, running))
                }
            } else {
                val purchases = db.purchaseDao().purchasesBySupplier(id).sortedBy { it.createdAt }
                if (purchases.isEmpty()) body.addView(emptyText(Loc.t(this@PartyReportsActivity, "No transactions yet", "کوئی لین دین نہیں ہے")))
                purchases.forEach { p ->
                    val outstanding = p.total - p.paid
                    running += outstanding
                    // ---- No bill number shown — date is the row's identifier ----
                    body.addView(statementRow(fmt.format(Date(p.createdAt)), p.total, running))
                }
            }

            body.addView(plDivider())
            body.addView(rowText(Loc.t(this@PartyReportsActivity, "Closing Balance", "اختتامی بیلنس"), "Rs %.2f".format(running)).apply {
                (getChildAt(0) as TextView).setTypeface(null, android.graphics.Typeface.BOLD)
                (getChildAt(1) as TextView).setTextColor(Color.parseColor(if (running > 0) red else green))
            })

            AlertDialog.Builder(this@PartyReportsActivity)
                .setView(content)
                .setPositiveButton(Loc.t(this@PartyReportsActivity, "Close", "بند کریں"), null)
                .show()
        }
    }

    // ---- Reference/invoice number removed — date is now the only identifier shown ----
    private fun statementRow(date: String, total: Double, balanceAfter: Double): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 10, 4, 10)
            val top = LinearLayout(this@PartyReportsActivity).apply { orientation = LinearLayout.HORIZONTAL }
            top.addView(TextView(this@PartyReportsActivity).apply {
                text = date; textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(textDark))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            top.addView(TextView(this@PartyReportsActivity).apply {
                text = "Rs %.2f".format(total); textSize = 13f
                setTextColor(Color.parseColor(textMuted))
            })
            addView(top)
            addView(TextView(this@PartyReportsActivity).apply {
                text = Loc.t(this@PartyReportsActivity, "Balance", "بیلنس") + ": Rs %.2f".format(balanceAfter)
                textSize = 12f
                setTextColor(Color.parseColor(if (balanceAfter > 0) red else green))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        }
    }

    // ================= 5) Sale/Purchase by Party (plain transaction list) =================
    private fun showTransactions(isCustomer: Boolean, id: Long, name: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PartyReportsActivity)
            val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())

            val title = if (isCustomer) Loc.t(this@PartyReportsActivity, "Sales", "سیلز") else Loc.t(this@PartyReportsActivity, "Purchases", "خریداریاں")
            val content = reportContainer("$title — $name")
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
            val content = reportContainer("$title — $name")
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
                    val profitColor = if (profit >= 0) green else red

                    body.addView(rowText(Loc.t(this@PartyReportsActivity, "Total Sales (bills)", "کل سیلز (بلز)"), "${sales.size}"))
                    body.addView(rowText(Loc.t(this@PartyReportsActivity, "Revenue", "آمدنی"), "Rs %.2f".format(revenue)))
                    body.addView(rowText(Loc.t(this@PartyReportsActivity, "Cost of Goods", "سامان کی لاگت"), "Rs %.2f".format(cost)))
                    body.addView(plDivider())
                    body.addView(rowText(if (profit >= 0) Loc.t(this@PartyReportsActivity, "Net Profit", "خالص منافع") else Loc.t(this@PartyReportsActivity, "Net Loss", "خالص نقصان"), "Rs %.2f".format(profit)).apply {
                        (getChildAt(0) as TextView).setTypeface(null, android.graphics.Typeface.BOLD)
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
                        (getChildAt(0) as TextView).setTypeface(null, android.graphics.Typeface.BOLD)
                        (getChildAt(1) as TextView).setTextColor(Color.parseColor(if (totalDue > 0) red else green))
                    })
                    body.addView(spacer(8))
                    body.addView(TextView(this@PartyReportsActivity).apply {
                        text = Loc.t(
                            this@PartyReportsActivity,
                            "Note: Suppliers don't have their own 'profit' — this is a purchase summary.",
                            "نوٹ: سپلائرز کا اپنا منافع نہیں ہوتا — یہ خریداری کا خلاصہ ہے۔"
                        )
                        textSize = 11.5f
                        setTextColor(Color.parseColor(textMuted))
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

    // ---- shared dialog container: title header (white) + scrollable body ----
    private fun reportContainer(title: String): LinearLayout {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(24, 24, 24, 12)
        })
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 0, 24, 12)
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (350 * resources.displayMetrics.density).toInt()
            )
            addView(body)
        }
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
                setTextColor(Color.parseColor(blue))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        }
    }

    private fun plDivider(): View {
        return View(this).apply {
            setBackgroundColor(Color.parseColor("#EDEEF5"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                setMargins(0, 8, 0, 8)
            }
        }
    }

    private fun emptyText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor(textMuted))
            textSize = 13f
            setPadding(0, 6, 0, 6)
        }
    }

    private fun roundedBg(colorHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(colorHex))
        cornerRadius = radius.toFloat()
    }

    private fun spacer(heightDp: Int) = View(this).apply {
        val px = (heightDp * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px)
    }

    private fun formatQty(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}
