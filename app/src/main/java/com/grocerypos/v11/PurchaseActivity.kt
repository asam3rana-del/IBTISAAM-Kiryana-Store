package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Product
import com.grocerypos.v11.Purchase
import com.grocerypos.v11.PurchaseItem
import com.grocerypos.v11.Supplier
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.launch

/**
 * "Purchase" entry/edit screen backed by the real PurchaseDao/ProductDao/SupplierDao —
 * numbered line-item cards ("#N  Name  Rs price" + "Item Subtotal  qty unit x rate = Rs total"),
 * a totals strip (Total Disc / Total Tax Amt / Total Qty / Subtotal), an "+ Add Items" button,
 * a Charges section (Shipping + Discount), and a bottom bar with Delete / Save / overflow-menu,
 * matching the reference screenshot.
 *
 * Start it for a NEW purchase with a plain Intent. Start it to EDIT an existing purchase by
 * putting the bill number under EXTRA_BILL_NO:
 *   startActivity(Intent(this, PurchaseActivity::class.java).putExtra(PurchaseActivity.EXTRA_BILL_NO, billNo))
 *
 * Schema notes (com.grocerypos.v11.PosDatabase, v17):
 *  - PurchaseItem has no per-line discount/tax column, so "Total Disc" is driven by the
 *    header-level Purchase.discount field (the Discount charge below) and "Total Tax Amt"
 *    has no backing column at all — it always shows 0.00 until a schema migration adds one.
 *  - Purchase has no dedicated "shipping" column. Shipping is added into the saved `total`
 *    and `subtotal` only; it is NOT persisted as its own field. Add a migration + column if
 *    you need it reported separately later.
 *  - Purchases are recorded as fully unpaid (paid = 0.0) here, which pushes the whole total
 *    onto the supplier's balance ("You'll Give" on the dashboard). Wire in a paid-amount field
 *    if you want partial/full cash purchases from this screen.
 *
 * NOTE: Add to AndroidManifest.xml under <application> if not already present:
 *   <activity android:name=".ui.PurchaseActivity" />
 */
class PurchaseActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BILL_NO = "bill_no"
    }

    // ---- palette (kept consistent with PartyActivity.kt / PartyDashboardActivity.kt) ----
    private val bg = "#F3F4F9"
    private val blue = "#5B6EE8"
    private val red = "#E57373"
    private val cardWhite = "#FFFFFF"
    private val cardBg = "#F5F6FA"
    private val cardBorder = "#EEF0F7"
    private val labelGray = "#9AA0B4"
    private val textDark = "#2E3242"
    private val divider = "#E7E9F2"

    private lateinit var supplierValue: TextView
    private lateinit var billNoValue: TextView
    private lateinit var itemsContainer: LinearLayout
    private lateinit var totalDiscValue: TextView
    private lateinit var totalTaxValue: TextView
    private lateinit var totalQtyValue: TextView
    private lateinit var subtotalValue: TextView
    private lateinit var shippingField: EditText
    private lateinit var discountField: EditText
    private lateinit var deleteBtn: TextView

    /** UI-side line item; barcode ties it back to a real Product row. */
    private data class UiLineItem(
        var barcode: String,
        var productName: String,
        var qty: Double,
        var unit: String,
        var rate: Double
    ) {
        val amount: Double get() = qty * rate
    }

    private val lineItems = mutableListOf<UiLineItem>()
    private var allProducts: List<Product> = emptyList()
    private var allSuppliers: List<Supplier> = emptyList()
    private var selectedSupplier: Supplier? = null

    private var billNo: String = ""
    private var editing = false
    /** Snapshot of barcode -> qty as originally loaded, so Save can correctly adjust stock deltas. */
    private var originalQtyByBarcode: Map<String, Double> = emptyMap()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        editing = intent.getStringExtra(EXTRA_BILL_NO) != null
        billNo = intent.getStringExtra(EXTRA_BILL_NO) ?: newBillNo()

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(bg))
        }

        outer.addView(buildHeader())

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(buildSupplierAndBillInfo())
        root.addView(sectionDivider())

        itemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(itemsContainer)

        root.addView(buildTotalsStrip())
        root.addView(buildAddItemsButton())
        root.addView(sectionDivider())
        root.addView(buildChargesSection())
        root.addView(spacer(90)) // keep content clear of the floating bottom bar

        scroll.addView(root)
        outer.addView(scroll)
        outer.addView(buildBottomBar())

        setContentView(outer)

        loadData()
    }

    private fun newBillNo(): String = "PB" + System.currentTimeMillis()

    // ================= DATA LOAD =================
    private fun loadData() {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PurchaseActivity)

            // one-off snapshots are fine here — this screen edits a single bill, it doesn't
            // need to live-update if another screen changes products/suppliers concurrently
            allProducts = collectFirst { db.productDao().all() }
            allSuppliers = collectFirst { db.supplierDao().all() }

            if (editing) {
                val purchase = db.purchaseDao().findPurchase(billNo)
                val existingItems = db.purchaseDao().itemsForBill(billNo)
                selectedSupplier = allSuppliers.find { it.id == purchase?.supplierId }
                lineItems.clear()
                for (pi in existingItems) {
                    val product = allProducts.find { it.barcode == pi.barcode }
                    lineItems.add(
                        UiLineItem(
                            barcode = pi.barcode,
                            productName = product?.name ?: pi.barcode,
                            qty = pi.qty.toDouble(),
                            unit = pi.unit.ifBlank { product?.unit ?: "" },
                            rate = pi.unitCost
                        )
                    )
                }
                originalQtyByBarcode = lineItems.groupBy { it.barcode }
                    .mapValues { (_, v) -> v.sumOf { it.qty } }
                discountField.setText(if ((purchase?.discount ?: 0.0) != 0.0) "%.2f".format(purchase!!.discount) else "")
                deleteBtn.visibility = View.VISIBLE
            } else {
                originalQtyByBarcode = emptyMap()
                deleteBtn.visibility = View.GONE
            }

            billNoValue.text = billNo
            updateSupplierLabel()
            renderLineItems()
        }
    }

    /** Small helper: take the first emission of a cold/hot Flow without keeping a live collector open. */
    private suspend fun <T> collectFirst(flowProvider: () -> kotlinx.coroutines.flow.Flow<List<T>>): List<T> {
        var result: List<T> = emptyList()
        try {
            flowProvider().collect {
                result = it
                throw StopCollecting
            }
        } catch (e: StopCollecting) {
            // expected — we only wanted the first emission
        }
        return result
    }

    private class StopCollecting : Exception()

    // ================= HEADER =================
    private fun buildHeader(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 46, 20, 22)
            setBackgroundColor(Color.parseColor(cardWhite))

            addView(TextView(this@PurchaseActivity).apply {
                text = "\u2190"
                textSize = 22f
                setTextColor(Color.parseColor(textDark))
                setPadding(4, 0, 24, 0)
                setOnClickListener { finish() }
            })

            addView(TextView(this@PurchaseActivity).apply {
                text = Loc.t(this@PurchaseActivity, "Purchase", "\u062E\u0631\u06CC\u062F\u0627\u0631\u06CC")
                textSize = 20f
                setTextColor(Color.parseColor(textDark))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(TextView(this@PurchaseActivity).apply {
                text = "\u2699"
                textSize = 20f
                setTextColor(Color.parseColor(textDark))
                setPadding(0, 0, 4, 0)
                setOnClickListener {
                    Toast.makeText(this@PurchaseActivity, Loc.t(this@PurchaseActivity, "Purchase settings", "\u062E\u0631\u06CC\u062F\u0627\u0631\u06CC \u0633\u06CC\u0679\u0646\u06AF\u0632"), Toast.LENGTH_SHORT).show()
                }
            })
        }
    }

    // ================= SUPPLIER + BILL INFO =================
    private fun buildSupplierAndBillInfo(): LinearLayout {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 18, 20, 18)
            setBackgroundColor(Color.parseColor(cardWhite))
        }

        val supplierRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 14, 16, 14)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(cardBg))
                cornerRadius = 10f
                setStroke(1, Color.parseColor(cardBorder))
            }
            setOnClickListener { showSupplierPicker() }
        }
        supplierRow.addView(TextView(this).apply {
            text = Loc.t(this@PurchaseActivity, "Supplier", "\u0633\u067E\u0644\u0627\u0626\u0631")
            textSize = 12.5f
            setTextColor(Color.parseColor(labelGray))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        supplierValue = TextView(this).apply {
            text = Loc.t(this@PurchaseActivity, "Cash Purchase \u203A", "\u0646\u0642\u062F \u062E\u0631\u06CC\u062F\u0627\u0631\u06CC \u203A")
            textSize = 13.5f
            setTextColor(Color.parseColor(blue))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        supplierRow.addView(supplierValue)
        wrap.addView(supplierRow)

        wrap.addView(spacer(10))

        val billRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        billRow.addView(TextView(this).apply {
            text = Loc.t(this@PurchaseActivity, "Bill No: ", "\u0628\u0644 \u0646\u0645\u0628\u0631: ")
            textSize = 12f
            setTextColor(Color.parseColor(labelGray))
        })
        billNoValue = TextView(this).apply {
            text = billNo
            textSize = 12f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        billRow.addView(billNoValue)
        wrap.addView(billRow)

        return wrap
    }

    private fun updateSupplierLabel() {
        val s = selectedSupplier
        supplierValue.text = if (s != null) "${s.name} \u203A" else Loc.t(this, "Cash Purchase \u203A", "\u0646\u0642\u062F \u062E\u0631\u06CC\u062F\u0627\u0631\u06CC \u203A")
    }

    private fun showSupplierPicker() {
        val names = mutableListOf(Loc.t(this, "Cash Purchase (no supplier)", "\u0646\u0642\u062F \u062E\u0631\u06CC\u062F\u0627\u0631\u06CC (\u0628\u063A\u06CC\u0631 \u0633\u067E\u0644\u0627\u0626\u0631)"))
        names.addAll(allSuppliers.map { it.name })
        AlertDialog.Builder(this)
            .setTitle(Loc.t(this, "Select Supplier", "\u0633\u067E\u0644\u0627\u0626\u0631 \u0645\u0646\u062A\u062E\u0628 \u06A9\u0631\u06CC\u06BA"))
            .setItems(names.toTypedArray()) { _, which ->
                selectedSupplier = if (which == 0) null else allSuppliers[which - 1]
                updateSupplierLabel()
            }
            .show()
    }

    // ================= LINE ITEMS =================
    private fun renderLineItems() {
        itemsContainer.removeAllViews()
        lineItems.forEachIndexed { index, item ->
            itemsContainer.addView(lineItemRow(index + 1, item))
        }
        updateTotals()
    }

    private fun lineItemRow(number: Int, item: UiLineItem): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
            setBackgroundColor(Color.parseColor(cardBg))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            setOnClickListener { showEditItemDialog(item) }

            val topRow = LinearLayout(this@PurchaseActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topRow.addView(TextView(this@PurchaseActivity).apply {
                text = "#$number"
                textSize = 11f
                setTextColor(Color.parseColor(labelGray))
                setPadding(10, 4, 10, 4)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(cardWhite))
                    cornerRadius = 8f
                    setStroke(1, Color.parseColor(cardBorder))
                }
            })
            topRow.addView(TextView(this@PurchaseActivity).apply {
                text = item.productName
                textSize = 15.5f
                setTextColor(Color.parseColor(textDark))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(16, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            topRow.addView(TextView(this@PurchaseActivity).apply {
                text = "Rs %,.0f".format(item.amount)
                textSize = 16f
                setTextColor(Color.parseColor(textDark))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(topRow)

            val subRow = LinearLayout(this@PurchaseActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 10, 0, 0)
            }
            subRow.addView(TextView(this@PurchaseActivity).apply {
                text = Loc.t(this@PurchaseActivity, "Item Subtotal", "\u0622\u0626\u0679\u0645 \u0633\u0628 \u0679\u0648\u0679\u0644")
                textSize = 12.5f
                setTextColor(Color.parseColor(labelGray))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            subRow.addView(TextView(this@PurchaseActivity).apply {
                val qtyStr = if (item.qty == item.qty.toLong().toDouble()) item.qty.toLong().toString() else item.qty.toString()
                text = "$qtyStr ${item.unit} x %,.0f = Rs %,.0f".format(item.rate, item.amount)
                textSize = 12.5f
                setTextColor(Color.parseColor(labelGray))
            })
            addView(subRow)
        }
    }

    // ================= ADD / EDIT ITEM =================
    private fun buildAddItemsButton(): LinearLayout {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 0, 20, 20)
            setBackgroundColor(Color.parseColor(cardWhite))
        }

        val btn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 18)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(cardWhite))
                cornerRadius = 10f
                setStroke(2, Color.parseColor(cardBorder))
            }
            setOnClickListener { showProductPicker() }
        }
        btn.addView(TextView(this).apply {
            text = "\u2295"
            textSize = 17f
            setTextColor(Color.parseColor(blue))
            setPadding(0, 0, 10, 0)
        })
        btn.addView(TextView(this).apply {
            text = Loc.t(this@PurchaseActivity, "Add Items", "\u0622\u0626\u0679\u0645\u0632 \u0634\u0627\u0645\u0644 \u06A9\u0631\u06CC\u06BA")
            textSize = 14.5f
            setTextColor(Color.parseColor(blue))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        wrap.addView(btn)
        return wrap
    }

    private fun showProductPicker() {
        if (allProducts.isEmpty()) {
            Toast.makeText(this, Loc.t(this, "No products found", "\u06A9\u0648\u0626\u06CC \u0622\u0626\u0679\u0645 \u0646\u06C1\u06CC\u06BA \u0645\u0644\u0627"), Toast.LENGTH_SHORT).show()
            return
        }
        val names = allProducts.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(Loc.t(this, "Select Product", "\u0622\u0626\u0679\u0645 \u0645\u0646\u062A\u062E\u0628 \u06A9\u0631\u06CC\u06BA"))
            .setItems(names) { _, which ->
                val product = allProducts[which]
                showQtyRateDialog(
                    UiLineItem(
                        barcode = product.barcode,
                        productName = product.name,
                        qty = 1.0,
                        unit = product.unit,
                        rate = product.cost
                    ),
                    isNew = true
                )
            }
            .show()
    }

    private fun showEditItemDialog(item: UiLineItem) {
        showQtyRateDialog(item, isNew = false)
    }

    private fun showQtyRateDialog(item: UiLineItem, isNew: Boolean) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val qtyInput = EditText(this).apply {
            hint = Loc.t(this@PurchaseActivity, "Quantity (${item.unit})", "\u0645\u0642\u062F\u0627\u0631 (${item.unit})")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (item.qty != 0.0) trimNumber(item.qty) else "")
        }
        val rateInput = EditText(this).apply {
            hint = Loc.t(this@PurchaseActivity, "Rate per unit", "\u0641\u06CC \u06CC\u0648\u0646\u0679 \u0631\u06CC\u0679")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (item.rate != 0.0) trimNumber(item.rate) else "")
        }
        form.addView(qtyInput)
        form.addView(spacer(12))
        form.addView(rateInput)

        val dialog = AlertDialog.Builder(this)
            .setTitle(item.productName)
            .setView(form)
            .setPositiveButton(Loc.t(this, "Save", "\u0645\u062D\u0641\u0648\u0638 \u06A9\u0631\u06CC\u06BA")) { _, _ ->
                val qty = qtyInput.text.toString().toDoubleOrNull() ?: 0.0
                val rate = rateInput.text.toString().toDoubleOrNull() ?: 0.0
                if (qty <= 0.0) {
                    Toast.makeText(this, Loc.t(this, "Enter a valid quantity", "\u0635\u062D\u06CC\u062D \u0645\u0642\u062F\u0627\u0631 \u062F\u0631\u062C \u06A9\u0631\u06CC\u06BA"), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                item.qty = qty
                item.rate = rate
                if (isNew) lineItems.add(item)
                renderLineItems()
            }
            .setNegativeButton(Loc.t(this, "Cancel", "\u0645\u0646\u0633\u0648\u062E \u06A9\u0631\u06CC\u06BA"), null)

        if (!isNew) {
            dialog.setNeutralButton(Loc.t(this, "Remove", "\u06C1\u0679\u0627\u0626\u06CC\u06BA")) { _, _ ->
                lineItems.remove(item)
                renderLineItems()
            }
        }
        dialog.show()
    }

    private fun trimNumber(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    private fun sectionDivider() = View(this).apply {
        setBackgroundColor(Color.parseColor(divider))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (10 * resources.displayMetrics.density).toInt()
        )
    }

    // ================= TOTALS STRIP =================
    private fun buildTotalsStrip(): LinearLayout {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 18, 20, 18)
            setBackgroundColor(Color.parseColor(cardWhite))
        }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val discPair = totalLabelValue(Loc.t(this, "Total Disc", "\u0679\u0648\u0679\u0644 \u0688\u0633\u06A9\u0627\u0624\u0646\u0679"))
        val taxPair = totalLabelValue(Loc.t(this, "Total Tax Amt", "\u0679\u0648\u0679\u0644 \u0679\u06CC\u06A9\u0633"))
        totalDiscValue = discPair.second
        totalTaxValue = taxPair.second
        discPair.first.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        taxPair.first.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row1.addView(discPair.first)
        row1.addView(taxPair.first)
        wrap.addView(row1)
        wrap.addView(spacer(10))

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val qtyPair = totalLabelValue(Loc.t(this, "Total Qty", "\u0679\u0648\u0679\u0644 \u0645\u0642\u062F\u0627\u0631"))
        val subPair = totalLabelValue(Loc.t(this, "Subtotal", "\u0633\u0628 \u0679\u0648\u0679\u0644"))
        totalQtyValue = qtyPair.second
        subtotalValue = subPair.second
        qtyPair.first.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        subPair.first.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row2.addView(qtyPair.first)
        row2.addView(subPair.first)
        wrap.addView(row2)

        // Total Tax Amt has no backing schema column (see class doc) — always shows 0.00.
        totalTaxValue.text = "0.00"

        return wrap
    }

    private fun totalLabelValue(label: String): Pair<LinearLayout, TextView> {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply {
            text = "$label: "
            textSize = 13f
            setTextColor(Color.parseColor(labelGray))
        })
        val value = TextView(this).apply {
            text = "0.00"
            textSize = 13f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        row.addView(value)
        return Pair(row, value)
    }

    private fun updateTotals() {
        val subtotal = lineItems.sumOf { it.amount }
        val totalQty = lineItems.sumOf { it.qty }
        val discount = discountField.text?.toString()?.toDoubleOrNull() ?: 0.0

        totalDiscValue.text = "%.2f".format(discount)
        totalQtyValue.text = if (totalQty == totalQty.toLong().toDouble()) "${totalQty.toLong()}.0" else "%.1f".format(totalQty)
        subtotalValue.text = "%.2f".format(subtotal)
    }

    private fun grandTotal(): Double {
        val shipping = shippingField.text?.toString()?.toDoubleOrNull() ?: 0.0
        val discount = discountField.text?.toString()?.toDoubleOrNull() ?: 0.0
        return lineItems.sumOf { it.amount } + shipping - discount
    }

    // ================= CHARGES =================
    private fun buildChargesSection(): LinearLayout {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor(cardWhite))
        }

        wrap.addView(TextView(this).apply {
            text = Loc.t(this@PurchaseActivity, "Charges", "\u0686\u0627\u0631\u062C\u0632")
            textSize = 15f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        wrap.addView(spacer(10))
        wrap.addView(View(this).apply {
            setBackgroundColor(Color.parseColor(divider))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
        })
        wrap.addView(spacer(18))

        val onChargeChanged = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { updateTotals() }
            override fun afterTextChanged(s: Editable?) {}
        }

        val shippingRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        shippingRow.addView(TextView(this).apply {
            text = Loc.t(this@PurchaseActivity, "Shipping", "\u0634\u067E\u0646\u06AF")
            textSize = 14.5f
            setTextColor(Color.parseColor(textDark))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val shippingBox = currencyInputBox()
        shippingField = shippingBox.second
        shippingField.addTextChangedListener(onChargeChanged)
        shippingRow.addView(shippingBox.first)
        wrap.addView(shippingRow)
        wrap.addView(spacer(14))

        val discountRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        discountRow.addView(TextView(this).apply {
            text = Loc.t(this@PurchaseActivity, "Discount", "\u0688\u0633\u06A9\u0627\u0624\u0646\u0679")
            textSize = 14.5f
            setTextColor(Color.parseColor(textDark))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val discountBox = currencyInputBox()
        discountField = discountBox.second
        discountField.addTextChangedListener(onChargeChanged)
        discountRow.addView(discountBox.first)
        wrap.addView(discountRow)

        return wrap
    }

    private fun currencyInputBox(): Pair<LinearLayout, EditText> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor(cardWhite))
                cornerRadius = 8f
                setStroke(2, Color.parseColor(cardBorder))
            }
            layoutParams = LinearLayout.LayoutParams(
                (170 * resources.displayMetrics.density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        box.addView(TextView(this).apply {
            text = "Rs"
            textSize = 13.5f
            setTextColor(Color.parseColor(labelGray))
            setPadding(16, 16, 12, 16)
            setBackgroundColor(Color.parseColor(cardBg))
        })
        val field = EditText(this).apply {
            hint = "0.00"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            background = null
            textSize = 13.5f
            setPadding(14, 14, 14, 14)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        box.addView(field)
        return Pair(box, field)
    }

    // ================= BOTTOM ACTION BAR =================
    private fun buildBottomBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 4, 20, 4)
            setBackgroundColor(Color.parseColor(cardWhite))
            elevation = 10f

            deleteBtn = TextView(this@PurchaseActivity).apply {
                text = Loc.t(this@PurchaseActivity, "Delete", "\u0688\u06CC\u0644\u06CC\u0679")
                textSize = 15f
                setTextColor(Color.parseColor(textDark))
                gravity = Gravity.CENTER
                setPadding(0, 22, 0, 22)
                visibility = View.GONE
                setOnClickListener { confirmDelete() }
            }
            addView(deleteBtn)

            addView(TextView(this@PurchaseActivity).apply {
                text = Loc.t(this@PurchaseActivity, "Save", "\u0645\u062D\u0641\u0648\u0638 \u06A9\u0631\u06CC\u06BA")
                textSize = 15.5f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 22, 0, 22)
                background = GradientDrawable().apply { setColor(Color.parseColor(blue)) }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(20, 10, 20, 10)
                }
                setOnClickListener { savePurchase() }
            })

            addView(TextView(this@PurchaseActivity).apply {
                text = "\u22EE"
                textSize = 18f
                setTextColor(Color.parseColor(textDark))
                gravity = Gravity.CENTER
                setPadding(16, 22, 4, 22)
                setOnClickListener { showOverflowMenu() }
            })
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(Loc.t(this, "Delete purchase?", "\u062E\u0631\u06CC\u062F\u0627\u0631\u06CC \u062E\u062A\u0645 \u06A9\u0631\u06CC\u06BA\u061F"))
            .setMessage(Loc.t(this, "This cannot be undone.", "\u0627\u0633\u06D2 \u0648\u0627\u067E\u0633 \u0646\u06C1\u06CC\u06BA \u06A9\u06CC\u0627 \u062C\u0627 \u0633\u06A9\u062A\u0627."))
            .setPositiveButton(Loc.t(this, "Delete", "\u0688\u06CC\u0644\u06CC\u0679")) { _, _ ->
                lifecycleScope.launch {
                    val db = PosDatabase.get(this@PurchaseActivity)
                    val purchase = db.purchaseDao().findPurchase(billNo)
                    // reverse the stock this bill had added, then remove the bill
                    for ((barcode, qty) in originalQtyByBarcode) {
                        db.productDao().decrease(barcode, qty.toInt())
                    }
                    purchase?.supplierId?.let { supId ->
                        db.supplierDao().addBalance(supId, -(purchase.total - purchase.paid))
                    }
                    db.purchaseDao().deleteItems(billNo)
                    db.purchaseDao().deletePurchase(billNo)
                    finish()
                }
            }
            .setNegativeButton(Loc.t(this, "Cancel", "\u0645\u0646\u0633\u0648\u062E \u06A9\u0631\u06CC\u06BA"), null)
            .show()
    }

    private fun savePurchase() {
        if (lineItems.isEmpty()) {
            Toast.makeText(this, Loc.t(this, "Add at least one item", "\u06A9\u0645 \u0627\u0632 \u06A9\u0645 \u0627\u06CC\u06A9 \u0622\u0626\u0679\u0645 \u0634\u0627\u0645\u0644 \u06A9\u0631\u06CC\u06BA"), Toast.LENGTH_SHORT).show()
            return
        }
        val subtotal = lineItems.sumOf { it.amount }
        val discount = discountField.text?.toString()?.toDoubleOrNull() ?: 0.0
        val shipping = shippingField.text?.toString()?.toDoubleOrNull() ?: 0.0
        val total = subtotal + shipping - discount
        val paid = 0.0 // no paid-amount field on this screen yet — see class doc

        lifecycleScope.launch {
            val db = PosDatabase.get(this@PurchaseActivity)

            val purchase = Purchase(
                billNo = billNo,
                supplierId = selectedSupplier?.id,
                total = total,
                paid = paid,
                subtotal = subtotal,
                discount = discount
            )
            val purchaseItemRows = lineItems.map {
                PurchaseItem(
                    billNo = billNo,
                    barcode = it.barcode,
                    qty = it.qty.toInt(),
                    unitCost = it.rate,
                    amount = it.amount,
                    unit = it.unit
                )
            }

            if (editing) {
                db.purchaseDao().deleteItems(billNo)
            }
            db.purchaseDao().purchase(purchase)
            db.purchaseDao().items(purchaseItemRows)

            // adjust stock by the delta between what this bill used to add and what it adds now
            val newQtyByBarcode = lineItems.groupBy { it.barcode }.mapValues { (_, v) -> v.sumOf { i -> i.qty } }
            val allBarcodes = originalQtyByBarcode.keys + newQtyByBarcode.keys
            for (barcode in allBarcodes) {
                val delta = (newQtyByBarcode[barcode] ?: 0.0) - (originalQtyByBarcode[barcode] ?: 0.0)
                if (delta > 0) db.productDao().increase(barcode, delta.toInt())
                else if (delta < 0) db.productDao().decrease(barcode, (-delta).toInt())
            }

            selectedSupplier?.let { db.supplierDao().addBalance(it.id, total - paid) }

            Toast.makeText(
                this@PurchaseActivity,
                Loc.t(this@PurchaseActivity, "Saved: Rs %.2f".format(total), "\u0645\u062D\u0641\u0648\u0638: \u0631\u0648\u067E\u06D2 %.2f".format(total)),
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }

    private fun showOverflowMenu() {
        val options = arrayOf(
            Loc.t(this, "Print", "\u067E\u0631\u0646\u0679"),
            Loc.t(this, "Share", "\u0634\u06CC\u0626\u0631 \u06A9\u0631\u06CC\u06BA")
        )
        AlertDialog.Builder(this)
            .setItems(options) { _, _ -> /* hook up per-action handling */ }
            .show()
    }

    // ================= UI helpers =================
    private fun spacer(heightDp: Int) = View(this).apply {
        val px = (heightDp * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px)
    }
}
