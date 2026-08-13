package com.grocerypos.v11.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.CashTransaction
import com.grocerypos.v11.PosDatabase
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class CashActivity : AppCompatActivity() {

    private lateinit var amount: EditText
    private lateinit var reason: EditText
    private lateinit var methodSpinner: Spinner
    private lateinit var inTotalText: TextView
    private lateinit var outTotalText: TextView
    private lateinit var listContainer: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 32)
        }

        root.addView(TextView(this).apply { text = "Cash In / Cash Out"; textSize = 22f; setPadding(0,0,0,20) })

        // Today's totals
        val totalsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val inCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
            background = roundedBackground("#2E7D32", 14)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,8,0) }
        }
        inCard.addView(TextView(this).apply { text = "Today Cash In"; setTextColor(Color.WHITE); textSize = 13f })
        inTotalText = TextView(this).apply { text = "Rs 0.00"; setTextColor(Color.WHITE); textSize = 18f }
        inCard.addView(inTotalText)

        val outCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
            background = roundedBackground("#C62828", 14)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8,0,0,0) }
        }
        outCard.addView(TextView(this).apply { text = "Today Cash Out"; setTextColor(Color.WHITE); textSize = 13f })
        outTotalText = TextView(this).apply { text = "Rs 0.00"; setTextColor(Color.WHITE); textSize = 18f }
        outCard.addView(outTotalText)

        totalsRow.addView(inCard)
        totalsRow.addView(outCard)
        root.addView(totalsRow)

        root.addView(divider())

        // ---- Entry form ----
        root.addView(TextView(this).apply { text = "New Entry"; textSize = 18f; setPadding(0,16,0,8) })

        amount = EditText(this).apply {
            hint = "Amount"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        root.addView(amount)

        methodSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@CashActivity, android.R.layout.simple_spinner_dropdown_item, listOf("cash", "bank"))
        }
        root.addView(methodSpinner)

        reason = EditText(this).apply { hint = "Reason / Note (optional)" }
        root.addView(reason)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0,16,0,16) }
        btnRow.addView(Button(this).apply {
            text = "CASH IN"
            setTextColor(Color.WHITE)
            background = roundedBackground("#2E7D32", 14)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,8,0) }
            setOnClickListener { saveEntry("IN") }
        })
        btnRow.addView(Button(this).apply {
            text = "CASH OUT"
            setTextColor(Color.WHITE)
            background = roundedBackground("#C62828", 14)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8,0,0,0) }
            setOnClickListener { saveEntry("OUT") }
        })
        root.addView(btnRow)

        root.addView(divider())

        root.addView(TextView(this).apply { text = "Recent Transactions"; textSize = 18f; setPadding(0,8,0,8) })
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        setContentView(ScrollView(this).apply { addView(root) })

        loadTodayTotals()
        loadTransactions()
    }

    private fun saveEntry(type: String) {
        val amt = amount.text.toString().toDoubleOrNull()
        if (amt == null || amt <= 0.0) {
            Toast.makeText(this, "Sahi amount likhein", Toast.LENGTH_SHORT).show()
            return
        }
        val method = methodSpinner.selectedItem?.toString() ?: "cash"
        val note = reason.text.toString().trim()

        lifecycleScope.launch {
            PosDatabase.get(this@CashActivity).cashTransactionDao().insert(
                CashTransaction(type = type, method = method, amount = amt, reason = note)
            )
            Toast.makeText(this@CashActivity, "Saved", Toast.LENGTH_SHORT).show()
            amount.text.clear()
            reason.text.clear()
            loadTodayTotals()
        }
    }

    private fun loadTodayTotals() {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@CashActivity)
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            val end = start + 24 * 60 * 60 * 1000L

            val cashIn = db.cashTransactionDao().totalBetween("IN", "cash", start, end)
            val bankIn = db.cashTransactionDao().totalBetween("IN", "bank", start, end)
            val cashOut = db.cashTransactionDao().totalBetween("OUT", "cash", start, end)
            val bankOut = db.cashTransactionDao().totalBetween("OUT", "bank", start, end)

            inTotalText.text = "Rs %.2f".format(cashIn + bankIn)
            outTotalText.text = "Rs %.2f".format(cashOut + bankOut)
        }
    }

    private fun loadTransactions() {
        lifecycleScope.launch {
            PosDatabase.get(this@CashActivity).cashTransactionDao().all().collectLatest { list ->
                listContainer.removeAllViews()
                if (list.isEmpty()) {
                    listContainer.addView(TextView(this@CashActivity).apply {
                        text = "Koi entry nahi hai"
                        setTextColor(Color.GRAY)
                        setPadding(8,8,8,8)
                    })
                }
                val fmt = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
                for (t in list.take(50)) {
                    val color = if (t.type == "IN") "#2E7D32" else "#C62828"
                    val sign = if (t.type == "IN") "+" else "-"
                    listContainer.addView(LinearLayout(this@CashActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(16, 14, 16, 14)
                        val row = LinearLayout(this@CashActivity).apply { orientation = LinearLayout.HORIZONTAL }
                        row.addView(TextView(this@CashActivity).apply {
                            text = "${t.method.uppercase()}" + if (t.reason.isNotEmpty()) " - ${t.reason}" else ""
                            textSize = 14f
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        row.addView(TextView(this@CashActivity).apply {
                            text = "$sign Rs %.2f".format(t.amount)
                            setTextColor(Color.parseColor(color))
                            textSize = 15f
                        })
                        addView(row)
                        addView(TextView(this@CashActivity).apply {
                            text = fmt.format(Date(t.createdAt))
                            setTextColor(Color.GRAY)
                            textSize = 12f
                        })
                    })
                    listContainer.addView(divider())
                }
            }
        }
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
