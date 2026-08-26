package com.grocerypos.v11.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.*
import com.grocerypos.v11.util.ThemeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PurchaseHistoryActivity : ThemedActivity() {

    companion object {
        private const val TAG = "PurchaseHistoryActivity"
    }

    // ---- Own inline copies of the premium styling helpers, mirroring how ProductActivity
    // keeps its own duplicated versions rather than sharing a common base. ----
    private var bg = "#F5F7FA"
    private var cardWhite = "#FFFFFF"
    private var textDark = "#111827"
    private var textMuted = "#8892A0"
    private var border = "#E7EAF0"
    private var red = "#E5484D"
    private var fieldFill = "#FAFBFD"

    private val navy = "#101B33"
    private val navyLight = "#1C2C4F"
    private val teal = "#0EA5A0"
    private val gold = "#C9A24B"
    private val amberBadge = "#F4F1E8"
    private val successGreen = "#1E9E6B"

    private fun loadThemePrefs() {
        val p = ThemeManager.palette(this)
        bg = p.bg
        cardWhite = p.cardWhite
        textDark = p.textDark
        textMuted = p.textMuted
        border = p.border
        red = p.red
        fieldFill = p.fieldFill
    }

    private lateinit var listContainer: LinearLayout
    private lateinit var searchField: EditText
    private lateinit var emptyStateText: TextView

    // billNo/supplierName/total/createdAt/status come straight from the joined query;
    // paid is fetched separately per bill (allPurchases() doesn't project it) so we can
    // still show a due/paid-in-full badge.
    private data class HistoryRow(val billNo: String, val supplierName: String, val total: Double, val createdAt: Long, val status: String, val paid: Double)

    private var rows: List<HistoryRow> = emptyList()

    private fun safeLaunch(label: String, block: suspend () -> Unit) {
        lifecycleScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "safeLaunch[$label] failed", e)
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        try {
            loadThemePrefs()
            buildUi()
            loadPurchases()
        } catch (e: Exception) {
            Log.e(TAG, "onCreate: fatal error building Purchase History screen", e)
            finish()
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 0, 24, 40)
            setBackgroundColor(Color.parseColor(bg))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(26, 30, 22, 26)
            background = gradientBg(navy, navyLight, cornerBottom = 26)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(-24, 0, -24, 16) }
            applyElevation(this, 8f)
        }
        header.addView(TextView(this).apply {
            text = "\u2039"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = ovalBg("#22FFFFFF")
            val px = (38 * resources.displayMetrics.density).toInt(); width = px; height = px
            setOnClickListener { finish() }
        })
        header.addView(spacer(14).apply { layoutParams = LinearLayout.LayoutParams((14 * resources.displayMetrics.density).toInt(), 1) })
        val headerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerCol.addView(TextView(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@PurchaseHistoryActivity, "Purchase History", "خریداری کی تاریخ")
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.01f
        })
        headerCol.addView(TextView(this).apply {
            text = "ALL SUPPLIER BILLS"
            textSize = 10.5f
            setTextColor(Color.parseColor("#A7B4CC"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(0, 5, 0, 0)
        })
        header.addView(headerCol)
        root.addView(header)

        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 4, 20, 4)
            background = strokedBg(border, cardWhite, 16)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 16) }
            applyElevation(this, 2f)
        }
        searchBox.addView(TextView(this).apply { text = "\uD83D\uDD0D  "; textSize = 14f })
        searchField = EditText(this).apply {
            hint = com.grocerypos.v11.util.Loc.t(this@PurchaseHistoryActivity, "Search bill no. or supplier…", "بل نمبر یا سپلائر تلاش کریں…")
            setHintTextColor(Color.parseColor(textMuted))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 14.5f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        searchBox.addView(searchField)
        root.addView(searchBox)

        emptyStateText = TextView(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@PurchaseHistoryActivity, "No purchases yet", "ابھی کوئی خریداری نہیں")
            textSize = 14f
            setTextColor(Color.parseColor(textMuted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 60, 0, 0)
            visibility = View.GONE
        }
        root.addView(emptyStateText)

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        val scrollArea = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        }
        setContentView(scrollArea)

        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = renderList(s?.toString().orEmpty())
        })
    }

    private fun loadPurchases() = safeLaunch("loadPurchases") {
        val db = PosDatabase.get(this@PurchaseHistoryActivity)
        val purchases = db.purchaseDao().allPurchases()
        rows = purchases
            .sortedByDescending { it.createdAt }
            .map { pws ->
                val paid = try { db.purchaseDao().findPurchase(pws.billNo)?.paid ?: 0.0 } catch (e: Exception) {
                    Log.e(TAG, "loadPurchases: paid lookup failed for ${pws.billNo}", e); 0.0
                }
                HistoryRow(pws.billNo, pws.supplierName, pws.total, pws.createdAt, pws.status, paid)
            }
        renderList(searchField.text?.toString().orEmpty())
    }

    private fun renderList(query: String) {
        listContainer.removeAllViews()
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) rows else rows.filter { row ->
            row.billNo.lowercase().contains(q) || row.supplierName.lowercase().contains(q)
        }
        emptyStateText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        filtered.forEach { row ->
            val due = (row.total - row.paid).coerceAtLeast(0.0)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(22, 18, 22, 18)
                background = strokedBg(border, cardWhite, 18)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 12) }
                applyElevation(this, 3f)
                setOnClickListener {
                    startActivity(Intent(this@PurchaseHistoryActivity, PurchaseActivity::class.java).apply {
                        putExtra(PurchaseActivity.EXTRA_BILL_NO, row.billNo)
                    })
                }
            }
            val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val billCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            billCol.addView(TextView(this).apply { text = row.billNo; textSize = 14.5f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(Color.parseColor(textDark)) })
            billCol.addView(TextView(this).apply { text = row.supplierName; textSize = 12.5f; setTextColor(Color.parseColor(textMuted)); setPadding(0, 3, 0, 0) })
            topRow.addView(billCol)
            topRow.addView(TextView(this).apply { text = "Rs %.0f".format(row.total); textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(Color.parseColor(navy)) })
            card.addView(topRow)
            card.addView(spacer(10))
            val bottomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            bottomRow.addView(TextView(this).apply {
                text = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(row.createdAt))
                textSize = 11.5f
                setTextColor(Color.parseColor(textMuted))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (row.status != "active") {
                bottomRow.addView(TextView(this).apply {
                    text = row.status.replaceFirstChar { it.uppercase() }
                    textSize = 11.5f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor(textMuted))
                    background = strokedBg(border, fieldFill, 8)
                    setPadding(14, 5, 14, 5)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 8, 0) }
                })
            }
            bottomRow.addView(TextView(this).apply {
                text = if (due > 0) com.grocerypos.v11.util.Loc.t(this@PurchaseHistoryActivity, "Due: Rs %.0f", "باقی: Rs %.0f").format(due) else com.grocerypos.v11.util.Loc.t(this@PurchaseHistoryActivity, "Paid in full", "مکمل ادا شدہ")
                textSize = 11.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(if (due > 0) red else successGreen))
                background = strokedBg(if (due > 0) "#F4C7C8" else "#BFE7D3", if (due > 0) "#FDF1F1" else "#EEFBF4", 8)
                setPadding(14, 5, 14, 5)
            })
            card.addView(bottomRow)
            listContainer.addView(card)
        }
    }

    private fun roundedBg(colorHex: String, radius: Int) = GradientDrawable().apply { setColor(Color.parseColor(colorHex)); cornerRadius = radius.toFloat() }
    private fun strokedBg(strokeHex: String, fillHex: String, radius: Int) = GradientDrawable().apply { setColor(Color.parseColor(fillHex)); setStroke((1.2 * resources.displayMetrics.density).toInt(), Color.parseColor(strokeHex)); cornerRadius = radius.toFloat() }
    private fun ovalBg(colorHex: String) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(colorHex)) }
    private fun gradientBg(startHex: String, endHex: String, cornerTop: Int = 0, cornerBottom: Int = 0) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(startHex), Color.parseColor(endHex))
    ).apply {
        val density = resources.displayMetrics.density
        cornerRadii = floatArrayOf(
            cornerTop * density, cornerTop * density,
            cornerTop * density, cornerTop * density,
            cornerBottom * density, cornerBottom * density,
            cornerBottom * density, cornerBottom * density
        )
    }
    private fun applyElevation(view: View, dp: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { view.elevation = dp * resources.displayMetrics.density; view.outlineProvider = ViewOutlineProvider.BACKGROUND }
    }
    private fun spacer(heightDp: Int) = View(this).apply { val px = (heightDp * resources.displayMetrics.density).toInt(); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px) }
}
