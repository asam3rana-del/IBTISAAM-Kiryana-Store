package com.grocerypos.v11.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Product
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PurchaseActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BILL_NO = "bill_no"
    }

    private val bg = "#F4F6F8"
    private val navy = "#0B2545"
    private val teal = "#0F9B8E"
    private val cardWhite = "#FFFFFF"
    private val border = "#E3E8EE"
    private val textMuted = "#7C8798"

    private lateinit var searchField: EditText
    private lateinit var productListBox: LinearLayout
    private lateinit var unitSpinner: Spinner
    private lateinit var qtyField: EditText
    private lateinit var costField: EditText
    private lateinit var addButton: TextView
    private lateinit var cartContainer: LinearLayout
    private lateinit var totalView: TextView
    private lateinit var saveButton: Button

    private var allProducts: List<Product> = emptyList()
    private var filtered: List<Product> = emptyList()
    private var selectedProduct: Product? = null
    private val cart = mutableListOf<PurchaseLine>()

    private val urduMap = mapOf(
        "ctn" to "کارٹن",
        "carton" to "کارٹن",
        "outer" to "آؤٹر",
        "lari" to "لڑی",
        "dabbi" to "ڈبی",
        "pcs" to "عدد",
        "gachi" to "گچھی",
        "bag" to "بوری",
        "kg" to "کلو",
        "gram" to "گرام",
        "g" to "گرام",
        "pao" to "پاؤ",
        "box" to "باکس",
        "bottle" to "بوتل",
        "pet" to "pet",
        "پیٹ" to "pet"
    )

    private fun toUrdu(unit: String): String {
        if (unit.isBlank()) return ""
        return urduMap[unit.lowercase()] ?: unit
    }

    data class PurchaseLine(
        val barcode: String,
        val name: String,
        val qty: Double,
        val unit: String,
        val qtyInSmallest: Int,
        val costPerUnit: Double,
        val totalCost: Double
    )

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 44, 24, 24)
            setBackgroundColor(Color.parseColor(bg))
        }

        val header = LinearLayout(this).apply {
            setPadding(26, 22, 22, 22)
            background = roundedBg(navy, 20)
        }
        header.addView(TextView(this).apply {
            text = "📦 خریداری - 3 درجہ یونٹ (آؤٹر)"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(header)
        root.addView(spacer(12))

        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = strokedBg(border, cardWhite, 12)
            setPadding(18, 4, 18, 4)
        }
        searchBox.addView(TextView(this).apply { text = "🔍 " })
        searchField = EditText(this).apply {
            hint = "پروڈکٹ تلاش کریں..."
            background = null
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        searchBox.addView(searchField)
        root.addView(searchBox)
        root.addView(spacer(10))

        productListBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(productListBox)
        root.addView(spacer(12))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        qtyField = EditText(this).apply {
            hint = "مقدار"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            background = strokedBg(border, cardWhite, 12)
            setPadding(16, 14, 16, 14)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,6,0) }
        }
        unitSpinner = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f).apply { setMargins(6,0,6,0) }
        }
        costField = EditText(this).apply {
            hint = "فی یونٹ قیمت"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            background = strokedBg(border, cardWhite, 12)
            setPadding(16, 14, 16, 14)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6,0,0,0) }
        }
        row.addView(qtyField)
        row.addView(unitSpinner)
        row.addView(costField)
        root.addView(row)
        root.addView(spacer(10))

        addButton = TextView(this).apply {
            text = "➕ کارٹ میں شامل کریں"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBg(teal, 12)
            setPadding(0, 18, 0, 18)
            setOnClickListener { addToCart() }
        }
        root.addView(addButton)
        root.addView(spacer(12))

        cartContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(cartContainer) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        totalView = TextView(this).apply {
            text = "ٹوٹل: Rs 0"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(totalView)
        root.addView(spacer(10))

        saveButton = Button(this).apply {
            text = "💾 خریداری محفوظ کریں"
            setTextColor(Color.WHITE)
            background = roundedBg(navy, 16)
            setOnClickListener { savePurchase() }
        }
        root.addView(saveButton)

        setContentView(root)
        loadProducts()
        setupSearch()
    }

    private fun getUnitsForProduct(p: Product): List<String> {
        val list = mutableListOf<String>()
        if (p.unit.isNotBlank()) list.add(p.unit)
        if (p.secondaryUnit.isNotBlank()) list.add(p.secondaryUnit)
        if (p.tertiaryUnit.isNotBlank()) list.add(p.tertiaryUnit)
        if (list.isEmpty()) list.addAll(listOf("کارٹن","آؤٹر","ڈبی","بوری","کلو","گرام","عدد"))
        return list
    }

    private fun convertToSmallest(p: Product, qty: Double, unit: String): Int {
        val u = unit.lowercase().trim()
        val primary = p.unit.lowercase().trim()
        val secondary = p.secondaryUnit.lowercase().trim()
        val tertiary = p.tertiaryUnit.lowercase().trim()
        val secQty = p.secondaryUnitQty
        val terQty = p.tertiaryUnitQty
        if (u == tertiary || (tertiary.isEmpty() && u == secondary)) {
            return qty.toInt()
        }
        if (u == secondary) {
            return if (terQty > 0) (qty * terQty).toInt() else qty.toInt()
        }
        if (u == primary) {
            return if (secQty > 0 && terQty > 0) (qty * secQty * terQty).toInt()
            else if (secQty > 0) (qty * secQty).toInt()
            else qty.toInt()
        }
        val engUnit = urduMap.entries.find { it.value == unit }?.key ?: unit
        if (engUnit != unit) return convertToSmallest(p, qty, engUnit)
        return qty.toInt()
    }

    private fun loadProducts() {
        lifecycleScope.launch {
            PosDatabase.get(this@PurchaseActivity).productDao().all().collectLatest { list ->
                allProducts = list
                filtered = list
                renderProducts()
            }
        }
    }

    private fun setupSearch() {
        searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s.toString().trim().lowercase()
                filtered = if (q.isEmpty()) allProducts else allProducts.filter { it.name.lowercase().contains(q) }
                renderProducts()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun renderProducts() {
        productListBox.removeAllViews()
        for (p in filtered.take(6)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(18, 14, 18, 14)
                background = strokedBg(border, cardWhite, 10)
                setOnClickListener {
                    selectedProduct = p
                    searchField.setText(p.name)
                    val units = getUnitsForProduct(p)
                    val urduUnits = units.map { u -> toUrdu(u) + " (" + u + ")" }
                    unitSpinner.adapter = ArrayAdapter(this@PurchaseActivity, android.R.layout.simple_spinner_dropdown_item, urduUnits)
                    val stockText = formatStock(p)
                    Toast.makeText(this@PurchaseActivity, p.name + " اسٹاک: " + stockText, Toast.LENGTH_LONG).show()
                }
            }
            row.addView(TextView(this).apply {
                text = p.name + " - " + formatStock(p)
                setTypeface(typeface, Typeface.BOLD)
            })
            row.addView(TextView(this).apply {
                val urduPri = toUrdu(p.unit)
                val urduSec = toUrdu(p.secondaryUnit)
                val urduTer = toUrdu(p.tertiaryUnit)
                text = urduPri + " " + p.secondaryUnitQty.toInt() + " " + urduSec + " " + p.tertiaryUnitQty.toInt() + " " + urduTer
                setTextColor(Color.parseColor(textMuted))
                textSize = 12f
            })
            productListBox.addView(row)
            productListBox.addView(spacer(6))
        }
    }

    private fun formatStock(p: Product): String {
        val smallest = p.stock
        val secQty = p.secondaryUnitQty
        val terQty = p.tertiaryUnitQty
        if (secQty > 0 && terQty > 0) {
            val totalPerPrimary = (secQty * terQty).toInt()
            val primaryCount = smallest / totalPerPrimary
            val remAfterPrimary = smallest % totalPerPrimary
            val secondaryCount = remAfterPrimary / terQty.toInt()
            val tertiaryCount = remAfterPrimary % terQty.toInt()
            return primaryCount.toString() + " " + toUrdu(p.unit) + " " + secondaryCount + " " + toUrdu(p.secondaryUnit) + " " + tertiaryCount + " " + toUrdu(p.tertiaryUnit) + " (" + smallest + " " + toUrdu(p.tertiaryUnit) + ")"
        } else if (secQty > 0) {
            val primaryCount = smallest / secQty.toInt()
            val secondaryCount = smallest % secQty.toInt()
            return primaryCount.toString() + " " + toUrdu(p.unit) + " " + secondaryCount + " " + toUrdu(p.secondaryUnit)
        }
        val smallestUnit = p.tertiaryUnit.ifBlank { p.secondaryUnit.ifBlank { p.unit } }
        return smallest.toString() + " " + toUrdu(smallestUnit)
    }

    private fun addToCart() {
        val p = selectedProduct
        if (p == null) {
            Toast.makeText(this, "پہلے پروڈکٹ منتخب کریں", Toast.LENGTH_SHORT).show()
            return
        }
        val qty = qtyField.text.toString().toDoubleOrNull() ?: 0.0
        if (qty <= 0) {
            Toast.makeText(this, "مقدار لکھیں", Toast.LENGTH_SHORT).show()
            return
        }
        val unitRaw = unitSpinner.selectedItem?.toString() ?: p.unit
        val unit = if (unitRaw.contains("(") && unitRaw.contains(")")) {
            unitRaw.substringAfter("(").substringBefore(")").trim()
        } else unitRaw
        val cost = costField.text.toString().toDoubleOrNull() ?: 0.0
        val qtySmallest = convertToSmallest(p, qty, unit)
        cart.add(PurchaseLine(p.barcode, p.name, qty, unit, qtySmallest, cost, qty * cost))
        renderCart()
        qtyField.text.clear()
        costField.text.clear()
    }

    private fun renderCart() {
        cartContainer.removeAllViews()
        var total = 0.0
        for (line in cart) {
            total += line.totalCost
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(14, 10, 14, 10)
                background = strokedBg(border, "#FAFBFC", 8)
            }
            row.addView(TextView(this@PurchaseActivity).apply {
                val unitUrdu = toUrdu(line.unit)
                val terUrdu = toUrdu(selectedProduct?.tertiaryUnit ?: "")
                text = line.name + " " + line.qty + " " + unitUrdu + " = " + line.qtyInSmallest + " " + terUrdu + " - Rs " + line.totalCost
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val del = TextView(this@PurchaseActivity).apply {
                text = "❌"
                setPadding(12, 0, 12, 0)
                setOnClickListener { cart.remove(line); renderCart() }
            }
            row.addView(del)
            cartContainer.addView(row)
            cartContainer.addView(spacer(6))
        }
        totalView.text = "ٹوٹل: Rs " + total
    }

    private fun savePurchase() {
        if (cart.isEmpty()) {
            Toast.makeText(this, "کارٹ خالی ہے", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PurchaseActivity)
            for (line in cart) {
                db.productDao().increaseStock(line.barcode, line.qtyInSmallest)
            }
            Toast.makeText(this@PurchaseActivity, "خریداری محفوظ! اسٹاک چھوٹی یونٹ میں جمع ہو گیا", Toast.LENGTH_LONG).show()
            cart.clear()
            renderCart()
            loadProducts()
        }
    }

    private fun roundedBg(colorHex: String, radius: Int) = GradientDrawable().apply { setColor(Color.parseColor(colorHex)); cornerRadius = radius.toFloat() }
    private fun strokedBg(strokeHex: String, fillHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(fillHex)); setStroke((1.2 * resources.displayMetrics.density).toInt(), Color.parseColor(strokeHex)); cornerRadius = radius.toFloat()
    }
    private fun spacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (h * resources.displayMetrics.density).toInt()) }
}
