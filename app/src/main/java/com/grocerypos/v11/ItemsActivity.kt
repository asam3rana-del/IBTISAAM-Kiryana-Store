package com.grocerypos.v11.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.Category
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Product
import com.grocerypos.v11.UnitType
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * "Items" hub — three tabs: Products, Categories, Units.
 * Each tab has its own search box, list, and a floating "+ Add ..." button.
 */
class ItemsActivity : AppCompatActivity() {

    // ================= PREMIUM COLOR PALETTE (matches Settings / Product) =================
    private val bg = "#F3F2FA"
    private val cardBg = "#FFFFFF"
    private val primary = "#4A3AFF"
    private val primaryDark = "#3527D6"
    private val red = "#E5484D"
    private val redDark = "#C93A3E"
    private val purple = "#8B5CF6"
    private val amber = "#F5A524"
    private val textDark = "#1A1A2E"
    private val textGray = "#8A8A9E"
    private val border = "#E7E5F3"

    private enum class Tab { PRODUCTS, CATEGORIES, UNITS }
    private var currentTab = Tab.PRODUCTS

    private var allProducts: List<Product> = emptyList()
    private var allCategories: List<Category> = emptyList()
    private var allUnits: List<UnitType> = emptyList()
    private var searchQuery = ""

    private lateinit var tabRow: LinearLayout
    private lateinit var productsTabBtn: TextView
    private lateinit var categoriesTabBtn: TextView
    private lateinit var unitsTabBtn: TextView
    private lateinit var searchField: EditText
    private lateinit var listContainer: LinearLayout
    private lateinit var fab: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val outer = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor(bg))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 130)
        }

        // ================= HEADER =================
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(26, 22, 26, 22)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(primary), Color.parseColor(primaryDark))
            ).apply { cornerRadius = 22f }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 18) }
            applyElevation(this, 10f)
        }
        header.addView(circleIcon("🗃️", "#5C4DFF", 42))
        header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(16, 1) })
        val headerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerCol.addView(TextView(this).apply {
            text = "Items"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        headerCol.addView(TextView(this).apply {
            text = "Products, Categories & Units"
            textSize = 11.5f
            setTextColor(Color.parseColor("#D8D3FF"))
            setPadding(0, 4, 0, 0)
        })
        header.addView(headerCol)
        root.addView(header)

        // ================= TABS =================
        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = strokedBg(border, cardBg, 14)
            setPadding(6, 6, 6, 6)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 16) }
        }
        productsTabBtn = tabButton("PRODUCTS") { switchTab(Tab.PRODUCTS) }
        categoriesTabBtn = tabButton("CATEGORIES") { switchTab(Tab.CATEGORIES) }
        unitsTabBtn = tabButton("UNITS") { switchTab(Tab.UNITS) }
        tabRow.addView(productsTabBtn)
        tabRow.addView(categoriesTabBtn)
        tabRow.addView(unitsTabBtn)
        root.addView(tabRow)

        // ================= SEARCH =================
        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = strokedBg(border, "#FAFAFF", 14)
            setPadding(18, 4, 18, 4)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 18) }
        }
        searchBox.addView(TextView(this).apply { text = "🔍  "; textSize = 14f })
        searchField = EditText(this).apply {
            hint = "Search..."
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener {
                searchQuery = it.trim()
                renderCurrentTab()
            }
        }
        searchBox.addView(searchField)
        root.addView(searchBox)

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        val scroll = ScrollView(this).apply { addView(root) }
        outer.addView(scroll)

        // ================= FLOATING ADD BUTTON =================
        fab = TextView(this).apply {
            textSize = 14.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(46, 26, 46, 26)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor(red), Color.parseColor(redDark))
            ).apply { cornerRadius = 100f }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (24 * resources.displayMetrics.density).toInt()
            }
            applyElevation(this, 8f)
            setOnClickListener { onFabClicked() }
        }
        outer.addView(fab)

        setContentView(outer)

        loadAll()
        switchTab(Tab.PRODUCTS)
    }

    // ================= TAB SWITCHING =================
    private fun switchTab(tab: Tab) {
        currentTab = tab
        searchQuery = ""
        searchField.setText("")
        searchField.hint = when (tab) {
            Tab.PRODUCTS -> "Search Items by Name or Code"
            Tab.CATEGORIES -> "Search Category"
            Tab.UNITS -> "Search Unit"
        }
        fab.text = when (tab) {
            Tab.PRODUCTS -> "＋  Add Product"
            Tab.CATEGORIES -> "＋  Add Category"
            Tab.UNITS -> "＋  Add Unit"
        }

        productsTabBtn.setBackgroundColor(Color.TRANSPARENT)
        categoriesTabBtn.setBackgroundColor(Color.TRANSPARENT)
        unitsTabBtn.setBackgroundColor(Color.TRANSPARENT)
        productsTabBtn.setTextColor(Color.parseColor(textGray))
        categoriesTabBtn.setTextColor(Color.parseColor(textGray))
        unitsTabBtn.setTextColor(Color.parseColor(textGray))

        val selected = when (tab) {
            Tab.PRODUCTS -> productsTabBtn
            Tab.CATEGORIES -> categoriesTabBtn
            Tab.UNITS -> unitsTabBtn
        }
        selected.background = roundedBg(primary, 10)
        selected.setTextColor(Color.WHITE)

        renderCurrentTab()
    }

    private fun onFabClicked() {
        when (currentTab) {
            Tab.PRODUCTS -> startActivity(Intent(this, ProductActivity::class.java))
            Tab.CATEGORIES -> promptAddCategory()
            Tab.UNITS -> promptAddUnit()
        }
    }

    // ================= DATA LOADING =================
    private fun loadAll() {
        lifecycleScope.launch {
            PosDatabase.get(this@ItemsActivity).productDao().all().collectLatest {
                allProducts = it
                if (currentTab == Tab.PRODUCTS) renderCurrentTab()
            }
        }
        lifecycleScope.launch {
            PosDatabase.get(this@ItemsActivity).categoryDao().all().collectLatest {
                allCategories = it
                if (currentTab == Tab.CATEGORIES) renderCurrentTab()
            }
        }
        lifecycleScope.launch {
            PosDatabase.get(this@ItemsActivity).unitDao().all().collectLatest {
                allUnits = it
                if (currentTab == Tab.UNITS) renderCurrentTab()
            }
        }
    }

    private fun renderCurrentTab() {
        listContainer.removeAllViews()
        when (currentTab) {
            Tab.PRODUCTS -> renderProducts()
            Tab.CATEGORIES -> renderCategories()
            Tab.UNITS -> renderUnits()
        }
    }

    // ================= PRODUCTS TAB =================
    private fun renderProducts() {
        val filtered = if (searchQuery.isEmpty()) allProducts
        else allProducts.filter {
            it.name.contains(searchQuery, ignoreCase = true) || it.barcode.contains(searchQuery, ignoreCase = true)
        }
        if (filtered.isEmpty()) {
            listContainer.addView(emptyState("Koi product nahi mila"))
            return
        }
        for (p in filtered) {
            listContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 16, 20, 16)
                background = strokedBg(border, cardBg, 14)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 10) }

                addView(TextView(this@ItemsActivity).apply {
                    text = p.name
                    textSize = 14.5f
                    setTextColor(Color.parseColor(textDark))
                    setTypeface(typeface, Typeface.BOLD)
                })
                if (p.category.isNotBlank()) {
                    addView(TextView(this@ItemsActivity).apply {
                        text = p.category
                        textSize = 10.5f
                        setTextColor(Color.WHITE)
                        setTypeface(typeface, Typeface.BOLD)
                        background = roundedBg(purple, 16)
                        setPadding(16, 4, 16, 4)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 8, 0, 0) }
                    })
                }
                val priceRow = LinearLayout(this@ItemsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 10, 0, 0)
                }
                priceRow.addView(priceCol("Sale Price", p.salePrice))
                priceRow.addView(priceCol("Purchase Price", p.cost))
                addView(priceRow)

                setOnClickListener {
                    startActivity(Intent(this@ItemsActivity, ProductActivity::class.java))
                }
            })
        }
    }

    private fun priceCol(label: String, value: Double) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        addView(TextView(this@ItemsActivity).apply {
            text = label; textSize = 11f
            setTextColor(Color.parseColor(textGray))
        })
        addView(TextView(this@ItemsActivity).apply {
            text = "Rs %.2f".format(value)
            textSize = 13f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, Typeface.BOLD)
        })
    }

    // ================= CATEGORIES TAB =================
    private fun renderCategories() {
        val counts = allProducts.groupingBy { it.category.ifBlank { "" } }.eachCount()
        val notCategorized = counts[""] ?: 0

        val rows = mutableListOf<Pair<String, Int>>()
        rows.add("Items Not in Any Category" to notCategorized)
        for (c in allCategories) {
            rows.add(c.name to (counts[c.name] ?: 0))
        }

        val filtered = if (searchQuery.isEmpty()) rows
        else rows.filter { it.first.contains(searchQuery, ignoreCase = true) }

        if (filtered.isEmpty()) {
            listContainer.addView(emptyState("Koi category nahi mili"))
            return
        }

        for ((name, count) in filtered) {
            listContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(20, 18, 20, 18)
                background = strokedBg(border, cardBg, 14)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 10) }

                addView(TextView(this@ItemsActivity).apply {
                    text = name
                    textSize = 14f
                    setTextColor(Color.parseColor(textDark))
                    setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@ItemsActivity).apply {
                    text = count.toString()
                    textSize = 13.5f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, Typeface.BOLD)
                    background = roundedBg(purple, 20)
                    setPadding(20, 6, 20, 6)
                })
            })
        }
    }

    private fun promptAddCategory() {
        val input = EditText(this).apply { setPadding(32, 24, 32, 24) }
        AlertDialog.Builder(this)
            .setTitle("New Category")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) lifecycleScope.launch {
                    PosDatabase.get(this@ItemsActivity).categoryDao().insert(Category(v))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ================= UNITS TAB =================
    private fun renderUnits() {
        val filtered = if (searchQuery.isEmpty()) allUnits
        else allUnits.filter { it.name.contains(searchQuery, ignoreCase = true) }

        if (filtered.isEmpty()) {
            listContainer.addView(emptyState("Koi unit nahi mila"))
            return
        }
        for (u in filtered) {
            listContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(20, 18, 20, 18)
                background = strokedBg(border, cardBg, 14)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 10) }

                addView(TextView(this@ItemsActivity).apply { text = "📏  "; textSize = 15f })
                addView(TextView(this@ItemsActivity).apply {
                    text = u.name
                    textSize = 14f
                    setTextColor(Color.parseColor(textDark))
                    setTypeface(typeface, Typeface.BOLD)
                })
            })
        }
    }

    private fun promptAddUnit() {
        val input = EditText(this).apply { setPadding(32, 24, 32, 24) }
        AlertDialog.Builder(this)
            .setTitle("New Unit")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) lifecycleScope.launch {
                    PosDatabase.get(this@ItemsActivity).unitDao().insert(UnitType(v))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ================= UI HELPERS =================
    private fun emptyState(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13.5f
        setTextColor(Color.parseColor(textGray))
        gravity = Gravity.CENTER
        setPadding(0, 60, 0, 0)
    }

    private fun tabButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 12.5f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 20, 0, 20)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { onClick() }
    }

    private fun circleIcon(label: String, colorHex: String, sizeDp: Int) = TextView(this).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        background = ovalBg(colorHex)
        val px = (sizeDp * resources.displayMetrics.density).toInt()
        width = px; height = px
    }

    private fun ovalBg(colorHex: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(colorHex))
    }

    private fun roundedBg(colorHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(colorHex))
        cornerRadius = radius.toFloat()
    }

    private fun strokedBg(strokeHex: String, fillHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(fillHex))
        setStroke((1.4 * resources.displayMetrics.density).toInt(), Color.parseColor(strokeHex))
        cornerRadius = radius.toFloat()
    }

    private fun applyElevation(view: View, dp: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.elevation = dp * resources.displayMetrics.density
            view.outlineProvider = ViewOutlineProvider.BACKGROUND
        }
    }

    private fun EditText.addTextChangedListener(onChanged: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { onChanged(s?.toString() ?: "") }
        })
    }
}
