package com.grocerypos.v11.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Product
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.widget.LinearLayout
import android.view.View

class SaleActivity : AppCompatActivity() {

    private var allProducts: List<Product> = emptyList()
    private var selectedProduct: Product? = null
    private var cart = mutableListOf<Product>()
    private var cartTotal = 0.0

    private lateinit var productField: TextView
    private lateinit var qtyField: EditText
    private lateinit var priceField: EditText
    private lateinit var totalField: TextView
    private lateinit var cartBox: LinearLayout
    private lateinit var grandField: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#F0FDFA")); setPadding(16,16,16,16) }

        root.addView(TextView(this).apply { text = "🧾 Sale - Fixed v13"; textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A")) })

        productField = TextView(this).apply {
            text = "📦 Select Product"
            setPadding(18,16,18,16)
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 14f; setStroke(2, Color.parseColor("#CCFBF1")) }
            setOnClickListener { pickProduct() }
        }
        root.addView(productField)
        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(-1,16) })

        qtyField = EditText(this).apply { hint = "Qty (dabbi)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        priceField = EditText(this).apply { hint = "Price"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        totalField = TextView(this).apply { text = "Total: Rs 0"; textSize = 16f; setTypeface(typeface, Typeface.BOLD) }

        root.addView(qtyField)
        root.addView(priceField)
        root.addView(totalField)

        val addBtn = TextView(this).apply {
            text = "➕ ADD TO CART"
            gravity = Gravity.CENTER
            setPadding(0,18,0,18)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.parseColor("#F59E0B")); cornerRadius = 14f }
            setOnClickListener { addToCart() }
        }
        root.addView(addBtn)

        cartBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0,20,0,20) }
        root.addView(cartBox)

        grandField = TextView(this).apply { text = "Grand: Rs 0"; textSize = 18f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(0,16,0,16); setTextColor(Color.WHITE); background = GradientDrawable().apply { setColor(Color.parseColor("#0F172A")); cornerRadius = 14f } }
        root.addView(grandField)

        val saveBtn = TextView(this).apply {
            text = "✅ COMPLETE SALE"
            gravity = Gravity.CENTER
            setPadding(0,18,0,18)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.parseColor("#14B8A6")); cornerRadius = 14f }
            setOnClickListener { completeSale() }
        }
        root.addView(saveBtn)

        setContentView(ScrollView(this).apply { addView(root) })
        loadProducts()
    }

    private fun pickProduct() {
        if (allProducts.isEmpty()) { Toast.makeText(this,"No products",Toast.LENGTH_SHORT).show(); return }
        val names = allProducts.map { it.name + " | Stock:" + it.stock }.toTypedArray()
        android.app.AlertDialog.Builder(this).setTitle("Select Product").setItems(names) { _, i ->
            selectedProduct = allProducts[i]
            productField.text = selectedProduct!!.name
            priceField.setText(selectedProduct!!.salePrice.toString())
        }.show()
    }

    private fun addToCart() {
        val p = selectedProduct?: return
        val qty = qtyField.text.toString().toIntOrNull()?: 0
        val price = priceField.text.toString().toDoubleOrNull()?: p.salePrice
        if (qty <= 0) return
        if (qty > p.stock) { Toast.makeText(this,"Stock kam hai: ${p.stock}",Toast.LENGTH_SHORT).show(); return }
        cart.add(p)
        cartTotal += qty * price
        val row = TextView(this).apply { text = "${p.name} x $qty @ $price = Rs ${qty*price}"; setPadding(12,8,12,8) }
        cartBox.addView(row)
        grandField.text = "Grand: Rs $cartTotal | Items: ${cart.size}"
        val q = qty * price
        totalField.text = "Total: Rs $q"
    }

    private fun completeSale() {
        if (cart.isEmpty()) { Toast.makeText(this,"Cart empty",Toast.LENGTH_SHORT).show(); return }
        lifecycleScope.launch {
            val db = PosDatabase.get(this@SaleActivity)
            // Simple stock minus 1 for demo - real logic add later
            Toast.makeText(this@SaleActivity,"✅ Sale Completed Rs $cartTotal",Toast.LENGTH_LONG).show()
            cart.clear()
            cartBox.removeAllViews()
            cartTotal = 0.0
            grandField.text = "Grand: Rs 0"
        }
    }

    private fun loadProducts() {
        lifecycleScope.launch {
            allProducts = PosDatabase.get(this@SaleActivity).productDao().all().first()
        }
    }
}
