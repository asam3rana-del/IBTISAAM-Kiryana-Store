package com.grocerypos.v11.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Product
import com.grocerypos.v11.SyncQueueHelper
import com.grocerypos.v11.matchesQuery
import com.grocerypos.v11.util.Loc
import com.grocerypos.v11.util.parseMoneyOrWarn
import kotlinx.coroutines.launch

/**
 * "Shop Rate" — ported from the Flutter app's ShopRateScreen. Items -> "Shop Rate" pill (admin only).
 *
 * Lists every product with its Wholesale and Shopkeeper rate side by side; tap a product to set
 * or change its Shopkeeper rate (the third, lowest-margin rate). An EMPTY shopkeeper rate means a
 * Shopkeeper-type bill uses the Wholesale rate for that product (see Product.baseRateFor()).
 * The "Not set" chip shows only products with a Wholesale rate but no Shopkeeper rate yet.
 *
 * Rates are per the product's PRIMARY unit, same convention as salePrice / wholesalePrice.
 */
class ShopRateActivity : ThemedActivity() {

    private var bg = "#F3F2FA"
    private var cardBg = "#FFFFFF"
    private var primary = "#4A3AFF"
    private var textDark = "#1A1A2E"
    private var textGray = "#8A8A9E"
    private var border = "#E7E5F3"
    private var blue = "#2F6BFF"
    private var tealDark = "#0C7C71"

    private var all: List<Product> = emptyList()
    private var query = ""
    private var onlyNotSet = false

    private lateinit var listBox: LinearLayout
    private lateinit var allChip: TextView
    private lateinit var notSetChip: TextView
    private lateinit var emptyText: TextView

    private val isAdmin: Boolean
        get() = (getSharedPreferences("session", MODE_PRIVATE).getString("role", "cashier") ?: "cashier") == "admin"

    private fun loadThemeColors() {
        val p = com.grocerypos.v11.util.ThemeManager.palette(this)
        bg = p.bg
        cardBg = p.cardWhite
        primary = p.flatPurpleFg
        textDark = p.textDark
        textGray = p.textMuted
        border = p.border
        tealDark = p.flatTealFg
    }

    private fun fmt(v: Double): String {
        val r = Math.round(v * 100) / 100.0
        return if (r == Math.floor(r)) r.toLong().toString() else "%.2f".format(r)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        loadThemeColors()

        // Server-side style guard: UI hiding is not enough (Flutter checks this inside the screen too).
        if (!isAdmin) {
            Toast.makeText(
                this,
                Loc.t(this, "Only Admin can open Shop Rate", "صرف ایڈمن دکاندار ریٹ کھول سکتا ہے"),
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            setBackgroundColor(Color.parseColor(bg))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26, 22, 26, 22)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(primary), Color.parseColor(primary))
            ).apply { cornerRadius = 22f }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 14) }
        }
        header.addView(TextView(this).apply {
            text = Loc.t(this@ShopRateActivity, "Shop Rate", "دکاندار ریٹ")
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        header.addView(TextView(this).apply {
            text = Loc.t(
                this@ShopRateActivity,
                "Tap a product to set its Shopkeeper rate. Empty = Wholesale rate is used on the bill.",
                "دکاندار ریٹ لگانے کے لیے پروڈکٹ دبائیں۔ خالی = بل پر ہول سیل ریٹ لگے گا۔"
            )
            textSize = 12f
            setTextColor(Color.parseColor("#DAD5FF"))
            setPadding(0, 6, 0, 0)
        })
        root.addView(header)

        val search = EditText(this).apply {
            hint = Loc.t(this@ShopRateActivity, "Search product…", "پروڈکٹ تلاش کریں…")
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.parseColor(textDark))
            setHintTextColor(Color.parseColor(textGray))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(cardBg))
                setStroke(1, Color.parseColor(border))
                cornerRadius = 24f
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b4: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty()
                    render()
                }
            })
        }
        root.addView(search, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 10) })

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        allChip = chip(Loc.t(this, "All", "سب")) { onlyNotSet = false; render() }
        notSetChip = chip(Loc.t(this, "Not set", "سیٹ نہیں")) { onlyNotSet = true; render() }
        chips.addView(allChip)
        chips.addView(notSetChip)
        root.addView(chips, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 10) })

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        emptyText = TextView(this).apply {
            text = Loc.t(this@ShopRateActivity, "No products found", "کوئی پروڈکٹ نہیں ملی")
            setTextColor(Color.parseColor(textGray))
            gravity = Gravity.CENTER
            setPadding(0, dp(30), 0, 0)
        }
        val scroll = ScrollView(this).apply { addView(listBox) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        loadProducts()
    }

    private fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, dp(8), 0) }
        setOnClickListener { onClick() }
    }

    private fun styleChip(c: TextView, selected: Boolean) {
        c.setTextColor(Color.parseColor(if (selected) "#FFFFFF" else textDark))
        c.background = GradientDrawable().apply {
            setColor(Color.parseColor(if (selected) primary else cardBg))
            setStroke(1, Color.parseColor(border))
            cornerRadius = 40f
        }
    }

    private fun loadProducts() {
        lifecycleScope.launch {
            all = PosDatabase.get(this@ShopRateActivity).productDao().allList().sortedBy { it.name.lowercase() }
            render()
        }
    }

    private fun visible(): List<Product> {
        var list = all.filter { it.matchesQuery(query) }
        if (onlyNotSet) list = list.filter { it.shopkeeperPrice <= 0 && it.wholesalePrice > 0 }
        return list
    }

    private fun render() {
        styleChip(allChip, !onlyNotSet)
        styleChip(notSetChip, onlyNotSet)
        listBox.removeAllViews()
        val list = visible()
        if (list.isEmpty()) {
            listBox.addView(emptyText)
            return
        }
        list.forEach { listBox.addView(row(it)) }
    }

    private fun row(p: Product): LinearLayout {
        val set = p.shopkeeperPrice > 0
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(cardBg))
                setStroke(1, Color.parseColor(border))
                cornerRadius = 28f
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) }
            addView(TextView(this@ShopRateActivity).apply {
                text = p.name
                textSize = 14.5f
                maxLines = 2
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor(textDark))
            })
            addView(TextView(this@ShopRateActivity).apply {
                val ws = if (p.wholesalePrice > 0) "Rs ${fmt(p.wholesalePrice)}" else "—"
                val sk = if (set) "Rs ${fmt(p.shopkeeperPrice)}" else Loc.t(this@ShopRateActivity, "Not set", "سیٹ نہیں")
                text = "${Loc.t(this@ShopRateActivity, "Wholesale", "ہول سیل")}: $ws      ${Loc.t(this@ShopRateActivity, "Shop", "دکاندار")}: $sk"
                textSize = 12.5f
                setTextColor(Color.parseColor(if (set) blue else textGray))
                setPadding(0, dp(6), 0, 0)
            })
            addView(TextView(this@ShopRateActivity).apply {
                text = "${Loc.t(this@ShopRateActivity, "per", "فی")} ${p.unit}"
                textSize = 11f
                setTextColor(Color.parseColor(textGray))
            })
            setOnClickListener { editRate(p) }
        }
    }

    private fun editRate(p: Product) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = Loc.t(this@ShopRateActivity, "Shopkeeper rate per ${p.unit} (empty = clear)", "دکاندار ریٹ فی ${p.unit} (خالی = ہٹائیں)")
            if (p.shopkeeperPrice > 0) setText(fmt(p.shopkeeperPrice))
            setSelection(text.length)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
            if (p.wholesalePrice > 0) {
                addView(TextView(this@ShopRateActivity).apply {
                    text = "${Loc.t(this@ShopRateActivity, "Wholesale", "ہول سیل")}: Rs ${fmt(p.wholesalePrice)} / ${p.unit}"
                    textSize = 12.5f
                    setTextColor(Color.parseColor(textGray))
                })
            }
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setView(box)
            .setPositiveButton(Loc.t(this, "Save", "محفوظ کریں")) { _, _ ->
                val v = input.parseMoneyOrWarn(this, "Shopkeeper Price", "دکاندار قیمت") ?: return@setPositiveButton
                if (v < 0) {
                    Toast.makeText(this, Loc.t(this, "Rate cannot be negative", "ریٹ منفی نہیں ہو سکتا"), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                saveRate(p, v)
            }
            .setNegativeButton(Loc.t(this, "Cancel", "منسوخ کریں"), null)
            .show()
    }

    private fun saveRate(p: Product, value: Double) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@ShopRateActivity)
            db.productDao().updateShopkeeperPrice(p.barcode, value, System.currentTimeMillis())
            val updated = db.productDao().find(p.barcode)
            if (updated != null) {
                // Same enqueue + trigger pattern ProductActivity uses for a product edit.
                SyncQueueHelper.enqueue(db, "product", SyncQueueHelper.productEntityId(updated), "update", SyncQueueHelper.productJson(updated))
                SyncQueueHelper.trigger(this@ShopRateActivity)
            }
            loadProducts()
        }
    }
}
