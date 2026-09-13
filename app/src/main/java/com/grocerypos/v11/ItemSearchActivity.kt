package com.grocerypos.v11.ui

import com.grocerypos.v11.R

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
import com.grocerypos.v11.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.grocerypos.v11.ui.components.*

/**
 * Standalone "check the rate" screen: search for any item and see every sale
 * (who bought it, at what rate, when) and every purchase (which supplier, at
 * what rate, when) for that item — newest first, so the current going rate
 * is always the first row in each list.
 *
 * Can be opened two ways:
 *  1) Plain (from menu) — user types to search, exactly as before.
 *  2) With extras "product_id" / "product_name" (from Dashboard search result
 *     tap) — skips the typing step and shows that item's history directly.
 */
class ItemSearchActivity : ThemedActivity() {

    // ---- Same navy + teal palette as PurchaseActivity / SaleActivity ----
    // Pulled from ThemeManager so this screen respects dark mode.
    private var bg = "#F4F6F8"
    private var cardWhite = "#FFFFFF"
    private var navy = "#0B2545"
    private var teal = "#0F9B8E"
    private var orange = "#F5A15C"
    private var textDark = "#0B2545"
    private var textMuted = "#7C8798"
    private var border = "#E3E8EE"

    private fun tintedDrawable(iconRes: Int, tintHex: String, sizeDp: Int = 16): android.graphics.drawable.Drawable? {
        val d = androidx.core.content.ContextCompat.getDrawable(this, iconRes)?.mutate() ?: return null
        d.setTint(Color.parseColor(tintHex))
        val size = (sizeDp * resources.displayMetrics.density).toInt()
        d.setBounds(0, 0, size, size)
        return d
    }

    private fun loadThemeColors() {
        val p = com.grocerypos.v11.util.ThemeManager.palette(this)
        bg = p.bg
        cardWhite = p.cardWhite
        navy = p.navy
        teal = p.teal
        orange = p.amber
        textDark = p.textDark
        textMuted = p.textMuted
        border = p.border
    }

    private lateinit var searchInput: EditText
    private lateinit var resultsContainer: LinearLayout
    private lateinit var detailContainer: LinearLayout
    private lateinit var detailTitle: TextView

    private var products = listOf<Product>()

    // Extras passed in from Dashboard's live search result tap
    private var preselectProductBarcode: String? = null
    private var preselectProductName: String? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        loadThemeColors()

        preselectProductBarcode = intent.getStringExtra("product_barcode")
        preselectProductName = intent.getStringExtra("product_name")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 44, 24, 28)
            setBackgroundColor(Color.parseColor(bg))
        }

        // ================= HEADER =================
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 0, 4, 18)
            addView(TextView(this@ItemSearchActivity).apply {
                text = "Item Rate Search"
                textSize = 20f
                setTextColor(Color.parseColor(textDark))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        })
        root.addView(TextView(this).apply {
            text = "Search any item to see its sale & purchase rate history"
            textSize = 12f
            setTextColor(Color.parseColor(textMuted))
            setPadding(4, 0, 4, 16)
        })

        // ================= SEARCH BOX =================
        val searchBox = card()
        val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        searchRow.addView(ImageView(this).apply {
            setImageDrawable(tintedDrawable(R.drawable.ic_search, textMuted, 16))
            setPadding(0, 0, 12, 0)
        })
        searchInput = EditText(this).apply {
            hint = "Type item name…"
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        searchRow.addView(searchInput)
        searchBox.addView(searchRow)
        root.addView(searchBox)

        // ================= RESULTS (matching products) =================
        resultsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(resultsContainer)
        root.addView(spacer(8))

        // ================= DETAIL (selected item's rate history) =================
        detailTitle = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor(textDark))
            setPadding(4, 8, 4, 10)
            visibility = View.GONE
        }
        root.addView(detailTitle)

        detailContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(detailContainer)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        })

        loadProducts()

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                renderResults(s?.toString()?.trim() ?: "")
            }
        })
    }

    private fun loadProducts() {
        lifecycleScope.launch {
            PosDatabase.get(this@ItemSearchActivity).productDao().all().collectLatest { list ->
                products = list

                // If opened directly from Dashboard's search result with a specific
                // product, jump straight to its history instead of showing the
                // type-to-search box empty — no retyping needed.
                if (preselectProductBarcode != null) {
                    val match = products.firstOrNull { it.barcode == preselectProductBarcode }
                        ?: preselectProductName?.let { name -> products.firstOrNull { it.name == name } }
                    if (match != null) {
                        searchInput.setText(match.name)
                        showItemHistory(match)
                        preselectProductBarcode = null // only auto-open once
                        preselectProductName = null
                        return@collectLatest
                    }
                }

                renderResults(searchInput.text.toString().trim())
            }
        }
    }

    private fun renderResults(query: String) {
        resultsContainer.removeAllViews()
        if (query.isEmpty()) return

        val matches = products.filter { it.name.contains(query, ignoreCase = true) }.take(15)
        if (matches.isEmpty()) {
            resultsContainer.addView(TextView(this).apply {
                text = "No matching item"
                textSize = 13f
                setTextColor(Color.parseColor(textMuted))
                setPadding(4, 10, 4, 4)
            })
            return
        }
        matches.forEach { product ->
            resultsContainer.addView(card().apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { showItemHistory(product) }
                addView(TextView(this@ItemSearchActivity).apply {
                    text = product.name
                    textSize = 14.5f
                    setTextColor(Color.parseColor(textDark))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@ItemSearchActivity).apply {
                    // ---- FIX: Product.stock is a SMALLEST-unit count, not a primary-unit
                    // count, so it must never be paired with `product.unit` directly (was
                    // showing e.g. "500 carton" when 500 was actually the dabbi count).
                    // formatStockBreakdown() is the same helper Product/Purchase/Sale/
                    // Dashboard/CategoriesUnits screens use, so this now always agrees.
                    text = "Stock: ${product.formatStockBreakdown()}"
                    textSize = 11.5f
                    setTextColor(Color.parseColor(textMuted))
                })
            })
        }
    }

    private fun showItemHistory(product: Product) {
        detailTitle.visibility = View.VISIBLE
        detailTitle.text = product.name
        detailContainer.removeAllViews()
        resultsContainer.removeAllViews()

        lifecycleScope.launch {
            val db = PosDatabase.get(this@ItemSearchActivity)
            val saleRecords = db.saleDao().saleRecordsForItem(product.barcode)
            val purchaseRecords = db.purchaseDao().purchaseRecordsForItem(product.barcode)
            val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())

            // ---- Sale rate history (newest first, so latest sale rate is on top) ----
            detailContainer.addView(sectionHeader("Sale Rate History", teal))
            if (saleRecords.isEmpty()) {
                detailContainer.addView(emptyRow("No sales of this item yet"))
            } else {
                saleRecords.forEachIndexed { index, r ->
                    detailContainer.addView(
                        rateRow(
                            party = r.customerName,
                            qtyLabel = "${r.qty} ${product.unit}",
                            rate = r.unitPrice,
                            date = fmt.format(Date(r.createdAt)),
                            colorHex = teal,
                            isLatest = index == 0,
                            product = product
                        )
                    )
                }
            }

            detailContainer.addView(spacer(14))

            // ---- NEW: Compare Suppliers — groups the same purchase records by
            // supplier so the user can see, at a glance, who is currently cheapest
            // for this item (last rate) and who tends to be cheapest overall (avg
            // rate), without reading through the full chronological list above. ----
            detailContainer.addView(sectionHeader("Compare Suppliers", navy))
            if (purchaseRecords.isEmpty()) {
                detailContainer.addView(emptyRow("No supplier data yet for this item"))
            } else {
                val bySupplier = purchaseRecords.groupBy { it.supplierName }
                val summaries = bySupplier.map { (supplier, records) ->
                    // records are already newest-first (query orders by createdAt DESC),
                    // so the first one per supplier is that supplier's latest rate.
                    val lastRate = records.first().unitCost
                    val avgRate = records.sumOf { it.unitCost } / records.size
                    Triple(supplier, lastRate, Pair(avgRate, records.size))
                }.sortedBy { it.second }
                val cheapestRate = summaries.minOf { it.second }
                summaries.forEach { (supplier, lastRate, avgAndCount) ->
                    detailContainer.addView(
                        supplierCompareRow(
                            supplier = supplier,
                            lastRate = lastRate,
                            avgRate = avgAndCount.first,
                            purchaseCount = avgAndCount.second,
                            isBest = lastRate == cheapestRate,
                            product = product
                        )
                    )
                }
            }

            detailContainer.addView(spacer(14))

            // ---- Purchase rate history (newest first, so latest cost rate is on top) ----
            detailContainer.addView(sectionHeader("Purchase Rate History", orange))
            if (purchaseRecords.isEmpty()) {
                detailContainer.addView(emptyRow("No purchases of this item yet"))
            } else {
                purchaseRecords.forEachIndexed { index, r ->
                    detailContainer.addView(
                        rateRow(
                            party = r.supplierName,
                            qtyLabel = "${r.qty} ${product.unit}",
                            rate = r.unitCost,
                            date = fmt.format(Date(r.createdAt)),
                            colorHex = orange,
                            isLatest = index == 0,
                            product = product
                        )
                    )
                }
            }
        }
    }

    // NEW: given a rate in the product's PRIMARY unit, format the same rate in
    // every OTHER tier of the product's unit ladder (secondary/tertiary), e.g.
    // "Rs 769.17 / Dzn  •  Rs 64.10 / Pc" for a Carton→Dozen→Piece product.
    // Reuses Product.fromPrimaryUnitRate() (the same conversion Sale/Purchase
    // screens use) so this can never drift out of sync with actual pricing math.
    // Returns "" for a 1-tier product (nothing to break down).
    private fun Product.rateBreakdownLabel(primaryRate: Double): String {
        val ladder = unitLadder()
        if (ladder.size <= 1) return ""
        // unitLadder() is smallest-first; reverse to largest-first ([primary, ...,
        // smallest]) and drop the primary tier since that's already the main
        // "Rs X" figure shown above this line.
        return ladder.asReversed().drop(1).joinToString("   •   ") { tier ->
            "Rs %.2f / ${tier.unit}".format(fromPrimaryUnitRate(primaryRate, tier.unit))
        }
    }

    private fun unitBreakdownRow(product: Product, rate: Double, colorHex: String): TextView? {
        val label = product.rateBreakdownLabel(rate)
        if (label.isEmpty()) return null
        return TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.parseColor(colorHex))
            setPadding(0, 4, 0, 0)
        }
    }

    // NEW: one row of the Compare Suppliers table — supplier name, their last rate
    // and running average, purchase count, and a "BEST RATE" badge on whoever is
    // currently cheapest (by last rate).
    private fun supplierCompareRow(supplier: String, lastRate: Double, avgRate: Double, purchaseCount: Int, isBest: Boolean, product: Product): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 14, 18, 14)
            background = strokedBg(if (isBest) navy else border, if (isBest) "#EAF2FF" else cardWhite, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 8) }

            val top = LinearLayout(this@ItemSearchActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(TextView(this@ItemSearchActivity).apply {
                text = supplier
                textSize = 13.5f
                setTextColor(Color.parseColor(textDark))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (isBest) {
                top.addView(TextView(this@ItemSearchActivity).apply {
                    text = "BEST RATE"
                    textSize = 9.5f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    background = roundedBg(navy, 20)
                    setPadding(14, 4, 14, 4)
                })
                top.addView(View(this@ItemSearchActivity).apply { layoutParams = LinearLayout.LayoutParams(8, 1) })
            }
            top.addView(TextView(this@ItemSearchActivity).apply {
                text = "Rs %.2f".format(lastRate)
                textSize = 14f
                setTextColor(Color.parseColor(if (isBest) navy else textDark))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(top)
            unitBreakdownRow(product, lastRate, if (isBest) navy else textMuted)?.let { addView(it) }
            addView(TextView(this@ItemSearchActivity).apply {
                text = "Last rate  •  Avg Rs %.2f over %d purchase%s".format(avgRate, purchaseCount, if (purchaseCount == 1) "" else "s")
                textSize = 11.5f
                setTextColor(Color.parseColor(textMuted))
                setPadding(0, 4, 0, 0)
            })
        }
    }

    private fun sectionHeader(label: String, colorHex: String) = TextView(this).apply {
        text = label
        textSize = 12.5f
        setTextColor(Color.parseColor(colorHex))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(4, 0, 4, 8)
    }

    private fun emptyRow(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(Color.parseColor(textMuted))
        setPadding(4, 4, 4, 12)
    }

    private fun rateRow(party: String, qtyLabel: String, rate: Double, date: String, colorHex: String, isLatest: Boolean, product: Product): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 14, 18, 14)
            background = strokedBg(if (isLatest) colorHex else border, cardWhite, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 8) }

            val top = LinearLayout(this@ItemSearchActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(TextView(this@ItemSearchActivity).apply {
                text = party
                textSize = 13.5f
                setTextColor(Color.parseColor(textDark))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (isLatest) {
                top.addView(TextView(this@ItemSearchActivity).apply {
                    text = "LATEST"
                    textSize = 9.5f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    background = roundedBg(colorHex, 20)
                    setPadding(14, 4, 14, 4)
                    setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
                })
                top.addView(View(this@ItemSearchActivity).apply { layoutParams = LinearLayout.LayoutParams(8, 1) })
            }
            top.addView(TextView(this@ItemSearchActivity).apply {
                text = "Rs %.2f".format(rate)
                textSize = 14f
                setTextColor(Color.parseColor(colorHex))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(top)
            unitBreakdownRow(product, rate, colorHex)?.let { addView(it) }
            addView(TextView(this@ItemSearchActivity).apply {
                text = "$qtyLabel  •  $date"
                textSize = 11.5f
                setTextColor(Color.parseColor(textMuted))
                setPadding(0, 4, 0, 0)
            })
        }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20, 14, 20, 14)
        background = strokedBg(border, cardWhite, 14)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 10) }
        elevation = 2f
    }

    // spacer() now comes from the shared UiHelpers.kt (item #24 dedup) — was a
    // byte-identical private copy here before.
}
