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
import com.grocerypos.v11.ui.ReportsActivity
import com.grocerypos.v11.ui.CashActivity
import com.grocerypos.v11.ui.PartyActivity
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var todaySaleValue: TextView
    private lateinit var todayProfitValue: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        // ---- Require login before showing the dashboard ----
        val session = getSharedPreferences("session", MODE_PRIVATE)
        if (session.getString("username", null) == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        installCrashHandler()
        showLastCrashIfAny()

        val bgColor = "#F4F3FB"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 36)
            setBackgroundColor(Color.parseColor(bgColor))
        }

        // ================= HEADER =================
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 22, 24, 22)
            background = roundedBackground("#1A237E", 22)
            elevation = 10f
        }
        header.addView(avatarCircle("IK", 64, "#5C6BC0"))
        val headerText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 0, 0, 0)
        }
        headerText.addView(TextView(this).apply {
            text = "IBTISAAM Kiryana Store"
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        headerText.addView(TextView(this).apply {
            text = "Point of Sale System"
            textSize = 12f
            setTextColor(Color.parseColor("#C5CAE9"))
        })
        header.addView(headerText)
        root.addView(header)

        root.addView(spacer(24))

        // ================= STAT CARDS =================
        val statsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val saleCardParts = statCard("💰", "Today's Sale", "#2E7D32")
        val saleCardView = saleCardParts.first
        todaySaleValue = saleCardParts.second
        val profitCardParts = statCard("📈", "Today's Profit", "#1565C0")
        val profitCardView = profitCardParts.first
        todayProfitValue = profitCardParts.second

        saleCardView.layoutParams =
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,10,0) }
        profitCardView.layoutParams =
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(10,0,0,0) }

        statsRow.addView(saleCardView)
        statsRow.addView(profitCardView)
        root.addView(statsRow)

        root.addView(spacer(28))

        root.addView(TextView(this).apply {
            text = "MENU"
            textSize = 13f
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(4, 0, 0, 12)
        })

        // ================= MENU GRID (2 tiles per row) =================
        val tiles = listOf(
            Tile("🛒", "New Sale", "#2E7D32") { startActivity(Intent(this, SaleActivity::class.java)) },
            Tile("📦", "Products", "#1565C0") { startActivity(Intent(this, ProductActivity::class.java)) },
            Tile("🧾", "Purchases", "#EF6C00") { startActivity(Intent(this, PurchaseActivity::class.java)) },
            Tile("📊", "Reports", "#6A1B9A") { startActivity(Intent(this, ReportsActivity::class.java)) },
            Tile("💵", "Cash In/Out", "#00838F") { startActivity(Intent(this, CashActivity::class.java)) },
            Tile("👥", "Customers &\nSuppliers", "#4E342E") { startActivity(Intent(this, PartyActivity::class.java)) },
            Tile("⚙️", "Settings", "#37474F") { startActivity(Intent(this, SettingsActivity::class.java)) },
            Tile("🚪", "Logout", "#C62828") {
                getSharedPreferences("session", MODE_PRIVATE).edit().remove("username").apply()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
        )

        tiles.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { idx, tile ->
                val tileView = menuTile(tile)
                tileView.layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    if (idx == 0) setMargins(0, 0, 8, 16) else setMargins(8, 0, 0, 16)
                }
                row.addView(tileView)
            }
            root.addView(row)
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bgColor))
            addView(root)
        }
        setContentView(scroll)

        loadDashboard()
    }

    override fun onResume() {
        super.onResume()
        loadDashboard()
    }

    // ---- small holder for a menu tile's icon/label/color/click ----
    private data class Tile(val emoji: String, val label: String, val colorHex: String, val onClick: () -> Unit)

    private fun menuTile(tile: Tile): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(20, 26, 20, 22)
            background = roundedBackground(tile.colorHex, 20)
            elevation = 6f
            addView(TextView(this@MainActivity).apply {
                text = tile.emoji
                textSize = 30f
                gravity = Gravity.CENTER
            })
            addView(TextView(this@MainActivity).apply {
                text = tile.label
                textSize = 13f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, 10, 0, 0)
            })
            setOnClickListener { tile.onClick() }
        }
    }

    // ---- stat card: returns the card view plus its live value TextView ----
    private fun statCard(emoji: String, label: String, colorHex: String): Pair<LinearLayout, TextView> {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 20, 22, 20)
            background = roundedBackground(colorHex, 20)
            elevation = 6f
        }
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        topRow.addView(TextView(this).apply { text = emoji; textSize = 18f })
        topRow.addView(TextView(this).apply {
            text = "  $label"
            setTextColor(Color.WHITE)
            textSize = 13f
        })
        card.addView(topRow)
        val valueText = TextView(this).apply {
            text = "Rs 0.00"
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 8, 0, 0)
        }
        card.addView(valueText)
        return Pair(card, valueText)
    }

    private fun avatarCircle(initials: String, sizeDp: Int, colorHex: String): FrameLayout {
        val size = (sizeDp * resources.displayMetrics.density).toInt()
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(colorHex))
            }
            addView(TextView(this@MainActivity).apply {
                text = initials
                textSize = 20f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
        }
    }

    private fun spacer(heightPx: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx)
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

            todaySaleValue.text = "Rs %.2f".format(todaySale)
            todayProfitValue.text = "Rs %.2f".format(todayProfit)
        }
    }

    private fun roundedBackground(colorHex: String, cornerRadius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(colorHex))
            this.cornerRadius = cornerRadius.toFloat()
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
