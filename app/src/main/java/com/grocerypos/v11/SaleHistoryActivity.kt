package com.grocerypos.v11.ui

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
import com.grocerypos.v11.*
import kotlinx.coroutines.launch

class SaleHistoryActivity : AppCompatActivity() {

    // ---- Same navy + teal palette as PurchaseActivity / SaleActivity ----
    private val bg = "#F4F6F8"
    private val cardWhite = "#FFFFFF"
    private val navy = "#0B2545"
    private val teal = "#0F9B8E"
    private val textDark = "#0B2545"
    private val textMuted = "#7C8798"
    private val border = "#E3E8EE"
    private val red = "#E5484D"

    private lateinit var listContainer: LinearLayout
    private lateinit var emptyText: TextView

    // invoice -> whether its item breakdown is currently expanded
    private val expandedSales = mutableSetOf<String>()
    // invoice -> the container view holding its expanded item rows, so we can rebuild just that
    private val saleBodyViews = mutableMapOf<String, LinearLayout>()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 28)
            setBackgroundColor(Color.parseColor(bg))
        }

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 0, 4, 18)
            addView(TextView(this@SaleHistoryActivity).apply {
                text = "Sale History"
                textSize = 20f
                setTextColor(Color.parseColor(textDark))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@SaleHistoryActivity).apply {
                text = "+ New"
                textSize = 13f
                setTextColor(Color.parseColor(teal))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setOnClickListener {
                    startActivity(Intent(this@SaleHistoryActivity, SaleActivity::class.java))
                }
            })
        })

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        emptyText = TextView(this).apply {
            text = "No sales yet."
            textSize = 14f
            setTextColor(Color.parseColor(textMuted))
            setPadding(4, 20, 4, 4)
            visibility = View.GONE
        }
        root.addView(emptyText)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SaleHistoryActivity)
            val allSales = db.saleDao().allSales() // invoice, customerName, total, paymentMethod, createdAt, status
            // ---- Party-wise: grouped by customer, most recently active customer first ----
            val grouped = allSales.groupBy { it.customerName }
                .toList()
                .sortedByDescending { (_, sales) -> sales.maxOf { it.createdAt } }

            listContainer.removeAllViews()
            saleBodyViews.clear()
            emptyText.visibility = if (allSales.isEmpty()) View.VISIBLE else View.GONE

            grouped.forEach { (customerName, sales) ->
                val customerTotal = sales.sumOf { it.total }
                listContainer.addView(customerHeader(customerName, sales.size, customerTotal))
                sales.sortedByDescending { it.createdAt }.forEach { sale ->
                    listContainer.addView(saleRow(sale))
                    val body = LinearLayout(this@SaleHistoryActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(24, 0, 0, 8)
                        visibility = if (expandedSales.contains(sale.invoice)) View.VISIBLE else View.GONE
                    }
                    saleBodyViews[sale.invoice] = body
                    listContainer.addView(body)
                    if (expandedSales.contains(sale.invoice)) loadSaleItems(sale.invoice, body)
                }
            }
        }
    }

    private fun customerHeader(name: String, count: Int, total: Double) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(4, 18, 4, 8)
        addView(TextView(this@SaleHistoryActivity).apply {
            text = name
            textSize = 15f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(this@SaleHistoryActivity).apply {
            text = "$count sales · Rs %.2f".format(total)
            textSize = 12f
            setTextColor(Color.parseColor(textMuted))
        })
    }

    // ---- Invoice number is intentionally never shown — date is the visible identifier ----
    private fun saleRow(sale: SaleWithCustomer) = outlinedBox().apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setOnClickListener { toggleSale(sale.invoice) }

        addView(LinearLayout(this@SaleHistoryActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@SaleHistoryActivity).apply {
                text = formatDate(sale.createdAt)
                textSize = 13.5f
                setTextColor(Color.parseColor(textDark))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@SaleHistoryActivity).apply {
                text = if (sale.status == "returned") "Returned" else sale.paymentMethod.replaceFirstChar { it.uppercase() }
                textSize = 11f
                setTextColor(Color.parseColor(textMuted))
            })
        })
        addView(TextView(this@SaleHistoryActivity).apply {
            text = "Rs %.2f".format(sale.total)
            textSize = 13f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(8, 0, 12, 0)
        })
        addView(TextView(this@SaleHistoryActivity).apply {
            text = "🗑"
            textSize = 15f
            setTextColor(Color.parseColor(red))
            setPadding(10, 0, 4, 0)
            setOnClickListener { confirmDelete(sale.invoice) }
        })
    }

    private fun toggleSale(invoice: String) {
        val body = saleBodyViews[invoice] ?: return
        if (expandedSales.contains(invoice)) {
            expandedSales.remove(invoice)
            body.visibility = View.GONE
        } else {
            expandedSales.add(invoice)
            body.visibility = View.VISIBLE
            loadSaleItems(invoice, body)
        }
    }

    private fun loadSaleItems(invoice: String, body: LinearLayout) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SaleHistoryActivity)
            val items = db.saleDao().itemsForInvoice(invoice)
            body.removeAllViews()
            items.forEach { si ->
                body.addView(TextView(this@SaleHistoryActivity).apply {
                    text = "${si.product}  —  ${si.qty} ${si.unit} × Rs ${si.unitPrice} = Rs %.2f".format(si.amount)
                    textSize = 12f
                    setTextColor(Color.parseColor(textDark))
                    setPadding(4, 6, 4, 6)
                })
            }
            if (items.isEmpty()) {
                body.addView(TextView(this@SaleHistoryActivity).apply {
                    text = "No items on this sale."
                    textSize = 12f
                    setTextColor(Color.parseColor(textMuted))
                    setPadding(4, 6, 4, 6)
                })
            }
        }
    }

    private fun confirmDelete(invoice: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete sale")
            .setMessage("Delete this sale? This will reverse its stock and customer balance changes. This can't be undone.")
            .setPositiveButton("Delete") { _, _ -> deleteSale(invoice) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- FIX: stock reversal ab stored si.unit ke sath Product.toSmallestUnits()
    // (multiply-only) use karta hai — pehle `SyncQueueHelper.increaseProductStock(db, it.barcode, it.qty)`
    // primary-unit qty seedha smallest-unit stock mein add kar raha tha, jo unit-tier
    // products (secondary/tertiary unit wale) ke liye galat stock reverse karta tha.
    // Ab SaleActivity.deleteSale() / PurchaseActivity.reverseStockForItems() jaisa hi. ----
    // FIX (Phase 1 - Data Safety): all writes below now run as one atomic Room transaction
    // instead of separate sequential writes (same pattern as SaleActivity/HistoryActivity).
    private fun deleteSale(invoice: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SaleHistoryActivity)
            val sale = db.saleDao().findSale(invoice) ?: return@launch
            val items = db.saleDao().itemsForInvoice(invoice)

            db.withTransaction {
                items.forEach { si ->
                    val p = db.productDao().find(si.barcode)
                    if (p != null) {
                        val smallestQty = p.toSmallestUnits(si.qty.toDouble(), si.unit.ifBlank { p.unit })
                        SyncQueueHelper.increaseProductStock(db, si.barcode, smallestQty)
                    }
                }

                // Reverse any outstanding balance this sale added to the customer.
                val outstanding = sale.total - sale.paid
                if (sale.customerId != null && outstanding > 0) {
                    SyncQueueHelper.adjustCustomerBalance(db, sale.customerId, -outstanding)
                }

                db.saleDao().deleteItems(invoice)
                db.saleDao().deleteSale(invoice)
                db.paymentDao().deleteByReference(invoice)
                db.cashTransactionDao().deleteByReference(invoice)
            }

            expandedSales.remove(invoice)
            saleBodyViews.remove(invoice)
            Toast.makeText(this@SaleHistoryActivity, "Sale deleted", Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    private fun outlinedBox() = LinearLayout(this).apply {
        setPadding(20, 14, 12, 14)
        background = strokedBg(border, cardWhite, 12)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 8) }
    }

    private fun strokedBg(strokeHex: String, fillHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(fillHex))
        setStroke((1.2 * resources.displayMetrics.density).toInt(), Color.parseColor(strokeHex))
        cornerRadius = radius.toFloat()
    }

    private fun formatDate(millis: Long) =
        java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(millis))
}
