package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.ReturnLine
import com.grocerypos.v11.smallestUnitFactor
import com.grocerypos.v11.toSmallestUnits
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class HistoryActivity : AppCompatActivity() {
    private lateinit var tabRow: LinearLayout
    private lateinit var listContainer: LinearLayout
    private var showingSales = true

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(16))
            setBackgroundColor(Color.parseColor("#F4F6F8"))
        }
        root.addView(TextView(this).apply { text = "History"; textSize = 22f; setTextColor(Color.parseColor("#0B2545")); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        root.addView(TextView(this).apply { text = "Kisi bhi entry par tap karke detail dekhein"; textSize = 12f; setTextColor(Color.parseColor("#7C8798")); setPadding(0,0,0,dp(16)) })
        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(tabRow)
        root.addView(divider())
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)
        setContentView(ScrollView(this).apply { addView(root) })
        buildTabs()
        showSales()
    }

    override fun onResume() { super.onResume(); if (showingSales) loadSales() else loadPurchases() }

    private fun buildTabs() {
        tabRow.removeAllViews()
        tabRow.addView(Button(this).apply { text = "SALES"; setTextColor(Color.WHITE); background = roundedBackground(if (showingSales) "#0B2545" else "#90A4AE", 14); layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(0,0,dp(8),0) }; setOnClickListener { showSales() } })
        tabRow.addView(Button(this).apply { text = "PURCHASES"; setTextColor(Color.WHITE); background = roundedBackground(if (!showingSales) "#0F9B8E" else "#90A4AE", 14); layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(8),0,0,0) }; setOnClickListener { showPurchases() } })
    }

    private fun showSales() { showingSales = true; buildTabs(); loadSales() }
    private fun showPurchases() { showingSales = false; buildTabs(); loadPurchases() }

    private fun loadSales() {
        lifecycleScope.launch {
            val list = PosDatabase.get(this@HistoryActivity).saleDao().allSales()
            listContainer.removeAllViews()
            if (list.isEmpty()) { listContainer.addView(emptyText("Koi sale nahi hui")); return@launch }
            val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            for (s in list) listContainer.addView(row(s.invoice, s.customerName, s.total, fmt.format(Date(s.createdAt)), "#0B2545", s.status == "returned") { openSaleDetail(s.invoice) })
        }
    }

    private fun loadPurchases() {
        lifecycleScope.launch {
            val list = PosDatabase.get(this@HistoryActivity).purchaseDao().allPurchases()
            listContainer.removeAllViews()
            if (list.isEmpty()) { listContainer.addView(emptyText("Koi purchase nahi hui")); return@launch }
            val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            for (p in list) listContainer.addView(row(p.billNo, p.supplierName, p.total, fmt.format(Date(p.createdAt)), "#0F9B8E", p.status == "returned") { openPurchaseDetail(p.billNo) })
        }
    }

    private fun openSaleDetail(invoice: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@HistoryActivity)
            val sale = db.saleDao().findSale(invoice) ?: return@launch
            val items = db.saleDao().itemsForInvoice(invoice)
            val content = detailContainer("Sale: $invoice", "#0B2545")
            val body = content.getChildAt(1) as LinearLayout
            if (sale.status == "returned") body.addView(returnedBanner())
            body.addView(kv("Total", "Rs %.2f".format(sale.total))); body.addView(kv("Paid", "Rs %.2f".format(sale.paid)))
            body.addView(spacer()); body.addView(sectionTitle("Items"))
            for (it in items) body.addView(itemRow(it.product, "${it.qty} x ${it.unitPrice}", "Rs %.2f".format(it.amount)))
            val dialog = AlertDialog.Builder(this@HistoryActivity).setView(content).create()
            val footer = content.getChildAt(2) as LinearLayout
            footer.addView(Button(this@HistoryActivity).apply { text = "Close"; layoutParams = LinearLayout.LayoutParams(0,-2,1f); setOnClickListener { dialog.dismiss() } })
            if (sale.status != "returned") {
                footer.addView(Button(this@HistoryActivity).apply { text = "Return"; setTextColor(Color.WHITE); background = roundedBackground("#EF6C00",12); layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(6),0,0,0) }; setOnClickListener { returnSale(invoice); dialog.dismiss() } })
                footer.addView(Button(this@HistoryActivity).apply { text = "Delete"; setTextColor(Color.WHITE); background = roundedBackground("#C62828",12); layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(6),0,0,0) }; setOnClickListener { deleteSale(invoice); dialog.dismiss() } })
            }
            dialog.show()
        }
    }

    // ---- FIX: stock reversal now converts si.qty (stored in whatever unit was entered,
    // e.g. "dozen") to smallest-unit stock via Product.toSmallestUnits() before touching
    // stock, same as SaleActivity.deleteSale() / SaleHistoryActivity.deleteSale(). Previously
    // this called db.productDao().increase(it.barcode, it.qty) directly, which added back the
    // raw entered-unit number as if it were already smallest units — wrong for any product
    // with a secondary/tertiary unit. ----
    // FIX (Phase 1 - Data Safety): stock increase + return-row insert + balance reversal +
    // markReturned are now one atomic transaction (previously separate sequential writes —
    // a crash partway through could leave stock/balance updated but the sale still "active",
    // or vice versa).
    // BUILD FIX: toSmallestUnits(...).roundToInt() is Int, but the `it.qty` fallback is a
    // Double (SaleItem.qty) — mixing them in `?:` produced an unresolved Number/Comparable
    // captured type that increase(barcode, Int) couldn't accept ("Argument type mismatch...
    // but 'kotlin.Int' was expected", :app:compileDebugKotlin failure). Fallback now rounds
    // it.qty to Int too, so both branches of the elvis are the same type.
    private fun returnSale(invoice: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@HistoryActivity); val sale = db.saleDao().findSale(invoice) ?: return@launch; if (sale.status == "returned") return@launch
            val items = db.saleDao().itemsForInvoice(invoice)
            db.withTransaction {
                for (it in items) {
                    val p = db.productDao().find(it.barcode)
                    val smallestQty = p?.toSmallestUnits(it.qty.toDouble(), it.unit.ifBlank { p.unit })?.roundToInt() ?: it.qty.roundToInt()
                    db.productDao().increase(it.barcode, smallestQty)
                    db.returnDao().insert(ReturnLine(reference = invoice, type = "sale", barcode = it.barcode, qty = it.qty.toDouble(), amount = it.amount))
                }
                if (sale.customerId != null && sale.paid < sale.total) db.customerDao().addBalance(sale.customerId, -(sale.total - sale.paid))
                db.cashTransactionDao().deleteByReference(invoice); db.saleDao().markReturned(invoice)
            }
            loadSales()
        }
    }

    // FIX (Phase 1 - Data Safety): same atomic-transaction treatment as returnSale() above.
    // BUILD FIX: same Int/Double elvis mismatch as returnSale() above — fallback now rounds
    // it.qty to Int.
    private fun deleteSale(invoice: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@HistoryActivity); val sale = db.saleDao().findSale(invoice) ?: return@launch
            val items = db.saleDao().itemsForInvoice(invoice)
            db.withTransaction {
                for (it in items) {
                    val p = db.productDao().find(it.barcode)
                    val smallestQty = p?.toSmallestUnits(it.qty.toDouble(), it.unit.ifBlank { p.unit })?.roundToInt() ?: it.qty.roundToInt()
                    db.productDao().increase(it.barcode, smallestQty)
                }
                if (sale.customerId != null && sale.paid < sale.total) db.customerDao().addBalance(sale.customerId, -(sale.total - sale.paid))
                db.cashTransactionDao().deleteByReference(invoice); db.saleDao().deleteItems(invoice); db.saleDao().deleteSale(invoice)
            }
            loadSales()
        }
    }

    private fun openPurchaseDetail(billNo: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@HistoryActivity)
            val purchase = db.purchaseDao().findPurchase(billNo) ?: return@launch
            val items = db.purchaseDao().itemsForBill(billNo)
            val content = detailContainer("Purchase: $billNo", "#0F9B8E")
            val body = content.getChildAt(1) as LinearLayout
            if (purchase.status == "returned") body.addView(returnedBanner())
            body.addView(kv("Total", "Rs %.2f".format(purchase.total))); body.addView(kv("Paid", "Rs %.2f".format(purchase.paid)))
            body.addView(spacer()); body.addView(sectionTitle("Items"))
            // ---- FIX: was showing the raw barcode (it.barcode) instead of the product name.
            // PurchaseItem only stores the barcode, so look up the product to get its name —
            // same pattern already used by PurchaseHistoryActivity.loadBillItems(). Falls back
            // to the barcode only if the product record itself is missing/deleted. ----
            for (it in items) {
                val product = db.productDao().find(it.barcode)
                val displayName = product?.name ?: it.barcode
                val u = if (it.unit.isBlank()) "" else " ${it.unit}"
                body.addView(itemRow(displayName, "${it.qty}$u x ${it.unitCost}", "Rs %.2f".format(it.amount)))
            }
            val dialog = AlertDialog.Builder(this@HistoryActivity).setView(content).create()
            val footer = content.getChildAt(2) as LinearLayout
            footer.addView(Button(this@HistoryActivity).apply { text = "Close"; layoutParams = LinearLayout.LayoutParams(0,-2,1f); setOnClickListener { dialog.dismiss() } })
            if (purchase.status != "returned") {
                footer.addView(Button(this@HistoryActivity).apply { text = "Edit"; setTextColor(Color.WHITE); background = roundedBackground("#1565C0",12); layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(6),0,0,0) }; setOnClickListener { dialog.dismiss(); startActivity(Intent(this@HistoryActivity, PurchaseActivity::class.java).putExtra(PurchaseActivity.EXTRA_BILL_NO, billNo)) } })
                footer.addView(Button(this@HistoryActivity).apply { text = "Return"; setTextColor(Color.WHITE); background = roundedBackground("#EF6C00",12); layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(6),0,0,0) }; setOnClickListener { returnPurchase(billNo); dialog.dismiss() } })
                footer.addView(Button(this@HistoryActivity).apply { text = "Delete"; setTextColor(Color.WHITE); background = roundedBackground("#C62828",12); layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(6),0,0,0) }; setOnClickListener { deletePurchase(billNo); dialog.dismiss() } })
            }
            dialog.show()
        }
    }

    // ---- FIX: mirrors PurchaseActivity.reverseStockAndCostForItems() — converts item.qty via
    // Product.toSmallestUnits() before touching stock (previously used the raw entered-unit qty,
    // truncated with .toInt(), directly on decreaseForce — wrong for multi-unit products and lost
    // fractional qty), and also reverses the weighted-average cost impact so product.cost isn't
    // left distorted after a delete/return (previously not reversed at all). ----
    private suspend fun reverseStockAndCostForPurchaseItems(db: PosDatabase, items: List<com.grocerypos.v11.PurchaseItem>) {
        for (pi in items) {
            val product = db.productDao().find(pi.barcode) ?: continue
            val factor = product.smallestUnitFactor()
            val smallestQty = product.toSmallestUnits(pi.qty, pi.unit.ifBlank { product.unit }).roundToInt()
            if (smallestQty <= 0) continue

            val currentCostPerSmallest = if (factor > 0) product.cost / factor else product.cost
            val currentStock = product.stock
            val newStock = currentStock - smallestQty

            val totalValueBefore = currentStock * currentCostPerSmallest
            val totalValueAfterRemoval = (totalValueBefore - pi.amount).coerceAtLeast(0.0)
            val newCostPerSmallest = if (newStock > 0) totalValueAfterRemoval / newStock else 0.0

            db.productDao().decreaseForce(pi.barcode, smallestQty)
            db.productDao().updateCost(pi.barcode, newCostPerSmallest * factor)
        }
    }

    // FIX (Phase 1 - Data Safety): stock/cost reversal + return-row inserts + balance
    // reversal + markReturned now run as one atomic transaction.
    private fun returnPurchase(billNo: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@HistoryActivity); val purchase = db.purchaseDao().findPurchase(billNo) ?: return@launch; if (purchase.status == "returned") return@launch
            val items = db.purchaseDao().itemsForBill(billNo)
            db.withTransaction {
                reverseStockAndCostForPurchaseItems(db, items)
                for (item in items) {
                    db.returnDao().insert(ReturnLine(reference = billNo, type = "purchase", barcode = item.barcode, qty = item.qty, amount = item.amount))
                }
                if (purchase.supplierId != null && purchase.paid < purchase.total) db.supplierDao().addBalance(purchase.supplierId, -(purchase.total - purchase.paid))
                db.cashTransactionDao().deleteByReference(billNo); db.purchaseDao().markReturned(billNo)
            }
            loadPurchases()
        }
    }

    // FIX (Phase 1 - Data Safety): same atomic-transaction treatment as returnPurchase() above.
    private fun deletePurchase(billNo: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@HistoryActivity); val purchase = db.purchaseDao().findPurchase(billNo) ?: return@launch
            val items = db.purchaseDao().itemsForBill(billNo)
            db.withTransaction {
                reverseStockAndCostForPurchaseItems(db, items)
                if (purchase.supplierId != null && purchase.paid < purchase.total) db.supplierDao().addBalance(purchase.supplierId, -(purchase.total - purchase.paid))
                db.cashTransactionDao().deleteByReference(billNo); db.paymentDao().deleteByReference(billNo); db.purchaseDao().deleteItems(billNo); db.purchaseDao().deletePurchase(billNo)
            }
            loadPurchases()
        }
    }

    private fun detailContainer(title: String, colorHex: String): LinearLayout {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply { setPadding(dp(20), dp(16), dp(20), dp(16)); setBackgroundColor(Color.parseColor(colorHex)) }
        header.addView(TextView(this).apply { text = title; textSize = 17f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        outer.addView(header)
        outer.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(8)) })
        outer.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(8), dp(16), dp(16)) })
        return outer
    }
    private fun returnedBanner() = LinearLayout(this).apply { setPadding(dp(12), dp(8), dp(12), dp(8)); background = roundedBackground("#FFEBEE", 10); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0,0,0,dp(12)) }; addView(TextView(this@HistoryActivity).apply { text = "RETURNED"; setTextColor(Color.parseColor("#C62828")); setTypeface(typeface, android.graphics.Typeface.BOLD) }) }
    private fun kv(l: String, v: String) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(TextView(this@HistoryActivity).apply { text = l; setTextColor(Color.GRAY); layoutParams = LinearLayout.LayoutParams(0,-2,1f) }); addView(TextView(this@HistoryActivity).apply { text = v }) }
    private fun sectionTitle(t: String) = TextView(this).apply { text = t; setTypeface(typeface, android.graphics.Typeface.BOLD) }
    private fun itemRow(n: String, q: String, a: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        val top = LinearLayout(this@HistoryActivity).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(TextView(this@HistoryActivity).apply {
            text = n; textSize = 14f
            setTextColor(Color.parseColor("#1A1D2E"))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        top.addView(TextView(this@HistoryActivity).apply {
            text = a; textSize = 14f
            setTextColor(Color.parseColor("#1A1D2E"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        addView(top)
        addView(TextView(this@HistoryActivity).apply {
            text = q; textSize = 12.5f
            setTextColor(Color.GRAY)
            gravity = Gravity.END
            setPadding(0, dp(2), 0, 0)
        })
        addView(divider())
    }
    private fun spacer() = View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(12)) }
    private fun row(title: String, subtitle: String, amount: Double, date: String, color: String, returned: Boolean, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); setOnClickListener { onClick() }
        val top = LinearLayout(this@HistoryActivity).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(TextView(this@HistoryActivity).apply { text = title; layoutParams = LinearLayout.LayoutParams(0,-2,1f) })
        top.addView(TextView(this@HistoryActivity).apply { text = "Rs %.2f".format(amount); setTextColor(Color.parseColor(color)) })
        addView(top); addView(TextView(this@HistoryActivity).apply { text = subtitle }); addView(TextView(this@HistoryActivity).apply { text = date; setTextColor(Color.GRAY) }); addView(divider())
    }
    private fun emptyText(t: String) = TextView(this).apply { text = t }
    private fun roundedBackground(c: String, r: Int) = GradientDrawable().apply { setColor(Color.parseColor(c)); cornerRadius = r * resources.displayMetrics.density }
    private fun divider() = View(this).apply { setBackgroundColor(0xFFEEEEEE.toInt()); layoutParams = LinearLayout.LayoutParams(-1, dp(1)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
