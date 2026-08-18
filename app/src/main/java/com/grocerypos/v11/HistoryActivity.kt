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
            text = "History"
            textSize = 22f
            setTextColor(Color.parseColor("#0B2545"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0,0,0,dp(4))
        })
        root.addView(TextView(this).apply {
            text = "Kisi bhi entry par tap karke poori detail dekhein"
            textSize = 12f
            setTextColor(Color.parseColor("#7C8798"))
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
            text = "SALES"
            setTextColor(Color.WHITE)
            background = roundedBackground(if (showingSales) "#2E7D32" else "#90A4AE", 14)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,dp(8),0) }
            setOnClickListener { showSales() }
        })
        tabRow.addView(Button(this).apply {
            text = "PURCHASES"
            setTextColor(Color.WHITE)
            background = roundedBackground(if (!showingSales) "#EF6C00" else "#90A4AE", 14)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(8),0,0,0) }
            setOnClickListener { showPurchases() }
        })
    }

    private fun showSales() {
        showingSales = true
        buildTabs()
        loadSales()
    }

    private fun showPurchases() {
        showingSales = false
        buildTabs()
        loadPurchases()
    }

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
                listContainer.addView(row(
                    title = s.invoice,
                    subtitle = "${s.customerName}  •  ${s.paymentMethod}",
                    amount = s.total,
                    date = fmt.format(Date(s.createdAt)),
                    color = "#2E7D32",
                    returned = s.status == "returned"
                ) { openSaleDetail(s.invoice) })
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
                listContainer.addView(row(
                    title = p.billNo,
                    subtitle = p.supplierName,
                    amount = p.total,
                    date = fmt.format(Date(p.createdAt)),
                    color = "#EF6C00",
                    returned = p.status == "returned"
                ) { openPurchaseDetail(p.billNo) })
            }
        }
    }

    // ================= Sale detail dialog =================
    private fun openSaleDetail(invoice: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@HistoryActivity)
            val sale = db.saleDao().findSale(invoice) ?: return@launch
            val items = db.saleDao().itemsForInvoice(invoice)
            val isReturned = sale.status == "returned"

            val content = detailContainer("Sale: $invoice", "#2E7D32")
            val body = content.getChildAt(1) as LinearLayout

            if (isReturned) {
                body.addView(returnedBanner())
            }
            body.addView(kv("Total", "Rs %.2f".format(sale.total)))
            body.addView(kv("Paid", "Rs %.2f".format(sale.paid)))
            body.addView(kv("Payment Method", sale.paymentMethod))
            body.addView(kv("Sale Type", sale.saleType))
            body.addView(spacer())
            body.addView(sectionTitle("Items"))
            for (it in items) {
                body.addView(itemRow(it.product, "${it.qty} × ${it.unitPrice}", "Rs %.2f".format(it.amount)))
            }

            val dialog = AlertDialog.Builder(this@HistoryActivity).setView(content).create()
            val footer = content.getChildAt(2) as LinearLayout
            footer.addView(Button(this@HistoryActivity).apply {
                text = "Close"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { dialog.dismiss() }
            })

            if (!isReturned) {
                footer.addView(Button(this@HistoryActivity).apply {
                    text = "Return"
                    setTextColor(Color.WHITE)
                    background = roundedBackground("#EF6C00", 12)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(6),0,0,0) }
                    setOnClickListener {
                        AlertDialog.Builder(this@HistoryActivity)
                            .setTitle("Sale return karen?")
                            .setMessage("Stock wapas add ho jayega aur customer ka udhaar adjust ho jayega. Record History mein 'Returned' ke tor par reh jayega.")
                            .setPositiveButton("Return") { _, _ ->
                                returnSale(invoice)
                                dialog.dismiss()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                })
                footer.addView(Button(this@HistoryActivity).apply {
                    text = "Delete"
                    setTextColor(Color.WHITE)
                    background = roundedBackground("#C62828", 12)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(6),0,0,0) }
                    setOnClickListener {
                        AlertDialog.Builder(this@HistoryActivity)
                            .setTitle("Sale delete karen?")
                            .setMessage("Stock aur cash record wapas revert ho jayenge. Ye action undo nahi ho sakta.")
                            .setPositiveButton("Delete") { _, _ ->
                                deleteSale(invoice)
                                dialog.dismiss()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
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
            if (sale.status == "returned") {
                Toast.makeText(this@HistoryActivity, "Ye sale pehle hi return ho chuki hai", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val items = db.saleDao().itemsForInvoice(invoice)

            for (it in items) {
                db.productDao().increase(it.barcode, it.qty)
                db.returnDao().insert(
                    ReturnLine(
                        reference = invoice,
                        type = "sale",
                        barcode = it.barcode,
                        qty = it.qty,
                        amount = it.amount
                    )
                )
            }

            if (sale.customerId != null && sale.paid < sale.total) {
                db.customerDao().addBalance(sale.customerId, -(sale.total - sale.paid))
            }

            db.cashTransactionDao().deleteByReference(invoice)
            db.saleDao().markReturned(invoice)

            Toast.makeText(this@HistoryActivity, "Sale return ho gayi, stock revert ho gaya", Toast.LENGTH_LONG).show()
            loadSales()
        }
    }

    private fun deleteSale(invoice: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@HistoryActivity)
            val sale = db.saleDao().findSale(invoice) ?: return@launch
            val items = db.saleDao().itemsForInvoice(invoice)

            for (it in items) {
                db.productDao().increase(it.barcode, it.qty)
            }

            if (sale.customerId != null && sale.paid < sale.total) {
                db.customerDao().addBalance(sale.customerId, -(sale.total - sale.paid))
            }

            db.cashTransactionDao().deleteByReference(invoice)
            db.saleDao().deleteItems(invoice)
            db.saleDao().deleteSale(invoice)

            Toast.makeText(this@HistoryActivity, "Sale delete ho gayi, stock revert ho gaya", Toast.LENGTH_LONG).show()
            loadSales()
        }
    }

    // ================= Purchase detail dialog =================
    private fun openPurchaseDetail(billNo: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@HistoryActivity)
            val purchase = db.purchaseDao().findPurchase(billNo) ?: return@launch
            val items = db.purchaseDao().itemsForBill(billNo)
            val isReturned = purchase.status == "returned"

            val content = detailContainer("Purchase: $billNo", "#EF6C00")
            val body = content.getChildAt(1) as LinearLayout

            if (isReturned) {
                body.addView(returnedBanner())
            }
            body.addView(kv("Total", "Rs %.2f".format(purchase.total)))
            body.addView(kv("Paid", "Rs %.2f".format(purchase.paid)))
            body.addView(spacer())
            body.addView(sectionTitle("Items"))
            for (it in items) {
                val unitLabel = if (it.unit.isBlank()) "" else " ${it.unit}"
                body.addView(itemRow(it.barcode, "${it.qty}$unitLabel × ${it.unitCost}", "Rs %.2f".format(it.amount)))
            }

            val dialog = AlertDialog.Builder(this@HistoryActivity).setView(content).create()
            val footer = content.getChildAt(2) as LinearLayout
            footer.addView(Button(this@HistoryActivity).apply {
                text = "Close"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { dialog.dismiss() }
            })

           
