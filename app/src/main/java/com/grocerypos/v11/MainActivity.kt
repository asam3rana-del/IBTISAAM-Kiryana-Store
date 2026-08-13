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
    private var todayProfitValue: TextView? = null
    private var role: String = "cashier"

    private val bgColor = "#F3F4F9"
    private val cardWhite = "#FFFFFF"
    private val textDark = "#1A1D2E"
    private val textMuted = "#8A8FA3"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val session = getSharedPreferences("session", MODE_PRIVATE)
        if (session.getString("username", null) == null) {
            startActivity(Intent(this@MainActivity, LoginActivity::class.java))
            finish()
            return
        }
        role = session.getString("role", "cashier") ?: "cashier"

        installCrashHandler()
        showLastCrashIfAny()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(bgColor))
        }

        // ================= HEADER (gradient, rounded bottom) =================
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(30, 60, 30, 44)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#1A237E"), Color.parseColor("#3949AB"))
            ).apply {
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, 36f, 36f, 36f, 36f)
            }
        }
        header.addView(avatarCircle("IK", 58, "#FFFFFF", "#3949AB"))
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
        headerText.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 6, 0, 0)
            addView(View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(16, 16)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#69F0AE"))
                }
            })
            addView(TextView(this@MainActivity).apply {
                text = "  ${role.replaceFirstChar { it.uppercase() }} Panel"
                textSize = 12.5f
                setTextColor(Color.parseColor("#C5CAE9"))
            })
        })
        header.addView(headerText)
        root.addView(header)

        // ================= scrollable body =================
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 36)
        }

        // ================= STAT CARDS =================
        val statsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val saleCardParts = premiumStatCard("💰", "Today's Sale", "#2E7D32", "#E8F5E9")
        val saleCardView = saleCardParts.first
        todaySaleValue = saleCardParts.second

        if (role == "admin") {
            val profitCardParts = premiumStatCard("📈", "Today's Profit", "#1565C0", "#E3F2FD")
            val profitCardView = profitCardParts.first
            todayProfitValue = profitCardParts.second

            saleCardView.layoutParams =
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,9,0) }
            profitCardView.layoutParams =
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(9,0,0,0) }

            statsRow.addView(saleCardView)
            statsRow.addView(profitCardView)
        } else {
            saleCardView.layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            statsRow.addView(saleCardView)
        }

        body.addView(statsRow)
        body.addView(spacer(30))

        body.addView(TextView(this).apply {
            text = "QUICK ACCESS"
            textSize = 12.5f
            setTextColor(Color.parseColor(textMuted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(4, 0, 0, 14)
        })

        // ================= MENU GRID (role-based, premium tiles) =================
        val tiles: List<Tile> = when (role) {
            "admin" -> listOf(
                Tile("🛒", "New Sale", "#2E7D32", "#E8F5E9") { startActivity(Intent(this@MainActivity, SaleActivity::class.java)) },
                Tile("📦", "Products", "#1565C0", "#E3F2FD") { startActivity(Intent(this@MainActivity, ProductActivity::class.java)) },
                Tile("🧾", "Purchases", "#EF6C00", "#FFF3E0") { startActivity(Intent(this@MainActivity, PurchaseActivity::class.java)) },
                Tile("📊", "Reports", "#6A1B9A", "#F3E5F5") { startActivity(Intent(this@MainActivity, ReportsActivity::class.java)) },
                Tile("💵", "Cash In/Out", "#00838F", "#E0F7FA") { startActivity(Intent(this@MainActivity, CashActivity::class.java)) },
                Tile("👥", "Customers &\nSuppliers", "#4E342E", "#EFEBE9") { startActivity(Intent(this@MainActivity, PartyActivity::class.java)) },
                Tile("⚙️", "Settings", "#37474F", "#ECEFF1") { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            )
            "manager" -> listOf(
                Tile("🛒", "New Sale", "#2E7D32", "#E8F5E9") { startActivity(Intent(this@MainActivity, SaleActivity::class.java)) },
                Tile("🧾", "Purchases", "#EF6C00", "#FFF3E0") { startActivity(Intent(this@MainActivity, PurchaseActivity::class.java)) },
                Tile("📊", "Reports", "#6A1B9A", "#F3E5F5") { startActivity(Intent(this@MainActivity, ReportsActivity::class.java)) },
                Tile("🚪", "Logout", "#C62828", "#FFEBEE") { doLogout() }
            )
            else -> listOf(
                Tile("🛒", "New Sale", "#2E7D32", "#E8F5E9") { startActivity(Intent(this@MainActivity, SaleActivity::class.java)) },
                Tile("💵", "Cash In/Out", "#00838F", "#E0F7FA") { startActivity(Intent(this@MainActivity, CashActivity::class.java)) },
                Tile("👥", "Customers &\nSuppliers", "#4E342E", "#EFEBE9") { startActivity(Intent(this@MainActivity, PartyActivity::class.java)) },
                Tile("🚪", "Logout", "#C62828", "#FFEBEE") { doLogout() }
            )
        }

        tiles.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { idx, tile ->
                val tileView = premiumMenuTile(tile)
                tileView.layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    if (idx == 0) setMargins(0, 0, 8, 14) else setMargins(8, 0, 0, 14)
                }
                row.addView(tileView)
            }
            body.addView(row)
        }

        root.addView(body)

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

    private fun doLogout() {
        getSharedPreferences("session", MODE_PRIVATE).edit().clear().apply()
        startActivity(Intent(this@MainActivity, LoginActivity::class.java))
        finish()
    }

    private data class Tile(val emoji: String, val label: String, val accentHex: String, val tintHex: String, val onClick: () -> Unit)

    // ---- premium white card tile with a colored icon "chip" ----
    private fun premiumMenuTile(tile: Tile): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(18, 26, 18, 22)
            background = roundedBackground(cardWhite, 24)
            elevation = 4f

            addView(FrameLayout(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (52 * resources.displayMetrics.density).toInt(),
                    (52 * resources.displayMetrics.density).toInt()
                )
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(tile.tintHex))
                }
                addView(TextView(this@MainActivity).apply {
                    text = tile.emoji
                    textSize = 22f
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                    )
                })
            })

            addView(TextView(this@MainActivity).apply {
                text = tile.label
                textSize = 13f
                setTextColor(Color.parseColor(textDark))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 14, 0, 0)
            })

            // subtle accent underline
            addView(View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams((26 * resources.displayMetrics.density).toInt(), 4).apply {
                    topMargin = 10
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                background = GradientDrawable().apply {
                    cornerRadius = 4f
                    setColor(Color.parseColor(tile.accentHex))
                }
            })

            setOnClickListener { tile.onClick() }
        }
    }

    // ---- premium stat card: white bg, icon chip, big bold value ----
    private fun premiumStatCard(emoji: String, label: String, accentHex: String, tintHex: String): Pair<LinearLayout, TextView> {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 22, 24, 22)
            background = roundedBackground(cardWhite, 24)
            elevation = 4f
        }
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        topRow.addView(FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (38 * resources.displayMetrics.density).toInt(),
                (38 * resources.displayMetrics.density).toInt()
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(tintHex))
            }
            addView(TextView(this@MainActivity).apply {
                text = emoji
                textSize = 16f
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
        })
        topRow.addView(TextView(this).apply {
            text = "  $label"
            setTextColor(Color.parseColor(textMuted))
            textSize = 12.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        card.addView(topRow)
        val valueText = TextView(this).apply {
            text = "Rs 0.00"
            setTextColor(Color.parseColor(accentHex))
            textSize = 21f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 12, 0, 0)
        }
        card.addView(valueText)
        return Pair(card, valueText)
    }

    private fun avatarCircle(initials: String, sizeDp: Int, bgHex: String, textHex: String): FrameLayout {
        val size = (sizeDp * resources.displayMetrics.density).toInt()
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(bgHex))
            }
            addView(TextView(this@MainActivity).apply {
                text = initials
                textSize = 18f
                setTextColor(Color.parseColor(textHex))
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
            todaySaleValue.text = "Rs %.2f".format(todaySale)

            if (role == "admin") {
                val todayProfit = db.saleDao().profitBetween(startOfDay, endOfDay)
                todayProfitValue?.text = "Rs %.2f".format(todayProfit)
            }
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
