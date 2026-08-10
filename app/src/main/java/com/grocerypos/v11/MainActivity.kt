package com.grocerypos.v11

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.ui.LoginActivity
import com.grocerypos.v11.ui.SettingsActivity
import com.grocerypos.v11.ui.ProductActivity
import com.grocerypos.v11.ui.PurchaseActivity
import com.grocerypos.v11.ui.SaleActivity
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var todaySaleText: TextView
    private lateinit var todayProfitText: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        installCrashHandler()
        showLastCrashIfAny()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = "IBTISAAM Kiryana Store"
            textSize = 24f
            setPadding(0, 0, 0, 24)
        })

        // ---- Dashboard summary card ----
        val summaryCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            background = roundedBackground("#F3E5F5", 24)
        }
        todaySaleText = TextView(this).apply { text = "Today's Sale: Rs 0"; textSize = 18f }
        todayProfitText = TextView(this).apply { text = "Today's Profit: Rs 0"; textSize = 18f }
        summaryCard.addView(todaySaleText)
        summaryCard.addView(todayProfitText)
        root.addView(summaryCard)

        root.addView(divider())

        // ---- Menu buttons (premium colors) ----
        root.addView(menuButton("New Sale (POS)", "#2E7D32") {
            startActivity(Intent(this, SaleActivity::class.java))
        })
        root.addView(menuButton("Products", "#1565C0") {
            startActivity(Intent(this, ProductActivity::class.java))
        })
        root.addView(menuButton("Purchases", "#EF6C00") {
            startActivity(Intent(this, PurchaseActivity::class.java))
        })
        root.addView(menuButton("Reports", "#6A1B9A") {
            toast("Reports screen abhi khaali hai - agle step mein banayenge")
        })
        root.addView(menuButton("Cash In / Cash Out", "#00838F") {
            toast("Cash register screen agle step mein banayenge")
        })
        root.addView(menuButton("Customers & Suppliers", "#4E342E") {
            toast("Party ledger screen agle step mein banayenge")
        })
        root.addView(menuButton("Settings", "#37474F") {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        root.addView(menuButton("Logout", "#C62828") {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        })

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        loadDashboard()
    }

    override fun onResume() {
        super.onResume()
        loadDashboard()
    }

    private fun loadDashboard() {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@MainActivity)

            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            val startOfDay = cal.timeInMillis
            val endOfDay = startOfDay + 24 * 60 * 60 * 1000L

            val todaySale = db.saleDao().totalSalesBetween(startOfDay, endOfDay)
            val todayProfit = db.saleDao().profitBetween(startOfDay, endOfDay)

            todaySaleText.text = "Today's Sale: Rs %.2f".format(todaySale)
            todayProfitText.text = "Today's Profit: Rs %.2f".format(todayProfit)
        }
    }

    private fun menuButton(label: String, colorHex: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE)
            setPadding(32, 28, 32, 28)
            background = roundedBackground(colorHex, 18)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 10, 0, 10) }
            setOnClickListener { onClick() }
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
            setBackgroundColor(0xFFDDDDDD.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2
            ).apply { setMargins(0, 16, 0, 16) }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ---- Crash capture: saves the stack trace so it can be shown next time the app opens ----
    private fun installCrashHandler() {
        val prefs = getSharedPreferences("crash_log", MODE_PRIVATE)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            prefs.edit().putString("last_crash", sw.toString()).apply()
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun showLastCrashIfAny() {
        val prefs = getSharedPreferences("crash_log", MODE_PRIVATE)
        val crash = prefs.getString("last_crash", null)
        if (crash != null) {
            prefs.edit().remove("last_crash").apply()
            AlertDialog.Builder(this)
                .setTitle("Last Crash Log (screenshot this)")
                .setMessage(crash)
                .setPositiveButton("OK", null)
                .show()
        }
    }
}
