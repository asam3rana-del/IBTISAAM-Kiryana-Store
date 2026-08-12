package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.PosDatabase
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
            setPadding(28, 32, 28, 32)
        }

        root.addView(TextView(this).apply { text = "History"; textSize = 22f; setPadding(0,0,0,16) })
        root.addView(TextView(this).apply {
            text = "Kisi bhi entry par tap karke poori detail dekhein"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0,0,0,16)
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
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,8,0) }
            setOnClickListener { showSales() }
        })
        tabRow.addView(Button(this).apply {
            text = "PURCHASES"
            setTextColor(Color.WHITE)
            background = roundedBackground(if (!showingSales) "#EF6C00" else "#90A4AE", 14)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8,0,0,0) }
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
                    color = "#2E7D32"
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
                    color = "#EF6C00"
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

            val content = detailContainer("Sale: $invoice", "#2E7D32")
            val body = content.getChildAt(1) as LinearLayout

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
            footer.addView(Button(this@HistoryActivity).apply {
                text = "Delete (Reverse)"
                setTextColor(Color.WHITE)
                background = roundedBackground("#C62828", 12)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
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
            dialog.show()
        }
    }

    private fun deleteSale(invoice: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@HistoryActivity)
            val sale = db.saleDao().findSale(invoice) ?: return@launch
            val items = db.saleDao().itemsForInvoice(invoice)

            // Stock wapas add karo (jitna becha tha)
            for (it in items) {
                db.productDao().increase(it.barcode, it.qty)
            }

            // Agar customer ka udhar tha to wo wapas kam karo
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

            val content = detailContainer("Purchase: $billNo", "#EF6C00")
            val body = content.getChildAt(1) as LinearLayout

            body.addView(kv("Total", "Rs %.2f".format(purchase.total)))
            body.addView(kv("Paid", "Rs %.2f".format(purchase.paid)))
            body.addView(spacer())
            body.addView(sectionTitle("Items"))
            for (it in items) {
                body.addView(itemRow(it.barcode, "${it.qty} × ${it.unitCost}", "Rs %.2f".format(it.amount)))
            }

            val dialog = AlertDialog.Builder(this@HistoryActivity).setView(content).create()
            val footer = content.getChildAt(2) as LinearLayout
            footer.addView(Button(this@HistoryActivity).apply {
                text = "Close"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { dialog.dismiss() }
            })
            footer.addView(Button(this@HistoryActivity).apply {
                text = "Delete (Reverse)"
                setTextColor(Color.WHITE)
                background = roundedBackground("#C62828", 12)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    AlertDialog.Builder(this@HistoryActivity)
                        .setTitle("Purchase delete karen?")
                        .setMessage("Stock aur cash record wapas revert ho jayenge. Ye action undo nahi ho sakta.")
                        .setPositiveButton("Delete") { _, _ ->
                            deletePurchase(billNo)
                            dialog.dismiss()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            })
            dialog.show()
        }
    }

    private fun deletePurchase(billNo: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@HistoryActivity)
            val purchase = db.purchaseDao().findPurchase(billNo) ?: return@launch
            val items = db.purchaseDao().itemsForBill(billNo)

            // Stock wapas kam karo (jitna khareeda tha)
            for (it in items) {
                db.productDao().decrease(it.barcode, it.qty)
            }

            // Agar supplier ka udhar tha to wo wapas kam karo
            if (purchase.supplierId != null && purchase.paid < purchase.total) {
                db.supplierDao().addBalance(purchase.supplierId, -(purchase.total - purchase.paid))
            }

            db.cashTransactionDao().deleteByReference(billNo)
            db.purchaseDao().deleteItems(billNo)
            db.purchaseDao().deletePurchase(billNo)

            Toast.makeText(this@HistoryActivity, "Purchase delete ho gayi, stock revert ho gaya", Toast.LENGTH_LONG).show()
            loadPurchases()
        }
    }

    // ================= shared small UI helpers =================
    private fun detailContainer(title: String, colorHex: String): LinearLayout {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            setPadding(28, 24, 28, 24)
            setBackgroundColor(Color.parseColor(colorHex))
        }
        header.addView(TextView(this).apply {
            text = title; textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        outer.addView(header)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 12)
        }
        outer.addView(body)

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 8, 20, 20)
        }
        outer.addView(footer)
        return outer
    }

    private fun kv(label: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 4, 0, 4)
        addView(TextView(this@HistoryActivity).apply {
            text = label; setTextColor(Color.GRAY); textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(this@HistoryActivity).apply { text = value; textSize = 14f })
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text; textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 10, 0, 6)
    }

    private fun itemRow(name: String, qtyRate: String, amount: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 6, 0, 6)
        val col = LinearLayout(this@HistoryActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this@HistoryActivity).apply { text = name; textSize = 13f })
        col.addView(TextView(this@HistoryActivity).apply { text = qtyRate; textSize = 11f; setTextColor(Color.GRAY) })
        addView(col)
        addView(TextView(this@HistoryActivity).apply { text = amount; textSize = 13f })
    }

    private fun spacer() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 16)
    }

    private fun row(title: String, subtitle: String, amount: Double, date: String, color: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 14, 16, 14)
            setOnClickListener { onClick() }

            val topRow = LinearLayout(this@HistoryActivity).apply { orientation = LinearLayout.HORIZONTAL }
            topRow.addView(TextView(this@HistoryActivity).apply {
                text = title
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            topRow.addView(TextView(this@HistoryActivity).apply {
                text = "Rs %.2f".format(amount)
                setTextColor(Color.parseColor(color))
                textSize = 15f
            })
            addView(topRow)

            addView(TextView(this@HistoryActivity).apply {
                text = subtitle
                textSize = 13f
                setTextColor(Color.DKGRAY)
            })
            addView(TextView(this@HistoryActivity).apply {
                text = date
                textSize = 12f
                setTextColor(Color.GRAY)
            })
            addView(divider())
        }
    }

    private fun emptyText(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.GRAY)
        setPadding(8, 8, 8, 8)
    }

    private fun roundedBackground(colorHex: String, cornerRadius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(colorHex))
        this.cornerRadius = cornerRadius.toFloat()
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(0xFFEEEEEE.toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
    }
}
