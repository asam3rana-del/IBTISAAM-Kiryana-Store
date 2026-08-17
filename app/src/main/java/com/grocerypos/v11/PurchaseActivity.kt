package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.app.DatePickerDialog
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Product
import com.grocerypos.v11.Purchase
import com.grocerypos.v11.PurchaseItem
import com.grocerypos.v11.Supplier
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * "Purchase" entry/edit screen backed by the real PurchaseDao/ProductDao/SupplierDao —
 * Date / Firm Name / Party Balance / Party Name section (unchanged), followed by an inline
 * Item Entry card ("+ Hide/Show Item Entry" toggle, Item Name search, Quantity + Unit,
 * Rate, a live "Total Amount: Rs …" line, and an ADD ITEM button), the list of items added
 * so far, a Total Amount card, a Method + Amount Paid row, and a bottom SAVE PURCHASE button
 * (plus a Delete link when editing an existing bill).
 *
 * Start it for a NEW purchase with a plain Intent. Start it to EDIT an existing purchase by
 * putting the bill number under EXTRA_BILL_NO:
 *   startActivity(Intent(this, PurchaseActivity::class.java).putExtra(PurchaseActivity.EXTRA_BILL_NO, billNo))
 *
 * Schema notes (com.grocerypos.v11.PosDatabase, v17):
 *  - PurchaseItem has no per-line discount column. This screen no longer collects a header
 *    Discount/Shipping charge, so Purchase.discount is always saved as 0.0.
 *  - The Method field (Cash / Credit / Bank Transfer / Cheque) is UI-only for now — Purchase
 *    has no dedicated column for it. Add a migration + column if you need it persisted later.
 *  - Amount Paid is now wired into Purchase.paid, so partial/full cash purchases are possible;
 *    whatever remains unpaid still lands on the supplier's balance ("You'll Give" on the
 *    dashboard).
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
    private val teal = "#14A98A"
    private val navy = "#0E1B3C"

    private lateinit var dateValue: TextView
    private lateinit var firmNameValue: TextView
    private lateinit var partyBalanceValue: TextView
    private lateinit var partyNameValue: TextView

    private lateinit var hideEntryToggleLabel: TextView
    private lateinit var itemEntryCard: LinearLayout
    private lateinit var itemNameField: AutoCompleteTextView
    private lateinit var quantityField: EditText
    private lateinit var unitValue: TextView
    private lateinit var rateField: EditText
    private lateinit var entryTotalValue: TextView
    private lateinit var itemsListContainer: LinearLayout
    private lateinit var totalAmountValue: TextView
    private lateinit var methodValue: TextView
    private lateinit var amountPaidField: EditText
    private lateinit var deleteBtn: TextView

    private var itemEntryExpanded = true
    private var entryUnit: String = "pcs"
    private var selectedEntryProduct: Product? = null
    private var selectedMethod: String = "Cash"
    private val unitOptions = listOf("pcs", "kg", "g", "ltr", "ml", "box", "dozen")
    private val methodOptions = listOf("Cash", "Credit", "Bank Transfer", "Cheque")

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

    private var selectedDateMillis: Long = System.currentTimeMillis()
    private val dateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

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

        root.addView(buildDateAndFirmSection())
        root.addView(sectionDivider())
        root.addView(buildPartySection())
        root.addView(sectionDivider())
        root.addView(buildItemEntrySection())
        root.addView(buildTotalAmountCard())
        root.addView(buildMethodAndPaidRow())
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
            itemNameField.setAdapter(
                ArrayAdapter(this@PurchaseActivity, android.R.layout.simple_dropdown_item_1line, allProducts.map { it.name })
            )

            val savedName = db.appSettingDao().get("business_name")?.value
            if (!savedName.isNullOrBlank()) firmNameValue.text = savedName

            if (editing) {
                val purchase = db.purchaseDao().findPurchase(billNo)
                val existingItems = db.purchaseDao().itemsForBill(billNo)
                selectedSupplier = allSuppliers.find { it.id == purchase?.supplierId }
                selectedDateMillis = purchase?.createdAt ?: System.currentTimeMillis()
                dateValue.text = dateFmt.format(Date(selectedDateMillis))
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
                if ((purchase?.paid ?: 0.0) != 0.0) amountPaidField.setText("%.2f".format(purchase!!.paid))
                deleteBtn.visibility = View.VISIBLE
            } else {
                originalQtyByBarcode = emptyMap()
                deleteBtn.visibility = View.GONE
            }

            updatePartyDisplay()
            renderLineItems()
        }
    }

    /** Small helper: take the first emission of a cold/hot Flow without keeping a live collector open. */
    private suspend fun <T> collectFirst(flowProvider: () -> kotlinx.coroutines.flow.Flow<List<T>>): List<T> {
        var result: List<T> = emptyList()
        try {
            flowProvider().collect {
                result = it
                throw StopCollecting()
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
            setPadding(20, 20, 20, 22)
            setBackgroundColor(Color.parseColor(cardWhite))

            // Push the header below the status bar / camera cutout on edge-to-edge devices -
            // a fixed top padding alone gets hidden under the cutout on notched/punch-hole phones.
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).top
                view.setPadding(view.paddingLeft, 20 + topInset, view.paddingRight, view.paddingBottom)
                insets
            }

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

    // ================= DATE + FIRM NAME =================
    private fun buildDateAndFirmSection(): LinearLayout {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(cardWhite))
        }

        val dateRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 14, 20, 14)
            isClickable = true
            setOnClickListener { showDatePicker() }
        }
        dateRow.addView(TextView(this).apply {
            text = Loc.t(this@PurchaseActivity, "Date", "\u062A\u0627\u0631\u06CC\u062E")
            textSize = 12f
            setTextColor(Color.parseColor(labelGray))
        })
        val dateValueRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dateValue = TextView(this).apply {
            text = dateFmt.format(Date(selectedDateMillis))
            textSize = 15f
            setTextColor(Color.parseColor(textDark))
            setPadding(0, 4, 8, 0)
        }
        dateValueRow.addView(dateValue)
        dateValueRow.addView(TextView(this).apply {
            text = "\u25BE"
            textSize = 13f
            setTextColor(Color.parseColor(labelGray))
        })
        dateRow.addView(dateValueRow)
        wrap.addView(dateRow)
        wrap.addView(hairline())

        val firmRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 16, 20, 16)
        }
        firmRow.addView(TextView(this).apply {
            text = Loc.t(this@PurchaseActivity, "Firm Name: ", "\u0641\u0631\u0645 \u0646\u06CC\u0645: ")
            textSize = 13.5f
            setTextColor(Color.parseColor(labelGray))
        })
        firmNameValue = TextView(this).apply {
            text = Loc.t(this@PurchaseActivity, "My Store", "\u0645\u06CC\u0631\u06CC \u062F\u06A9\u0627\u0646")
            textSize = 14f
            setTextColor(Color.parseColor(textDark))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        firmRow.addView(firmNameValue)
        firmRow.addView(TextView(this).apply {
            text = "\u25BE"
            textSize = 13f
            setTextColor(Color.parseColor(labelGray))
        })
        wrap.addView(firmRow)

        return wrap
    }

    private fun showDatePicker() {
        val cal = Calendar.getInstance().apply { timeInMillis = selectedDateMillis }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                cal.set(year, month, day)
                selectedDateMillis = cal.timeInMillis
                dateValue.text = dateFmt.format(Date(selectedDateMillis))
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    // ================= PARTY BALANCE + PARTY NAME =================
    private fun buildPartySection(): LinearLayout {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 18, 20, 18)
            setBackgroundColor(Color.parseColor(cardWhite))
        }

        val balanceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        balanceRow.addView(TextView(this).apply {
            text = Loc.t(this@PurchaseActivity, "Party Balance: ", "\u067E\u0627\u0631\u0679\u06CC \u0628\u06CC\u0644\u06CC\u0646\u0633: ")
            textSize = 12.5f
            setTextColor(Color.parseColor(labelGray))
        })
        partyBalanceValue = TextView(this).apply {
            text = "Rs 0.00"
            textSize = 12.5f
            setTextColor(Color.parseColor(red))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        balanceRow.addView(partyBalanceValue)
        wrap.addView(balanceRow)
        wrap.addView(spacer(6))

        wrap.addView(TextView(this).apply {
            text = Loc.t(this@PurchaseActivity, "Party Name*", "\u067E\u0627\u0631\u0679\u06CC \u0646\u06CC\u0645*")
            textSize = 12f
            setTextColor(Color.parseColor(labelGray))
            setPadding(4, 0, 0, 6)
        })
        val partyBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 16, 16, 16)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(cardWhite))
                cornerRadius = 8f
                setStroke(2, Color.parseColor(cardBorder))
            }
            isClickable = true
            setOnClickListener { showPartyPicker() }
        }
        partyNameValue = TextView(this).apply {
            text = Loc.t(this@PurchaseActivity, "Cash Purchase", "\u0646\u0642\u062F \u062E\u0631\u06CC\u062F\u0627\u0631\u06CC")
            textSize = 16f
            setTextColor(Color.parseColor(textDark))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        partyBox.addView(partyNameValue)
        wrap.addView(partyBox)

        return wrap
    }

    private fun updatePartyDisplay() {
        val s = selectedSupplier
        if (s != null) {
            partyNameValue.text = s.name
            val closing = s.openingBalance + s.balance
            partyBalanceValue.text = "Rs %.2f".format(kotlin.math.abs(closing))
            partyBalanceValue.setTextColor(Color.parseColor(if (closing > 0) red else "#4CAF50"))
        } else {
            partyNameValue.text = Loc.t(this, "Cash Purchase", "\u0646\u0642\u062F \u062E\u0631\u06CC\u062F\u0627\u0631\u06CC")
            partyBalanceValue.text = "Rs 0.00"
            partyBalanceValue.setTextColor(Color.parseColor(labelGray))
        }
    }

    private fun showPartyPicker() {
        val names = mutableListOf(Loc.t(this, "Cash Purchase (no supplier)", "\u0646\u0642\u062F \u062E\u0631\u06CC\u062F\u0627\u0631\u06CC (\u0628\u063A\u06CC\u0631 \u0633\u067E\u0644\u0627\u0626\u0631)"))
        names.addAll(allSuppliers.map { it.name })
        AlertDialog.Builder(this)
            .setTitle(Loc.t(this, "Select Party", "\u067E\u0627\u0631\u0679\u06CC \u0645\u0646\u062A\u062E\u0628 \u06A9\u0631\u06CC\u06BA"))
            .setItems(names.toTypedArray()) { _, which ->
                selectedSupplier = if (which == 0) null else allSuppliers[which - 1]
                updatePartyDisplay()
            }
            .show()
    }

    private fun hairline() = View(this).apply {
        setBackgroundColor(Color.parseColor(divider))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    private fun fieldLabel(en: String, ur: String): TextView = TextView(this).apply {
        text = Loc.t(this@PurchaseActivity, en, ur)
        textSize = 11.5f
        setTextColor(Color.parseColor(labelGray))
        setPadding(0, 0, 0, 4)
    }

    // ================= ITEM ENTRY (inline card) =================
    private fun buildItemEntrySection(): LinearLayout {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
        }

        val toggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 16)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(cardWhite))
                cornerRadius = 10f
                setStroke(2, Color.parseColor(cardBorder))
            }
            isClickable = true
            setOnClickListener { toggleItemEntry() }
        }
        toggleRow.addView(TextView(this).apply {
            text = "+"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(teal))
                shape = GradientDrawable.OVAL
            }
            layoutParams = LinearLayout.LayoutParams(
                (26 * resources.displayMetrics.density).toInt(),
                (26 * resources.displayMetrics.density).toInt()
            ).apply { marginEnd = (10 * resources.displayMetrics.density).toInt() }
        })
        hideEntryToggleLabel = TextView(this).apply {
            text = Loc.t(this@PurchaseActivity, "Hide Item Entry", "\u0622\u0626\u0679\u0645 \u0627\u0646\u0679\u0631\u06CC \u0686\u06BE\u067E\u0627\u0626\u06CC\u06BA")
            textSize = 15f
            setTextColor(Color.parseColor(teal))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        toggleRow.addView(hideEntryToggleLabel)
        wrap.addView(toggleRow)
        wrap.addView(spacer(14))

        itemEntryCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(cardWhite))
                cornerRadius = 10f
                setStroke(2, Color.parseColor(cardBorder))
            }
        }

        itemEntryCard.addView(fieldLabel("Item Name", "\u0622\u0626\u0679\u0645 \u06A9\u0627 \u0646\u0627\u0645"))
        itemNameField = AutoCompleteTextView(this).apply {
            hint = Loc.t(this@PurchaseActivity, "Type to search...", "\u062A\u0644\u0627\u0634 \u06A9\u06CC \u0644\u06CC\u06D2 \u0644\u06A9\u06BE\u06CC\u06BA...")
            textSize = 15f
            setTextColor(Color.parseColor(textDark))
            setPadding(0, 10, 0, 10)
            background = null
            threshold = 1
            setOnItemClickListener { parent, _, position, _ ->
                val name = parent.getItemAtPosition(position) as String
                val product = allProducts.find { it.name == name }
                selectedEntryProduct = product
                if (product != null) {
                    entryUnit = product.unit.ifBlank { "pcs" }
                    unitValue.text = entryUnit
                    if (rateField.text.isNullOrBlank()) rateField.setText(trimNumber(product.cost))
                }
                updateEntryTotal()
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                    if (allProducts.none { it.name == s?.toString() }) selectedEntryProduct = null
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        itemEntryCard.addView(itemNameField)
        itemEntryCard.addView(hairline())
        itemEntryCard.addView(spacer(16))

        val qtyUnitRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val qtyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (16 * resources.displayMetrics.density).toInt()
            }
        }
        qtyBox.addView(fieldLabel("Quantity", "\u0645\u0642\u062F\u0627\u0631"))
        quantityField = EditText(this).apply {
            hint = "0"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 15f
            setTextColor(Color.parseColor(textDark))
            background = null
            setPadding(0, 10, 0, 10)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { updateEntryTotal() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        qtyBox.addView(quantityField)
        qtyBox.addView(hairline())

        val unitBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            isClickable = true
            setOnClickListener { showUnitPicker() }
        }
        unitBox.addView(fieldLabel("Unit", "\u06CC\u0648\u0646\u0679"))
        val unitRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        unitValue = TextView(this).apply {
            text = entryUnit
            textSize = 15f
            setTextColor(Color.parseColor(textDark))
            setPadding(0, 10, 0, 10)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        unitRow.addView(unitValue)
        unitRow.addView(TextView(this).apply {
            text = "\u25BE"
            textSize = 13f
            setTextColor(Color.parseColor(labelGray))
        })
        unitBox.addView(unitRow)
        unitBox.addView(hairline())

        qtyUnitRow.addView(qtyBox)
        qtyUnitRow.addView(unitBox)
        itemEntryCard.addView(qtyUnitRow)
        itemEntryCard.addView(spacer(16))

        itemEntryCard.addView(fieldLabel("Rate", "\u0631\u06CC\u0679"))
        rateField = EditText(this).apply {
            hint = Loc.t(this@PurchaseActivity, "Price / Unit", "\u0642\u06CC\u0645\u062A / \u06CC\u0648\u0646\u0679")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 15f
            setTextColor(Color.parseColor(textDark))
            background = null
            setPadding(0, 10, 0, 10)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { updateEntryTotal() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        itemEntryCard.addView(rateField)
        itemEntryCard.addView(hairline())
        itemEntryCard.addView(spacer(16))

        entryTotalValue = TextView(this).apply {
            text = Loc.t(this@PurchaseActivity, "Total Amount: Rs 0.00", "\u0679\u0648\u0679\u0644 \u0631\u0642\u0645: Rs 0.00")
            textSize = 15f
            setTextColor(Color.parseColor(teal))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        itemEntryCard.addView(entryTotalValue)
        itemEntryCard.addView(spacer(14))

        itemEntryCard.addView(TextView(this).apply {
            text = Loc.t(this@PurchaseActivity, "ADD ITEM", "\u0622\u0626\u0679\u0645 \u0634\u0627\u0645\u0644 \u06A9\u0631\u06CC\u06BA")
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 26, 0, 26)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(teal))
                cornerRadius = 10f
            }
            setOnClickListener { addItemFromEntry() }
        })

        wrap.addView(itemEntryCard)
        wrap.addView(spacer(16))

        itemsListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(itemsListContainer)

        return wrap
    }

    private fun toggleItemEntry() {
        itemEntryExpanded = !itemEntryExpanded
        itemEntryCard.visibility = if (itemEntryExpanded) View.VISIBLE else View.GONE
        hideEntryToggleLabel.text = if (itemEntryExpanded)
            Loc.t(this, "Hide Item Entry", "\u0622\u0626\u0679\u0645 \u0627\u0646\u0679\u0631\u06CC \u0686\u06BE\u067E\u0627\u0626\u06CC\u06BA")
        else
            Loc.t(this, "Show Item Entry", "\u0622\u0626\u0679\u0645 \u0627\u0646\u0679\u0631\u06CC \u062F\u06A9\u06BE\u0627\u0626\u06CC\u06BA")
    }

    private fun showUnitPicker() {
        val popup = PopupMenu(this, unitValue)
        unitOptions.forEach { popup.menu.add(it) }
        popup.setOnMenuItemClickListener { item ->
            entryUnit = item.title.toString()
            unitValue.text = entryUnit
            true
        }
        popup.show()
    }

    private fun updateEntryTotal() {
        val qty = quantityField.text?.toString()?.toDoubleOrNull() ?: 0.0
        val rate = rateField.text?.toString()?.toDoubleOrNull() ?: 0.0
        val amount = qty * rate
        entryTotalValue.text = Loc.t(
            this,
            "Total Amount: Rs %.2f".format(amount),
            "\u0679\u0648\u0679\u0644 \u0631\u0642\u0645: Rs %.2f".format(amount)
        )
    }

    private fun addItemFromEntry() {
        val product = selectedEntryProduct
        if (product == null) {
            Toast.makeText(this, Loc.t(this, "Select an item from the list", "\u0641\u06C1\u0631\u0633\u062A \u0633\u06D2 \u0622\u0626\u0679\u0645 \u0645\u0646\u062A\u062E\u0628 \u06A9\u0631\u06CC\u06BA"), Toast.LENGTH_SHORT).show()
            return
        }
        val qty = quantityField.text?.toString()?.toDoubleOrNull() ?: 0.0
        if (qty <= 0.0) {
            Toast.makeText(this, Loc.t(this, "Enter a valid quantity", "\u0635\u062D\u06CC\u062D \u0645\u0642\u062F\u0627\u0631 \u062F\u0631\u062C \u06A9\u0631\u06CC\u06BA"), Toast.LENGTH_SHORT).show()
            return
        }
        val rate = rateField.text?.toString()?.toDoubleOrNull() ?: 0.0

        lineItems.add(UiLineItem(barcode = product.barcode, productName = product.name, qty = qty, unit = entryUnit, rate = rate))
        renderLineItems()

        itemNameField.setText("")
        quantityField.setText("")
        rateField.setText("")
        selectedEntryProduct = null
        entryUnit = "pcs"
        unitValue.text = entryUnit
        entryTotalValue.text = Loc.t(this, "Total Amount: Rs 0.00", "\u0679\u0648\u0679\u0644 \u0631\u0642\u0645: Rs 0.00")
    }

    // ================= ITEMS LIST =================
    private fun renderLineItems() {
        itemsListContainer.removeAllViews()
        lineItems.forEachIndexed { index, item ->
            itemsListContainer.addView(itemRow(item))
            if (index < lineItems.size - 1) itemsListContainer.addView(spacer(10))
        }
        updateTotalAmount()
    }

    private fun itemRow(item: UiLineItem): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 14, 16, 14)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(cardWhite))
                cornerRadius = 10f
                setStroke(2, Color.parseColor(cardBorder))
            }

            val info = LinearLayout(this@PurchaseActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this@PurchaseActivity).apply {
                text = item.productName
                textSize = 14.5f
                setTextColor(Color.parseColor(textDark))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            info.addView(TextView(this@PurchaseActivity).apply {
                val qtyStr = if (item.qty == item.qty.toLong().toDouble()) item.qty.toLong().toString() else item.qty.toString()
                text = "$qtyStr ${item.unit} x Rs %,.2f = Rs %,.2f".format(item.rate, item.amount)
                textSize = 12.5f
                setTextColor(Color.parseColor(labelGray))
            })
            addView(info)

            addView(TextView(this@PurchaseActivity).apply {
                text = "\u2715"
                textSize = 15f
                setTextColor(Color.parseColor(red))
                setPadding(20, 8, 8, 8)
                setOnClickListener {
                    lineItems.remove(item)
                    renderLineItems()
                }
            })
        }
    }

    private fun trimNumber(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    private fun sectionDivider() = View(this).apply {
        setBackgroundColor(Color.parseColor(divider))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (10 * resources.displayMetrics.density).toInt()
        )
    }

    // ================= TOTAL AMOUNT =================
    private fun buildTotalAmountCard(): LinearLayout {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 0, 20, 16)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 18, 18, 18)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(cardWhite))
                cornerRadius = 10f
                setStroke(2, Color.parseColor(cardBorder))
            }
        }
        row.addView(TextView(this).apply {
            text = Loc.t(this@PurchaseActivity, "Total Amount", "\u0679\u0648\u0679\u0644 \u0631\u0642\u0645")
            textSize = 12.5f
            setTextColor(Color.parseColor(labelGray))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        totalAmountValue = TextView(this).apply {
            text = "Rs 0.00"
            textSize = 20f
            setTextColor(Color.parseColor(teal))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        row.addView(totalAmountValue)
        outer.addView(row)
        return outer
    }

    private fun updateTotalAmount() {
        val total = lineItems.sumOf { it.amount }
        totalAmountValue.text = "Rs %,.2f".format(total)
    }

    // ================= METHOD + AMOUNT PAID =================
    private fun buildMethodAndPaidRow(): LinearLayout {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 0, 20, 20)
        }

        val methodBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 14, 16, 14)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(cardWhite))
                cornerRadius = 8f
                setStroke(2, Color.parseColor(cardBorder))
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (10 * resources.displayMetrics.density).toInt()
            }
            isClickable = true
            setOnClickListener { showMethodPicker() }
        }
        methodBox.addView(fieldLabel("Method", "\u0637\u0631\u06CC\u0642\u06C1"))
        val methodRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        methodValue = TextView(this).apply {
            text = selectedMethod
            textSize = 15f
            setTextColor(Color.parseColor(textDark))
            setPadding(0, 6, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        methodRow.addView(methodValue)
        methodRow.addView(TextView(this).apply {
            text = "\u25BE"
            textSize = 13f
            setTextColor(Color.parseColor(labelGray))
        })
        methodBox.addView(methodRow)
        outer.addView(methodBox)

        val paidBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 14, 16, 14)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(cardWhite))
                cornerRadius = 8f
                setStroke(2, Color.parseColor(cardBorder))
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        paidBox.addView(fieldLabel("Amount Paid", "\u0627\u062F\u0627 \u0634\u062F\u06C1 \u0631\u0642\u0645"))
        amountPaidField = EditText(this).apply {
            hint = "0.00"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 15f
            setTextColor(Color.parseColor(textDark))
            background = null
            setPadding(0, 6, 0, 0)
        }
        paidBox.addView(amountPaidField)
        outer.addView(paidBox)

        return outer
    }

    private fun showMethodPicker() {
        val popup = PopupMenu(this, methodValue)
        methodOptions.forEach { popup.menu.add(it) }
        popup.setOnMenuItemClickListener { item ->
            selectedMethod = item.title.toString()
            methodValue.text = selectedMethod
            true
        }
        popup.show()
    }

    // ================= BOTTOM ACTION BAR =================
    private fun buildBottomBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 10, 20, 10)
            setBackgroundColor(Color.parseColor(cardWhite))
            elevation = 10f

            // Keep the bar clear of the phone's gesture/navigation bar on edge-to-edge devices -
            // without this, the bottom ~16-48px of the bar renders underneath the system nav bar.
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val navBarInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                view.setPadding(view.paddingLeft, 10, view.paddingRight, 10 + navBarInset)
                insets
            }

            deleteBtn = TextView(this@PurchaseActivity).apply {
                text = Loc.t(this@PurchaseActivity, "Delete purchase", "\u062E\u0631\u06CC\u062F\u0627\u0631\u06CC \u062D\u0630\u0641 \u06A9\u0631\u06CC\u06BA")
                textSize = 13.5f
                setTextColor(Color.parseColor(red))
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 14)
                visibility = View.GONE
                setOnClickListener { confirmDelete() }
            }
            addView(deleteBtn)

            addView(TextView(this@PurchaseActivity).apply {
                text = Loc.t(this@PurchaseActivity, "SAVE PURCHASE", "\u062E\u0631\u06CC\u062F\u0627\u0631\u06CC \u0645\u062D\u0641\u0648\u0638 \u06A9\u0631\u06CC\u06BA")
                textSize = 15.5f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 26, 0, 26)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(navy))
                    cornerRadius = 6f
                }
                setOnClickListener { savePurchase() }
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
        val total = lineItems.sumOf { it.amount }
        val paid = amountPaidField.text?.toString()?.toDoubleOrNull() ?: 0.0
        // Method (selectedMethod) is UI-only for now — Purchase has no column to persist it, see class doc.

        lifecycleScope.launch {
            val db = PosDatabase.get(this@PurchaseActivity)

            val purchase = Purchase(
                billNo = billNo,
                supplierId = selectedSupplier?.id,
                total = total,
                paid = paid,
                createdAt = selectedDateMillis,
                subtotal = total,
                discount = 0.0
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

    // ================= UI helpers =================
    private fun spacer(heightDp: Int) = View(this).apply {
        val px = (heightDp * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px)
    }
}
