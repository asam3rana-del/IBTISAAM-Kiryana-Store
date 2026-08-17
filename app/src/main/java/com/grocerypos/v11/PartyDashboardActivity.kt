package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.Customer
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Supplier
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home-screen style dashboard: "You'll Get / You'll Give" summary cards + Parties /
 * Transactions / Items tabs + searchable party list + bottom Add Purchase / Add Sale bar.
 *
 * This is now the app's HOME screen (opened right after login). It reuses PartyActivity
 * for the actual add/edit/delete forms and full history, so nothing is duplicated. The
 * hamburger (☰) icon in the header opens a menu with the rest of the app's features
 * (Products, Reports, Cash In/Out, Item Rate Search, Settings, Logout) since this screen
 * no longer has a "back to MainActivity" to fall back on.
 *
 * NOTE: Add this to AndroidManifest.xml under <application> before running it:
 *   <activity android:name=".ui.PartyDashboardActivity" />
 *
 * Sign convention (matches PartyActivity.partyRow exactly):
 *   closing = opening + running
 *   closing > 0  -> "You'll Give" (red)   -> counted in the You'll Give total
 *   closing <= 0 -> "You'll Get"  (green) -> counted in the You'll Get total
 */
class PartyDashboardActivity : AppCompatActivity() {

    // ---- palette (kept consistent with PartyActivity.kt) ----
    private val bg = "#F3F4F9"
    private val gradientStart = "#7C86F5"
    private val gradientEnd = "#A6ADFF"
    private val blue = "#5B6EE8"
    private val orange = "#F5A15C"
    private val green = "#4CAF50"
    private val red = "#E57373"
    private val cardWhite = "#FFFFFF"
    private val cardBorder = "#EEF0F7"
    private val labelGray = "#9AA0B4"

    private lateinit var youllGetValue: TextView
    private lateinit var youllGiveValue: TextView
    private lateinit var tabRow: LinearLayout
    private lateinit var searchField: EditText
    private lateinit var listContainer: LinearLayout

    private var activeTab = Tab.PARTIES
    private var filterMode = FilterMode.ALL
    private var allItems: List<PartyItem> = emptyList()
    private var role: String = "cashier"

    private enum class Tab { PARTIES, TRANSACTIONS, ITEMS }
    private enum class FilterMode { ALL, CUSTOMERS, SUPPLIERS }

    /** Unified wrapper so customers + suppliers can share one list/adapter-ish rendering. */
    private data class PartyItem(
        val name: String,
        val phone: String,
        val closing: Double,
        val isCustomer: Boolean,
        val onTap: () -> Unit
    )

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val session = getSharedPreferences("session", MODE_PRIVATE)
        if (session.getString("username", null) == null) {
            startActivity(Intent(this@PartyDashboardActivity, LoginActivity::class.java))
            finish()
            return
        }
        role = session.getString("role", "cashier") ?: "cashier"

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(bg))
        }

        outer.addView(buildHeader())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 0)
        }

        root.addView(buildSummaryCards())
        root.addView(spacer(18))
        root.addView(buildTabs())
        root.addView(spacer(16))
        root.addView(buildSearchRow())
        root.addView(spacer(14))

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)
        root.addView(spacer(90)) // keep list clear of the floating bottom bar

        val scrollArea = ScrollView(this).apply {
            // This view's real parent is the FrameLayout "stack" below, so it needs
            // FrameLayout.LayoutParams here, not LinearLayout.LayoutParams - the previous
            // LinearLayout params (with weight) were silently ignored inside a FrameLayout,
            // which collapsed this ScrollView to zero height and hid all the middle content.
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            addView(root)
        }

        val stack = FrameLayout(this).apply {
            addView(scrollArea)
            addView(buildBottomBar())
            // "stack" itself sits inside "outer" (a LinearLayout), so it needs weight=1
            // here to actually fill the remaining space below the header.
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        outer.addView(stack)

        setContentView(outer)

        loadParties()
    }

    override fun onResume() {
        super.onResume()
        loadParties()
    }

    // ================= HEADER =================
    private fun buildHeader(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 46, 24, 32)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(gradientStart), Color.parseColor(gradientEnd))
            )

            addView(TextView(this@PartyDashboardActivity).apply {
                text = "\u2630"
                textSize = 20f
                setTextColor(Color.WHITE)
                setPadding(4, 0, 20, 0)
                setOnClickListener { showMainMenu() }
            })

            addView(TextView(this@PartyDashboardActivity).apply {
                text = Loc.t(this@PartyDashboardActivity, "Dashboard", "\u0688\u06CC\u0634 \u0628\u0648\u0631\u0688")
                textSize = 19f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(TextView(this@PartyDashboardActivity).apply {
                text = "\uD83D\uDD14"
                textSize = 18f
                setPadding(0, 0, 24, 0)
                setOnClickListener {
                    Toast.makeText(this@PartyDashboardActivity, Loc.t(this@PartyDashboardActivity, "No new notifications", "\u06A9\u0648\u0626\u06CC \u0646\u0626\u06CC \u0627\u0637\u0644\u0627\u0639 \u0646\u06C1\u06CC\u06BA"), Toast.LENGTH_SHORT).show()
                }
            })

            addView(TextView(this@PartyDashboardActivity).apply {
                text = "\u27A4"
                textSize = 18f
                setTextColor(Color.parseColor("#FF5252"))
                setOnClickListener { shareSummary() }
            })
        }
    }

    // ================= MAIN MENU (this screen is now HOME, so it hosts app-wide navigation) =================
    private fun showMainMenu() {
        val options = mutableListOf(
            Loc.t(this, "Products", "\u067E\u0631\u0648\u0688\u06A9\u0679\u0633"),
            Loc.t(this, "Reports", "\u0631\u067E\u0648\u0631\u0679\u0633"),
            Loc.t(this, "Cash In/Out", "\u06A9\u06CC\u0634 \u0627\u0646/\u0622\u0624\u0679"),
            Loc.t(this, "Item Rate Search", "\u0622\u0626\u0679\u0645 \u0631\u06CC\u0679 \u0633\u0631\u0686"),
            Loc.t(this, "Settings", "\u0633\u06CC\u0679\u0646\u06AF\u0632"),
            Loc.t(this, "Logout", "\u0644\u0627\u06AF \u0622\u0624\u0679")
        )
        AlertDialog.Builder(this)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, ProductActivity::class.java))
                    1 -> startActivity(Intent(this, ReportsActivity::class.java))
                    2 -> startActivity(Intent(this, CashActivity::class.java))
                    3 -> startActivity(Intent(this, ItemSearchActivity::class.java))
                    4 -> startActivity(Intent(this, SettingsActivity::class.java))
                    5 -> doLogout()
                }
            }
            .show()
    }

    private fun doLogout() {
        getSharedPreferences("session", MODE_PRIVATE).edit().clear().apply()
        // Wipe the whole task so nothing deeper in the back stack is reachable via Back
        // after logging out - same pattern MainActivity used to use.
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun shareSummary() {
        val text = Loc.t(
            this,
            "You'll Get: Rs %.2f\nYou'll Give: Rs %.2f".format(
                parseAmount(youllGetValue.text), parseAmount(youllGiveValue.text)
            ),
            "\u0622\u067E \u06A9\u0648 \u0645\u0644\u06CC\u06BA \u06AF\u06D2: \u0631\u0648\u067E\u06D2 %.2f\n\u0622\u067E \u06A9\u0648 \u062F\u06CC\u0646\u06D2 \u06C1\u06CC\u06BA: \u0631\u0648\u067E\u06D2 %.2f".format(
                parseAmount(youllGetValue.text), parseAmount(youllGiveValue.text)
            )
        )
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }, Loc.t(this, "Share summary", "\u062E\u0644\u0627\u0635\u06C1 \u0634\u06CC\u0626\u0631 \u06A9\u0631\u06CC\u06BA")))
    }

    private fun parseAmount(t: CharSequence): Double =
        t.toString().replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0

    // ================= SUMMARY CARDS =================
    private fun buildSummaryCards(): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val getCard = summaryCard("\u2193", Loc.t(this, "You'll Get", "\u0622\u067E \u06A9\u0648 \u0645\u0644\u06CC\u06BA \u06AF\u06D2"), green)
        val giveCard = summaryCard("\u2191", Loc.t(this, "You'll Give", "\u0622\u067E \u06A9\u0648 \u062F\u06CC\u0646\u06D2 \u06C1\u06CC\u06BA"), red)
        youllGetValue = getCard.second
        youllGiveValue = giveCard.second

        getCard.first.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 8, 0) }
        giveCard.first.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 0, 0) }

        row.addView(getCard.first)
        row.addView(giveCard.first)
        return row
    }

    private fun summaryCard(arrow: String, label: String, accentHex: String): Pair<LinearLayout, TextView> {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 18, 20, 18)
            background = elevatedCardBg()
            elevation = 3f
        }
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        topRow.addView(TextView(this).apply {
            text = arrow
            setTextColor(Color.parseColor(accentHex))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        topRow.addView(TextView(this).apply {
            text = "  $label"
            setTextColor(Color.parseColor(labelGray))
            textSize = 12.5f
        })
        card.addView(topRow)
        val value = TextView(this).apply {
            text = "Rs 0"
            textSize = 19f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#2E3242"))
            setPadding(0, 8, 0, 0)
        }
        card.addView(value)
        return Pair(card, value)
    }

    // ================= TABS =================
    private fun buildTabs(): LinearLayout {
        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        renderTabs()
        return tabRow
    }

    private fun renderTabs() {
        tabRow.removeAllViews()
        val entries = listOf(
            Triple(Tab.PARTIES, Loc.t(this, "Parties", "\u067E\u0627\u0631\u0679\u06CC\u0632"), true),
            Triple(Tab.TRANSACTIONS, Loc.t(this, "Transactions", "\u0644\u06CC\u0646 \u062F\u06CC\u0646"), true),
            Triple(Tab.ITEMS, Loc.t(this, "Items", "\u0622\u0626\u0679\u0645\u0632"), true)
        )
        entries.forEachIndexed { idx, (tab, label, _) ->
            val isActive = tab == activeTab
            tabRow.addView(TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, 18, 0, 18)
                setTypeface(typeface, if (isActive) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setTextColor(Color.parseColor(if (isActive) red else labelGray))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(cardWhite))
                    cornerRadius = 22f
                    if (isActive) setStroke(3, Color.parseColor(red)) else setStroke(2, Color.parseColor(cardBorder))
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    val m = 6
                    setMargins(if (idx == 0) 0 else m, 0, if (idx == entries.lastIndex) 0 else m, 0)
                }
                setOnClickListener {
                    activeTab = tab
                    renderTabs()
                    renderActiveTabBody()
                }
            })
        }
    }

    // ================= SEARCH + NEW PARTY =================
    private fun buildSearchRow(): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 8, 18, 8)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(cardWhite))
                cornerRadius = 24f
                setStroke(2, Color.parseColor(cardBorder))
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        searchBox.addView(TextView(this).apply { text = "\uD83D\uDD0D  "; textSize = 14f; setTextColor(Color.parseColor(blue)) })
        searchField = EditText(this).apply {
            hint = Loc.t(this@PartyDashboardActivity, "Search party", "\u067E\u0627\u0631\u0679\u06CC \u062A\u0644\u0627\u0634 \u06A9\u0631\u06CC\u06BA")
            background = null
            textSize = 13.5f
            setHintTextColor(Color.parseColor(labelGray))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { renderPartyList() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        searchBox.addView(searchField)
        row.addView(searchBox)
        row.addView(spacer(10).apply { layoutParams = LinearLayout.LayoutParams((10 * resources.displayMetrics.density).toInt(), 1) })

        row.addView(TextView(this).apply {
            text = "\u2630"
            textSize = 15f
            setTextColor(Color.parseColor(labelGray))
            gravity = Gravity.CENTER
            background = ovalBg(cardWhite, strokeHex = cardBorder)
            width = (40 * resources.displayMetrics.density).toInt()
            height = (40 * resources.displayMetrics.density).toInt()
            setOnClickListener { showFilterDialog() }
        })
        row.addView(spacer(10).apply { layoutParams = LinearLayout.LayoutParams((10 * resources.displayMetrics.density).toInt(), 1) })

        row.addView(TextView(this).apply {
            text = "+ " + Loc.t(this@PartyDashboardActivity, "New Party", "\u0646\u0626\u06CC \u067E\u0627\u0631\u0679\u06CC")
            textSize = 13f
            setTextColor(Color.parseColor(blue))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(20, 14, 20, 14)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E9EBFF"))
                cornerRadius = 22f
            }
            setOnClickListener { startActivity(Intent(this@PartyDashboardActivity, PartyActivity::class.java)) }
        })

        return row
    }

    private fun showFilterDialog() {
        val options = arrayOf(
            Loc.t(this, "All Parties", "\u062A\u0645\u0627\u0645 \u067E\u0627\u0631\u0679\u06CC\u0632"),
            Loc.t(this, "Customers Only", "\u0635\u0631\u0641 \u06A9\u0633\u0679\u0645\u0631\u0632"),
            Loc.t(this, "Suppliers Only", "\u0635\u0631\u0641 \u0633\u067E\u0644\u0627\u0626\u0631\u0632")
        )
        val current = when (filterMode) { FilterMode.ALL -> 0; FilterMode.CUSTOMERS -> 1; FilterMode.SUPPLIERS -> 2 }
        AlertDialog.Builder(this)
            .setTitle(Loc.t(this, "Filter", "\u0641\u0644\u0679\u0631"))
            .setSingleChoiceItems(options, current) { d, which ->
                filterMode = when (which) { 1 -> FilterMode.CUSTOMERS; 2 -> FilterMode.SUPPLIERS; else -> FilterMode.ALL }
                renderPartyList()
                d.dismiss()
            }
            .show()
    }

    // ================= BOTTOM ACTION BAR =================
    private fun buildBottomBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 16, 24, 16)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(cardWhite))
                cornerRadii = floatArrayOf(28f, 28f, 28f, 28f, 0f, 0f, 0f, 0f)
            }
            elevation = 10f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
            )
        }

        // Keep the bar clear of the phone's gesture/navigation bar on edge-to-edge devices -
        // without this, the last ~16-48px of the bar renders underneath the system nav bar.
        ViewCompat.setOnApplyWindowInsetsListener(bar) { view, insets ->
            val navBarInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            view.setPadding(view.paddingLeft, 16, view.paddingRight, 16 + navBarInset)
            insets
        }

        bar.addView(TextView(this).apply {
            text = Loc.t(this@PartyDashboardActivity, "Add Purchase", "\u062E\u0631\u06CC\u062F\u0627\u0631\u06CC \u0634\u0627\u0645\u0644 \u06A9\u0631\u06CC\u06BA")
            setTextColor(Color.WHITE)
            textSize = 13.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 22, 0, 22)
            background = roundedBackground(blue, 26)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 10, 0) }
            setOnClickListener { startActivity(Intent(this@PartyDashboardActivity, PurchaseActivity::class.java)) }
        })

        bar.addView(TextView(this).apply {
            text = "+"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = ovalBg(blue)
            width = (52 * resources.displayMetrics.density).toInt()
            height = (52 * resources.displayMetrics.density).toInt()
            setOnClickListener { showQuickAddDialog() }
        })

        bar.addView(TextView(this).apply {
            text = Loc.t(this@PartyDashboardActivity, "Add Sale", "\u0633\u06CC\u0644 \u0634\u0627\u0645\u0644 \u06A9\u0631\u06CC\u06BA")
            setTextColor(Color.WHITE)
            textSize = 13.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 22, 0, 22)
            background = roundedBackground(red, 26)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(10, 0, 0, 0) }
            setOnClickListener { startActivity(Intent(this@PartyDashboardActivity, SaleActivity::class.java)) }
        })

        return bar
    }

    private fun showQuickAddDialog() {
        val options = arrayOf(
            Loc.t(this, "Add Sale", "\u0633\u06CC\u0644 \u0634\u0627\u0645\u0644 \u06A9\u0631\u06CC\u06BA"),
            Loc.t(this, "Add Purchase", "\u062E\u0631\u06CC\u062F\u0627\u0631\u06CC \u0634\u0627\u0645\u0644 \u06A9\u0631\u06CC\u06BA"),
            Loc.t(this, "New Party", "\u0646\u0626\u06CC \u067E\u0627\u0631\u0679\u06CC")
        )
        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, SaleActivity::class.java))
                    1 -> startActivity(Intent(this, PurchaseActivity::class.java))
                    2 -> startActivity(Intent(this, PartyActivity::class.java))
                }
            }
            .show()
    }

    // ================= DATA LOAD (Parties tab / summary cards) =================
    private fun loadParties() {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PartyDashboardActivity)
            combine(db.customerDao().all(), db.supplierDao().all()) { customers, suppliers ->
                Pair(customers, suppliers)
            }.collectLatest { (customers, suppliers) ->
                val items = mutableListOf<PartyItem>()
                for (c in customers) {
                    items.add(
                        PartyItem(
                            name = c.name,
                            phone = c.phone,
                            closing = c.openingBalance + c.balance,
                            isCustomer = true,
                            onTap = { startActivity(Intent(this@PartyDashboardActivity, PartyActivity::class.java)) }
                        )
                    )
                }
                for (s in suppliers) {
                    items.add(
                        PartyItem(
                            name = s.name,
                            phone = s.phone,
                            closing = s.openingBalance + s.balance,
                            isCustomer = false,
                            onTap = { startActivity(Intent(this@PartyDashboardActivity, PartyActivity::class.java)) }
                        )
                    )
                }
                allItems = items.sortedBy { it.name.lowercase() }
                updateSummaryTotals()
                renderActiveTabBody()
            }
        }
    }

    private fun updateSummaryTotals() {
        val youllGet = allItems.filter { it.closing <= 0 }.sumOf { -it.closing }
        val youllGive = allItems.filter { it.closing > 0 }.sumOf { it.closing }
        youllGetValue.text = "Rs %.2f".format(youllGet)
        youllGiveValue.text = "Rs %.2f".format(youllGive)
    }

    private fun renderActiveTabBody() {
        listContainer.removeAllViews()
        when (activeTab) {
            Tab.PARTIES -> renderPartyList()
            Tab.TRANSACTIONS -> renderTransactionsList()
            Tab.ITEMS -> renderItemsList()
        }
    }

    private fun renderPartyList() {
        if (activeTab != Tab.PARTIES) return
        listContainer.removeAllViews()

        val query = searchField.text?.toString()?.trim()?.lowercase().orEmpty()
        val filtered = allItems
            .filter { item ->
                when (filterMode) {
                    FilterMode.ALL -> true
                    FilterMode.CUSTOMERS -> item.isCustomer
                    FilterMode.SUPPLIERS -> !item.isCustomer
                }
            }
            .filter { it.name.lowercase().contains(query) }

        if (filtered.isEmpty()) {
            listContainer.addView(placeholderCard(Loc.t(this, "No parties found", "\u06A9\u0648\u0626\u06CC \u067E\u0627\u0631\u0679\u06CC \u0646\u06C1\u06CC\u06BA \u0645\u0644\u06CC")))
            return
        }

        for (item in filtered) {
            listContainer.addView(dashboardPartyRow(item))
        }
    }

    private fun dashboardPartyRow(item: PartyItem): LinearLayout {
        val give = item.closing > 0
        val amountColor = if (give) red else green
        val label = if (give) Loc.t(this, "You'll Give", "\u0622\u067E \u06A9\u0648 \u062F\u06CC\u0646\u06D2 \u06C1\u06CC\u06BA") else Loc.t(this, "You'll Get", "\u0622\u067E \u06A9\u0648 \u0645\u0644\u06CC\u06BA \u06AF\u06D2")

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 16, 18, 16)
            background = elevatedCardBg()
            elevation = 2f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 10) }
            isClickable = true
            setOnClickListener { item.onTap() }

            val infoCol = LinearLayout(this@PartyDashboardActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            infoCol.addView(TextView(this@PartyDashboardActivity).apply {
                text = item.name
                textSize = 15f
                setTextColor(Color.parseColor("#2E3242"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            infoCol.addView(TextView(this@PartyDashboardActivity).apply {
                text = if (item.isCustomer) Loc.t(this@PartyDashboardActivity, "Customer", "\u06A9\u0633\u0679\u0645\u0631") else Loc.t(this@PartyDashboardActivity, "Supplier", "\u0633\u067E\u0644\u0627\u0626\u0631")
                textSize = 11.5f
                setTextColor(Color.parseColor(labelGray))
                setPadding(0, 4, 0, 0)
            })
            addView(infoCol)

            val amountCol = LinearLayout(this@PartyDashboardActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
            }
            amountCol.addView(TextView(this@PartyDashboardActivity).apply {
                text = "Rs %.2f".format(kotlin.math.abs(item.closing))
                textSize = 14.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(amountColor))
            })
            amountCol.addView(TextView(this@PartyDashboardActivity).apply {
                text = label
                textSize = 11f
                setTextColor(Color.parseColor(amountColor))
                setPadding(0, 2, 0, 0)
            })
            addView(amountCol)
        }
    }

    // ================= TRANSACTIONS TAB =================
    // Merges recent sales + purchases into one date-sorted feed so the user can see
    // everything that's happened across all parties without leaving the dashboard.
    private fun renderTransactionsList() {
        listContainer.removeAllViews()
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PartyDashboardActivity)
            val sales = db.saleDao().allSales()
            val purchases = db.purchaseDao().allPurchases()

            data class Row(val partyName: String, val amount: Double, val createdAt: Long, val isSale: Boolean, val status: String)
            val merged = mutableListOf<Row>()
            sales.forEach { merged.add(Row(it.customerName, it.total, it.createdAt, true, it.status)) }
            purchases.forEach { merged.add(Row(it.supplierName, it.total, it.createdAt, false, it.status)) }
            val sorted = merged.sortedByDescending { it.createdAt }.take(100)

            // Bail out quietly if the tab was switched away while this was loading.
            if (activeTab != Tab.TRANSACTIONS) return@launch

            if (sorted.isEmpty()) {
                listContainer.addView(placeholderCard(Loc.t(this@PartyDashboardActivity, "No transactions yet", "\u0627\u0628\u06BE\u06CC \u062A\u06A9 \u06A9\u0648\u0626\u06CC \u0644\u06CC\u0646 \u062F\u06CC\u0646 \u0646\u06C1\u06CC\u06BA \u06C1\u06D2")))
                return@launch
            }

            val fmt = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
            for (row in sorted) {
                listContainer.addView(transactionRow(row.partyName, row.amount, fmt.format(Date(row.createdAt)), row.isSale, row.status))
            }
        }
    }

    private fun transactionRow(partyName: String, amount: Double, dateText: String, isSale: Boolean, status: String): LinearLayout {
        val accent = if (isSale) green else orange
        val typeLabel = if (isSale) Loc.t(this, "Sale", "\u0633\u06CC\u0644") else Loc.t(this, "Purchase", "\u062E\u0631\u06CC\u062F\u0627\u0631\u06CC")

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 14, 18, 14)
            background = elevatedCardBg()
            elevation = 2f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 10) }

            addView(TextView(this@PartyDashboardActivity).apply {
                text = if (isSale) "\uD83D\uDED2" else "\uD83E\uDDFE"
                textSize = 16f
                gravity = Gravity.CENTER
                background = ovalBg(accent)
                width = (38 * resources.displayMetrics.density).toInt()
                height = (38 * resources.displayMetrics.density).toInt()
            })

            val infoCol = LinearLayout(this@PartyDashboardActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 0, 12, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            infoCol.addView(TextView(this@PartyDashboardActivity).apply {
                text = partyName
                textSize = 14.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#2E3242"))
            })
            infoCol.addView(TextView(this@PartyDashboardActivity).apply {
                text = "$typeLabel \u00B7 $dateText" + if (status == "returned") "  \u2022  " + Loc.t(this@PartyDashboardActivity, "Returned", "\u0648\u0627\u067E\u0633") else ""
                textSize = 11f
                setTextColor(Color.parseColor(if (status == "returned") red else labelGray))
            })
            addView(infoCol)

            addView(TextView(this@PartyDashboardActivity).apply {
                text = "Rs %.2f".format(amount)
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(accent))
            })
        }
    }

    // ================= ITEMS TAB =================
    // Combines all-time sold vs purchased totals per product so the user gets a quick
    // "what's moving" view without opening full Reports.
    private fun renderItemsList() {
        listContainer.removeAllViews()
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PartyDashboardActivity)
            val soldTotals = db.saleDao().allTimeItemTotals()
            val purchasedTotals = db.purchaseDao().allTimeItemTotals()

            data class Combined(var product: String, var soldQty: Int = 0, var soldAmt: Double = 0.0, var purQty: Int = 0, var purAmt: Double = 0.0)
            val map = LinkedHashMap<String, Combined>()
            soldTotals.forEach {
                val c = map.getOrPut(it.product) { Combined(it.product) }
                c.soldQty = it.totalQty; c.soldAmt = it.totalAmount
            }
            purchasedTotals.forEach {
                val c = map.getOrPut(it.product) { Combined(it.product) }
                c.purQty = it.totalQty; c.purAmt = it.totalAmount
            }
            val combined = map.values.sortedByDescending { it.soldAmt }

            if (activeTab != Tab.ITEMS) return@launch

            if (combined.isEmpty()) {
                listContainer.addView(placeholderCard(Loc.t(this@PartyDashboardActivity, "No items found", "\u06A9\u0648\u0626\u06CC \u0622\u0626\u0679\u0645 \u0646\u06C1\u06CC\u06BA \u0645\u0644\u0627")))
                return@launch
            }

            for (c in combined) {
                listContainer.addView(itemRow(c.product, c.soldQty, c.soldAmt, c.purQty, c.purAmt))
            }
        }
    }

    private fun itemRow(product: String, soldQty: Int, soldAmt: Double, purQty: Int, purAmt: Double): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 14, 18, 14)
            background = elevatedCardBg()
            elevation = 2f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 10) }

            addView(TextView(this@PartyDashboardActivity).apply {
                text = product
                textSize = 14.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#2E3242"))
            })

            val row = LinearLayout(this@PartyDashboardActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 0)
            }
            row.addView(TextView(this@PartyDashboardActivity).apply {
                text = Loc.t(this@PartyDashboardActivity, "Sold", "\u0641\u0631\u0648\u062E\u062A") + ": $soldQty \u00B7 Rs %.2f".format(soldAmt)
                textSize = 12f
                setTextColor(Color.parseColor(green))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this@PartyDashboardActivity).apply {
                text = Loc.t(this@PartyDashboardActivity, "Purchased", "\u062E\u0631\u06CC\u062F\u0627") + ": $purQty \u00B7 Rs %.2f".format(purAmt)
                textSize = 12f
                setTextColor(Color.parseColor(orange))
            })
            addView(row)
        }
    }

    private fun placeholderCard(text: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(20, 30, 20, 30)
        background = elevatedCardBg()
        addView(TextView(this@PartyDashboardActivity).apply {
            this.text = text
            setTextColor(Color.parseColor(labelGray))
            textSize = 13f
            gravity = Gravity.CENTER
        })
    }

    // ================= UI helpers =================
    private fun elevatedCardBg() = GradientDrawable().apply {
        setColor(Color.parseColor(cardWhite))
        cornerRadius = 16f
        setStroke(1, Color.parseColor(cardBorder))
    }

    private fun ovalBg(colorHex: String, strokeHex: String? = null) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(colorHex))
        if (strokeHex != null) setStroke(2, Color.parseColor(strokeHex))
    }

    private fun roundedBackground(colorHex: String, cornerRadius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(colorHex))
        this.cornerRadius = cornerRadius.toFloat()
    }

    private fun spacer(heightDp: Int) = View(this).apply {
        val px = (heightDp * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px)
    }
}
