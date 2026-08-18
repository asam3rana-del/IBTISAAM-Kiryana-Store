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
    private val bg = "#F4F6F8"
    private val cardWhite = "#FFFFFF"
    private val teal = "#0F9B8E"
    private val textDark = "#0B2545"
    private val textMuted = "#7C8798"
    private val border = "#E3E8EE"
    private val red = "#E5484D"
    private lateinit var listContainer: LinearLayout
    private lateinit var emptyText: TextView
    private val expandedBills = mutableSetOf<String>()
    private val billBodyViews = mutableMapOf<String, LinearLayout>()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(16)); setBackgroundColor(Color.parseColor(bg)) }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4),0,dp(4),dp(16))
            addView(TextView(this@PurchaseHistoryActivity).apply { text = "Purchase History"; textSize = 20f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, android.graphics.Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0,-2,1f) })
            addView(TextView(this@PurchaseHistoryActivity).apply { text = "+ New"; textSize = 13f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(dp(12),dp(8),dp(12),dp(8)); background = roundedBg(teal,20); setOnClickListener { startActivity(Intent(this@PurchaseHistoryActivity, PurchaseActivity::class.java)) } })
        })
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)
        emptyText = TextView(this).apply { text = "No purchases yet."; textSize = 14f; setTextColor(Color.parseColor(textMuted)); visibility = View.GONE }
        root.addView(emptyText)
        setContentView(ScrollView(this).apply { addView(root) })
    }
    override fun onResume() { super.onResume(); refresh() }
    private fun refresh() {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PurchaseHistoryActivity)
            val all = db.purchaseDao().allPurchases()
            val grouped = all.groupBy { it.supplierName }.toList().sortedByDescending { (_,b) -> b.maxOfOrNull { it.createdAt } ?: 0L }
            listContainer.removeAllViews(); billBodyViews.clear()
            emptyText.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
            grouped.forEach { (name,bills) ->
                listContainer.addView(supplierHeader(name, bills.size, bills.sumOf { it.total }))
                bills.sortedByDescending { it.createdAt }.forEach { bill ->
                    listContainer.addView(billRow(bill))
                    val body = LinearLayout(this@PurchaseHistoryActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),0,0,dp(8)); visibility = if (expandedBills.contains(bill.billNo)) View.VISIBLE else View.GONE }
                    billBodyViews[bill.billNo] = body; listContainer.addView(body)
                    if (expandedBills.contains(bill.billNo)) loadBillItems(bill.billNo, body)
                }
            }
        }
    }
    private fun supplierHeader(name: String, count: Int, total: Double) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(dp(4),dp(16),dp(4),dp(8))
        addView(TextView(this@PurchaseHistoryActivity).apply { text = name.ifBlank { "Unknown" }; textSize = 15f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, android.graphics.Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0,-2,1f) })
        addView(TextView(this@PurchaseHistoryActivity).apply { text = "$count bills - Rs %.2f".format(total); textSize = 12f; setTextColor(Color.parseColor(textMuted)) })
    }
    private fun billRow(bill: PurchaseWithSupplier) = outlinedBox().apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setOnClickListener { toggleBill(bill.billNo) }
        addView(LinearLayout(this@PurchaseHistoryActivity).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0,-2,1f)
            addView(TextView(this@PurchaseHistoryActivity).apply { text = formatDate(bill.createdAt); textSize = 13.5f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, android.graphics.Typeface.BOLD) })
            addView(TextView(this@PurchaseHistoryActivity).apply { text = if (bill.status == "returned") "Returned" else "Purchase"; textSize = 11f; setTextColor(Color.parseColor(textMuted)) })
        })
        addView(TextView(this@PurchaseHistoryActivity).apply { text = "Rs %.2f".format(bill.total); textSize = 13f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(dp(8),0,dp(12),0) })
        addView(TextView(this@PurchaseHistoryActivity).apply { text = "EDIT"; textSize = 11f; setTextColor(Color.parseColor(teal)); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(dp(10),dp(6),dp(10),dp(6)); background = roundedBg("#E6F7F5",8); setOnClickListener { startActivity(Intent(this@PurchaseHistoryActivity, PurchaseActivity::class.java).putExtra(PurchaseActivity.EXTRA_BILL_NO, bill.billNo)) } })
        addView(Space(this@PurchaseHistoryActivity).apply { layoutParams = LinearLayout.LayoutParams(dp(8),1) })
        addView(TextView(this@PurchaseHistoryActivity).apply { text = "DEL"; textSize = 11f; setTextColor(Color.parseColor(red)); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(dp(10),dp(6),dp(10),dp(6)); background = roundedBg("#FDE8E9",8); setOnClickListener { confirmDelete(bill.billNo) } })
    }
    private fun toggleBill(no: String) { val b = billBodyViews[no] ?: return; if (expandedBills.contains(no)) { expandedBills.remove(no); b.visibility = View.GONE } else { expandedBills.add(no); b.visibility = View.VISIBLE; loadBillItems(no,b) } }
    private fun loadBillItems(no: String, body: LinearLayout) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PurchaseHistoryActivity); val items = db.purchaseDao().itemsForBill(no); body.removeAllViews()
            items.forEach { pi -> val p = db.productDao().find(pi.barcode); val u = pi.unit.ifBlank { p?.unit ?: "" }; body.addView(TextView(this@PurchaseHistoryActivity).apply { text = "${p?.name ?: pi.barcode} - ${pi.qty} $u x Rs ${pi.unitCost} = Rs %.2f".format(pi.amount); textSize = 12f; setTextColor(Color.parseColor(textDark)); setPadding(dp(4),dp(6),dp(4),dp(6)) }) }
            if (items.isEmpty()) body.addView(TextView(this@PurchaseHistoryActivity).apply { text = "No items"; textSize = 12f; setTextColor(Color.parseColor(textMuted)) })
        }
    }
    private fun confirmDelete(no: String) { android.app.AlertDialog.Builder(this).setTitle("Delete purchase").setMessage("Delete this purchase? Stock reverse hoga.").setPositiveButton("Delete") { _,_ -> deleteBill(no) }.setNegativeButton("Cancel",null).show() }
    private fun deleteBill(no: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PurchaseHistoryActivity); val pur = db.purchaseDao().findPurchase(no) ?: return@launch; val items = db.purchaseDao().itemsForBill(no)
            items.forEach { db.productDao().decreaseForce(it.barcode, it.qty.toInt().coerceAtLeast(1)) }
            val outstanding = pur.total - pur.paid; if (pur.supplierId != null && outstanding > 0) db.supplierDao().addBalance(pur.supplierId, -outstanding)
            db.purchaseDao().deleteItems(no); db.purchaseDao().deletePurchase(no); db.paymentDao().deleteByReference(no)
            expandedBills.remove(no); billBodyViews.remove(no); Toast.makeText(this@PurchaseHistoryActivity, "Deleted", Toast.LENGTH_SHORT).show(); refresh()
        }
    }
    private fun outlinedBox() = LinearLayout(this).apply { setPadding(dp(16),dp(12),dp(12),dp(12)); background = strokedBg(border,cardWhite,12); layoutParams = LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,0,0,dp(8)) } }
    private fun strokedBg(s: String, f: String, r: Int) = GradientDrawable().apply { setColor(Color.parseColor(f)); setStroke((1.2f * resources.displayMetrics.density).toInt(), Color.parseColor(s)); cornerRadius = r * resources.displayMetrics.density }
    private fun roundedBg(c: String, r: Int) = GradientDrawable().apply { setColor(Color.parseColor(c)); cornerRadius = r * resources.displayMetrics.density }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun formatDate(m: Long) = java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(m))
}
