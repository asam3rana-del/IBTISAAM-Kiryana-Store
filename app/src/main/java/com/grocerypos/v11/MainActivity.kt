package com.grocerypos.v11

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.ui.LoginActivity
import com.grocerypos.v11.ui.SettingsActivity
import com.grocerypos.v11.ui.ProductActivity
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
        }
        todaySaleText = TextView(this).apply { text = "Today's Sale: Rs 0"; textSize = 18f }
        todayProfitText = TextView(this).apply { text = "Today's Profit: Rs 0"; textSize = 18f }
        summaryCard.addView(todaySaleText)
        summaryCard.addView(todayProfitText)
        root.addView(summaryCard)

        root.addView(divider())

        // ---- Menu buttons ----
        root.addView(menuButton("New Sale (POS)") {
            toast("New Sale screen agle step mein banayenge")
        })
        root.addView(menuButton("Products") {
            startActivity(Intent(this, ProductActivity::class.java))
        })
        root.addView(menuButton("Purchases") {
            toast("Purchase screen abhi khaali hai - agle step mein banayenge")
        })
        root.addView(menuButton("Reports") {
            toast("Reports screen abhi khaali hai - agle step mein banayenge")
        })
        root.addView(menuButton("Cash In / Cash Out") {
            toast("Cash register screen agle step mein banayenge")
        })
        root.addView(menuButton("Customers & Suppliers") {
            toast("Party ledger screen agle step mein banayenge")
        })
        root.addView(menuButton("Settings") {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        root.addView(menuButton("Logout") {
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

    private fun menuButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            gravity = Gravity.START
            setOnClickListener { onClick() }
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
