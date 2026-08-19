package com.grocerypos.v11

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

// ===== TOP LEVEL HELPERS - FIX 1: formatQty ko top level pe laya =====
private fun formatQty(q: Double): String = if (q % 1 == 0.0) q.toInt().toString() else "%.2f".format(q)

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

private fun PurchaseLine.mainUnitQty(): Double {
    val base = baseQty()
    return when {
        tertiaryUnit.isNotEmpty() && secondaryUnit.isNotEmpty() -> base / (secondaryUnitQty * tertiaryUnitQty)
        secondaryUnit.isNotEmpty() -> base / secondaryUnitQty
        else -> base
    }
}

private fun PurchaseLine.mainUnitRate(): Double {
    return when {
        isTertiary() -> rate * secondaryUnitQty * tertiaryUnitQty
        isSecondary() -> rate * tertiaryUnitQty
        else -> rate
    }
}

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // NOTE: Aapka asal UI code yahan tha, wohi rehne do. Build fix ke liye ye minimal setContentView kaafi hai
        // Agar aapke pass layout file hai to usko use karo: setContentView(R.layout.activity_purchase)
        // Filhal empty layout se build error khatam ho jayega
        val root = ScrollView(this).apply {
            addView(LinearLayout(this@PurchaseActivity).apply { orientation = LinearLayout.VERTICAL })
        }
        setContentView(root)
        // Aapka baqi initialization yahan call hota tha
    }

    private fun getUnitAdapter(context: Context): ArrayAdapter<String> {
        val cleaned = allUnits.map { it.lowercase(Locale.ROOT) }.distinct().toMutableList()
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
                val v = input.text.toString().trim().lowercase(Locale.ROOT)
                if (v.isNotEmpty() && v != "+ add new unit") {
                    if (!allUnits.any { it.lowercase(Locale.ROOT) == v }) {
                        allUnits.add(v)
                        lifecycleScope.launch {
                            try {
                                PosDatabase.get(this@PurchaseActivity).unitDao().insert(UnitType(v))
                            } catch (e: Exception) {
                                Log.e("Purchase", "unit insert failed", e)
                            }
                        }
                    }
                    onAdded(v)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderBilledItems_FIXED() {
        itemsContainer.removeAllViews()
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
                lp.setMargins(0, 0, 0, 8)
                layoutParams = lp
            }
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
                text = line.format3Tier()
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

    private fun proceedSave_FIXED(party: String, grandTotal: Double) {
        val discount = 0.0
        val amountPaid = (paidInput.text.toString().toDoubleOrNull() ?: 0.0).coerceIn(0.0, grandTotal)
        val paymentMethod = "Cash"
        val matchedSupplier = suppliers.find { it.name.equals(party, ignoreCase = true) }
        var supplierId = matchedSupplier?.id
        val billNo = editBillNo ?: genBillNo()
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PurchaseActivity)
            if (supplierId == null && party.isNotEmpty()) {
                supplierId = db.supplierDao().insert(Supplier(name = party))
            }
            val original = originalPurchase
            if (original != null) {
                for (item in originalItems) {
                    db.productDao().decreaseForce(item.barcode, item.qty.toInt())
                }
                val originalOutstanding = original.total - original.paid
                if (original.supplierId != null && originalOutstanding > 0) {
                    db.supplierDao().addBalance(original.supplierId, -originalOutstanding)
                }
                db.purchaseDao().deleteItems(billNo)
                db.purchaseDao().deletePurchase(billNo)
                db.paymentDao().deleteByReference(billNo)
                db.cashTransactionDao().deleteByReference(billNo)
            }
            db.purchaseDao().purchase(
                Purchase(
                    billNo = billNo,
                    supplierId = supplierId,
                    total = grandTotal,
                    paid = amountPaid,
                    createdAt = purchaseDateMillis,
                    subtotal = lines.sumOf { it.amount },
                    discount = discount
                )
            )
            db.purchaseDao().items(lines.map { line ->
                PurchaseItem(
                    billNo = billNo,
                    barcode = line.barcode ?: "",
                    qty = line.mainUnitQty(),
                    unitCost = line.mainUnitRate(),
                    amount = line.amount,
                    unit = line.unit
                )
            })
            // FIX 2: for-loop se return@forEach error khatam
            for (line in lines) {
                val barcode = line.barcode ?: continue
                val before = db.productDao().find(barcode)
                val purchasedBaseQty = line.baseQty().roundToInt()
                if (purchasedBaseQty <= 0) continue
                db.productDao().increase(barcode, purchasedBaseQty)
                if (before != null) {
                    val oldStock = before.stock
                    val oldCost = before.cost
                    val div = when {
                        line.tertiaryUnit.isNotEmpty() && line.secondaryUnit.isNotEmpty() -> line.secondaryUnitQty * line.tertiaryUnitQty
                        line.secondaryUnit.isNotEmpty() -> line.secondaryUnitQty
                        else -> 1.0
                    }
                    val purchaseRatePerBase = if (div != 0.0) line.mainUnitRate() / div else line.mainUnitRate()
                    val newCost = if (oldStock <= 0) purchaseRatePerBase else ((oldStock * oldCost) + (purchasedBaseQty * purchaseRatePerBase)) / (oldStock + purchasedBaseQty).toDouble()
                    db.productDao().updateCost(barcode, newCost)
                }
            }
            val outstanding = grandTotal - amountPaid
            if (supplierId != null && outstanding > 0) {
                db.supplierDao().addBalance(supplierId!!, outstanding)
            }
            if (supplierId != null && amountPaid > 0) {
                db.paymentDao().insert(
                    Payment(
                        reference = billNo,
                        partyType = "supplier",
                        partyId = supplierId,
                        amount = amountPaid,
                        method = paymentMethod,
                        note = if (original != null) "Purchase payment (edited)" else "Purchase payment"
                    )
                )
            }
            if (amountPaid > 0) {
                db.cashTransactionDao().insert(
                    CashTransaction(
                        type = "OUT",
                        method = paymentMethod.lowercase(Locale.ROOT),
                        amount = amountPaid,
                        reason = "Purchase",
                        reference = billNo
                    )
                )
            }
            suppressDraftSave = true
            Toast.makeText(this@PurchaseActivity, if (original != null) "Purchase updated" else "Purchase saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // ===== DUMMY PLACEHOLDERS - aapki purani file me ye already hain, compile ke liye rakhe hain =====
    private fun handleScannedItems(json: String) {}
    private fun hideKeyboard() {}
    private fun clearDraft() {}
    private fun roundedBg(color: String, radius: Int) = GradientDrawable().apply { setColor(Color.parseColor(color)); cornerRadius = radius.toFloat() }
    private fun applyElevation(v: View, dp: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            v.elevation = dp * resources.displayMetrics.density
            v.outlineProvider = ViewOutlineProvider.BACKGROUND
            v.clipToOutline = true
        }
    }
    private fun openBillPreview(billNo: String, forSaving: Boolean, party: String = "", grandTotal: Double = 0.0, discount: Double = 0.0, amountPaid: Double = 0.0, paymentMethod: String = "Cash") {}
}
