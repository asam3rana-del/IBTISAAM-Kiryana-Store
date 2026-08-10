package com.grocerypos.v11

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.ui.LoginActivity
import com.grocerypos.v11.ui.SettingsActivity
import kotlinx.coroutines.launch
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var todaySalesText: TextView
    private lateinit var todayProfitText: TextView
    private lateinit var monthProfitText: TextView
    private lateinit var yearProfitText: TextView
    private lateinit var cashBalanceText: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        root.addView(layout)

        layout.addView(TextView(this).apply {
            text = "Grocery POS Dashboard"
            textSize = 24f
        })

        layout.addView(View(this).apply { setPadding(0, 16, 0, 16) })

        // ---- Prominent profit / sales summary ----
        todaySalesText = TextView(this).apply { textSize = 18f; text = "Today's Sales: -" }
        todayProfitText = TextView(this).apply { textSize = 18f; text = "Today's Profit: -" }
        monthProfitText = TextView(this).apply { textSize = 18f; text = "This Month's Profit: -" }
        yearProfitText = TextView(this).apply { textSize = 18f; text = "This Year's Profit: -" }
        cashBalanceText = TextView(this).apply { textSize = 18f; text = "Cash in Hand: -" }

        listOf(todaySalesText, todayProfitText, monthProfitText, yearProfitText, cashBalanceText)
            .forEach { layout.addView(it) }

        layout.addView(View(this).apply { setPadding(0, 24, 0, 24) })

        // ---- Navigation buttons ----
        layout.addView(Button(this).apply {
            text = "NEW SALE (POS)"
            setOnClickListener {
                Toast.makeText(this@MainActivity, "POS billing screen - coming soon", Toast.LENGTH_SHORT).show()
            }
        })
        layout.addView(Button(this).apply {
            text = "PURCHASE"
            setOnClickListener {
                Toast.makeText(this@MainActivity, "Purchase screen - coming soon", Toast.LENGTH_SHORT).show()
            }
        })
        layout.addView(Button(this).apply {
            text = "PRODUCTS"
            setOnClickListener {
                Toast.makeText(this@MainActivity, "Products screen - coming soon", Toast.LENGTH_SHORT).show()
            }
        })
        layout.addView(Button(this).apply {
            text = "REPORTS"
            setOnClickListener {
                Toast.makeText(this@MainActivity, "Reports screen - coming soon", Toast.LENGTH_SHORT).show()
            }
        })
        layout.addView(Button(this).apply {
            text = "CASH IN / CASH OUT"
            setOnClickListener { showCashDialog() }
        })
        layout.addView(Button(this).apply {
            text = "SETTINGS"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        })
        layout.addView(Button(this).apply {
            text = "LOGOUT"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                finish()
            }
        })

        setContentView(root)
        loadDashboard()
    }

    override fun onResume() {
        super.onResume()
        loadDashboard()
    }

    private fun loadDashboard() {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@MainActivity)
            val now = System.currentTimeMillis()

            val todayCal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            }
            val monthCal = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            }
            val yearCal = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            }

            val todaySales = db.saleDao().totalSalesBetween(todayCal.timeInMillis, now)
            val todayProfit = db.saleDao().profitBetween(todayCal.timeInMillis, now)
            val monthProfit = db.saleDao().profitBetween(monthCal.timeInMillis, now)
            val yearProfit = db.saleDao().profitBetween(yearCal.timeInMillis, now)

            val cashIn = db.cashTransactionDao().totalBetween("IN", "cash", 0L, now)
            val cashOut = db.cashTransactionDao().totalBetween("OUT", "cash", 0L, now)
            val cashFromSales = db.paymentDao().totalByMethodBetween("cash", 0L, now)
            val cashBalance = cashIn - cashOut + cashFromSales

            todaySalesText.text = "Today's Sales: %.2f".format(todaySales)
            todayProfitText.text = "Today's Profit: %.2f".format(todayProfit)
            monthProfitText.text = "This Month's Profit: %.2f".format(monthProfit)
            yearProfitText.text = "This Year's Profit: %.2f".format(yearProfit)
            cashBalanceText.text = "Cash in Hand: %.2f".format(cashBalance)
        }
    }

    private fun showCashDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("IN", "OUT"))
        }
        val methodSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("cash", "bank"))
        }
        val amount = EditText(this).apply {
            hint = "Amount"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val reason = EditText(this).apply { hint = "Reason (optional)" }

        layout.addView(TextView(this).apply { text = "Type" })
        layout.addView(typeSpinner)
        layout.addView(TextView(this).apply { text = "Method" })
        layout.addView(methodSpinner)
        layout.addView(amount)
        layout.addView(reason)

        AlertDialog.Builder(this)
            .setTitle("Cash In / Cash Out")
            .setView(layout)
            .setPositiveButton("SAVE") { _, _ ->
                val amt = amount.text.toString().toDoubleOrNull() ?: 0.0
                if (amt > 0) {
                    lifecycleScope.launch {
                        PosDatabase.get(this@MainActivity).cashTransactionDao().insert(
                            CashTransaction(
                                type = typeSpinner.selectedItem.toString(),
                                method = methodSpinner.selectedItem.toString(),
                                amount = amt,
                                reason = reason.text.toString()
                            )
                        )
                        loadDashboard()
                    }
                } else {
                    Toast.makeText(this, "Enter a valid amount", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }
}
