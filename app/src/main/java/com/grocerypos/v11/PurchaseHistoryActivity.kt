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
import com.grocerypos.v11.*
import kotlinx.coroutines.launch

class PurchaseHistoryActivity : AppCompatActivity() {

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

    // billNo -> whether its item breakdown is currently expanded
    private val expandedBills = mutableSetOf<String>()
    // billNo -> the container view holding its expanded item rows, so we can rebuild just that
    private val billBodyViews = mutableMapOf<String, LinearLayout>()

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
            addView(TextView(this@PurchaseHistoryActivity).apply {
                text = "Purchase History"
                textSize = 20f
                setTextColor(Color.parseColor(textDark))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@PurchaseHistoryActivity).apply {
                text = "+ New"
                textSize = 13f
                setTextColor(Color.parseColor(teal))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setOnClickListener {
                    startActivity(Intent(this@PurchaseHistoryActivity, PurchaseActivity::class.java))
                }
            })
        })

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        emptyText = TextView(this).apply {
            text = "No purchases yet."
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
        // Refresh every time we come back (after Edit / Delete / adding a new purchase).
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PurchaseHistoryActivity)
            val allPurchases = db.purchaseDao().allPurchases() // billNo, supplierName, total, createdAt
            // ---- Party-wise: grouped by supplier, most recently active supplier first ----
            val grouped = allPurchases.groupBy { it.supplierName }
                .toList()
                .sortedByDescending { (_, bills) -> bills.maxOf { it.createdAt } }

            listContainer.removeAllViews()
            billBodyViews.clear()
            emptyText.visibility = if (allPurchases.isEmpty()) View.VISIBLE else View.GONE

            grouped.forEach { (supplierName, bills) ->
                val supplierTotal = bills.sumOf { it.total }
                listContainer.addView(supplierHeader(supplierName, bills.size, supplierTotal))
                bills.sortedByDescending { it.createdAt }.forEach { bill ->
                    listContainer.addView(billRow(bill))
                    val body = LinearLayout(this@PurchaseHistoryActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(24, 0, 0, 8)
                        visibility = if (expandedBills.contains(bill.billNo)) View.VISIBLE else View.GONE
                    }
                    billBodyViews[bill.billNo] = body
                    listContainer.addView(body)
                    if (expandedBills.contains(bill.billNo)) loadBillItems(bill.billNo, body)
                }
            }
        }
    }

    private fun supplierHeader(name: String, count: Int, total: Double) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(4, 18, 4, 8)
        addView(TextView(this@PurchaseHistoryActivity).apply {
            text = name
            textSize = 15f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(this@PurchaseHistoryActivity).apply {
            text = "$count bills · Rs %.2f".format(total)
            textSize = 12f
            setTextColor(Color.parseColor(textMuted))
        })
    }

    // ---- Bill number is intentionally never shown — date is the visible identifier ----
    private fun billRow(bill: PurchaseWithSupplier) = outlinedBox().apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setOnClickListener { toggleBill(bill.billNo) }

        addView(LinearLayout(this@PurchaseHistoryActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@PurchaseHistoryActivity).apply {
                text = formatDate(bill.createdAt)
                textSize = 13.5f
                setTextColor(Color.parseColor(textDark))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@PurchaseHistoryActivity).apply {
                text = if (bill.status == "returned") "Returned" else "Purchase"
                textSize = 11f
                setTextColor(Color.parseColor(textMuted))
            })
        })
        addView(TextView(this@PurchaseHistoryActivity).apply {
            text = "Rs %.2f".format(bill.total)
            textSize = 13f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(8, 0, 12, 0)
        })
        addView(TextView(this@PurchaseHistoryActivity).apply {
            text = "✎"
            textSize = 16f
            setTextColor(Color.parseColor(teal))
            setPadding(10, 0, 10, 0)
            setOnClickListener {
                startActivity(
                    Intent(this@PurchaseHistoryActivity, PurchaseActivity::class.java)
                        .putExtra(PurchaseActivity.EXTRA_BILL_NO, bill.billNo)
                )
            }
        })
        addView(TextView(this@PurchaseHistoryActivity).apply {
            text = "🗑"
            textSize = 15f
            setTextColor(Color.parseColor(red))
            setPadding(10, 0, 4, 0)
            setOnClickListener { confirmDelete(bill.billNo) }
        })
    }

    private fun toggleBill(billNo: String) {
        val body = billBodyViews[billNo] ?: return
        if (expandedBills.contains(billNo)) {
            expandedBills.remove(billNo)
            body.visibility = View.GONE
        } else {
            expandedBills.add(billNo)
            body.visibility = View.VISIBLE
            loadBillItems(billNo, body)
        }
    }

    private fun loadBillItems(billNo: String, body: LinearLayout) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PurchaseHistoryActivity)
            val items = db.purchaseDao().itemsForBill(billNo)
            body.removeAllViews()
            items.forEach { pi ->
                val product = db.productDao().find(pi.barcode)
                val unitLabel = pi.unit.ifBlank { product?.unit ?: "" }
                body.addView(TextView(this@PurchaseHistoryActivity).apply {
                    text = "${product?.name ?: pi.barcode}  —  ${pi.qty} $unitLabel × Rs ${pi.unitCost} = Rs %.2f".format(pi.amount)
                    textSize = 12f
                    setTextColor(Color.parseColor(textDark))
                    setPadding(4, 6, 4, 6)
                })
            }
            if (items.isEmpty()) {
                body.addView(TextView(this@PurchaseHistoryActivity).apply {
                    text = "No items on this bill."
                    textSize = 12f
                    setTextColor(Color.parseColor(textMuted))
                    setPadding(4, 6, 4, 6)
                })
            }
        }
    }

    private fun confirmDelete(billNo: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete purchase")
            .setMessage("Delete this purchase? This will reverse its stock and supplier balance changes. This can't be undone.")
            .setPositiveButton("Delete") { _, _ -> deleteBill(billNo) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteBill(billNo: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PurchaseHistoryActivity)
            val purchase = db.purchaseDao().findPurchase(billNo) ?: return@launch
            val items = db.purchaseDao().itemsForBill(billNo)

            // Reverse stock added by this purchase.
            items.forEach { db.productDao().decrease(it.barcode, it.qty) }

            // Reverse any outstanding balance this purchase added to the supplier.
            val outstanding = purchase.total - purchase.paid
            if (purchase.supplierId != null && outstanding > 0) {
                db.supplierDao().addBalance(purchase.supplierId, -outstanding)
            }

            db.purchaseDao().deleteItems(billNo)
            db.purchaseDao().deletePurchase(billNo)
            db.paymentDao().deleteByReference(billNo)

            expandedBills.remove(billNo)
            billBodyViews.remove(billNo)
            Toast.makeText(this@PurchaseHistoryActivity, "Purchase deleted", Toast.LENGTH_SHORT).show()
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
