package com.grocerypos.v11.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.Customer
import com.grocerypos.v11.PartyItemReport
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Supplier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PartyReportsActivity : AppCompatActivity() {

    private val blue = "#1565C0"
    private val orange = "#EF6C00"

    private var mode = "summary"
    private var showingCustomers = true
    private var customers: List<Customer> = emptyList()
    private var suppliers: List<Supplier> = emptyList()

    private lateinit var tabRow: LinearLayout
    private lateinit var partySpinner: Spinner
    private lateinit var resultContainer: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        mode = intent.getStringExtra("mode") ?: "summary"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 32)
        }

        val title = when (mode) {
            "item" -> "Party Report by Item"
            "statement" -> "Party Statement"
            else -> "Sale / Purchase by Party"
        }
        root.addView(TextView(this).apply { text = title; textSize = 22f; setPadding(0, 0, 0, 20) })

        resultContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        if (mode == "summary") {
            root.addView(resultContainer)
            setContentView(ScrollView(this).apply { addView(root) })
            loadSummary()
        } else {
            tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            root.addView(tabRow)
            root.addView(spacerView())

            partySpinner = Spinner(this)
            root.addView(partySpinner)

            root.addView(Button(this).apply {
                text = "VIEW REPORT"
                setOnClickListener { runSelectedReport() }
            })

            root.addView(divider())
            root.addView(resultContainer)

            setContentView(ScrollView(this).apply { addView(root) })

            lifecycleScope.launch {
                val db = PosDatabase.get(this@PartyReportsActivity)
                customers = db.customerDao().all().first()
                suppliers = db.supplierDao().all().first()
                buildTabs()
                showCustomers()
            }
        }
    }

    // ================= SUMMARY (Sale/Purchase by Party) =================
    private fun loadSummary() {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PartyReportsActivity)
            val custTotals = db.customerDao().salesTotalsByCustomer()
            val supTotals = db.supplierDao().purchaseTotalsBySupplier()

            resultContainer.removeAllViews()
            resultContainer.addView(sectionHeader("Sales by Customer", blue))
            if (custTotals.isEmpty()) {
                resultContainer.addView(emptyText("Koi sale nahi hui"))
            } else {
                for (c in custTotals) {
                    resultContainer.addView(rowTwoColumn(c.customerName, "Rs %.2f".format(c.total), blue))
                }
            }
            resultContainer.addView(spacerView())
            resultContainer.addView(sectionHeader("Purchases by Supplier", orange))
            if (supTotals.isEmpty()) {
                resultContainer.addView(emptyText("Koi purchase nahi hui"))
            } else {
                for (s in supTotals) {
                    resultContainer.addView(rowTwoColumn(s.supplierName, "Rs %.2f".format(s.total), orange))
                }
            }
        }
    }

    // ================= TABS (Customer / Supplier) =================
    private fun buildTabs() {
        tabRow.removeAllViews()
        tabRow.addView(Button(this).apply {
            text = "CUSTOMERS"
            setTextColor(Color.WHITE)
            background = roundedBg(if (showingCustomers) blue else "#90A4AE")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 8, 0) }
            setOnClickListener { showCustomers() }
        })
        tabRow.addView(Button(this).apply {
            text = "SUPPLIERS"
            setTextColor(Color.WHITE)
            background = roundedBg(if (!showingCustomers) orange else "#90A4AE")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 0, 0) }
            setOnClickListener { showSuppliers() }
        })
    }

    private fun showCustomers() {
        showingCustomers = true
        buildTabs()
        partySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, customers.map { it.name })
        resultContainer.removeAllViews()
    }

    private fun showSuppliers() {
        showingCustomers = false
        buildTabs()
        partySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, suppliers.map { it.name })
        resultContainer.removeAllViews()
    }

    // ================= RUN REPORT (item / statement) =================
    private fun runSelectedReport() {
        val pos = partySpinner.selectedItemPosition
        if (pos < 0) {
            Toast.makeText(this, "Pehle party select karein", Toast.LENGTH_SHORT).show()
            return
        }

        if (mode == "item") {
            if (showingCustomers) { if (customers.isEmpty()) return; loadItemReportCustomer(customers[pos]) }
            else { if (suppliers.isEmpty()) return; loadItemReportSupplier(suppliers[pos]) }
        } else {
            if (showingCustomers) { if (customers.isEmpty()) return; loadStatementCustomer(customers[pos]) }
            else { if (suppliers.isEmpty()) return; loadStatementSupplier(suppliers[pos]) }
        }
    }

    private fun loadItemReportCustomer(c: Customer) {
        lifecycleScope.launch {
            val items = PosDatabase.get(this@PartyReportsActivity).saleDao().itemReportByCustomer(c.id)
            renderItemReport(c.name, items, blue)
        }
    }

    private fun loadItemReportSupplier(s: Supplier) {
        lifecycleScope.launch {
            val items = PosDatabase.get(this@PartyReportsActivity).purchaseDao().itemReportBySupplier(s.id)
            renderItemReport(s.name, items, orange)
        }
    }

    private fun renderItemReport(partyName: String, items: List<PartyItemReport>, colorHex: String) {
        resultContainer.removeAllViews()
        resultContainer.addView(sectionHeader(partyName, colorHex))
        if (items.isEmpty()) {
            resultContainer.addView(emptyText("Koi item record nahi mila"))
            return
        }
        for (it in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14, 10, 14, 10)
                background = roundedBg("#F4F3FB")
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 8) }
            }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            top.addView(TextView(this).apply {
                text = it.product; textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            top.addView(TextView(this).apply {
                text = "Rs %.2f".format(it.totalAmount)
                setTextColor(Color.parseColor(colorHex))
                textSize = 14f
            })
            row.addView(top)
            row.addView(TextView(this).apply {
                text = "Qty: ${it.totalQty}"
                textSize = 12f
                setTextColor(Color.GRAY)
            })
            resultContainer.addView(row)
        }
    }

    private fun loadStatementCustomer(c: Customer) {
        lifecycleScope.launch {
            val sales = PosDatabase.get(this@PartyReportsActivity).saleDao().salesByCustomer(c.id)
            val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            resultContainer.removeAllViews()
            resultContainer.addView(sectionHeader(c.name, blue))
            resultContainer.addView(rowTwoColumn("Opening Balance", "Rs %.2f".format(c.openingBalance), blue))
            resultContainer.addView(spacerView())

            var running = c.openingBalance
            if (sales.isEmpty()) {
                resultContainer.addView(emptyText("Koi sale nahi hui abhi tak"))
            } else {
                for (s in sales) {
                    running += s.total
                    resultContainer.addView(statementRow(s.invoice, fmt.format(Date(s.createdAt)), s.total, s.paid, running, blue))
                }
            }
            resultContainer.addView(spacerView())
            resultContainer.addView(rowTwoColumn("Closing Balance", "Rs %.2f".format(running), blue))
        }
    }

    private fun loadStatementSupplier(s: Supplier) {
        lifecycleScope.launch {
            val purchases = PosDatabase.get(this@PartyReportsActivity).purchaseDao().purchasesBySupplier(s.id)
            val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            resultContainer.removeAllViews()
            resultContainer.addView(sectionHeader(s.name, orange))
            resultContainer.addView(rowTwoColumn("Opening Balance", "Rs %.2f".format(s.openingBalance), orange))
            resultContainer.addView(spacerView())

            var running = s.openingBalance
            if (purchases.isEmpty()) {
                resultContainer.addView(emptyText("Koi purchase nahi hui abhi tak"))
            } else {
                for (p in purchases) {
                    running += p.total
                    resultContainer.addView(statementRow(p.billNo, fmt.format(Date(p.createdAt)), p.total, p.paid, running, orange))
                }
            }
            resultContainer.addView(spacerView())
            resultContainer.addView(rowTwoColumn("Closing Balance", "Rs %.2f".format(running), orange))
        }
    }

    private fun statementRow(ref: String, date: String, total: Double, paid: Double, runningBalance: Double, colorHex: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 10, 14, 10)
            background = roundedBg("#F4F3FB")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 8) }

            val top = LinearLayout(this@PartyReportsActivity).apply { orientation = LinearLayout.HORIZONTAL }
            top.addView(TextView(this@PartyReportsActivity).apply {
                text = ref; textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            top.addView(TextView(this@PartyReportsActivity).apply {
                text = "Rs %.2f".format(total)
                setTextColor(Color.parseColor(colorHex))
                textSize = 14f
            })
            addView(top)
            addView(TextView(this@PartyReportsActivity).apply {
                text = "$date  •  Paid: Rs %.2f  •  Balance: Rs %.2f".format(paid, runningBalance)
                textSize = 11f
                setTextColor(Color.GRAY)
            })
        }
    }

    private fun sectionHeader(text: String, colorHex: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(Color.parseColor(colorHex))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 8, 0, 10)
    }

    private fun rowTwoColumn(left: String, right: String, colorHex: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(14, 10, 14, 10)
            addView(TextView(this@PartyReportsActivity).apply {
                text = left; textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@PartyReportsActivity).apply {
                text = right; textSize = 14f
                setTextColor(Color.parseColor(colorHex))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        }
    }

    private fun emptyText(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.GRAY)
        setPadding(8, 8, 8, 8)
    }

    private fun roundedBg(colorHex: String) = GradientDrawable().apply {
        setColor(Color.parseColor(colorHex))
        cornerRadius = 12f
    }

    private fun spacerView() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 16)
    }

    private fun divider(): View {
        return View(this).apply {
            setBackgroundColor(0xFFEEEEEE.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                topMargin = 16; bottomMargin = 16
            }
        }
    }
}
