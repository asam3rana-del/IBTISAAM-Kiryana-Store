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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.Category
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Product
import com.grocerypos.v11.UnitType
import com.grocerypos.v11.formatStockBreakdown
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * "Items" hub — three tabs: Products, Categories, Units.
 * Each tab has its own search box, list, and a floating "+ Add ..." button.
 *
 * Categories tab now supports drilling into a category (tap the row) to see
 * every product inside it, with per-product Edit / Change Category / Delete
 * actions, plus Edit (rename) and Delete on the category row itself.
 */
class ItemsActivity : AppCompatActivity() {

    // ================= PREMIUM COLOR PALETTE (matches Settings / Product) =================
    // NOTE: bg switched from lavender to plain white — reskin request to match a
    // reference screenshot's flat white look. Logic/behavior below is unchanged.
    private val bg = "#FFFFFF"
    private val cardBg = "#FFFFFF"
    private val tagBg = "#F1F0F5"
    private val primary = "#4A3AFF"
    private val primaryDark = "#3527D6"
    private val red = "#E5484D"
    private val redDark = "#C93A3E"
    private val purple = "#8B5CF6"
    private val amber = "#F5A524"
    private val teal = "#0F9B8E"
    private val textDark = "#1A1A2E"
    private val textGray = "#8A8A9E"
    private val border = "#E7E5F3"

    private enum class Tab { PRODUCTS, CATEGORIES, UNITS }
    private var currentTab = Tab.PRODUCTS

    private var allProducts: List<Product> = emptyList()
    private var allCategories: List<Category> = emptyList()
    private var allUnits: List<UnitType> = emptyList()
    private var searchQuery = ""

    // ---- NEW: category drill-down state. When non-null, Categories tab shows
    // that category's products instead of the category list. "" (empty string)
    // means the special "Items Not in Any Category" bucket. ----
    private var openCategoryName: String? = null

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
        // Restyled to a flat white bar (was a purple gradient) to match the reference
        // screenshot's look. Same title/subtitle/back-behavior as before.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 18, 20, 18)
            background = strokedBg(border, cardBg, 18)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 18) }
            applyElevation(this, 3f)
        }
        header.addView(circleIcon("🗃️", "#EFECFF", 42))
        header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(16, 1) })
        val headerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerCol.addView(TextView(this).apply {
            text = "Items"
            textSize = 20f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, Typeface.BOLD)
        })
        headerCol.addView(TextView(this).apply {
            text = "Products, Categories & Units"
            textSize = 11.5f
            setTextColor(Color.parseColor(textGray))
            setPadding(0, 4, 0, 0)
        })
        header.addView(headerCol)

        // ---- NEW: "Translate" pill button — launches BulkTranslateActivity so Urdu
        // category/unit values already saved can be renamed to English once each,
        // instead of editing every product individually. ----
        header.addView(TextView(this).apply {
            text = "🌐 Translate"
            textSize = 11f
            setTextColor(Color.parseColor(primary))
            setTypeface(typeface, Typeface.BOLD)
            background = strokedBg(primary, "#EFECFF", 30)
            setPadding(18, 12, 18, 12)
            setOnClickListener {
                startActivity(Intent(this@ItemsActivity, BulkTranslateActivity::class.java))
            }
        })

        root.addView(header)

        // ================= TABS =================
        // Restyled as separate outlined pills (was one filled segmented control) to
        // match the reference screenshot's Parties/Transactions/Items look.
        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
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

    override fun onBackPressed() {
        // ---- NEW: if a category is drilled into, Back closes the drill-down
        // first instead of leaving the screen. ----
        if (currentTab == Tab.CATEGORIES && openCategoryName != null) {
            closeCategoryDetail()
            return
        }
        super.onBackPressed()
    }

    // ================= TAB SWITCHING =================
    private fun switchTab(tab: Tab) {
        currentTab = tab
        openCategoryName = null
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
        fab.visibility = View.VISIBLE

        productsTabBtn.background = strokedBg(border, cardBg, 30)
        categoriesTabBtn.background = strokedBg(border, cardBg, 30)
        unitsTabBtn.background = strokedBg(border, cardBg, 30)
        productsTabBtn.setTextColor(Color.parseColor(textGray))
        categoriesTabBtn.setTextColor(Color.parseColor(textGray))
        unitsTabBtn.setTextColor(Color.parseColor(textGray))

        val selected = when (tab) {
            Tab.PRODUCTS -> productsTabBtn
            Tab.CATEGORIES -> categoriesTabBtn
            Tab.UNITS -> unitsTabBtn
        }
        selected.background = strokedBg(red, "#FDE8E8", 30)
        selected.setTextColor(Color.parseColor(red))

        renderCurrentTab()
    }

    private fun closeCategoryDetail() {
        openCategoryName = null
        searchQuery = ""
        searchField.setText("")
        searchField.hint = "Search Category"
        fab.text = "＋  Add Category"
        fab.visibility = View.VISIBLE
        renderCurrentTab()
    }

    private fun onFabClicked() {
        if (currentTab == Tab.CATEGORIES && openCategoryName != null) {
            // Inside a category's product list — FAB adds a new product
            // (category can be set on the product form itself).
            startActivity(Intent(this, ProductActivity::class.java))
            return
        }
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
                if (currentTab == Tab.PRODUCTS || currentTab == Tab.CATEGORIES) renderCurrentTab()
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
            Tab.CATEGORIES -> {
                val open = openCategoryName
                if (open != null) renderCategoryDetail(open) else renderCategories()
            }
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

                // Top row: name on the left, category tag + share icon on the right —
                // matches the reference screenshot's item row layout.
                val topRow = LinearLayout(this@ItemsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                topRow.addView(TextView(this@ItemsActivity).apply {
                    text = p.name
                    textSize = 14.5f
                    setTextColor(Color.parseColor(textDark))
                    setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (p.category.isNotBlank()) {
                    topRow.addView(TextView(this@ItemsActivity).apply {
                        text = p.category
                        textSize = 10f
                        setTextColor(Color.parseColor(textGray))
                        setTypeface(typeface, Typeface.BOLD)
                        background = roundedBg(tagBg, 8)
                        setPadding(14, 6, 14, 6)
                    })
                }
                topRow.addView(TextView(this@ItemsActivity).apply {
                    text = "\u27A4"
                    textSize = 13f
                    setTextColor(Color.parseColor(textGray))
                    setPadding(20, 0, 0, 0)
                    setOnClickListener {
                        val summary = "${p.name}\nSale Price: Rs %.2f\nPurchase Price: Rs %.2f".format(p.salePrice, p.cost)
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, summary)
                        }, null))
                    }
                })
                addView(topRow)

                val priceRow = LinearLayout(this@ItemsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 10, 0, 0)
                }
                priceRow.addView(priceCol("Sale Price", p.salePrice))
                priceRow.addView(priceCol("Purchase Price", p.cost))
                addView(priceRow)

                setOnClickListener {
                    startActivity(Intent(this@ItemsActivity, ProductActivity::class.java).apply {
                        putExtra(ProductActivity.EXTRA_EDIT_BARCODE, p.barcode)
                    })
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

    // ================= CATEGORIES TAB (list) =================
    private fun renderCategories() {
        val counts = allProducts.groupingBy { it.category.ifBlank { "" } }.eachCount()
        val notCategorized = counts[""] ?: 0

        // name to (count, isSpecialUncategorizedBucket)
        val rows = mutableListOf<Triple<String, Int, Boolean>>()
        rows.add(Triple("Items Not in Any Category", notCategorized, true))
        for (c in allCategories) {
            rows.add(Triple(c.name, counts[c.name] ?: 0, false))
        }

        val filtered = if (searchQuery.isEmpty()) rows
        else rows.filter { it.first.contains(searchQuery, ignoreCase = true) }

        if (filtered.isEmpty()) {
            listContainer.addView(emptyState("Koi category nahi mili"))
            return
        }

        for ((name, count, isUncategorized) in filtered) {
            listContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(20, 18, 20, 18)
                background = strokedBg(border, cardBg, 14)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 10) }

                // ---- Tap anywhere on the row (except the edit/delete icons) to
                // drill into that category's products. ----
                setOnClickListener {
                    openCategoryName = if (isUncategorized) "" else name
                    searchQuery = ""
                    searchField.setText("")
                    searchField.hint = "Search Items in \"$name\""
                    fab.text = "＋  Add Product"
                    renderCurrentTab()
                }

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

                // ---- Real categories (not the "Items Not in Any Category"
                // bucket) get Edit (rename) and Delete icons. ----
                if (!isUncategorized) {
                    val category = allCategories.first { it.name == name }
                    addView(View(this@ItemsActivity).apply { layoutParams = LinearLayout.LayoutParams(14, 1) })
                    addView(TextView(this@ItemsActivity).apply {
                        text = "✏️"
                        textSize = 15f
                        setPadding(14, 8, 14, 8)
                        setOnClickListener { promptEditCategory(category) }
                    })
                    addView(TextView(this@ItemsActivity).apply {
                        text = "🗑️"
                        textSize = 15f
                        setPadding(14, 8, 14, 8)
                        setOnClickListener { confirmDeleteCategory(category, count) }
                    })
                }
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

    // ---- NEW: rename a category. Renaming also updates every product
    // currently tagged with the old name, so nothing silently becomes
    // "uncategorized" just because the category was renamed. ----
    private fun promptEditCategory(category: Category) {
        val input = EditText(this).apply {
            setPadding(32, 24, 32, 24)
            setText(category.name)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename Category")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty() || newName == category.name) return@setPositiveButton
                lifecycleScope.launch {
                    val db = PosDatabase.get(this@ItemsActivity)
                    // Category's primary key IS its name, so "renaming" means:
                    // add the new name, repoint every product that used the old
                    // name (existing helper — already used by BulkTranslateActivity),
                    // then remove the old category row.
                    db.categoryDao().insert(Category(newName))
                    db.productDao().renameCategoryInProducts(category.name, newName)
                    db.categoryDao().deleteByName(category.name)
                    Toast.makeText(this@ItemsActivity, "Category renamed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- NEW: delete a category. If it still has products in it, they are
    // moved to "Items Not in Any Category" instead of being deleted, and the
    // user is warned about this before confirming. ----
    private fun confirmDeleteCategory(category: Category, productCount: Int) {
        val message = if (productCount > 0)
            "\"${category.name}\" has $productCount item(s). Deleting it will move them to \"Items Not in Any Category\". Continue?"
        else
            "Delete \"${category.name}\"? This cannot be undone."
        AlertDialog.Builder(this)
            .setTitle("Delete Category")
            .setMessage(message)
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    val db = PosDatabase.get(this@ItemsActivity)
                    if (productCount > 0) db.productDao().renameCategoryInProducts(category.name, "")
                    db.categoryDao().deleteByName(category.name)
                    Toast.makeText(this@ItemsActivity, "Category deleted", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ================= CATEGORIES TAB (drill-down: products inside one category) =================
    private fun renderCategoryDetail(categoryName: String) {
        val displayName = categoryName.ifBlank { "Items Not in Any Category" }

        // ---- Back row + category title ----
        listContainer.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 0, 4, 16)
            addView(TextView(this@ItemsActivity).apply {
                text = "←  Categories"
                textSize = 13f
                setTextColor(Color.parseColor(primary))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(10, 10, 20, 10)
                setOnClickListener { closeCategoryDetail() }
            })
        })
        listContainer.addView(TextView(this).apply {
            text = displayName
            textSize = 17f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(4, 0, 4, 16)
        })

        val inCategory = allProducts.filter { it.category.ifBlank { "" } == categoryName }
        val filtered = if (searchQuery.isEmpty()) inCategory
        else inCategory.filter { it.name.contains(searchQuery, ignoreCase = true) }

        if (filtered.isEmpty()) {
            listContainer.addView(emptyState(if (inCategory.isEmpty()) "Is category mein koi item nahi" else "Koi matching item nahi mila"))
            return
        }

        for (p in filtered) {
            listContainer.addView(categoryProductRow(p))
        }
    }

    private fun categoryProductRow(p: Product) = LinearLayout(this).apply {
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

        addView(TextView(this@ItemsActivity).apply {
            text = "📊 Stock: ${p.formatStockBreakdown()}"
            textSize = 12f
            setTextColor(Color.parseColor(textGray))
            setPadding(0, 6, 0, 0)
        })

        val priceRow = LinearLayout(this@ItemsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 0)
        }
        priceRow.addView(priceCol("Sale Price", p.salePrice))
        priceRow.addView(priceCol("Purchase Price", p.cost))
        addView(priceRow)

        val actionsRow = LinearLayout(this@ItemsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 0)
        }
        actionsRow.addView(TextView(this@ItemsActivity).apply {
            text = "✏️  Edit"
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg(primary, 30)
            setPadding(20, 10, 20, 10)
            setOnClickListener {
                startActivity(Intent(this@ItemsActivity, ProductActivity::class.java).apply {
                    putExtra(ProductActivity.EXTRA_EDIT_BARCODE, p.barcode)
                })
            }
        })
        actionsRow.addView(View(this@ItemsActivity).apply { layoutParams = LinearLayout.LayoutParams(8, 1) })
        actionsRow.addView(TextView(this@ItemsActivity).apply {
            text = "🔀  Change Category"
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg(teal, 30)
            setPadding(20, 10, 20, 10)
            setOnClickListener { promptChangeProductCategory(p) }
        })
        actionsRow.addView(View(this@ItemsActivity).apply { layoutParams = LinearLayout.LayoutParams(8, 1) })
        actionsRow.addView(TextView(this@ItemsActivity).apply {
            text = "🗑️  Delete"
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg(red, 30)
            setPadding(20, 10, 20, 10)
            setOnClickListener { confirmDeleteProduct(p) }
        })
        addView(actionsRow)
    }

    // ---- NEW: change which category a single product belongs to, from a
    // simple pick-list of all existing categories plus "Items Not in Any
    // Category". Updates the product row immediately and, since the product
    // list is a live Flow, it disappears from the current category's view
    // right away if a different category was picked. ----
    private fun promptChangeProductCategory(p: Product) {
        val options = mutableListOf("Items Not in Any Category")
        options.addAll(allCategories.map { it.name })

        AlertDialog.Builder(this)
            .setTitle("Move \"${p.name}\" to")
            .setItems(options.toTypedArray()) { _, index ->
                val newCategory = if (index == 0) "" else options[index]
                lifecycleScope.launch {
                    PosDatabase.get(this@ItemsActivity).productDao().upsert(p.copy(category = newCategory))
                    Toast.makeText(
                        this@ItemsActivity,
                        "\"${p.name}\" moved to ${newCategory.ifBlank { "Items Not in Any Category" }}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteProduct(p: Product) {
        AlertDialog.Builder(this)
            .setTitle("Delete Product")
            .setMessage("Delete \"${p.name}\"? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    PosDatabase.get(this@ItemsActivity).productDao().delete(p)
                    Toast.makeText(this@ItemsActivity, "Product deleted", Toast.LENGTH_SHORT).show()
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
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })

                // ---- Delete button for this unit ----
                addView(TextView(this@ItemsActivity).apply {
                    text = "🗑️  Delete"
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, Typeface.BOLD)
                    background = roundedBg(red, 30)
                    setPadding(22, 10, 22, 10)
                    setOnClickListener { confirmDeleteUnit(u) }
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

    private fun confirmDeleteUnit(u: UnitType) {
        AlertDialog.Builder(this)
            .setTitle("Delete Unit")
            .setMessage("Delete \"${u.name}\"? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    PosDatabase.get(this@ItemsActivity).unitDao().delete(u)
                    Toast.makeText(this@ItemsActivity, "Unit deleted", Toast.LENGTH_SHORT).show()
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
        textSize = 12f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 20, 0, 20)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = 8
        }
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
