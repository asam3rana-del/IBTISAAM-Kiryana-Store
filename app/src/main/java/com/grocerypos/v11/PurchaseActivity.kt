package com.grocerypos.v11.ui

import android.app.DatePickerDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class PurchaseLine(
    val itemName: String,
    val barcode: String?,
    val qty: Double,
    val unit: String,
    val rate: Double,
    val amount: Double,
    val mainUnit: String,
    val secondaryUnit: String,
    val secondaryUnitQty: Double,
    val tertiaryUnit: String = "",
    val tertiaryUnitQty: Double = 0.0
)

private fun PurchaseLine.isSecondary(): Boolean =
    secondaryUnit.isNotEmpty() && unit == secondaryUnit && secondaryUnitQty > 0
private fun PurchaseLine.isTertiary(): Boolean =
    tertiaryUnit.isNotEmpty() && unit == tertiaryUnit && tertiaryUnitQty > 0 && secondaryUnitQty > 0
private fun PurchaseLine.mainUnitQty(): Double = when {
    isTertiary() -> qty / (secondaryUnitQty * tertiaryUnitQty)
    isSecondary() -> qty / secondaryUnitQty
    else -> qty
}
private fun PurchaseLine.mainUnitRate(): Double = when {
    isTertiary() -> rate * secondaryUnitQty * tertiaryUnitQty
    isSecondary() -> rate * secondaryUnitQty
    else -> rate
}
private fun genBillNo(): String = "PUR" + System.currentTimeMillis()

class PurchaseActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_BILL_NO = "billNo"
        private const val PREFS_NAME = "purchase_draft_prefs"
        private const val KEY_DRAFT = "draft_json"
        private const val PREFS_UNIT = "unit_preset_prefs"
        private const val KEY_LAST_PRIMARY = "last_primary"
        private const val KEY_LAST_SECONDARY = "last_secondary"
        private const val KEY_LAST_SEC_QTY = "last_sec_qty"
        private const val KEY_LAST_TERTIARY = "last_tertiary"
        private const val KEY_LAST_TER_QTY = "last_ter_qty"
    }

    private val bg = "#F4F6F8"
    private val cardWhite = "#FFFFFF"
    private val navy = "#0F9B8E"
    private val teal = "#0B2545"
    private val textDark = "#0B2545"
    private val textMuted = "#7C8798"
    private val border = "#E3E8EE"
    private val red = "#E5484D"
    private val billedBlue = "#90CAF9"
    private val billedBlueDark = "#42A5F5"

    private lateinit var dateValueText: TextView
    private lateinit var firmNameText: TextView
    private lateinit var supplierBalanceText: TextView
    private lateinit var partyName: AutoCompleteTextView
    private lateinit var itemEntrySection: LinearLayout
    private lateinit var addItemsTrigger: TextView
    private lateinit var itemsContainer: LinearLayout
    private lateinit var grandTotalText: TextView
    private lateinit var paymentSection: LinearLayout
    private lateinit var paidInput: EditText
    private lateinit var dueAmountText: TextView
    private lateinit var paidWarningText: TextView
    private lateinit var saveButton: Button
    private lateinit var deleteButton: Button
    private lateinit var overflowButton: TextView
    private lateinit var scrollArea: ScrollView
    private lateinit var billedItemsHeader: LinearLayout
    private lateinit var billedItemsChevron: TextView

    private var suppliers = listOf<Supplier>()
    private var products = listOf<Product>()
    private var allUnits = listOf("pcs", "kg", "Pao", "gram", "g", "box", "dozen", "carton", "ctn", "outer", "dabbi", "Ctn", "Box", "Nos", "Bkt", "Kg")
    private val lines = mutableListOf<PurchaseLine>()
    private var purchaseDateMillis = System.currentTimeMillis()
    private var itemsExpanded = true
    private var editBillNo: String? = null
    private var originalPurchase: Purchase? = null
    private var originalItems: List<PurchaseItem> = emptyList()
    private var suppressDraftSave = false
    private var draftRestored = false

    private val billScanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val json = result.data?.getStringExtra(BillScanActivity.RESULT_ITEMS_JSON)
            if (!json.isNullOrBlank()) handleScannedItems(json)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        editBillNo = intent.getStringExtra(EXTRA_BILL_NO)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 80)
            setBackgroundColor(Color.parseColor(bg))
        }
        // Header, Firm, Party same as before...
        // ... (header code wesa hi rahega)

        // ADD ITEM TRIGGER - NEW WINDOW
        itemEntrySection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 20, 22, 20)
            background = strokedBg(border, cardWhite, 18)
        }
        addItemsTrigger = TextView(this).apply {
            text = "➕  Add Items"
            textSize = 14.5f
            setTextColor(Color.parseColor(teal))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setOnClickListener { openAddItemNewWindow() }
        }
        itemEntrySection.addView(addItemsTrigger)
        root.addView(itemEntrySection)

        // BILLED ITEMS HEADER - Screenshot blue #90CAF9
        billedItemsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 14, 20, 14)
            background = roundedBg(billedBlue, 12)
            visibility = View.GONE
        }
        billedItemsHeader.addView(TextView(this).apply { text = "✓ Billed Items"; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        root.addView(billedItemsHeader)

        itemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(itemsContainer)

        // Payment - Paid Amount last page pe
        paymentSection = premiumCard()
        paidInput = EditText(this).apply { hint = "0"; inputType = InputType.TYPE_CLASS_NUMBER }
        paymentSection.addView(paidInput)
        root.addView(paymentSection)

        // Due, Save buttons same...
        scrollArea = ScrollView(this).apply { addView(root) }
        setContentView(scrollArea)

        loadSuppliers(); loadUnits(); loadProducts(); loadFirmName()
        if (editBillNo == null) restoreDraftIfAny()
    }

    // NEW WINDOW LOGIC
    private fun openAddItemNewWindow() {
        val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(bg))
            setPadding(24, 24, 24, 24)
        }
        root.addView(TextView(this).apply {
            text = "←  Add Item (New Window)"; textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setOnClickListener { dialog.dismiss() }
        })

        val itemNameInput = AutoCompleteTextView(this).apply {
            hint = "Item Name"; threshold = 1
            setAdapter(ArrayAdapter(this@PurchaseActivity, android.R.layout.simple_dropdown_item_1line, products.map { it.name }))
        }
        val qtyInput = EditText(this).apply { hint = "Quantity"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val unitSpinner = Spinner(this).apply { adapter = ArrayAdapter(this@PurchaseActivity, android.R.layout.simple_spinner_dropdown_item, allUnits) }
        val rateInput = EditText(this).apply { hint = "Rate"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }

        val addBtn = Button(this).apply {
            text = "ADD TO BILL"; setTextColor(Color.WHITE); background = roundedBg(teal, 14)
            setOnClickListener {
                val q = qtyInput.text.toString().toDoubleOrNull() ?: 0.0
                val r = rateInput.text.toString().toDoubleOrNull() ?: 0.0
                val line = PurchaseLine(itemNameInput.text.toString(), null, q, unitSpinner.selectedItem.toString(), r, q*r, "", "", 0.0)
                lines.add(line); renderItemsList(); updateGrandTotal(); dialog.dismiss()
            }
        }
        root.addView(itemNameInput); root.addView(qtyInput); root.addView(unitSpinner); root.addView(rateInput); root.addView(addBtn)
        dialog.setContentView(root); dialog.show()
    }

    // SCREENSHOT STYLE
    private fun renderItemsList() {
        itemsContainer.removeAllViews()
        billedItemsHeader.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        lines.forEachIndexed { index, line ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(28, 18, 28, 18)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F5F5F5")); setStroke(1, Color.parseColor(border)); cornerRadius = 14f
                }
            }
            val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            topRow.addView(TextView(this).apply { text = "#${34+index}"; background = strokedBg(border, "#FFFFFF", 6); setPadding(12,4,12,4) })
            topRow.addView(TextView(this).apply { text = "  ${line.itemName}"; layoutParams = LinearLayout.LayoutParams(0, -2, 1f); setTypeface(typeface, android.graphics.Typeface.BOLD) })
            topRow.addView(TextView(this).apply { text = "Rs ${"%,.0f".format(line.amount)}"; setTypeface(typeface, android.graphics.Typeface.BOLD) })
            val bottomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            bottomRow.addView(TextView(this).apply { text = "Item Subtotal"; layoutParams = LinearLayout.LayoutParams(0, -2, 1f); textSize = 11f })
            bottomRow.addView(TextView(this).apply { text = "${line.qty} ${line.unit} x ${line.rate.toInt()} = Rs ${"%,.0f".format(line.amount)}"; textSize = 11f })
            row.addView(topRow); row.addView(bottomRow)
            itemsContainer.addView(row)
        }
    }

    // Baqi sare functions wese hi rahenge...
    // loadSuppliers(), loadUnits(), savePurchase(), proceedSave() etc.
}
