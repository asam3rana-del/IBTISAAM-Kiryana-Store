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
    secondaryUnit.isNotEmpty() && unit.equals(secondaryUnit, ignoreCase = true) && secondaryUnitQty > 0

private fun PurchaseLine.isTertiary(): Boolean =
    tertiaryUnit.isNotEmpty() && unit.equals(tertiaryUnit, ignoreCase = true) && tertiaryUnitQty > 0 && secondaryUnitQty > 0

private fun PurchaseLine.isMain(): Boolean =
    mainUnit.isNotEmpty() && unit.equals(mainUnit, ignoreCase = true)

// ====== FIX 3: Outer 0.04 ka asal hal ======
/** Base qty hamesha sab se choti unit (dabbi) me */
private fun PurchaseLine.baseQty(): Double = when {
    isTertiary() -> qty
    isSecondary() -> qty * tertiaryUnitQty
    isMain() -> {
        if (tertiaryUnit.isNotEmpty() && secondaryUnit.isNotEmpty()) qty * secondaryUnitQty * tertiaryUnitQty
        else if (secondaryUnit.isNotEmpty()) qty * secondaryUnitQty
        else qty
    }
    else -> qty
}

/** Main unit me qty - display ke liye */
private fun PurchaseLine.mainUnitQty(): Double {
    val base = baseQty()
    return when {
        tertiaryUnit.isNotEmpty() && secondaryUnit.isNotEmpty() -> base / (secondaryUnitQty * tertiaryUnitQty)
        secondaryUnit.isNotEmpty() -> base / secondaryUnitQty
        else -> base
    }
}

private fun PurchaseLine.mainUnitRate(): Double {
    // Rate hamesha per selected unit se per main unit me convert
    return when {
        isTertiary() -> rate * secondaryUnitQty * tertiaryUnitQty
        isSecondary() -> rate * tertiaryUnitQty
        else -> rate
    }
}

/** 0 Ctn x 2 Outer x 0 Dabbi format */
private fun PurchaseLine.format3Tier(): String {
    if (tertiaryUnit.isEmpty() || secondaryUnit.isEmpty()) {
        return "${formatQty(qty)} ${unit}"
    }
    val base = baseQty()
    val totalPerMain = secondaryUnitQty * tertiaryUnitQty
    val ctn = (base / totalPerMain).toInt()
    val remAfterCtn = base % totalPerMain
    val outer = (remAfterCtn / tertiaryUnitQty).toInt()
    val dabbi = (remAfterCtn % tertiaryUnitQty).toInt()
    return "$ctn Ctn x $outer Outer x $dabbi Dabbi"
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
    // ====== FIX 1: Duplicate units khatam + add new unit support ======
    private var allUnits = mutableListOf("pcs", "kg", "pao", "gram", "g", "box", "dozen", "carton", "ctn", "outer", "dabbi", "nos", "bkt")
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

    // ... baqi onCreate, UI code apka waisa hi rahega ...
    // Yahan se neeche sirf fix wale important functions diye hain, baqi file ke liye apni purani file ka code rehne do

    // ====== FIX 1 CONTINUE: Unit spinner me Add New Unit ======
    private fun getUnitAdapter(context: Context): ArrayAdapter<String> {
        val cleaned = allUnits.map { it.lowercase() }.distinct().toMutableList()
        allUnits = cleaned.toMutableList()
        val listWithAdd = cleaned.toMutableList()
        listWithAdd.add("+ Add New Unit")
        return ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, listWithAdd)
    }

    private fun promptAddUnitFromPurchase(onAdded: (String) -> Unit) {
        val input = EditText(this).apply { 
            hint = "New unit name"
            setPadding(32, 24, 32, 24) 
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("New Unit")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val v = input.text.toString().trim().lowercase()
                if (v.isNotEmpty() && v != "+ add new unit") {
                    if (!allUnits.any { it.lowercase() == v }) {
                        allUnits.add(v)
                        // DB me bhi save karo
                        lifecycleScope.launch {
                            try {
                                PosDatabase.get(this@promptAddUnitFromPurchase).unitDao().insert(UnitType(v))
                            } catch (e: Exception) {}
                        }
                    }
                    onAdded(v)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ====== FIX 2: Billed items numbering 1,2,3,4 ======
    // Apke render function me (jahan billed items ka container banta hai) ye logic lagao
    private fun renderBilledItems_FIXED() {
        itemsContainer.removeAllViews()
        // yahan pehle apka for loop tha
        lines.forEachIndexed { index, line ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(12, 10, 12, 10)
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    setStroke(1, Color.parseColor(border))
                    cornerRadius = 12f
                }
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0,0,0,8)
                layoutParams = lp
            }
            // FIX: index+1 = 1,2,3,4
            row.addView(TextView(this).apply {
                text = "${index + 1}"
                textSize = 12f
                setTextColor(Color.parseColor(textDark))
                layoutParams = LinearLayout.LayoutParams(40, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            row.addView(TextView(this).apply {
                text = line.itemName
                textSize = 13f
                setTextColor(Color.parseColor(textDark))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = line.format3Tier() // FIX 3 ka display
                textSize = 11f
                setTextColor(Color.parseColor(textMuted))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            row.addView(TextView(this).apply {
                text = "Rs %.0f".format(line.amount)
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            itemsContainer.addView(row)
        }
        grandTotalText.text = "Total: Rs %.0f".format(lines.sumOf { it.amount })
    }

    // ====== FIX 3: proceedSave me stock logic ======
    private fun proceedSave_FIXED(party: String, grandTotal: Double) {
        val discount = 0.0
        val amountPaid = Math.round(paidInput.text.toString().toDoubleOrNull() ?: 0.0).toDouble().coerceIn(0.0, grandTotal)
        val paymentMethod = "Cash"
        val matchedSupplier = suppliers.find { it.name.equals(party, ignoreCase = true) }
        var supplierId = matchedSupplier?.id
        val billNo = editBillNo ?: genBillNo()
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PurchaseActivity)
            if (supplierId == null && party.isNotEmpty()) { supplierId = db.supplierDao().insert(Supplier(name = party)) }
            val original = originalPurchase
            if (original != null) {
                originalItems.forEach { db.productDao().decreaseForce(it.barcode, it.qty.toInt()) }
                val originalOutstanding = original.total - original.paid
                if (original.supplierId != null && originalOutstanding > 0) { db.supplierDao().addBalance(original.supplierId, -originalOutstanding) }
                db.purchaseDao().deleteItems(billNo); db.purchaseDao().deletePurchase(billNo); db.paymentDao().deleteByReference(billNo); db.cashTransactionDao().deleteByReference(billNo)
            }
            db.purchaseDao().purchase(Purchase(billNo = billNo, supplierId = supplierId, total = grandTotal, paid = amountPaid, createdAt = purchaseDateMillis, subtotal = lines.sumOf { it.amount }, discount = discount))
            // Stock main unit me nahi, base me save karo
            db.purchaseDao().items(lines.map { line -> 
                PurchaseItem(billNo = billNo, barcode = line.barcode ?: "", qty = line.mainUnitQty(), unitCost = line.mainUnitRate(), amount = line.amount, unit = line.unit) 
            })
            lines.forEach { line ->
                val barcode = line.barcode ?: return@forEach
                val before = db.productDao().find(barcode)
                // FIX: baseQty use karo, mainUnitQty nahi
                val purchasedBaseQty = line.baseQty().roundToInt()
                db.productDao().increase(barcode, purchasedBaseQty)
                if (before != null && purchasedBaseQty > 0) {
                    val oldStock = before.stock
                    val oldCost = before.cost
                    val purchaseRatePerBase = line.mainUnitRate() / (if(line.tertiaryUnit.isNotEmpty()) line.secondaryUnitQty * line.tertiaryUnitQty else if(line.secondaryUnit.isNotEmpty()) line.secondaryUnitQty else 1.0)
                    val newCost = if (oldStock <= 0) { purchaseRatePerBase } else { ((oldStock * oldCost) + (purchasedBaseQty * purchaseRatePerBase)) / (oldStock + purchasedBaseQty).toDouble() }
                    db.productDao().updateCost(barcode, newCost)
                }
            }
            val outstanding = grandTotal - amountPaid
            if (supplierId != null && outstanding > 0) { db.supplierDao().addBalance(supplierId!!, outstanding) }
            if (supplierId != null && amountPaid > 0) { db.paymentDao().insert(Payment(reference = billNo, partyType = "supplier", partyId = supplierId, amount = amountPaid, method = paymentMethod, note = if (original != null) "Purchase payment (edited)" else "Purchase payment")) }
            if (amountPaid > 0) { db.cashTransactionDao().insert(CashTransaction(type = "OUT", method = paymentMethod.lowercase(), amount = amountPaid, reason = "Purchase", reference = billNo)) }
            suppressDraftSave = true
            Toast.makeText(this@PurchaseActivity, if (original != null) "Purchase updated" else "Purchase saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // Dummy placeholders taake file compile ho - apni purani file se ye functions copy rehne do
    private fun handleScannedItems(json: String) {}
    private fun hideKeyboard() {}
    private fun clearDraft() {}
    private fun roundedBg(color: String, radius: Int) = GradientDrawable().apply { setColor(Color.parseColor(color)); cornerRadius = radius.toFloat() }
    private fun applyElevation(v: View, dp: Float) {}
    private fun formatQty(q: Double): String = if (q % 1 == 0.0) q.toInt().toString() else "%.2f".format(q)
    private fun openBillPreview(billNo: String, forSaving: Boolean, party: String = "", grandTotal: Double = 0.0, discount: Double = 0.0, amountPaid: Double = 0.0, paymentMethod: String = "Cash") {}
}
