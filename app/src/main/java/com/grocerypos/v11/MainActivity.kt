package com.grocerypos.v11

import android.app.AlertDialog
import android.content.Context
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
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var todaySaleValue: TextView
    private var todayProfitValue: TextView? = null
    private var role: String = "cashier"
    private lateinit var shopNameHeader: TextView

    private lateinit var youllGetValue: TextView
    private lateinit var youllGiveValue: TextView

    private val bgColor = "#F0F1F8"
    private val cardWhite = "#FFFFFF"
    private val textDark = "#151726"
    private val textMuted = "#8A8FA3"
    private val partyGreen = "#1B8A4A"
    private val partyRed = "#D32F4A"
    private val headerStart = "#0F1450"
    private val headerMid = "#2A2E8F"
    private val headerEnd = "#4B4FCF"
    private val goldAccent = "#F2C94C"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val session = getSharedPreferences("session", MODE_PRIVATE)
        if (session.getString("username", null) == null) {
            startActivity(Intent(this@MainActivity, LoginActivity::class.java))
            finish()
            return
        }
        role = session.getString("role", "cashier") ?: "cashier"

        com.grocerypos.v11.util.CrashHandler.install(this)
        com.grocerypos.v11.util.CrashHandler.getLastCrash(this)?.let { crashText -> showCrashDialog(crashText) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(bgColor))
        }

        // ================= HEADER =================
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26, 56, 26, 46)
            elevation = 14f
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(headerStart), Color.parseColor(headerMid), Color.parseColor(headerEnd))
            ).apply {
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, 44f, 44f, 44f, 44f)
            }
        }

        // ---- top row: gear (left) ... premium logo + small admin-panel tag (right) ----
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        topRow.addView(FrameLayout(this).apply {
            val size = (44 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            elevation = 8f
            val chipBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(Color.parseColor("#6B74E0"), Color.parseColor("#3D42B0"))
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
                setStroke((1.2 * resources.displayMetrics.density).toInt(), Color.parseColor(goldAccent))
            }
            isClickable = true
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.WHITE).withAlpha(70),
                chipBg, null
            )
            addView(TextView(this@MainActivity).apply {
                text = "⚙️"
                textSize = 19f
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            })
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })

        topRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })

        topRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(premiumLogoBadge(46))
            addView(TextView(this@MainActivity).apply {
                text = role.replaceFirstChar { it.uppercase() } + " Panel"
                textSize = 9.5f
                setTextColor(Color.parseColor("#C7CAF5"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 6, 0, 0)
            })
        })

        header.addView(topRow)

        // ---- shop name: big, centered ----
        shopNameHeader = TextView(this).apply {
            text = "IBTISAAM Kiryana Store"
            textSize = 25f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(8, 22, 8, 0)
            setShadowLayer(8f, 0f, 3f, Color.parseColor("#40000000"))
        }
        header.addView(shopNameHeader)

        header.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams((46 * resources.displayMetrics.density).toInt(), (3 * resources.displayMetrics.density).toInt()).apply {
                topMargin = (12 * resources.displayMetrics.density).toInt()
                gravity = Gravity.CENTER_HORIZONTAL
            }
            background = GradientDrawable().apply {
                cornerRadius = 6f
                setColor(Color.parseColor(goldAccent))
            }
        })

        root.addView(header)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 26, 28, 40)
        }

        // ================= ITEM RATE SEARCH — moved to top =================
        body.addView(premiumSearchBar())
        body.addView(spacer(26))

        // ================= STAT CARDS =================
        val statsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val saleCardParts = premiumStatCard("💰", "Today's Sale", "#1B8A4A", "#E4F6EA")
        val saleCardView = saleCardParts.first
        todaySaleValue = saleCardParts.second

        if (role == "admin") {
            val profitCardParts = premiumStatCard("📈", "Today's Profit", "#1257C4", "#E4EDFC")
            val profitCardView = profitCardParts.first
            todayProfitValue = profitCardParts.second

            saleCardView.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 9, 0) }
            profitCardView.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(9, 0, 0, 0) }

            statsRow.addView(saleCardView)
            statsRow.addView(profitCardView)
        } else {
            saleCardView.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            statsRow.addView(saleCardView)
        }

        body.addView(statsRow)
        body.addView(spacer(30))

        // ================= QUICK ACTIONS: SALE + PURCHASE — swipeable floating carousel =================
        body.addView(sectionLabel("QUICK ACTIONS  •  swipe →"))
        body.addView(buildSwipeableQuickActions())
        body.addView(spacer(30))

        // ================= CUSTOMERS & SUPPLIERS =================
        body.addView(buildCustomerSupplierSection())
        body.addView(spacer(30))

        body.addView(sectionLabel("MORE"))

        val partiesTile = Tile("👥", "Customers &\nSuppliers", "#4E342E", "#EFEBE9") { startActivity(Intent(this@MainActivity, PartyDashboardActivity::class.java)) }

        val tiles: List<Tile> = when (role) {
            "admin" -> listOf(
                Tile("📦", "Products", "#1257C4", "#E4EDFC") { startActivity(Intent(this@MainActivity, ProductActivity::class.java)) },
                Tile("📊", "Reports", "#7B1FA2", "#F3E5F9") { startActivity(Intent(this@MainActivity, ReportsActivity::class.java)) },
                Tile("💵", "Cash In/Out", "#00838F", "#DFF6F8") { startActivity(Intent(this@MainActivity, CashActivity::class.java)) },
                partiesTile
            )
            "manager" -> listOf(
                Tile("📊", "Reports", "#7B1FA2", "#F3E5F9") { startActivity(Intent(this@MainActivity, ReportsActivity::class.java)) },
                partiesTile,
                Tile("🚪", "Logout", "#D32F4A", "#FCE6EA") { doLogout() }
            )
            else -> listOf(
                Tile("💵", "Cash In/Out", "#00838F", "#DFF6F8") { startActivity(Intent(this@MainActivity, CashActivity::class.java)) },
                partiesTile,
                Tile("🚪", "Logout", "#D32F4A", "#FCE6EA") { doLogout() }
            )
        }

        tiles.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { idx, tile ->
                val tileView = premiumMenuTile(tile)
                tileView.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
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

    private fun showCrashDialog(crashText: String) {
        AlertDialog.Builder(this)
            .setTitle("Pichli baar app crash hui thi")
            .setMessage(if (crashText.length > 3000) crashText.take(3000) + "\n\n…(truncated, use Share for full text)" else crashText)
            .setPositiveButton("Share") { _, _ ->
                startActivity(Intent.createChooser(com.grocerypos.v11.util.CrashHandler.shareIntent(crashText), "Share crash log"))
                com.grocerypos.v11.util.CrashHandler.clearLastCrash(this)
            }
            .setNeutralButton("Copy") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("crash", crashText))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Dismiss") { _, _ -> com.grocerypos.v11.util.CrashHandler.clearLastCrash(this) }
            .setCancelable(false)
            .show()
    }

    // ---- premium circular logo badge: gold double-ring + navy-gold gradient fill ----
    private fun premiumLogoBadge(sizeDp: Int): FrameLayout {
        val size = (sizeDp * resources.displayMetrics.density).toInt()
        val outer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            elevation = 10f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(Color.parseColor(goldAccent), Color.parseColor("#B9892C"))
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
            }
        }
        val innerSize = ((sizeDp - 5) * resources.displayMetrics.density).toInt()
        outer.addView(FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(innerSize, innerSize, Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(Color.parseColor(headerEnd), Color.parseColor(headerStart))
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
            }
            addView(TextView(this@MainActivity).apply {
                text = "IK"
                textSize = 15f
                setTextColor(Color.parseColor(goldAccent))
                setTypeface(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            })
        })
        return outer
    }

    // ---- premium search bar, tap opens Item Rate Search ----
    private fun premiumSearchBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22, 20, 22, 20)
            elevation = 8f
            val bg = roundedBackgroundBordered(cardWhite, 30)
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.parseColor(headerEnd)).withAlpha(30),
                bg, bg
            )
            isClickable = true

            addView(FrameLayout(this@MainActivity).apply {
                val size = (36 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(0, 0, 14, 0) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    colors = intArrayOf(lighten("#0F9B8E", 0.85f), Color.parseColor("#DEF6F2"))
                    gradientType = GradientDrawable.LINEAR_GRADIENT
                    orientation = GradientDrawable.Orientation.TL_BR
                }
                addView(TextView(this@MainActivity).apply {
                    text = "🔎"
                    textSize = 16f
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                })
            })

            addView(TextView(this@MainActivity).apply {
                text = "Search Item Rate…"
                textSize = 14.5f
                setTextColor(Color.parseColor(textMuted))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(TextView(this@MainActivity).apply {
                text = "›"
                textSize = 20f
                setTextColor(Color.parseColor(headerEnd))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })

            setOnClickListener { startActivity(Intent(this@MainActivity, ItemSearchActivity::class.java)) }
        }
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(Color.parseColor(textMuted))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        letterSpacing = 0.06f
        setPadding(4, 0, 0, 14)
    }

    // ---- swipeable carousel: HorizontalScrollView holding two floating hero cards ----
    private fun buildSwipeableQuickActions(): HorizontalScrollView {
        val cardWidth = (resources.displayMetrics.widthPixels * 0.72f).toInt()

        val saleQuick = QuickAction("🛒", "Sale", "Start a new sale", "#00B4D8", "#0077B6")
        { startActivity(Intent(this@MainActivity, SaleActivity::class.java)) }
        val purchaseQuick: QuickAction? = if (role == "admin" || role == "manager") {
            QuickAction("🧾", "Purchase", "Record a purchase", "#D6408F", "#8E2A6C")
            { startActivity(Intent(this@MainActivity, PurchaseActivity::class.java)) }
        } else null

        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4, 24, 4, 10)
            clipToPadding = false
            clipChildren = false
        }

        track.addView(floatingQuickActionCard(saleQuick, cardWidth).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.marginEnd = (14 * resources.displayMetrics.density).toInt()
        })
        if (purchaseQuick != null) {
            track.addView(floatingQuickActionCard(purchaseQuick, cardWidth))
        }

        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            clipChildren = false
            addView(track)
        }
    }

    // ---- single floating card: icon badge overlaps the top edge for a "floating" effect ----
    private fun floatingQuickActionCard(action: QuickAction, widthPx: Int): FrameLayout {
        val iconSize = (64 * resources.displayMetrics.density).toInt()
        val topOverlap = iconSize / 2

        val outer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = topOverlap
            }
            setPadding(20, (topOverlap / resources.displayMetrics.density).toInt() + 22, 20, 26)
            elevation = 16f

            val base = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(action.accentHex), Color.parseColor(action.accentHex2))
            ).apply { cornerRadius = 32f }

            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.WHITE).withAlpha(60),
                base, base
            )
            isClickable = true

            addView(TextView(this@MainActivity).apply {
                text = action.title
                textSize = 18f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 8, 0, 3)
            })
            addView(TextView(this@MainActivity).apply {
                text = action.subtitle
                textSize = 11.5f
                setTextColor(Color.parseColor("#F5F5F5"))
                gravity = Gravity.CENTER
            })

            setOnClickListener { action.onClick() }
        }
        outer.addView(card)

        // floating icon badge, elevated above the card, overlapping its top edge
        outer.addView(FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER_HORIZONTAL or Gravity.TOP)
            elevation = 22f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(Color.WHITE, Color.parseColor("#F4F4FF"))
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
            }
            addView(TextView(this@MainActivity).apply {
                text = action.emoji
                textSize = 28f
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            })
        })

        return outer
    }

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
            letterSpacing = 0.06f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(this).apply {
            text = "View All ›"
            textSize = 12.5f
            setTextColor(Color.parseColor(headerEnd))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setOnClickListener { startActivity(Intent(this@MainActivity, PartyDashboardActivity::class.java)) }
        })
        section.addView(titleRow)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val getCardParts = premiumStatCard("↓", "You'll Get", partyGreen, "#E4F6EA")
        val giveCardParts = premiumStatCard("↑", "You'll Give", partyRed, "#FCE6EA")
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
        val intent = Intent(this@MainActivity, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private data class Tile(val emoji: String, val label: String, val accentHex: String, val tintHex: String, val onClick: () -> Unit)
    private data class QuickAction(val emoji: String, val title: String, val subtitle: String, val accentHex: String, val accentHex2: String, val onClick: () -> Unit)

    private fun premiumMenuTile(tile: Tile): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(18, 26, 18, 22)
            elevation = 7f

            val ripple = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.parseColor(tile.accentHex)).withAlpha(40),
                roundedBackgroundBordered(cardWhite, 26),
                roundedBackgroundBordered(cardWhite, 26)
            )
            background = ripple
            isClickable = true

            addView(FrameLayout(this@MainActivity).apply {
                val size = (58 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
                elevation = 9f
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    colors = intArrayOf(lighten(tile.accentHex, 0.85f), Color.parseColor(tile.tintHex))
                    gradientType = GradientDrawable.LINEAR_GRADIENT
                    orientation = GradientDrawable.Orientation.TL_BR
                }
                addView(TextView(this@MainActivity).apply {
                    text = tile.emoji
                    textSize = 24f
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
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
            background = roundedBackgroundBordered(cardWhite, 26)
            elevation = 7f
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
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
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
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 12, 0, 0)
        }
        card.addView(valueText)
        return Pair(card, valueText)
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

    private fun roundedBackgroundBordered(colorHex: String, cornerRadius: Int, strokeColorHex: String = "#ECEEF6"): GradientDrawable {
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

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
