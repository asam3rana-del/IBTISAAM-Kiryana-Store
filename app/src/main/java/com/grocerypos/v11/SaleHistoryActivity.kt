package com.grocerypos.v11.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.withTransaction
import com.grocerypos.v11.*
import kotlinx.coroutines.launch
import com.grocerypos.v11.ui.components.*

class SaleHistoryActivity : ThemedActivity() {

    // ---- Same navy + teal palette as PurchaseActivity / SaleActivity ----
    // Pulled from ThemeManager so this screen respects dark mode.
    private var bg = "#F4F6F8"
    private var cardWhite = "#FFFFFF"
    private var navy = "#0B2545"
    private var teal = "#0F9B8E"
    private var textDark = "#0B2545"
    private var textMuted = "#7C8798"
    private var border = "#E3E8EE"
    private var red = "#E5484D"

    private fun loadThemeColors() {
        val p = com.grocerypos.v11.util.ThemeManager.palette(this)
        bg = p.bg
        cardWhite = p.cardWhite
        navy = p.navy
        teal = p.teal
        textDark = p.textDark
        textMuted = p.textMuted
        border = p.border
        red = p.red
    }

    // ---- Item #2 (RecyclerView migration): the header/sale/expanded-item rows
    // that used to be addView()'d into a LinearLayout inside a ScrollView are now
    // a flat list of Row values bound to a RecyclerView, so only on-screen rows
    // get inflated instead of the whole history living as permanent child views.
    private sealed class Row {
        data class Header(val name: String, val count: Int, val total: Double) : Row()
        data class SaleRow(val sale: SaleWithCustomer) : Row()
        data class ItemRow(val invoice: String, val text: String) : Row()
        data class ItemsEmpty(val invoice: String) : Row()
    }

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.Holder>() {
        inner class Holder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

        var rows: List<Row> = emptyList()
            private set

        fun submit(newRows: List<Row>) {
            rows = newRows
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val view = when (val row = rows[position]) {
                is Row.Header -> customerHeader(row.name, row.count, row.total)
                is Row.SaleRow -> saleRow(row.sale)
                is Row.ItemRow -> itemLine(row.text, muted = false)
                is Row.ItemsEmpty -> itemLine("No items on this sale.", muted = true)
            }
            holder.container.removeAllViews()
            holder.container.addView(view)
        }

        override fun getItemCount() = rows.size
    }

    private lateinit var recyclerView: RecyclerView
    private val adapter = RowAdapter()
    private lateinit var emptyText: TextView

    // invoice -> whether its item breakdown is currently expanded
    private val expandedSales = mutableSetOf<String>()
    // invoice -> cached items once loaded, so re-collapsing/expanding doesn't re-hit the DB
    private val loadedItems = mutableMapOf<String, List<SaleItem>>()
    // last grouped-by-customer sales fetched from the DB, so toggling expand/collapse
    // can rebuild the flat row list without a fresh query
    private var groupedSales: List<Pair<String, List<SaleWithCustomer>>> = emptyList()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        loadThemeColors()

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

        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@SaleHistoryActivity)
            adapter = this@SaleHistoryActivity.adapter
            isNestedScrollingEnabled = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        root.addView(recyclerView)

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
            groupedSales = allSales.groupBy { it.customerName }
                .toList()
                .sortedByDescending { (_, sales) -> sales.maxOf { it.createdAt } }

            loadedItems.clear()
            emptyText.visibility = if (allSales.isEmpty()) View.VISIBLE else View.GONE
            rebuildRows()
        }
    }

    // Rebuilds the flat row list from the last-fetched groupedSales + current
    // expand/collapse state + whatever item rows are already cached — no DB hit.
    private fun rebuildRows() {
        val rows = mutableListOf<Row>()
        groupedSales.forEach { (customerName, sales) ->
            val customerTotal = sales.sumOf { it.total }
            rows.add(Row.Header(customerName, sales.size, customerTotal))
            sales.sortedByDescending { it.createdAt }.forEach { sale ->
                rows.add(Row.SaleRow(sale))
                if (expandedSales.contains(sale.invoice)) {
                    val items = loadedItems[sale.invoice]
                    if (items != null) {
                        if (items.isEmpty()) {
                            rows.add(Row.ItemsEmpty(sale.invoice))
                        } else {
                            items.forEach { si ->
                                rows.add(Row.ItemRow(sale.invoice, "${si.product}  —  ${si.qty} ${si.unit} × Rs ${si.unitPrice} = Rs %.2f".format(si.amount)))
                            }
                        }
                    }
                }
            }
        }
        adapter.submit(rows)
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
        if (sale.status != "returned") {
            addView(TextView(this@SaleHistoryActivity).apply {
                text = "↩"
                textSize = 16f
                setTextColor(Color.parseColor(teal))
                setPadding(10, 0, 4, 0)
                setOnClickListener { confirmReturn(sale.invoice) }
            })
        }
        addView(TextView(this@SaleHistoryActivity).apply {
            text = "🗑"
            textSize = 15f
            setTextColor(Color.parseColor(red))
            setPadding(10, 0, 4, 0)
            setOnClickListener { confirmDelete(sale.invoice) }
        })
    }

    private fun itemLine(text: String, muted: Boolean) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.parseColor(if (muted) textMuted else textDark))
        setPadding(28, 6, 4, 6)
    }

    private fun toggleSale(invoice: String) {
        if (expandedSales.contains(invoice)) {
            expandedSales.remove(invoice)
            rebuildRows()
        } else {
            expandedSales.add(invoice)
            rebuildRows() // shows the row expanded immediately; item rows fill in once loaded
            if (!loadedItems.containsKey(invoice)) loadSaleItems(invoice)
        }
    }

    private fun loadSaleItems(invoice: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SaleHistoryActivity)
            val items = db.saleDao().itemsForInvoice(invoice)
            loadedItems[invoice] = items
            if (expandedSales.contains(invoice)) rebuildRows()
        }
    }

    private fun confirmReturn(invoice: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Return sale")
            .setMessage("Return this sale? Stock will be added back and any outstanding customer balance from it will be reversed.")
            .setPositiveButton("Return") { _, _ -> returnSale(invoice) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Mirrors HistoryActivity.returnSale() — same atomic transaction: stock reversal,
    // ReturnLine insert (so it shows up in Reports > Sale Returns), customer balance
    // reversal, and markReturned — kept in sync so both entry points behave identically.
    private fun returnSale(invoice: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SaleHistoryActivity)
            val sale = db.saleDao().findSale(invoice) ?: return@launch
            if (sale.status == "returned") return@launch
            val items = db.saleDao().itemsForInvoice(invoice)

            db.withTransaction {
                items.forEach { si ->
                    val p = db.productDao().find(si.barcode)
                    val smallestQty = si.smallestQty(p)
                    SyncQueueHelper.increaseProductStock(db, si.barcode, smallestQty, "SALE_REVERSAL", invoice)
                    db.returnDao().insert(ReturnLine(reference = invoice, type = "sale", barcode = si.barcode, qty = si.qty, amount = si.amount))
                }
                if (sale.customerId != null && sale.paid < sale.total) {
                    SyncQueueHelper.adjustCustomerBalance(db, sale.customerId, -(sale.total - sale.paid))
                }
                db.cashTransactionDao().deleteByReference(invoice)
                db.saleDao().markReturned(invoice)
            }

            Toast.makeText(this@SaleHistoryActivity, "Sale returned", Toast.LENGTH_SHORT).show()
            refresh()
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
                    val smallestQty = si.smallestQty(p)
                    SyncQueueHelper.increaseProductStock(db, si.barcode, smallestQty, "SALE_REVERSAL", invoice)
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
            loadedItems.remove(invoice)
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

    private fun formatDate(millis: Long) =
        java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(millis))
}
