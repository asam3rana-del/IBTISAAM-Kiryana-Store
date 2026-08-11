package com.grocerypos.v11.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.Customer
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Supplier
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PartyActivity : AppCompatActivity() {

    private lateinit var tabRow: LinearLayout
    private lateinit var formContainer: LinearLayout
    private lateinit name: EditText.let { }
    private lateinit var nameField: EditText
    private lateinit var phoneField: EditText
    private lateinit var extraField: EditText
    private lateinit var listContainer: LinearLayout

    private var showingCustomers = true

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 32)
        }

        root.addView(TextView(this).apply { text = "Customers & Suppliers"; textSize = 22f; setPadding(0,0,0,20) })

        // ---- Tab switch ----
        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(tabRow)

        root.addView(divider())

        // ---- Add form ----
        formContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nameField = EditText(this).apply { hint = "Name" }
        phoneField = EditText(this).apply { hint = "Phone (optional)" }
        extraField = EditText(this).apply {
            hint = "Credit Limit (optional)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        formContainer.addView(nameField)
        formContainer.addView(phoneField)
        formContainer.addView(extraField)
        formContainer.addView(Button(this).apply {
            text = "SAVE"
            setTextColor(Color.WHITE)
            background = roundedBackground("#1565C0", 14)
            setOnClickListener { saveParty() }
        })
        root.addView(formContainer)

        root.addView(divider())

        root.addView(TextView(this).apply { text = "List"; textSize = 18f; setPadding(0,8,0,8) })
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        setContentView(ScrollView(this).apply { addView(root) })

        buildTabs()
        showCustomers()
    }

    private fun buildTabs() {
        tabRow.removeAllViews()
        tabRow.addView(Button(this).apply {
            text = "CUSTOMERS"
            setTextColor(Color.WHITE)
            background = roundedBackground(if (showingCustomers) "#1565C0" else "#90A4AE", 14)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,8,0) }
            setOnClickListener { showCustomers() }
        })
        tabRow.addView(Button(this).apply {
            text = "SUPPLIERS"
            setTextColor(Color.WHITE)
            background = roundedBackground(if (!showingCustomers) "#EF6C00" else "#90A4AE", 14)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8,0,0,0) }
            setOnClickListener { showSuppliers() }
        })
    }

    private fun showCustomers() {
        showingCustomers = true
        buildTabs()
        extraField.hint = "Credit Limit (optional)"
        extraField.visibility = View.VISIBLE
        loadCustomers()
    }

    private fun showSuppliers() {
        showingCustomers = false
        buildTabs()
        extraField.visibility = View.GONE
        loadSuppliers()
    }

    private fun saveParty() {
        val n = nameField.text.toString().trim()
        if (n.isEmpty()) {
            Toast.makeText(this, "Naam zaroori hai", Toast.LENGTH_SHORT).show()
            return
        }
        val phone = phoneField.text.toString().trim()

        lifecycleScope.launch {
            if (showingCustomers) {
                val limit = extraField.text.toString().toDoubleOrNull() ?: 0.0
                PosDatabase.get(this@PartyActivity).customerDao().insert(
                    Customer(name = n, phone = phone, creditLimit = limit, balance = 0.0)
                )
            } else {
                PosDatabase.get(this@PartyActivity).supplierDao().insert(
                    Supplier(name = n, phone = phone, balance = 0.0)
                )
            }
            Toast.makeText(this@PartyActivity, "Saved", Toast.LENGTH_SHORT).show()
            nameField.text.clear()
            phoneField.text.clear()
            extraField.text.clear()
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
                    listContainer.addView(partyRow(c.name, c.phone, "Balance: Rs %.2f".format(c.balance)))
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
                    listContainer.addView(partyRow(s.name, s.phone, "Balance: Rs %.2f".format(s.balance)))
                }
            }
        }
    }

    private fun partyRow(name: String, phone: String, balanceText: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 14, 16, 14)
            addView(TextView(this@PartyActivity).apply { text = name; textSize = 16f })
            if (phone.isNotEmpty()) {
                addView(TextView(this@PartyActivity).apply { text = phone; textSize = 13f; setTextColor(Color.GRAY) })
            }
            addView(TextView(this@PartyActivity).apply { text = balanceText; textSize = 14f })
            addView(divider())
        }
    }

    private fun emptyText(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.GRAY)
        setPadding(8,8,8,8)
    }

    private fun roundedBackground(colorHex: String, cornerRadius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(colorHex))
            this.cornerRadius = cornerRadius.toFloat()
        }
    }

    private fun divider(): View {
        return View(this).apply {
            setBackgroundColor(0xFFEEEEEE.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
        }
    }
}
