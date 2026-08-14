package com.grocerypos.v11.ui

import android.app.DatePickerDialog
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
import com.grocerypos.v11.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    val secondaryUnitQty: Double
)

private fun genBillNo(): String = "PUR" + System.currentTimeMillis()

class PurchaseActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BILL_NO = "billNo"
    }

    private val bg = "#F3F4F9"
    private val cardWhite = "#FFFFFF"
    private val textDark = "#1A1D2E"
    private val textMuted = "#8A8FA3"
    private val orange = "#EF6C00"
    private val blue = "#1565C0"
    private val green = "#2E7D32"
    private val border = "#E6E8F0"

    private lateinit var dateValueText: TextView
    private lateinit var partyName: AutoCompleteTextView
    private lateinit var itemEntrySection: LinearLayout
    private lateinit var addItemsTrigger: TextView
    private lateinit var itemName: AutoCompleteTextView
    private lateinit var qty: EditText
    private lateinit var unitSpinner: Spinner
    private lateinit var unitToggleRow: LinearLayout
    private lateinit var rate: EditText
    private lateinit var conversionInfo: TextView
    private lateinit var totalAmountText: TextView
    private lateinit var itemsContainer: LinearLayout
    private lateinit var grandTotalText: TextView
    private lateinit var cashBtn: Button
    private lateinit var creditBtn: Button
    private lateinit var paymentSection: LinearLayout
    private lateinit var paidInput: EditText
    private lateinit var paymentMethodSpinner: Spinner
    private lateinit var saveButton: Button
    private var isCashPurchase = true

    private var suppliers = listOf<Supplier>()
    private var products = listOf<Product>()
    private var allUnits = listOf("pcs", "kg", "box", "dozen")
    private val lines = mutableListOf<PurchaseLine>()
    private var purchaseDateMillis = System.currentTimeMillis()
    private var selectedProduct: Product? = null

    private var editBillNo: String? = null
    private var originalPurchase: Purchase? = null
    private var originalItems: List<PurchaseItem> = emptyList()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        editBillNo = intent.getStringExtra(EXTRA_BILL_NO)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 28)
            setBackgroundColor(Color.parseColor(bg))
        }

        // ================= HEADER =================
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 0, 4, 20)
            addView(TextView(this@PurchaseActivity).apply {
                text = if (editBillNo != null) "Edit Purchase" else "Purchase"
                textSize = 20f
                setTextColor(Color.parseColor(textDark))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@PurchaseActivity).apply {
                text = "History"
                textSize = 12.5f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(24, 12, 24, 12)
                background = roundedBg(blue, 30)
                setOnClickListener {
                    startActivity(Intent(this@PurchaseActivity, PurchaseHistoryActivity::class.java))
                }
            })
        })

        // ================= DATE =================
        val dateBox = premiumCard()
        dateBox.setOnClickListener { openDatePicker() }
        dateBox.addView(TextView(this).apply { text = "Date"; textSize = 12f; setTextColor(Color.parseColor(textMuted)) })
        val dateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dateValueText = TextView(this).apply {
            text = formatDate(purchaseDateMillis)
            textSize = 15f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, 4, 0, 0)
        }
        dateRow.addView(dateValueText)
        dateRow.addView(TextView(this).apply { text = "▾"; textSize = 15f; setTextColor(Color.parseColor(blue)) })
        dateBox.addView(dateRow)
        root.addView(dateBox)

        // ================
