
package com.grocerypos.v11.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
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

    private lateinit var searchField: EditText
    private lateinit var productListBox: LinearLayout
    private lateinit var unitSpinner: Spinner
    private lateinit var qtyField: EditText
    private lateinit var costField: EditText
    private lateinit var cartContainer: LinearLayout
    private lateinit var totalView: TextView

    private var allProducts: List<Product> = emptyList()
    private var filtered: List<Product> = emptyList()
    private var selectedProduct: Product? = null

    data class PurchaseLine(
        val barcode: String,
        val name: String,
        val qty: Double,
        val unit: String,
        val qtyInSmallest: Int,
        val totalCost: Double
    )
    private val cart = mutableListOf<PurchaseLine>()

    private fun toUrdu(unit: String): String {
        val u = unit.trim().lowercase()
        return when(u) {
            "ctn", "carton" -> "کارٹن"
            "outer" -> "آؤٹر"
            "lari" -> "لڑی"
            "dabbi" -> "ڈبی"
            "pcs", "pc" -> "عدد"
            "gachi" -> "گچھی"
            "bag", "bori" -> "بوری"
            "kg", "kilo" -> "کلو"
            "gram", "g", "gm" -> "گرام"
            "pao" -> "پاؤ"
            "box" -> "باکس"
            "pet" -> "pet"
            else -> unit
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24,44,24,24)
            setBackgroundColor(Color.parseColor("#F4F6F8"))
        }

        root.addView(TextView(this).apply {
            text = "Purchase - 3 Tier Urdu"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0,0,0,16)
        })

        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = strokedBg("#E3E8EE","#FFFFFF",12)
            setPadding(18,4,18,4)
        }
        searchField = EditText(this).apply {
            hint = "Search product..."
            background = null
            layoutParams = LinearLayout.LayoutParams(0,-2,1f)
        }
        searchBox.addView(searchField)
        root.addView(searchBox)
        root.addView(spacer(10))

        productListBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(productListBox)
        root.addView(spacer(10))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        qtyField = EditText(this).apply {
            hint = "Qty"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            background = strokedBg("#E3E8EE","#FFFFFF",12)
            setPadding(16,14,16,14)
            layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply{ setMargins(0,0,6,0) }
        }
        unitSpinner = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(0,-2,1.2f).apply{ setMargins(6,0,6,0) }
        }
        costField = EditText(this).apply {
            hint = "Rate"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            background = strokedBg("#E3E8EE","#FFFFFF",12)
            setPadding(16,14,16,14)
            layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply{ setMargins(6,0,0,0) }
        }
        row.addView(qtyField)
        row.addView(unitSpinner)
        row.addView(costField)
        root.addView(row)
        root.addView(spacer(10))

        val addButton = TextView(this).apply {
            text = "Add to Cart"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBg("#0F9B8E",12)
            setPadding(0,18,0,18)
            setOnClickListener { addToCart() }
        }
        root.addView(addButton)
        root.addView(spacer(12))

        cartContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(cartContainer) }
        root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))

        totalView = TextView(this).apply {
            text = "Total: Rs 0"
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(totalView)
        root.addView(spacer(10))

        val saveButton = Button(this).apply {
            text = "Save Purchase"
            setTextColor(Color.WHITE)
            background = roundedBg("#0B2545",16)
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
        if (list.isEmpty()) {
            list.addAll(listOf("ctn","outer","dabbi"))
        }
        return list
    }

    private fun convertToSmallest(p: Product, qty: Double, unit: String): Int {
        val u = unit.lowercase().trim()
        val primary = p.unit.lowercase().trim()
        val secondary = p.secondaryUnit.lowercase().trim()
        val tertiary = p.tertiaryUnit.lowercase().trim()
        val secQty = p.secondaryUnitQty
        val terQty = p.tertiaryUnitQty

        if (u == tertiary) {
            return qty.toInt()
        }
        if (u == secondary) {
            if (terQty > 0) return (qty * terQty).toInt()
            return qty.toInt()
        }
        if (u == primary) {
            if (secQty > 0 && terQty > 0) return (qty * secQty * terQty).toInt()
            if (secQty > 0) return (qty * secQty).toInt()
            return qty.toInt()
        }
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
                setPadding(18,14,18,14)
                background = strokedBg("#E3E8EE","#FFFFFF",10)
                setOnClickListener {
                    selectedProduct = p
                    searchField.setText(p.name)
                    val units = getUnitsForProduct(p)
                    val displayUnits = units.map { toUrdu(it) + " (" + it + ")" }
                    unitSpinner.adapter = ArrayAdapter(this@PurchaseActivity, android.R.layout.simple_spinner_dropdown_item, displayUnits)
                    val msg = p.name + " stock " + p.stock
                    Toast.makeText(this@PurchaseActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
            val nameView = TextView(this).apply {
                text = p.name + " - stock " + p.stock + " " + toUrdu(p.tertiaryUnit)
                setTypeface(typeface, Typeface.BOLD)
            }
            val detailView = TextView(this).apply {
                text = toUrdu(p.unit) + " " + p.secondaryUnitQty.toInt() + " " + toUrdu(p.secondaryUnit) + " " + p.tertiaryUnitQty.toInt() + " " + toUrdu(p.tertiaryUnit)
                textSize = 12f
            }
            row.addView(nameView)
            row.addView(detailView)
            productListBox.addView(row)
            productListBox.addView(spacer(6))
        }
    }

    private fun addToCart() {
        val p = selectedProduct
        if (p == null) {
            Toast.makeText(this, "Select product", Toast.LENGTH_SHORT).show()
            return
        }
        val qty = qtyField.text.toString().toDoubleOrNull() ?: 0.0
        if (qty <= 0) return
        val unitRaw = unitSpinner.selectedItem?.toString() ?: p.unit
        var unit = unitRaw
        if (unitRaw.contains("(") && unitRaw.contains(")")) {
            unit = unitRaw.substringAfter("(").substringBefore(")").trim()
        }
        val cost = costField.text.toString().toDoubleOrNull() ?: 0.0
        val qtySmallest = convertToSmallest(p, qty, unit)
        cart.add(PurchaseLine(p.barcode, p.name, qty, unit, qtySmallest, qty * cost))
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
                setPadding(14,10,14,10)
                background = strokedBg("#E3E8EE","#FAFBFC",8)
            }
            val tv = TextView(this@PurchaseActivity).apply {
                val unitUrdu = toUrdu(line.unit)
                val msg = line.name + " " + line.qty + " " + unitUrdu + " = " + line.qtyInSmallest + " - Rs " + line.totalCost
                text = msg
                layoutParams = LinearLayout.LayoutParams(0,-2,1f)
            }
            val del = TextView(this@PurchaseActivity).apply {
                text = "X"
                setPadding(12,0,12,0)
                setOnClickListener { cart.remove(line); renderCart() }
            }
            row.addView(tv)
            row.addView(del)
            cartContainer.addView(row)
            cartContainer.addView(spacer(6))
        }
        totalView.text = "Total: Rs " + total
    }

    private fun savePurchase() {
        if (cart.isEmpty()) {
            Toast.makeText(this, "Cart empty", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PurchaseActivity)
            for (line in cart) {
                db.productDao().increaseStock(line.barcode, line.qtyInSmallest)
            }
            Toast.makeText(this@PurchaseActivity, "Purchase saved", Toast.LENGTH_LONG).show()
            cart.clear()
            renderCart()
            loadProducts()
        }
    }

    private fun roundedBg(colorHex: String, radius: Int) = GradientDrawable().apply { setColor(Color.parseColor(colorHex)); cornerRadius = radius.toFloat() }
    private fun strokedBg(strokeHex: String, fillHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(fillHex))
        setStroke(2, Color.parseColor(strokeHex))
        cornerRadius = radius.toFloat()
    }
    private fun spacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, (h*3)) }
}
