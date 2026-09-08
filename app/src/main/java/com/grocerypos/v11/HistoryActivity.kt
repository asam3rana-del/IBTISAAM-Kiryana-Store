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
import android.view.ViewOutlineProvider
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.R
import com.grocerypos.v11.ReturnLine
import com.grocerypos.v11.SyncQueueHelper
import com.grocerypos.v11.smallestUnitFactor
import com.grocerypos.v11.smallestQty
import com.grocerypos.v11.toSmallestUnits
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.grocerypos.v11.ui.components.*

/**
 * ---- CHANGE (ultra-premium UI pass) ----
 * Restyled to match ReportsActivity / StockReportActivity / BalanceSheetActivity /
 * PartyReportsActivity: same palette, premiumHeader(), strokedBg cards with
 * applyElevation(), pill tabs, and icon-badge rows — carried all the way into the
 * Sale/Purchase detail dialogs and their Close/Edit/Return/Delete action buttons.
 * No business logic changed: every DB call, the atomic withTransaction blocks for
 * return/delete, and the stock/cost reversal math are byte-for-byte the same as
 * before — only the view-building code changed.
 */
class HistoryActivity : AppCompatActivity() {

    companion object {
        // ADDED: lets Reports link straight into Sale History or Purchase History
        // without an extra tap on the in-screen SALES/PURCHASES tabs.
        const val EXTRA_MODE = "history_mode"
        const val MODE_SALES = "sales"
        const val MODE_PURCHASES = "purchases"
    }

    // ================= PREMIUM PALETTE (shared with Reports / Stock / Balance Sheet) =================
    // Pulled from ThemeManager so this screen respects dark mode. Headers were two-tone
    // gradients (primary/primaryDark, gold/goldDark); now flat like the rest of the app.
    private var bg = "#F3F2FA"
    private var cardBg = "#FFFFFF"
    private var primary = "#4A3AFF"
    private var primaryDark = "#4A3AFF"
    private var amber = "#F5A524"
    private var teal = "#0F9B8E"
    private var gold = "#C9A24B"
    private var goldDark = "#C9A24B"
    private var red = "#E5484D"
    private var textDark = "#1A1A2E"
    private var textGray = "#8A8A9E"
    private var border = "#E7E5F3"

    private fun loadThemeColors() {
        val p = com.grocerypos.v11.util.ThemeManager.palette(this)
        bg = p.bg
        cardBg = p.cardWhite
        primary = p.flatPurpleFg
        primaryDark = p.flatPurpleFg
        amber = p.flatAmberFg
        teal = p.flatTealFg
        gold = p.flatAmberFg
        goldDark = p.flatAmberFg
        red = p.red
        textDark = p.textDark
        textGray = p.textMuted
        border = p.border
    }

    private lateinit var tabRow: LinearLayout
    private lateinit var salesTab: TextView
    private lateinit var purchasesTab: TextView
    private lateinit var listContainer: LinearLayout
    private var showingSales = true
    private var singleMode: String? = null
    private lateinit var headerBox: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        loadThemeColors()
        singleMode = intent.getStringExtra(EXTRA_MODE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            setBackgroundColor(Color.parseColor(bg))
        }

        // CHANGE (dedicated Sale History / Purchase History screens): when opened from
        // the Reports "Sale History" or "Purchase History" tile, this is now a true
        // single-purpose screen — its own icon/title/gradient, no SALES/PURCHASES tab
        // switcher at all, so tapping one tile can never end up showing the other
        // list. The old combined tabbed view (both lists, switchable) is kept as the
        // fallback only for any code path that opens this Activity with no mode extra.
        headerBox = when (singleMode) {
            MODE_SALES -> premiumHeader(
                R.drawable.ic_receipt,
                Loc.t(this, "Sale History", "سیل کی تاریخ"),
                Loc.t(this, "View all sale transactions", "تمام سیل لین دین دیکھیں"),
                primary, primaryDark
            )
            MODE_PURCHASES -> premiumHeader(
                R.drawable.ic_cart,
                Loc.t(this, "Purchase History", "خریداری کی تاریخ"),
                Loc.t(this, "View all purchase transactions", "تمام خریداری لین دین دیکھیں"),
                gold, goldDark
            )
            else -> premiumHeader(
                R.drawable.ic_receipt,
                Loc.t(this, "Sale / Purchase History", "سیل / خریداری کی تاریخ"),
                Loc.t(this, "Tap any entry to view details", "تفصیل دیکھنے کے لیے کسی بھی اندراج پر ٹیپ کریں"),
                primary, primaryDark
            )
        }
        root.addView(headerBox)

        if (singleMode == null) {
            tabRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = strokedBg(border, cardBg, 14)
                setPadding(6, 6, 6, 6)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, 0, 0, 16) }
            }
            salesTab = filterPill(Loc.t(this, "SALES", "سیلز")) { showSales() }
            purchasesTab = filterPill(Loc.t(this, "PURCHASES", "خریداریاں")) { showPurchases() }
            tabRow.addView(salesTab)
            tabRow.addView(purchasesTab)
            root.addView(tabRow)
        }

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)
        root.addView(spacer(30))

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        }
        setContentView(scroll)

        if (singleMode == null) refreshTabs()
        // Land directly on the requested list (Sale History vs Purchase History) —
        // no extra tap on the tabs needed when opened from the new separate Reports tiles.
        if (singleMode == MODE_PURCHASES) showPurchases() else showSales()
    }

    override fun onResume() { super.onResume(); if (showingSales) loadSales() else loadPurchases() }

    private fun refreshTabs() {
        if (singleMode != null) return
        if (showingSales) {
            salesTab.background = roundedBg(primary, 10)
            salesTab.setTextColor(Color.WHITE)
            purchasesTab.setBackgroundColor(Color.TRANSPARENT)
            purchasesTab.setTextColor(Color.parseColor(textGray))
        } else {
            purchasesTab.background = roundedBg(teal, 10)
            purchasesTab.setTextColor(Color.WHITE)
            salesTab.setBackgroundColor(Color.TRANSPARENT)
            salesTab.setTextColor(Color.parseColor(textGray))
        }
    }

    private fun showSales() { showingSales = true; refreshTabs(); loadSales() }
    private fun showPurchases() { showingSales = false; refreshTabs(); loadPurchases() }

    private fun loadSales() {
        lifecycleScope.launch {
            val list = PosDatabase.get(this@HistoryActivity).saleDao().allSales()
            listContainer.removeAllViews()
            if (list.isEmpty()) { listContainer.addView(emptyText(Loc.t(this@HistoryActivity, "No sales yet", "کوئی سیل نہیں ہوئی"))); return@launch }
            val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            for (s in list) listContainer.addView(
                row(R.drawable.ic_receipt, s.invoice, s.customerName, s.total, fmt.format(Date(s.createdAt)), primary, "#E9E6FF", s.status == "returned") { openSaleDetail(s.invoice) }
            )
        }
    }

    private fun loadPurchases() {
        lifecycleScope.launch {
            val list = PosDatabase.get(this@HistoryActivity).purchaseDao().allPurchases()
            listContainer.removeAllViews()
            if (list.isEmpty()) { listContainer.addView(emptyText(Loc.t(this@HistoryActivity, "No purchases yet", "کوئی خریداری نہیں ہوئی"))); return@launch }
            val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            for (p in list) listContainer.addView(
                row(R.drawable.ic_cart, p.billNo, p.supplierName, p.total, fmt.format(Date(p.createdAt)), gold, "#F6EFDD", p.status == "returned") { openPurchaseDetail(p.billNo) }
            )
        }
    }

    private fun openSaleDetail(invoice: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@HistoryActivity)
            val sale = db.saleDao().findSale(invoice) ?: return@launch
            val items = db.saleDao().itemsForInvoice(invoice)
            val content = detailContainer(R.drawable.ic_receipt, primary, "#E9E6FF", Loc.t(this@HistoryActivity, "Sale", "سیل"), invoice)
            val body = content.getChildAt(1) as LinearLayout
            if (sale.status == "returned") body.addView(returnedBanner())
            body.addView(kv(Loc.t(this@HistoryActivity, "Total", "کل"), "Rs %.2f".format(sale.total)))
            body.addView(kv(Loc.t(this@HistoryActivity, "Paid", "ادا شدہ"), "Rs %.2f".format(sale.paid)))
            body.addView(spacer(12))
            body.addView(sectionTitle(Loc.t(this@HistoryActivity, "Items", "آئٹمز")))
            for (it in items) body.addView(itemRow(it.product, "${it.qty} x ${it.unitPrice}", "Rs %.2f".format(it.amount)))
            val dialog = AlertDialog.Builder(this@HistoryActivity).setView(content).create()
            val footer = content.getChildAt(2) as LinearLayout
            footer.addView(outlineButton(Loc.t(this@HistoryActivity, "Close", "بند کریں")) { dialog.dismiss() })
            if (sale.status != "returned") {
                footer.addView(spacerH(8))
                footer.addView(filledButton(Loc.t(this@HistoryActivity, "Return", "واپس"), amber) { returnSale(invoice); dialog.dismiss() })
                footer.addView(spacerH(8))
                footer.addView(filledButton(Loc.t(this@HistoryActivity, "Delete", "حذف کریں"), red) { deleteSale(invoice); dialog.dismiss() })
            }
            dialog.show()
        }
    }

    // ---- FIX: stock reversal now converts si.qty (stored in whatever unit was entered,
    // e.g. "dozen") to smallest-unit stock via Product.toSmallestUnits() before touching
    // stock, same as SaleActivity.deleteSale() / SaleHistoryActivity.deleteSale(). Previously
    // this called SyncQueueHelper.increaseProductStock(db, it.barcode, it.qty) directly, which added back the
    // raw entered-unit number as if it were already smallest units — wrong for any product
    // with a secondary/tertiary unit. ----
    // FIX (Phase 1 - Data Safety): stock increase + return-row insert + balance reversal +
    // markReturned are now one atomic transaction (previously separate sequential writes —
    // a crash partway through could leave stock/balance updated but the sale still "active",
    // or vice versa).
    // BUILD FIX: toSmallestUnits(...).roundToInt() is Int, but the `it.qty` fallback is a
    // Double (SaleItem.qty) — mixing them in `?:` produced an unresolved Number/Comparable
    // captured type that increase(barcode, Int) couldn't accept ("Argument type mismatch...
    // but 'kotlin.Int' was expected", :app:compileDebugKotlin failure). Fallback now rounds
    // it.qty to Int too, so both branches of the elvis are the same type.
    private fun returnSale(invoice: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@HistoryActivity); val sale = db.saleDao().findSale(invoice) ?: return@launch; if (sale.status == "returned") return@launch
            val items = db.saleDao().itemsForInvoice(invoice)
            db.withTransaction {
                for (it in items) {
                    val p = db.productDao().find(it.barcode)
                    val smallestQty = it.smallestQty(p)
                    SyncQueueHelper.increaseProductStock(db, it.barcode, smallestQty, "SALE_REVERSAL", invoice)
                    db.returnDao().insert(ReturnLine(reference = invoice, type = "sale", barcode = it.barcode, qty = it.qty.toDouble(), amount = it.amount))
                }
                if (sale.customerId != null && sale.paid < sale.total) SyncQueueHelper.adjustCustomerBalance(db, sale.customerId, -(sale.total - sale.paid))
                db.cashTransactionDao().deleteByReference(invoice); db.saleDao().markReturned(invoice)
            }
            loadSales()
        }
    }

    // FIX (Phase 1 - Data Safety): same atomic-transaction treatment as returnSale() above.
    // BUILD FIX: same Int/Double elvis mismatch as returnSale() above — fallback now rounds
    // it.qty to Int.
    private fun deleteSale(invoice: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@HistoryActivity); val sale = db.saleDao().findSale(invoice) ?: return@launch
            val items = db.saleDao().itemsForInvoice(invoice)
            db.withTransaction {
                for (it in items) {
                    val p = db.productDao().find(it.barcode)
                    val smallestQty = it.smallestQty(p)
                    SyncQueueHelper.increaseProductStock(db, it.barcode, smallestQty, "SALE_REVERSAL", invoice)
                }
                if (sale.customerId != null && sale.paid < sale.total) SyncQueueHelper.adjustCustomerBalance(db, sale.customerId, -(sale.total - sale.paid))
                db.cashTransactionDao().deleteByReference(invoice); db.saleDao().deleteItems(invoice); db.saleDao().deleteSale(invoice)
            }
            loadSales()
        }
    }

    private fun openPurchaseDetail(billNo: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@HistoryActivity)
            val purchase = db.purchaseDao().findPurchase(billNo) ?: return@launch
            val items = db.purchaseDao().itemsForBill(billNo)
            val content = detailContainer(R.drawable.ic_cart, gold, "#F6EFDD", Loc.t(this@HistoryActivity, "Purchase", "خریداری"), billNo)
            val body = content.getChildAt(1) as LinearLayout
            if (purchase.status == "returned") body.addView(returnedBanner())
            body.addView(kv(Loc.t(this@HistoryActivity, "Total", "کل"), "Rs %.2f".format(purchase.total)))
            body.addView(kv(Loc.t(this@HistoryActivity, "Paid", "ادا شدہ"), "Rs %.2f".format(purchase.paid)))
            body.addView(spacer(12))
            body.addView(sectionTitle(Loc.t(this@HistoryActivity, "Items", "آئٹمز")))
            // ---- FIX: was showing the raw barcode (it.barcode) instead of the product name.
            // PurchaseItem only stores the barcode, so look up the product to get its name —
            // same pattern already used by PurchaseHistoryActivity.loadBillItems(). Falls back
            // to the barcode only if the product record itself is missing/deleted. ----
            for (it in items) {
                val product = db.productDao().find(it.barcode)
                val displayName = product?.name ?: it.barcode
                val u = if (it.unit.isBlank()) "" else " ${it.unit}"
                body.addView(itemRow(displayName, "${it.qty}$u x ${it.unitCost}", "Rs %.2f".format(it.amount)))
            }
            val dialog = AlertDialog.Builder(this@HistoryActivity).setView(content).create()
            val footer = content.getChildAt(2) as LinearLayout
            footer.addView(outlineButton(Loc.t(this@HistoryActivity, "Close", "بند کریں")) { dialog.dismiss() })
            if (purchase.status != "returned") {
                footer.addView(spacerH(8))
                footer.addView(filledButton(Loc.t(this@HistoryActivity, "Edit", "ترمیم"), primary) {
                    dialog.dismiss()
                    startActivity(Intent(this@HistoryActivity, PurchaseActivity::class.java).putExtra(PurchaseActivity.EXTRA_BILL_NO, billNo))
                })
                footer.addView(spacerH(8))
                footer.addView(filledButton(Loc.t(this@HistoryActivity, "Return", "واپس"), amber) { dialog.dismiss(); openReturnPurchaseDialog(billNo, items) })
                footer.addView(spacerH(8))
                footer.addView(filledButton(Loc.t(this@HistoryActivity, "Delete", "حذف کریں"), red) { deletePurchase(billNo); dialog.dismiss() })
            }
            dialog.show()
        }
    }

    // ---- FIX: mirrors PurchaseActivity.reverseStockAndCostForItems() — converts item.qty via
    // Product.toSmallestUnits() before touching stock (previously used the raw entered-unit qty,
    // truncated with .toInt(), directly on decreaseForce — wrong for multi-unit products and lost
    // fractional qty), and also reverses the weighted-average cost impact so product.cost isn't
    // left distorted after a delete/return (previously not reversed at all). ----
    // FIX (item #23, negative stock on delete): this was still using
    // decreaseProductStockForce() with no pre-check, unlike PurchaseRepository's copy of
    // this same logic which already got the item #7 guard. If stock had already been drawn
    // down below this purchase's quantity (by a later sale, or another purchase edit/delete),
    // the force-decrease would silently push stock negative and corrupt the cost math. Now
    // validates every line FIRST — before any writes — and refuses the whole return/delete
    // if any line can't be reversed cleanly, same as PurchaseRepository.reverseStockAndCostForItems().
    private suspend fun reverseStockAndCostForPurchaseItems(db: PosDatabase, items: List<com.grocerypos.v11.PurchaseItem>) {
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
            // FIX (historical unit conversion bug): use pi.conversionFactor (frozen at
            // purchase time) instead of the product's CURRENT unit config — see
            // PurchaseRepository.reverseStockAndCostForItems() / Database.kt's
            // PurchaseItem.smallestQty() comment for the full explanation.
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

    // FIX (partial purchase return): "Return" used to only offer returning the ENTIRE
    // bill in one shot, even when the actual issue was e.g. 3 of 10 units of one line
    // being faulty/short — there was no way to send back just those 3. This now opens a
    // per-line quantity picker so only the lines/quantities actually being sent back get
    // reversed — see processPartialReturn() below (mirrors
    // PurchaseHistoryActivity's identically-named fix). Returning the full qty on every
    // line still behaves exactly like the old whole-bill return.
    private fun openReturnPurchaseDialog(billNo: String, items: List<com.grocerypos.v11.PurchaseItem>) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@HistoryActivity)
            if (items.isEmpty()) return@launch
            val rowMeta = items.map { item ->
                val product = db.productDao().find(item.barcode)
                Triple(item, product?.name ?: item.barcode, item.unit.ifBlank { product?.unit ?: "" })
            }

            // FIX (dialog buttons hidden off-screen): capping just the item list's height
            // wasn't enough — on some devices the dialog's own title+message chrome plus an
            // uncapped list could still add up to taller than the screen, pushing the
            // Return/Cancel buttons out of view with no way to reach them. Now the ENTIRE
            // dialog (title, message, list, buttons) is one custom layout whose total height
            // is hard-capped to a share of the screen — the item list is the only part that
            // flexes/scrolls, so the button row at the bottom is always on-screen.
            val container = LinearLayout(this@HistoryActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(36, 8, 36, 8)
            }
            val fields = LinkedHashMap<Long, EditText>()
            for ((item, name, unit) in rowMeta) {
                val row = LinearLayout(this@HistoryActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 14, 0, 14)
                }
                row.addView(TextView(this@HistoryActivity).apply {
                    text = name
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor(textDark))
                })
                row.addView(TextView(this@HistoryActivity).apply {
                    text = Loc.t(this@HistoryActivity, "Purchased: ${formatQty(item.qty)} $unit", "خریدی گئی مقدار: ${formatQty(item.qty)} $unit")
                    textSize = 12f
                    setTextColor(Color.parseColor(textGray))
                    setPadding(0, 2, 0, 8)
                })
                val input = EditText(this@HistoryActivity).apply {
                    hint = Loc.t(this@HistoryActivity, "Return qty (leave blank to skip)", "واپسی مقدار (چھوڑنے کے لیے خالی رکھیں)")
                    setHintTextColor(Color.parseColor(textGray))
                    setTextColor(Color.parseColor(textDark))
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    background = strokedBg(border, cardBg, 10)
                    setPadding(22, 16, 22, 16)
                }
                fields[item.id] = input
                row.addView(input)
                container.addView(row)
            }

            val itemsScroll = ScrollView(this@HistoryActivity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                addView(container)
            }

            val itemsById = rowMeta.associateBy({ it.first.id }, { it.first to it.second })

            val titleView = TextView(this@HistoryActivity).apply {
                text = Loc.t(this@HistoryActivity, "Return items", "آئٹمز واپس کریں")
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor(textDark))
                setPadding(40, 32, 40, 8)
            }
            val messageView = TextView(this@HistoryActivity).apply {
                text = Loc.t(
                    this@HistoryActivity,
                    "Enter how many units of each item are being returned. Stock and supplier balance will be adjusted only for those quantities.",
                    "ہر آئٹم کی کتنی مقدار واپس ہو رہی ہے درج کریں۔ صرف انہی مقداروں کے مطابق اسٹاک اور سپلائر بیلنس ایڈجسٹ ہو گا۔"
                )
                textSize = 13f
                setTextColor(Color.parseColor(textGray))
                setPadding(40, 0, 40, 8)
            }

            val cancelBtn = outlineButton(Loc.t(this@HistoryActivity, "Cancel", "منسوخ کریں")) {}
            val returnBtn = filledButton(Loc.t(this@HistoryActivity, "Return", "واپسی"), amber) {}
            val footer = LinearLayout(this@HistoryActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(40, 16, 40, 32)
                addView(cancelBtn)
                addView(spacerH(12))
                addView(returnBtn)
            }

            val maxDialogHeightPx = (resources.displayMetrics.heightPixels * 0.82).toInt()
            val root = object : LinearLayout(this@HistoryActivity) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val mode = View.MeasureSpec.getMode(heightMeasureSpec)
                    val size = View.MeasureSpec.getSize(heightMeasureSpec)
                    val cappedSize = if (mode == View.MeasureSpec.UNSPECIFIED) maxDialogHeightPx else size.coerceAtMost(maxDialogHeightPx)
                    val newMode = if (mode == View.MeasureSpec.UNSPECIFIED) View.MeasureSpec.AT_MOST else mode
                    super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(cappedSize, newMode))
                }
            }.apply {
                orientation = LinearLayout.VERTICAL
                addView(titleView)
                addView(messageView)
                addView(itemsScroll)
                addView(footer)
            }

            val dialog = AlertDialog.Builder(this@HistoryActivity)
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
                    val (item, name) = itemsById[id] ?: continue
                    when {
                        qty == null || qty < 0 -> {
                            errorMsg = Loc.t(this@HistoryActivity, "Enter a valid quantity for \"$name\"", "\"$name\" کے لیے درست مقدار درج کریں")
                        }
                        qty == 0.0 -> { /* treated as skip */ }
                        qty > item.qty + 0.0001 -> {
                            errorMsg = Loc.t(
                                this@HistoryActivity,
                                "Return qty for \"$name\" can't exceed purchased qty (${formatQty(item.qty)})",
                                "\"$name\" کی واپسی مقدار خریدی گئی مقدار (${formatQty(item.qty)}) سے زیادہ نہیں ہو سکتی"
                            )
                        }
                        else -> requested[id] = qty
                    }
                    if (errorMsg != null) break
                }
                if (errorMsg != null) {
                    Toast.makeText(this@HistoryActivity, errorMsg, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (requested.isEmpty()) {
                    Toast.makeText(this@HistoryActivity, Loc.t(this@HistoryActivity, "Enter a return quantity for at least one item", "کم از کم ایک آئٹم کے لیے واپسی مقدار درج کریں"), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                processPartialReturn(billNo, requested)
            }
            dialog.show()
        }
    }

    private fun formatQty(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    // Does the actual line-level return picked in openReturnPurchaseDialog() — see
    // PurchaseHistoryActivity.processPartialReturn() for the full reasoning (identical
    // logic, kept in sync so both entry points to Purchase History behave the same way).
    private fun processPartialReturn(billNo: String, requested: Map<Long, Double>) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@HistoryActivity)
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
                        // Every line on the bill ended up fully returned — same end state
                        // as the old whole-bill returnPurchase().
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
                loadPurchases()
            } catch (e: IllegalStateException) {
                Toast.makeText(this@HistoryActivity, e.message ?: "Return nahi ho saka", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Same frozen-conversionFactor reasoning as PurchaseItem.smallestQty(product) in
    // Database.kt, but for a QUANTITY BEING RETURNED (which may be less than the
    // line's full qty) instead of the whole line.
    private fun partialSmallestQty(item: com.grocerypos.v11.PurchaseItem, product: com.grocerypos.v11.Product?, returnQty: Double): Double =
        if (item.conversionFactor > 0) returnQty * item.conversionFactor
        else product?.toSmallestUnits(returnQty, item.unit.ifBlank { product.unit }) ?: returnQty

    // Same weighted-average reversal math as reverseStockAndCostForPurchaseItems()
    // above, but taking the qty/amount to remove as parameters so it can be used for a
    // PARTIAL line return instead of always reversing the whole line.
    private fun reversePurchaseLineCostPartial(product: com.grocerypos.v11.Product, smallestQtyToRemove: Double, amountToRemove: Double): Double {
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

    // FIX (Phase 1 - Data Safety): same atomic-transaction treatment as returnPurchase() above.
    // FIX (item #23): same negative-stock guard + Toast-on-refusal as returnPurchase() above.
    private fun deletePurchase(billNo: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@HistoryActivity); val purchase = db.purchaseDao().findPurchase(billNo) ?: return@launch
            val items = db.purchaseDao().itemsForBill(billNo)
            try {
                db.withTransaction {
                    reverseStockAndCostForPurchaseItems(db, items)
                    if (purchase.supplierId != null && purchase.paid < purchase.total) SyncQueueHelper.adjustSupplierBalance(db, purchase.supplierId, -(purchase.total - purchase.paid))
                    db.cashTransactionDao().deleteByReference(billNo); db.paymentDao().deleteByReference(billNo); db.purchaseDao().deleteItems(billNo); db.purchaseDao().deletePurchase(billNo)
                }
                loadPurchases()
            } catch (e: IllegalStateException) {
                Toast.makeText(this@HistoryActivity, e.message ?: "Delete nahi ho saka", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ================= List row (matches summaryCard/navRow icon-badge treatment) =================
    private fun tintedDrawable(iconRes: Int, tintHex: String, sizeDp: Int = 16): android.graphics.drawable.Drawable? {
        val d = androidx.core.content.ContextCompat.getDrawable(this, iconRes)?.mutate() ?: return null
        d.setTint(Color.parseColor(tintHex))
        val px = (sizeDp * resources.displayMetrics.density).toInt()
        d.setBounds(0, 0, px, px)
        return d
    }

    private fun row(iconRes: Int, reference: String, subtitle: String, amount: Double, date: String, accentHex: String, tintHex: String, returned: Boolean, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 16, 18, 16)
            background = strokedBg(border, cardBg, 18)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, 0, 10) }
            applyElevation(this, 2f)
            isClickable = true
            setOnClickListener { onClick() }

            addView(FrameLayout(this@HistoryActivity).apply {
                val size = (38 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(tintHex)) }
                addView(ImageView(this@HistoryActivity).apply {
                    setImageDrawable(tintedDrawable(iconRes, accentHex, 17))
                    scaleType = ImageView.ScaleType.CENTER
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                })
            })

            val infoCol = LinearLayout(this@HistoryActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14, 0, 8, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameRow = LinearLayout(this@HistoryActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            nameRow.addView(TextView(this@HistoryActivity).apply {
                text = reference
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor(textDark))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (returned) {
                nameRow.addView(TextView(this@HistoryActivity).apply {
                    text = Loc.t(this@HistoryActivity, "RETURNED", "واپس")
                    textSize = 10f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    background = roundedBg(red, 8)
                    setPadding(12, 4, 12, 4)
                })
            }
            infoCol.addView(nameRow)
            infoCol.addView(TextView(this@HistoryActivity).apply {
                text = subtitle
                textSize = 12f
                setTextColor(Color.parseColor(textGray))
                setPadding(0, 3, 0, 0)
            })
            infoCol.addView(TextView(this@HistoryActivity).apply {
                text = date
                textSize = 11f
                setTextColor(Color.parseColor(textGray))
                setPadding(0, 2, 0, 0)
            })
            addView(infoCol)

            addView(TextView(this@HistoryActivity).apply {
                text = "Rs %.2f".format(amount)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor(accentHex))
            })
        }
    }

    // ================= Detail dialog shell =================
    // Keeps the same 3-child shape callers rely on: index 0 = header, index 1 = body
    // (plain LinearLayout — callers do body.addView(...) directly), index 2 = footer
    // (horizontal LinearLayout for the action buttons).
    private fun detailContainer(iconRes: Int, accentHex: String, tintHex: String, kind: String, reference: String): LinearLayout {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 22, 24, 18)
        }
        header.addView(FrameLayout(this).apply {
            val size = (40 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(tintHex)) }
            addView(ImageView(this@HistoryActivity).apply {
                setImageDrawable(tintedDrawable(iconRes, accentHex, 18))
                scaleType = ImageView.ScaleType.CENTER
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            })
        })
        val headerCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(14, 0, 0, 0) }
        headerCol.addView(TextView(this).apply {
            text = kind
            textSize = 12f
            setTextColor(Color.parseColor(accentHex))
            setTypeface(typeface, Typeface.BOLD)
        })
        headerCol.addView(TextView(this).apply {
            text = reference
            textSize = 16f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 2, 0, 0)
        })
        header.addView(headerCol)
        outer.addView(header)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 12)
            background = strokedBg(border, cardBg, 16)
            applyElevation(this, 1f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(20, 0, 20, 16) }
        }
        outer.addView(body)

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 0, 20, 20)
        }
        outer.addView(footer)
        return outer
    }

    private fun returnedBanner() = LinearLayout(this).apply {
        setPadding(16, 10, 16, 10)
        background = strokedBg(red, "#FDE8E8", 10)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 14) }
        addView(TextView(this@HistoryActivity).apply {
            text = Loc.t(this@HistoryActivity, "RETURNED", "واپس")
            setTextColor(Color.parseColor(red))
            setTypeface(typeface, Typeface.BOLD)
            textSize = 12.5f
        })
    }

    private fun kv(l: String, v: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 6, 0, 6)
        addView(TextView(this@HistoryActivity).apply {
            text = l; textSize = 13.5f
            setTextColor(Color.parseColor(textGray))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(this@HistoryActivity).apply {
            text = v; textSize = 13.5f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, Typeface.BOLD)
        })
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(Color.parseColor(textDark))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, 6)
    }

    private fun itemRow(n: String, q: String, a: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 8, 0, 8)
        val top = LinearLayout(this@HistoryActivity).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(TextView(this@HistoryActivity).apply {
            text = n; textSize = 13.5f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(TextView(this@HistoryActivity).apply {
            text = a; textSize = 13.5f
            setTextColor(Color.parseColor(primary))
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(top)
        addView(TextView(this@HistoryActivity).apply {
            text = q; textSize = 11.5f
            setTextColor(Color.parseColor(textGray))
            setPadding(0, 2, 0, 0)
        })
        addView(rowDivider())
    }

    private fun rowDivider() = View(this).apply {
        setBackgroundColor(Color.parseColor(border))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { setMargins(0, 8, 0, 0) }
    }

    // CHANGE (ultimate premium look): replaced the plain gray placeholder text with an
    // icon-badge empty-state card matching the rest of the app's premium style.
    private fun emptyText(t: String): LinearLayout {
        val accentHex = if (showingSales) primary else gold
        val tintHex = if (showingSales) "#E9E6FF" else "#F6EFDD"
        val iconRes = if (showingSales) R.drawable.ic_receipt else R.drawable.ic_cart
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 60, 24, 40)
            addView(circleIconDrawable(iconRes, accentHex, tintHex, 64))
            addView(TextView(this@HistoryActivity).apply {
                text = t
                setTextColor(Color.parseColor(textDark))
                setTypeface(typeface, Typeface.BOLD)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 22, 0, 0)
            })
        }
    }

    private fun outlineButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(textGray))
            background = strokedBg(border, cardBg, 12)
            setPadding(0, 20, 0, 20)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
    }

    private fun filledButton(label: String, colorHex: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = roundedBg(colorHex, 12)
            setPadding(0, 20, 0, 20)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
    }

    private fun spacerH(widthDp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams((widthDp * resources.displayMetrics.density).toInt(), 1)
    }

    private fun filterPill(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 20, 0, 20)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
    }

    // ================= PREMIUM HEADER (matches Reports/Stock/Balance Sheet/Party Reports) =================

    // ================= SHARED UI HELPERS (matches Reports/Stock/Balance Sheet/Party Reports) =================
    private fun circleIcon(label: String, colorHex: String, sizeDp: Int) = TextView(this).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        background = ovalBg(colorHex)
        val px = (sizeDp * resources.displayMetrics.density).toInt()
        layoutParams = android.view.ViewGroup.LayoutParams(px, px)
    }

    // ---- Drawable-resource overload of circleIcon(), added alongside the original
    // emoji-string version so this one call site (empty-state badge) can move to a
    // vector icon without touching circleIcon()'s other behavior. ----
    private fun circleIconDrawable(iconRes: Int, tintHex: String, colorHex: String, sizeDp: Int) = ImageView(this).apply {
        setImageDrawable(tintedDrawable(iconRes, tintHex, (sizeDp * 0.4).toInt()))
        scaleType = ImageView.ScaleType.CENTER
        background = ovalBg(colorHex)
        val px = (sizeDp * resources.displayMetrics.density).toInt()
        layoutParams = android.view.ViewGroup.LayoutParams(px, px)
    }

    private fun spacer(heightDp: Int) = View(this).apply {
        val px = (heightDp * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px)
    }
}
