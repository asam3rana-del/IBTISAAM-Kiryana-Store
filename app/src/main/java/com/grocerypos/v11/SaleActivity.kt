package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Product
import com.grocerypos.v11.SaleItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SaleActivity : AppCompatActivity() {

    private val tealStart = "#14B8A6"
    private val tealEnd = "#0F766E"
    private val bg = "#F0FDFA"
    private val textDark = "#0F172A"
    private val textGray = "#64748B"
    private val border = "#CCFBF1"
    private val lightTeal = "#F0FDFA"
    private val orangeStart = "#F59E0B"
    private val orangeEnd = "#EF4444"

    private lateinit var customerField: TextView
    private lateinit var productField: TextView
    private lateinit var unitInfo: TextView
    private lateinit var qtyField: EditText
    private lateinit var qtyTypeField: TextView
    private lateinit var priceTypeField: TextView
    private lateinit var priceField: EditText
    private lateinit var totalField: TextView
    private lateinit var cartContainer: LinearLayout
    private lateinit var grandTotalField: TextView

    private var selectedProduct: Product? = null
    private var selectedCustomer = "Walk-in Customer"
    private var selectedQtyType = "dabbi"
    private var selectedPriceType = "Retail"
    private var cart = mutableListOf<SaleItem>()
    private var allProducts: List<Product> = emptyList()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor(bg)) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22,26,22,26)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(orangeStart), Color.parseColor(tealStart))).apply { cornerRadii = floatArrayOf(0f,0f,0f,0f,0f,0f,32f,32f) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { elevation = 10f }
        }
        header.addView(TextView(this).apply { text = "🧾 Sale - Ultra Premium"; textSize = 20f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
        outer.addView(header)

        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(-1,0,1f) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16,18,16,24) }

        val custCard = ultraCard("👤", "Customer", "#8B5CF6", "#EC4899")
        customerField = TextView(this).apply { text = "👤 Customer: Walk-in Customer"; textSize = 13.5f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD); setPadding(18,16,18,16); background = GradientDrawable().apply { setColor(Color.parseColor(lightTeal)); cornerRadius = 14f; setStroke(2, Color.parseColor(border)) }; setOnClickListener { pickCustomer() } }
        custCard.addView(customerField); root.addView(custCard); root.addView(spacer(16))

        val prodCard = ultraCard("📦", "Product - Category + Stock", "#F59E0B", "#EF4444")
        productField = TextView(this).apply { text = "📦 Select Product for Sale"; textSize = 13.5f; setTextColor(Color.parseColor("#94A3B8")); setTypeface(typeface, Typeface.BOLD); setPadding(18,16,18,16); background = GradientDrawable().apply { setColor(Color.parseColor(lightTeal)); cornerRadius = 14f; setStroke(2, Color.parseColor(border)) }; setOnClickListener { pickProduct() } }
        prodCard.addView(productField); prodCard.addView(spacer(8))
        unitInfo = TextView(this).apply { text = "📐 Stock: 0 | Ctn 50 Outer 10 Dabbi"; textSize = 10.5f; setTextColor(Color.parseColor(tealStart)) }
        prodCard.addView(unitInfo); root.addView(prodCard); root.addView(spacer(16))

        val qtyCard = ultraCard("🔢", "Quantity & Price Type", "#06B6D4", "#3B82F6")
        val qtyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0,10,0,0) }
        qtyField = EditText(this).apply { hint = "Qty"; textSize = 14f; inputType = InputType.TYPE_CLASS_NUMBER; setPadding(18,16,18,16); background = GradientDrawable().apply { setColor(Color.parseColor(lightTeal)); cornerRadius = 14f; setStroke(2, Color.parseColor(border)) }; layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(0,0,8,0) }; addTextChangedListener(object : android.text.TextWatcher { override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}; override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}; override fun afterTextChanged(s: android.text.Editable?) { calcTotal() } }) }
        qtyTypeField = TextView(this).apply { text = "📦 dabbi"; textSize = 11f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(14,16,14,16); background = GradientDrawable().apply { setColor(Color.parseColor(tealStart)); cornerRadius = 14f }; layoutParams = LinearLayout.LayoutParams(-2,-2).apply { setMargins(0,0,8,0) }; setOnClickListener { pickQtyType() } }
        priceTypeField = TextView(this).apply { text = "🏪 Retail"; textSize = 11f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(14,16,14,16); background = GradientDrawable().apply { setColor(Color.parseColor(orangeStart)); cornerRadius = 14f }; setOnClickListener { pickPriceType() } }
        qtyRow.addView(qtyField); qtyRow.addView(qtyTypeField); qtyRow.addView(priceTypeField)
        qtyCard.addView(qtyRow); root.addView(qtyCard); root.addView(spacer(16))

        val priceCard = ultraCard("💰", "Price & Total", "#10B981", "#06B6D4")
        priceField = EditText(this).apply { hint = "Price"; textSize = 13f; inputType = InputType.TYPE_CLASS_NUMBER; setPadding(16,14,16,14); background = GradientDrawable().apply { setColor(Color.parseColor(lightTeal)); cornerRadius = 12f; setStroke(2, Color.parseColor(border)) }; addTextChangedListener(object : android.text.TextWatcher { override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}; override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}; override fun afterTextChanged(s: android.text.Editable?) { calcTotal() } }) }
        priceCard.addView(priceField); priceCard.addView(spacer(10))
        totalField = TextView(this).apply { text = "💵 Total: Rs 0"; textSize = 14f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(14,14,14,14); background = GradientDrawable().apply { setColor(Color.parseColor("#D1FAE5")); cornerRadius = 12f } }
        priceCard.addView(totalField); root.addView(priceCard); root.addView(spacer(16))

        val addBtn = TextView(this).apply { text = "➕ ADD TO CART"; textSize = 13f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(0,16,0,16); background = GradientDrawable().apply { setColor(Color.parseColor(orangeStart)); cornerRadius = 14f }; setOnClickListener { addToCart() } }
        root.addView(addBtn); root.addView(spacer(16))

        val cartCard = ultraCard("🛒", "Cart - Sale Items", "#14B8A6", "#0F766E")
        cartContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cartCard.addView(cartContainer); root.addView(cartCard); root.addView(spacer(12))

        grandTotalField = TextView(this).apply { text = "💰 Grand Total: Rs 0"; textSize = 16f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(0,18,0,18); background = GradientDrawable().apply { setColor(Color.parseColor(textDark)); cornerRadius = 16f } }
        root.addView(grandTotalField); root.addView(spacer(16))

        val saveBtn = TextView(this).apply { text = "✅ COMPLETE SALE"; textSize = 15f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(0,20,0,20); background = GradientDrawable().apply { setColor(Color.parseColor(tealStart)); cornerRadius = 18f }; setOnClickListener { saveSale() } }
        root.addView(saveBtn)

        scroll.addView(root); outer.addView(scroll); setContentView(outer)
        loadProducts()
    }

    private fun ultraCard(icon: String, title: String, c1: String, c2: String): LinearLayout {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(3,3,3,3); background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(c1), Color.parseColor(c2))).apply { cornerRadius = 22f } }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18,16,18,18); background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 19f } }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply { text = icon; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setPadding(10,10,10,10); background = GradientDrawable().apply { setColor(Color.parseColor(c1)); shape = GradientDrawable.OVAL }; layoutParams = LinearLayout.LayoutParams((36*resources.displayMetrics.density).toInt(), (36*resources.displayMetrics.density).toInt()) })
        head.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(10,1) }); head.addView(TextView(this).apply { text = title; textSize = 11f; setTextColor(Color.parseColor(textGray)); setTypeface(typeface, Typeface.BOLD) })
        inner.addView(head); outer.addView(inner); return inner
    }
    private fun spacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, (h*resources.displayMetrics.density).toInt()) }
    private fun pickCustomer() { val custs = arrayOf("Walk-in Customer","Regular","Wholesale Customer","Credit Customer"); AlertDialog.Builder(this).setTitle("👤 Select Customer").setItems(custs) { _, i -> selectedCustomer = custs[i]; customerField.text = "👤 Customer: " + selectedCustomer }.show() }
    private fun pickQtyType() { val types = arrayOf("ctn","outer/lari","dabbi/pcs"); AlertDialog.Builder(this).setTitle("📦 Qty Type").setItems(types) { _, i -> selectedQtyType = types[i]; qtyTypeField.text = "📦 " + types[i]; calcTotal() }.show() }
    private fun pickPriceType() { val types = arrayOf("Retail","Wholesale"); AlertDialog.Builder(this).setTitle("💰 Price Type").setItems(types) { _, i -> selectedPriceType = types[i]; if (types[i] == "Retail") { priceTypeField.text = "🏪 Retail" } else { priceTypeField.text = "📦 Wholesale" }; updatePrice(); calcTotal() }.show() }
    private fun pickProduct() {
        if (allProducts.isEmpty()) { Toast.makeText(this, "No products", Toast.LENGTH_SHORT).show(); return }
        val names = mutableListOf<String>()
        for (p in allProducts) { names.add(p.name + " - " + p.category + " | Stock:" + p.stock) }
        AlertDialog.Builder(this).setTitle("📦 Select Product").setItems(names.toTypedArray()) { _, i ->
            selectedProduct = allProducts[i]
            productField.text = "📦 " + selectedProduct!!.name + " | " + selectedProduct!!.category
            productField.setTextColor(Color.parseColor(textDark))
            unitInfo.text = "📦 Stock: " + selectedProduct!!.stock + " " + selectedProduct!!.tertiaryUnit + " | W:" + selectedProduct!!.wholesalePrice + " R:" + selectedProduct!!.salePrice
            updatePrice()
            calcTotal()
        }.show()
    }
    private fun updatePrice() {
        val p = selectedProduct?: return
        if (selectedPriceType == "Wholesale") { priceField.setText(p.wholesalePrice.toString()) } else { priceField.setText(p.salePrice.toString()) }
    }
    private fun calcTotal() {
        val p = selectedProduct?: return
        val qty = qtyField.text.toString().toDoubleOrNull()?: 0.0
        val price = priceField.text.toString().toDoubleOrNull()?: 0.0
        var totalDabbi = qty
        if (selectedQtyType.contains("ctn")) { totalDabbi = qty * p.secondaryUnitQty * p.tertiaryUnitQty } else if (selectedQtyType.contains("outer") || selectedQtyType.contains("lari")) { totalDabbi = qty * p.tertiaryUnitQty }
        val total = totalDabbi * price
        totalField.text = "💵 Total: Rs " + total + " (" + totalDabbi + " " + p.tertiaryUnit + ")"
    }
    private fun addToCart() {
        val p = selectedProduct?: return
        val qty = qtyField.text.toString().toDoubleOrNull()?: return
        val price = priceField.text.toString().toDoubleOrNull()?: 0.0
        var totalDabbi = qty
        if (selectedQtyType.contains("ctn")) { totalDabbi = qty * p.secondaryUnitQty * p.tertiaryUnitQty } else if (selectedQtyType.contains("outer")) { totalDabbi = qty * p.tertiaryUnitQty }
        if (totalDabbi > p.stock) { Toast.makeText(this, "Stock kam hai! Stock: " + p.stock, Toast.LENGTH_SHORT).show(); return }
        val item = SaleItem(barcode = p.barcode, name = p.name, qty = totalDabbi, price = price, total = totalDabbi * price, qtyType = selectedQtyType, priceType = selectedPriceType)
        cart.add(item); showCart(); qtyField.text.clear()
    }
    private fun showCart() {
        cartContainer.removeAllViews()
        var grand = 0.0
        for (item in cart) {
            grand += item.total
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(14,12,14,12); background = GradientDrawable().apply { setColor(Color.parseColor(lightTeal)); cornerRadius = 12f }; layoutParams = LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,0,0,8) } }
            row.addView(TextView(this).apply { text = item.name + " | " + item.qty + " " + item.qtyType + " | " + item.priceType + " @ " + item.price + " = Rs " + item.total; textSize = 12f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD) })
            cartContainer.addView(row)
        }
        grandTotalField.text = "💰 Grand Total: Rs " + grand + " | Items: " + cart.size
    }
    private fun saveSale() {
        if (cart.isEmpty()) { Toast.makeText(this, "Cart empty", Toast.LENGTH_SHORT).show(); return }
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SaleActivity)
            for (item in cart) {
                val prod = db.productDao().getByBarcode(item.barcode)
                if (prod!= null) {
                    val updated = prod.copy(stock = (prod.stock - item.qty.toInt()).coerceAtLeast(0))
                    db.productDao().upsert(updated)
                }
            }
            var total = 0.0
            for (it in cart) { total += it.total }
            Toast.makeText(this@SaleActivity, "✅ Sale Completed Rs " + total, Toast.LENGTH_LONG).show()
            cart.clear(); showCart()
        }
    }
    private fun loadProducts() { lifecycleScope.launch { allProducts = PosDatabase.get(this@SaleActivity).productDao().all().first() } }
}
