package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Product
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Shows every Category, and inside each category, every Product that belongs to it —
 * with its Primary Unit, Secondary Unit, Tertiary Unit, and the conversions between them
 * (1 Primary = X Secondary, 1 Secondary = X Tertiary).
 * Each product row also offers Edit (deep-links into ProductActivity's edit form) and Delete.
 */
class CategoriesUnitsActivity : AppCompatActivity() {

    // ================= PREMIUM COLOR PALETTE (matches Settings / Product) =================
    private val bg = "#F3F2FA"
    private val cardBg = "#FFFFFF"
    private val primary = "#4A3AFF"
    private val primaryDark = "#3527D6"
    private val purple = "#8B5CF6"
    private val amber = "#F5A524"
    private val red = "#E5484D"
    private val textDark = "#1A1A2E"
    private val textGray = "#8A8A9E"
    private val border = "#E7E5F3"

    private lateinit var listContainer: LinearLayout
    private lateinit var emptyText: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            setBackgroundColor(Color.parseColor(bg))
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
            ).apply { setMargins(0, 0, 0, 20) }
            applyElevation(this, 10f)
        }
        header.addView(circleIcon("🗂️", "#5C4DFF", 42))
        header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(16, 1) })
        val headerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerCol.addView(TextView(this).apply {
            text = Loc.t(this@CategoriesUnitsActivity, "Categories & Units", "کیٹیگریز اور یونٹس")
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        headerCol.addView(TextView(this).apply {
            text = Loc.t(this@CategoriesUnitsActivity, "Product category-wise unit details", "پروڈکٹ کیٹیگری کے لحاظ سے یونٹ کی تفصیل")
            textSize = 11.5f
            setTextColor(Color.parseColor("#D8D3FF"))
            setPadding(0, 4, 0, 0)
        })
        header.addView(headerCol)
        root.addView(header)

        emptyText = TextView(this).apply {
            text = Loc.t(this@CategoriesUnitsActivity, "No products added yet", "ابھی تک کوئی پروڈکٹ شامل نہیں ہوئی")
            textSize = 13.5f
            setTextColor(Color.parseColor(textGray))
            gravity = Gravity.CENTER
            setPadding(0, 60, 0, 0)
            visibility = View.GONE
        }
        root.addView(emptyText)

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)
        root.addView(spacer(30))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        })

        loadCategorized()
    }

    private fun loadCategorized() {
        lifecycleScope.launch {
            PosDatabase.get(this@CategoriesUnitsActivity).productDao().all().collectLatest { products ->
                listContainer.removeAllViews()

                if (products.isEmpty()) {
                    emptyText.visibility = View.VISIBLE
                    return@collectLatest
                }
                emptyText.visibility = View.GONE

                val grouped = products.groupBy { it.category.ifBlank { "General" } }
                    .toSortedMap(compareBy { it })

                for ((category, items) in grouped) {
                    listContainer.addView(categoryCard(category, items))
                    listContainer.addView(spacer(16))
                }
            }
        }
    }

    private fun categoryCard(category: String, items: List<Product>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(22, 20, 22, 20)
        background = strokedBg(border, cardBg, 18)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        applyElevation(this, 3f)

        // ---- category header row ----
        val head = LinearLayout(this@CategoriesUnitsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 14)
        }
        head.addView(TextView(this@CategoriesUnitsActivity).apply { text = "🗂️  "; textSize = 15f })
        head.addView(TextView(this@CategoriesUnitsActivity).apply {
            text = category
            textSize = 15f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(TextView(this@CategoriesUnitsActivity).apply {
            text = "${items.size} " + Loc.t(this@CategoriesUnitsActivity, if (items.size == 1) "item" else "items", "آئٹمز")
            textSize = 11f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg(purple, 20)
            setPadding(18, 6, 18, 6)
        })
        addView(head)

        // ---- one row per product ----
        for ((index, p) in items.withIndex()) {
            addView(productRow(p))
            if (index != items.lastIndex) addView(spacer(10))
        }
    }

    private fun productRow(p: Product) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16, 14, 16, 14)
        background = strokedBg(border, "#FAFAFF", 14)

        addView(TextView(this@CategoriesUnitsActivity).apply {
            text = p.name
            textSize = 13.5f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, Typeface.BOLD)
        })

        // ---- FIX: now shows all 3 tiers — Primary, Secondary, and Tertiary
        // (smallest quantity, e.g. grams/ml) — instead of stopping at Secondary.
        // Chain: 1 Primary = secondaryUnitQty Secondary; 1 Secondary = tertiaryUnitQty Tertiary.
        val unitLine = buildString {
            append("📏 " + Loc.t(this@CategoriesUnitsActivity, "Unit", "یونٹ") + ": ${p.unit}")
            if (p.secondaryUnit.isNotBlank() && p.secondaryUnitQty > 0) {
                append("  •  🔁 1 ${p.unit} = ${formatQty(p.secondaryUnitQty)} ${p.secondaryUnit}")
                if (p.tertiaryUnit.isNotBlank() && p.tertiaryUnitQty > 0) {
                    append("  •  🔁 1 ${p.secondaryUnit} = ${formatQty(p.tertiaryUnitQty)} ${p.tertiaryUnit}")
                }
            }
        }

        addView(TextView(this@CategoriesUnitsActivity).apply {
            text = unitLine
            textSize = 12f
            setTextColor(Color.parseColor(amber))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 6, 0, 4)
        })

        addView(TextView(this@CategoriesUnitsActivity).apply {
            text = "📊 " + Loc.t(this@CategoriesUnitsActivity, "Stock", "اسٹاک") + ": ${p.stock} ${p.unit}"
            textSize = 12f
            setTextColor(Color.parseColor(textGray))
        })

        // ---- Edit / Delete actions ----
        val actionsRow = LinearLayout(this@CategoriesUnitsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 0)
        }
        actionsRow.addView(TextView(this@CategoriesUnitsActivity).apply {
            text = "✏️  " + Loc.t(this@CategoriesUnitsActivity, "Edit", "ترمیم کریں")
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg(primary, 30)
            setPadding(24, 10, 24, 10)
            setOnClickListener {
                startActivity(Intent(this@CategoriesUnitsActivity, ProductActivity::class.java).apply {
                    putExtra(ProductActivity.EXTRA_EDIT_BARCODE, p.barcode)
                })
            }
        })
        actionsRow.addView(View(this@CategoriesUnitsActivity).apply { layoutParams = LinearLayout.LayoutParams(10, 1) })
        actionsRow.addView(TextView(this@CategoriesUnitsActivity).apply {
            text = "🗑️  " + Loc.t(this@CategoriesUnitsActivity, "Delete", "حذف کریں")
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg(red, 30)
            setPadding(24, 10, 24, 10)
            setOnClickListener { confirmDeleteProduct(p) }
        })
        addView(actionsRow)
    }

    private fun confirmDeleteProduct(p: Product) {
        AlertDialog.Builder(this)
            .setTitle(Loc.t(this, "Delete Product", "پروڈکٹ حذف کریں"))
            .setMessage(
                Loc.t(
                    this,
                    "Delete \"${p.name}\"? This cannot be undone.",
                    "\"${p.name}\" کو حذف کریں؟ یہ واپس نہیں ہو سکتا۔"
                )
            )
            .setPositiveButton(Loc.t(this, "Delete", "حذف کریں")) { _, _ ->
                lifecycleScope.launch {
                    PosDatabase.get(this@CategoriesUnitsActivity).productDao().delete(p)
                    Toast.makeText(this@CategoriesUnitsActivity, Loc.t(this@CategoriesUnitsActivity, "Product deleted", "پروڈکٹ حذف ہو گئی"), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(Loc.t(this, "Cancel", "منسوخ کریں"), null)
            .show()
    }

    private fun formatQty(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    // ================= UI HELPERS =================
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

    private fun spacer(heightDp: Int) = View(this).apply {
        val px = (heightDp * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px)
    }
}
