package com.grocerypos.v11.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.ItemMovement
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Product
import com.grocerypos.v11.formatStockBreakdown
import com.grocerypos.v11.smallestUnitName
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

// NEW: four related inventory reports behind one tab switcher, launched from
// ReportsActivity via EXTRA_MODE (which tab opens first) — the person can still
// switch tabs freely once inside, so this is one destination, not four.
class InventoryInsightsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_REORDER = "reorder"
        const val MODE_DAMAGE = "damage"
        const val MODE_PROFIT = "profit"
        const val MODE_MOVERS = "movers"
    }

    private val bg = "#F3F2FA"
    private val cardBg = "#FFFFFF"
    private val primary = "#4A3AFF"
    private val primaryDark = "#3527D6"
    private val amber = "#F5A524"
    private val teal = "#0F9B8E"
    private val red = "#E5484D"
    private val textDark = "#1A1A2E"
    private val textGray = "#8A8A9E"
    private val border = "#E7E5F3"

    private var mode: String = MODE_REORDER
    private val tabs = mutableListOf<TextView>()
    private val periodFilters = mutableListOf<TextView>()
    private lateinit var periodRow: LinearLayout
    private lateinit var summaryBox: LinearLayout
    private lateinit var resultsBox: LinearLayout
    private lateinit var headerBox: LinearLayout

    private var rangeStart = 0L
    private var rangeEnd = System.currentTimeMillis()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_REORDER

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            setBackgroundColor(Color.parseColor(bg))
        }

        headerBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(headerBox)

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = strokedBg(border, cardBg, 14)
            setPadding(6, 6, 6, 6)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 12) }
        }
        val reorderTab = tabPill(Loc.t(this, "Reorder", "دوبارہ آرڈر")) { mode = MODE_REORDER; onModeChanged() }
        val damageTab = tabPill(Loc.t(this, "Damage", "نقصان")) { mode = MODE_DAMAGE; onModeChanged() }
        val profitTab = tabPill(Loc.t(this, "Margin", "منافع")) { mode = MODE_PROFIT; onModeChanged() }
        val moversTab = tabPill(Loc.t(this, "Movers", "چلن")) { mode = MODE_MOVERS; onModeChanged() }
        tabs.addAll(listOf(reorderTab, damageTab, profitTab, moversTab))
        tabs.forEach { tabRow.addView(it) }
        root.addView(tabRow)

        periodRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = strokedBg(border, cardBg, 14)
            setPadding(6, 6, 6, 6)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 12) }
        }
        val today = periodPill(Loc.t(this, "Today", "آج")) { setRangeToday(); loadData() }
        val week = periodPill(Loc.t(this, "This Week", "اس ہفتے")) { setRangeThisWeek(); loadData() }
        val month = periodPill(Loc.t(this, "This Month", "اس مہینے")) { setRangeThisMonth(); loadData() }
        val all = periodPill(Loc.t(this, "All Time", "تمام وقت")) { setRangeAllTime(); loadData() }
        periodFilters.addAll(listOf(today, week, month, all))
        periodFilters.forEach { periodRow.addView(it) }
        root.addView(periodRow)

        summaryBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(summaryBox)
        root.addView(spacer(10))

        resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(resultsBox)
        root.addView(spacer(30))

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        }
        setContentView(scroll)

        setRangeThisMonth()
        onModeChanged()
    }

    private fun onModeChanged() {
        headerBox.removeAllViews()
        headerBox.addView(when (mode) {
            MODE_REORDER -> premiumHeader("\uD83D\uDECD\uFE0F", Loc.t(this, "Reorder Suggestions", "دوبارہ آرڈر تجاویز"), Loc.t(this, "Items at or below their reorder level", "آئٹمز جو دوبارہ آرڈر کی سطح پر یا نیچے ہیں"))
            MODE_DAMAGE -> premiumHeader("\uD83D\uDCA5", Loc.t(this, "Damage / Loss Report", "نقصان کی رپورٹ"), Loc.t(this, "Stock logged as damaged or lost", "خراب یا ضائع ہونے والا اسٹاک"))
            MODE_PROFIT -> premiumHeader("\uD83D\uDCC8", Loc.t(this, "Profit Margin per Item", "فی آئٹم منافع"), Loc.t(this, "Sale price vs cost, item by item", "سیل پرائس بمقابلہ لاگت"))
            else -> premiumHeader("\u23F1\uFE0F", Loc.t(this, "Fast / Slow Movers", "تیز / سست چلنے والے"), Loc.t(this, "Which items sell, and which sit on the shelf", "کون سے آئٹم بکتے ہیں"))
        })
        highlightTab()
        periodRow.visibility = if (mode == MODE_DAMAGE || mode == MODE_MOVERS) View.VISIBLE else View.GONE
        loadData()
    }

    private fun highlightTab() {
        val activeIdx = when (mode) { MODE_REORDER -> 0; MODE_DAMAGE -> 1; MODE_PROFIT -> 2; else -> 3 }
        tabs.forEachIndexed { i, t ->
            if (i == activeIdx) { t.background = roundedBg(primary, 10); t.setTextColor(Color.WHITE) }
            else { t.background = null; t.setTextColor(Color.parseColor(textGray)) }
        }
    }

    private fun highlightPeriod(activeIdx: Int) {
        periodFilters.forEachIndexed { i, t ->
            if (i == activeIdx) { t.background = roundedBg(primary, 10); t.setTextColor(Color.WHITE) }
            else { t.background = null; t.setTextColor(Color.parseColor(textGray)) }
        }
    }

    private fun setRangeToday() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        rangeStart = cal.timeInMillis; rangeEnd = rangeStart + 24 * 60 * 60 * 1000L
        highlightPeriod(0)
    }
    private fun setRangeThisWeek() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        rangeStart = cal.timeInMillis; rangeEnd = System.currentTimeMillis()
        highlightPeriod(1)
    }
    private fun setRangeThisMonth() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        rangeStart = cal.timeInMillis; rangeEnd = System.currentTimeMillis()
        highlightPeriod(2)
    }
    private fun setRangeAllTime() {
        rangeStart = 0L; rangeEnd = System.currentTimeMillis()
        highlightPeriod(3)
    }

    private fun loadData() = lifecycleScope.launch {
        val db = PosDatabase.get(this@InventoryInsightsActivity)
        summaryBox.removeAllViews()
        resultsBox.removeAllViews()
        when (mode) {
            MODE_REORDER -> loadReorder(db)
            MODE_DAMAGE -> loadDamage(db)
            MODE_PROFIT -> loadProfit(db)
            MODE_MOVERS -> loadMovers(db)
        }
    }

    // ---------------- Reorder Suggestions ----------------
    private suspend fun loadReorder(db: PosDatabase) {
        val low = db.productDao().lowStock().first().filter { it.reorderLevel > 0.0 }.sortedBy { it.name.lowercase() }
        summaryBox.addView(summaryCard("\u26A0\uFE0F", Loc.t(this, "Items to reorder", "دوبارہ آرڈر کرنے والے آئٹمز"), "${low.size}", red, "#FDE8E8"))
        if (low.isEmpty()) { showEmpty(Loc.t(this, "Nothing needs reordering right now", "ابھی کچھ بھی دوبارہ آرڈر کرنے کی ضرورت نہیں")); return }
        low.forEach { p ->
            // Simple, transparent heuristic: suggest topping back up to DOUBLE the
            // reorder level (a common "reorder point vs target stock" rule of thumb),
            // so the shop doesn't dip below the reorder level again immediately.
            val suggested = (p.reorderLevel * 2 - p.stock).coerceAtLeast(p.reorderLevel - p.stock)
            resultsBox.addView(rowCard(
                title = p.name,
                subtitle = Loc.t(this, "Current: ", "موجودہ: ") + p.formatStockBreakdown() + "  •  " + Loc.t(this, "Reorder level: ", "دوبارہ آرڈر کی سطح: ") + "%.0f ${p.smallestUnitName()}".format(p.reorderLevel),
                rightTop = "+${"%.0f".format(suggested)} ${p.smallestUnitName()}",
                rightTopColor = amber,
                rightBottom = Loc.t(this, "suggested", "تجویز کردہ")
            ))
        }
    }

    // ---------------- Damage / Loss Report ----------------
    private suspend fun loadDamage(db: PosDatabase) {
        val movements = db.stockMovementDao().damageBetween(rangeStart, rangeEnd)
        val products = db.productDao().all().first().associateBy { it.barcode }
        val totalLoss = movements.sumOf { m -> kotlin.math.abs(m.qty) * m.cost }
        summaryBox.addView(summaryCard("\uD83D\uDCB8", Loc.t(this, "Total loss value", "کل نقصان کی مالیت"), "Rs %.2f".format(totalLoss), red, "#FDE8E8"))
        summaryBox.addView(summaryCard("\uD83D\uDCE6", Loc.t(this, "Entries logged", "درج اندراجات"), "${movements.size}", amber, "#FFF3E0"))
        if (movements.isEmpty()) { showEmpty(Loc.t(this, "No damage/loss logged for this period", "اس مدت میں کوئی نقصان درج نہیں")); return }
        movements.forEach { m ->
            val p = products[m.barcode]
            resultsBox.addView(rowCard(
                title = p?.name ?: m.barcode,
                subtitle = (if (m.note.isNotBlank()) m.note + "  •  " else "") + java.text.SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(java.util.Date(m.createdAt)),
                rightTop = "-%.0f ${m.unit}".format(kotlin.math.abs(m.qty)),
                rightTopColor = red,
                rightBottom = "Rs %.2f".format(kotlin.math.abs(m.qty) * m.cost)
            ))
        }
    }

    // ---------------- Profit Margin per Item ----------------
    private suspend fun loadProfit(db: PosDatabase) {
        val products = db.productDao().all().first().filter { it.salePrice > 0.0 }
        val avgMargin = if (products.isNotEmpty()) products.map { marginPercent(it) }.average() else 0.0
        summaryBox.addView(summaryCard("\uD83D\uDCC8", Loc.t(this, "Average margin", "اوسط منافع"), "%.1f%%".format(avgMargin), teal, "#E0F2F1"))
        val lowMarginCount = products.count { marginPercent(it) < 10.0 }
        summaryBox.addView(summaryCard("\u26A0\uFE0F", Loc.t(this, "Under 10% margin", "10% سے کم منافع"), "$lowMarginCount", red, "#FDE8E8"))
        if (products.isEmpty()) { showEmpty(Loc.t(this, "No priced items yet", "ابھی کوئی قیمت والا آئٹم نہیں")); return }
        // Worst margin first — the most actionable ordering (items barely making money).
        products.sortedBy { marginPercent(it) }.forEach { p ->
            val margin = marginPercent(p)
            val color = when { margin < 10.0 -> red; margin < 25.0 -> amber; else -> teal }
            resultsBox.addView(rowCard(
                title = p.name,
                subtitle = Loc.t(this, "Cost: ", "لاگت: ") + "Rs %.2f".format(p.cost) + "   " + Loc.t(this, "Sale: ", "سیل: ") + "Rs %.2f".format(p.salePrice),
                rightTop = "%.1f%%".format(margin),
                rightTopColor = color,
                rightBottom = Loc.t(this, "margin", "منافع")
            ))
        }
    }
    private fun marginPercent(p: Product): Double = if (p.salePrice > 0.0) ((p.salePrice - p.cost) / p.salePrice) * 100.0 else 0.0

    // ---------------- Fast / Slow Movers ----------------
    private suspend fun loadMovers(db: PosDatabase) {
        val movement = db.saleDao().itemMovementBetween(rangeStart, rangeEnd).associateBy { it.barcode }
        val products = db.productDao().all().first()
        data class Row(val name: String, val barcode: String, val qty: Double, val amount: Double)
        val rows = products.map { p ->
            val m = movement[p.barcode]
            Row(p.name, p.barcode, m?.totalQty ?: 0.0, m?.totalAmount ?: 0.0)
        }
        val totalUnitsSold = rows.sumOf { it.qty }
        summaryBox.addView(summaryCard("\uD83D\uDD25", Loc.t(this, "Units sold this period", "اس مدت میں فروخت شدہ یونٹس"), "%.0f".format(totalUnitsSold), teal, "#E0F2F1"))

        resultsBox.addView(sectionLabel(Loc.t(this, "\uD83D\uDD25 FAST MOVERS", "\uD83D\uDD25 تیز چلنے والے")))
        val fast = rows.sortedByDescending { it.qty }.take(15).filter { it.qty > 0 }
        if (fast.isEmpty()) resultsBox.addView(smallNote(Loc.t(this, "No sales in this period yet", "اس مدت میں ابھی کوئی سیل نہیں")))
        fast.forEach { r ->
            resultsBox.addView(rowCard(title = r.name, subtitle = "Rs %.2f".format(r.amount), rightTop = "%.0f".format(r.qty), rightTopColor = teal, rightBottom = Loc.t(this, "sold", "فروخت")))
        }

        resultsBox.addView(spacer(14))
        resultsBox.addView(sectionLabel(Loc.t(this, "\u2744\uFE0F SLOW MOVERS", "\u2744\uFE0F سست چلنے والے")))
        val slow = rows.sortedBy { it.qty }.take(15)
        slow.forEach { r ->
            resultsBox.addView(rowCard(title = r.name, subtitle = if (r.qty == 0.0) Loc.t(this, "No sales this period", "اس مدت میں کوئی سیل نہیں") else "Rs %.2f".format(r.amount), rightTop = "%.0f".format(r.qty), rightTopColor = if (r.qty == 0.0) red else amber, rightBottom = Loc.t(this, "sold", "فروخت")))
        }
    }

    // ================= shared small views =================
    private fun showEmpty(text: String) {
        resultsBox.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor(textGray))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 40, 0, 0)
        })
    }

    private fun smallNote(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor(textGray))
        textSize = 12.5f
        setPadding(4, 4, 4, 12)
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11.5f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor(textGray))
        setPadding(4, 6, 4, 10)
    }

    private fun rowCard(title: String, subtitle: String, rightTop: String, rightTopColor: String, rightBottom: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 16, 20, 16)
            background = strokedBg(border, cardBg, 18)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 10) }
            applyElevation(this, 2f)
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        col.addView(TextView(this).apply { text = title; textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor(textDark)) })
        col.addView(TextView(this).apply { text = subtitle; textSize = 12f; setTextColor(Color.parseColor(textGray)); setPadding(0, 3, 0, 0) })
        card.addView(col)
        val rightCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        rightCol.addView(TextView(this).apply { text = rightTop; textSize = 14.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor(rightTopColor)) })
        rightCol.addView(TextView(this).apply { text = rightBottom; textSize = 10.5f; setTextColor(Color.parseColor(textGray)); setPadding(0, 2, 0, 0) })
        card.addView(rightCol)
        return card
    }

    private fun summaryCard(emoji: String, label: String, value: String, accentHex: String, tintHex: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22, 20, 22, 20)
            background = strokedBg(border, cardBg, 18)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 10) }
            applyElevation(this, 2f)
            addView(FrameLayout(this@InventoryInsightsActivity).apply {
                val size = (40 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(tintHex)) }
                addView(TextView(this@InventoryInsightsActivity).apply {
                    text = emoji; textSize = 16f; gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                })
            })
            val textCol = LinearLayout(this@InventoryInsightsActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 0, 0, 0) }
            textCol.addView(TextView(this@InventoryInsightsActivity).apply { text = label; setTextColor(Color.parseColor(textGray)); textSize = 12.5f; setTypeface(typeface, Typeface.BOLD) })
            textCol.addView(TextView(this@InventoryInsightsActivity).apply { text = value; setTextColor(Color.parseColor(accentHex)); textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(0, 4, 0, 0) })
            addView(textCol)
        }
    }

    private fun tabPill(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label; textSize = 12f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 18, 0, 18)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { onClick() }
    }
    private fun periodPill(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label; textSize = 11.5f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 16, 0, 16)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { onClick() }
    }

    // ================= SHARED UI HELPERS =================
    private fun premiumHeader(icon: String, title: String, subtitle: String): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(26, 22, 26, 22)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(primary), Color.parseColor(primaryDark))).apply { cornerRadius = 22f }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 20) }
            applyElevation(this, 10f)
        }
        header.addView(TextView(this).apply {
            text = "\u2039"; textSize = 20f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = ovalBg("#33FFFFFF")
            val px = (36 * resources.displayMetrics.density).toInt(); width = px; height = px
            setOnClickListener { finish() }
        })
        header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(14, 1) })
        header.addView(TextView(this).apply {
            text = icon; textSize = 18f; gravity = Gravity.CENTER
            background = ovalBg("#5C4DFF")
            val px = (42 * resources.displayMetrics.density).toInt(); width = px; height = px
        })
        header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(16, 1) })
        val headerCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        headerCol.addView(TextView(this).apply { text = title; textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
        headerCol.addView(TextView(this).apply { text = subtitle; textSize = 10.5f; setTextColor(Color.parseColor("#D8D3FF")); setPadding(0, 4, 0, 0) })
        header.addView(headerCol)
        return header
    }

    private fun ovalBg(colorHex: String) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(colorHex)) }
    private fun roundedBg(colorHex: String, radius: Int) = GradientDrawable().apply { setColor(Color.parseColor(colorHex)); cornerRadius = radius.toFloat() }
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
