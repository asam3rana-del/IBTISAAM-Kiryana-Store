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
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.ReturnLine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        root.addView(TextView(this).apply {
            text = "History"; textSize = 22f
            setTextColor(Color.parseColor("#0B2545"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0,0,0,dp(4))
        })
        root.addView(TextView(this).apply {
            text = "Kisi bhi entry par tap karke poori detail dekhein"
            textSize = 12f; setTextColor(Color.parseColor("#7C8798"))
            setPadding(0,0,0,dp(16))
        })
        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(tabRow)
        root.addView(divider())
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)
        setContentView(ScrollView(this).apply { addView(root) })
        buildTabs()
        showSales()
    }

    override fun onResume() {
        super.onResume()
        if (showingSales) loadSales() else loadPurchases()
    }

    private fun buildTabs() {
        tabRow.removeAllViews()
        tabRow.addView(Button(this).apply {
            text = "SALES"; setTextColor(Color.WHITE)
            background = roundedBackground(if (showingSales) "#0B2545" else "#90A4AE", 14)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0,0,dp(8),0) }
            setOnClickListener { showSales() }
        })
        tabRow.addView(Button(this).apply {
            text = "PURCHASES"; setTextColor(Color.WHITE)
            background = roundedBackground(if (!showingSales) "#0F9B8E" else "#90A4AE", 14)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(8),0,0,0) }
            setOnClickListener { showPurchases() }
        })
    }

    private fun showSales() { showingSales = true; buildTabs(); loadSales() }
    private fun showPurchases() { showingSales = false; buildTabs(); loadPurchases() }

    private fun loadSales() {
        lifecycleScope.launch {
            val list = PosDatabase.get(this@HistoryActivity).saleDao().allSales()
            listContainer.removeAllViews()
            if (list.isEmpty()) {
                listContainer.addView(emptyText("Koi sale nahi hui abhi tak"))
                return@launch
            }
            val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            for (s in list) {
                listContainer.addView(row(s.invoice, "${s.customerName} • ${s.paymentMethod}", s.total, fmt.format(Date(s.createdAt)), "#0B2545", s.status == "returned") { openSaleDetail(s.invoice) })
            }
        }
    }

    private fun loadPurchases() {
        lifecycleScope.launch {
            val list = PosDatabase.get(this@HistoryActivity).purchaseDao().allPurchases()
            listContainer.removeAllViews()
            if (list.isEmpty()) {
                listContainer.addView(emptyText("Koi purchase nahi hui abhi tak"))
                return@launch
            }
            val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            for (p in list) {
                listContainer.addView(row(p.billNo, p.supplierName, p.total, fmt.format(Date(p.createdAt)), "#0F9B8E", p.status == "returned") { openPurchaseDetail(p.billNo) })
            }
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
            body.addView(kv("Total", "Rs %.2f".format(sale.total)))
            body.addView(kv("Paid", "Rs %.2f".format(sale.paid)))
            body.addView(kv("Payment", sale.paymentMethod))
            body.addView(kv("Type", sale.saleType))
            body.addView(spacer()); body.addView(sectionTitle("Items"))
            for (it in items) body.addView(itemRow(it.product, "${it.qty} x ${it.unitPrice}", "Rs %.2f".format(it.amount)))
            val dialog = AlertDialog.Builder(this@HistoryActivity).setView(content).create()
            val footer = content.getChildAt(2) as LinearLayout
            footer.addView(Button(this@HistoryActivity).apply { text = "Close"; layoutParams = LinearLayout.LayoutParams(0,-2,1f); setOnClickListener { dialog.dismiss() } })
            if (sale.status != "returned") {
                footer.addView(Button(this@HistoryActivity).apply {
                    text = "Return"; setTextColor(Color.WHITE); background = roundedBackground("#EF6C00",12)
                    layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(6),0,0,0) }
                    setOnClickListener {
                        AlertDialog.Builder(this@HistoryActivity).setTitle("Sale return karen?").setMessage("Stock wapas add ho jayega.")
                            .setPositiveButton("Return") { _,_ -> returnSale(invoice); dialog.dismiss() }.setNegativeButton("Cancel",null).show()
                    }
                })
                footer.addView(Button(this@HistoryActivity).apply {
                    text = "Delete"; setTextColor(Color.WHITE); background = roundedBackground("#C62828",12)
                    layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(6),0,0,0) }
                    setOnClickListener {
                        AlertDialog.Builder(this@HistoryActivity).setTitle("Sale delete karen?").setMessage("Undo nahi hoga.")
                            .setPositiveButton("Delete") { _,_ -> deleteSale(invoice); dialog.dismiss() }.setNegativeButton("Cancel",null).show()
                    }
                })
            }
            dialog.show()
        }
    }

    private fun returnSale(invoice: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@HistoryActivity)
            val sale = db.saleDao().findSale(invoice) ?: return@launch
            if (sale.status == "returned") return@launch
            val items = db.saleDao().itemsForInvoice(invoice)
            for (it in items) {
                db.productDao().increase(it.barcode, it.qty)
                db.returnDao().insert(ReturnLine(invoice, "sale", it.barcode, it.qty, it.amount))
            }
            if (sale.customerId != null && sale.paid < sale.total) db.customerDao().addBalance(sale.customerId, -(sale.total - sale.paid))
            db.cashTransactionDao().deleteByReference(invoice)
            db.saleDao().markReturned(invoice)
            Toast.makeText(this@HistoryActivity, "Sale return ho gayi", Toast.LENGTH_LONG).show()
            loadSales()
        }
    }

    private fun deleteSale(invoice: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@HistoryActivity)
            val sale = db.saleDao().findSale(invoice) ?: return@launch
            val items = db.saleDao().itemsForInvoice(invoice)
            for (it in items) db.productDao().increase(it.barcode, it.qty)
            if (sale.customerId != null && sale.paid < sale.total) db.customerDao().addBalance(sale.customerId, -(sale.total - sale.paid))
            db.cashTransactionDao().deleteByReference(invoice)
            db.saleDao().deleteItems(invoice); db.saleDao().deleteSale(invoice)
            Toast.makeText(this@HistoryActivity, "Sale delete ho gayi", Toast.LENGTH_LONG).show()
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
            body.addView(kv("Total", "Rs %.2f".format(purchase.total)))
            body.addView(kv("Paid", "Rs %.2f".format(purchase.paid)))
            body.addView(spacer()); body.addView(sectionTitle("Items"))
            for (it in items) {
                val unitLabel = if (it.unit.isBlank()) "" else " ${it.unit}"
                body.addView(itemRow(it.barcode, "${it.qty}$unitLabel x ${it.unitCost}", "Rs %.2f".format(it.amount)))
            }
            val dialog = AlertDialog.Builder(this@HistoryActivity).setView(content).create()
            val footer = content.getChildAt(2) as LinearLayout
            footer.addView(Button(this@HistoryActivity).apply { text = "Close"; layoutParams = LinearLayout.LayoutParams(0,-2,1f); setOnClickListener { dialog.dismiss() } })
            if (purchase.status != "returned") {
                footer.addView(Button(this@HistoryActivity).apply {
                    text = "Edit"; setTextColor(Color.WHITE); background = roundedBackground("#1565C0",12)
                    layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(6),0,0,0) }
                    setOnClickListener { dialog.dismiss(); startActivity(Intent(this@HistoryActivity, PurchaseActivity::class.java).putExtra(PurchaseActivity.EXTRA_BILL_NO, billNo)) }
                })
                footer.addView(Button(this@HistoryActivity).apply {
                    text = "Return"; setTextColor(Color.WHITE); background = roundedBackground("#EF6C00",12)
                    layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(6),0,0,0) }
                    setOnClickListener {
                        AlertDialog.Builder(this@HistoryActivity).setTitle("Purchase return karen?").setMessage("Stock kam ho jayega.")
                            .setPositiveButton("Return") { _,_ -> returnPurchase(billNo); dialog.dismiss() }.setNegativeButton("Cancel",null).show()
                    }
                })
                footer.addView(Button(this@HistoryActivity).apply {
                    text = "Delete"; setTextColor(Color.WHITE); background = roundedBackground("#C62828",12)
                    layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(6),0,0,0) }
                    setOnClickListener {
                        AlertDialog.Builder(this@HistoryActivity).setTitle("Purchase delete karen?").setMessage("Undo nahi hoga.")
                            .setPositiveButton("Delete") { _,_ -> deletePurchase(billNo); dialog.dismiss() }.setNegativeButton("Cancel",null).show()
                    }
                })
            }
            dialog.show()
        }
    }

    private fun returnPurchase(billNo: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@HistoryActivity)
            val purchase = db.purchaseDao().findPurchase(billNo) ?: return@launch
            if (purchase.status == "returned") return@launch
            val items = db.purchaseDao().itemsForBill(billNo)
            for (item in items) {
                db.productDao().decrease(item.barcode, item.qty)
                db.returnDao().insert(ReturnLine(billNo, "purchase", item.barcode, item.qty, item.amount))
            }
            if (purchase.supplierId != null && purchase.paid < purchase.total) db.supplierDao().addBalance(purchase.supplierId, -(purchase.total - purchase.paid))
            db.cashTransactionDao().deleteByReference(billNo)
            db.purchaseDao().markReturned(billNo)
            Toast.makeText(this@HistoryActivity, "Purchase return ho gayi", Toast.LENGTH_LONG).show()
            loadPurchases()
        }
    }

    private fun deletePurchase(billNo: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@HistoryActivity)
            val purchase = db.purchaseDao().findPurchase(billNo) ?: return@launch
            val items = db.purchaseDao().itemsForBill(billNo)
            for (item in items) db.productDao().decrease(item.barcode, item.qty)
            if (purchase.supplierId != null && purchase.paid < purchase.total) db.supplierDao().addBalance(purchase.supplierId, -(purchase.total - purchase.paid))
            db.cashTransactionDao().deleteByReference(billNo)
            db.paymentDao().deleteByReference(billNo)
            db.purchaseDao().deleteItems(billNo)
            db.purchaseDao().deletePurchase(billNo)
            Toast.makeText(this@HistoryActivity, "Purchase delete ho gayi", Toast.LENGTH_LONG).show()
            loadPurchases()
        }
    }

    // UI helpers
    private fun detailContainer(title: String, colorHex: String): LinearLayout {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply { setPadding(dp(20), dp(16), dp(20), dp(16)); setBackgroundColor(Color.parseColor(colorHex)) }
        header.addView(TextView(this).apply { text = title; textSize = 17f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        outer.addView(header)
        outer.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(8)) })
        outer.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(8), dp(16), dp(16)) })
        return outer
    }
    private fun returnedBanner() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8)); background = roundedBackground("#FFEBEE", 10)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0,0,0,dp(12)) }
        addView(TextView(this@HistoryActivity).apply { text = "RETURNED"; textSize = 12.5f; setTextColor(Color.parseColor("#C62828")); setTypeface(typeface, android.graphics.Typeface.BOLD) })
    }
    private fun kv(l: String, v: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4))
        addView(TextView(this@HistoryActivity).apply { text = l; setTextColor(Color.GRAY); textSize = 13f; layoutParams = LinearLayout.LayoutParams(0,-2,1f) })
        addView(TextView(this@HistoryActivity).apply { text = v; textSize = 14f })
    }
    private fun sectionTitle(t: String) = TextView(this).apply { text = t; textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, dp(8), 0, dp(4)) }
    private fun itemRow(n: String, q: String, a: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(6))
        val col = LinearLayout(this@HistoryActivity).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0,-2,1f) }
        col.addView(TextView(this@HistoryActivity).apply { text = n; textSize = 13f })
        col.addView(TextView(this@HistoryActivity).apply { text = q; textSize = 11f; setTextColor(Color.GRAY) })
        addView(col); addView(TextView(this@HistoryActivity).apply { text = a; textSize = 13f })
    }
    private fun spacer() = View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(12)) }
    private fun row(title: String, subtitle: String, amount: Double, date: String, color: String, returned: Boolean, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); setOnClickListener { onClick() }
        val topRow = LinearLayout(this@HistoryActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleCol = LinearLayout(this@HistoryActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(0,-2,1f) }
        titleCol.addView(TextView(this@HistoryActivity).apply { text = title; textSize = 15f })
        if (returned) titleCol.addView(TextView(this@HistoryActivity).apply { text = "  RETURNED"; textSize = 10f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD); background = roundedBackground("#C62828",8); setPadding(dp(8), dp(2), dp(8), dp(2)) })
        topRow.addView(titleCol)
        topRow.addView(TextView(this@HistoryActivity).apply { text = "Rs %.2f".format(amount); setTextColor(if (returned) Color.GRAY else Color.parseColor(color)); textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        addView(topRow)
        addView(TextView(this@HistoryActivity).apply { text = subtitle; textSize = 13f; setTextColor(Color.DKGRAY) })
        addView(TextView(this@HistoryActivity).apply { text = date; textSize = 12f; setTextColor(Color.GRAY) })
        addView(divider())
    }
    private fun emptyText(t: String) = TextView(this).apply { text = t; setTextColor(Color.GRAY); setPadding(dp(8), dp(8), dp(8), dp(8)) }
    private fun roundedBackground(c: String, r: Int) = GradientDrawable().apply { setColor(Color.parseColor(c)); cornerRadius = r * resources.displayMetrics.density }
    private fun divider() = View(this).apply { setBackgroundColor(0xFFEEEEEE.toInt()); layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(8), 0, 0) } }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
