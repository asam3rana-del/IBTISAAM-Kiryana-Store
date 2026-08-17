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
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.Customer
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Supplier
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Home-screen style dashboard: "You'll Get / You'll Give" summary cards + Parties /
 * Transactions / Items tabs + searchable party list + bottom Add Purchase / Add Sale bar.
 *
 * This is a NEW, separate screen — it does not touch PartyActivity.kt. Wire it up wherever
 * you want (e.g. point MainActivity's "Customers & Suppliers" tile here instead of
 * PartyActivity, or add a fresh tile for it). It reuses PartyActivity for the actual
 * add/edit/delete forms and full history, so nothing is duplicated.
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
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(root)
        }

        val stack = FrameLayout(this).apply {
            addView(scrollArea)
            addView(buildBottomBar())
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
                setOnClickListener { finish() }
            })

            addView(TextView(this@PartyDashboardActivity).apply {
                text = Loc.t(this@PartyDashboardActivity, "Dashboard", "ڈیش بورڈ")
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
                    Toast.makeText(this@PartyDashboardActivity, Loc.t(this@PartyDashboardActivity, "No new notifications", "کوئی نئی اطلاع نہیں"), Toast.LENGTH_SHORT).show()
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

    private fun shareSummary() {
        val text = Loc.t(
            this,
            "You'll Get: Rs %.2f\nYou'll Give: Rs %.2f".format(
                parseAmount(youllGetValue.text), parseAmount(youllGiveValue.text)
            ),
            "آپ کو ملیں گے: روپے %.2f\nآپ کو دینے ہیں: روپے %.2f".format(
                parseAmount(youllGetValue.text), parseAmount(youllGiveValue.text)
            )
        )
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }, Loc.t(this, "Share summary", "خلاصہ شیئر کریں")))
    }

    private fun parseAmount(t: CharSequence): Double =
        t.toString().replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0

    // ================= SUMMARY CARDS =================
    private fun buildSummaryCards(): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val getCard = summaryCard("\u2193", Loc.t(this, "You'll Get", "آپ کو ملیں گے"), green)
        val giveCard = summaryCard("\u2191", Loc.t(this, "You'll Give", "آپ کو دینے ہیں"), red)
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
            Triple(Tab.PARTIES, Loc.t(this, "Parties", "پارٹیز"), true),
            Triple(Tab.TRANSACTIONS, Loc.t(this, "Transactions", "لین دین"), true),
            Triple(Tab.ITEMS, Loc.t(this, "Items", "آئٹمز"), true)
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
            hint = Loc.t(this@PartyDashboardActivity, "Search party", "پارٹی تلاش کریں")
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
            text = "+ " + Loc.t(this@PartyDashboardActivity, "New Party", "نئی پارٹی")
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
            Loc.t(this, "All Parties", "تمام پارٹیز"),
            Loc.t(this, "Customers Only", "صرف کسٹمرز"),
            Loc.t(this, "Suppliers Only", "صرف سپلائرز")
        )
        val current = when (filterMode) { FilterMode.ALL -> 0; FilterMode.CUSTOMERS -> 1; FilterMode.SUPPLIERS -> 2 }
        AlertDialog.Builder(this)
            .setTitle(Loc.t(this, "Filter", "فلٹر"))
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

        bar.addView(TextView(this).apply {
            text = Loc.t(this@PartyDashboardActivity, "Add Purchase", "خریداری شامل کریں")
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
            text = Loc.t(this@PartyDashboardActivity, "Add Sale", "سیل شامل کریں")
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
            Loc.t(this, "Add Sale", "سیل شامل کریں"),
            Loc.t(this, "Add Purchase", "خریداری شامل کریں"),
            Loc.t(this, "New Party", "نئی پارٹی")
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

    // ================= DATA LOAD =================
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
            Tab.TRANSACTIONS -> listContainer.addView(placeholderCard(Loc.t(this, "Transactions view — hook this up to SaleDao/PurchaseDao when ready", "لین دین کا منظر — بعد میں سیل/خریداری ڈیٹا سے جوڑیں")))
            Tab.ITEMS -> listContainer.addView(placeholderCard(Loc.t(this, "Items view — hook this up to your ItemDao when ready", "آئٹمز کا منظر — بعد میں آئٹم ڈیٹا سے جوڑیں")))
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
            listContainer.addView(placeholderCard(Loc.t(this, "No parties found", "کوئی پارٹی نہیں ملی")))
            return
        }

        for (item in filtered) {
            listContainer.addView(dashboardPartyRow(item))
        }
    }

    private fun dashboardPartyRow(item: PartyItem): LinearLayout {
        val give = item.closing > 0
        val amountColor = if (give) red else green
        val label = if (give) Loc.t(this, "You'll Give", "آپ کو دینے ہیں") else Loc.t(this, "You'll Get", "آپ کو ملیں گے")

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
                text = if (item.isCustomer) Loc.t(this@PartyDashboardActivity, "Customer", "کسٹمر") else Loc.t(this@PartyDashboardActivity, "Supplier", "سپلائر")
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
