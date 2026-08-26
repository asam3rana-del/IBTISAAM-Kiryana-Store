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
import com.grocerypos.v11.Product
import com.grocerypos.v11.Supplier
import com.grocerypos.v11.formatStockBreakdown
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home-screen style dashboard: "You'll Get / You'll Give" summary cards + Parties /
 * Transactions / Items tabs + searchable list + bottom Add Purchase / Add Sale bar.
 *
 * ---- CHANGES IN THIS VERSION (per Roman Urdu requirements) ----
 * 1. Parties tab: tapping a party now opens PartyTransactionActivity — a NEW screen
 *    showing only that party's own sales/purchases, instead of the generic PartyActivity.
 * 2. Transactions tab: the search/add row now shows ONLY a search box (no "New Party"
 *    button, no filter icon) and filters by party name across cached transactions.
 * 3. Transactions tab: rows are clickable and open the underlying Sale/Purchase.
 *    *** ASSUMPTION *** — SaleActivity is opened with extra "invoice" (String) and
 *    PurchaseActivity with extra "billNo" (String) to load that record in edit mode,
 *    matching the pattern already described for PurchaseActivity's edit/delete flow.
 *    If those activities expect different extra keys, tell me and I'll fix the two
 *    lines marked ADJUST-EXTRA-KEY below.
 * 4. Items tab: search/add row now shows a search box + "+ Add Item" button (opens
 *    ProductActivity) instead of "+ New Party". Search filters by product name.
 * 5. Items tab: each product row shows its full profile — category, unit, purchase
 *    (cost) rate, retail (sale) rate, wholesale rate — plus lifetime sold/purchased
 *    qty & amount already there before. Tapping a row shows the full detail dialog.
 *
 * ---- FIX IN THIS VERSION ----
 * 6. You'll Get / You'll Give totals (both the top summary cards and each party row's
 *    label) were using the exact same sign rule for customers and suppliers, which
 *    wrongly mixed receivables and payables together. Fixed so that:
 *      - Customer closing > 0  => customer owes the shop (receivable)  -> You'll Get
 *      - Customer closing < 0  => shop owes the customer                -> You'll Give
 *      - Supplier closing > 0  => shop owes the supplier (payable)      -> You'll Give
 *      - Supplier closing < 0  => supplier owes the shop (e.g. credit)  -> You'll Get
 *    See updateSummaryTotals() and dashboardPartyRow() below.
 * 7. Items tab stock display: Product.stock is the SMALLEST-unit count, not a primary-unit
 *    count, so "${stock} ${unit}" (raw smallest count + primary unit label) was misleading.
 *    Now uses Product.formatStockBreakdown() (same helper Product/Purchase/Sale screens use)
 *    so the Items tab always agrees with the rest of the app.
 *
 * ---- BUILD FIX ----
 * 8. renderItemsList(): sold?.totalQty / pur?.totalQty come back as Double (SQL SUM
 *    aggregate), but ItemAgg.soldQty/purQty are declared Int. `sold?.totalQty ?: 0`
 *    mixed a Double with an Int literal, which the Kotlin compiler couldn't resolve to
 *    a single type (":app:compileDebugKotlin" failure — "Argument type mismatch: actual
 *    type is 'it(kotlin.Number & kotlin.Comparable<CapturedType(*)>)', but 'kotlin.Int'
 *    was expected"). Fixed by defaulting to 0.0 and rounding to Int explicitly.
 *
 * Manifest: PartyTransactionActivity must be added:
 *   <activity android:name=".ui.PartyTransactionActivity" android:exported="false" />
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
    private lateinit var searchRowContainer: LinearLayout
    private lateinit var listContainer: LinearLayout

    private var activeTab = Tab.PARTIES
    private var filterMode = FilterMode.ALL
    private var allItems: List<PartyItem> = emptyList()
    private var role: String = "cashier"

    // ---- Transactions tab cache + search query (so typing doesn't re-hit the DB) ----
    private var txCache: List<TxRow> = emptyList()
    private var txQuery: String = ""

    // ---- Items tab cache + search query ----
    private var itemCache: List<ItemAgg> = emptyList()
    private var itemQuery: String = ""

    private enum class Tab { PARTIES, TRANSACTIONS, ITEMS }
    private enum class FilterMode { ALL, CUSTOMERS, SUPPLIERS }

    /** Unified wrapper so customers + suppliers can share one list/adapter-ish rendering. */
    private data class PartyItem(
        val id: Long,
        val name: String,
        val phone: String,
        val closing: Double,
        val isCustomer: Boolean
    )

    private data class TxRow(
        val reference: String,   // invoice (sale) or billNo (purchase)
        val partyName: String,
        val amount: Double,
        val createdAt: Long,
        val isSale: Boolean,
        val status: String
    )

    private data class ItemAgg(
        val product: String,
        val category: String,
        val unit: String,
        val cost: Double,
        val salePrice: Double,
        val wholesalePrice: Double,
        val stock: Int,
        val stockDisplay: String,
        val soldQty: Int,
        val soldAmt: Double,
        val purQty: Int,
        val purAmt: Double
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

        searchRowContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(searchRowContainer)
        root.addView(spacer(14))

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)
        root.addView(spacer(90)) // keep list clear of the floating bottom bar

        val scrollArea = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            addView(root)
        }

        val stack = FrameLayout(this).apply {
            addView(scrollArea)
            addView(buildBottomBar())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        outer.addView(stack)

        setContentView(outer)

        renderSearchRow()
        loadParties()
    }

    override fun onResume() {
        super.onResume()
        loadParties()
        // Transactions/Items caches are refreshed lazily when their tab is opened
        // (renderActiveTabBody -> render*List with forceReload), so a sale/purchase
        // added elsewhere shows up next time the user visits those tabs.
        if (activeTab == Tab.TRANSACTIONS) renderTransactionsList(forceReload = true)
        if (activeTab == Tab.ITEMS) renderItemsList(forceReload = true)
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

    // ================= MAIN MENU =================
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
                    renderSearchRow()
                    renderActiveTabBody()
                }
            })
        }
    }

    // ================= SEARCH / ADD ROW (rebuilt per tab) =================
    private fun renderSearchRow() {
        searchRowContainer.removeAllViews()
        when (activeTab) {
            Tab.PARTIES -> searchRowContainer.addView(buildPartySearchRow())
            Tab.TRANSACTIONS -> searchRowContainer.addView(buildSearchOnlyRow(
                hint = Loc.t(this, "Search transaction (party name)", "\u067E\u0627\u0631\u0679\u06CC \u06A9\u0627 \u0646\u0627\u0645 \u0633\u06D2 \u062A\u0644\u0627\u0634 \u06A9\u0631\u06CC\u06BA"),
                onQueryChanged = { txQuery = it; renderTxRows() }
            ))
            Tab.ITEMS -> searchRowContainer.addView(buildItemSearchRow())
        }
    }

    /** Search box + filter icon + "New Party" button — Parties tab only. */
    private fun buildPartySearchRow(): LinearLayout {
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
        searchBox.addView(EditText(this).apply {
            hint = Loc.t(this@PartyDashboardActivity, "Search party", "\u067E\u0627\u0631\u0679\u06CC \u062A\u0644\u0627\u0634 \u06A9\u0631\u06CC\u06BA")
            background = null
            textSize = 13.5f
            setHintTextColor(Color.parseColor(labelGray))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { renderPartyList(s?.toString().orEmpty()) }
                override fun afterTextChanged(s: Editable?) {}
            })
        })
        row.addView(searchBox)
        row.addView(spacerHoriz(10))

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
        row.addView(spacerHoriz(10))

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

    /** Search box only — used for the Transactions tab (no add button, no filter). */
    private fun buildSearchOnlyRow(hint: String, onQueryChanged: (String) -> Unit): LinearLayout {
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
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        searchBox.addView(TextView(this).apply { text = "\uD83D\uDD0D  "; textSize = 14f; setTextColor(Color.parseColor(blue)) })
        searchBox.addView(EditText(this).apply {
            this.hint = hint
            background = null
            textSize = 13.5f
            setHintTextColor(Color.parseColor(labelGray))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { onQueryChanged(s?.toString().orEmpty()) }
                override fun afterTextChanged(s: Editable?) {}
            })
        })
        row.addView(searchBox)
        return row
    }

    /** Search box + "Add Item" button — Items tab only. */
    private fun buildItemSearchRow(): LinearLayout {
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
        searchBox.addView(EditText(this).apply {
            hint = Loc.t(this@PartyDashboardActivity, "Search item", "\u0622\u0626\u0679\u0645 \u062A\u0644\u0627\u0634 \u06A9\u0631\u06CC\u06BA")
            background = null
            textSize = 13.5f
            setHintTextColor(Color.parseColor(labelGray))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { itemQuery = s?.toString().orEmpty(); renderItemRows() }
                override fun afterTextChanged(s: Editable?) {}
            })
        })
        row.addView(searchBox)
        row.addView(spacerHoriz(10))

        row.addView(TextView(this).apply {
            text = "+ " + Loc.t(this@PartyDashboardActivity, "Add Item", "\u0622\u0626\u0679\u0645 \u0634\u0627\u0645\u0644 \u06A9\u0631\u06CC\u06BA")
            textSize = 13f
            setTextColor(Color.parseColor(blue))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(20, 14, 20, 14)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E9EBFF"))
                cornerRadius = 22f
            }
            setOnClickListener { startActivity(Intent(this@PartyDashboardActivity, ProductActivity::class.java)) }
        })

        return row
    }

    private fun spacerHoriz(widthDp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams((widthDp * resources.displayMetrics.density).toInt(), 1)
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
                    items.add(PartyItem(id = c.id, name = c.name, phone = c.phone, closing = c.openingBalance + c.balance, isCustomer = true))
                }
                for (s in suppliers) {
                    items.add(PartyItem(id = s.id, name = s.name, phone = s.phone, closing = s.openingBalance + s.balance, isCustomer = false))
                }
                allItems = items.sortedBy { it.name.lowercase() }
                updateSummaryTotals()
                if (activeTab == Tab.PARTIES) renderPartyList()
            }
        }
    }

    /**
     * ---- FIX ----
     * Customer and supplier closing balances mean opposite things and must NOT share the
     * same sign rule:
     *   - Customer closing > 0  => customer owes the shop (receivable)  -> You'll Get
     *   - Customer closing < 0  => shop owes the customer                -> You'll Give
     *   - Supplier closing > 0  => shop owes the supplier (payable)      -> You'll Give
     *   - Supplier closing < 0  => supplier owes the shop (e.g. credit)  -> You'll Get
     */
    private fun updateSummaryTotals() {
        val youllGet = allItems.sumOf {
            if (it.isCustomer) maxOf(it.closing, 0.0) else maxOf(-it.closing, 0.0)
        }
        val youllGive = allItems.sumOf {
            if (it.isCustomer) maxOf(-it.closing, 0.0) else maxOf(it.closing, 0.0)
        }
        youllGetValue.text = "Rs %.2f".format(youllGet)
        youllGiveValue.text = "Rs %.2f".format(youllGive)
    }

    private fun renderActiveTabBody() {
        listContainer.removeAllViews()
        when (activeTab) {
            Tab.PARTIES -> renderPartyList()
            Tab.TRANSACTIONS -> renderTransactionsList(forceReload = true)
            Tab.ITEMS -> renderItemsList(forceReload = true)
        }
    }

    // ================= PARTIES TAB =================
    private fun renderPartyList(query: String = "") {
        if (activeTab != Tab.PARTIES) return
        listContainer.removeAllViews()

        val q = query.trim().lowercase()
        val filtered = allItems
            .filter { item ->
                when (filterMode) {
                    FilterMode.ALL -> true
                    FilterMode.CUSTOMERS -> item.isCustomer
                    FilterMode.SUPPLIERS -> !item.isCustomer
                }
            }
            .filter { it.name.lowercase().contains(q) }

        if (filtered.isEmpty()) {
            listContainer.addView(placeholderCard(Loc.t(this, "No parties found", "\u06A9\u0648\u0626\u06CC \u067E\u0627\u0631\u0679\u06CC \u0646\u06C1\u06CC\u06BA \u0645\u0644\u06CC")))
            return
        }

        for (item in filtered) {
            listContainer.addView(dashboardPartyRow(item))
        }
    }

    private fun dashboardPartyRow(item: PartyItem): LinearLayout {
        // ---- FIX: type-aware give/get, see updateSummaryTotals() comment above ----
        val give = if (item.isCustomer) item.closing < 0 else item.closing > 0
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
            // ---- CHANGE: opens PartyTransactionActivity filtered to this party,
            // instead of the generic PartyActivity. ----
            setOnClickListener {
                startActivity(Intent(this@PartyDashboardActivity, PartyTransactionActivity::class.java).apply {
                    putExtra("partyId", item.id)
                    putExtra("partyName", item.name)
                    putExtra("isCustomer", item.isCustomer)
                })
            }

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
    // Merges recent sales + purchases into one date-sorted feed. forceReload=true
    // re-hits the DB (used when the tab is opened); typing in the search box only
    // re-filters the cached list (renderTxRows), no DB hit.
    private fun renderTransactionsList(forceReload: Boolean) {
        if (!forceReload) { renderTxRows(); return }
        listContainer.removeAllViews()
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PartyDashboardActivity)
            val sales = db.saleDao().allSales()
            val purchases = db.purchaseDao().allPurchases()

            val merged = mutableListOf<TxRow>()
            sales.forEach { merged.add(TxRow(it.invoice, it.customerName, it.total, it.createdAt, true, it.status)) }
            purchases.forEach { merged.add(TxRow(it.billNo, it.supplierName, it.total, it.createdAt, false, it.status)) }
            txCache = merged.sortedByDescending { it.createdAt }.take(100)

            if (activeTab != Tab.TRANSACTIONS) return@launch
            renderTxRows()
        }
    }

    private fun renderTxRows() {
        if (activeTab != Tab.TRANSACTIONS) return
        listContainer.removeAllViews()
        val q = txQuery.trim().lowercase()
        val filtered = txCache.filter { it.partyName.lowercase().contains(q) }

        if (filtered.isEmpty()) {
            listContainer.addView(placeholderCard(Loc.t(this, "No transactions yet", "\u0627\u0628\u06BE\u06CC \u062A\u06A9 \u06A9\u0648\u0626\u06CC \u0644\u06CC\u0646 \u062F\u06CC\u0646 \u0646\u06C1\u06CC\u06BA \u06C1\u06D2")))
            return
        }

        val fmt = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        for (row in filtered) {
            listContainer.addView(transactionRow(row, fmt.format(Date(row.createdAt))))
        }
    }

    private fun transactionRow(row: TxRow, dateText: String): LinearLayout {
        val accent = if (row.isSale) green else orange
        val typeLabel = if (row.isSale) Loc.t(this, "Sale", "\u0633\u06CC\u0644") else Loc.t(this, "Purchase", "\u062E\u0631\u06CC\u062F\u0627\u0631\u06CC")

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 14, 18, 14)
            background = elevatedCardBg()
            elevation = 2f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 10) }
            isClickable = true
            // ---- CHANGE: tapping a transaction opens it. ADJUST-EXTRA-KEY if
            // SaleActivity/PurchaseActivity expect a different extra name. ----
            setOnClickListener {
                val intent = if (row.isSale) {
                    Intent(this@PartyDashboardActivity, SaleActivity::class.java).apply {
                        putExtra("invoice", row.reference) // ADJUST-EXTRA-KEY
                    }
                } else {
                    Intent(this@PartyDashboardActivity, PurchaseActivity::class.java).apply {
                        putExtra("billNo", row.reference) // ADJUST-EXTRA-KEY
                    }
                }
                startActivity(intent)
            }

            addView(TextView(this@PartyDashboardActivity).apply {
                text = if (row.isSale) "\uD83D\uDED2" else "\uD83E\uDDFE"
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
                text = row.partyName
                textSize = 14.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#2E3242"))
            })
            infoCol.addView(TextView(this@PartyDashboardActivity).apply {
                text = "$typeLabel \u00B7 $dateText" + if (row.status == "returned") "  \u2022  " + Loc.t(this@PartyDashboardActivity, "Returned", "\u0648\u0627\u067E\u0633") else ""
                textSize = 11f
                setTextColor(Color.parseColor(if (row.status == "returned") red else labelGray))
            })
            addView(infoCol)

            addView(TextView(this@PartyDashboardActivity).apply {
                text = "Rs %.2f".format(row.amount)
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(accent))
            })
        }
    }

    // ================= ITEMS TAB =================
    // Combines each product's own profile (category/unit/rates) with its all-time
    // sold vs purchased totals, so the row shows the item's complete picture.
    private fun renderItemsList(forceReload: Boolean) {
        if (!forceReload) { renderItemRows(); return }
        listContainer.removeAllViews()
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PartyDashboardActivity)
            val products = db.productDao().all().first()
            val soldTotals = db.saleDao().allTimeItemTotals()
            val purchasedTotals = db.purchaseDao().allTimeItemTotals()

            val soldMap = soldTotals.associateBy { it.product }
            val purMap = purchasedTotals.associateBy { it.product }

            itemCache = products.map { p ->
                val sold = soldMap[p.name]
                val pur = purMap[p.name]
                ItemAgg(
                    product = p.name,
                    category = p.category,
                    unit = p.unit,
                    cost = p.cost,
                    salePrice = p.salePrice,
                    wholesalePrice = p.wholesalePrice,
                    stock = p.stock,
                    // Product.stock is a SMALLEST-unit count, not a primary-unit count, so it
                    // must never be paired with `p.unit` directly — formatStockBreakdown() is
                    // the same helper Product/Purchase/Sale screens use for this.
                    stockDisplay = p.formatStockBreakdown(),
                    // ---- FIX: totalQty is a Double (SQL SUM aggregate); ItemAgg.soldQty/
                    // purQty are Int. `?: 0` mixed an Int literal with a Double, which the
                    // compiler couldn't resolve to a single type — this was the
                    // ":app:compileDebugKotlin" failure. Default to 0.0 and round to Int.
                    soldQty = (sold?.totalQty ?: 0.0).toInt(),
                    soldAmt = sold?.totalAmount ?: 0.0,
                    purQty = (pur?.totalQty ?: 0.0).toInt(),
                    purAmt = pur?.totalAmount ?: 0.0
                )
            }.sortedBy { it.product.lowercase() }

            if (activeTab != Tab.ITEMS) return@launch
            renderItemRows()
        }
    }

    private fun renderItemRows() {
        if (activeTab != Tab.ITEMS) return
        listContainer.removeAllViews()
        val q = itemQuery.trim().lowercase()
        val filtered = itemCache.filter { it.product.lowercase().contains(q) }

        if (filtered.isEmpty()) {
            listContainer.addView(placeholderCard(Loc.t(this, "No items found", "\u06A9\u0648\u0626\u06CC \u0622\u0626\u0679\u0645 \u0646\u06C1\u06CC\u06BA \u0645\u0644\u0627")))
            return
        }

        for (c in filtered) {
            listContainer.addView(itemRow(c))
        }
    }

    private fun itemRow(c: ItemAgg): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 14, 18, 14)
            background = elevatedCardBg()
            elevation = 2f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 10) }
            isClickable = true
            setOnClickListener { showItemDetailDialog(c) }

            val topRow = LinearLayout(this@PartyDashboardActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            topRow.addView(TextView(this@PartyDashboardActivity).apply {
                text = c.product
                textSize = 14.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#2E3242"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            topRow.addView(TextView(this@PartyDashboardActivity).apply {
                text = "Stock: ${c.stockDisplay}"
                textSize = 11.5f
                setTextColor(Color.parseColor(labelGray))
            })
            addView(topRow)

            addView(TextView(this@PartyDashboardActivity).apply {
                text = Loc.t(this@PartyDashboardActivity, "Category", "\u06A9\u06CC\u0679\u06AF\u0631\u06CC") + ": ${c.category.ifBlank { "-" }}  \u00B7  " +
                        Loc.t(this@PartyDashboardActivity, "Unit", "\u06CC\u0648\u0646\u0679") + ": ${c.unit}"
                textSize = 11.5f
                setTextColor(Color.parseColor(labelGray))
                setPadding(0, 4, 0, 0)
            })

            val ratesRow = LinearLayout(this@PartyDashboardActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 6, 0, 0) }
            ratesRow.addView(TextView(this@PartyDashboardActivity).apply {
                text = Loc.t(this@PartyDashboardActivity, "Purchase Rate", "\u062E\u0631\u06CC\u062F \u0631\u06CC\u0679") + ": Rs %.2f".format(c.cost)
                textSize = 11.5f
                setTextColor(Color.parseColor(orange))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            ratesRow.addView(TextView(this@PartyDashboardActivity).apply {
                text = Loc.t(this@PartyDashboardActivity, "Retail Rate", "\u0631\u06CC\u0679\u06CC\u0644 \u0631\u06CC\u0679") + ": Rs %.2f".format(c.salePrice)
                textSize = 11.5f
                setTextColor(Color.parseColor(blue))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(ratesRow)

            addView(TextView(this@PartyDashboardActivity).apply {
                text = Loc.t(this@PartyDashboardActivity, "Wholesale Rate", "\u06C1\u0648\u0644 \u0633\u06CC\u0644 \u0631\u06CC\u0679") + ": Rs %.2f".format(c.wholesalePrice)
                textSize = 11.5f
                setTextColor(Color.parseColor("#7B61FF"))
                setPadding(0, 4, 0, 0)
            })

            val row = LinearLayout(this@PartyDashboardActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 0)
            }
            row.addView(TextView(this@PartyDashboardActivity).apply {
                text = Loc.t(this@PartyDashboardActivity, "Sold", "\u0641\u0631\u0648\u062E\u062A") + ": ${c.soldQty} \u00B7 Rs %.2f".format(c.soldAmt)
                textSize = 12f
                setTextColor(Color.parseColor(green))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this@PartyDashboardActivity).apply {
                text = Loc.t(this@PartyDashboardActivity, "Purchased", "\u062E\u0631\u06CC\u062F\u0627") + ": ${c.purQty} \u00B7 Rs %.2f".format(c.purAmt)
                textSize = 12f
                setTextColor(Color.parseColor(orange))
            })
            addView(row)
        }
    }

    private fun showItemDetailDialog(c: ItemAgg) {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 10)
        }
        fun line(label: String, value: String, colorHex: String = "#2E3242") {
            body.addView(LinearLayout(this@PartyDashboardActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
                addView(TextView(this@PartyDashboardActivity).apply {
                    text = label; textSize = 13.5f
                    setTextColor(Color.parseColor(labelGray))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@PartyDashboardActivity).apply {
                    text = value; textSize = 13.5f; gravity = Gravity.END
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor(colorHex))
                })
            })
        }
        line(Loc.t(this, "Category", "\u06A9\u06CC\u0679\u06AF\u0631\u06CC"), c.category.ifBlank { "-" })
        line(Loc.t(this, "Unit", "\u06CC\u0648\u0646\u0679"), c.unit)
        line(Loc.t(this, "Current Stock", "\u0645\u0648\u062C\u0648\u062F\u06C1 \u0627\u0633\u0679\u0627\u06A9"), c.stockDisplay)
        line(Loc.t(this, "Purchase Rate", "\u062E\u0631\u06CC\u062F \u0631\u06CC\u0679"), "Rs %.2f".format(c.cost), orange)
        line(Loc.t(this, "Retail Sale Rate", "\u0631\u06CC\u0679\u06CC\u0644 \u0631\u06CC\u0679"), "Rs %.2f".format(c.salePrice), blue)
        line(Loc.t(this, "Wholesale Rate", "\u06C1\u0648\u0644 \u0633\u06CC\u0644 \u0631\u06CC\u0679"), "Rs %.2f".format(c.wholesalePrice), "#7B61FF")
        body.addView(View(this).apply {
            setBackgroundColor(Color.parseColor(cardBorder))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply { setMargins(0, 8, 0, 8) }
        })
        line(Loc.t(this, "Total Sold (all-time)", "\u06A9\u0644 \u0641\u0631\u0648\u062E\u062A"), "${c.soldQty} \u00B7 Rs %.2f".format(c.soldAmt), green)
        line(Loc.t(this, "Total Purchased (all-time)", "\u06A9\u0644 \u062E\u0631\u06CC\u062F\u0627\u0631\u06CC"), "${c.purQty} \u00B7 Rs %.2f".format(c.purAmt), orange)

        AlertDialog.Builder(this)
            .setTitle(c.product)
            .setView(body)
            .setPositiveButton(Loc.t(this, "Close", "\u0628\u0646\u062F \u06A9\u0631\u06CC\u06BA"), null)
            .show()
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
