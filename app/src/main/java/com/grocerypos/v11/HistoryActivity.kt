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

    // ================= PREMIUM PALETTE (shared with Reports / Stock / Balance Sheet) =================
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

    private lateinit var tabRow: LinearLayout
    private lateinit var salesTab: TextView
    private lateinit var purchasesTab: TextView
    private lateinit var listContainer: LinearLayout
    private var showingSales = true

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            setBackgroundColor(Color.parseColor(bg))
        }

        root.addView(premiumHeader("🧾", Loc.t(this, "Sale / Purchase History", "سیل / خریداری کی تاریخ"), Loc.t(this, "Tap any entry to view details", "تفصیل دیکھنے کے لیے کسی بھی اندراج پر ٹیپ کریں")))

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

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)
        root.addView(spacer(30))

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        }
        setContentView(scroll)

        refreshTabs()
        showSales()
    }

    override fun onResume() { super.onResume(); if (showingSales) loadSales() else loadPurchases() }

    private fun refreshTabs() {
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
                row("🧾", s.invoice, s.customerName, s.total, fmt.format(Date(s.createdAt)), primary, "#E9E6FF", s.status == "returned") { openSaleDetail(s.invoice) }
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
                row("📦", p.billNo, p.supplierName, p.total, fmt.format(Date(p.createdAt)), teal, "#E0F2F1", p.status == "returned") { openPurchaseDetail(p.billNo) }
            )
        }
    }

    private fun openSaleDetail(invoice: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@HistoryActivity)
            val sale = db.saleDao().findSale(invoice) ?: return@launch
            val items = db.saleDao().itemsForInvoice(invoice)
            val content = detailContainer("🧾", primary, "#E9E6FF", Loc.t(this@HistoryActivity, "Sale", "سیل"), invoice)
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
            val content = detailContainer("📦", teal, "#E0F2F1", Loc.t(this@HistoryActivity, "Purchase", "خریداری"), billNo)
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
                footer.addView(filledButton(Loc.t(this@HistoryActivity, "Return", "واپس"), amber) { returnPurchase(billNo); dialog.dismiss() })
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

    // FIX (Phase 1 - Data Safety): stock/cost reversal + return-row inserts + balance
    // reversal + markReturned now run as one atomic transaction.
    // FIX (item #23): reverseStockAndCostForPurchaseItems() can now throw IllegalStateException
    // (negative-stock guard) — catch it here and show the reason instead of letting it crash
    // the app, so the return is cleanly refused with an explanation.
    private fun returnPurchase(billNo: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@HistoryActivity); val purchase = db.purchaseDao().findPurchase(billNo) ?: return@launch; if (purchase.status == "returned") return@launch
            val items = db.purchaseDao().itemsForBill(billNo)
            try {
                db.withTransaction {
                    reverseStockAndCostForPurchaseItems(db, items)
                    for (item in items) {
                        db.returnDao().insert(ReturnLine(reference = billNo, type = "purchase", barcode = item.barcode, qty = item.qty, amount = item.amount))
                    }
                    if (purchase.supplierId != null && purchase.paid < purchase.total) SyncQueueHelper.adjustSupplierBalance(db, purchase.supplierId, -(purchase.total - purchase.paid))
                    db.cashTransactionDao().deleteByReference(billNo); db.purchaseDao().markReturned(billNo)
                }
                loadPurchases()
            } catch (e: IllegalStateException) {
                Toast.makeText(this@HistoryActivity, e.message ?: "Return nahi ho saka", Toast.LENGTH_LONG).show()
            }
        }
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
    private fun row(icon: String, reference: String, subtitle: String, amount: Double, date: String, accentHex: String, tintHex: String, returned: Boolean, onClick: () -> Unit): LinearLayout {
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
                addView(TextView(this@HistoryActivity).apply {
                    text = icon; textSize = 15f; gravity = Gravity.CENTER
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
    private fun detailContainer(icon: String, accentHex: String, tintHex: String, kind: String, reference: String): LinearLayout {
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
            addView(TextView(this@HistoryActivity).apply {
                text = icon; textSize = 16f; gravity = Gravity.CENTER
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

    private fun emptyText(t: String) = TextView(this).apply {
        text = t
        setTextColor(Color.parseColor(textGray))
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(0, 40, 0, 0)
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
    private fun premiumHeader(icon: String, title: String, subtitle: String): LinearLayout {
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
        header.addView(TextView(this).apply {
            text = "‹"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = ovalBg("#33FFFFFF")
            val px = (36 * resources.displayMetrics.density).toInt()
            width = px; height = px
            setOnClickListener { finish() }
        })
        header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(14, 1) })
        header.addView(circleIcon(icon, "#5C4DFF", 42))
        header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(16, 1) })
        val headerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerCol.addView(TextView(this).apply {
            text = title
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        headerCol.addView(TextView(this).apply {
            text = subtitle
            textSize = 11f
            setTextColor(Color.parseColor("#D8D3FF"))
            setPadding(0, 4, 0, 0)
        })
        header.addView(headerCol)
        return header
    }

    // ================= SHARED UI HELPERS (matches Reports/Stock/Balance Sheet/Party Reports) =================
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
