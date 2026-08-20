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
import com.grocerypos.v11.PurchaseItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PurchaseActivity : AppCompatActivity() {
    private val tealStart = "#14B8A6"
    private val tealEnd = "#0F766E"
    private val bg = "#F0FDFA"
    private val textDark = "#0F172A"
    private val textGray = "#64748B"
    private val border = "#CCFBF1"
    private val lightTeal = "#F0FDFA"

    private lateinit var supplierField: TextView
    private lateinit var productField: TextView
    private lateinit var unitInfo: TextView
    private lateinit var qtyField: EditText
    private lateinit var qtyTypeField: TextView
    private lateinit var costField: EditText
    private lateinit var wholesaleField: EditText
    private lateinit var retailField: EditText
    private lateinit var totalField: TextView
    private lateinit var cartContainer: LinearLayout
    private lateinit var grandTotalField: TextView

    private var selectedProduct: Product? = null
    private var selectedSupplier = "General Supplier"
    private var selectedQtyType = "dabbi"
    private var cart = mutableListOf<PurchaseItem>()
    private var allProducts: List<Product> = emptyList()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor(bg)) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(22,26,22,26)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(tealStart), Color.parseColor(tealEnd))).apply { cornerRadii = floatArrayOf(0f,0f,0f,0f,0f,0f,32f,32f) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { elevation = 10f }
        }
        header.addView(TextView(this).apply { text = "🛒 Purchase - Ultra Premium"; textSize = 20f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
        outer.addView(header)
        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(-1,0,1f) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16,18,16,24) }

        val supCard = ultraCard("🏭", "Supplier", "#6366F1", "#8B5CF6")
        supplierField = TextView(this).apply { text = "🏭 Supplier: General Supplier"; textSize = 13.5f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD); setPadding(18,16,18,16); background = GradientDrawable().apply { setColor(Color.parseColor(lightTeal)); cornerRadius = 14f; setStroke(2, Color.parseColor(border)) }; setOnClickListener { pickSupplier() } }
        supCard.addView(supplierField); root.addView(supCard); root.addView(spacer(16))

        val prodCard = ultraCard("📦", "Product - Category + Units", "#F59E0B", "#EF4444")
        productField = TextView(this).apply { text = "📦 Select Product"; textSize = 13.5f; setTextColor(Color.parseColor("#94A3B8")); setTypeface(typeface, Typeface.BOLD); setPadding(18,16,18,16); background = GradientDrawable().apply { setColor(Color.parseColor(lightTeal)); cornerRadius = 14f; setStroke(2, Color.parseColor(border)) }; setOnClickListener { pickProduct() } }
        prodCard.addView(productField); prodCard.addView(spacer(8))
        unitInfo = TextView(this).apply { text = "📐 Ctn 50 Outer 10 Dabbi = 500 Dabbi"; textSize = 10.5f; setTextColor(Color.parseColor(tealStart)) }
        prodCard.addView(unitInfo); root.addView(prodCard); root.addView(spacer(16))

        val qtyCard = ultraCard("🔢", "Quantity - Ctn / Outer / Dabbi", "#06B6D4", "#3B82F6")
        val qtyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0,10,0,0) }
        qtyField = EditText(this).apply { hint = "Qty"; textSize = 14f; inputType = InputType.TYPE_CLASS_NUMBER; setPadding(18,16,18,16); background = GradientDrawable().apply { setColor(Color.parseColor(lightTeal)); cornerRadius = 14f; setStroke(2, Color.parseColor(border)) }; layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(0,0,10,0) }; addTextChangedListener(object : android.text.TextWatcher { override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}; override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}; override fun afterTextChanged(s: android.text.Editable?) { calcTotal() } }) }
        qtyTypeField = TextView(this).apply { text = "📦 dabbi ▼"; textSize = 11f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(14,16,14,16); background = GradientDrawable().apply { setColor(Color.parseColor(tealStart)); cornerRadius = 14f }; setOnClickListener { pickQtyType() } }
        qtyRow.addView(qtyField); qtyRow.addView(qtyTypeField); qtyCard.addView(qtyRow); root.addView(qtyCard); root.addView(spacer(16))

        val rateCard = ultraCard("💰", "Rates - Cost / Wholesale / Retail", "#10B981", "#06B6D4")
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0,10,0,0) }
        costField = smallInput("Cost"); wholesaleField = smallInput("W-Sale"); retailField = smallInput("Retail")
        r1.addView(costField.apply { layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(0,0,8,0) } }); r1.addView(wholesaleField.apply { layoutParams = LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(0,0,8,0) } }); r1.addView(retailField.apply { layoutParams = LinearLayout.LayoutParams(0,-2,1f) })
        rateCard.addView(r1); rateCard.addView(spacer(10))
        totalField = TextView(this).apply { text = "💵 Total: Rs 0"; textSize = 14f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(14,14,14,14); background = GradientDrawable().apply { setColor(Color.parseColor("#FEF3C7")); cornerRadius = 12f } }
        rateCard.addView(totalField); root.addView(rateCard); root.addView(spacer(16))

        val addBtn = TextView(this).apply { text = "➕ ADD TO CART"; textSize = 13f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(0,16,0,16); background = GradientDrawable().apply { setColor(Color.parseColor("#F59E0B")); cornerRadius = 14f }; setOnClickListener { addToCart() } }
        root.addView(addBtn); root.addView(spacer(16))

        val cartCard = ultraCard("🛒", "Cart - Purchase Items", "#14B8A6", "#0F766E")
        cartContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cartCard.addView(cartContainer); root.addView(cartCard); root.addView(spacer(12))

        grandTotalField = TextView(this).apply { text = "💰 Grand Total: Rs 0"; textSize = 16f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(0,18,0,18); background = GradientDrawable().apply { setColor(Color.parseColor(textDark)); cornerRadius = 16f } }
        root.addView(grandTotalField); root.addView(spacer(16))

        val saveBtn = TextView(this).apply { text = "✅ SAVE PURCHASE - ULTRA"; textSize = 15f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(0,20,0,20); background = GradientDrawable().apply { setColor(Color.parseColor(tealStart)); cornerRadius = 18f }; setOnClickListener { savePurchase() } }
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
    private fun smallInput(h: String) = EditText(this).apply { hint = h; textSize = 12f; setPadding(14,12,14,12); background = GradientDrawable().apply { setColor(Color.parseColor(lightTeal)); cornerRadius = 12f; setStroke(2, Color.parseColor(border)) }; inputType = InputType.TYPE_CLASS_NUMBER }
    private fun spacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, (h*resources.displayMetrics.density).toInt()) }
    private fun pickSupplier() { val sups = arrayOf("General Supplier","Metro","Imtiaz","Local Market"); AlertDialog.Builder(this).setTitle("🏭 Select Supplier").setItems(sups) { _, i -> selectedSupplier = sups[i]; supplierField.text = "🏭 Supplier: $selectedSupplier" }.show() }
    private fun pickQtyType() { val types = arrayOf("ctn","outer/lari","dabbi/pcs"); AlertDialog.Builder(this).setTitle("📦 Qty Type").setItems(types) { _, i -> selectedQtyType = types[i]; qtyTypeField.text = "📦 ${types[i]} ▼"; calcTotal() }.show() }
    private fun pickProduct() {
        if (allProducts.isEmpty()) { Toast.makeText(this, "No products", Toast.LENGTH_SHORT).show(); return }
        val names = allProducts.map { it.name + " - " + it.category }.toTypedArray()
        AlertDialog.Builder(this).setTitle("📦 Select Product").setItems(names) { _, i ->
            selectedProduct = allProducts[i]
            productField.text = "📦 " + selectedProduct!!.name + " | " + selectedProduct!!.category
            productField.setTextColor(Color.parseColor(textDark))
            unitInfo.text = "📐 ${selectedProduct!!.unit} ${selectedProduct!!.secondaryUnitQty.toInt()} ${selectedProduct!!.secondaryUnit} ${selectedProduct!!.tertiaryUnitQty.toInt()} ${selectedProduct!!.tertiaryUnit} = ${(selectedProduct!!.secondaryUnitQty * selectedProduct!!.tertiaryUnitQty).toInt()} ${selectedProduct!!.tertiaryUnit} | W:${selectedProduct!!.wholesalePrice} R:${selectedProduct!!.salePrice}"
            costField.setText(selectedProduct!!.cost.toString()); wholesaleField.setText(selectedProduct!!.wholesalePrice.toString()); retailField.setText(selectedProduct!!.salePrice.toString()); calcTotal()
        }.show()
    }
    private fun calcTotal() {
        val p = selectedProduct?: return
        val qty = qtyField.text.toString().toDoubleOrNull()?: 0.0
        val cost = costField.text.toString().toDoubleOrNull()?: 0.0
        var totalDabbi = qty
        if (selectedQtyType.contains("ctn")) totalDabbi = qty * p.secondaryUnitQty * p.tertiaryUnitQty
        else if (selectedQtyType.contains("outer") || selectedQtyType.contains("lari")) totalDabbi = qty * p.tertiaryUnitQty
        val total = totalDabbi * cost
        totalField.text = "💵 Total: Rs $total"
    }
    private fun addToCart() {
        val p = selectedProduct?: return
        val qty = qtyField.text.toString().toDoubleOrNull()?: return
        val cost = costField.text.toString().toDoubleOrNull()?: 0.0
        var totalDabbi = qty
        if (selectedQtyType.contains("ctn")) totalDabbi = qty * p.secondaryUnitQty * p.tertiaryUnitQty
        else if (selectedQtyType.contains("outer")) totalDabbi = qty * p.tertiaryUnitQty
        val item = PurchaseItem(barcode = p.barcode, name = p.name, qty = totalDabbi, cost = cost, total = totalDabbi * cost, qtyType = selectedQtyType, wholesalePrice = wholesaleField.text.toString().toDoubleOrNull()?: 0.0, retailPrice = retailField.text.toString().toDoubleOrNull()?: 0.0)
        cart.add(item); showCart(); qtyField.text.clear()
    }
    private fun showCart() {
        cartContainer.removeAllViews()
        var grand = 0.0
        for (item in cart) {
            grand += item.total
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(14,12,14,12); background = GradientDrawable().apply { setColor(Color.parseColor(lightTeal)); cornerRadius = 12f }; layoutParams = LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,0,0,8) } }
            row.addView(TextView(this).apply { text = "${item.name} | ${item.qty} ${item.qtyType} = Rs ${item.total}"; textSize = 12f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD) })
            cartContainer.addView(row)
        }
        grandTotalField.text = "💰 Grand Total: Rs $grand | Items: ${cart.size}"
    }
    private fun savePurchase() {
        if (cart.isEmpty()) { Toast.makeText(this, "Cart empty", Toast.LENGTH_SHORT).show(); return }
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PurchaseActivity)
            for (item in cart) {
                val prod = db.productDao().getByBarcode(item.barcode)
                if (prod!= null) {
                    val updated = prod.copy(
                        stock = prod.stock + item.qty.toInt(),
                        cost = item.cost,
                        wholesalePrice = item.wholesalePrice,
                        salePrice = item.retailPrice
                    )
                    db.productDao().upsert(updated)
                }
            }
            Toast.makeText(this@PurchaseActivity, "✅ Purchase Saved", Toast.LENGTH_LONG).show()
            cart.clear(); showCart()
        }
    }
    private fun loadProducts() { lifecycleScope.launch { allProducts = PosDatabase.get(this@PurchaseActivity).productDao().all().first() } }
}
