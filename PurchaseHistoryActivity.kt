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
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
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

            if (row.status == "active") {
                val actionsRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    setPadding(0, 10, 0, 0)
                }
                actionsRow.addView(TextView(this).apply {
                    text = com.grocerypos.v11.util.Loc.t(this@PurchaseHistoryActivity, "↩ Return", "↩ واپسی")
                    textSize = 12.5f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor(teal))
                    setPadding(14, 6, 14, 6)
                    setOnClickListener { confirmReturnPurchase(row.billNo) }
                })
                actionsRow.addView(TextView(this).apply {
                    text = com.grocerypos.v11.util.Loc.t(this@PurchaseHistoryActivity, "🗑 Delete", "🗑 حذف کریں")
                    textSize = 12.5f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor(red))
                    setPadding(14, 6, 0, 6)
                    setOnClickListener { confirmDeletePurchase(row.billNo) }
                })
                card.addView(actionsRow)
            }

            listContainer.addView(card)
        }
    }

    // FIX (partial purchase return): "Return" used to only offer returning the ENTIRE
    // bill in one shot, even when the actual issue was e.g. 3 of 10 units of one line
    // being faulty/short — there was no way to send back just those 3. This now opens
    // a per-line quantity picker so only the lines/quantities actually being sent back
    // get reversed — see openReturnPurchaseDialog()/processPartialReturn() below.
    // Returning the full qty on every line still works exactly like the old whole-bill
    // return (see the "remainingItemCount == 0" branch in processPartialReturn()).
    private fun confirmReturnPurchase(billNo: String) = safeLaunch("openReturnDialog") {
        val db = PosDatabase.get(this@PurchaseHistoryActivity)
        val purchase = db.purchaseDao().findPurchase(billNo) ?: return@safeLaunch
        if (purchase.status == "returned") return@safeLaunch
        val items = db.purchaseDao().itemsForBill(billNo)
        if (items.isEmpty()) return@safeLaunch
        val rows = items.map { item ->
            val product = db.productDao().find(item.barcode)
            ReturnRow(item, product?.name ?: item.barcode, item.unit.ifBlank { product?.unit ?: "" })
        }
        openReturnPurchaseDialog(billNo, rows)
    }

    private data class ReturnRow(val item: PurchaseItem, val productName: String, val unit: String)

    private fun openReturnPurchaseDialog(billNo: String, rows: List<ReturnRow>) {
        // FIX (dialog buttons hidden off-screen): capping just the item list's height
        // wasn't enough — on some devices the AlertDialog's own title+message chrome plus
        // an uncapped list could still add up to taller than the screen, pushing the
        // Return/Cancel buttons out of view with no way to reach them. Now the ENTIRE
        // dialog (title, message, list, buttons) is one custom layout whose total height
        // is hard-capped to a share of the screen — the item list is the only part that
        // flexes/scrolls, so the button row at the bottom is always on-screen.
        val fields = LinkedHashMap<Long, EditText>()
        val itemsById = rows.associateBy { it.item.id }

        val itemsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 8, 36, 8)
        }
        for (r in rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 14, 0, 14)
            }
            row.addView(TextView(this).apply {
                text = r.productName
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(textDark))
            })
            row.addView(TextView(this).apply {
                text = com.grocerypos.v11.util.Loc.t(
                    this@PurchaseHistoryActivity,
                    "Purchased: ${formatQty(r.item.qty)} ${r.unit}",
                    "خریدی گئی مقدار: ${formatQty(r.item.qty)} ${r.unit}"
                )
                textSize = 12f
                setTextColor(Color.parseColor(textMuted))
                setPadding(0, 2, 0, 8)
            })
            val input = EditText(this).apply {
                hint = com.grocerypos.v11.util.Loc.t(this@PurchaseHistoryActivity, "Return qty (leave blank to skip)", "واپسی مقدار (چھوڑنے کے لیے خالی رکھیں)")
                setHintTextColor(Color.parseColor(textMuted))
                setTextColor(Color.parseColor(textDark))
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                background = strokedBg(border, fieldFill, 10)
                setPadding(22, 16, 22, 16)
            }
            fields[r.item.id] = input
            row.addView(input)
            itemsContainer.addView(row)
        }

        val itemsScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(itemsContainer)
        }

        val titleView = TextView(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@PurchaseHistoryActivity, "Return items", "آئٹمز واپس کریں")
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor(textDark))
            setPadding(40, 32, 40, 8)
        }
        val messageView = TextView(this).apply {
            text = com.grocerypos.v11.util.Loc.t(
                this@PurchaseHistoryActivity,
                "Enter how many units of each item are being returned. Stock and supplier balance will be adjusted only for those quantities.",
                "ہر آئٹم کی کتنی مقدار واپس ہو رہی ہے درج کریں۔ صرف انہی مقداروں کے مطابق اسٹاک اور سپلائر بیلنس ایڈجسٹ ہو گا۔"
            )
            textSize = 13f
            setTextColor(Color.parseColor(textMuted))
            setPadding(40, 0, 40, 8)
        }

        val cancelBtn = TextView(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@PurchaseHistoryActivity, "Cancel", "منسوخ کریں")
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor(textMuted))
            background = strokedBg(border, fieldFill, 12)
            setPadding(0, 20, 0, 20)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val returnBtn = TextView(this).apply {
            text = com.grocerypos.v11.util.Loc.t(this@PurchaseHistoryActivity, "Return", "واپسی")
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = roundedBg(gold, 12)
            setPadding(0, 20, 0, 20)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams((12 * resources.displayMetrics.density).toInt(), 1)
        }
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(40, 16, 40, 32)
            addView(cancelBtn)
            addView(spacer)
            addView(returnBtn)
        }

        val maxDialogHeightPx = (resources.displayMetrics.heightPixels * 0.82).toInt()
        val root = object : LinearLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val mode = android.view.View.MeasureSpec.getMode(heightMeasureSpec)
                val size = android.view.View.MeasureSpec.getSize(heightMeasureSpec)
                val cappedSize = if (mode == android.view.View.MeasureSpec.UNSPECIFIED) maxDialogHeightPx else size.coerceAtMost(maxDialogHeightPx)
                val newMode = if (mode == android.view.View.MeasureSpec.UNSPECIFIED) android.view.View.MeasureSpec.AT_MOST else mode
                super.onMeasure(widthMeasureSpec, android.view.View.MeasureSpec.makeMeasureSpec(cappedSize, newMode))
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(messageView)
            addView(itemsScroll)
            addView(footer)
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setView(root)
            .create()

        cancelBtn.setOnClickListener { dialog.dismiss() }
        returnBtn.setOnClickListener {
            val requested = LinkedHashMap<Long, Double>()
            var errorMsg: String? = null
            for ((id, field) in fields) {
                val text = field.text.toString().trim()
                if (text.isEmpty()) continue
                val qty = text.toDoubleOrNull()
                val row = itemsById[id] ?: continue
                when {
                    qty == null || qty < 0 -> {
                        errorMsg = com.grocerypos.v11.util.Loc.t(this, "Enter a valid quantity for \"${row.productName}\"", "\"${row.productName}\" کے لیے درست مقدار درج کریں")
                    }
                    qty == 0.0 -> { /* treated as skip */ }
                    qty > row.item.qty + 0.0001 -> {
                        errorMsg = com.grocerypos.v11.util.Loc.t(
                            this,
                            "Return qty for \"${row.productName}\" can't exceed purchased qty (${formatQty(row.item.qty)})",
                            "\"${row.productName}\" کی واپسی مقدار خریدی گئی مقدار (${formatQty(row.item.qty)}) سے زیادہ نہیں ہو سکتی"
                        )
                    }
                    else -> requested[id] = qty
                }
                if (errorMsg != null) break
            }
            if (errorMsg != null) {
                Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (requested.isEmpty()) {
                Toast.makeText(this, com.grocerypos.v11.util.Loc.t(this, "Enter a return quantity for at least one item", "کم از کم ایک آئٹم کے لیے واپسی مقدار درج کریں"), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            processPartialReturn(billNo, requested)
        }
        dialog.show()
    }

    private fun formatQty(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    private fun confirmDeletePurchase(billNo: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle(com.grocerypos.v11.util.Loc.t(this, "Delete purchase", "خریداری حذف کریں"))
            .setMessage(com.grocerypos.v11.util.Loc.t(
                this,
                "Delete this purchase? This will reverse its stock and cost changes. This can't be undone.",
                "یہ خریداری حذف کریں؟ اس سے اسٹاک اور لاگت واپس ہو جائے گی۔ اسے واپس نہیں لیا جا سکتا۔"
            ))
            .setPositiveButton(com.grocerypos.v11.util.Loc.t(this, "Delete", "حذف کریں")) { _, _ -> deletePurchase(billNo) }
            .setNegativeButton(com.grocerypos.v11.util.Loc.t(this, "Cancel", "منسوخ کریں"), null)
            .show()
    }

    // Mirrors HistoryActivity.reverseStockAndCostForPurchaseItems() — same negative-stock
    // guard (refuses the whole return/delete if any line can't be reversed cleanly) and same
    // weighted-average cost reversal math, kept in sync so all three history entry points
    // (this screen, SaleHistoryActivity's purchase-side twin doesn't exist, and HistoryActivity)
    // behave identically.
    private suspend fun reverseStockAndCostForPurchaseItems(db: PosDatabase, items: List<PurchaseItem>) {
        items.forEach { pi ->
            val product = db.productDao().find(pi.barcode) ?: return@forEach
            val smallestQty = pi.smallestQty(product)
            if (smallestQty > 0 && smallestQty > product.stock) {
                throw IllegalStateException(
                    "\"${product.name}\" ka stock is purchase ke baad already kam ho chuka hai " +
                    "(sale ya doosri entry se) — is purchase ko edit/delete karna cost ko galat kar dega. " +
                    "Iski jagah stock adjustment karen."
                )
            }
        }
        for (pi in items) {
            val product = db.productDao().find(pi.barcode) ?: continue
            val factor = product.smallestUnitFactor()
            val smallestQty = pi.smallestQty(product)
            if (smallestQty <= 0) continue

            val currentCostPerSmallest = if (factor > 0) product.cost / factor else product.cost
            val currentStock = product.stock
            val newStock = currentStock - smallestQty

            val totalValueBefore = currentStock * currentCostPerSmallest
            val totalValueAfterRemoval = (totalValueBefore - pi.amount).coerceAtLeast(0.0)
            val newCostPerSmallest = if (newStock > 0) totalValueAfterRemoval / newStock else 0.0
            val newCost = newCostPerSmallest * factor

            SyncQueueHelper.decreaseProductStockForce(db, pi.barcode, smallestQty, "PURCHASE_REVERSAL", pi.billNo, newCost)
            SyncQueueHelper.updateProductCost(db, pi.barcode, newCost)
        }
    }

    // FIX (partial purchase return): does the actual line-level return picked in
    // openReturnPurchaseDialog(). For each returned quantity: reverses ONLY that
    // portion of stock/cost (using the SAME weighted-average math as
    // reverseStockAndCostForPurchaseItems()/PartyTransactionActivity's
    // reversePurchaseLineCost(), just scaled to the returned qty instead of the whole
    // line), shrinks the purchase line by that qty (or removes it if fully returned),
    // logs a ReturnLine for exactly the returned qty/amount, and shrinks the bill's
    // total/paid/supplier-balance by the returned amount instead of reversing the
    // whole bill. If every line ends up fully returned, the bill is marked "returned"
    // exactly like the old whole-bill returnPurchase() path (including clearing its
    // cash/payment records), so a full return via this same dialog still behaves
    // identically to before.
    private fun processPartialReturn(billNo: String, requested: Map<Long, Double>) = safeLaunch("processPartialReturn") {
        val db = PosDatabase.get(this@PurchaseHistoryActivity)
        try {
            db.withTransaction {
                val purchase = db.purchaseDao().findPurchase(billNo) ?: return@withTransaction
                if (purchase.status == "returned") return@withTransaction

                var totalReturnedAmount = 0.0

                for ((itemId, returnQty) in requested) {
                    if (returnQty <= 0.0) continue
                    val item = db.purchaseDao().findItem(itemId) ?: continue
                    val clampedQty = returnQty.coerceAtMost(item.qty)
                    if (clampedQty <= 0.0) continue

                    val product = db.productDao().find(item.barcode)
                    val smallestQtyToRemove = partialSmallestQty(item, product, clampedQty)
                    val returnedAmount = if (item.qty > 0) item.amount * (clampedQty / item.qty) else item.unitCost * clampedQty

                    if (product != null && smallestQtyToRemove > 0) {
                        if (smallestQtyToRemove > product.stock) {
                            throw IllegalStateException(
                                "\"${product.name}\" ka stock is purchase ke baad already kam ho chuka hai " +
                                "(sale ya doosri entry se) — itni miqdaar wapas karna cost ko galat kar dega."
                            )
                        }
                        val newCost = reversePurchaseLineCostPartial(product, smallestQtyToRemove, returnedAmount)
                        SyncQueueHelper.decreaseProductStockForce(db, item.barcode, smallestQtyToRemove, "PURCHASE_RETURN", billNo, newCost)
                        SyncQueueHelper.updateProductCost(db, item.barcode, newCost)
                        db.productDao().find(item.barcode)?.let { p -> SyncQueueHelper.enqueueProduct(db, p) }
                    }

                    db.returnDao().insert(ReturnLine(reference = billNo, type = "purchase", barcode = item.barcode, qty = clampedQty, amount = returnedAmount))

                    val remainingQty = item.qty - clampedQty
                    if (remainingQty <= 0.0001) {
                        db.purchaseDao().deleteItemById(item.id)
                    } else {
                        db.purchaseDao().updateItemRow(item.copy(qty = remainingQty, amount = item.amount - returnedAmount))
                    }

                    totalReturnedAmount += returnedAmount
                }

                if (totalReturnedAmount <= 0.0) return@withTransaction

                val remainingItemCount = db.purchaseDao().itemCountForBill(billNo)
                val oldOutstanding = purchase.total - purchase.paid

                if (remainingItemCount == 0) {
                    // Every line on the bill ended up fully returned — same end state as
                    // the old whole-bill returnPurchase().
                    if (purchase.supplierId != null && oldOutstanding > 0) {
                        SyncQueueHelper.adjustSupplierBalance(db, purchase.supplierId, -oldOutstanding)
                    }
                    db.cashTransactionDao().deleteByReference(billNo)
                    db.paymentDao().deleteByReference(billNo)
                    db.purchaseDao().markReturned(billNo)
                    val updatedPurchase = purchase.copy(
                        subtotal = (purchase.subtotal - totalReturnedAmount).coerceAtLeast(0.0),
                        total = (purchase.total - totalReturnedAmount).coerceAtLeast(0.0)
                    )
                    db.purchaseDao().updatePurchase(updatedPurchase)
                    SyncQueueHelper.enqueuePurchase(db, updatedPurchase)
                } else {
                    val newTotal = (purchase.total - totalReturnedAmount).coerceAtLeast(0.0)
                    val newPaid = reconcilePaidAfterReturn(db, billNo, purchase.paid, newTotal)
                    val updatedPurchase = purchase.copy(
                        subtotal = (purchase.subtotal - totalReturnedAmount).coerceAtLeast(0.0),
                        total = newTotal,
                        paid = newPaid
                    )
                    db.purchaseDao().updatePurchase(updatedPurchase)
                    if (purchase.supplierId != null) {
                        val newOutstanding = newTotal - newPaid
                        val delta = newOutstanding - oldOutstanding
                        if (delta != 0.0) SyncQueueHelper.adjustSupplierBalance(db, purchase.supplierId, delta)
                    }
                    SyncQueueHelper.enqueuePurchase(db, updatedPurchase)
                }
            }
            Toast.makeText(this@PurchaseHistoryActivity, com.grocerypos.v11.util.Loc.t(this@PurchaseHistoryActivity, "Items returned", "آئٹمز واپس ہو گئے"), Toast.LENGTH_SHORT).show()
            loadPurchases()
        } catch (e: IllegalStateException) {
            Toast.makeText(this@PurchaseHistoryActivity, e.message ?: "Return nahi ho saka", Toast.LENGTH_LONG).show()
        }
    }

    // Same frozen-conversionFactor reasoning as PurchaseItem.smallestQty(product) in
    // Database.kt, but for a QUANTITY BEING RETURNED (which may be less than the
    // line's full qty) instead of the whole line.
    private fun partialSmallestQty(item: PurchaseItem, product: Product?, returnQty: Double): Double =
        if (item.conversionFactor > 0) returnQty * item.conversionFactor
        else product?.toSmallestUnits(returnQty, item.unit.ifBlank { product.unit }) ?: returnQty

    // Same weighted-average reversal math as reverseStockAndCostForPurchaseItems()
    // above (and PartyTransactionActivity.reversePurchaseLineCost()), but taking the
    // qty/amount to remove as parameters so it can be used for a PARTIAL line return
    // instead of always reversing the whole line.
    private fun reversePurchaseLineCostPartial(product: Product, smallestQtyToRemove: Double, amountToRemove: Double): Double {
        if (smallestQtyToRemove <= 0) return product.cost
        val factor = product.smallestUnitFactor()
        val currentCostPerSmallest = if (factor > 0) product.cost / factor else product.cost
        val currentStock = product.stock
        val newStock = currentStock - smallestQtyToRemove
        val totalValueBefore = currentStock * currentCostPerSmallest
        val totalValueAfterRemoval = (totalValueBefore - amountToRemove).coerceAtLeast(0.0)
        val newCostPerSmallest = if (newStock > 0) totalValueAfterRemoval / newStock else 0.0
        return newCostPerSmallest * factor
    }

    // Same "cap paid at the new (smaller) total and shrink the linked cash/payment
    // record by the same amount" reasoning as PartyTransactionActivity's
    // reconcilePaidAndCashRecords(), scoped here to purchases only and to the
    // paid-can-only-go-down direction a return implies.
    private suspend fun reconcilePaidAfterReturn(db: PosDatabase, reference: String, oldPaid: Double, newTotal: Double): Double {
        val newPaid = oldPaid.coerceIn(0.0, newTotal.coerceAtLeast(0.0))
        val paidDelta = newPaid - oldPaid
        if (paidDelta == 0.0) return newPaid

        db.cashTransactionDao().findByReference(reference)?.let { tx ->
            val updatedTx = tx.copy(amount = (tx.amount + paidDelta).coerceAtLeast(0.0), updatedAt = System.currentTimeMillis(), dirty = true)
            db.cashTransactionDao().update(updatedTx)
            SyncQueueHelper.enqueueCashTransaction(db, updatedTx)
        }
        db.paymentDao().findByReference(reference)?.let { pay ->
            val updatedPay = pay.copy(amount = (pay.amount + paidDelta).coerceAtLeast(0.0), updatedAt = System.currentTimeMillis(), dirty = true)
            db.paymentDao().update(updatedPay)
            SyncQueueHelper.enqueuePayment(db, updatedPay)
        }
        return newPaid
    }

    private fun deletePurchase(billNo: String) = safeLaunch("deletePurchase") {
        val db = PosDatabase.get(this@PurchaseHistoryActivity)
        val purchase = db.purchaseDao().findPurchase(billNo) ?: return@safeLaunch
        val items = db.purchaseDao().itemsForBill(billNo)
        try {
            db.withTransaction {
                reverseStockAndCostForPurchaseItems(db, items)
                if (purchase.supplierId != null && purchase.paid < purchase.total) {
                    SyncQueueHelper.adjustSupplierBalance(db, purchase.supplierId, -(purchase.total - purchase.paid))
                }
                db.cashTransactionDao().deleteByReference(billNo)
                db.paymentDao().deleteByReference(billNo)
                db.purchaseDao().deleteItems(billNo)
                db.purchaseDao().deletePurchase(billNo)
            }
            Toast.makeText(this@PurchaseHistoryActivity, "Purchase deleted", Toast.LENGTH_SHORT).show()
            loadPurchases()
        } catch (e: IllegalStateException) {
            Toast.makeText(this@PurchaseHistoryActivity, e.message ?: "Delete nahi ho saka", Toast.LENGTH_LONG).show()
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
