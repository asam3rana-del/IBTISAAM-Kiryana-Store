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
import com.grocerypos.v11.Customer
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Supplier
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PartyActivity : AppCompatActivity() {

    private val blue = "#1565C0"
    private val orange = "#EF6C00"

    private lateinit var tabRow: LinearLayout
    private lateinit var formContainer: LinearLayout
    private lateinit var nameField: EditText
    private lateinit var phoneField: EditText
    private lateinit var creditLimitField: EditText
    private lateinit var openingBalanceField: EditText
    private lateinit var listContainer: LinearLayout

    private var showingCustomers = true

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 32)
        }

        root.addView(TextView(this).apply { text = "Customers & Suppliers"; textSize = 22f; setPadding(0,0,0,20) })

        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(tabRow)

        root.addView(divider())

        formContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nameField = EditText(this).apply { hint = "Name" }
        phoneField = EditText(this).apply { hint = "Phone (optional)" }
        creditLimitField = EditText(this).apply {
            hint = "Credit Limit (optional)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        openingBalanceField = EditText(this).apply {
            hint = "Opening Balance (Rs, if any previous due)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        formContainer.addView(nameField)
        formContainer.addView(phoneField)
        formContainer.addView(creditLimitField)
        formContainer.addView(openingBalanceField)
        formContainer.addView(Button(this).apply {
            text = "SAVE"
            setTextColor(Color.WHITE)
            background = roundedBackground(blue, 14)
            setOnClickListener { saveParty() }
        })
        root.addView(formContainer)

        root.addView(divider())

        root.addView(TextView(this).apply { text = "List (tap for history)"; textSize = 15f; setTextColor(Color.GRAY); setPadding(0,8,0,8) })
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        setContentView(ScrollView(this).apply { addView(root) })

        buildTabs()
        showCustomers()
    }

    override fun onResume() {
        super.onResume()
        if (showingCustomers) loadCustomers() else loadSuppliers()
    }

    private fun buildTabs() {
        tabRow.removeAllViews()
        tabRow.addView(Button(this).apply {
            text = "CUSTOMERS"
            setTextColor(Color.WHITE)
            background = roundedBackground(if (showingCustomers) blue else "#90A4AE", 14)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,8,0) }
            setOnClickListener { showCustomers() }
        })
        tabRow.addView(Button(this).apply {
            text = "SUPPLIERS"
            setTextColor(Color.WHITE)
            background = roundedBackground(if (!showingCustomers) orange else "#90A4AE", 14)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8,0,0,0) }
            setOnClickListener { showSuppliers() }
        })
    }

    private fun showCustomers() {
        showingCustomers = true
        buildTabs()
        creditLimitField.visibility = View.VISIBLE
        loadCustomers()
    }

    private fun showSuppliers() {
        showingCustomers = false
        buildTabs()
        creditLimitField.visibility = View.GONE
        loadSuppliers()
    }

    private fun saveParty() {
        val n = nameField.text.toString().trim()
        if (n.isEmpty()) {
            Toast.makeText(this, "Naam zaroori hai", Toast.LENGTH_SHORT).show()
            return
        }
        val phone = phoneField.text.toString().trim()
        val opening = openingBalanceField.text.toString().toDoubleOrNull() ?: 0.0

        lifecycleScope.launch {
            if (showingCustomers) {
                val limit = creditLimitField.text.toString().toDoubleOrNull() ?: 0.0
                PosDatabase.get(this@PartyActivity).customerDao().insert(
                    Customer(name = n, phone = phone, creditLimit = limit, openingBalance = opening, balance = 0.0)
                )
            } else {
                PosDatabase.get(this@PartyActivity).supplierDao().insert(
                    Supplier(name = n, phone = phone, openingBalance = opening, balance = 0.0)
                )
            }
            Toast.makeText(this@PartyActivity, "Saved", Toast.LENGTH_SHORT).show()
            nameField.text.clear()
            phoneField.text.clear()
            creditLimitField.text.clear()
            openingBalanceField.text.clear()
        }
    }

    private fun loadCustomers() {
        lifecycleScope.launch {
            PosDatabase.get(this@PartyActivity).customerDao().all().collectLatest { list ->
                listContainer.removeAllViews()
                if (list.isEmpty()) {
                    listContainer.addView(emptyText("Koi customer nahi hai"))
                }
                for (c in list) {
                    listContainer.addView(partyRow(c.name, c.phone, c.openingBalance, c.balance) { openCustomerHistory(c) })
                }
            }
        }
    }

    private fun loadSuppliers() {
        lifecycleScope.launch {
            PosDatabase.get(this@PartyActivity).supplierDao().all().collectLatest { list ->
                listContainer.removeAllViews()
                if (list.isEmpty()) {
                    listContainer.addView(emptyText("Koi supplier nahi hai"))
                }
                for (s in list) {
                    listContainer.addView(partyRow(s.name, s.phone, s.openingBalance, s.balance) { openSupplierHistory(s) })
                }
            }
        }
    }

    private fun partyRow(name: String, phone: String, opening: Double, running: Double, onClick: () -> Unit): LinearLayout {
        val closing = opening + running
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 14, 16, 14)
            setOnClickListener { onClick() }
            addView(TextView(this@PartyActivity).apply { text = name; textSize = 16f })
            if (phone.isNotEmpty()) {
                addView(TextView(this@PartyActivity).apply { text = phone; textSize = 13f; setTextColor(Color.GRAY) })
            }
            val balRow = LinearLayout(this@PartyActivity).apply { orientation = LinearLayout.HORIZONTAL }
            balRow.addView(TextView(this@PartyActivity).apply {
                text = "Opening: Rs %.2f".format(opening)
                textSize = 13f
                setTextColor(Color.DKGRAY)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            balRow.addView(TextView(this@PartyActivity).apply {
                text = "Closing: Rs %.2f".format(closing)
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(if (closing > 0) "#C62828" else "#2E7D32"))
            })
            addView(balRow)
            addView(TextView(this@PartyActivity).apply {
                text = "Tap for full history"
                textSize = 11f
                setTextColor(Color.parseColor(blue))
                setPadding(0, 4, 0, 0)
            })
            addView(divider())
        }
    }

    // ================= Customer full history =================
    private fun openCustomerHistory(c: Customer) {
        lifecycleScope.launch {
            val sales = PosDatabase.get(this@PartyActivity).saleDao().salesByCustomer(c.id)
            val content = historyDialogContainer(c.name, blue, c.openingBalance, c.balance)
            val body = content.getChildAt(1) as LinearLayout

            if (sales.isEmpty()) {
                body.addView(emptyText("Koi sale nahi hui abhi tak"))
            } else {
                val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
                for (s in sales) {
                    body.addView(historyRow(s.invoice, fmt.format(Date(s.createdAt)), s.total, s.paid, blue))
                }
            }

            val dialog = AlertDialog.Builder(this@PartyActivity).setView(content).create()
            (content.getChildAt(2) as LinearLayout).addView(Button(this@PartyActivity).apply {
                text = "Close"
                setOnClickListener { dialog.dismiss() }
            })
            dialog.show()
        }
    }

    // ================= Supplier full history =================
    private fun openSupplierHistory(s: Supplier) {
        lifecycleScope.launch {
            val purchases = PosDatabase.get(this@PartyActivity).purchaseDao().purchasesBySupplier(s.id)
            val content = historyDialogContainer(s.name, orange, s.openingBalance, s.balance)
            val body = content.getChildAt(1) as LinearLayout

            if (purchases.isEmpty()) {
                body.addView(emptyText("Koi purchase nahi hui abhi tak"))
            } else {
                val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
                for (p in purchases) {
                    body.addView(historyRow(p.billNo, fmt.format(Date(p.createdAt)), p.total, p.paid, orange))
                }
            }

            val dialog = AlertDialog.Builder(this@PartyActivity).setView(content).create()
            (content.getChildAt(2) as LinearLayout).addView(Button(this@PartyActivity).apply {
                text = "Close"
                setOnClickListener { dialog.dismiss() }
            })
            dialog.show()
        }
    }

    // ================= shared dialog helpers =================
    private fun historyDialogContainer(name: String, colorHex: String, opening: Double, running: Double): LinearLayout {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            setBackgroundColor(Color.parseColor(colorHex))
        }
        header.addView(TextView(this).apply {
            text = name; textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val balRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 10, 0, 0) }
        balRow.addView(TextView(this).apply {
            text = "Opening: Rs %.2f".format(opening)
            setTextColor(Color.WHITE); textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        balRow.addView(TextView(this).apply {
            text = "Closing: Rs %.2f".format(opening + running)
            setTextColor(Color.WHITE); textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        header.addView(balRow)
        outer.addView(header)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 8)
        }
        outer.addView(body)

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 8, 20, 20)
        }
        outer.addView(footer)
        return outer
    }

    private fun historyRow(ref: String, date: String, total: Double, paid: Double, colorHex: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            background = roundedBackground("#F4F3FB", 10)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 8) }

            val top = LinearLayout(this@PartyActivity).apply { orientation = LinearLayout.HORIZONTAL }
            top.addView(TextView(this@PartyActivity).apply {
                text = ref; textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            top.addView(TextView(this@PartyActivity).apply {
                text = "Rs %.2f".format(total)
                setTextColor(Color.parseColor(colorHex))
                textSize = 14f
            })
            addView(top)
            addView(TextView(this@PartyActivity).apply {
                text = "$date  •  Paid: Rs %.2f".format(paid)
                textSize = 11f
                setTextColor(Color.GRAY)
            })
        }
    }

    private fun emptyText(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.GRAY)
        setPadding(8,8,8,8)
    }

    private fun roundedBackground(colorHex: String, cornerRadius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(colorHex))
        this.cornerRadius = cornerRadius.toFloat()
    }

    private fun divider(): View {
        return View(this).apply {
            setBackgroundColor(0xFFEEEEEE.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
        }
    }
}
