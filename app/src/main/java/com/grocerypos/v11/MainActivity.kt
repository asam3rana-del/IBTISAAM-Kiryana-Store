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
import com.grocerypos.v11.ui.PartyDashboardActivity
import com.grocerypos.v11.ui.ItemSearchActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var todaySaleValue: TextView
    private var todayProfitValue: TextView? = null
    private var role: String = "cashier"
    private lateinit var shopNameHeader: TextView

    // ---- Customers & Suppliers mini-summary (embedded on this dashboard) ----
    private lateinit var youllGetValue: TextView
    private lateinit var youllGiveValue: TextView

    private val bgColor = "#F3F4F9"
    private val cardWhite = "#FFFFFF"
    private val textDark = "#1A1D2E"
    private val textMuted = "#8A8FA3"
    private val partyGreen = "#2E7D32"
    private val partyRed = "#C62828"

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

        // ---- Settings gear icon (now on the LEFT, premium chip) ----
        header.addView(FrameLayout(this).apply {
            val size = (44 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(0, 0, 18, 0)
            }
            elevation = 6f
            val chipBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(Color.parseColor("#5C6BC0"), Color.parseColor("#3949AB"))
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
                setStroke((1 * resources.displayMetrics.density).toInt(), Color.parseColor("#7986CB"))
            }
            isClickable = true
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.WHITE).withAlpha(60),
                chipBg,
                null
            )
            addView(TextView(this@MainActivity).apply {
                text = "⚙️"
                textSize = 20f
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })

        header.addView(avatarCircle("IK", 58, "#FFFFFF", "#3949AB"))
        val headerText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        shopNameHeader = TextView(this).apply {
            text = "IBTISAAM Kiryana Store"
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        headerText.addView(shopNameHeader)
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

        // ================= CUSTOMERS & SUPPLIERS (embedded summary) =================
        body.addView(buildCustomerSupplierSection())
        body.addView(spacer(30))

        // ================= QUICK ACTIONS: SALE + PURCHASE (big cards) =================
        body.addView(TextView(this).apply {
            text = "QUICK ACTIONS"
            textSize = 12.5f
            setTextColor(Color.parseColor(textMuted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(4, 0, 0, 14)
        })

        val saleQuick = QuickAction("🛒", "Sale", "Start a new sale", "#2E7D32", "#E8F5E9") {
            startActivity(Intent(this@MainActivity, SaleActivity::class.java))
        }
        // Cashiers don't have purchase access in the original menu — keep that rule.
        val purchaseQuick: QuickAction? = if (role == "admin" || role == "manager") {
            QuickAction("🧾", "Purchase", "Record a purchase", "#EF6C00", "#FFF3E0") {
                startActivity(Intent(this@MainActivity, PurchaseActivity::class.java))
            }
        } else null

        val quickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (purchaseQuick != null) {
            val saleView = premiumQuickActionCard(saleQuick).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 9, 0) }
            }
            val purchaseView = premiumQuickActionCard(purchaseQuick).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(9, 0, 0, 0) }
            }
            quickRow.addView(saleView)
            quickRow.addView(purchaseView)
        } else {
            val saleView = premiumQuickActionCard(saleQuick).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            quickRow.addView(saleView)
        }
        body.addView(quickRow)
        body.addView(spacer(30))

        body.addView(TextView(this).apply {
            text = "MORE"
            textSize = 12.5f
            setTextColor(Color.parseColor(textMuted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(4, 0, 0, 14)
        })

        // ================= MENU GRID (role-based, premium tiles) — Sale & Purchase removed, now above =================
        val itemSearchTile = Tile("🔎", "Item Rate\nSearch", "#0F9B8E", "#E0F5F2") { startActivity(Intent(this@MainActivity, ItemSearchActivity::class.java)) }

        val partiesTile = Tile("👥", "Customers &\nSuppliers", "#4E342E", "#EFEBE9") { startActivity(Intent(this@MainActivity, PartyDashboardActivity::class.java)) }

        val tiles: List<Tile> = when (role) {
            "admin" -> listOf(
                Tile("📦", "Products", "#1565C0", "#E3F2FD") { startActivity(Intent(this@MainActivity, ProductActivity::class.java)) },
                Tile("📊", "Reports", "#6A1B9A", "#F3E5F5") { startActivity(Intent(this@MainActivity, ReportsActivity::class.java)) },
                Tile("💵", "Cash In/Out", "#00838F", "#E0F7FA") { startActivity(Intent(this@MainActivity, CashActivity::class.java)) },
                partiesTile,
                itemSearchTile
            )
            "manager" -> listOf(
                Tile("📊", "Reports", "#6A1B9A", "#F3E5F5") { startActivity(Intent(this@MainActivity, ReportsActivity::class.java)) },
                itemSearchTile,
                Tile("🚪", "Logout", "#C62828", "#FFEBEE") { doLogout() }
            )
            else -> listOf(
                Tile("💵", "Cash In/Out", "#00838F", "#E0F7FA") { startActivity(Intent(this@MainActivity, CashActivity::class.java)) },
                partiesTile,
                itemSearchTile,
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
        loadShopName()
        loadPartySummary()
    }

    override fun onResume() {
        super.onResume()
        loadDashboard()
        loadShopName()
        loadPartySummary()
    }

    // ================= CUSTOMERS & SUPPLIERS SECTION =================
    private fun buildCustomerSupplierSection(): LinearLayout {
        val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 0, 0, 14)
        }
        titleRow.addView(TextView(this).apply {
            text = "CUSTOMERS & SUPPLIERS"
            textSize = 12.5f
            setTextColor(Color.parseColor(textMuted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(this).apply {
            text = "View All ›"
            textSize = 12f
            setTextColor(Color.parseColor("#3949AB"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setOnClickListener { startActivity(Intent(this@MainActivity, PartyDashboardActivity::class.java)) }
        })
        section.addView(titleRow)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val getCardParts = premiumStatCard("↓", "You'll Get", partyGreen, "#E8F5E9")
        val giveCardParts = premiumStatCard("↑", "You'll Give", partyRed, "#FFEBEE")
        youllGetValue = getCardParts.second
        youllGiveValue = giveCardParts.second

        val getCardView = getCardParts.first.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 9, 0) }
            isClickable = true
            setOnClickListener { startActivity(Intent(this@MainActivity, PartyDashboardActivity::class.java)) }
        }
        val giveCardView = giveCardParts.first.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(9, 0, 0, 0) }
            isClickable = true
            setOnClickListener { startActivity(Intent(this@MainActivity, PartyDashboardActivity::class.java)) }
        }

        row.addView(getCardView)
        row.addView(giveCardView)
        section.addView(row)

        return section
    }

    /**
     * Mirrors PartyDashboardActivity's own You'll Get / You'll Give calculation, so both
     * screens always agree.
     *
     * ---- FIX ----
     * Customers and suppliers are NOT the same kind of balance:
     *   - Customer closing > 0  => customer owes the shop (receivable)  -> You'll Get
     *   - Customer closing < 0  => shop owes the customer (rare, e.g. refund due) -> You'll Give
     *   - Supplier closing > 0  => shop owes the supplier (payable)     -> You'll Give
     *   - Supplier closing < 0  => supplier owes the shop (e.g. return credit)    -> You'll Get
     * Previously both were combined into one list and split purely by sign, which mixed
     * receivables and payables together incorrectly.
     */
    private fun loadPartySummary() {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@MainActivity)
            combine(db.customerDao().all(), db.supplierDao().all()) { customers, suppliers ->
                Pair(customers, suppliers)
            }.collectLatest { (customers, suppliers) ->
                val customerClosings = customers.map { it.openingBalance + it.balance }
                val supplierClosings = suppliers.map { it.openingBalance + it.balance }

                val getFromCustomers = customerClosings.filter { it > 0 }.sumOf { it }
                val giveFromCustomers = customerClosings.filter { it < 0 }.sumOf { -it }

                val giveFromSuppliers = supplierClosings.filter { it > 0 }.sumOf { it }
                val getFromSuppliers = supplierClosings.filter { it < 0 }.sumOf { -it }

                val youllGet = getFromCustomers + getFromSuppliers
                val youllGive = giveFromCustomers + giveFromSuppliers

                youllGetValue.text = "Rs %.2f".format(youllGet)
                youllGiveValue.text = "Rs %.2f".format(youllGive)
            }
        }
    }

    private fun loadShopName() {
        lifecycleScope.launch {
            val savedName = PosDatabase.get(this@MainActivity).appSettingDao().get("shop_name")?.value
            if (!savedName.isNullOrBlank()) shopNameHeader.text = savedName
        }
    }

    private fun doLogout() {
        getSharedPreferences("session", MODE_PRIVATE).edit().clear().apply()
        // NOTE: a plain finish() here only removes MainActivity itself. If the user had
        // navigated deeper (e.g. MainActivity -> SaleActivity -> back -> MainActivity), or if
        // some other screen is still sitting under this one in the task, that screen would stay
        // reachable via Back after logging out. FLAG_ACTIVITY_NEW_TASK + FLAG_ACTIVITY_CLEAR_TASK
        // wipes the whole task so LoginActivity is the only thing left in the back stack.
        val intent = Intent(this@MainActivity, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private data class Tile(val emoji: String, val label: String, val accentHex: String, val tintHex: String, val onClick: () -> Unit)
    private data class QuickAction(val emoji: String, val title: String, val subtitle: String, val accentHex: String, val tintHex: String, val onClick: () -> Unit)

    // ---- Big "hero" style card for Sale / Purchase ----
    private fun premiumQuickActionCard(action: QuickAction): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(20, 30, 20, 26)
            elevation = 10f

            val ripple = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.parseColor(action.accentHex)).withAlpha(50),
                roundedGradientCard(action.accentHex, action.tintHex, 26),
                roundedGradientCard(action.accentHex, action.tintHex, 26)
            )
            background = ripple
            isClickable = true

            addView(FrameLayout(this@MainActivity).apply {
                val size = (66 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
                elevation = 10f
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
                addView(TextView(this@MainActivity).apply {
                    text = action.emoji
                    textSize = 28f
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                    )
                })
            })

            addView(TextView(this@MainActivity).apply {
                text = action.title
                textSize = 18f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 16, 0, 4)
            })

            addView(TextView(this@MainActivity).apply {
                text = action.subtitle
                textSize = 11.5f
                setTextColor(Color.parseColor("#F0F0F0"))
                gravity = Gravity.CENTER
            })

            setOnClickListener { action.onClick() }
        }
    }

    private fun roundedGradientCard(accentHex: String, tintHex: String, cornerRadius: Int): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor(accentHex), darken(accentHex, 0.22f))
        ).apply {
            this.cornerRadius = cornerRadius.toFloat()
        }
    }

    private fun premiumMenuTile(tile: Tile): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(18, 26, 18, 22)
            elevation = 6f

            val ripple = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.parseColor(tile.accentHex)).withAlpha(40),
                roundedBackgroundBordered(cardWhite, 24),
                roundedBackgroundBordered(cardWhite, 24)
            )
            background = ripple
            isClickable = true

            addView(FrameLayout(this@MainActivity).apply {
                val size = (58 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
                elevation = 8f
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    colors = intArrayOf(
                        lighten(tile.accentHex, 0.85f),
                        Color.parseColor(tile.tintHex)
                    )
                    gradientType = GradientDrawable.LINEAR_GRADIENT
                    orientation = GradientDrawable.Orientation.TL_BR
                }
                addView(TextView(this@MainActivity).apply {
                    text = tile.emoji
                    textSize = 24f
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
                setPadding(0, 16, 0, 0)
            })

            addView(View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams((28 * resources.displayMetrics.density).toInt(), 5).apply {
                    topMargin = 10
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                background = GradientDrawable().apply {
                    cornerRadius = 6f
                    setColor(Color.parseColor(tile.accentHex))
                }
            })

            setOnClickListener { tile.onClick() }
        }
    }

    private fun premiumStatCard(emoji: String, label: String, accentHex: String, tintHex: String): Pair<LinearLayout, TextView> {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 22, 24, 22)
            background = roundedBackgroundBordered(cardWhite, 24)
            elevation = 6f
        }
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        topRow.addView(FrameLayout(this).apply {
            val size = (42 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            elevation = 6f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(lighten(accentHex, 0.85f), Color.parseColor(tintHex))
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
            }
            addView(TextView(this@MainActivity).apply {
                text = emoji
                textSize = 18f
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

    private fun roundedBackgroundBordered(colorHex: String, cornerRadius: Int, strokeColorHex: String = "#EDEFF5"): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(colorHex))
            this.cornerRadius = cornerRadius.toFloat()
            setStroke(2, Color.parseColor(strokeColorHex))
        }
    }

    private fun lighten(hex: String, factor: Float): Int {
        val base = Color.parseColor(hex)
        val r = (Color.red(base) + (255 - Color.red(base)) * factor).toInt()
        val g = (Color.green(base) + (255 - Color.green(base)) * factor).toInt()
        val b = (Color.blue(base) + (255 - Color.blue(base)) * factor).toInt()
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    private fun darken(hex: String, factor: Float): Int {
        val base = Color.parseColor(hex)
        val r = (Color.red(base) * (1 - factor)).toInt()
        val g = (Color.green(base) * (1 - factor)).toInt()
        val b = (Color.blue(base) * (1 - factor)).toInt()
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
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
